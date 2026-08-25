package app.fjj.stun.util

import android.util.Base64
import java.security.SecureRandom
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
        val ciphertext = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        
        val json = JSONObject()
        json.put("v", 1) // version
        json.put("s", Base64.encodeToString(salt, Base64.NO_WRAP))
        json.put("i", Base64.encodeToString(iv, Base64.NO_WRAP))
        json.put("c", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        
        return Base64.encodeToString(json.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    // Decrypts the Base64 payload using the PIN
    fun decrypt(encryptedPayload: String, pin: String): String? {
        try {
            val jsonString = String(Base64.decode(encryptedPayload, Base64.DEFAULT), Charsets.UTF_8)
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
            return String(plaintext, Charsets.UTF_8)
        } catch (e: Exception) {
            return null
        }
    }

    fun isEncryptedPayload(payload: String): Boolean {
        try {
            val jsonString = String(Base64.decode(payload, Base64.DEFAULT), Charsets.UTF_8)
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
}
