package app.fjj.stun.remote

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 控制台页面的**结构**护栏。
 *
 * 这不是快照测试（快照一改就红，会逼着人机械更新而不是理解问题）。这里钉的是
 * 「拆分不能悄悄弄坏的东西」：模板引用的每个文案键都得存在、HTML 骨架必须闭合、
 * 配置片段必须经过调用方转义与掩码。
 *
 * ## 为什么要钉
 * `renderDashboardHtml` 原来是 [StunMcpServer] 里一个 550 行的方法，其中 513 行是
 * 内联 HTML raw string。拆成 [McpDashboardHtml]（模板）+ `McpI18n`（文案表）之后，
 * 引入了一类新风险：**模板引用的文案键在 `STRINGS` 里不存在**，页面就会把
 * `security_title` 这种原始 key 直接显示给用户。这类问题编译期无感知、
 * 也不影响任何单测，只有真打开页面才看得见。
 */
class McpDashboardHtmlTest {

    private val repoRoot: File by lazy {
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle").isFile || File(it, "settings.gradle.kts").isFile }
            ?: throw AssertionError("从 ${File(".").absolutePath} 往上找不到 settings.gradle")
    }

    private fun read(rel: String): String = File(repoRoot, rel).readText()

    private val template: String
        get() = read("core/src/main/java/app/fjj/stun/remote/McpDashboardHtml.kt")

    /** 模板 raw string 的正文（`= """` 与收尾 `"""` 之间）。 */
    private val templateBody: String by lazy {
        val s = template
        val start = s.indexOf("\"\"\"", s.indexOf("String ="))
        require(start > 0) { "找不到模板 raw string 起点" }
        // 收尾：文件末尾那个独占一行的 """（模板内不含嵌套 raw string）
        val end = s.lastIndexOf("\"\"\"")
        require(end > start) { "找不到模板 raw string 收尾" }
        s.substring(start + 3, end)
    }

    /** `McpI18n.STRINGS` 里的 key 集合。 */
    private fun stringKeys(): Set<String> {
        val src = read("core/src/main/java/app/fjj/stun/remote/McpI18n.kt")
        val body = src.substring(src.indexOf("private val STRINGS"))
        return Regex("""\"([a-z0-9_]+)\"\s+to\s+\"""").findAll(body)
            .map { it.groupValues[1] }.toSet()
    }

    // ─────────────────── 模板 ↔ 文案表 ───────────────────

    /**
     * 模板引用的每个 `t("key")` 都必须在 `STRINGS` 里存在。
     *
     * 缺失的表现：页面上直接显示 `security_title` 这样的原始 key。
     */
    @Test
    fun everyTemplateKeyExistsInStrings() {
        val used = Regex("""\bt\("([a-z0-9_]+)"\)""").findAll(templateBody)
            .map { it.groupValues[1] }.toSet()
        assertTrue("模板里应至少有几十处 t() 引用，实际 ${used.size}", used.size >= 40)

        val missing = used - stringKeys()
        assertTrue("模板引用了 STRINGS 里没有的文案键（页面会显示原始 key）：$missing", missing.isEmpty())
    }

    /**
     * 反事实：确认 [stringKeys] 不是"空集合恒真"。
     * 若正则因格式变化而失配，上面的缺失断言会永远绿。
     */
    @Test
    fun stringKeysExtractorActuallyFindsKeys() {
        val keys = stringKeys()
        for (must in listOf("title", "security_title", "auth_none", "protocol_mode")) {
            assertTrue("STRINGS 应含基础键 $must，实际只有 ${keys.size} 条", must in keys)
        }
        // 5 种语言（en/zh/ja/de/fr）
        assertTrue("STRINGS 应至少 40 条，实际 ${keys.size}", keys.size >= 40)
    }

    /** 5 种语言都要有 `title` —— 少一种语言就是整页回退成 key。 */
    @Test
    fun allFiveLanguagesPresent() {
        val src = read("core/src/main/java/app/fjj/stun/remote/McpI18n.kt")
        val body = src.substring(src.indexOf("private val STRINGS"))
        for (lang in listOf("en", "zh", "ja", "de", "fr")) {
            assertTrue("STRINGS 缺语言 $lang", Regex("\"$lang\"\\s+to\\s+mapOf\\(").containsMatchIn(body))
        }
    }

    // ─────────────────── HTML 骨架 ───────────────────

    @Test
    fun htmlDocumentSkeletonIsIntact() {
        assertTrue("缺 <!DOCTYPE html>", templateBody.contains("<!DOCTYPE html>"))
        // 模板里形如 <html lang="$lang"> —— 必须按字面量匹配，不能让 Kotlin 插值
        assertTrue("缺 <html lang=...>", templateBody.contains("<html lang=\"" + "$" + "lang\">"))
        assertTrue("缺 </html>", templateBody.contains("</html>"))
        assertTrue("缺 <head>", templateBody.contains("<head>"))
        assertTrue("缺 </head>", templateBody.contains("</head>"))
        assertTrue("缺 <body>", templateBody.contains("<body>"))
        assertTrue("缺 </body>", templateBody.contains("</body>"))
        assertTrue("缺 <style>", templateBody.contains("<style>"))
        assertTrue("缺 </style>", templateBody.contains("</style>"))
    }

    /**
     * 标签配平（只查块级标签；`<br>` / `<img>` 这类自闭合的不算）。
     * 少一个闭合标签会让页面从那一行起整个塌掉。
     */
    @Test
    fun blockTagsAreBalanced() {
        for (tag in listOf("html", "head", "body", "style", "script", "div")) {
            val open = Regex("<$tag(\\s[^>]*)?>").findAll(templateBody).count()
            val close = Regex("</$tag>").findAll(templateBody).count()
            assertEquals("<$tag> 开闭不配平：$open 开 / $close 闭", open, close)
        }
    }

    /** 语言切换按钮：五种语言各一个 `?lang=xx` 链接，且当前语言有 active 标记。 */
    @Test
    fun languageSwitcherCoversAllFiveAndMarksCurrent() {
        for (lang in listOf("en", "zh", "ja", "de", "fr")) {
            assertTrue("语言切换缺 ?lang=$lang",
                templateBody.contains("""href="?lang=$lang""""))
            // 模板里这一行形如 ${if (lang == "en") "active" else ""} —— 必须按字面量匹配
            val marker = "\${if (lang == \"" + lang + "\") \"active\" else \"\"}"
            assertTrue("当前语言 $lang 缺 active 高亮（找 $marker）",
                templateBody.contains(marker))
        }
    }

    // ─────────────────── 拆分后的职责边界 ───────────────────

    /**
     * 模板里不得出现服务端类型 —— 一旦出现就说明"取数"漏搬进来了，
     * 模板会重新依赖 `Context` / 单例，也就没法单独渲染与测试。
     */
    @Test
    fun templateHasNoServerSideDependency() {
        val body = templateBody
        for (forbidden in listOf("SettingsManager", "ProfileManager", "context", "Profile(")) {
            assertFalse("模板正文不应引用 $forbidden", body.contains(forbidden))
        }
    }

    /** 反事实：上面那条不是"因为搜索不到才绿"，确认这些词确实出现在 server 侧。 */
    @Test
    fun serverSideStillOwnsContextAndEscaping() {
        val srv = read("core/src/main/java/app/fjj/stun/remote/StunMcpServer.kt")
        assertTrue("server 侧应仍持有 getClaudeConfigJson 调用",
            srv.contains("getClaudeConfigJson(context"))
        assertTrue("控制台页面必须走掩码版配置",
            srv.contains("maskSecrets = true"))
        assertTrue("server 侧应仍负责 htmlEscape", srv.contains("fun htmlEscape"))
        // isOauth 由 server 判断后传入，模板不该认识 MCP_AUTH_MODE_OAUTH
        assertTrue("server 侧应计算 isOauth",
            srv.contains("isOauth = authMode == SettingsManager.MCP_AUTH_MODE_OAUTH"))
    }
}