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
    private const val ITERATION_COUNT = 10000
    private const val KEY_LENGTH = 256

    // Generates a random 6 digit PIN
    fun generateRandomPIN(): String {
        val random = SecureRandom()
        val pin = random.nextInt(1000000)
        return String.format("%06d", pin)
    }

    // Encrypts plain text using the PIN and returns a Base64 encoded JSON string
    fun encrypt(plainText: String, pin: String): String {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH)
        random.nextBytes(salt)

        val iv = ByteArray(IV_LENGTH)
        random.nextBytes(iv)

        val secretKey = deriveKey(pin, salt)
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
        json.put("v", 1) // version
        json.put("g", if (useGzip) 1 else 0) // 1 = plaintext was gzipped
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

            val salt = Base64.decode(json.getString("s"), Base64.DEFAULT)
            val iv = Base64.decode(json.getString("i"), Base64.DEFAULT)
            val ciphertext = Base64.decode(json.getString("c"), Base64.DEFAULT)

            val secretKey = deriveKey(pin, salt)
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

    private fun deriveKey(pin: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH)
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
