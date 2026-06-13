package app.fjj.stun.util

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.nio.charset.StandardCharsets

object KeystoreUtils {
    private const val KEYSET_NAME = "stun_secure_keyset"
    private const val PREF_FILE_NAME = "stun_secure_prefs"
    private const val MASTER_KEY_URI = "android-keystore://stun_master_key_v2"

    private var aead: Aead? = null
    private var packageName: String? = null

    @Synchronized
    fun init(context: Context) {
        if (aead != null) return

        try {
            AeadConfig.register()
            this.packageName = context.applicationContext.packageName

            val manager = AndroidKeysetManager.Builder()
                .withSharedPref(context.applicationContext, KEYSET_NAME, PREF_FILE_NAME)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()

            aead = manager.keysetHandle.getPrimitive(
                RegistryConfiguration.get(),
                Aead::class.java
            )
        } catch (e: Exception) {
            throw RuntimeException("Keystore 初始化失败", e)
        }
    }

    fun encrypt(data: String?, associatedData: String? = null): String {
        if (data.isNullOrEmpty()) return ""
        val client = aead ?: throw IllegalStateException("Keystore 未初始化")

        val aad = (associatedData ?: packageName ?: "")
            .toByteArray(StandardCharsets.UTF_8)

        return try {
            val plaintext = data.toByteArray(StandardCharsets.UTF_8)
            val ciphertext = client.encrypt(plaintext, aad)
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        } catch (e: Exception) {
            throw RuntimeException("加密失败", e)
        }
    }

    fun decrypt(encryptedBase64: String?, associatedData: String? = null): String {
        if (encryptedBase64.isNullOrEmpty()) return ""
        val client = aead ?: throw IllegalStateException("Keystore 未初始化")

        val aad = (associatedData ?: packageName ?: "")
            .toByteArray(StandardCharsets.UTF_8)

        return try {
            val ciphertext = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            val decrypted = client.decrypt(ciphertext, aad)
            String(decrypted, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            throw RuntimeException("解密失败", e)
        }
    }
}
