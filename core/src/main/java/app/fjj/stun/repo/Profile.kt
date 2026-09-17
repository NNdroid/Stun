package app.fjj.stun.repo

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName
import java.util.UUID

@Keep
@Entity(tableName = "profiles")
data class Profile(
    @PrimaryKey
    @SerializedName("id")
    var id: String = UUID.randomUUID().toString(),
    @SerializedName("name")
    var name: String = "Default config",
    @SerializedName("sshAddr")
    var sshAddr: String = "185.248.33.40:22",
    @SerializedName("user")
    var user: String = "default-username",
    @SerializedName("pass")
    var pass: String = "default-password",
    @SerializedName("authType")
    var authType: String = AUTH_TYPE_PASSWORD,
    @SerializedName("privateKey")
    var privateKey: String = "",
    @SerializedName("tunnelType")
    var tunnelType: String = TUNNEL_TYPE_RAW,
    @SerializedName("tunnelTlsEnabled")
    var tunnelTlsEnabled: Boolean = true,

    // ICMP Custom tunnel (SSH-over-ICMP)
    @SerializedName("icmpCustomPsk")
    var icmpCustomPsk: String = "",
    @SerializedName("icmpCustomMagic")
    var icmpCustomMagic: String = "",
    @SerializedName("icmpCustomPublicKey")
    var icmpCustomPublicKey: String = "",
    @SerializedName("icmpCustomMtuMode")
    var icmpCustomMtuMode: String = "",
    @SerializedName("icmpCustomMaxPayload")
    var icmpCustomMaxPayload: Int = 0,
    @SerializedName("icmpCustomPaceMS")
    var icmpCustomPaceMS: Int = 0,
    @SerializedName("icmpCustomIdRange")
    var icmpCustomIdRange: String = "",

    @SerializedName("proxyAddr")
    var proxyAddr: String = "185.248.33.40:443",
    @SerializedName("customHost")
    var customHost: String = "tunnel.stun.app",
    @SerializedName("serverName")
    var serverName: String = "tunnel.stun.app",
    @SerializedName("customPath")
    var customPath: String = "/path/to/custom/path",
    @SerializedName("enableCustomPath")
    var enableCustomPath: Boolean = false,
    @SerializedName("proxyAuthRequired")
    var proxyAuthRequired: Boolean = false,
    @SerializedName("proxyAuthToken")
    var proxyAuthToken: String = "",
    @SerializedName("proxyAuthUser")
    var proxyAuthUser: String = "",
    @SerializedName("proxyAuthPass")
    var proxyAuthPass: String = "",
    @SerializedName("httpPayload")
    var httpPayload: String = "CONNECT [host] HTTP/1.1[crlf]Host: [host][crlf][crlf]",
    @SerializedName("type")
    var type: String = "ssh",

    // DNS and Routing Overrides
    @SerializedName("dnsOverride")
    var dnsOverride: Boolean = false,
    @SerializedName("remoteDns")
    var remoteDns: String = SettingsManager.DEFAULT_REMOTE_DNS_SERVER,
    @SerializedName("localDns")
    var localDns: String = SettingsManager.DEFAULT_LOCAL_DNS_SERVER,
    @SerializedName("udpgwVersion")
    var udpgwVersion: String = SettingsManager.DEFAULT_UDPGW_VERSION,
    @SerializedName("udpgwAddr")
    var udpgwAddr: String = SettingsManager.DEFAULT_UDPGW_ADDR,
    @SerializedName("geositeDirect")
    var geositeDirect: String = SettingsManager.DEFAULT_GEOSITE_DIRECT_FLAGS,
    @SerializedName("geoipDirect")
    var geoipDirect: String = SettingsManager.DEFAULT_GEOIP_DIRECT_FLAGS,

    // App Filtering Overrides
    @SerializedName("appFilterOverride")
    var appFilterOverride: Boolean = false,
    @SerializedName("filterApps")
    var filterApps: String = "",
    @SerializedName("filterMode")
    var filterMode: Int = 0, // 0: Disallow, 1: Allow

    // HTTP specific options
    @SerializedName("disableStatusCheck")
    var disableStatusCheck: Boolean = false,

    // Server Fingerprint (SSH)
    @SerializedName("verifyFingerprint")
    var verifyFingerprint: Boolean = false,
    @SerializedName("serverFingerprint")
    var serverFingerprint: String = "",

    // Certificate Fingerprint (TLS/QUIC)
    @SerializedName("verifyCertFingerprint")
    var verifyCertFingerprint: Boolean = false,
    @SerializedName("serverCertFingerprint")
    var serverCertFingerprint: String = "",

    @SerializedName("alpn")
    var alpn: String = "h2,http/1.1",

    // Private key password (stored encrypted)
    @SerializedName("keyPass")
    var keyPass: String = "",

    // DNS Tunnel (SSH-over-DNS) options
    @SerializedName("dnsTunnelDomain")
    var dnsTunnelDomain: String = "",
    @SerializedName("dnsTunnelServers")
    var dnsTunnelServers: String = "",
    @SerializedName("dnsTunnelType")
    var dnsTunnelType: String = "txt",
    @SerializedName("dnsTunnelPublicKey")
    var dnsTunnelPublicKey: String = "",
    @SerializedName("dnsTunnelEDNS0")
    var dnsTunnelEDNS0: Boolean = false,
    // myssh ce76928: dns_custom PSK auth + custom marker (empty = anonymous / SDK default)
    @SerializedName("dnsTunnelPsk")
    var dnsTunnelPsk: String = "",
    @SerializedName("dnsTunnelMarker")
    var dnsTunnelMarker: String = "",

    // KCP (kcptun protocol) tunnel options
    @SerializedName("kcpPassword")
    var kcpPassword: String = "",
    @SerializedName("kcpCrypt")
    var kcpCrypt: String = "aes",
    @SerializedName("kcpMode")
    var kcpMode: String = "",
    @SerializedName("kcpDataShards")
    var kcpDataShards: Int = 10,
    @SerializedName("kcpParityShards")
    var kcpParityShards: Int = 3,
    @SerializedName("kcpSndWnd")
    var kcpSndWnd: Int = 0,
    @SerializedName("kcpRcvWnd")
    var kcpRcvWnd: Int = 0,
    @SerializedName("kcpMtu")
    var kcpMtu: Int = 0,
    @SerializedName("kcpNoComp")
    var kcpNoComp: Boolean = false,
    @SerializedName("kcpSmuxVer")
    var kcpSmuxVer: Int = 0,
    @SerializedName("kcpKeepAlive")
    var kcpKeepAlive: Int = 0,

    // UDP Custom options
    @SerializedName("udpCustomPsk")
    var udpCustomPsk: String = "",
    @SerializedName("udpCustomMagic")
    var udpCustomMagic: String = "UDPC",
    @SerializedName("udpCustomPublicKey")
    var udpCustomPublicKey: String = "",
    @SerializedName("udpCustomPaths")
    var udpCustomPaths: Int = 0,
    @SerializedName("udpCustomSockets")
    var udpCustomSockets: Int = 0,
    @SerializedName("udpCustomSendWindow")
    var udpCustomSendWindow: Int = 0,
    // myssh 152c556: UDP Custom path-MTU 探测 + 最大包长
    @SerializedName("udpCustomMaxPkt")
    var udpCustomMaxPkt: Int = 0,
    @SerializedName("udpCustomMtuProbe")
    var udpCustomMtuProbe: String = "",

    // Legacy shared Noise key retained for old profile/URI compatibility. New profiles
    // use the protocol-specific public-key fields above.
    @SerializedName("noisePublicKey")
    var noisePublicKey: String = "",

    // XHTTP options
    @SerializedName("xhttpChunkSizeKB")
    var xhttpChunkSizeKB: Int = 0,

    // XHTTP downlink transport: ""/auto (SDK adaptive, default), stream, poll
    @SerializedName("xhttpStreamMode")
    var xhttpStreamMode: String = "",

    // Outbound dialer network-interface binding (e.g. wlan0); empty = no binding
    @SerializedName("bindInterface")
    var bindInterface: String = "",

    // Last successful connect timestamp (epoch ms); 0 = never connected on this device
    @SerializedName("lastConnectedAt")
    var lastConnectedAt: Long = 0,

    // Stream heartbeat (h2 family tunnels); 0 = library default
    @SerializedName("heartbeatIntervalMs")
    var heartbeatIntervalMs: Int = 0,
    // myssh 152c556: h2tunnel 出站帧最小填充字节（流量混淆，负数=关闭）
    @SerializedName("paddingMinBytes")
    var paddingMinBytes: Int = 0,
    // myssh 152c556: masque 独有 ALPN 选择（h2/h3/auto）
    @SerializedName("masqueAlpn")
    var masqueAlpn: String = "",

    // Traffic statistics
    @SerializedName("totalTx")
    var totalTx: Long = 0,
    @SerializedName("totalRx")
    var totalRx: Long = 0,

    @SerializedName("sortIndex")
    var sortIndex: Int = 0,

    // 用户备注（连接详情面板「备注」行）。空串 = 未填写，UI 显示 “—”。
    @SerializedName("note")
    var note: String = "",

    // 星标收藏（连接详情面板右上角 ⭐）。纯展示状态，不参与连接/路由逻辑。
    @SerializedName("favorite")
    var favorite: Boolean = false,

    // 来源订阅 URL。手动添加的节点恒为空串；订阅同步导入/更新时盖戳。
    // 用途：节点卡片显示来源徽标、删订阅时询问清理、同步后移除已下架节点。
    // 纯元数据，不参与 VpnConfigBuilder 的任何连接参数。
    @SerializedName("sourceSubscriptionUrl")
    var sourceSubscriptionUrl: String = ""
) : java.io.Serializable {
    companion object {
        // myssh 4735512：类型收敛为 13 个，TLS 由 tunnelTlsEnabled 开关控制。
        // 旧别名（base/tls/ws/wss/h2c/grpcc/xhttpc/wt）已从 myssh 删除，勿再使用。
        const val TUNNEL_TYPE_RAW = "raw"
        const val TUNNEL_TYPE_WEBSOCKET = "websocket"
        const val TUNNEL_TYPE_HTTP = "http"
        const val TUNNEL_TYPE_H2 = "h2"
        const val TUNNEL_TYPE_QUIC = "quic"
        const val TUNNEL_TYPE_GRPC = "grpc"
        const val TUNNEL_TYPE_H3 = "h3"
        const val TUNNEL_TYPE_MASQUE = "masque"
        const val TUNNEL_TYPE_WEBTRANSPORT = "webtransport"
        const val TUNNEL_TYPE_XHTTP = "xhttp"
        const val TUNNEL_TYPE_DNS = "dns_custom"
        const val TUNNEL_TYPE_KCP = "kcptun"
        const val TUNNEL_TYPE_UDP_CUSTOM = "udp_custom"
        const val TUNNEL_TYPE_ICMP_CUSTOM = "icmp_custom"

        const val AUTH_TYPE_PASSWORD = "password"
        const val AUTH_TYPE_PRIVATEKEY = "privatekey"

        /**
         * masque 承载 ALPN 归一（含已废弃历史别名的平滑迁移）。
         *
         * myssh/h2tunnel SDK 侧只有 `""`(auto) / `"h3"` / `"h2"` 三个合法取值
         * （transport_masque_client.go 定义，client_api.go 按严格字符串相等校验，
         * 不做逗号拆分）。配置面历史上曾把 `"h3,h2"` / `"h2,h3"` 当作 auto 的别名，
         * 但这两个写法并不表达 SDK 支持的任何能力（不存在「优先级排序」这一语义），
         * 已从下拉选项、校验、错误文案与文档中一并移除。
         *
         * 此处负责把旧存档里残留的这两个值归一到 `"auto"`：既避免旧节点被 SDK 拒绝
         * 而连不上，也让下次保存把脏值写回干净值。空串/auto 同样返回 `"auto"`。
         *
         * 比较前先 trim + 转小写 + 去掉内部空格，与 Go 侧 normalizeMasqueALPN 的归一
         * 口径保持一致（否则 "H3, H2" 这类写法会漏判成"未知值"而透传给 SDK）。
         */
        fun normalizeMasqueAlpn(raw: String): String {
            val key = raw.trim().lowercase().replace(" ", "")
            return when (key) {
                "", "auto", "h3,h2", "h2,h3" -> "auto"
                else -> raw.trim()
            }
        }

        fun getAllTunnelTypes() = arrayOf(
            TUNNEL_TYPE_RAW,
            TUNNEL_TYPE_WEBSOCKET,
            TUNNEL_TYPE_HTTP,
            TUNNEL_TYPE_H2,
            TUNNEL_TYPE_QUIC,
            TUNNEL_TYPE_GRPC,
            TUNNEL_TYPE_H3,
            TUNNEL_TYPE_MASQUE,
            TUNNEL_TYPE_WEBTRANSPORT,
            TUNNEL_TYPE_XHTTP,
            TUNNEL_TYPE_DNS,
            TUNNEL_TYPE_KCP,
            TUNNEL_TYPE_UDP_CUSTOM,
            TUNNEL_TYPE_ICMP_CUSTOM
        )
    }
}
