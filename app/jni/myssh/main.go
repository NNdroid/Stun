package myssh

import (
	"bufio"
	"crypto/tls"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"github.com/miekg/dns"
	"github.com/txthinking/socks5"
	"golang.org/x/crypto/ssh"
)

const TAG = "[GoMySsh]"

type ProxyConfig struct {
	LocalAddr  string `json:"local_addr"`
	SshAddr    string `json:"ssh_addr"`
	User       string `json:"user"`
	Pass       string `json:"pass"`
	TunnelType string `json:"tunnel_type"`
	ProxyAddr  string `json:"proxy_addr"`
	CustomHost string `json:"custom_host"`
}

var (
	sshClient   *ssh.Client
	socksServer *socks5.Server
	mu          sync.Mutex
)

// handleSshTcpDns 通过 SSH 隧道进行 DNS-over-TCP 查询
func handleSshTcpDns(requestMsg *dns.Msg) (*dns.Msg, error) {
	mu.Lock()
	client := sshClient
	mu.Unlock()

	if client == nil {
		return nil, fmt.Errorf("ssh client not ready")
	}

	log.Printf("%s [DNS-over-TCP] 准备通过 SSH 隧道向 8.8.8.8:53 发起 TCP DNS 解析请求...", TAG)
	
	// 强制连接远程 DNS 的 TCP 端口 (53)
	conn, err := client.Dial("tcp", "8.8.8.8:53")
	if err != nil {
		log.Printf("%s [DNS-over-TCP] ❌ 连接远程 DNS 服务器失败: %v", TAG, err)
		return nil, err
	}
	defer conn.Close()

	tcpConn := &dns.Conn{Conn: conn}
	if err := tcpConn.WriteMsg(requestMsg); err != nil {
		log.Printf("%s [DNS-over-TCP] ❌ 发送 DNS 请求失败: %v", TAG, err)
		return nil, err
	}

	reply, err := tcpConn.ReadMsg()
	if err != nil {
		log.Printf("%s [DNS-over-TCP] ❌ 读取 DNS 响应失败: %v", TAG, err)
		return nil, err
	}
	
	log.Printf("%s [DNS-over-TCP] ✅ 成功获取 DNS 解析响应", TAG)
	return reply, nil
}

// dialTunnel 处理底层传输隧道
func dialTunnel(cfg ProxyConfig) (net.Conn, error) {
	target := cfg.ProxyAddr
	if strings.ToLower(cfg.TunnelType) == "base" || cfg.TunnelType == "" {
		target = cfg.SshAddr
	}

	log.Printf("%s [Tunnel] 1. 开始建立底层连接，目标地址: %s, 模式: %s", TAG, target, cfg.TunnelType)
	dialer := &net.Dialer{Timeout: 10 * time.Second}
	baseConn, err := dialer.Dial("tcp", target)
	if err != nil {
		log.Printf("%s [Tunnel] ❌ 底层 TCP 连接失败: %v", TAG, err)
		return nil, err
	}
	log.Printf("%s [Tunnel] ✅ 底层 TCP 连接建立成功", TAG)

	switch strings.ToLower(cfg.TunnelType) {
	case "tls":
		log.Printf("%s [Tunnel] 2. 准备进行 TLS (SNI Proxy) 握手, 伪装 Host: %s", TAG, cfg.CustomHost)
		tlsConn := tls.Client(baseConn, &tls.Config{
			ServerName:         cfg.CustomHost,
			InsecureSkipVerify: true,
		})
		if err := tlsConn.Handshake(); err != nil {
			baseConn.Close()
			log.Printf("%s [Tunnel] ❌ TLS 握手失败: %v", TAG, err)
			return nil, err
		}
		log.Printf("%s [Tunnel] ✅ TLS 握手成功", TAG)
		return tlsConn, nil
	case "ws", "wss":
		scheme := "ws"
		if strings.ToLower(cfg.TunnelType) == "wss" {
			scheme = "wss"
		}
		log.Printf("%s [Tunnel] 2. 准备进行 %s 握手, 伪装 Host: %s", TAG, strings.ToUpper(scheme), cfg.CustomHost)
		u := url.URL{Scheme: scheme, Host: cfg.ProxyAddr, Path: "/"}
		wsDialer := &websocket.Dialer{
			NetDial:         func(n, a string) (net.Conn, error) { return baseConn, nil },
			TLSClientConfig: &tls.Config{ServerName: cfg.CustomHost, InsecureSkipVerify: true},
		}
		ws, _, err := wsDialer.Dial(u.String(), http.Header{"Host": {cfg.CustomHost}})
		if err != nil {
			baseConn.Close()
			log.Printf("%s [Tunnel] ❌ WebSocket 握手失败: %v", TAG, err)
			return nil, err
		}
		log.Printf("%s [Tunnel] ✅ WebSocket 握手成功", TAG)
		return &wsConnAdapter{Conn: ws}, nil
	case "http":
		log.Printf("%s [Tunnel] 2. 准备发送 HTTP CONNECT 代理请求, 目标 SSH: %s", TAG, cfg.SshAddr)
		fmt.Fprintf(baseConn, "CONNECT %s HTTP/1.1\r\nHost: %s\r\n\r\n", cfg.SshAddr, cfg.CustomHost)
		br := bufio.NewReader(baseConn)
		line, _ := br.ReadString('\n')
		if !strings.Contains(line, "200") {
			baseConn.Close()
			log.Printf("%s [Tunnel] ❌ HTTP 代理拒绝连接: %s", TAG, line)
			return nil, fmt.Errorf("HTTP Proxy Refused: %s", line)
		}
		for {
			l, _ := br.ReadString('\n')
			if l == "\r\n" || l == "" {
				break
			}
		}
		log.Printf("%s [Tunnel] ✅ HTTP CONNECT 代理建立成功", TAG)
		return baseConn, nil
	default:
		return baseConn, nil
	}
}

// ---------------------------------------------------------
// txthinking/socks5 的 Handler 实现
// ---------------------------------------------------------
type SshProxyHandler struct{}

// TCPHandle 处理 SOCKS5 的 TCP 代理请求
// TCPHandle 处理 SOCKS5 的 TCP 代理请求
func (h *SshProxyHandler) TCPHandle(s *socks5.Server, c *net.TCPConn, r *socks5.Request) error {
	// --- 新增：专门处理 UDP ASSOCIATE (握手) 请求 ---
	if r.Cmd == socks5.CmdUDP {
		log.Printf("%s [SOCKS5-TCP] 🟢 接收到 UDP ASSOCIATE 握手请求 (客户端准备发送 UDP)", TAG)
		
		// 告诉客户端，它应该把 UDP 数据包发到我们本地的哪个地址
		localAddr := c.LocalAddr().(*net.TCPAddr)
		atyp := byte(socks5.ATYPIPv4)
		ip := localAddr.IP.To4()
		if ip == nil {
			atyp = socks5.ATYPIPv6
			ip = localAddr.IP.To16()
		}
		portBytes := make([]byte, 2)
		binary.BigEndian.PutUint16(portBytes, uint16(localAddr.Port))
		
		rep := socks5.NewReply(socks5.RepSuccess, atyp, ip, portBytes)
		if _, err := rep.WriteTo(c); err != nil {
			return err
		}
		
		// 核心机制：SOCKS5 协议规定，UDP 转发的生命周期与这条 TCP 握手连接绑定。
		// 只要这条 TCP 不断开，UDP 通道就一直有效。所以我们要在这里阻塞住。
		io.Copy(io.Discard, c)
		log.Printf("%s [SOCKS5-TCP] 🔴 UDP ASSOCIATE 握手连接已释放", TAG)
		return nil
	}

	// --- 处理普通的 TCP CONNECT 请求 ---
	if r.Cmd == socks5.CmdConnect {
		mu.Lock()
		client := sshClient
		mu.Unlock()

		if client == nil {
			return fmt.Errorf("ssh client disconnected")
		}

		target := r.Address()
		log.Printf("%s [SOCKS5-TCP] 客户端请求 TCP 连接 -> 目标: %s", TAG, target)

		log.Printf("%s [SSH-Dial] 正在通过远端 SSH 拨号 -> %s", TAG, target)
		// 1. 通过 SSH 隧道拨号到目标服务器
		remote, err := client.Dial("tcp", target)
		if err != nil {
			log.Printf("%s [SSH-Dial] ❌ 远端拨号失败 (%s): %v", TAG, target, err)
			rep := socks5.NewReply(socks5.RepHostUnreachable, socks5.ATYPIPv4, []byte{0, 0, 0, 0}, []byte{0, 0})
			_, _ = rep.WriteTo(c)
			return err
		}
		
		log.Printf("%s [SSH-Dial] ✅ 远端拨号成功，开始双向数据转发 -> %s", TAG, target)
		defer func() {
			remote.Close()
			log.Printf("%s [SOCKS5-TCP] 连接已断开，释放资源 -> 目标: %s", TAG, target)
		}()

		// 2. 告诉本地客户端 SOCKS5 握手成功
		rep := socks5.NewReply(socks5.RepSuccess, socks5.ATYPIPv4, []byte{0, 0, 0, 0}, []byte{0, 0})
		if _, err := rep.WriteTo(c); err != nil {
			log.Printf("%s [SOCKS5-TCP] ❌ 回复客户端 SOCKS5 响应失败: %v", TAG, err)
			return err
		}

		// 3. 双向流量转发
		errc := make(chan error, 2)
		go func() {
			_, err := io.Copy(remote, c)
			errc <- err
		}()
		go func() {
			_, err := io.Copy(c, remote)
			errc <- err
		}()
		<-errc
		return nil
	}

	// 其他不支持的命令 (如 Bind 0x02)
	log.Printf("%s [SOCKS5-TCP] ⚠️ 拦截到不支持的 SOCKS5 命令: 0x%02x", TAG, r.Cmd)
	rep := socks5.NewReply(socks5.RepCommandNotSupported, socks5.ATYPIPv4, []byte{0, 0, 0, 0}, []byte{0, 0})
	_, _ = rep.WriteTo(c)
	return fmt.Errorf("unsupported command: %v", r.Cmd)
}

// UDPHandle 处理 SOCKS5 的 UDP ASSOCIATE 数据包
func (h *SshProxyHandler) UDPHandle(s *socks5.Server, addr *net.UDPAddr, d *socks5.Datagram) error {
	dstPort := binary.BigEndian.Uint16(d.DstPort)

	if dstPort == 53 {
		log.Printf("%s [SOCKS5-UDP] 🟢 拦截到 UDP 53 端口 (DNS) 请求，转交 SSH TCP 解析处理", TAG)
		reqMsg := new(dns.Msg)
		if err := reqMsg.Unpack(d.Data); err != nil {
			log.Printf("%s [SOCKS5-UDP] ❌ 解析原生 DNS 数据包失败: %v", TAG, err)
			return err
		}

		replyMsg, err := handleSshTcpDns(reqMsg)
		if err != nil || replyMsg == nil {
			return err
		}

		replyData, err := replyMsg.Pack()
		if err != nil {
			return err
		}

		res := socks5.NewDatagram(d.Atyp, d.DstAddr, d.DstPort, replyData)
		_, err = s.UDPConn.WriteToUDP(res.Bytes(), addr)
		if err == nil {
			log.Printf("%s [SOCKS5-UDP] ✅ DNS 解析结果已成功封装回 UDP 返回给客户端", TAG)
		}
		return err
	}

	// === 新增：解析并详细记录非 53 端口的 UDP 流量 ===
	var targetHost string
	switch d.Atyp {
	case socks5.ATYPIPv4, socks5.ATYPIPv6:
		targetHost = net.IP(d.DstAddr).String()
	case socks5.ATYPDomain:
		// 域名的第一个字节是长度，后面是真实的域名内容
		if len(d.DstAddr) > 1 {
			targetHost = string(d.DstAddr[1:])
		} else {
			targetHost = "unknown_domain"
		}
	default:
		targetHost = "unknown_type"
	}

	log.Printf("%s [SOCKS5-UDP] ⚠️ 丢弃非 DNS UDP 数据包 -> 目标: %s:%d (原因: SSH 不支持 UDP 转发)", TAG, targetHost, dstPort)
	return nil
}

func StartSshTProxy(configJson string) int {
	StopSshTProxy()

	var cfg ProxyConfig
	if err := json.Unmarshal([]byte(configJson), &cfg); err != nil {
		log.Printf("%s [Core] ❌ 解析配置 JSON 失败: %v", TAG, err)
		return -1
	}

	log.Printf("%s [Core] ==================== 启动代理引擎 ====================", TAG)

	conn, err := dialTunnel(cfg)
	if err != nil {
		return -2
	}

	sshConfig := &ssh.ClientConfig{
		User:            cfg.User,
		Auth:            []ssh.AuthMethod{ssh.Password(cfg.Pass)},
		HostKeyCallback: ssh.InsecureIgnoreHostKey(),
		Timeout:         15 * time.Second,
	}

	log.Printf("%s [SSH] 3. 准备与远端建立 SSH 安全认证 (User: %s)", TAG, cfg.User)
	scc, chans, reqs, err := ssh.NewClientConn(conn, cfg.SshAddr, sshConfig)
	if err != nil {
		conn.Close()
		log.Printf("%s [SSH] ❌ SSH 握手或认证失败: %v", TAG, err)
		return -3
	}
	log.Printf("%s [SSH] ✅ SSH 隧道握手并认证成功！", TAG)

	mu.Lock()
	sshClient = ssh.NewClient(scc, chans, reqs)
	mu.Unlock()

	srv, err := socks5.NewClassicServer(cfg.LocalAddr, "", "", "", 60, 60)
	if err != nil {
		log.Printf("%s [SOCKS5] ❌ 创建 SOCKS5 服务器实例失败: %v", TAG, err)
		return -4
	}

	mu.Lock()
	socksServer = srv
	mu.Unlock()

	handler := &SshProxyHandler{}

	go func() {
		log.Printf("%s [SOCKS5] 🚀 SOCKS5 本地代理服务已启动并监听于: %s", TAG, cfg.LocalAddr)
		if err := srv.ListenAndServe(handler); err != nil && !strings.Contains(err.Error(), "closed network connection") {
			log.Printf("%s [SOCKS5] ❌ 服务异常退出: %v", TAG, err)
		}
	}()

	return 0
}

func StopSshTProxy() {
	mu.Lock()
	defer mu.Unlock()
	log.Printf("%s [Core] 正在停止资源...", TAG)
	if socksServer != nil {
		socksServer.Shutdown()
		socksServer = nil
	}
	if sshClient != nil {
		sshClient.Close()
		sshClient = nil
	}
	log.Printf("%s [Core] 代理引擎已停止", TAG)
}

// wsConnAdapter 保持不变
type wsConnAdapter struct {
	*websocket.Conn
	readMu  sync.Mutex
	reader  io.Reader
	writeMu sync.Mutex
}

func (c *wsConnAdapter) Read(b []byte) (int, error) {
	c.readMu.Lock()
	defer c.readMu.Unlock()
	for {
		if c.reader == nil {
			_, r, err := c.NextReader()
			if err != nil {
				return 0, err
			}
			c.reader = r
		}
		n, err := c.reader.Read(b)
		if err == io.EOF {
			c.reader = nil
			if n > 0 {
				return n, nil
			}
			continue
		}
		return n, err
	}
}

func (c *wsConnAdapter) Write(b []byte) (int, error) {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	err := c.WriteMessage(websocket.BinaryMessage, b)
	if err != nil {
		return 0, err
	}
	return len(b), nil
}

func (c *wsConnAdapter) SetDeadline(t time.Time) error      { return c.UnderlyingConn().SetDeadline(t) }
func (c *wsConnAdapter) SetReadDeadline(t time.Time) error  { return c.UnderlyingConn().SetReadDeadline(t) }
func (c *wsConnAdapter) SetWriteDeadline(t time.Time) error { return c.UnderlyingConn().SetWriteDeadline(t) }