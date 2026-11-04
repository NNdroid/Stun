package app.fjj.stun.backup

import android.app.Application
import android.content.Context
import app.fjj.stun.repo.SettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 本地 SOCKS5 认证凭据 × WebDAV 设置备份的契约。
 *
 * [SettingsManager.webDavSettingsSnapshot] 是全量枚举设计（「新增设置字段零改动」），
 * SOCKS5 的三个键（user / pass / initialized）**本就自动纳入**，无需任何清单——
 * 这组用例钉死的是三条跨设备语义，防止将来分类逻辑改动时静默破坏：
 *
 * 1. 凭据随备份走：恢复端拿到源端的 user/pass，认证开关状态原样复现；
 * 2. 恢复后 [SettingsManager.ensureSocks5AuthCredentials] 不得用新生成的随机对
 *    **覆盖**恢复进来的凭据（否则两台设备的凭据永远对不上）；
 * 3. 旧版备份（还没有 SOCKS5 键）恢复时不得清掉/重置目标机现有凭据。
 *
 * Keystore 说明：真实产物中 pass 恒为 `ENC:` 密文，导出解密/恢复重加密由
 * [SettingsBackupCodec] 的假加解密单测覆盖；Robolectric 拉不起真 Keystore，
 * 所以这里种**裸明文**（getSocks5AuthPass 对无 ENC: 前缀的值原样放行）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WebDavSocks5AuthBackupTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    /** Robolectric 能否拉起 Tink/Keystore（拉不起则跳过依赖 encrypt 的分支）。 */
    private var keystoreAvailable = false

    /** 与 SettingsManager 私有键镜像 —— 改键名时这里同步。 */
    private fun prefs() = context.getSharedPreferences("stun_settings", Context.MODE_PRIVATE)

    /** SharedPreferences 会跨测试方法残留，每个用例先清库保证互相独立。 */
    @Before
    fun resetPrefs() {
        prefs().edit().clear().commit()
        context.getSharedPreferences("stun_device_state", Context.MODE_PRIVATE).edit().clear().commit()
        keystoreAvailable = try {
            app.fjj.stun.util.KeystoreUtils.rememberContext(context.applicationContext)
            app.fjj.stun.util.KeystoreUtils.init(context.applicationContext)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun seedCredentials(user: String, pass: String) {
        prefs().edit()
            .putString("socks5_auth_user", user)
            .putString("socks5_auth_pass", pass)
            .putBoolean("socks5_auth_initialized", true)
            .commit()
    }

    @Test
    fun socks5AuthKeysAreIncludedInTheSnapshot() {
        seedCredentials("deviceA", "passA123")
        val snapshot = SettingsManager.webDavSettingsSnapshot(context)
        assertTrue(snapshot.containsKey("socks5_auth_user"))
        assertTrue(snapshot.containsKey("socks5_auth_pass"))
        assertTrue(snapshot.containsKey("socks5_auth_initialized"))
        // 裸明文种子没有 ENC 标记；initialized 是布尔
        val passEntry = snapshot["socks5_auth_pass"]!!
        assertEquals(SettingsBackupCodec.STRING, SettingsBackupCodec.typeOf(passEntry))
        assertFalse(SettingsBackupCodec.isEncrypted(passEntry))
        assertEquals(SettingsBackupCodec.BOOL, SettingsBackupCodec.typeOf(snapshot["socks5_auth_initialized"]!!))
    }

    @Test
    fun restoredCredentialsSurviveBootstrapRegeneration() {
        // 设备 A：备份
        seedCredentials("deviceA", "passA123")
        val snapshot = SettingsManager.webDavSettingsSnapshot(context)

        // 设备 B：全新首启 —— 清库（模拟换机）后 bootstrap 生成 B 自己的随机对。
        // Robolectric 拉不起 Keystore 时跳过这半段：恢复后 flag=true + 凭据为恢复值，
        // bootstrap 只会是 no-op，「不得覆盖」的核心断言不受影响。
        prefs().edit().clear().commit()
        if (keystoreAvailable) {
            SettingsManager.ensureSocks5AuthCredentials(context)
            assertNotEquals("deviceA", SettingsManager.getSocks5AuthUser(context))
            assertNotEquals("passA123", SettingsManager.getSocks5AuthPass(context))
        }

        // 拉取云备份额覆盖本机
        SettingsManager.applyWebDavSettingsSnapshot(context, snapshot)
        assertEquals("凭据应随备份走，而不是留在恢复端自己的随机对", "deviceA", SettingsManager.getSocks5AuthUser(context))
        assertEquals("passA123", SettingsManager.getSocks5AuthPass(context))
        assertTrue(SettingsManager.isSocks5AuthEnabled(context))
        // 关键：恢复后再跑 bootstrap（每次冷启动都会调），绝不能用新生成的对覆盖恢复值
        SettingsManager.ensureSocks5AuthCredentials(context)
        assertEquals("deviceA", SettingsManager.getSocks5AuthUser(context))
        assertEquals("passA123", SettingsManager.getSocks5AuthPass(context))
    }

    @Test
    fun legacyBackupWithoutSocks5KeysLeavesLocalCredentialsAlone() {
        // 旧版备份里还没有 SOCKS5 键：恢复不得清掉目标机现有凭据，
        // 也不得把它们当成「未初始化」重新生成
        seedCredentials("local", "localpass")
        val legacyCloud = mapOf(
            "remote_dns" to mapOf("t" to SettingsBackupCodec.STRING, "v" to "8.8.8.8:53"),
        )
        SettingsManager.applyWebDavSettingsSnapshot(context, legacyCloud)
        SettingsManager.ensureSocks5AuthCredentials(context)
        assertEquals("local", SettingsManager.getSocks5AuthUser(context))
        assertEquals("localpass", SettingsManager.getSocks5AuthPass(context))
        assertTrue(SettingsManager.isSocks5AuthEnabled(context))
    }

    @Test
    fun clearedCredentialsStayClearedAfterRestore() {
        // 设备 A 用户清空两项 = 关闭认证：这个决定必须跨设备成立，
        // 恢复端 bootstrap 不得因为「两项为空」就把认证又打开
        seedCredentials("", "")
        val snapshot = SettingsManager.webDavSettingsSnapshot(context)

        SettingsManager.ensureSocks5AuthCredentials(context)   // 本机有凭据
        prefs().edit().clear().commit()
        SettingsManager.applyWebDavSettingsSnapshot(context, snapshot)
        SettingsManager.ensureSocks5AuthCredentials(context)   // 冷启动再跑一次

        assertFalse("清空 = 关闭认证的决定应随备份跨设备", SettingsManager.isSocks5AuthEnabled(context))
    }
}
