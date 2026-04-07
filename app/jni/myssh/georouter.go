package myssh

import (
	"fmt"
	"io/ioutil"
	"net"
	"regexp"
	"strings"

	"github.com/v2fly/v2ray-core/v5/app/router/routercommon"
	"github.com/yl2chen/cidranger"
	"google.golang.org/protobuf/proto"
)


// ---------------------------------------------------------
// GeoRouter 核心结构
// ---------------------------------------------------------

type GeoRouter struct {
	// 域名匹配器
	fullDomains   map[string]struct{} // 精确匹配 (Full)
	subDomains    map[string]struct{} // 后缀/子域名匹配 (Domain)
	keywordList   []string            // 关键字匹配 (Keyword)
	regexList     []*regexp.Regexp    // 正则匹配 (Regex)

	// IP CIDR 匹配器 (使用 cidranger 提供 O(1) 高效检索)
	ipRanger cidranger.Ranger
}

// newGeoRouter 创建一个新的空路由管理器
func newGeoRouter() *GeoRouter {
	return &GeoRouter{
		fullDomains: make(map[string]struct{}),
		subDomains:  make(map[string]struct{}),
		keywordList: make([]string, 0),
		regexList:   make([]*regexp.Regexp, 0),
		ipRanger:    cidranger.NewPCTrieRanger(),
	}
}

// ---------------------------------------------------------
// 加载数据解析器
// ---------------------------------------------------------

// LoadGeoSite 解析 geosite.dat 并加载指定 tags 数组的规则 (如 []string{"cn", "google"})
func (r *GeoRouter) LoadGeoSite(filepath string, targetTags []string) error {
	data, err := ioutil.ReadFile(filepath)
	if err != nil {
		return fmt.Errorf("读取 geosite.dat 失败: %w", err)
	}

	var geoSiteList routercommon.GeoSiteList
	if err := proto.Unmarshal(data, &geoSiteList); err != nil {
		return fmt.Errorf("解析 protobuf 失败: %w", err)
	}

	// 建立一个 map 提升查找效率，并统一转为小写
	tagMap := make(map[string]bool)
	for _, t := range targetTags {
		tagMap[strings.ToLower(t)] = true
	}

	foundCount := 0

	for _, site := range geoSiteList.Entry {
		if tagMap[strings.ToLower(site.CountryCode)] {
			foundCount++
			for _, domain := range site.Domain {
				val := strings.ToLower(domain.Value)
				switch domain.Type {
				case routercommon.Domain_Plain:
					// V2Ray 的 Plain 其实是 Keyword (包含该字符串即匹配)
					r.keywordList = append(r.keywordList, val)
				case routercommon.Domain_Regex:
					if re, err := regexp.Compile(val); err == nil {
						r.regexList = append(r.regexList, re)
					}
				case routercommon.Domain_RootDomain:
					// 根域名匹配，例如 "baidu.com" 匹配 "www.baidu.com"
					r.subDomains[val] = struct{}{}
				case routercommon.Domain_Full:
					// 精确域名匹配
					r.fullDomains[val] = struct{}{}
				}
			}
			// ⚠️ 核心修改：去掉了这里的 break，允许继续寻找其他的 tag
		}
	}

	if foundCount == 0 && len(targetTags) > 0 {
		return fmt.Errorf("未在 geosite 中找到任何指定的标签: %v", targetTags)
	}
	return nil
}

// LoadGeoIP 解析 geoip.dat 并加载指定 tags 数组的 CIDR 规则
func (r *GeoRouter) LoadGeoIP(filepath string, targetTags []string) error {
	data, err := ioutil.ReadFile(filepath)
	if err != nil {
		return fmt.Errorf("读取 geoip.dat 失败: %w", err)
	}

	var geoIPList routercommon.GeoIPList
	if err := proto.Unmarshal(data, &geoIPList); err != nil {
		return fmt.Errorf("解析 protobuf 失败: %w", err)
	}

	// geoip.dat 中的 tag 通常是大写，例如 "CN"
	tagMap := make(map[string]bool)
	for _, t := range targetTags {
		tagMap[strings.ToUpper(t)] = true
	}

	foundCount := 0

	for _, ipGroup := range geoIPList.Entry {
		if tagMap[strings.ToUpper(ipGroup.CountryCode)] {
			foundCount++
			for _, cidr := range ipGroup.Cidr {
				ip := cidr.Ip
				prefix := cidr.Prefix

				var ipNet *net.IPNet
				if len(ip) == 4 { // IPv4
					ipNet = &net.IPNet{IP: ip, Mask: net.CIDRMask(int(prefix), 32)}
				} else if len(ip) == 16 { // IPv6
					ipNet = &net.IPNet{IP: ip, Mask: net.CIDRMask(int(prefix), 128)}
				} else {
					continue // 未知格式
				}

				_ = r.ipRanger.Insert(cidranger.NewBasicRangerEntry(*ipNet))
			}
			// ⚠️ 核心修改：去掉了这里的 break
		}
	}

	if foundCount == 0 && len(targetTags) > 0 {
		return fmt.Errorf("未在 geoip 中找到任何指定的标签: %v", targetTags)
	}
	return nil
}

// ---------------------------------------------------------
// 高效分流匹配逻辑
// ---------------------------------------------------------

// --- 专门用于路由结果的结构体 ---
type RouteResult struct {
	IsDirect bool   `json:"is_direct"`
	DialHost string `json:"dial_host"`
}

// ShouldDirect 综合判断请求的 host 是否应该走直连，并返回路由决策对象
func (r *GeoRouter) ShouldDirect(host string) RouteResult {
	if host == "" {
		return RouteResult{IsDirect: false, DialHost: ""}
	}

	ip := net.ParseIP(host)
	if ip != nil {
		// 原生就是 IP，直接匹配并返回它自己
		return RouteResult{IsDirect: r.MatchIP(ip), DialHost: host}
	}

	// ==========================
	// 1. 走 GeoSite (域名规则) 匹配
	// ==========================
	if r.MatchDomain(host) {
		ips := GetCachedIPs(host)
		if len(ips) > 0 {
			return RouteResult{IsDirect: true, DialHost: ips[0].String()}
		}
		return RouteResult{IsDirect: true, DialHost: host}
	}

	// ==========================
	// 2. 查 GeoIP (IP 规则)
	// ==========================
	ips := GetCachedIPs(host)
	if len(ips) == 0 {
		localIPs, err := net.LookupIP(host)
		if err == nil {
			ips = localIPs
		}
	}

	for _, resolvedIP := range ips {
		if r.MatchIP(resolvedIP) {
			return RouteResult{IsDirect: true, DialHost: resolvedIP.String()}
		}
	}

	// ==========================
	// 3. 走代理 (未命中直连规则)
	// ==========================
	return RouteResult{IsDirect: false, DialHost: host}
}

// MatchDomain 检查域名是否命中规则 (包含精确匹配和后缀层级匹配)
func (r *GeoRouter) MatchDomain(domain string) bool {
	domain = strings.ToLower(domain)

	// 1. Full 检查: 精确匹配
	if _, ok := r.fullDomains[domain]; ok {
		return true
	}

	// 2. Domain 检查: 逐级剥离域名层级进行后缀检查
	parts := strings.Split(domain, ".")
	for i := 0; i < len(parts); i++ {
		sub := strings.Join(parts[i:], ".")
		if _, ok := r.subDomains[sub]; ok {
			return true
		}
	}

	// 3. Keyword 检查: 字符串包含匹配
	for _, kw := range r.keywordList {
		if strings.Contains(domain, kw) {
			return true
		}
	}

	// 4. Regex 检查: 正则匹配 (性能最差，通常放到最后)
	for _, re := range r.regexList {
		if re.MatchString(domain) {
			return true
		}
	}

	return false
}

// MatchIP 使用 Radix Tree (基数树) 以 O(1) 时间复杂度检查 IP 是否在 CIDR 网段内
func (r *GeoRouter) MatchIP(ip net.IP) bool {
	// Contains 返回是否有一个或多个网段包含该 IP
	contains, err := r.ipRanger.Contains(ip)
	if err != nil {
		return false
	}
	return contains
}