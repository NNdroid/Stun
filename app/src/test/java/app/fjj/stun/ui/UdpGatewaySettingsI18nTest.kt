package app.fjj.stun.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * UDP 网关小节那组文案的**六语言完整性**回归。
 *
 * 「UDP 会话上限 / UDP 空闲超时」最初加在「自定义直连路由」小节，且只配了 zh-rCN 的译文 ——
 * 其余四门语言静默回落成英文，`assembleDebug` 与所有单测都是绿的（aapt 不校验"有没有翻"，
 * 只校验"译文字面合法不合法"）。挪进 UDP 网关小节并补齐六语言后，这条测试钉住这个状态。
 *
 * 判据刻意用**键集合全等**而不是"译文不等于英文"：后者在 en/de/fr 之间同形的词上会假红，
 * 前者能同时抓住"漏语言"和"多写/键名打错"两个方向。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class UdpGatewaySettingsI18nTest {

    /** 六个 locale 的 UDP 网关组文案键集合必须与默认串完全一致。 */
    @Test
    fun `六门语言的 UDP 会话限制文案键集合与默认串一致`() {
        val expected = keysIn(File(coreResDir("values"), "strings.xml"))
        assertTrue(
            "默认串里没找到任何 UDP 会话限制键，测试本身失效了（键名改了要同步这里）",
            expected.containsAll(UDP_KEYS),
        )

        for (locale in LOCALES) {
            val file = File(coreResDir("values-$locale"), "strings.xml")
            assertTrue("缺少 $locale 的 core 串文件：${file.path}", file.isFile)
            val actual = keysIn(file)
            for (key in UDP_KEYS) {
                assertTrue(
                    "$locale 缺少 $key —— 该语言会静默回落成英文，aapt 不会报错",
                    key in actual,
                )
            }
        }
    }

    /** 每门语言的译文必须真的翻过，不能是英文原文原样复制。 */
    @Test
    fun `非英文 locale 的译文不等于默认串`() {
        val defaults = valuesIn(File(coreResDir("values"), "strings.xml"))
        for (locale in LOCALES) {
            val translated = valuesIn(File(coreResDir("values-$locale"), "strings.xml"))
            for (key in UDP_KEYS) {
                val base = defaults[key]
                val target = translated[key]
                assertTrue("$locale 的 $key 缺失", target != null)
                assertNotEquals(
                    "$locale 的 $key 与英文原文完全相同 —— 大概率是复制时忘了翻",
                    base, target,
                )
            }
        }
    }

    /**
     * 这两个输入框必须位于 UDP 网关小节内，而不是留在原来的「自定义直连路由」小节。
     *
     * 判据是行号区间：`card_udpgw_section` 开始、下一节开始之前。只看"字段存在"不够 ——
     * 挪回去也不会报错，只是又变成一个语义错位的设置项。
     */
    @Test
    fun `两个输入框位于 UDP 网关小节内`() {
        val lines = settingsLayout().readLines()
        val udpgwAt = indexOfFirst(lines, "card_udpgw_section")
        val routingAt = indexOfFirst(lines, "custom_direct_routing")
        val maxAt = indexOfFirst(lines, "et_udp_max_sessions")
        val idleAt = indexOfFirst(lines, "et_udp_idle_timeout")

        assertTrue("布局里找不到 card_udpgw_section", udpgwAt >= 0)
        assertTrue("布局里找不到 custom_direct_routing 小节", routingAt >= 0)
        assertTrue(
            "UDP 网关小节必须在自定义直连路由小节之前（否则区间判断无意义）",
            udpgwAt < routingAt,
        )
        assertTrue(
            "et_udp_max_sessions 不在 UDP 网关小节内（行 $maxAt，小节 $udpgwAt..$routingAt）",
            maxAt in (udpgwAt + 1) until routingAt,
        )
        assertTrue(
            "et_udp_idle_timeout 不在 UDP 网关小节内（行 $idleAt，小节 $udpgwAt..$routingAt）",
            idleAt in (udpgwAt + 1) until routingAt,
        )
        assertEquals("两个输入框的相对顺序应保持上限在前、超时在后", true, maxAt < idleAt)
    }

    /**
     * 运行时取串：日文下 `udpgw_session_title` 必须取到译文，不是英文原文。
     *
     * 为什么不能只靠读源文件：aapt 只校验译文字面合法，**不保证**这条串被合并进 APK、
     * 也不保证 qualifier 匹配得上。真取一次 `getString` 才知道用户看到的是什么。
     */
    @Test
    @Config(qualifiers = "ja-w393dp-h851dp-xhdpi")
    fun `日文下取出的会话限制标题是译文`() {
        val actual = stringOf("udpgw_session_title")
        assertEquals("日文下应取到译文，实际取到：$actual", "UDP セッション制限", actual)
        assertNotEquals(
            "取到了英文原文 —— 说明 ja 的串没被打包进去或 qualifier 没匹配上",
            "UDP Session Limits", actual,
        )
    }

    @Test
    @Config(qualifiers = "en-rUS-w393dp-h851dp-xhdpi")
    fun `英文下取出的会话限制文案是默认串`() {
        assertEquals("UDP Session Limits", stringOf("udpgw_session_title"))
        assertEquals(
            "helper 里的 \"0 =\" 与数字不能因为 aapt 吞空白而变形",
            "0 = default 1024",
            stringOf("udp_max_sessions_helper"),
        )
        assertEquals("0 = default 60s", stringOf("udp_idle_timeout_helper"))
    }

    // ------------------------------------------------------- harness

    /** core 的 res 目录：Gradle 的工作目录可能是仓库根或 core，两级探测。 */
    private fun coreResDir(name: String): File {
        listOf(File("core/src/main/res/$name"), File("../core/src/main/res/$name"))
            .firstOrNull { it.isDirectory }
            ?.let { return it }
        throw AssertionError("找不到 core/src/main/res/$name（工作目录 ${File(".").absolutePath}）")
    }

    private fun settingsLayout(): File {
        listOf(
            File("src/main/res/layout/activity_settings.xml"),
            File("app/src/main/res/layout/activity_settings.xml"),
        ).firstOrNull { it.isFile }?.let { return it }
        throw AssertionError("找不到 activity_settings.xml（工作目录 ${File(".").absolutePath}）")
    }

    private fun indexOfFirst(lines: List<String>, needle: String): Int =
        lines.indexOfFirst { it.contains(needle) }

    /**
     * 运行时按名字取串。
     *
     * 刻意走 `getIdentifier` 而不是直接引用 `CoreR.string.*`：core 的 R 在 app 的**测试**
     * 源集里解析不到（`Unresolved reference 'core'`），而资源合并后这些串本来就属于 app 包。
     */
    private fun stringOf(name: String): String {
        val ctx = RuntimeEnvironment.getApplication()
        val id = ctx.resources.getIdentifier(name, "string", ctx.packageName)
        assertTrue("运行时取不到串 $name —— 没被合并进 APK 或键名写错", id != 0)
        return ctx.getString(id)
    }

    private fun keysIn(file: File): Set<String> =
        UDP_KEY_RE.findAll(file.readText(Charsets.UTF_8)).map { it.groupValues[1] }.toSet()

    private fun valuesIn(file: File): Map<String, String> =
        VALUE_RE.findAll(file.readText(Charsets.UTF_8))
            .associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        /** 与 `values-<locale>` 目录一一对应（core 的串不按功能拆，每个 locale 一个整文件）。 */
        val LOCALES = listOf("zh-rCN", "zh-rTW", "de", "fr", "ja")

        /**
         * UDP 会话限制子卡的标题/说明、两个输入框的 hint 与 helper，以及两条无障碍描述。
         * 前缀刻意分开写：`udpgw_session_*` 是子卡文案，`udp_*` 是两个输入框，
         * `desc_udp_*` 给 TalkBack 用 —— 三者缺任何一类都算没翻完。
         */
        val UDP_KEYS = setOf(
            "udpgw_session_title",
            "udpgw_session_desc",
            "udp_max_sessions",
            "udp_max_sessions_helper",
            "udp_idle_timeout",
            "udp_idle_timeout_helper",
            "desc_udp_max_sessions_input",
            "desc_udp_idle_timeout_input",
        )

        val UDP_KEY_RE = Regex("""<(?:string|plurals)\s+name="((?:udpgw_session|udp_|desc_udp_)[a-z_0-9]+)"""")
        val VALUE_RE = Regex("""<string\s+name="([a-z_0-9]+)">(.*?)</string>""")
    }
}
