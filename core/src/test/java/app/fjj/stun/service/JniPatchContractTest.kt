package app.fjj.stun.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JniPatchContractTest {
    private fun patch(name: String): String {
        val root = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(root, "jni/patches/$name.patch"),
            File(root, "core/jni/patches/$name.patch"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("patch not found: $name; cwd=${root.absolutePath}")
        return file.readText()
    }

    @Test
    fun transparentAndTunFrontendsRecoverDomainBeforeSocksConnect() {
        for (name in listOf("hev-socks5-tproxy", "hev-socks5-tunnel")) {
            val text = patch(name)
            assertTrue("$name must parse TLS SNI", text.contains("sniff_tls"))
            assertTrue("$name must parse HTTP Host", text.contains("sniff_http"))
            assertTrue("$name must emit SOCKS domain targets", text.contains("hev_socks5_addr_from_name"))
            assertTrue("$name must avoid ECH cover-name rewrites", text.contains("type == 0xfe0d"))
        }
    }

    @Test
    fun sniffingDoesNotEnableIpv4OnlyMappedDns() {
        val tunnel = patch("hev-socks5-tunnel")
        assertFalse(tunnel.contains("mapdns:"))
    }
}
