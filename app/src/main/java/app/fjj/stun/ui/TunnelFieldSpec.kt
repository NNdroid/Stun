package app.fjj.stun.ui

import app.fjj.stun.repo.Profile

/**
 * 节点编辑器「每个隧道类型需要哪些字段」的唯一声明表。
 *
 * 事实来源：myssh docs/transports.md（§2/§4/§5）+ tunnel_*.go 的实际 cfg.* 消费。
 * UI 显隐（ProfileEditActivity.updateUIBasedOnSettings / webui
 * updateEditModalTunnelFields）与校验（FieldRules）都必须从这里派生，
 * 不再各自写 if/when —— 之前"很多字段在某些情况下没有显示"就是三处手写字段
 * 条件互相漂移导致的。
 *
 * 语义要点（易错处，均有源码依据）：
 * - ALPN 只有 xhttp 消费；raw 已固定 Chrome ALPN、ws 固定 http/1.1、quic 固定 h3（不可配）。
 * - websocket 的 custom_host / proxy_auth(user+pass) 与 TLS 无关（tunnel_ws.go 公共路径）。
 * - h2 家族与 xhttp 的鉴权是 Bearer/PSK token；h2c 明文同样读取 token（tunnel_h2.go:29）。
 * - 心跳只有 h2 家族读取；xhttp 分块大小硬限 0 或 16..900；heartbeat <0 报错。
 * - h2tunnel paddingMinBytes：出站帧最小填充（流量混淆），负数=关闭，0=默认1420。
 * - masqueAlpn：masque 独有，SDK 取值 auto/h2/h3。
 * - udp_custom magic 允许 4 字节 ASCII 或 0x hex；icmp_custom 只收 8 位 hex / 0x hex。
 * - dns_custom 不看 proxy_addr；icmp_custom 的 proxy_addr 是纯主机（无端口）。
 */
enum class ProxyAddrMode {
    /** host:port（IPv6 用 [..]:port） */
    HOST_PORT,

    /** host:port，且端口可为范围/列表（udp_custom 的源端口扩展） */
    HOST_PORT_RANGE,

    /** 纯主机/IP，无端口（icmp_custom：ICMP 没有端口概念） */
    BARE_HOST,

    /** 该类型不消费 proxy_addr → 隐藏输入框（dns_custom） */
    HIDDEN
}

enum class ProxyAuthMode { NONE, USER_PASS, BEARER }

data class TunnelFieldSpec(
    val proxyAddr: ProxyAddrMode = ProxyAddrMode.HOST_PORT,
    val customHost: Boolean = false,
    val customPath: Boolean = false,
    val proxyAuth: ProxyAuthMode = ProxyAuthMode.NONE,
    /** 展示「TLS」开关（raw/websocket/h2/grpc/xhttp） */
    val tlsCapable: Boolean = false,
    /** 恒 TLS（quic/h3/masque/webtransport）→ 隐藏开关但按 TLS 处理 */
    val tlsFixed: Boolean = false,
    val alpn: Boolean = false,
    val httpPayload: Boolean = false,
    val xhttpOptions: Boolean = false,
    val dnsOptions: Boolean = false,
    val kcpOptions: Boolean = false,
    val udpOptions: Boolean = false,
    val icmpOptions: Boolean = false,
    /** Noise 公钥输入（dns/udp/icmp 自拨加密类） */
    val noisePublicKey: Boolean = false,
    /** h2 家族独有的空闲心跳 */
    val heartbeat: Boolean = false,
    /** h2tunnel 出站帧最小填充（流量混淆） */
    val paddingOptions: Boolean = false,
    /** masque 独有 ALPN 选择 */
    val masqueAlpnOptions: Boolean = false,
    val disableStatusCheck: Boolean = false,
    /** 仅当 TLS 生效时才展示/校验 proxy_addr：myssh 608dec3 起 raw 关 TLS 直连
     *  ssh_addr（纯 SSH，无隧道），此时 proxy_addr 不被读取。 */
    val proxyAddrWhenTlsOnly: Boolean = false
) {
    /** TLS 是否生效（决定 server_name/证书指纹的显隐与校验） */
    fun tlsActive(tunnelTlsEnabled: Boolean): Boolean = tlsFixed || (tlsCapable && tunnelTlsEnabled)
}

object TunnelFieldSpecs {

    fun forType(tunnelType: String): TunnelFieldSpec = when (tunnelType) {
        Profile.TUNNEL_TYPE_RAW -> TunnelFieldSpec(
            tlsCapable = true, proxyAddrWhenTlsOnly = true
        )

        Profile.TUNNEL_TYPE_WEBSOCKET -> TunnelFieldSpec(
            customHost = true, customPath = true,
            proxyAuth = ProxyAuthMode.USER_PASS,
            tlsCapable = true
        )

        Profile.TUNNEL_TYPE_HTTP -> TunnelFieldSpec(
            customHost = true, httpPayload = true,
            proxyAuth = ProxyAuthMode.USER_PASS,
            disableStatusCheck = true
        )

        Profile.TUNNEL_TYPE_H2 -> TunnelFieldSpec(
            customHost = true, customPath = true,
            proxyAuth = ProxyAuthMode.BEARER,
            tlsCapable = true, heartbeat = true, paddingOptions = true
        )

        Profile.TUNNEL_TYPE_GRPC -> TunnelFieldSpec(
            customHost = true, customPath = true,
            proxyAuth = ProxyAuthMode.BEARER,
            tlsCapable = true, heartbeat = true, paddingOptions = true
        )

        Profile.TUNNEL_TYPE_H3 -> TunnelFieldSpec(
            customHost = true, customPath = true,
            proxyAuth = ProxyAuthMode.BEARER,
            tlsFixed = true, heartbeat = true, paddingOptions = true
        )

        Profile.TUNNEL_TYPE_WEBTRANSPORT -> TunnelFieldSpec(
            customHost = true, customPath = true,
            proxyAuth = ProxyAuthMode.BEARER,
            tlsFixed = true, heartbeat = true, paddingOptions = true
        )

        Profile.TUNNEL_TYPE_MASQUE -> TunnelFieldSpec(
            customHost = true, customPath = true,
            proxyAuth = ProxyAuthMode.BEARER,
            tlsFixed = true, heartbeat = true, paddingOptions = true,
            masqueAlpnOptions = true
        )

        Profile.TUNNEL_TYPE_QUIC -> TunnelFieldSpec(
            tlsFixed = true
        )

        Profile.TUNNEL_TYPE_XHTTP -> TunnelFieldSpec(
            customHost = true, customPath = true,
            proxyAuth = ProxyAuthMode.BEARER,
            tlsCapable = true,
            alpn = true, xhttpOptions = true
        )

        Profile.TUNNEL_TYPE_KCP -> TunnelFieldSpec(
            kcpOptions = true
        )

        Profile.TUNNEL_TYPE_UDP_CUSTOM -> TunnelFieldSpec(
            proxyAddr = ProxyAddrMode.HOST_PORT_RANGE,
            udpOptions = true, noisePublicKey = true
        )

        Profile.TUNNEL_TYPE_DNS -> TunnelFieldSpec(
            proxyAddr = ProxyAddrMode.HIDDEN,
            dnsOptions = true, noisePublicKey = true
        )

        Profile.TUNNEL_TYPE_ICMP_CUSTOM -> TunnelFieldSpec(
            proxyAddr = ProxyAddrMode.BARE_HOST,
            icmpOptions = true, noisePublicKey = true
        )

        else -> TunnelFieldSpec()
    }
}
