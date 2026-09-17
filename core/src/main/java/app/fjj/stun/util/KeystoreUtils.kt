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

    private val lock = Any()

    @Volatile
    private var aead: Aead? = null

    @Volatile
    private var packageName: String? = null

    /** 初始化时留存的 Application context —— [ensureInitialized] 的自愈重试需要它。 */
    @Volatile
    private var appContext: Context? = null

    /**
     * 只登记 Application context 与包名（包名参与构造 AAD）。**不做任何 Keystore 动作**，
     * 因此可以放心同步调用，成本就是两次字段赋值。
     *
     * 存在的理由：初始化被挪到 IO 预热之后，需要一个「同步就位、零成本」的锚点，
     * 保证 [ensureInitialized] 在任何时刻都能自愈重试，而不会读到 null 而误报未初始化。
     */
    fun rememberContext(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            appContext = app
            packageName = app.packageName
        }
    }

    /**
     * 幂等 + 线程安全。重复调用（启动期的 IO 预热与某个后台线程同时进来）只会真正初始化
     * 一次，后来者等在锁上拿到同一个结果。
     */
    fun init(context: Context) {
        rememberContext(context)
        synchronized(lock) {
            if (aead != null) return
            val app = appContext ?: throw IllegalStateException("Keystore not initialized: missing Application context")
            try {
                AeadConfig.register()
                TinkConfig.register()

                val manager = AndroidKeysetManager.Builder()
                    .withSharedPref(app, KEYSET_NAME, PREF_FILE_NAME)
                    .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                    .withMasterKeyUri(MASTER_KEY_URI)
                    .build()

                aead = manager.keysetHandle.getPrimitive(
                    RegistryConfiguration.get(),
                    Aead::class.java
                )
            } catch (e: Exception) {
                throw RuntimeException("Keystore initialization failed", e)
            }
        }
    }

    /**
     * 取可用的 Aead；尚未初始化就**就地**补做一次。
     *
     * 为什么要自愈：初始化已从 `Application.onCreate` 挪到 IO 上预热，于是存在一个
     * 「进程刚起来、预热还没跑完」的窗口。调用方是 22 处**同步**的 encrypt/decrypt
     * （不能改成挂起，否则要动整个调用面），所以谁先进来谁就在自己的线程上补做一次：
     * 最坏情况与挪动前完全一致（那次初始化本来就在主线程上发生），最好情况是预热已经做完了。
     */
    private fun ensureInitialized(): Aead {
        aead?.let { return it }
        // 在锁内读 appContext：另一个线程可能正停在 init 中间，裸读会读到 null 而误报。
        val ctx = synchronized(lock) { appContext }
            ?: throw IllegalStateException("Keystore not initialized: call init(context) before encrypt/decrypt")
        init(ctx)
        return aead ?: throw IllegalStateException("Keystore initialization failed: aead still unavailable")
    }

    fun encrypt(data: String?, associatedData: String? = null): String {
        if (data.isNullOrEmpty()) return ""
        val client = ensureInitialized()

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
        val client = ensureInitialized()

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
