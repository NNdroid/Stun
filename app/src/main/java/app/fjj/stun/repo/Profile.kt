package app.fjj.stun.repo

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "profiles")
data class Profile(
    @PrimaryKey
    var id: String = UUID.randomUUID().toString(),
    var name: String = "Server config",
    var sshAddr: String = "198.98.61.214:666",
    var user: String = "opentunnel.net-test007",
    var pass: String = "521qqwq",
    var tunnelType: String = TUNNEL_TYPE_TLS,
    var proxyAddr: String = "198.98.61.214:443",
    var customHost: String = "microsoft.com",
    var customPath: String = "/path/to/custom/path",
    var httpPayload: String = "CONNECT [host] HTTP/1.1[crlf]Host: [host][crlf][crlf]",
    var type: String = "ssh",

    // DNS and Routing Overrides
    var dnsOverride: Boolean = false,
    var remoteDns: String = SettingsManager.DEFAULT_REMOTE_DNS_SERVER,
    var localDns: String = SettingsManager.DEFAULT_LOCAL_DNS_SERVER,
    var udpgwAddr: String = SettingsManager.DEFAULT_UDPGW_ADDR,
    var geositeDirect: String = SettingsManager.DEFAULT_GEOSITE_DIRECT_FLAGS,
    var geoipDirect: String = SettingsManager.DEFAULT_GEOIP_DIRECT_FLAGS,

    // HTTP specific options
    var disableStatusCheck: Boolean = false
) {
    companion object {
        const val TUNNEL_TYPE_BASE = "base"
        const val TUNNEL_TYPE_TLS = "tls"
        const val TUNNEL_TYPE_HTTP = "http"
        const val TUNNEL_TYPE_WS = "ws"
        const val TUNNEL_TYPE_WSS = "wss"
        const val TUNNEL_TYPE_H2 = "h2"
        const val TUNNEL_TYPE_H2C = "h2c"
        const val TUNNEL_TYPE_GRPC = "grpc"
        const val TUNNEL_TYPE_GRPCC = "grpcc"
        const val TUNNEL_TYPE_H3 = "h3"
        const val TUNNEL_TYPE_WT = "wt"

        fun getAllTunnelTypes() = arrayOf(
            TUNNEL_TYPE_BASE,
            TUNNEL_TYPE_TLS,
            TUNNEL_TYPE_HTTP,
            TUNNEL_TYPE_WS,
            TUNNEL_TYPE_WSS,
            TUNNEL_TYPE_H2,
            TUNNEL_TYPE_H2C,
            TUNNEL_TYPE_GRPC,
            TUNNEL_TYPE_GRPCC,
            TUNNEL_TYPE_H3,
            TUNNEL_TYPE_WT
        )
    }
}
