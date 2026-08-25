package app.fjj.stun.util

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.config.TinkConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.RegistryConfiguration
import java.nio.charset.StandardCharsets
import java.util.Base64

object KeystoreUtils {
    private const val KEYSET_NAME = "stun_keyset"
    private const val PREF_FILE_NAME = "stun_prefs"
    private const val MASTER_KEY_URI = "android-keystore://stun_master_key"

    private var aead: Aead? = null
    private var packageName: String? = null

    fun init(context: Context) {
        packageName = context.packageName
        try {
            AeadConfig.register()
            TinkConfig.register()

            val manager = AndroidKeysetManager.Builder()
                .withSharedPref(context, KEYSET_NAME, PREF_FILE_NAME)
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
            val ciphertext = client.encrypt(data.toByteArray(StandardCharsets.UTF_8), aad)
            "ENC:" + Base64.getEncoder().encodeToString(ciphertext)
        } catch (e: Exception) {
            ""
        }
    }

    fun decrypt(encryptedData: String?, associatedData: String? = null): String {
        if (encryptedData.isNullOrEmpty()) return ""
        val client = aead ?: throw IllegalStateException("Keystore 未初始化")

        val aad = (associatedData ?: packageName ?: "")
            .toByteArray(StandardCharsets.UTF_8)

        val isEnc = encryptedData.startsWith("ENC:")
        val targetData = if (isEnc) encryptedData.substring(4) else encryptedData

        return try {
            val ciphertext = Base64.getDecoder().decode(targetData)
            val decrypted = client.decrypt(ciphertext, aad)
            String(decrypted, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            if (!isEnc) {
                return encryptedData
            }
            ""
        }
    }
}
