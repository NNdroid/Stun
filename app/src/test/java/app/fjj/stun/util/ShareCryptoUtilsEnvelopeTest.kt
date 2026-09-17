package app.fjj.stun.util

import android.app.Application
import android.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ShareCryptoUtils] 信封的**跨版本兼容**基线。
 *
 * 为什么单独钉这个：迭代数被提到 [ShareCryptoUtils.ITERATIONS_BACKUP] 之后，
 * "新版本解不开旧文件 / 旧版本解不开新二维码"这类事故**只在混版本使用时才出现**，
 * 手测永远覆盖不到。这里把三条契约写死：
 *
 *  ① 默认加密 = 历史迭代数（二维码 / `stun://` / 蓝牙同步与旧版本互通，谁都不能悄悄改）
 *  ② 老信封（没有 `it` 字段）照样能解 —— 历史备份文件必须还能恢复
 *  ③ 信封里的 `it` 说了算，但被夹在合理区间内（外部可控输入不能让我们按伪造值烧 CPU）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShareCryptoUtilsEnvelopeTest {

    private val pin = "246810"

    private fun decode(payload: String): JSONObject =
        JSONObject(String(Base64.decode(payload, Base64.DEFAULT), Charsets.UTF_8))

    private fun reencode(json: JSONObject): String =
        Base64.encodeToString(json.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    /** 拿历史迭代数加密再把 `it` 抹掉，等价于"旧版本 App 写出来的信封"。 */
    private fun legacyEnvelope(plain: String): String =
        reencode(decode(ShareCryptoUtils.encrypt(plain, pin, ShareCryptoUtils.ITERATIONS_LEGACY)).apply { remove("it") })

    @Test
    fun `默认加密用历史迭代数_二维码与旧版本保持互通`() {
        val payload = ShareCryptoUtils.encrypt("shared node", pin)
        assertEquals(ShareCryptoUtils.ITERATIONS_LEGACY, decode(payload).getInt("it"))
        assertEquals("shared node", ShareCryptoUtils.decrypt(payload, pin))
    }

    @Test
    fun `备份用加码迭代数_信封结构与 v1 一致`() {
        val payload = ShareCryptoUtils.encrypt("node json", pin, ShareCryptoUtils.ITERATIONS_BACKUP)
        val json = decode(payload)
        assertEquals(ShareCryptoUtils.ITERATIONS_BACKUP, json.getInt("it"))
        // v 不动：结构没变，变的只是 KDF 强度，靠 it 描述
        assertEquals(1, json.getInt("v"))
        assertEquals("node json", ShareCryptoUtils.decrypt(payload, pin))
    }

    @Test
    fun `没有 it 字段的老信封仍按历史迭代数解开`() {
        val stripped = legacyEnvelope("legacy node")
        assertFalse(decode(stripped).has("it"))
        assertEquals("legacy node", ShareCryptoUtils.decrypt(stripped, pin))
    }

    @Test
    fun `信封里的 it 说了算_写错就解不开`() {
        val payload = ShareCryptoUtils.encrypt("x", pin, ShareCryptoUtils.ITERATIONS_LEGACY)
        val tampered = reencode(decode(payload).apply { put("it", 20_000) })
        assertNull(ShareCryptoUtils.decrypt(tampered, pin))
    }

    @Test
    fun `低于下界的 it 被夹住_不会按伪造值做 KDF`() {
        // 下界 1000；写成 1 会被夹到 1000，与真实迭代数不符 ⇒ 解不开，但一定要"立刻"解不开
        val tampered = reencode(decode(ShareCryptoUtils.encrypt("x", pin, ShareCryptoUtils.ITERATIONS_LEGACY)).apply { put("it", 1) })
        val started = System.currentTimeMillis()
        assertNull(ShareCryptoUtils.decrypt(tampered, pin))
        assertTrue("越界值不该触发长时间 KDF", System.currentTimeMillis() - started < 5_000)
    }

    @Test
    fun `PIN 不对或载荷损坏一律返回 null`() {
        val payload = ShareCryptoUtils.encrypt("secret", pin)
        assertNull(ShareCryptoUtils.decrypt(payload, "000000"))
        assertNull(ShareCryptoUtils.decrypt("not-a-payload", pin))
        assertFalse(ShareCryptoUtils.isEncryptedPayload("not-a-payload"))
    }
}
