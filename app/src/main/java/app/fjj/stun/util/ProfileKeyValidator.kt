package app.fjj.stun.util

import app.fjj.stun.repo.Profile
import myssh.Myssh

/**
 * SSH 私钥可用性校验的**唯一实现**。
 *
 * ## 为什么必须是同一个类
 * `myssh.Myssh.checkIfKeyEncrypted` 返回一个 `long` 三态，而这个三分支原先在
 * **三个**文件里各写一遍，且**已经漂移出行为差异**：
 *
 * | 返回值 | 含义 | HomeFragment（改前） | VpnQuickAction（改前） | ProfileEdit（改前） |
 * |---|---|---|---|---|
 * | `0` | 明文私钥，可用 | 通过 | 通过 | 通过 |
 * | `1` | 有口令，需校验口令 | 弹「口令无效」 | 静默 false | 标错在口令框 |
 * | `2` | **私钥本身损坏** | Toast「私钥无效」 | **落到 else → 静默 false** | 标错在私钥框 |
 * | 其它 / 抛异常 | 未知 | **直接放行！** | 静默 false | **直接放行！** |
 *
 * 后两格是实打实的问题，不是风格差异：
 *  - 快捷动作页把 `2` 混进 `else`，用户看到的是"点了没反应"——它静默退回 MainActivity，
 *    而 MainActivity 那边的完整流程同样会失败，用户只会以为 App 卡了。
 *  - 两个"直接放行"的格子更隐蔽：JNI 抛异常时校验被跳过，坏私钥被当成好的，
 *    连接随后在服务端侧失败，报错指向完全无关的地方（"认证失败"而不是"你的私钥坏了"）。
 *
 * ## 设计取舍
 * 三态里的 `PassphraseInvalid` 要**解密并校验口令**，而解密依赖 `KeystoreUtils`（Android 侧）、
 * `Myssh` 是 JNI —— 两者都无状态、都能在纯 JVM 测试里替身，所以这里放 `object` 而不做成
 * 泛型策略类。`Result` 用 sealed interface 而不是 `Int` 常量：三分支里有两个都表示"用不了"，
 * 但**提示指向的字段不同**（口令框 vs 私钥框），用常量极易在调用点写错成同一个提示。
 */
object ProfileKeyValidator {

    /**
     * JNI 与 Keystore 的**替身口**（`internal`，仅供纯 JVM 测试替换）。
     *
     * ⚠️⚠️ **默认实现必须放在惰性 getter 里，不能写成 `@Volatile var x = { Myssh… }`**：
     * 字段初始化器里的 lambda 会在 `object` 初始化时求值、解析 `Myssh` 类引用 ⇒
     * 纯 JVM 下类加载即 `NoClassDefFoundError`。踩过：`stub(check = { 0L })` 明明设了替身，
     * 12 条测试仍有 6 条返回 `PrivateKeyUnusable`（替身链路里 JNI 异常被兜底吃掉了）。
     *
     * 也不能把默认值直接写成常量（`{ 0L }`）—— 那会让生产环境永远判定"私钥明文可用"，
     * 校验形同虚设。所以 backing field 保持 `null` 表示"用真身"：测试覆盖后永不触碰 JNI，
     * 生产环境仍走真身调用。
     */
    @Volatile
    private var checkIfKeyEncryptedOverride: ((String) -> Long)? = null

    @Volatile
    private var validatePassphraseOverride: ((String, String) -> Boolean)? = null

    @Volatile
    private var decryptOverride: ((String) -> String)? = null

    internal var checkIfKeyEncryptedProvider: (String) -> Long
        get() = checkIfKeyEncryptedOverride ?: { Myssh.checkIfKeyEncrypted(it) }
        set(value) { checkIfKeyEncryptedOverride = value }

    internal var validatePassphraseProvider: (String, String) -> Boolean
        get() = validatePassphraseOverride ?: { k, p -> Myssh.validatePassphrase(k, p) }
        set(value) { validatePassphraseOverride = value }

    internal var decryptProvider: (String) -> String
        get() = decryptOverride ?: { KeystoreUtils.decrypt(it) }
        set(value) { decryptOverride = value }

    /** 供测试清空替身，回到"用真身"的惰性状态。 */
    internal fun clearProvidersForTest() {
        checkIfKeyEncryptedOverride = null
        validatePassphraseOverride = null
        decryptOverride = null
    }

    /** 校验结论。调用方按需取用；快捷动作页只看 [Result.isUsable]。 */
    sealed interface Result {
        /** 明文私钥，直接可用（`checkIfKeyEncrypted == 0`）。 */
        object PlainKeyUsable : Result

        /** 私钥有口令，且口令解密并校验通过（`== 1` 分支校验通过）。 */
        object PassphraseVerified : Result

        /**
         * 需要用户输入/更正**口令**：`1` 分支但解密或 `validatePassphrase` 失败。
         * 提示应指向口令字段。
         */
        object PassphraseInvalid : Result

        /**
         * **私钥本身不可用**：`2`，或 JNI 调用抛异常 / 返回未知值。
         * 提示应指向私钥字段。
         *
         * ⚠️ 异常与 `2` 必须归为同一类：两者对用户都是"这把钥匙用不了"，
         * 区别只在诊断信息，不该让调用方各自决定要不要放行。
         */
        object PrivateKeyUnusable : Result

        /** 能否在不弹界面的前提下继续。凡是需要用户输入的一律 false。 */
        val isUsable: Boolean
            get() = this is PlainKeyUsable || this is PassphraseVerified
    }

    /**
     * 校验 [profile] 的私钥。
     *
     * 必须在 IO 上调用（`Myssh` 是 JNI，且 [KeystoreUtils.decrypt] 要读 Keystore）。
     */
    fun validate(profile: Profile): Result = validate(profile.privateKey, profile.keyPass)

    /**
     * 直接按私钥文本 + 口令密文校验，供**编辑页**使用。
     *
     * 编辑页校验的是"用户此刻在输入框里打的字"，而不是已保存的 profile ——
     * 所以必须走这个重载，否则改一个字段后校验的还是旧值。
     */
    fun validate(privateKey: String, keyPass: String): Result {
        if (privateKey.isEmpty()) return Result.PrivateKeyUnusable

        // ⚠️ 三处调用**一律走 provider**，不要在这里直接写 Myssh/KeystoreUtils：
        // 那样替身口形同虚设，纯 JVM 测试会在类加载时就炸（见上面替身口的说明）。
        //
        // ⚠️ 异常**不能**当放行：runCatching 包住 JNI 调用，但失败分支必须落到
        // PrivateKeyUnusable（见 Result 的说明），不能让异常顺着 ?: 逃出去变成"通过"。
        val encrypted = runCatching { checkIfKeyEncryptedProvider(privateKey) }
            .getOrElse { return Result.PrivateKeyUnusable }

        return when (encrypted) {
            0L -> Result.PlainKeyUsable
            1L -> {
                val decrypted = runCatching { decryptProvider(keyPass) }.getOrDefault("")
                if (decrypted.isNotEmpty() &&
                    runCatching { validatePassphraseProvider(privateKey, decrypted) }
                        .getOrDefault(false)
                ) {
                    Result.PassphraseVerified
                } else {
                    Result.PassphraseInvalid
                }
            }
            // 2L = 私钥损坏；其余未知值同样按"不可用"处理，
            // 不能像旧代码那样 `else -> Unit` 直接放行。
            else -> Result.PrivateKeyUnusable
        }
    }
}
