package app.fjj.stun.backup

/**
 * 设置快照的「带类型标签」编解码器。
 *
 * 设计目标：让「新增一个设置字段」**不再需要改备份代码**。
 * 做法是让分类信息从数据本身推导，而不是靠人工维护的清单：
 *
 * 1. **类型**由运行时值推导（`String/Boolean/Int/Long/Float/Set<String>` → 类型标签），
 *    避免 Gson 把数字统一反序列化成 Double。
 * 2. **是否密文**由值的形态推导 —— KeystoreUtils 的加密产物统一带 `ENC:` 前缀，
 *    因此「这个字段需要按密钥处理」可以**自动识别**，不需要维护一张密钥字段表。
 *    这同时也修掉了「新增密钥字段忘了特判 → 被当普通串备份」这一类 bug。
 *
 * 本文件**刻意零 Android 依赖**（纯 Kotlin），因此可以直接用 JVM 单测覆盖。
 */
object SettingsBackupCodec {

    const val STRING = "s"
    const val BOOL = "b"
    const val INT = "i"
    const val LONG = "l"
    const val FLOAT = "f"
    const val STRING_SET = "ss"

    private const val T = "t"
    private const val V = "v"
    private const val ENC = "enc"

    /** KeystoreUtils 加密产物的固定前缀。 */
    private const val KEYSTORE_PREFIX = "ENC:"

    /** 是否为 Keystore 密文。纯前缀判定，不需要 Context，因此可跨设备/离线判断。 */
    fun isKeystoreCipher(value: String?): Boolean =
        value != null && value.startsWith(KEYSTORE_PREFIX)

    /** 条目声明的类型标签（未知/损坏返回 null）。 */
    fun typeOf(entry: Map<String, Any?>): String? = entry[T] as? String

    /** 条目是否标记为「明文来自本机密钥密文，恢复时需重新加密存储」。 */
    fun isEncrypted(entry: Map<String, Any?>): Boolean = entry[ENC] == true

    /**
     * 导出：原始 SharedPreferences 值 → 可 JSON 化的条目。
     *
     * - Keystore 密文 → 解密成明文 + 打 `enc` 标记（跨设备可用，仍受备份 PIN 保护）；
     *   解不开（Keystore 被重置等）时**原样带出且不打标记**，至少不丢字段。
     * - 无法识别的类型返回 null，由调用方告警，避免静默漏备份。
     *
     * @param decryptSecret 本机解密函数（注入以便单测）。
     */
    fun encode(raw: Any?, decryptSecret: (String) -> String): Map<String, Any?>? = when (raw) {
        is String -> {
            if (!isKeystoreCipher(raw)) {
                mapOf(T to STRING, V to raw)
            } else {
                val plain = runCatching { decryptSecret(raw) }.getOrDefault("")
                if (plain.isEmpty()) {
                    mapOf(T to STRING, V to raw)
                } else {
                    mapOf(T to STRING, V to plain, ENC to true)
                }
            }
        }
        is Boolean -> mapOf(T to BOOL, V to raw)
        is Int -> mapOf(T to INT, V to raw)
        is Long -> mapOf(T to LONG, V to raw)
        is Float -> mapOf(T to FLOAT, V to raw)
        is Set<*> -> mapOf(T to STRING_SET, V to raw.filterIsInstance<String>())
        else -> null
    }

    /** 解码结果：类型标签 + 已按目标形态转换好的值。 */
    data class Decoded(val type: String, val value: Any?)

    /**
     * 恢复：云端条目 → 本机应写入的值。
     *
     * @param encryptSecret 本机加密函数（注入以便单测）。
     * @param forceEncrypt 条目未打 `enc` 标记、但本机该键**原本就是密文**时为 true。
     *   用于兼容历史备份：旧格式里 `webdav_pass` 是以明文形态导出的，
     *   没有标记；若本机发现该键本来就是密文，就继续按密文存储，避免回落成明文落盘。
     * @return null 表示条目损坏/类型未知/加密失败，调用方应跳过并告警。
     */
    fun decode(
        entry: Map<String, Any?>,
        encryptSecret: (String) -> String,
        forceEncrypt: Boolean = false,
    ): Decoded? {
        val type = entry[T] as? String ?: return null
        val raw = entry[V]
        val encrypted = isEncrypted(entry) || (forceEncrypt && type == STRING)

        return when (type) {
            STRING -> {
                val text = raw as? String ?: return null
                if (!encrypted) {
                    Decoded(STRING, text)
                } else {
                    // 加密失败（Keystore 不可用）宁可跳过，也不把密钥明文落盘
                    val cipher = runCatching { encryptSecret(text) }.getOrDefault("")
                    if (cipher.isEmpty()) null else Decoded(STRING, cipher)
                }
            }
            BOOL -> (raw as? Boolean)?.let { Decoded(BOOL, it) }
            INT -> (raw as? Number)?.let { Decoded(INT, it.toInt()) }
            LONG -> (raw as? Number)?.let { Decoded(LONG, it.toLong()) }
            FLOAT -> (raw as? Number)?.let { Decoded(FLOAT, it.toFloat()) }
            STRING_SET -> {
                val list = raw as? List<*> ?: return null
                Decoded(STRING_SET, list.filterIsInstance<String>().toSet())
            }
            else -> null
        }
    }
}
