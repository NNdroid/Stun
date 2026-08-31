package app.fjj.stun.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.fjj.stun.repo.Profile
import com.google.gson.Gson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 针对 Stun 分享/导入导出链路的测试：
 *   Profile -> Gson -> ShareCryptoUtils.encrypt(json, pin) -> 二维码/文件
 *   -> ShareCryptoUtils.decrypt(payload, pin) -> Gson -> Profile
 *
 * 覆盖：
 *  - 小节点 / 大节点（含 gzip 压缩，复现并守护此前 "data too big" 修复）往返一致
 *  - g 标志位：小数据不压缩(g=0)、大数据压缩(g=1)
 *  - 错误 PIN、乱码输入安全返回 null
 *  - 旧格式（无 g 字段）向后兼容
 *  - isEncryptedPayload 识别
 *  - generateRandomPIN 格式
 *
 * 注意：本测试为 instrumented 测试，依赖 Android 运行时（android.util.Base64 等），
 * 请在模拟器/真机上运行（./gradlew :app:connectedDebugAndroidTest 或 Android Studio 右键运行）。
 */
@RunWith(AndroidJUnit4::class)
class ShareCryptoUtilsTest {

    private val gson = Gson()
    private val pin = "123456"

    @Test
    fun roundTrip_smallProfile() {
        val profile = Profile(
            name = "TestNode",
            sshAddr = "1.2.3.4:22",
            user = "u",
            pass = "p",
            tunnelType = Profile.TUNNEL_TYPE_TLS
        )
        val json = gson.toJson(profile)
        val payload = ShareCryptoUtils.encrypt(json, pin)

        assertNotNull(payload)
        assertTrue(ShareCryptoUtils.isEncryptedPayload(payload!!))

        val decrypted = ShareCryptoUtils.decrypt(payload, pin)
        assertNotNull(decrypted)

        val restored = gson.fromJson(decrypted, Profile::class.java)
        // 序列化往返应与原始对象完全一致（含 id 字段）
        assertEquals(profile, restored)
    }

    @Test
    fun largeProfile_compressesBelowQrCapacity() {
        // 构造含超长私钥 + 巨量 payload 的节点：未压缩时远超 QR(L) 2953 字节上限
        val big = Profile(
            name = "BigNode",
            privateKey = "A".repeat(5000),
            httpPayload = "X".repeat(3000)
        )
        val json = gson.toJson(big)
        assertTrue("未压缩 JSON 应超过 QR 上限以复现原 bug", json.length > 2953)

        val payload = ShareCryptoUtils.encrypt(json, pin)
        assertNotNull(payload)

        // 压缩后 base64 字符串应回到 QR(L) 容量内 —— 这正是此前 "data too big" 的修复点
        assertTrue("加密产物应 <= 2953 字节以适配二维码，实际=${payload!!.length}", payload.length <= 2953)

        // 且仍能正确还原
        val decrypted = ShareCryptoUtils.decrypt(payload, pin)
        assertNotNull(decrypted)
        val restored = gson.fromJson(decrypted, Profile::class.java)
        assertEquals(big.privateKey, restored.privateKey)
        assertEquals(big.httpPayload, restored.httpPayload)
    }

    @Test
    fun gzipFlag_smallProfile_notGzipped() {
        val json = gson.toJson(Profile(name = "Small"))
        val payload = ShareCryptoUtils.encrypt(json, pin)!!
        val inner = decodeOuter(payload)
        assertEquals(0, inner.optInt("g", 0))
    }

    @Test
    fun gzipFlag_largeProfile_gzipped() {
        val big = Profile(privateKey = "B".repeat(5000))
        val payload = ShareCryptoUtils.encrypt(gson.toJson(big), pin)!!
        val inner = decodeOuter(payload)
        assertEquals(1, inner.optInt("g", 0))
    }

    @Test
    fun decrypt_wrongPin_returnsNull() {
        val json = gson.toJson(Profile(name = "N"))
        val payload = ShareCryptoUtils.encrypt(json, pin)!!
        assertNull(ShareCryptoUtils.decrypt(payload, "000000"))
    }

    @Test
    fun decrypt_garbage_returnsNull() {
        assertNull(ShareCryptoUtils.decrypt("not-a-valid-payload", pin))
        assertFalse(ShareCryptoUtils.isEncryptedPayload("not-a-valid-payload"))
    }

    @Test
    fun backwardCompat_oldFormatWithoutG() {
        // 旧版本加密产物不含 g 字段，解密应仍可用（optInt("g",0) == 0 分支）
        val json = gson.toJson(Profile(name = "Legacy", sshAddr = "9.9.9.9:22"))
        val payload = ShareCryptoUtils.encrypt(json, pin)!!
        val stripped = stripGFlag(payload)

        assertTrue(ShareCryptoUtils.isEncryptedPayload(stripped))
        val decrypted = ShareCryptoUtils.decrypt(stripped, pin)
        assertNotNull(decrypted)

        val restored = gson.fromJson(decrypted, Profile::class.java)
        assertEquals("Legacy", restored.name)
        assertEquals("9.9.9.9:22", restored.sshAddr)
    }

    @Test
    fun generateRandomPIN_isSixDigits() {
        val p = ShareCryptoUtils.generateRandomPIN()
        assertTrue("PIN 应为 6 位数字，实际=$p", p.matches(Regex("\\d{6}")))
    }

    private fun decodeOuter(payload: String): JSONObject {
        val clean = payload.trim().replace("\\s".toRegex(), "")
        val jsonString = String(android.util.Base64.decode(clean, android.util.Base64.DEFAULT), Charsets.UTF_8)
        return JSONObject(jsonString)
    }

    private fun stripGFlag(payload: String): String {
        val obj = decodeOuter(payload)
        obj.remove("g")
        return android.util.Base64.encodeToString(
            obj.toString().toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
    }
}
