package app.fjj.stun.service

import app.fjj.stun.repo.Profile
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.util.KeystoreUtils
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object VpnConfigBuilder {

    fun buildGlobalConfig(context: Context, profile: Profile): String {
        val remoteDns = if (profile.dnsOverride) profile.remoteDns else SettingsManager.getRemoteDnsServer(context)
        val localDns = if (profile.dnsOverride) profile.localDns else SettingsManager.getLocalDnsServer(context)
        val geositeDirect = if (profile.dnsOverride) profile.geositeDirect.split(",").filter { it.isNotBlank() } else SettingsManager.getGeositeDirectTags(context)
        val geoipDirect = if (profile.dnsOverride) profile.geoipDirect.split(",").filter { it.isNotBlank() } else SettingsManager.getGeoipDirectTags(context)

        return JSONObject().apply {
            put("remote_dns_server", remoteDns)
            put("local_dns_server", localDns)
            put("geosite_filepath", SettingsManager.getGeositeCachePath(context))
            put("geoip_filepath", SettingsManager.getGeoipCachePath(context))
            put("direct_site_tags", JSONArray(geositeDirect))
            put("direct_ip_tags", JSONArray(geoipDirect))
        }.toString()
    }

    fun buildMySshConfig(context: Context, profile: Profile, socksPort: Int, dnsPort: Int): String {
        val udpgwAddr = if (profile.dnsOverride) profile.udpgwAddr else SettingsManager.getUdpgwAddr(context)
        val udpgwVersion = if (profile.dnsOverride) profile.udpgwVersion else SettingsManager.getUdpgwVersion(context)

        return JSONObject().apply {
            put("local_addr", "127.0.0.1:$socksPort")
            put("ssh_addr", profile.sshAddr)
            put("user", profile.user)
            put("auth_type", profile.authType)
            put("pass", profile.pass)
            put("private_key", profile.privateKey)
            put("private_key_passphrase", KeystoreUtils.decrypt(profile.keyPass))
            put("tunnel_type", profile.tunnelType)
            put("proxy_addr", profile.proxyAddr)
            put("proxy_auth_required", profile.proxyAuthRequired)
            put("proxy_auth_user", profile.proxyAuthUser)
            put("proxy_auth_pass", profile.proxyAuthPass)
            put("proxy_auth_token", profile.proxyAuthToken)
            put("custom_host", profile.customHost)
            put("server_name", profile.serverName)
            put("custom_path", if (!profile.enableCustomPath) "" else profile.customPath)
            put("http_payload", profile.httpPayload)
            put("udpgw_addr", udpgwAddr)
            put("disable_status_check", profile.disableStatusCheck)
            put("verify_ssh_finger_print", profile.verifyFingerprint)
            put("server_ssh_finger_print", profile.serverFingerprint)
            put("alpn", profile.alpn)
            put("verify_certificate_finger_print", profile.verifyCertFingerprint)
            put("server_certificate_finger_print", profile.serverCertFingerprint)
            put("dns_addr", ":${dnsPort}")
            put("udpgw_version", udpgwVersion)
            
            if (profile.tunnelType == Profile.TUNNEL_TYPE_DNS) {
                val domain = profile.dnsTunnelDomain.ifBlank { profile.customHost }
                val rawServers = profile.dnsTunnelServers.ifBlank { profile.proxyAddr }
                val serversList = rawServers.split(",", "\n", " ").map { it.trim() }.filter { it.isNotBlank() }
                val qtype = profile.dnsTunnelType.ifBlank { "txt" }

                put("dns_tunnel_domain", domain)
                put("dns_tunnel_servers", JSONArray(serversList))
                put("dns_tunnel_type", qtype)
            }
            if (profile.tunnelType == Profile.TUNNEL_TYPE_KCP) {
                put("kcp_password", profile.kcpPassword)
                put("kcp_crypt", profile.kcpCrypt.ifBlank { "aes" })
                put("kcp_nodelay", profile.kcpNoDelay)
                put("kcp_data_shards", if (profile.kcpDataShards > 0) profile.kcpDataShards else 10)
                put("kcp_parity_shards", if (profile.kcpParityShards >= 0) profile.kcpParityShards else 3)
            }
            if (profile.tunnelType == Profile.TUNNEL_TYPE_UDP_CUSTOM) {
                put("udp_custom_psk", profile.udpCustomPsk)
                put("udp_custom_magic", profile.udpCustomMagic)
            }
        }.toString()
    }

    fun buildHevSocks5TunnelConfig(socksPort: Int): String {
        return """
            misc:
              log-level: ${(if (app.fjj.stun.core.BuildConfig.DEBUG) "debug" else "warn")}
            tunnel:
              mtu: 1500
              ipv4: true
              ipv6: true
            socks5:
              port: $socksPort
              address: 127.0.0.1
              udp: udp
        """.trimIndent()
    }
}
