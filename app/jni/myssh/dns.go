package myssh

import (
	"fmt"
	"log"
	"sync"
	"net"
	"time"

	"github.com/miekg/dns"
	"golang.org/x/crypto/ssh"
)

type dnsCacheEntry struct {
	msg       *dns.Msg
	expiresAt time.Time
}

var (
	// dnsConnPool 作为复用池，最大保持 10 个空闲连接 (可根据并发量调整)
	dnsConnPool = make(chan *dns.Conn, 10)
	dnsCache    = make(map[string]dnsCacheEntry)
	dnsCacheMu  sync.RWMutex
)

// init 函数会在包加载时自动运行，启动后台清理任务
func init() {
	go func() {
		// 每 60 秒主动清理一次已过期的缓存，释放内存
		ticker := time.NewTicker(60 * time.Second)
		for range ticker.C {
			dnsCacheMu.Lock()
			now := time.Now()
			deleted := 0
			for k, v := range dnsCache {
				if now.After(v.expiresAt) {
					delete(dnsCache, k)
					deleted++
				}
			}
			dnsCacheMu.Unlock()
			if deleted > 0 {
				log.Printf("[Cache-GC] ♻️ 主动清理了 %d 条过期的 DNS 缓存", deleted)
			}
		}
	}()
}

// getDnsConn 从池中获取或新建 SSH TCP 连接
func getDnsConn(client *ssh.Client) (*dns.Conn, error) {
	select {
	case conn := <-dnsConnPool:
		return conn, nil // 成功复用
	default:
		// 池中没有空闲连接，新建一个
		if globalConfig.DnsServer == "" {
			globalConfig.DnsServer = "8.8.8.8:53"
		}
		netConn, err := client.Dial("tcp", globalConfig.DnsServer)
		if err != nil {
			return nil, err
		}
		return &dns.Conn{Conn: netConn}, nil
	}
}

// putDnsConn 将健康的连接放回池中复用
func putDnsConn(conn *dns.Conn) {
	select {
	case dnsConnPool <- conn:
		// 成功放回池中
	default:
		// 池满了，直接关闭丢弃
		conn.Close()
	}
}

// handleSshTcpDns 通过 SSH 隧道进行 DNS-over-TCP 查询
func handleSshTcpDns(requestMsg *dns.Msg) (*dns.Msg, error) {
	domainName := "unknown"
	qtypeStr := "unknown"
	var cacheKey string

	if len(requestMsg.Question) > 0 {
		q := requestMsg.Question[0]
		domainName = q.Name
		qtypeStr = dns.TypeToString[q.Qtype]
		cacheKey = fmt.Sprintf("%s-%d", domainName, q.Qtype) 
	}

	// --- 1. 检查缓存 ---
	if cacheKey != "" {
		dnsCacheMu.RLock()
		entry, found := dnsCache[cacheKey]
		dnsCacheMu.RUnlock()

		if found {
			if time.Now().Before(entry.expiresAt) {
				log.Printf("%s [DNS-over-TCP] ⚡ 命中缓存: 域名=[%s] 类型=[%s] MsgID=[%d]", TAG, domainName, qtypeStr, requestMsg.MsgHdr.Id)
				cachedReply := entry.msg.Copy()
				cachedReply.Id = requestMsg.Id
				return cachedReply, nil
			} else {
				dnsCacheMu.Lock()
				delete(dnsCache, cacheKey)
				dnsCacheMu.Unlock()
			}
		}
	}

	// --- 2. 缓存未命中，发起真实网络请求 ---
	log.Printf("%s [DNS-over-TCP] ➡️ 发起请求: 域名=[%s] 类型=[%s] MsgID=[%d]", TAG, domainName, qtypeStr, requestMsg.MsgHdr.Id)

	mu.Lock()
	client := sshClient
	mu.Unlock()

	if client == nil {
		return nil, fmt.Errorf("ssh client not ready")
	}

	tcpConn, err := getDnsConn(client)
	if err != nil {
		log.Printf("%s [DNS-over-TCP] ❌ 获取复用连接失败: %v", TAG, err)
		return nil, err
	}
	tcpConn.SetDeadline(time.Now().Add(5 * time.Second))

	if err := tcpConn.WriteMsg(requestMsg); err != nil {
		log.Printf("%s [DNS-over-TCP] ❌ 发送请求失败 (%s): %v", TAG, domainName, err)
		tcpConn.Close()
		return nil, err
	}

	reply, err := tcpConn.ReadMsg()
	if err != nil {
		log.Printf("%s [DNS-over-TCP] ❌ 读取响应失败 (%s): %v", TAG, domainName, err)
		tcpConn.Close()
		return nil, err
	}

	tcpConn.SetDeadline(time.Time{})
	putDnsConn(tcpConn)

	// --- 3. 写入缓存 (10000 条容量控制) ---
	if reply != nil && cacheKey != "" {
		if reply.Rcode == dns.RcodeSuccess || reply.Rcode == dns.RcodeNameError {
			dnsCacheMu.Lock()
			
			// 容量达到 10000
			if len(dnsCache) >= 10000 {
				// 利用 Go map 遍历的伪随机性，随机淘汰一个键值对
				for k := range dnsCache {
					delete(dnsCache, k)
					break // 删掉一个腾出空间即可
				}
			}

			dnsCache[cacheKey] = dnsCacheEntry{
				msg:       reply.Copy(),
				expiresAt: time.Now().Add(120 * time.Second),
			}
			dnsCacheMu.Unlock()
		}
	}

	// --- 4. 打印响应详情 ---
	if reply != nil {
		rcodeStr := dns.RcodeToString[reply.MsgHdr.Rcode]
		log.Printf("%s [DNS-over-TCP] ✅ 收到响应: 域名=[%s] 状态=[%s] 记录数=[%d]", TAG, domainName, rcodeStr, len(reply.Answer))

		for _, ans := range reply.Answer {
			switch record := ans.(type) {
			case *dns.A:
				log.Printf("%s [DNS-over-TCP] └─ [A记录] IP: %s", TAG, record.A.String())
			case *dns.AAAA:
				log.Printf("%s [DNS-over-TCP] └─ [AAAA记录] IPv6: %s", TAG, record.AAAA.String())
			case *dns.CNAME:
				log.Printf("%s [DNS-over-TCP] └─ [CNAME记录] 别名: %s", TAG, record.Target)
			default:
				log.Printf("%s [DNS-over-TCP] └─ [%s记录] %s", TAG, dns.TypeToString[ans.Header().Rrtype], ans.String())
			}
		}
	}

	return reply, nil
}

// GetCachedIPs 尝试从安全的远端 DNS 缓存中提取域名的 A 或 AAAA 记录
func GetCachedIPs(domain string) []net.IP {
	// miekg/dns 库的域名要求以 '.' 结尾 (FQDN 格式)
	fqdn := dns.Fqdn(domain)
	var ips []net.IP

	dnsCacheMu.RLock()
	defer dnsCacheMu.RUnlock()

	// 1. 尝试获取 IPv4 (A 记录, Qtype 1)
	keyA := fmt.Sprintf("%s-%d", fqdn, dns.TypeA)
	if entry, found := dnsCache[keyA]; found && time.Now().Before(entry.expiresAt) {
		for _, ans := range entry.msg.Answer {
			if record, ok := ans.(*dns.A); ok {
				ips = append(ips, record.A)
			}
		}
	}

	// 2. 尝试获取 IPv6 (AAAA 记录, Qtype 28)
	keyAAAA := fmt.Sprintf("%s-%d", fqdn, dns.TypeAAAA)
	if entry, found := dnsCache[keyAAAA]; found && time.Now().Before(entry.expiresAt) {
		for _, ans := range entry.msg.Answer {
			if record, ok := ans.(*dns.AAAA); ok {
				ips = append(ips, record.AAAA)
			}
		}
	}

	return ips
}