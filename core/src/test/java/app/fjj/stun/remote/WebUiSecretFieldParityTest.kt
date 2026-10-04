package app.fjj.stun.remote

import app.fjj.stun.repo.ProfileSecrets
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「加凭据字段要三处同改」的机械护栏。
 *
 * 新增一个凭据字段必须同时改三个地方，漏掉任何一处都是**静默失效**：
 * 1. `ProfileSecrets.SECRET_FIELDS` —— 出口掩码 / 写回哨兵（漏了 = 明文进 AI 上下文）
 * 2. `ProfileManager.SECRET_IO_FIELDS` —— SQLite 静止加密（漏了 = 库里躺明文）；
 *    1↔2 的 parity 由 `ProfileSecretFieldsTest` 兜住
 * 3. `app.js` 的 `SECRET_INPUT_IDS` —— WebUI 的「空框 + hint / 删除」显示。漏了的话该密码框
 *    会把服务端返回的 `*****` 直接当 value 显示（`privateKey` / `dnsTunnelPsk` /
 *    `proxyAuthToken` 这三个是 type=text，星号**肉眼可见**），并且没有清空入口，
 *    同时 `lvVal()` 也不再回落到哨兵 ⇒ 已存密码的节点会被校验误报「必填」。
 *
 * 第 3 条此前只靠 `app.js` 里一句注释提醒。本测试把「Kotlin 字段名 → DOM id」的映射显式写成
 * 一张表，任何一侧新增字段都会立刻变红，逼着三处一起补全。
 *
 * DOM id 的命名**不规则**（`icmpCustomPsk` → `edit-node-icmp-psk` 而非
 * `edit-node-icmp-custom-psk`；`kcpPassword` → `edit-node-kcp-pass`；`proxyAuthToken` →
 * `edit-node-auth-token`），推导不出来，只能手写这张表。
 */
class WebUiSecretFieldParityTest {

    /** Kotlin 字段名（= payload 键 = 库列名）→ WebUI 输入框 DOM id。 */
    private val fieldToDomId = mapOf(
        "pass" to "edit-node-pass",
        "privateKey" to "edit-node-private-key",
        "keyPass" to "edit-node-key-pass",
        "proxyAuthToken" to "edit-node-auth-token",
        "proxyAuthPass" to "edit-node-auth-pass",
        "icmpCustomPsk" to "edit-node-icmp-psk",
        "udpCustomPsk" to "edit-node-udp-custom-psk",
        "dnsTunnelPsk" to "edit-node-dns-psk",
        "kcpPassword" to "edit-node-kcp-pass",
    )

    private fun asset(name: String): String {
        val candidates = listOf(
            File("src/main/assets/web/$name"),
            File("core/src/main/assets/web/$name"),
            File("app/src/main/assets/web/$name"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("找不到 $name：尝试过 ${candidates.map { it.absolutePath }}")
    }

    /** 抽 `const SECRET_INPUT_IDS = [ ... ];` 里的字符串字面量。 */
    private fun secretInputIds(js: String): List<String> {
        val start = js.indexOf("const SECRET_INPUT_IDS = [")
        assertTrue("app.js 里找不到 SECRET_INPUT_IDS 数组（被改名或删除了？）", start >= 0)
        val end = js.indexOf("];", start)
        assertTrue("SECRET_INPUT_IDS 数组没有闭合", end > start)
        return Regex("'([^']+)'").findAll(js.substring(start, end))
            .map { it.groupValues[1] }
            .toList()
    }

    @Test
    fun `Kotlin 凭据字段集合与本测试映射表一致`() {
        assertEquals(
            "ProfileSecrets.SECRET_FIELDS 增删了字段，却没有同步本测试的映射表 —— " +
                "请给新字段指定 WebUI DOM id，并把它加进 app.js 的 SECRET_INPUT_IDS",
            ProfileSecrets.SECRET_FIELDS.toSet(),
            fieldToDomId.keys,
        )
    }

    @Test
    fun `app_js 的 SECRET_INPUT_IDS 与映射表一一对应`() {
        val ids = secretInputIds(asset("app.js"))
        assertEquals("SECRET_INPUT_IDS 里有重复项", ids.size, ids.toSet().size)
        assertEquals(
            "app.js 的 SECRET_INPUT_IDS 与本测试映射表不一致（掩码/删除按钮会漏掉某个凭据框）",
            fieldToDomId.values.toSet(),
            ids.toSet(),
        )
    }

    @Test
    fun `映射表里的 DOM id 都真实存在于 index_html`() {
        val declared = Regex("""id="([^"{}]+)"""").findAll(asset("index.html"))
            .map { it.groupValues[1] }
            .toSet()
        val dead = fieldToDomId.values - declared
        assertTrue(
            "映射表指向的 WebUI 输入框在 index.html 里不存在（掩码逻辑会静默跳过它）: $dead",
            dead.isEmpty(),
        )
    }

    // ── 「空 / 有值」文案护栏 ──
    //
    // ⚠️ 这一态**不需要协议层新增 `isXxxNullOrEmpty`**：服务端 `ProfileSecrets.maskInPlace`
    // 刻意保留空串，所以线上 `''` = 未设置、`*****` = 已设置，前端据 `dataset.secret` 的有无二分渲染。
    // 再补一个布尔字段等于把同一个 bit 抄成两份（`pass:"*****"` + `empty:true` 这种矛盾组合拦不住），
    // 而写入侧 `dropMaskedSecrets` 只认 `*****` ⇒ 两份迟早分叉。
    //
    // 曾经的真实缺陷在文案层，两头都让用户分不清「空」还是「有值」：
    // 1. `edit-node-pass` 的无值态借用了 `edit_pass_placeholder`（「留空则保持原密码不变」）——
    //    那是**有值语境**的句子，对从没设过密码的节点就是在撒谎；
    // 2. `auth-pass` / `icmp-psk` / `kcp-pass` 连 placeholder 都没有 ⇒ 未设置时框内一片空白。

    /** 抽 `const phMap = { ... };` 的 DOM id → i18n 键。 */
    private fun phMapEntries(js: String): Map<String, String> {
        val start = js.indexOf("const phMap = {")
        assertTrue("app.js 里找不到 phMap（被改名或删除了？）", start >= 0)
        val end = js.indexOf("};", start)
        assertTrue("phMap 没有闭合", end > start)
        return Regex("""'([^']+)':\s*'([^']+)'""")
            .findAll(js.substring(start, end))
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /**
     * i18n 区间（`const I18N = {` 到顶层闭合）。
     * 必须限定范围：`^\s{4}key:` 这个模式在文件别处也可能撞上同缩进的键。
     */
    private fun i18nRegion(js: String): String {
        val start = js.indexOf("const I18N = {")
        assertTrue("app.js 里找不到 const I18N", start >= 0)
        val end = js.indexOf("\n};", start)
        assertTrue("I18N 没有闭合", end > start)
        return js.substring(start, end)
    }

    /** 某 i18n 键在区间里出现的次数（= 覆盖到的语言块数）。 */
    private fun i18nKeyCount(i18n: String, key: String): Int =
        Regex("""^\s{4}${Regex.escape(key)}:\s*['"]""", RegexOption.MULTILINE).findAll(i18n).count()

    /** 以 `secret_saved_hint` 为基准自校准语言块数，免得把"6 个语言"硬编码在这里。 */
    private fun localeCount(i18n: String): Int {
        val n = i18nKeyCount(i18n, "secret_saved_hint")
        assertTrue("基准键 secret_saved_hint 只出现 $n 次，i18n 结构变了？", n >= 2)
        return n
    }

    @Test
    fun `phMap 引用的 i18n 键覆盖全部语言块`() {
        val js = asset("app.js")
        val i18n = i18nRegion(js)
        val locales = localeCount(i18n)
        val bad = phMapEntries(js).values.distinct()
            .filter { i18nKeyCount(i18n, it) != locales }
            .map { "$it(${i18nKeyCount(i18n, it)}/$locales)" }
        assertTrue(
            "这些 phMap 引用的 i18n 键没有覆盖全部 $locales 个语言块" +
                "（键名拼错 / 键被删 / 只加了默认语言）: $bad",
            bad.isEmpty(),
        )
    }

    @Test
    fun `每个凭据框都有无值态文案来源`() {
        val html = asset("index.html")
        val inPhMap = phMapEntries(asset("app.js")).keys
        // 只认同一个标签内的 placeholder（`[^>]*` 不会跨标签）。
        val htmlPh = Regex("id=\"([^\"]+)\"[^>]*placeholder=\"([^\"]*)\"")
            .findAll(html).associate { it.groupValues[1] to it.groupValues[2] }
        val blank = fieldToDomId.values.filter { id ->
            id !in inPhMap && htmlPh[id].isNullOrBlank()
        }
        assertTrue(
            "这些凭据框没有任何无值态文案 —— 未设置时框内一片空白，用户分不清是空还是有值: $blank",
            blank.isEmpty(),
        )
    }

    @Test
    fun `无值态不得借用有值态文案`() {
        val js = asset("app.js")
        val borrowed = phMapEntries(js)
            .filterKeys { it in fieldToDomId.values }
            .filterValues { it == "secret_saved_hint" }
        assertTrue(
            "凭据框的无值态 placeholder 指向了有值态的 hint（对未设置的字段就是在撒谎）: $borrowed",
            borrowed.isEmpty(),
        )
        assertTrue(
            "refreshSecretDisplay 的无值分支必须回落到 secret_empty_hint，不能留空串",
            js.contains("|| t('secret_empty_hint')"),
        )
        assertTrue(
            "`edit_pass_placeholder`（「留空则保持原密码不变」）是**有值语境**的句子，" +
                "不得再作为无值态文案出现在 app.js 里",
            !js.contains("edit_pass_placeholder"),
        )
    }
}
