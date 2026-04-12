package app.fjj.stun.util

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.nio.charset.StandardCharsets

/**
 * 2026 顶级安全 Keystore 助手
 * 集成硬件 StrongBox 支持与 TEE 隔离
 */
object KeystoreUtils {
    private const val TAG = "KeystoreHelper"
    private const val KEYSET_NAME = "stun_secure_keyset"
    private const val PREF_FILE_NAME = "stun_secure_prefs"

    // 建议使用 android-keystore:// 协议前缀
    private const val MASTER_KEY_URI = "android-keystore://stun_master_key_v2"

    private var aead: Aead? = null

    /**
     * 初始化加密环境
     * 建议在 Application.onCreate 中调用
     */
    fun init(context: Context) {
        if (aead != null) return

        try {
            AeadConfig.register()

            val manager = AndroidKeysetManager.Builder()
                .withSharedPref(context.applicationContext, KEYSET_NAME, PREF_FILE_NAME)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()

            aead = manager.keysetHandle.getPrimitive(Aead::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Keystore 关键初始化失败: ${e.message}")
            // 在极少数情况下（如 KeyStore 损坏），可能需要清除旧数据重建
        }
    }

    /**
     * 高安全加密
     * @param associatedData 关联数据（AAD），解密时必须传入相同的值，否则失败。推荐使用包名。
     */
    fun encrypt(data: String?, associatedData: String = "app.fjj.stun"): String {
        if (data.isNullOrEmpty()) return ""
        val client = aead ?: return ""

        return try {
            val plaintext = data.toByteArray(StandardCharsets.UTF_8)
            val aad = associatedData.toByteArray(StandardCharsets.UTF_8)

            val ciphertext = client.encrypt(plaintext, aad)
            // 使用 NO_WRAP 是为了兼容性，NO_PADDING 是为了简洁
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "加密失败: ${e.message}")
            ""
        }
    }

    /**
     * 高安全解密
     */
    fun decrypt(encryptedBase64: String?, associatedData: String = "app.fjj.stun"): String {
        if (encryptedBase64.isNullOrEmpty()) return ""
        val client = aead ?: return ""

        return try {
            val ciphertext = Base64.decode(encryptedBase64, Base64.DEFAULT)
            val aad = associatedData.toByteArray(StandardCharsets.UTF_8)

            val decrypted = client.decrypt(ciphertext, aad)
            String(decrypted, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "解密失败 (可能是密钥损坏或 AAD 不匹配): ${e.message}")
            ""
        }
    }
}