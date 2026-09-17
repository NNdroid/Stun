package app.fjj.stun.util

import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

object ShareCryptoUtils {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_DERIVATION_ALG = "PBKDF2WithHmacSHA256"
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH = 128
    private const val KEY_LENGTH = 256
    private val secureRandom by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { SecureRandom() }

    /**
     * 历史迭代数。v1 信封没有 `it` 字段，缺省即此值 —— **不要动它**。
     *
     * 二维码 / `stun://` 订阅载荷 / 蓝牙同步都是**跨版本、跨设备互换**的密文：
     * 迭代数写死在旧版本 App 里，这里一提高，旧版本就再也解不开新载荷。
     * 这些都是短时效、走带外信道的载荷，加码收益小、破坏面大。
     */
    const val ITERATIONS_LEGACY = 10000

    /**
     * 云备份用的迭代数（20× 于历史值）。
     *
     * 备份是长期躺在第三方网盘上的密文，PIN 又由用户自定（常见只有 4~6 位），
     * 所以只对这条链路加码。[deriveKey] 的开销与迭代数成正比：200k 在中端机上
     * 约 0.2~0.4s，备份/恢复各解 3 个文件也就一秒级，用户感知不到。
     *
     * ⚠️ 代价：**旧版本 App 读不了本版本写出的备份**（它写死 10000 迭代）。
     * 反向兼容不受影响 —— 迭代数记在信封的 `it` 字段里，新版读旧备份照常。
     */
    const val ITERATIONS_BACKUP = 200_000

    /**
     * 允许的迭代数区间。
     *
     * 订阅载荷是**外部可控输入**（谁都能在订阅源里塞一段密文），不设上下界的话
     * 一个 `it: 1000000000` 就能让 App 在解密时长时间卡死。低值同样夹住：
     * 本工具从不写 < 1000 的值，读到就说明信封是伪造的。
     */
    private const val ITERATIONS_MIN = 1000
    private const val ITERATIONS_MAX = 2_000_000

    // Generates a random 6 digit PIN
    fun generateRandomPIN(): String {
        val pin = secureRandom.nextInt(1000000)
        return String.format("%06d", pin)
    }

    // Encrypts plain text using the PIN and returns a Base64 encoded JSON string
    // [iterations] 缺省用历史值，保证二维码/同步载荷与旧版本互通；云备份显式传 [ITERATIONS_BACKUP]
    fun encrypt(plainText: String, pin: String, iterations: Int = ITERATIONS_LEGACY): String {
        val salt = ByteArray(SALT_LENGTH)
        secureRandom.nextBytes(salt)

        val iv = ByteArray(IV_LENGTH)
        secureRandom.nextBytes(iv)

        val safeIterations = iterations.coerceIn(ITERATIONS_MIN, ITERATIONS_MAX)
        val secretKey = deriveKey(pin, salt, safeIterations)
        val cipher = Cipher.getInstance(ALGORITHM)
        val parameterSpec = GCMParameterSpec(TAG_LENGTH, iv)

        cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec)
        // 压缩明文以缩减二维码体积：含长私钥/httpPayload 的大节点 JSON 经 gzip 通常可压到 1/3~1/4，
        // 避免超出 QR 容量上限（纠错级 L 约 2953 字节）导致 "data too big"。仅当压缩后更小才启用。
        val raw = plainText.toByteArray(Charsets.UTF_8)
        val compressed = gzip(raw)
        val useGzip = compressed.size < raw.size
        val dataToEncrypt = if (useGzip) compressed else raw
        val ciphertext = cipher.doFinal(dataToEncrypt)

        val json = JSONObject()
        json.put("v", 1) // version（信封结构版本；KDF 强度由 it 单独描述，不动 v）
        json.put("g", if (useGzip) 1 else 0) // 1 = plaintext was gzipped
        json.put("it", safeIterations) // PBKDF2 迭代数，缺省视为 ITERATIONS_LEGACY
        json.put("s", Base64.encodeToString(salt, Base64.NO_WRAP))
        json.put("i", Base64.encodeToString(iv, Base64.NO_WRAP))
        json.put("c", Base64.encodeToString(ciphertext, Base64.NO_WRAP))

        return Base64.encodeToString(json.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    // Decrypts the Base64 payload using the PIN
    fun decrypt(encryptedPayload: String, pin: String): String? {
        try {
            val clean = encryptedPayload.trim().removePrefix("\uFEFF").replace("\r", "").replace("\n", "").replace(" ", "")
            val jsonString = String(Base64.decode(clean, Base64.DEFAULT), Charsets.UTF_8)
            val json = JSONObject(jsonString)

            if (json.optInt("v", 1) != 1) return null

            // 老信封没有 it ⇒ 历史迭代数；夹在合理区间内，防外部构造的 it 拖死解密
            val iterations = json.optInt("it", ITERATIONS_LEGACY)
                .coerceIn(ITERATIONS_MIN, ITERATIONS_MAX)

            val salt = Base64.decode(json.getString("s"), Base64.DEFAULT)
            val iv = Base64.decode(json.getString("i"), Base64.DEFAULT)
            val ciphertext = Base64.decode(json.getString("c"), Base64.DEFAULT)

            val secretKey = deriveKey(pin, salt, iterations)
            val cipher = Cipher.getInstance(ALGORITHM)
            val parameterSpec = GCMParameterSpec(TAG_LENGTH, iv)

            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec)
            val plaintext = cipher.doFinal(ciphertext)
            // 与 encrypt 对称：g==1 表示明文经 gzip 压缩，需解压还原
            val out = if (json.optInt("g", 0) == 1) gunzip(plaintext) else plaintext
            return String(out, Charsets.UTF_8)
        } catch (e: Exception) {
            return null
        }
    }

    fun isEncryptedPayload(payload: String): Boolean {
        try {
            val clean = payload.trim().removePrefix("\uFEFF").replace("\r", "").replace("\n", "").replace(" ", "")
            val jsonString = String(Base64.decode(clean, Base64.DEFAULT), Charsets.UTF_8)
            val json = JSONObject(jsonString)
            return json.has("v") && json.has("s") && json.has("i") && json.has("c")
        } catch (e: Exception) {
            return false
        }
    }

    private fun deriveKey(pin: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALG)
        val secretKey = factory.generateSecret(spec)
        return SecretKeySpec(secretKey.encoded, "AES")
    }

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray {
        val bis = ByteArrayInputStream(data)
        return GZIPInputStream(bis).use { it.readBytes() }
    }
}
