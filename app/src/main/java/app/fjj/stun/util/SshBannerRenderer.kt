package app.fjj.stun.util

import android.content.Intent
import android.net.Uri
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.text.HtmlCompat

/**
 * SSH 服务端 banner 的富文本渲染。
 *
 * banner（RFC 4252 §5.4）本身只是无格式 UTF-8 文本：颜色与 HTML 语义完全由客户端决定，
 * 因此服务端可能发来 ANSI 色码（很多 MOTD 脚本这么干），也可能发来 HTML 片段。
 *
 * 处理思路与 WebUI 端（core/src/main/assets/web/app.js 的 sanitizeBannerHtml）保持同一套白名单语义，
 * 但手段不同：WebView 那侧是把内容解析成 DOM 再逐节点重建；这里把预处理交给字符串阶段，
 * 再把真正的解析交给 [HtmlCompat.fromHtml] —— 后者只认固定的标签集合、不执行脚本、
 * 没有 ImageGetter 时也不会去加载图片，因此是一个可靠的安全闸门。
 *
 * 两道处理：
 *  1. [toRenderableHtml]（纯字符串，可单测）：清洗控制序列、把 ANSI SGR 与 `style="color:…"`
 *     统一转成 fromHtml 认得的 `<font color>`/`<b>`/`<i>`/`<u>`、剥掉危险标签与全部属性，
 *     并把等宽标签与空白改写成 fromHtml 真正会保留的形态（见 [MONOSPACE_ALIASES]/[encodeWhitespace]）。
 *  2. [render]：交给 fromHtml 解析，再把结果里的 URLSpan 换成只放行 http(s) 的可点击 span
 *     （否则 `javascript:` 这类 URI 在点击时会抛 ActivityNotFoundException 直接崩）。
 */
object SshBannerRenderer {

    private const val ESC = '\u001B'

    /** 危险标签：连同内容一起丢弃。fromHtml 不认识它们，不处理就会把标签文字原样显示出来。 */
    private val DROP_WITH_CONTENT = listOf(
        "script", "style", "iframe", "noscript", "svg", "math", "template", "object", "applet",
        "canvas", "audio", "video", "form", "select", "textarea", "button", "title", "head",
        "body", "frame", "frameset", "portal"
    )

    /** 无内容的标签：只丢弃标签本身。 */
    private val DROP_TAG_ONLY = listOf(
        "img", "input", "link", "meta", "base", "embed", "source", "track", "param", "option",
        "wbr", "hr", "area", "col"
    )

    /** 允许透传的标签（与 WebUI 白名单一致，另加 font 承载颜色）。 */
    private val PASS_THROUGH = setOf(
        "b", "strong", "i", "em", "u", "s", "strike", "del", "br", "a",
        "small", "sub", "sup", "p", "div", "ul", "ol", "li", "font"
    )

    /**
     * 等宽语义的标签。
     *
     * fromHtml 只把 `<tt>` 认成等宽（TypefaceSpan）；`<pre>`/`<code>` 它既不产生等宽 span，
     * 也不保留块内空白（实测 `<pre>a\nb</pre>` 得到的是 "a b"）。所以这些标签统一改写为 `<tt>`，
     * 空白保留由 [encodeWhitespace] 负责。
     */
    private val MONOSPACE_ALIASES = setOf("pre", "code", "tt", "kbd", "samp", "var")

    /** 制表符展开宽度：不依赖字体是否带 tab 字形，保证缩进可预测。 */
    private const val TAB_WIDTH = 4
    private const val BR = "<br>"
    private const val NBSP = "&nbsp;"

    /** 自闭合、无需配对结束标签的标签。 */
    private val VOID_TAGS = setOf("br")

    private val ANSI_COLORS = mapOf(
        30 to "#5C6B7A", 31 to "#E74C3C", 32 to "#2ECC71", 33 to "#F1C40F",
        34 to "#3498DB", 35 to "#9B59B6", 36 to "#1ABC9C", 37 to "#BDC3C7",
        90 to "#7F8C8D", 91 to "#FF6B6B", 92 to "#58D68D", 93 to "#FFD93D",
        94 to "#5DADE2", 95 to "#C39BD3", 96 to "#48C9B0", 97 to "#F4F6F7"
    )

    private val OSC_SEQ = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")
    private val CSI_NON_SGR = Regex("\u001B\\[[0-9;?]*[ -/]*[@-ln-~]")
    private val CHARSET_SEQ = Regex("\u001B[()*+/.,-][0-9A-Za-z]?")
    private val CONTROL_CHARS = Regex("[\u0000-\u0008\u000B-\u001A\u001C-\u001F\u007F]")
    private val ANSI_SGR = Regex("\u001B\\[([0-9;]*)m")
    private val TAG = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)((?:\"[^\"]*\"|'[^']*'|[^>\"'])*)>")

    /** 把 banner 渲染进 TextView；含可点击链接时自动挂上 LinkMovementMethod。 */
    fun applyTo(textView: TextView, raw: String) {
        val text = render(raw)
        textView.text = text
        val hasLink = text is Spanned && text.getSpans(0, text.length, ClickableSpan::class.java).isNotEmpty()
        if (hasLink) {
            textView.movementMethod = LinkMovementMethod.getInstance()
            textView.isClickable = true
        }
    }

    /** 渲染为可直接赋给 TextView 的富文本。 */
    fun render(raw: String): CharSequence {
        val html = toRenderableHtml(raw)
        if (html.isBlank()) return ""
        val spanned = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
        return replaceUrlSpans(spanned)
    }

    // ── 第一道：纯字符串处理（不依赖 Android 框架，便于单测） ──

    internal fun toRenderableHtml(raw: String): String {
        if (raw.isBlank()) return ""
        var text = preClean(raw)
        text = dropDangerousTags(text)
        text = rewriteTags(text)
        text = ansiToTags(text)
        text = encodeWhitespace(text)
        return text
    }

    /**
     * 去掉 OSC、非 SGR 的 CSI、字符集指定与控制字符。
     * 保留 `\t`/`\n` 以及 SGR 序列的 ESC —— 后者还要用来取颜色。
     */
    private fun preClean(raw: String): String = raw
        .replace(OSC_SEQ, "")
        .replace(CSI_NON_SGR, "")
        .replace(CHARSET_SEQ, "")
        .replace(CONTROL_CHARS, "")

    private fun dropDangerousTags(input: String): String {
        var out = input
        for (name in DROP_WITH_CONTENT) {
            out = Regex("(?is)<$name\\b[^>]*>.*?</$name\\s*>").replace(out, "")
            out = Regex("(?is)<$name\\b[^>]*/?>").replace(out, "")
            out = Regex("(?is)</$name\\s*>").replace(out, "")
        }
        return out
    }

    /**
     * 逐个标签重建：属性全部丢弃，只保留校验过的 `href`；
     * `style` 里的颜色/粗体/斜体/下划线转成等价的 `<font color>`/`<b>`/`<i>`/`<u>`；
     * 白名单外的标签丢壳留内容。用栈记录每个开标签产生的结束标签序列，保证成对闭合。
     */
    private fun rewriteTags(input: String): String {
        val out = StringBuilder()
        val stack = ArrayDeque<List<String>>()
        var cursor = 0
        for (match in TAG.findAll(input)) {
            out.append(input, cursor, match.range.first)
            cursor = match.range.last + 1

            val isClosing = match.groupValues[1] == "/"
            val name = match.groupValues[2].lowercase()
            val attrs = match.groupValues[3]
            val selfClosing = attrs.trimEnd().endsWith("/")

            if (isClosing) {
                stack.removeLastOrNull()?.asReversed()?.forEach { out.append(it) }
                continue
            }
            if (name in DROP_TAG_ONLY) continue

            val open = mutableListOf<String>()
            val close = mutableListOf<String>()

            val style = parseStyle(attrValue(attrs, "style"))
            styleColor(style["color"])?.let {
                open += "<font color=\"$it\">"
                close += "</font>"
            }
            if (isBold(style["font-weight"])) {
                open += "<b>"
                close += "</b>"
            }
            if (style["font-style"] == "italic" || style["font-style"] == "oblique") {
                open += "<i>"
                close += "</i>"
            }
            if (style["text-decoration"].orEmpty().contains("underline")) {
                open += "<u>"
                close += "</u>"
            }

            if (name == "a") {
                val href = attrValue(attrs, "href").orEmpty().trim()
                // href 不在放行清单里就连 <a> 壳一起丢掉：空 href 会被 fromHtml 做成可点击 span，
                // 而 fromHtml 对无 href 的 <a> 仍会留下一个 Href 标记，不如直接当成普通文本。
                if (isSafeUrl(href)) {
                    open += "<a href=\"$href\">"
                    close += "</a>"
                }
            } else if (name in MONOSPACE_ALIASES) {
                open += "<tt>"
                close += "</tt>"
            } else if (name in PASS_THROUGH) {
                open += "<$name>"
                close += "</$name>"
            }

            open.forEach { out.append(it) }
            val needsCloser = close.isNotEmpty() && !selfClosing && name !in VOID_TAGS
            stack.addLast(if (needsCloser) close else emptyList())
        }
        out.append(input, cursor, input.length)
        return out.toString()
    }

    /** 把 ANSI SGR 色码转换成 `<font color>`/`<b>`/`<i>`/`<u>`，并抹掉残留的裸 ESC。 */
    private fun ansiToTags(input: String): String {
        if (!input.contains(ESC)) return input
        val out = StringBuilder()
        var current: List<Pair<String, String>> = emptyList()
        var cursor = 0
        for (match in ANSI_SGR.findAll(input)) {
            out.append(stripEsc(input.substring(cursor, match.range.first)))
            cursor = match.range.last + 1
            val codes = match.groupValues[1].ifEmpty { "0" }
                .split(';').map { it.trim().toIntOrNull() ?: 0 }
            val next = applyCodes(current, codes)
            if (next != current) {
                current.asReversed().forEach { out.append(it.second) }
                next.forEach { out.append(it.first) }
                current = next
            }
        }
        out.append(stripEsc(input.substring(cursor)))
        current.asReversed().forEach { out.append(it.second) }
        return out.toString()
    }

    private fun applyCodes(
        current: List<Pair<String, String>>,
        codes: List<Int>
    ): List<Pair<String, String>> {
        var color = current.firstOrNull { it.first.startsWith("<font") }
        var bold = current.any { it.first == "<b>" }
        var italic = current.any { it.first == "<i>" }
        var underline = current.any { it.first == "<u>" }
        for (code in codes) {
            when {
                code == 0 -> {
                    color = null; bold = false; italic = false; underline = false
                }
                code == 1 -> bold = true
                code == 3 -> italic = true
                code == 4 -> underline = true
                ANSI_COLORS.containsKey(code) -> {
                    color = "<font color=\"${ANSI_COLORS.getValue(code)}\">" to "</font>"
                }
            }
        }
        // 固定顺序构造，保证状态相同时列表相等（ansiToTags 依赖这个相等性来跳过空转换）
        return buildList {
            color?.let { add(it) }
            if (bold) add("<b>" to "</b>")
            if (italic) add("<i>" to "</i>")
            if (underline) add("<u>" to "</u>")
        }
    }

    private fun stripEsc(text: String): String =
        if (text.indexOf(ESC) < 0) text else text.replace(ESC.toString(), "")

    /**
     * 把文本里的空白改写成 fromHtml 能原样保留的形式。
     *
     * HtmlCompat 走的是 HTML 排版语义：`\n` 当空格、连续空格折叠成一个、`<br>` 之后的行首空格
     * 直接被吃掉。banner 是预排版文本（MOTD 常见 ASCII art），不处理就整段塌成一行。所以：
     *  - `\n` → `<br>`：fromHtml 唯一可靠产出换行的方式；
     *  - `\t` → [TAB_WIDTH] 个空格；
     *  - 每串空白里首个留普通空格（保住换行点，长行仍可在词间折行），其余换 [NBSP]（保住对齐，
     *    同时避开「行首空白被丢」的规则）。
     *
     * 只处理标签之间的文本片段——我们自己生成的标签（`<font color="…">` 等）里也有空格，不能碰。
     */
    private fun encodeWhitespace(input: String): String {
        val out = StringBuilder(input.length + 16)
        var cursor = 0
        var atLineStart = true
        var previousWasSpace = false

        fun text(chunk: String) {
            for (c in chunk) {
                when {
                    c == '\r' -> Unit
                    c == '\n' -> {
                        out.append(BR)
                        atLineStart = true
                        previousWasSpace = false
                    }
                    c == ' ' || c == '\t' -> repeat(if (c == '\t') TAB_WIDTH else 1) {
                        out.append(if (atLineStart || previousWasSpace) NBSP else ' ')
                        previousWasSpace = true
                        atLineStart = false
                    }
                    else -> {
                        out.append(c)
                        previousWasSpace = false
                        atLineStart = false
                    }
                }
            }
        }

        for (match in TAG.findAll(input)) {
            text(input.substring(cursor, match.range.first))
            out.append(match.value)
            cursor = match.range.last + 1
        }
        text(input.substring(cursor))
        return out.toString()
    }

    private fun parseStyle(style: String?): Map<String, String> {
        if (style.isNullOrBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (decl in style.split(';')) {
            val sep = decl.indexOf(':')
            if (sep <= 0) continue
            val key = decl.substring(0, sep).trim().lowercase()
            val value = decl.substring(sep + 1).trim().lowercase()
            if (key.isNotEmpty() && value.isNotEmpty()) result[key] = value
        }
        return result
    }

    private fun attrValue(attrs: String, name: String): String? {
        val pattern = Regex("(?i)\\b$name\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
        val match = pattern.find(attrs) ?: return null
        return match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
    }

    private fun isBold(value: String?): Boolean {
        if (value == null) return false
        if (value == "bold" || value == "bolder") return true
        return (value.toIntOrNull() ?: 0) >= 600
    }

    /** 只接受 http(s)，避免 `javascript:` / `data:` 之类被 fromHtml 做成可点击 span。 */
    private fun isSafeUrl(url: String): Boolean =
        url.startsWith("http://", true) || url.startsWith("https://", true)

    /** 颜色只放行十六进制与 rgb()，避免把任意字符串塞进 color 属性。 */
    private fun styleColor(value: String?): String? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return null
        if (Regex("^#([0-9a-f]{3}|[0-9a-f]{6}|[0-9a-f]{8})$").matches(v)) return v
        val rgb = Regex("^rgba?\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})").find(v) ?: return null
        val (r, g, b) = rgb.destructured
        val parts = listOf(r, g, b).map { it.toIntOrNull() ?: return null }
        if (parts.any { it !in 0..255 }) return null
        return "#%02X%02X%02X".format(parts[0], parts[1], parts[2])
    }

    // ── 第二道：解析后用可点击 span 收口 ──

    /**
     * 把 fromHtml 产生的 URLSpan 换成自管的 ClickableSpan：
     * 默认 URLSpan 点击时直接 startActivity，遇到没有浏览器（或 scheme 非法）会抛
     * ActivityNotFoundException 直接崩溃，这里捕获并提示。
     */
    private fun replaceUrlSpans(spanned: Spanned): Spanned {
        val spannable: Spannable =
            if (spanned is Spannable) spanned else SpannableString(spanned)
        val urlSpans = spannable.getSpans(0, spannable.length, URLSpan::class.java)
        for (urlSpan in urlSpans) {
            val start = spannable.getSpanStart(urlSpan)
            val end = spannable.getSpanEnd(urlSpan)
            val flags = spannable.getSpanFlags(urlSpan)
            val href = urlSpan.url.orEmpty()
            spannable.removeSpan(urlSpan)
            if (start in 0 until end) {
                spannable.setSpan(SafeLinkSpan(href), start, end, flags)
            }
        }
        return spannable
    }

    private class SafeLinkSpan(private val href: String) : ClickableSpan() {
        override fun onClick(widget: View) {
            if (!isSafeUrl(href)) return
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(href))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                widget.context.startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(
                    widget.context,
                    widget.context.getString(app.fjj.stun.R.string.connection_banner_link_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        override fun updateDrawState(ds: TextPaint) {
            super.updateDrawState(ds)
            ds.color = ds.linkColor
            ds.isUnderlineText = true
        }
    }
}
