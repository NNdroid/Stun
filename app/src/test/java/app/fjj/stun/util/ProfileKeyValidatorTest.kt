package app.fjj.stun.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ProfileKeyValidator] —— SSH 私钥三态判定。
 *
 * 这段逻辑原先在三个文件里各写一遍，且**已经漂移**：快捷动作页把「私钥损坏」（`2`）
 * 混进 `else` 静默失败，主界面与编辑页则让 JNI 异常**直接放行**。
 * 收口之后，最要紧的是保证下面几条不变量不会再破。
 *
 * `Myssh` 是 gomobile 产物（类初始化即 loadLibrary）、`KeystoreUtils` 要真 Keystore，
 * 所以全部经 `ProfileKeyValidator` 的三个 internal 替身口注入（默认实现才碰真身）。
 */
class ProfileKeyValidatorTest {

    private val KEY = "-----BEGIN OPENSSH PRIVATE KEY-----\nfake\n-----END OPENSSH PRIVATE KEY-----"
    private val CIPHER = "ENC:cipher"

    private fun stub(
        check: (String) -> Long = { 0L },
        validate: (String, String) -> Boolean = { _, _ -> false },
        decrypt: (String) -> String = { "" },
    ) {
        ProfileKeyValidator.checkIfKeyEncryptedProvider = check
        ProfileKeyValidator.validatePassphraseProvider = validate
        ProfileKeyValidator.decryptProvider = decrypt
    }

    @After
    fun clearStubs() {
        // 必须清空：这些是 object 上的全局 var，泄漏到别的测试会引发难查的偶发失败。
        // ⚠️ 这里**赋 null 而不是真身 lambda** —— 赋 `{ Myssh… }` 等于在测试进程里
        // 触发 JNI 类加载。置 null 即回到"用真身"的惰性状态，生产语义不变。
        ProfileKeyValidator.clearProvidersForTest()
    }

    // ── 三态基本映射 ────────────────────────────────────────────

    @Test
    fun plainKeyIsUsable() {
        stub(check = { 0L })
        assertEquals(ProfileKeyValidator.Result.PlainKeyUsable, ProfileKeyValidator.validate(KEY, CIPHER))
    }

    @Test
    fun encryptedKeyWithCorrectPassphraseIsUsable() {
        stub(check = { 1L }, validate = { _, _ -> true }, decrypt = { "plain-pass" })
        assertEquals(ProfileKeyValidator.Result.PassphraseVerified, ProfileKeyValidator.validate(KEY, CIPHER))
    }

    @Test
    fun encryptedKeyWithWrongPassphrasePointsAtPassphraseField() {
        stub(check = { 1L }, validate = { _, _ -> false }, decrypt = { "plain-pass" })
        assertEquals(ProfileKeyValidator.Result.PassphraseInvalid, ProfileKeyValidator.validate(KEY, CIPHER))
    }

    @Test
    fun undecryptablePassphrasePointsAtPassphraseField() {
        // 解密得到空串 ⇒ 口令没输/Keystore 里没有。与 validatePassphrase 返回值无关。
        stub(check = { 1L }, validate = { _, _ -> true }, decrypt = { "" })
        assertEquals(ProfileKeyValidator.Result.PassphraseInvalid, ProfileKeyValidator.validate(KEY, CIPHER))
    }

    @Test
    fun brokenKeyPointsAtPrivateKeyField() {
        stub(check = { 2L })
        assertEquals(ProfileKeyValidator.Result.PrivateKeyUnusable, ProfileKeyValidator.validate(KEY, CIPHER))
    }

    // ── 下面三条是本次修掉的真 bug 的核心不变量 ──────────────────

    @Test
    fun unknownReturnValueIsTreatedAsUnusableNotPassed() {
        // 反事实：这里若返回"通过"，JNI 行为异常时坏私钥会被放行，
        // 连接随后在服务端侧失败、报错指向"认证失败"而不是"你的私钥坏了"。
        stub(check = { -1L })
        assertEquals(ProfileKeyValidator.Result.PrivateKeyUnusable, ProfileKeyValidator.validate(KEY, CIPHER))
    }

    @Test
    fun jniExceptionIsTreatedAsUnusableNotPassed() {
        // 这是 HomeFragment 与 ProfileEditActivity 改前都有的洞：runCatching/直接调用
        // 抛出后没有 else 分支，校验被**跳过**。
        stub(check = { throw UnsatisfiedLinkError("libmyssh not loaded") })
        assertEquals(ProfileKeyValidator.Result.PrivateKeyUnusable, ProfileKeyValidator.validate(KEY, CIPHER))
    }

    @Test
    fun emptyPrivateKeyIsUnusable() {
        stub(check = { 0L })
        assertEquals(ProfileKeyValidator.Result.PrivateKeyUnusable, ProfileKeyValidator.validate("", CIPHER))
    }

    @Test
    fun passphraseValidatorExceptionIsTreatedAsInvalidNotVerified() {
        // 同上，但发生在 validatePassphrase 这一步。
        stub(
            check = { 1L },
            validate = { _, _ -> throw RuntimeException("boom") },
            decrypt = { "plain-pass" },
        )
        assertEquals(ProfileKeyValidator.Result.PassphraseInvalid, ProfileKeyValidator.validate(KEY, CIPHER))
    }

    // ── isUsable：快捷动作页唯一关心的那个语义 ────────────────────

    @Test
    fun isUsableOnlyForTheTwoPassingResults() {
        assertTrue(ProfileKeyValidator.Result.PlainKeyUsable.isUsable)
        assertTrue(ProfileKeyValidator.Result.PassphraseVerified.isUsable)
        assertFalse(ProfileKeyValidator.Result.PassphraseInvalid.isUsable)
        assertFalse(ProfileKeyValidator.Result.PrivateKeyUnusable.isUsable)
    }

    /**
     * 「私钥损坏」在快捷动作页表现为**静默失败**（点了没反应），是本次修的用户可见问题。
     * 这条锁住它必须 `isUsable == false` —— 即便被将来某次"优化"改成 true 也会立刻变红。
     */
    @Test
    fun brokenKeyNeverCountsAsQuietlyConnectable() {
        stub(check = { 2L })
        assertFalse(
            "私钥损坏时快捷动作页必须回退到完整流程，否则用户看到的是「点了没反应」",
            ProfileKeyValidator.validate(KEY, CIPHER).isUsable,
        )
    }

    @Test
    fun correctPassphraseCountsAsQuietlyConnectable() {
        stub(check = { 1L }, validate = { _, _ -> true }, decrypt = { "plain-pass" })
        assertTrue(ProfileKeyValidator.validate(KEY, CIPHER).isUsable)
    }
}
