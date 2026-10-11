package app.fjj.stun.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 设置页「桌面图标外观」那组文案的**六语言完整性**回归。
 *
 * aapt 只校验「译文字面合法」，不校验「有没有翻」——
 * 漏一门语言会静默回落成英文，`assembleDebug` 和所有单测都是绿的，只有用户才发现。
 * 这里钉住三个方向：键集合全等（漏语言 + 多写/键名打错）、译文不等于英文（复制忘了翻）、
 * 标签长度（设置页那张预览格是 320dp ÷ 5 格算出来的，标签过长会被压成省略号）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class IconThemeSettingsI18nTest {

    @Test
    fun `六门语言的图标外观文案键集合与默认串一致`() {
        val expected = keysIn(File(coreResDir("values"), "strings.xml"))
        assertTrue(
            "默认串里没找到任何图标外观键，测试本身失效了（键名改了要同步这里）",
            expected.containsAll(ICON_THEME_KEYS),
        )

        for (locale in LOCALES) {
            val file = File(coreResDir("values-$locale"), "strings.xml")
            assertTrue("缺少 $locale 的 core 串文件：${file.path}", file.isFile)
            val actual = keysIn(file)
            for (key in ICON_THEME_KEYS) {
                assertTrue(
                    "$locale 缺少 $key —— 该语言会静默回落成英文，aapt 不会报错",
                    key in actual,
                )
            }
        }
    }

    @Test
    fun `非英文 locale 的译文不等于默认串`() {
        val defaults = valuesIn(File(coreResDir("values"), "strings.xml"))
        for (locale in LOCALES) {
            val translated = valuesIn(File(coreResDir("values-$locale"), "strings.xml"))
            for (key in ICON_THEME_KEYS) {
                val base = defaults[key]
                val target = translated[key]
                assertNotNull("$locale 的 $key 为空", target)
                if (key in IDENTICAL_BY_DESIGN[locale].orEmpty()) continue
                assertNotEquals(
                    "$locale 的 $key 与英文原文完全相同 —— 大概率是复制时忘了翻",
                    base, target,
                )
            }
        }
    }

    @Test
    fun `五档标签在所有语言里都不超过七字符`() {
        // 设置页那张预览格按 320dp 算：256dp 可用宽 ÷ 5 格 = 每格 48dp，扣掉内边距只剩 40dp。
        // 9sp 下七个字符是实际能放下不截断的上限，超了标签会变省略号。
        val maxChars = 7
        for (locale in listOf("values") + LOCALES.map { "values-$it" }) {
            val values = valuesIn(File(coreResDir(locale), "strings.xml"))
            for (key in LABEL_KEYS) {
                val label = values[key]
                assertNotNull("$locale 的 $key 为空", label)
                assertTrue(
                    "$locale 的 $key「$label」超过 ${maxChars} 字符，窄屏预览格会截断",
                    label!!.length <= maxChars,
                )
            }
        }
    }

    @Test
    fun `切换提示的格式串只有一个占位符`() {
        // SettingsFragment 里是 getString(icon_theme_applied, getString(label))，
        // 多写一个位置参数会让 aapt 直接构建失败，少写一个则提示里出现裸 "1"。
        for (locale in listOf("values") + LOCALES.map { "values-$it" }) {
            val value = valuesIn(File(coreResDir(locale), "strings.xml"))["icon_theme_applied"]
            assertNotNull("$locale 缺少 icon_theme_applied", value)
            assertEquals(
                "$locale 的 icon_theme_applied 位置参数数应为 1：$value",
                1, POSITIONAL_RE.findAll(value!!).count()
            )
        }
    }

    @Test
    @Config(qualifiers = "ja-w393dp-h851dp-xhdpi")
    fun `日文下取出的图标外观文案是译文`() {
        // 为什么不能只靠读源文件：aapt 只校验译文字面合法，不保证这条串被合并进 APK、
        // 也不保证 qualifier 匹配得上。真取一次 getString 才知道用户看到的是什么。
        assertEquals("アイコンの外観", stringOf("icon_theme_title"))
        assertEquals("標準", stringOf("icon_theme_default"))
        assertEquals("グレープ", stringOf("icon_theme_grape"))
        assertEquals("アイコンの外観を「%1\$s」に設定しました", stringOf("icon_theme_applied"))
        assertNotEquals("取到了英文原文", "Classic", stringOf("icon_theme_default"))
    }

    // ------------------------------------------------------- harness

    /** core 的 res 目录：Gradle 的工作目录可能是仓库根或 core，两级探测。 */
    private fun coreResDir(name: String): File {
        listOf(File("core/src/main/res/$name"), File("../core/src/main/res/$name"))
            .firstOrNull { it.isDirectory }
            ?.let { return it }
        throw AssertionError("找不到 core/src/main/res/$name（工作目录 ${File(".").absolutePath}）")
    }

    /**
     * 运行时按名字取串。
     *
     * 刻意走 `getIdentifier` 而不是直接引用 `CoreR.string.*`：core 的 R 在 app 的**测试**
     * 源集里解析不到，而资源合并后这些串本来就属于 app 包。
     */
    private fun stringOf(name: String): String {
        val ctx = RuntimeEnvironment.getApplication()
        val id = ctx.resources.getIdentifier(name, "string", ctx.packageName)
        assertTrue("运行时取不到串 $name —— 没被合并进 APK 或键名写错", id != 0)
        return ctx.getString(id)
    }

    private fun keysIn(file: File): Set<String> =
        ICON_THEME_KEY_RE.findAll(file.readText(Charsets.UTF_8)).map { it.groupValues[1] }.toSet()

    private fun valuesIn(file: File): Map<String, String> =
        VALUE_RE.findAll(file.readText(Charsets.UTF_8))
            .associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        /** 与 `values-<locale>` 目录一一对应（core 的串不按功能拆，每个 locale 一个整文件）。 */
        val LOCALES = listOf("zh-rCN", "zh-rTW", "de", "fr", "ja")

        /** 卡片标题 + 说明 + 五档标签 + 切换提示，缺一不可。 */
        val ICON_THEME_KEYS = setOf(
            "icon_theme_title",
            "icon_theme_desc",
            "icon_theme_default",
            "icon_theme_aurora",
            "icon_theme_ocean",
            "icon_theme_mint",
            "icon_theme_grape",
            "icon_theme_applied",
        )

        /** 只有五档标签受宽度约束；标题、说明、提示都在整宽文本行里，不受限。 */
        val LABEL_KEYS = setOf(
            "icon_theme_default",
            "icon_theme_aurora",
            "icon_theme_ocean",
            "icon_theme_mint",
            "icon_theme_grape",
        )

        /**
         * 允许与英文原文逐字节相同的「专名」白名单。
         *
         * 刻意做成显式清单而不是放宽整条断言：`Aurora` 在德语里本就是这个词，
         * 为了迁就测试硬译成「北光」反而更糟。清单外的任何 locale 一旦整段复制成英文，
         * 「译文不等于默认串」那条立刻炸——这里只豁免真正需要豁免的那一个键。
         */
        val IDENTICAL_BY_DESIGN = mapOf("de" to setOf("icon_theme_aurora"))

        val ICON_THEME_KEY_RE = Regex("""<string\s+name="((?:icon_theme)[a-z_0-9]*)""")
        val VALUE_RE = Regex("""<string\s+name="([a-z_0-9]+)">(.*?)</string>""")
        val POSITIONAL_RE = Regex("""%(\d+)\$[sdf]""")
    }
}
