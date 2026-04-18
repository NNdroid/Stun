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
    var name: String = "Server config",
    @SerializedName("sshAddr")
    var sshAddr: String = "198.98.61.214:666",
    @SerializedName("user")
    var user: String = "opentunnel.net-test007",
    @SerializedName("pass")
    var pass: String = "521qqwq",
    @SerializedName("authType")
    var authType: String = AUTH_TYPE_PASSWORD,
    @SerializedName("privateKey")
    var privateKey: String = "",
    @SerializedName("tunnelType")
    var tunnelType: String = TUNNEL_TYPE_TLS,
    @SerializedName("proxyAddr")
    var proxyAddr: String = "198.98.61.214:443",
    @SerializedName("customHost")
    var customHost: String = "learn.microsoft.com",
    @SerializedName("serverName")
    var serverName: String = "learn.microsoft.com",
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

    // Server Fingerprint
    @SerializedName("verifyFingerprint")
    var verifyFingerprint: Boolean = false,
    @SerializedName("serverFingerprint")
    var serverFingerprint: String = "",

    // Private key password (stored encrypted)
    @SerializedName("keyPass")
    var keyPass: String = "",

    // Traffic statistics
    @SerializedName("totalTx")
    var totalTx: Long = 0,
    @SerializedName("totalRx")
    var totalRx: Long = 0
) {
    companion object {
        const val TUNNEL_TYPE_BASE = "base"
        const val TUNNEL_TYPE_TLS = "tls"
        const val TUNNEL_TYPE_HTTP = "http"
        const val TUNNEL_TYPE_WS = "ws"
        const val TUNNEL_TYPE_WSS = "wss"
        const val TUNNEL_TYPE_H2 = "h2"
        const val TUNNEL_TYPE_H2C = "h2c"
        const val TUNNEL_TYPE_QUIC = "quic"
        const val TUNNEL_TYPE_GRPC = "grpc"
        const val TUNNEL_TYPE_GRPCC = "grpcc"
        const val TUNNEL_TYPE_H3 = "h3"
        const val TUNNEL_TYPE_WT = "wt"
        const val TUNNEL_TYPE_MASQUE = "masque"

        const val AUTH_TYPE_PASSWORD = "password"
        const val AUTH_TYPE_PRIVATEKEY = "privatekey"

        fun getAllTunnelTypes() = arrayOf(
            TUNNEL_TYPE_BASE,
            TUNNEL_TYPE_TLS,
            TUNNEL_TYPE_HTTP,
            TUNNEL_TYPE_WS,
            TUNNEL_TYPE_WSS,
            TUNNEL_TYPE_H2,
            TUNNEL_TYPE_H2C,
            TUNNEL_TYPE_QUIC,
            TUNNEL_TYPE_GRPC,
            TUNNEL_TYPE_GRPCC,
            TUNNEL_TYPE_H3,
            TUNNEL_TYPE_WT,
            TUNNEL_TYPE_MASQUE
        )
    }
}
