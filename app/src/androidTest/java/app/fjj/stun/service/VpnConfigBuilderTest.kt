package app.fjj.stun.service

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.fjj.stun.repo.Profile
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
            tunnelType = Profile.TUNNEL_TYPE_TLS,
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
        assertEquals(Profile.TUNNEL_TYPE_TLS, json.getString("tunnel_type"))
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
                noisePublicKey = "npk",
                dnsOverride = true
            )
            val json = JSONObject(VpnConfigBuilder.buildMySshConfig(context, p, 1080, 53))
            assertEquals("dns_tunnel_type mismatch for [$t]", t, json.getString("dns_tunnel_type"))
            assertEquals("t.example.com", json.getString("dns_tunnel_domain"))

            val servers = json.getJSONArray("dns_tunnel_servers")
            assertEquals(2, servers.length())
            assertEquals("1.1.1.1", servers.getString(0))
            assertEquals("8.8.8.8", servers.getString(1))

            assertEquals("npk", json.getString("noise_public_key"))
            assertEquals("npk", json.getString("dns_tunnel_public_key"))
        }
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
            kcpNoDelay = true,
            kcpDataShards = 7,
            kcpParityShards = 2,
            dnsOverride = true
        )
        val json = JSONObject(VpnConfigBuilder.buildMySshConfig(context, p, 1080, 53))
        assertEquals("kp", json.getString("kcp_password"))
        assertEquals("aes", json.getString("kcp_crypt"))
        assertTrue(json.getBoolean("kcp_nodelay"))
        assertEquals(7, json.getInt("kcp_data_shards"))
        assertEquals(2, json.getInt("kcp_parity_shards"))
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
            noisePublicKey = "npk2",
            dnsOverride = true
        )
        val json = JSONObject(VpnConfigBuilder.buildMySshConfig(context, p, 1080, 53))
        assertEquals("psk", json.getString("udp_custom_psk"))
        assertEquals("UDPC", json.getString("udp_custom_magic"))
        assertEquals("npk2", json.getString("udp_custom_public_key"))
        assertEquals("npk2", json.getString("noise_public_key"))
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
}
