package app.fjj.stun.service

import app.fjj.stun.repo.Profile
import app.fjj.stun.repo.SettingsManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

object VpnConfigBuilder {

    /** 将旧版编辑器接受的 SHA-256 hex 转为 myssh 使用的 OpenSSH 指纹格式。 */
    internal fun normalizeSshFingerprint(value: String): String {
        val trimmed = value.trim()
        val hex = trimmed.replace(":", "").replace(" ", "")
        if (hex.length != 64 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return trimmed
        }
        val digest = ByteArray(32) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    /** "1.2.3.4:22" → "1.2.3.4"；无端口/非纯数字尾段/带括号的 IPv6 字面量原样返回。 */
    private fun stripTrailingPort(addr: String): String {
        val idx = addr.lastIndexOf(':')
        if (idx <= 0 || idx == addr.length - 1 || addr.contains('[')) return addr
        val tail = addr.substring(idx + 1)
        return if (tail.length <= 5 && tail.all { it.isDigit() }) addr.substring(0, idx) else addr
    }

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
        val isMasque = profile.tunnelType == Profile.TUNNEL_TYPE_MASQUE
        val customPath = if (isMasque && !profile.enableCustomPath) "" else profile.customPath
        // Before udp_custom exposed dedicated fields, Android profiles commonly reused
        // the SSH password/address. Keep those profiles connectable while newly saved
        // profiles are validated to contain an explicit UDP PSK and proxy address.
        val proxyAddr = when (profile.tunnelType) {
            Profile.TUNNEL_TYPE_UDP_CUSTOM -> profile.proxyAddr.ifBlank { profile.sshAddr }
            // myssh icmp_custom requires proxy_addr to be a bare peer host
            // ("ICMP peer host, no port"); strip an accidentally saved :port.
            Profile.TUNNEL_TYPE_ICMP_CUSTOM -> stripTrailingPort(profile.proxyAddr.trim())
            else -> profile.proxyAddr
        }

        // SNI/ALPN 仅对 TLS 激活的类型有意义（quic/h3/masque/webtransport 固定 TLS；
        // raw/websocket/h2/grpc/xhttp 由 tunnelTlsEnabled 决定），明文隧道不下发这两个字段。
        val isTlsFixedType = profile.tunnelType in listOf(
            Profile.TUNNEL_TYPE_QUIC, Profile.TUNNEL_TYPE_H3, Profile.TUNNEL_TYPE_MASQUE,
            Profile.TUNNEL_TYPE_WEBTRANSPORT
        )
        val isTlsCapableType = profile.tunnelType in listOf(
            Profile.TUNNEL_TYPE_RAW, Profile.TUNNEL_TYPE_WEBSOCKET, Profile.TUNNEL_TYPE_H2,
            Profile.TUNNEL_TYPE_GRPC, Profile.TUNNEL_TYPE_XHTTP
        )
        val isTlsActive = isTlsFixedType || (isTlsCapableType && profile.tunnelTlsEnabled)

        return JSONObject().apply {
            put("local_addr", "127.0.0.1:$socksPort")
            put("ssh_addr", profile.sshAddr)
            put("user", profile.user)
            put("auth_type", profile.authType)
            put("pass", profile.pass)
            put("private_key", profile.privateKey)
            put("private_key_passphrase", profile.keyPass)
            put("tunnel_type", profile.tunnelType)
            put("tunnel_tls_enabled", profile.tunnelTlsEnabled)
            put("proxy_addr", proxyAddr)
            put("proxy_auth_required", profile.proxyAuthRequired)
            put("proxy_auth_user", profile.proxyAuthUser)
            put("proxy_auth_pass", profile.proxyAuthPass)
            put("proxy_auth_token", profile.proxyAuthToken)
            put("custom_host", profile.customHost)
            put("server_name", if (isTlsActive) profile.serverName else "")
            put("custom_path", customPath)
            put("http_payload", profile.httpPayload)
            put("udpgw_addr", udpgwAddr)
            put("disable_status_check", profile.disableStatusCheck)
            put("verify_ssh_finger_print", profile.verifyFingerprint)
            put("server_ssh_finger_print", normalizeSshFingerprint(profile.serverFingerprint))
            put("alpn", if (isTlsActive) profile.alpn else "")
            put("verify_certificate_finger_print", profile.verifyCertFingerprint)
            put("server_certificate_finger_print", profile.serverCertFingerprint)
            put("dns_addr", ":${dnsPort}")
            put("udpgw_version", udpgwVersion)
            // myssh dialer binds outbound connections to this interface when non-blank.
            // Null-safe reads: Gson leaves String fields null when a shared/imported
            // JSON omits them (Kotlin defaults do not apply on reflective parsing).
            if (!profile.bindInterface.isNullOrBlank()) {
                put("bind_interface", profile.bindInterface)
            }
            
            if (profile.tunnelType == Profile.TUNNEL_TYPE_DNS) {
                val domain = profile.dnsTunnelDomain.ifBlank { profile.customHost }
                val rawServers = profile.dnsTunnelServers.ifBlank { profile.proxyAddr }
                val serversList = rawServers.split(",", "\n", " ").map { it.trim() }.filter { it.isNotBlank() }
                val qtype = profile.dnsTunnelType.ifBlank { "txt" }

                put("dns_tunnel_domain", domain)
                put("dns_tunnel_servers", JSONArray(serversList))
                put("dns_tunnel_type", qtype)
                put("dns_tunnel_public_key", profile.dnsTunnelPublicKey.ifBlank { profile.noisePublicKey })
                put("dns_tunnel_edns0", profile.dnsTunnelEDNS0)
                // myssh ce76928：空 psk=匿名、空 marker=SDK 默认（两值须与服务端一致）
                if (profile.dnsTunnelPsk.isNotBlank()) put("dns_tunnel_psk", profile.dnsTunnelPsk)
                if (profile.dnsTunnelMarker.isNotBlank()) put("dns_tunnel_marker", profile.dnsTunnelMarker)
            }
            if (profile.tunnelType == Profile.TUNNEL_TYPE_KCP) {
                put("kcp_password", profile.kcpPassword)
                // kcptun crypt：blank/none = 不加密，保留同回退语义
                put("kcp_crypt", profile.kcpCrypt.ifBlank { "none" })
                // ""/fast = kcptun 默认 modes 模式
                if (profile.kcpMode.isNotBlank()) put("kcp_mode", profile.kcpMode)
                put("kcp_data_shards", if (profile.kcpDataShards > 0) profile.kcpDataShards else 10)
                put("kcp_parity_shards", if (profile.kcpParityShards >= 0) profile.kcpParityShards else 3)
                if (profile.kcpSndWnd > 0) put("kcp_sndwnd", profile.kcpSndWnd)
                if (profile.kcpRcvWnd > 0) put("kcp_rcvwnd", profile.kcpRcvWnd)
                if (profile.kcpMtu > 0) put("kcp_mtu", profile.kcpMtu)
                if (profile.kcpNoComp) put("kcp_nocomp", true)
                if (profile.kcpSmuxVer > 0) put("kcp_smuxver", profile.kcpSmuxVer)
                if (profile.kcpKeepAlive > 0) put("kcp_keepalive", profile.kcpKeepAlive)
            }
            if (profile.tunnelType == Profile.TUNNEL_TYPE_ICMP_CUSTOM) {
                put("icmp_custom_psk", profile.icmpCustomPsk.ifBlank { profile.pass })
                // magic 留空 = SDK MagicDefault；Noise 公钥优先，回退共享 Noise
                put("icmp_custom_magic", profile.icmpCustomMagic)
                put("icmp_custom_public_key", profile.icmpCustomPublicKey.ifBlank { profile.noisePublicKey })
                // icmp_custom_family 已从 myssh 152c556 的 ProxyConfig 删除，不再下发
                put("icmp_custom_mtu_mode", profile.icmpCustomMtuMode)
                if (profile.icmpCustomMaxPayload > 0) put("icmp_custom_max_payload", profile.icmpCustomMaxPayload)
                if (profile.icmpCustomPaceMS > 0) put("icmp_custom_pace_ms", profile.icmpCustomPaceMS)
                if (profile.icmpCustomIdRange.isNotBlank()) put("icmp_custom_id_range", profile.icmpCustomIdRange)
            }
            if (profile.tunnelType == Profile.TUNNEL_TYPE_UDP_CUSTOM) {
                put("udp_custom_psk", profile.udpCustomPsk.ifBlank { profile.pass })
                put("udp_custom_magic", profile.udpCustomMagic)
                put("udp_custom_public_key", profile.udpCustomPublicKey.ifBlank { profile.noisePublicKey })
                put("udp_custom_paths", profile.udpCustomPaths)
                put("udp_custom_sockets", profile.udpCustomSockets)
                put("udp_custom_send_window", profile.udpCustomSendWindow)
                // myssh 152c556：路径 MTU 探测与最大包长
                if (profile.udpCustomMaxPkt > 0) put("udp_custom_max_pkt", profile.udpCustomMaxPkt)
                if (profile.udpCustomMtuProbe.isNotBlank()) put("udp_custom_mtu_probe", profile.udpCustomMtuProbe)
            }
            if (profile.tunnelType == Profile.TUNNEL_TYPE_XHTTP) {
                put("xhttp_chunk_size_kb", profile.xhttpChunkSizeKB)
                // ""/auto = SDK adaptive streaming with polling fallback; stream/poll force a mode
                val streamMode = profile.xhttpStreamMode ?: ""
                if (streamMode.isNotBlank()) {
                    put("xhttp_stream_mode", streamMode)
                }
            }
            // KEEPALIVE heartbeat is consumed by the h2-family tunnels only.
            if (profile.tunnelType in listOf(
                    Profile.TUNNEL_TYPE_H2, Profile.TUNNEL_TYPE_GRPC, Profile.TUNNEL_TYPE_H3,
                    Profile.TUNNEL_TYPE_MASQUE, Profile.TUNNEL_TYPE_WEBTRANSPORT
                )
            ) {
                put("heartbeat_interval_ms", profile.heartbeatIntervalMs)
                // myssh 152c556：h2tunnel 出站帧最小填充字节（流量混淆，负数=关闭，0=默认1420）
                if (profile.paddingMinBytes != 0) put("padding_min_bytes", profile.paddingMinBytes)
            }
            // myssh 152c556：masque 独有 ALPN 选择（SDK 仅在 masque transport 上接受此字段）。
            // SDK 只认 ""(auto)/h3/h2，历史别名 h3,h2 / h2,h3 由 normalizeMasqueAlpn 归一到 auto。
            if (profile.tunnelType == Profile.TUNNEL_TYPE_MASQUE && profile.masqueAlpn.isNotBlank()) {
                put("masque_alpn", Profile.normalizeMasqueAlpn(profile.masqueAlpn))
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
