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
    var customPath: String = "/vpn",
    var httpPayload: String = "CONNECT [host] HTTP/1.1[crlf]Host: [host][crlf][crlf]",
    var type: String = "ssh"
) {
    companion object {
        const val TUNNEL_TYPE_BASE = "base"
        const val TUNNEL_TYPE_TLS = "tls"
        const val TUNNEL_TYPE_HTTP = "http"
        const val TUNNEL_TYPE_WS = "ws"
        const val TUNNEL_TYPE_WSS = "wss"

        fun getAllTunnelTypes() = arrayOf(
            TUNNEL_TYPE_BASE,
            TUNNEL_TYPE_TLS,
            TUNNEL_TYPE_HTTP,
            TUNNEL_TYPE_WS,
            TUNNEL_TYPE_WSS
        )
    }
}
