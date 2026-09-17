package app.fjj.stun.service

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.fjj.stun.repo.Profile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * VpnConfigBuilder 测试：守护「Profile -> Go 引擎配置 JSON」的字段映射正确性，
 * 重点覆盖此前扩展的 tunnel_dns 记录类型（txt/null/cname/a/aaaa/mx/srv/ns）、
 * KCP / UDP-Custom 分支，以及 dnsOverride 走 profile 自有值时的映射。
 *
 * 说明：测试 profile 均保持 keyPass 默认空串，避免触碰 KeystoreUtils 初始化；
 * 并设 dnsOverride=true 让 udpgw/geo 等走 profile 字段，规避 SettingsManager 默认值依赖。
 */
@RunWith(AndroidJUnit4::class)
class VpnConfigBuilderTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun buildMySshConfig_basicFields() {
        val p = Profile(
            name = "basic",
            sshAddr = "1.2.3.4:22",
            user = "u",
            pass = "pw",
            authType = Profile.AUTH_TYPE_PASSWORD,
            tunnelType = Profile.TUNNEL_TYPE_RAW,
            tunnelTlsEnabled = true,
            proxyAddr = "5.6.7.8:443",
            customHost = "h.example",
            serverName = "s.example",
            dnsOverride = true,
            udpgwAddr = "9.9.9.9:7300",
            udpgwVersion = "v1"
        )
        val json = JSONObject(VpnConfigBuilder.buildMySshConfig(context, p, 1080, 53))

        assertEquals("127.0.0.1:1080", json.getString("local_addr"))
        assertEquals("1.2.3.4:22", json.getString("ssh_addr"))
        assertEquals("u", json.getString("user"))
        assertEquals(Profile.AUTH_TYPE_PASSWORD, json.getString("auth_type"))
        assertEquals(Profile.TUNNEL_TYPE_RAW, json.getString("tunnel_type"))
        assertEquals(true, json.getBoolean("tunnel_tls_enabled"))
        assertEquals("5.6.7.8:443", json.getString("proxy_addr"))
        assertEquals(":53", json.getString("dns_addr"))
        assertEquals("9.9.9.9:7300", json.getString("udpgw_addr"))
    }

    @Test
    fun buildMySshConfig_dnsTunnel_mapsAllQTypes() {
        // 守护 tunnel_dns 类型扩展：每种 qtype 都应原样映射进 dns_tunnel_type
        val types = arrayOf("txt", "null", "cname", "a", "aaaa", "mx", "srv", "ns")
        for (t in types) {
            val p = Profile(
                name = "dns",
                tunnelType = Profile.TUNNEL_TYPE_DNS,
                dnsTunnelDomain = "t.example.com",
                dnsTunnelServers = "1.1.1.1, 8.8.8.8",
                dnsTunnelType = t,
                dnsTunnelPublicKey = "dns-npk",
                dnsOverride = true
            )
            val json = JSONObject(VpnConfigBuilder.buildMySshConfig(context, p, 1080, 53))
            assertEquals("dns_tunnel_type mismatch for [$t]", t, json.getString("dns_tunnel_type"))
            assertEquals("t.example.com", json.getString("dns_tunnel_domain"))

            val servers = json.getJSONArray("dns_tunnel_servers")
            assertEquals(2, servers.length())
            assertEquals("1.1.1.1", servers.getString(0))
            assertEquals("8.8.8.8", servers.getString(1))

            assertEquals("dns-npk", json.getString("dns_tunnel_public_key"))
            // noise_public_key is a dead field on the Go side; must not be sent anymore
            assertFalse("noise_public_key must not be sent", json.has("noise_public_key"))
            assertFalse(json.getBoolean("dns_tunnel_edns0"))
        }
    }

    @Test
    fun buildMySshConfig_dnsTunnel_edns0Enabled() {
        val p = Profile(
            name = "dns",
            tunnelType = Profile.TUNNEL_TYPE_DNS,
            dnsTunnelDomain = "t.example.com",
            dnsTunnelServers = "1.1.1.1",
            dnsTunnelEDNS0 = true,
            dnsOverride = true
        )
        val json = JSONObject(VpnConfigBuilder.buildMySshConfig(context, p, 1080, 53))
        assertTrue(json.getBoolean("dns_tunnel_edns0"))
    }

    @Test
    fun buildMySshConfig_dnsTunnel_defaultsWhenBlank() {
        // dnsTunnelType/domain/servers 留空时应回退到默认 txt / customHost / proxyAddr
        val p = Profile(tunnelType = Profile.TUNNEL_TYPE_DNS, dnsOverride = true)
        val json = JSONObject(VpnConfigBuilder.buildMySshConfig(context, p, 1080, 53))
        assertEquals("txt", json.getString("dns_tunnel_type"))
        assertEquals(p.customHost, json.getString("dns_tunnel_domain"))
        assertEquals(1, json.getJSONArray("dns_tunnel_servers").length())
        assertEquals(p.proxyAddr, json.getJSONArray("dns_tunnel_servers").getString(0))
    }

    @Test
    fun buildMySshConfig_kcpBranch() {
        val p = Profile(
            tunnelType = Profile.TUNNEL_TYPE_KCP,
            kcpPassword = "kp",
            kcpCrypt = "aes",
            kcpMode = "fast3",
            kcpSndWnd = 256,
            kcpRcvWnd = 1024,
            kcpMtu = 1400,
            kcpNoComp = true,
            kcpSmuxVer = 1,
            kcpKeepAlive = 15,
            kcpDataShards = 7,
            kcpParityShards = 2,
            dnsOverride = true
        )
        val json = JSONObject(VpnConfigBuilder.buildMySshConfig(context, p, 1080, 53))
        assertEquals("kp", json.getString("kcp_password"))
        assertEquals("aes", json.getString("kcp_crypt"))
        assertEquals("fast3", json.getString("kcp_mode"))
        assertEquals(256, json.getInt("kcp_sndwnd"))
        assertEquals(1024, json.getInt("kcp_rcvwnd"))
        assertEquals(1400, json.getInt("kcp_mtu"))
        assertEquals(true, json.getBoolean("kcp_nocomp"))
        assertEquals(1, json.getInt("kcp_smuxver"))
        assertEquals(15, json.getInt("kcp_keepalive"))
        assertFalse("kcp_nodelay must not be sent", json.has("kcp_nodelay"))
        assertTrue(json.getBoolean("kcp_nodelay"))
        assertEquals(7, json.getInt("kcp_data_shards"))
        assertEquals(2, json.getInt("kcp_parity_shards"))
    }

    @Test
    fun buildMySshConfig_kcpBlankCryptFallsBackToNone() {
        // myssh treats blank/none as "no encryption"; the app must not force aes
        val p = Profile(
            tunnelType = Profile.TUNNEL_TYPE_KCP,
            kcpCrypt = "",
            dnsOverride = true
        )
        val json = JSONObject(VpnConfigBuilder.buildMySshConfig(context, p, 1080, 53))
        assertEquals("none", json.getString("kcp_crypt"))
    }

    @Test
    fun buildMySshConfig_kcpShardsFallbacks() {
        // 非法分片数应回退到安全默认（data>0 -> 10, parity>=0 -> 3）
        val p = Profile(
            tunnelType = Profile.TUNNEL_TYPE_KCP,
            kcpDataShards = 0,
            kcpParityShards = -1,
            dnsOverride = true
        )
        val json = JSONObject(VpnConfigBuilder.buildMySshConfig(context, p, 1080, 53))
        assertEquals(10, json.getInt("kcp_data_shards"))
        assertEquals(3, json.getInt("kcp_parity_shards"))
    }

    @Test
    fun buildMySshConfig_udpCustomBranch() {
        val p = Profile(
            tunnelType = Profile.TUNNEL_TYPE_UDP_CUSTOM,
            udpCustomPsk = "psk",
            udpCustomMagic = "UDPC",
            udpCustomPublicKey = "udp-npk",
            udpCustomPaths = 12,
            udpCustomSockets = 4,
            udpCustomSendWindow = 512,
            dnsOverride = true
        )
        val json = JSONObject(VpnConfigBuilder.buildMySshConfig(context, p, 1080, 53))
        assertEquals("psk", json.getString("udp_custom_psk"))
        assertEquals("UDPC", json.getString("udp_custom_magic"))
        assertEquals("udp-npk", json.getString("udp_custom_public_key"))
        assertEquals(12, json.getInt("udp_custom_paths"))
        assertEquals(4, json.getInt("udp_custom_sockets"))
        assertEquals(512, json.getInt("udp_custom_send_window"))
        assertFalse("noise_public_key must not be sent", json.has("noise_public_key"))
    }

    @Test
    fun buildMySshConfig_xhttpChunkSizeBranch() {
        val p = Profile(
            tunnelType = Profile.TUNNEL_TYPE_XHTTP,
            xhttpChunkSizeKB = 320,
            dnsOverride = true
        )
        val json = JSONObject(VpnConfigBuilder.buildMySshConfig(context, p, 1080, 53))
        assertEquals(320, json.getInt("xhttp_chunk_size_kb"))
        // heartbeat is h2-family only
        assertFalse(json.has("heartbeat_interval_ms"))
    }

    @Test
    fun buildMySshConfig_heartbeatForH2Family() {
        for (t in listOf(
            Profile.TUNNEL_TYPE_H2, Profile.TUNNEL_TYPE_GRPC, Profile.TUNNEL_TYPE_H3,
            Profile.TUNNEL_TYPE_MASQUE, Profile.TUNNEL_TYPE_WEBTRANSPORT
        )) {
            val p = Profile(tunnelType = t, heartbeatIntervalMs = 15000, dnsOverride = true)
            val json = JSONObject(VpnConfigBuilder.buildMySshConfig(context, p, 1080, 53))
            assertEquals("heartbeat for [$t]", 15000, json.getInt("heartbeat_interval_ms"))
        }
        val ws = Profile(tunnelType = Profile.TUNNEL_TYPE_WEBSOCKET, heartbeatIntervalMs = 15000, dnsOverride = true)
        assertFalse(JSONObject(VpnConfigBuilder.buildMySshConfig(context, ws, 1080, 53)).has("heartbeat_interval_ms"))
    }

    @Test
    fun buildMySshConfig_customPathToggleIsMasqueOnly() {
        val alwaysCustomPath = listOf(
            Profile.TUNNEL_TYPE_WEBSOCKET, Profile.TUNNEL_TYPE_H2, Profile.TUNNEL_TYPE_H3,
            Profile.TUNNEL_TYPE_WEBTRANSPORT, Profile.TUNNEL_TYPE_GRPC,
            Profile.TUNNEL_TYPE_XHTTP
        )
        alwaysCustomPath.forEach { tunnelType ->
            val profile = Profile(
                tunnelType = tunnelType,
                customPath = "/custom/$tunnelType",
                enableCustomPath = false,
                dnsOverride = true
            )
            val json = JSONObject(VpnConfigBuilder.buildMySshConfig(context, profile, 1080, 53))
            assertEquals("path for [$tunnelType]", "/custom/$tunnelType", json.getString("custom_path"))
        }

        val masqueDefault = Profile(
            tunnelType = Profile.TUNNEL_TYPE_MASQUE,
            customPath = "/ignored",
            enableCustomPath = false,
            dnsOverride = true
        )
        assertEquals("", JSONObject(VpnConfigBuilder.buildMySshConfig(context, masqueDefault, 1080, 53)).getString("custom_path"))

        val masqueCustom = masqueDefault.copy(enableCustomPath = true, customPath = "/masque/custom")
        assertEquals("/masque/custom", JSONObject(VpnConfigBuilder.buildMySshConfig(context, masqueCustom, 1080, 53)).getString("custom_path"))
    }

    @Test
    fun buildMySshConfig_legacyNoiseAndUdpCredentialsRemainCompatible() {
        val dns = Profile(
            tunnelType = Profile.TUNNEL_TYPE_DNS,
            noisePublicKey = "legacy-key",
            dnsOverride = true
        )
        assertEquals(
            "legacy-key",
            JSONObject(VpnConfigBuilder.buildMySshConfig(context, dns, 1080, 53)).getString("dns_tunnel_public_key")
        )

        val udp = Profile(
            tunnelType = Profile.TUNNEL_TYPE_UDP_CUSTOM,
            sshAddr = "legacy.example:22",
            proxyAddr = "",
            pass = "legacy-psk",
            udpCustomPsk = "",
            noisePublicKey = "legacy-key",
            dnsOverride = true
        )
        val udpJson = JSONObject(VpnConfigBuilder.buildMySshConfig(context, udp, 1080, 53))
        assertEquals("legacy.example:22", udpJson.getString("proxy_addr"))
        assertEquals("legacy-psk", udpJson.getString("udp_custom_psk"))
        assertEquals("legacy-key", udpJson.getString("udp_custom_public_key"))
    }

    @Test
    fun buildGlobalConfig_mapsDnsOverrides() {
        val p = Profile(
            dnsOverride = true,
            remoteDns = "1.1.1.1",
            localDns = "8.8.8.8",
            geositeDirect = "tag1,tag2",
            geoipDirect = "ip1"
        )
        val json = JSONObject(VpnConfigBuilder.buildGlobalConfig(context, p))
        assertEquals("1.1.1.1", json.getString("remote_dns_server"))
        assertEquals("8.8.8.8", json.getString("local_dns_server"))

        val site = json.getJSONArray("direct_site_tags")
        assertEquals(2, site.length())
        assertEquals("tag1", site.getString(0))
        assertEquals("tag2", site.getString(1))

        val ip = json.getJSONArray("direct_ip_tags")
        assertEquals(1, ip.length())
        assertEquals("ip1", ip.getString(0))
    }

    @Test
    fun buildMySshConfig_dnsTunnelPskMarker() {
        val base = Profile(
            name = "dns", tunnelType = Profile.TUNNEL_TYPE_DNS, dnsOverride = true,
            sshAddr = "1.2.3.4:22", dnsTunnelDomain = "t.example", dnsTunnelServers = "1.1.1.1"
        )
        val with = JSONObject(VpnConfigBuilder.buildMySshConfig(context,
            base.copy(dnsTunnelPsk = "psk-value", dnsTunnelMarker = "mk"), 1080, 53))
        assertEquals("psk-value", with.getString("dns_tunnel_psk"))
        assertEquals("mk", with.getString("dns_tunnel_marker"))

        val without = JSONObject(VpnConfigBuilder.buildMySshConfig(context, base, 1080, 53))
        assertFalse(without.has("dns_tunnel_psk"))
        assertFalse(without.has("dns_tunnel_marker"))
    }

    @Test
    fun buildMySshConfig_icmpPeerIsBareHost() {
        // myssh: proxy_addr 为 ICMP peer host，无端口；误存的 :port 在 builder 层剥除
        val p = Profile(
            name = "icmp", tunnelType = Profile.TUNNEL_TYPE_ICMP_CUSTOM, dnsOverride = true,
            sshAddr = "1.2.3.4:22", proxyAddr = "5.6.7.8:20000", icmpCustomPsk = "k".repeat(16)
        )
        val json = JSONObject(VpnConfigBuilder.buildMySshConfig(context, p, 1080, 53))
        assertEquals("5.6.7.8", json.getString("proxy_addr"))
    }

    @Test
    fun buildMySshConfig_tlsGatingForSniAndAlpn() {
        // 明文 raw：server_name/alpn 必须为空（myssh 明文路径忽略它们）
        val plain = Profile(
            name = "n", tunnelType = Profile.TUNNEL_TYPE_RAW, tunnelTlsEnabled = false, dnsOverride = true,
            sshAddr = "1.2.3.4:22", proxyAddr = "5.6.7.8:443", serverName = "sni.example", alpn = "h2"
        )
        val pj = JSONObject(VpnConfigBuilder.buildMySshConfig(context, plain, 1080, 53))
        assertEquals("", pj.getString("server_name"))
        assertEquals("", pj.getString("alpn"))

        // xhttp+TLS：alpn（唯一消费者）原样下发
        val x = plain.copy(tunnelType = Profile.TUNNEL_TYPE_XHTTP, tunnelTlsEnabled = true, alpn = "h2,http/1.1")
        val xj = JSONObject(VpnConfigBuilder.buildMySshConfig(context, x, 1080, 53))
        assertEquals("h2,http/1.1", xj.getString("alpn"))
        assertEquals("sni.example", xj.getString("server_name"))
    }
}
