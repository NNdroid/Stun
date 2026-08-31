package app.fjj.stun.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Profile 实体测试：守护隧道类型枚举完整性、DNS 隧道类型默认值等基础契约，
 * 避免后续增删 TUNNEL_TYPE 时遗漏（如 dns_custom / kcp / udp_custom 等扩展类型）。
 */
@RunWith(AndroidJUnit4::class)
class ProfileTest {

    @Test
    fun getAllTunnelTypes_containsExtendedTypes() {
        val types = Profile.getAllTunnelTypes()
        assertTrue("应包含 TLS 基础类型", types.contains(Profile.TUNNEL_TYPE_TLS))
        assertTrue("应包含 DNS 隧道类型 dns_custom", types.contains(Profile.TUNNEL_TYPE_DNS))
        assertTrue("应包含 KCP 类型", types.contains(Profile.TUNNEL_TYPE_KCP))
        assertTrue("应包含 UDP-Custom 类型", types.contains(Profile.TUNNEL_TYPE_UDP_CUSTOM))
    }

    @Test
    fun dnsTunnelType_defaultIsTxt() {
        val p = Profile()
        assertEquals("txt", p.dnsTunnelType)
    }

    @Test
    fun authTypeConstants() {
        assertEquals("password", Profile.AUTH_TYPE_PASSWORD)
        assertEquals("privatekey", Profile.AUTH_TYPE_PRIVATEKEY)
    }
}
