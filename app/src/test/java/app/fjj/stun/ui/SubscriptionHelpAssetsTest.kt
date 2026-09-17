package app.fjj.stun.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * 订阅说明页的资源契约（纯 JUnit，不跑 Robolectric）。
 *
 * 背景：说明正文是 6 份随包的 HTML（`assets/help/<lang>/subscription_help.html`），
 * 不走 res 限定符 —— 也就是说 aapt **不会**替我们比对它们之间的差异。
 * 这一层空白用测试补上，锁住三件最容易漏、且只在真机看图时才会发现的事：
 *
 * 1. 占位符集合必须与 [SubscriptionHelpActivity.PLACEHOLDER_NAMES] **全等**。
 *    多写一个（HTML 里拼错）会留下字面量，少写一个会让某个颜色写不进去。
 * 2. 各语言小节结构一致（h2/h3 数量相同）—— 翻译时最容易"这门语言多一段、那门少一段"。
 * 3. 页面自包含：无 `<script>` / `<link>` / 外部 `src`。WebView 里 JS 是关掉的，
 *    真引了脚本就是一片不会动的死页面。
 */
class SubscriptionHelpAssetsTest {

    private val helpRoot: File = findHelpRoot()

    private fun findHelpRoot(): File {
        listOf(File("src/main/assets/help"), File("app/src/main/assets/help"))
            .firstOrNull { it.isDirectory }
            ?.let { return it }
        throw AssertionError("找不到 assets/help（工作目录 ${File(".").absolutePath}）")
    }

    private fun page(lang: String) = File(helpRoot, "$lang/subscription_help.html")

    private fun text(lang: String): String {
        val f = page(lang)
        assertTrue("缺少 $lang 的说明页：${f.path}", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    @Test
    fun everyAppLanguageHasItsOwnPage() {
        for (lang in LANGUAGES) {
            val f = page(lang)
            assertTrue("缺少 $lang 的说明页：${f.path}", f.isFile)
            assertTrue("$lang 的说明页是空文件", f.length() > 0)
        }
    }

    @Test
    fun assetDirsAreExactlyTheLanguageList() {
        val dirs = helpRoot.listFiles { f -> f.isDirectory }?.map { it.name }?.toSortedSet()
            ?: emptySet<String>()
        assertEquals(
            "assets/help 下的语言目录必须正好是 app 支持的那几门（多出来的目录映射表永远打不到）",
            LANGUAGES.toSortedSet(), dirs
        )
    }

    @Test
    fun placeholderSetMatchesTheActivitysSubstitutionTable() {
        val expected = SubscriptionHelpActivity.PLACEHOLDER_NAMES.toSet()
        for (lang in LANGUAGES) {
            val found = PLACEHOLDER.findAll(text(lang)).map { it.groupValues[1] }.toSet()
            assertEquals(
                "$lang 的占位符集合与 SubscriptionHelpActivity.PLACEHOLDER_NAMES 不一致",
                expected, found
            )
        }
    }

    /** 复刻 Activity 的替换算法，确认替换完页面上不留 `{{`（拼错的名字会在这里现形）。 */
    @Test
    fun substitutionLeavesNoPlaceholderBehind() {
        val names = SubscriptionHelpActivity.PLACEHOLDER_NAMES
        for (lang in LANGUAGES) {
            var out = text(lang)
            for (name in names) out = out.replace("{{$name}}", "#000000")
            assertFalse(
                "$lang 替换后仍残留占位符：${PLACEHOLDER.find(out)?.value}",
                out.contains("{{")
            )
        }
    }

    @Test
    fun sectionStructureIsIdenticalAcrossLanguages() {
        val h2 = LANGUAGES.associateWith { H2.findAll(text(it)).count() }
        val h3 = LANGUAGES.associateWith { H3.findAll(text(it)).count() }

        assertTrue("说明页至少要有一个 <h2> 章节", h2.values.all { it > 0 })
        assertEquals(
            "各语言的 <h2> 数量必须一致（漏译/多译都会落在这里）：$h2",
            1, h2.values.toSet().size
        )
        assertEquals(
            "各语言的 <h3> 数量必须一致：$h3",
            1, h3.values.toSet().size
        )
    }

    @Test
    fun pagesAreSelfContainedAndDeclareUtf8() {
        for (lang in LANGUAGES) {
            val html = text(lang)
            assertFalse(
                "$lang 引了 <script>：WebView 里 JS 是关掉的，脚本不会执行",
                html.contains("<script")
            )
            assertFalse("$lang 引了 <link>：说明页必须内联样式、不依赖外部资源", html.contains("<link"))
            assertFalse("$lang 含外部 src= 引用", html.contains(" src="))
            assertTrue(
                "$lang 必须声明 UTF-8，否则中文/日文会乱码",
                html.contains("charset=\"utf-8\"", ignoreCase = true)
            )
            assertTrue("$lang 的 <html lang> 应标成 $lang", html.contains("lang=\"$lang\""))
        }
    }

    @Test
    fun nonEnglishPagesAreReallyTranslated() {
        val en = text("en")
        for (lang in LANGUAGES - "en") {
            val html = text(lang)
            assertNotEquals("$lang 的说明页与英文版一字不差 —— 翻译漏了", en, html)
            assertTrue(
                "$lang 的说明页没有任何非 ASCII 字符，看着像没翻译",
                html.any { it.code > 127 }
            )
        }
        // 中日文再确认真的带了汉字字形，别是拿拉丁字母糊出来的占位翻译。
        for (lang in listOf("ja", "zh-CN", "zh-TW")) {
            assertTrue("$lang 应该含中日韩字形", text(lang).any { it.code in CJK_START..CJK_END })
        }
    }

    @Test
    fun localeMapsToTheRightAssetDir() {
        assertEquals("en", SubscriptionHelpActivity.helpAssetDir(Locale.ENGLISH))
        assertEquals("de", SubscriptionHelpActivity.helpAssetDir(Locale.GERMAN))
        assertEquals("fr", SubscriptionHelpActivity.helpAssetDir(Locale.FRENCH))
        assertEquals("ja", SubscriptionHelpActivity.helpAssetDir(Locale.JAPANESE))

        assertEquals("zh-CN", SubscriptionHelpActivity.helpAssetDir(Locale.forLanguageTag("zh-CN")))
        assertEquals("zh-CN", SubscriptionHelpActivity.helpAssetDir(Locale.forLanguageTag("zh-Hans")))
        assertEquals("zh-TW", SubscriptionHelpActivity.helpAssetDir(Locale.forLanguageTag("zh-TW")))
        assertEquals("zh-TW", SubscriptionHelpActivity.helpAssetDir(Locale.forLanguageTag("zh-Hant")))
        // 港澳没有单独的 values- 目录，读繁体最自然（app 的 zh-rTW 就是繁体资源）。
        assertEquals("zh-TW", SubscriptionHelpActivity.helpAssetDir(Locale.forLanguageTag("zh-HK")))
        assertEquals("zh-TW", SubscriptionHelpActivity.helpAssetDir(Locale.forLanguageTag("zh-MO")))
        // 只给了语言没给地区：按简体。
        assertEquals("zh-CN", SubscriptionHelpActivity.helpAssetDir(Locale.forLanguageTag("zh")))

        // 没有对应翻译的语言一律回落英文，而不是白屏。
        assertEquals("en", SubscriptionHelpActivity.helpAssetDir(Locale.forLanguageTag("es")))
        assertEquals("en", SubscriptionHelpActivity.helpAssetDir(Locale.forLanguageTag("ru")))
        assertEquals("en", SubscriptionHelpActivity.helpAssetDir(Locale.forLanguageTag("ko")))
        assertEquals("en", SubscriptionHelpActivity.helpAssetDir(Locale.ROOT))

        // 映射出来的目录必须真的有一份说明页，否则等于白屏。
        for (tag in listOf("en", "de", "fr", "ja", "zh-CN", "zh-TW", "zh-HK", "es", "ko", "ru")) {
            val dir = SubscriptionHelpActivity.helpAssetDir(Locale.forLanguageTag(tag))
            assertTrue("$tag 被映射到 $dir，但那个目录下没有说明页", page(dir).isFile)
        }
    }

    @Test
    fun cssColorEmitsHexWhenOpaqueAndRgbaWhenTranslucent() {
        assertEquals("#1C1B1F", cssColor(0xFF1C1B1F.toInt()))
        assertEquals("#FFFFFF", cssColor(0xFFFFFFFF.toInt()))
        assertEquals("rgba(28,27,31,0.50)", cssColor(0x801C1B1F.toInt()))
    }

    private companion object {
        /** app 声明了 `values-<locale>` 的语言，顺序与 assets/help 下无关，仅用于遍历。 */
        val LANGUAGES = listOf("en", "de", "fr", "ja", "zh-CN", "zh-TW")

        val PLACEHOLDER = Regex("""\{\{([a-zA-Z_]+)\}\}""")
        val H2 = Regex("<h2>")
        val H3 = Regex("<h3>")

        const val CJK_START = 0x4E00
        const val CJK_END = 0x9FFF
    }
}
