package app.fjj.stun.service

import android.app.Application
import android.content.Context
import app.fjj.stun.repo.Profile
import app.fjj.stun.repo.SettingsManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 本地 SOCKS5 入站认证（RFC 1929）的下发契约：三份配置产物必须同进同退。
 *
 * 认证开关 = SettingsManager 两项凭据皆非空。开启时 [VpnConfigBuilder.buildMySshConfig]
 * （JSON → myssh）、tunnel YAML、tproxy YAML 三处都要带上同一组凭据，少任何一处
 * 对应的消费者就被 myssh 拒之门外（hev 握手失败 / 出口探测回落直连）；关闭时
 * 三处都**不写键**，产物与无认证旧行为逐字节一致。
 *
 * 测试刻意绕开 Keystore：给 prefs 种**裸明文**（无 `ENC:` 前缀），decrypt 对非
 * 密文原样返回（[app.fjj.stun.util.KeystoreUtils.decrypt] 的兼容分支），Robolectric
 * 环境拉不起真 Keystore，不能走 set/get 的加解密路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class Socks5AuthConfigEmissionTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    // 与 SettingsManager 私有键镜像 —— 改键名时这里同步。
    private fun seedCredentials(user: String, pass: String) {
        context.getSharedPreferences("stun_settings", Context.MODE_PRIVATE).edit()
            .putString("socks5_auth_user", user)
            .putString("socks5_auth_pass", pass)
            .commit()
    }

    // ── tunnel YAML（VPN 模式，hev-socks5-tunnel 进程内 JNI） ──

    @Test
    fun tunnelYamlEmitsCredentialsUnderSocks5Section() {
        val yaml = VpnConfigBuilder.buildHevSocks5TunnelConfig(10808, "alice" to "s3cret")
        val socks5 = yaml.substringAfter("socks5:")
        assertTrue(socks5.contains("username: 'alice'"))
        assertTrue(socks5.contains("password: 's3cret'"))
    }

    @Test
    fun tunnelYamlOmitsCredentialsWhenAuthDisabled() {
        val yaml = VpnConfigBuilder.buildHevSocks5TunnelConfig(10808, null)
        assertFalse(yaml.contains("username"))
        assertFalse(yaml.contains("password"))
    }

    @Test
    fun tunnelYamlEscapesSingleQuotesInCredentials() {
        val yaml = VpnConfigBuilder.buildHevSocks5TunnelConfig(10808, "o'brien" to "p@ss'word")
        assertTrue(yaml.contains("username: 'o''brien'"))
        assertTrue(yaml.contains("password: 'p@ss''word'"))
    }

    // ── buildMySshConfig（JSON → myssh 引擎） ──

    @Test
    fun mysshConfigEmitsAuthKeysWhenEnabled() {
        seedCredentials("testuser", "testpass123")
        val json = JSONObject(VpnConfigBuilder.buildMySshConfig(context, Profile(), 10808, 53))
        assertEquals("testuser", json.getString("socks5_auth_user"))
        assertEquals("testpass123", json.getString("socks5_auth_pass"))
    }

    @Test
    fun mysshConfigOmitsAuthKeysWhenDisabled() {
        // 任一项为空 = 关闭：只填 user 不填 pass 也不许下发（myssh 侧成对校验会拒绝启动）
        seedCredentials("testuser", "")
        val json = JSONObject(VpnConfigBuilder.buildMySshConfig(context, Profile(), 10808, 53))
        assertFalse(json.has("socks5_auth_user"))
        assertFalse(json.has("socks5_auth_pass"))
    }

    // ── 随机凭据生成（设置页「重新生成」的原料） ──

    @Test
    fun generatedCredentialPairsHaveStunPrefixAndUniqueValues() {
        val (u1, p1) = SettingsManager.newSocks5CredentialPair()
        val (u2, p2) = SettingsManager.newSocks5CredentialPair()
        assertTrue(u1.startsWith("stun_"))
        assertTrue(p1.startsWith("stun_"))
        assertEquals("stun_".length + 8, u1.length)
        assertEquals("stun_".length + 16, p1.length)
        assertNotEquals(u1, u2)
        assertNotEquals(p1, p2)
    }
}
