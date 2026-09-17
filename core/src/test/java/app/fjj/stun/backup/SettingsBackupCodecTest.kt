package app.fjj.stun.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SettingsBackupCodec] 的纯 JVM 单测 —— 它是「新增设置字段零改动」这条链路的守门人。
 *
 * 刻意不使用 Robolectric：编解码器本身零 Android 依赖，注入假的加解密函数即可完整覆盖。
 */
class SettingsBackupCodecTest {

    private val cipherPrefix = "ENC:"

    /** 假 Keystore：encrypt 加前缀，decrypt 去前缀。 */
    private val fakeEncrypt: (String) -> String = { cipherPrefix + it.reversed() }
    private val fakeDecrypt: (String) -> String = { raw ->
        if (raw.startsWith(cipherPrefix)) raw.removePrefix(cipherPrefix).reversed() else raw
    }

    // ── 密文识别 ──

    @Test
    fun `keystore cipher detected by prefix only`() {
        assertTrue(SettingsBackupCodec.isKeystoreCipher("ENC:whatever"))
        assertFalse(SettingsBackupCodec.isKeystoreCipher("plain_value"))
        assertFalse(SettingsBackupCodec.isKeystoreCipher(""))
        assertFalse(SettingsBackupCodec.isKeystoreCipher(null))
    }

    // ── 导出：类型标签 ──

    @Test
    fun `encode plain string has no enc flag`() {
        val entry = SettingsBackupCodec.encode("hello", fakeDecrypt)
        assertEquals(SettingsBackupCodec.STRING, SettingsBackupCodec.typeOf(entry!!))
        assertEquals("hello", entry["v"])
        assertFalse(SettingsBackupCodec.isEncrypted(entry))
    }

    @Test
    fun `encode ciphertext exports plaintext and marks enc`() {
        val entry = SettingsBackupCodec.encode(cipherPrefix + "dlrow", fakeDecrypt)!!
        assertEquals(SettingsBackupCodec.STRING, SettingsBackupCodec.typeOf(entry))
        assertEquals("world", entry["v"])
        assertTrue(SettingsBackupCodec.isEncrypted(entry))
    }

    @Test
    fun `encode undecryptable ciphertext keeps raw value without enc flag`() {
        // Keystore 被重置：解不开时应原样带出，至少不丢字段
        val raw = cipherPrefix + "broken"
        val entry = SettingsBackupCodec.encode(raw) { "" }!!
        assertEquals(raw, entry["v"])
        assertFalse(SettingsBackupCodec.isEncrypted(entry))
    }

    @Test
    fun `encode primitive types use proper tags`() {
        assertEquals(SettingsBackupCodec.BOOL, SettingsBackupCodec.typeOf(SettingsBackupCodec.encode(true, fakeDecrypt)!!))
        assertEquals(SettingsBackupCodec.INT, SettingsBackupCodec.typeOf(SettingsBackupCodec.encode(42, fakeDecrypt)!!))
        assertEquals(SettingsBackupCodec.LONG, SettingsBackupCodec.typeOf(SettingsBackupCodec.encode(42L, fakeDecrypt)!!))
        assertEquals(SettingsBackupCodec.FLOAT, SettingsBackupCodec.typeOf(SettingsBackupCodec.encode(1.5f, fakeDecrypt)!!))
    }

    @Test
    fun `encode string set is supported not silently dropped`() {
        val entry = SettingsBackupCodec.encode(setOf("a", "b"), fakeDecrypt)!!
        assertEquals(SettingsBackupCodec.STRING_SET, SettingsBackupCodec.typeOf(entry))
        assertEquals(setOf("a", "b"), (entry["v"] as List<*>).toSet())
    }

    @Test
    fun `encode unsupported type returns null so caller can warn`() {
        assertNull(SettingsBackupCodec.encode(ByteArray(3), fakeDecrypt))
    }

    // ── 恢复 ──

    @Test
    fun `decode plain string stays plain`() {
        val decoded = SettingsBackupCodec.decode(mapOf("t" to "s", "v" to "abc"), fakeEncrypt)!!
        assertEquals(SettingsBackupCodec.STRING, decoded.type)
        assertEquals("abc", decoded.value)
    }

    @Test
    fun `decode enc entry re-encrypts with local key`() {
        val decoded = SettingsBackupCodec.decode(
            mapOf("t" to "s", "v" to "secret", "enc" to true), fakeEncrypt
        )!!
        assertEquals(cipherPrefix + "terces", decoded.value)
    }

    @Test
    fun `decode enc entry returns null when local encryption unavailable`() {
        // 加密失败时宁可跳过，也不能把密钥明文写进 prefs
        assertNull(
            SettingsBackupCodec.decode(
                mapOf("t" to "s", "v" to "secret", "enc" to true),
                encryptSecret = { "" },
            )
        )
    }

    @Test
    fun `decode forceEncrypt covers legacy plaintext secret backups`() {
        // 旧格式：webdav_pass 以明文导出且没有 enc 标记；本机该键原本是密文 → 继续加密存储
        val decoded = SettingsBackupCodec.decode(
            mapOf("t" to "s", "v" to "legacy"), fakeEncrypt, forceEncrypt = true
        )!!
        assertEquals(cipherPrefix + "ycagel", decoded.value)
    }

    @Test
    fun `decode numbers coming back as Double are narrowed correctly`() {
        assertEquals(7, (SettingsBackupCodec.decode(mapOf("t" to "i", "v" to 7.0), fakeEncrypt)!!.value as Number).toInt())
        assertEquals(9L, (SettingsBackupCodec.decode(mapOf("t" to "l", "v" to 9.0), fakeEncrypt)!!.value as Number).toLong())
        val f = (SettingsBackupCodec.decode(mapOf("t" to "f", "v" to 1.25), fakeEncrypt)!!.value as Number).toFloat()
        assertEquals(1.25f, f, 0.0001f)
        assertEquals(true, SettingsBackupCodec.decode(mapOf("t" to "b", "v" to true), fakeEncrypt)!!.value)
    }

    @Test
    fun `decode string set from json array`() {
        val decoded = SettingsBackupCodec.decode(mapOf("t" to "ss", "v" to listOf("x", "y")), fakeEncrypt)!!
        assertEquals(setOf("x", "y"), decoded.value)
    }

    @Test
    fun `decode corrupted entries return null`() {
        assertNull(SettingsBackupCodec.decode(emptyMap(), fakeEncrypt))
        assertNull(SettingsBackupCodec.decode(mapOf("t" to "s"), fakeEncrypt))
        assertNull(SettingsBackupCodec.decode(mapOf("t" to "s", "v" to 123), fakeEncrypt))
        assertNull(SettingsBackupCodec.decode(mapOf("t" to "unknown", "v" to "x"), fakeEncrypt))
    }

    // ── 往返 ──

    @Test
    fun `round trip preserves every supported type`() {
        val samples = mapOf(
            "str" to "hello",
            "bool" to true,
            "int" to 7,
            "long" to 9L,
            "float" to 1.25f,
            "set" to setOf("a", "b"),
        )
        samples.forEach { (key, raw) ->
            val encoded = SettingsBackupCodec.encode(raw, fakeDecrypt)
            assertNotNull("encode failed for $key", encoded)
            val decoded = SettingsBackupCodec.decode(encoded!!, fakeEncrypt)
            assertNotNull("decode failed for $key", decoded)
            assertEquals("round trip mismatch for $key", raw, decoded!!.value)
        }
    }
}
