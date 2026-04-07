package myssh

import (
	"bufio"
	"crypto/tls"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"os"
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
	// --- 新增：自定义 HTTP Payload ---
	HttpPayload string `json:"http_payload"`
}

type GlobalConfig struct {
	// --- 远程DNS查询服务器 8.8.8.8:53 ---
	LocalDnsServer       string   `json:"local_dns_server"`
	RemoteDnsServer       string   `json:"remote_dns_server"`
	GeoSiteFilePath string   `json:"geosite_filepath"`
	GeoIPFilePath   string   `json:"geoip_filepath"`
	
	// 🌟 强烈建议加上这两个字段，方便在 JSON 中动态配置多个直连标签 (如果不传，代码中会给默认值 cn)
	DirectSiteTags  []string `json:"direct_site_tags"`
	DirectIPTags    []string `json:"direct_ip_tags"`
}

var (
	sshClient    *ssh.Client
	socksServer  *socks5.Server
	mu           sync.Mutex
	globalConfig GlobalConfig
	// 全局路由管理器实例 (供外部 Handler 调用)
    globalRouter *GeoRouter
	// 🌟 新增：UDP NAT 会话表，用于复用直连的 UDP Socket
	udpNatMap sync.Map // key: string (ClientAddr-TargetAddr), value: *net.UDPConn
)

// LoadGlobalConfigFromJson 解析 JSON 字符串并载入全局配置
// 返回值: 
//   0: 成功
//  -1: 未加载任何分流规则 (退化为全局代理)
//  -2: JSON 解析失败
func LoadGlobalConfigFromJson(configJson string) int {
	// 尝试将传入的 JSON 字符串解析到 GlobalConfig 结构体中
	if err := json.Unmarshal([]byte(configJson), &globalConfig); err != nil {
		log.Printf("%s [Config] ❌ 解析全局配置 JSON 失败: %v\n传入的JSON内容: %s", TAG, err, configJson)
		return -2
	}

	// 解析成功后，复用我们之前写好的核心加载逻辑
	return loadGlobalConfig(globalConfig)
}

// loadGlobalConfig 载入全局配置并初始化分流路由
// 返回值: 0 表示成功, -1 表示未加载任何规则 (所有流量将默认走代理)
func loadGlobalConfig(cfg GlobalConfig) int {
	mu.Lock()
	defer mu.Unlock()

	// 1. 设置默认兜底值
	if cfg.LocalDnsServer == "" {
		cfg.LocalDnsServer = "223.5.5.5:53"
	}
	if cfg.RemoteDnsServer == "" {
		cfg.RemoteDnsServer = "8.8.8.8:53"
	}
	if cfg.GeoSiteFilePath == "" {
		cfg.GeoSiteFilePath = "geosite.dat"
	}
	if cfg.GeoIPFilePath == "" {
		cfg.GeoIPFilePath = "geoip.dat"
	}
	// 如果 JSON 没传 tags，默认使用 "cn"
	if len(cfg.DirectSiteTags) == 0 {
		cfg.DirectSiteTags = []string{"cn"}
	}
	if len(cfg.DirectIPTags) == 0 {
		cfg.DirectIPTags = []string{"cn"}
	}

	// 2. 保存到全局变量
	log.Printf("%s [Config] ✅ 已应用全局配置: LocalDNS=[%s], RemoteDNS=[%s], GeoSite=[%s], GeoIP=[%s]", TAG, cfg.LocalDnsServer, cfg.RemoteDnsServer, cfg.GeoSiteFilePath, cfg.GeoIPFilePath)

	// 3. 实例化空的路由器，准备加载数据
	globalRouter = newGeoRouter()

	// ==========================================
	// 4. 检查并按需加载 GeoSite (域名规则)
	// ==========================================
	if _, err := os.Stat(cfg.GeoSiteFilePath); err == nil {
		log.Printf("%s [Config] 正在加载 GeoSite 规则... Tags: %v", TAG, cfg.DirectSiteTags)
		// 🌟 传入 []string 数组
		if err := globalRouter.LoadGeoSite(cfg.GeoSiteFilePath, cfg.DirectSiteTags); err != nil {
			log.Printf("%s [Config] ❌ 加载 GeoSite 失败 (可能是文件损坏或找不到标签): %v", TAG, err)
		} else {
			log.Printf("%s [Config] ✅ GeoSite 加载成功", TAG)
		}
	} else if os.IsNotExist(err) {
		log.Printf("%s [Config] ⚠️ 未找到 GeoSite 文件 (%s)，将跳过加载【域名直连分流】规则", TAG, cfg.GeoSiteFilePath)
	}

	// ==========================================
	// 5. 检查并按需加载 GeoIP (IP 规则)
	// ==========================================
	if _, err := os.Stat(cfg.GeoIPFilePath); err == nil {
		log.Printf("%s [Config] 正在加载 GeoIP 规则... Tags: %v", TAG, cfg.DirectIPTags)
		// 🌟 传入 []string 数组
		if err := globalRouter.LoadGeoIP(cfg.GeoIPFilePath, cfg.DirectIPTags); err != nil {
			log.Printf("%s [Config] ❌ 加载 GeoIP 失败 (可能是文件损坏或找不到标签): %v", TAG, err)
		} else {
			log.Printf("%s [Config] ✅ GeoIP 加载成功", TAG)
		}
	} else if os.IsNotExist(err) {
		log.Printf("%s [Config] ⚠️ 未找到 GeoIP 文件 (%s)，将跳过加载【IP直连分流】规则", TAG, cfg.GeoIPFilePath)
	}

	return 0
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
		
		// 1. 获取 payload，如果没传，则给一个标准的默认值
		payload := cfg.HttpPayload
		if payload == "" {
			payload = "CONNECT [host_and_port] HTTP/1.1[crlf]Host: [host][crlf][crlf]"
		}

		// 2. 按照规则进行字符串替换
		payload = strings.ReplaceAll(payload, "[host_and_port]", cfg.SshAddr)
		payload = strings.ReplaceAll(payload, "[host]", cfg.CustomHost)
		payload = strings.ReplaceAll(payload, "[crlf]", "\r\n")

		// 3. 将最终的 payload 发送给代理服务器
		log.Printf("%s [Tunnel] 发送的 HTTP Payload:\n%s", TAG, payload)
		_, err := baseConn.Write([]byte(payload))
		if err != nil {
			baseConn.Close()
			return nil, err
		}

		// 4. 读取代理服务器的响应
		br := bufio.NewReader(baseConn)
		line, _ := br.ReadString('\n')
		if !strings.Contains(line, "200") {
			baseConn.Close()
			log.Printf("%s [Tunnel] ❌ HTTP 代理拒绝连接: %s", TAG, line)
			return nil, fmt.Errorf("HTTP Proxy Refused: %s", line)
		}
		
		// 消耗掉剩下的 HTTP 头信息
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
func (h *SshProxyHandler) TCPHandle(s *socks5.Server, c *net.TCPConn, r *socks5.Request) error {
	// --- 处理 UDP ASSOCIATE (握手) 请求 ---
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

		// 🌟 1. 拆分原始目标的 Host 和 Port
		host, port, err := net.SplitHostPort(target)
		if err != nil {
			host = target // fallback，极少发生
		}

		// 🌟 2. 调用路由分流逻辑，获取决断和确切要连接的拨号地址
		var isDirect bool
		var dialHost string

		if globalRouter != nil {
			res := globalRouter.ShouldDirect(host)
			isDirect = res.IsDirect
			dialHost = res.DialHost
		} else {
			// 防御性编程：如果没有初始化路由引擎，默认全部走代理
			isDirect = false
			dialHost = host
		}

		var remote net.Conn
		var dialErr error

		// 🌟 3. 根据分流结果进行双向拨号选择
		if isDirect {
			// ==============================
			// 🌍 路由：直连 (锁定安全 IP)
			// ==============================
			// 使用分流器返回的确切 IP 重新拼接目标地址，防止系统底层再次解析导致污染
			dialTarget := net.JoinHostPort(dialHost, port)
			
			log.Printf("%s [ROUTER] 🟢 命中直连规则，使用锁定地址本地拨号 -> %s", TAG, dialTarget)
			remote, dialErr = net.DialTimeout("tcp", dialTarget, 5*time.Second)
			if dialErr != nil {
				log.Printf("%s [Local-Dial] ❌ 本地拨号失败 (%s): %v", TAG, dialTarget, dialErr)
			}
		} else {
			// ==============================
			// 🛡️ 路由：代理 (交给远端解析)
			// ==============================
			// 走代理时，使用原始带域名的 target，让远端 SSH 服务器就近解析最佳 CDN 节点
			log.Printf("%s [ROUTER] 🛡️ 命中代理规则，交由远端 SSH 解析并拨号 -> %s", TAG, target)
			remote, dialErr = client.Dial("tcp", target)
			if dialErr != nil {
				log.Printf("%s [SSH-Dial] ❌ 远端拨号失败 (%s): %v", TAG, target, dialErr)
			}
		}

		// 4. 统一处理拨号失败情况
		if dialErr != nil {
			rep := socks5.NewReply(socks5.RepHostUnreachable, socks5.ATYPIPv4, []byte{0, 0, 0, 0}, []byte{0, 0})
			_, _ = rep.WriteTo(c)
			return dialErr
		}
		
		log.Printf("%s [SOCKS5-TCP] ✅ 拨号成功，开始双向数据转发", TAG)
		defer func() {
			remote.Close()
			log.Printf("%s [SOCKS5-TCP] 连接已断开，释放资源 -> 目标: %s", TAG, target)
		}()

		// 5. 告诉本地客户端 SOCKS5 握手成功
		rep := socks5.NewReply(socks5.RepSuccess, socks5.ATYPIPv4, []byte{0, 0, 0, 0}, []byte{0, 0})
		if _, err := rep.WriteTo(c); err != nil {
			log.Printf("%s [SOCKS5-TCP] ❌ 回复客户端 SOCKS5 响应失败: %v", TAG, err)
			return err
		}

		// 6. 双向流量转发
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

	// ==============================
	// 1. DNS 特判：强制走 SSH 远端解析防止污染
	// ==============================
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
			// log.Printf("%s [SOCKS5-UDP] ✅ DNS 解析结果已成功封装回 UDP", TAG)
		}
		return err
	}

	// ==============================
	// 2. 解析目标地址
	// ==============================
	var targetHost string
	switch d.Atyp {
	case socks5.ATYPIPv4, socks5.ATYPIPv6:
		targetHost = net.IP(d.DstAddr).String()
	case socks5.ATYPDomain:
		if len(d.DstAddr) > 1 {
			targetHost = string(d.DstAddr[1:])
		} else {
			targetHost = "unknown_domain"
		}
	default:
		log.Printf("%s [SOCKS5-UDP] ⚠️ 未知地址类型", TAG)
		return nil
	}

	// ==============================
	// 3. 路由判定
	// ==============================
	var isDirect bool
	var dialHost string

	if globalRouter != nil {
		res := globalRouter.ShouldDirect(targetHost)
		isDirect = res.IsDirect
		dialHost = res.DialHost
	} else {
		isDirect = false
	}

	if !isDirect {
		// 代理流量：丢弃 (因为远端是 SSH，原生不支持 UDP 转发)
		log.Printf("%s [SOCKS5-UDP] ⚠️ 拦截并丢弃代理 UDP 数据包 -> %s:%d (SSH不支持UDP)", TAG, targetHost, dstPort)
		return nil
	}

	// ==============================
	// 4. 🌍 直连流量：本地 UDP 转发 (带 NAT 保持)
	// ==============================
	targetAddrStr := fmt.Sprintf("%s:%d", dialHost, dstPort)
	// NAT 映射键：客户端地址 <-> 最终目标地址
	sessionKey := addr.String() + "<->" + targetAddrStr

	var uc *net.UDPConn

	// 尝试从 NAT 表中复用已有的 UDP 连接
	if val, ok := udpNatMap.Load(sessionKey); ok {
		uc = val.(*net.UDPConn)
	} else {
		// 如果不存在，则新建本地 UDP 连接
		raddr, err := net.ResolveUDPAddr("udp", targetAddrStr)
		if err != nil {
			log.Printf("%s [SOCKS5-UDP] ❌ 解析直连 UDP 地址失败: %v", TAG, err)
			return err
		}

		uc, err = net.DialUDP("udp", nil, raddr)
		if err != nil {
			log.Printf("%s [SOCKS5-UDP] ❌ 建立本地 UDP 连接失败: %v", TAG, err)
			return err
		}
		
		log.Printf("%s [ROUTER] 🟢 建立本地 UDP 直连会话 -> 目标: %s", TAG, targetAddrStr)
		udpNatMap.Store(sessionKey, uc)

		// 🚀 启动协程：监听该 UDP 连接的远端响应，并原样封装回 SOCKS5 客户端
		go func(conn *net.UDPConn, key string, dstAtyp byte, dstAddr []byte, dstPortBytes []byte, clientAddr *net.UDPAddr) {
			defer conn.Close()
			defer udpNatMap.Delete(key) // 会话结束，清理 NAT 表
			
			buf := make([]byte, 65535) // UDP 最大包长
			for {
				// 设置读超时 (如果 60 秒没有收到响应，认为会话结束，释放端口)
				conn.SetReadDeadline(time.Now().Add(60 * time.Second))
				n, _, err := conn.ReadFromUDP(buf)
				if err != nil {
					// 超时或连接关闭，退出协程
					break 
				}
				
				// 将收到的真实 UDP 响应数据封装回 SOCKS5 Datagram 格式
				res := socks5.NewDatagram(dstAtyp, dstAddr, dstPortBytes, buf[:n])
				s.UDPConn.WriteToUDP(res.Bytes(), clientAddr)
			}
			log.Printf("%s [ROUTER] 🔴 UDP 直连会话已释放 -> %s", TAG, targetAddrStr)
		}(uc, sessionKey, d.Atyp, d.DstAddr, d.DstPort, addr)
	}

	// 5. 将客户端发来的数据写入对应的远端 UDP Socket
	// 每次写入都刷新一次保活机制（如果需要的话，目前通过协程的 ReadDeadline 足够控制生命周期）
	_, err := uc.Write(d.Data)
	if err != nil {
		log.Printf("%s [SOCKS5-UDP] ❌ 写入直连 UDP 数据失败: %v", TAG, err)
	}
	
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

	srv, err := socks5.NewClassicServer(cfg.LocalAddr, "", "", "", 0, 60)
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

// wsConnAdapter
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

func (c *wsConnAdapter) SetDeadline(t time.Time) error       { return c.UnderlyingConn().SetDeadline(t) }
func (c *wsConnAdapter) SetReadDeadline(t time.Time) error  { return c.UnderlyingConn().SetReadDeadline(t) }
func (c *wsConnAdapter) SetWriteDeadline(t time.Time) error { return c.UnderlyingConn().SetWriteDeadline(t) }