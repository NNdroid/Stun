package app.fjj.stun.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import app.fjj.stun.databinding.ActivitySubscriptionHelpBinding
import com.google.android.material.color.MaterialColors
import java.util.Locale

/**
 * 订阅文件格式说明页。
 *
 * 内容是随 APK 打包的离线 HTML（`assets/help/<lang>/subscription_help.html`），
 * 一份语言一个目录 —— 不走 Android 的 res 限定符，因为 assets 不参与资源匹配，
 * 得由 [helpAssetDir] 自己挑。
 *
 * 三个刻意的设计点：
 *
 * 1. **不用 Custom Tabs / 系统浏览器。** Chrome 只接受 http/https，打不开 APK 内的
 *    `file://` 资源；要走 Chrome 就必须把页面挂到公网，而"隧道出问题时查订阅规则"
 *    正是最需要这页的时候。所以用内置 WebView（同一个 Blink 内核，观感一致）。
 *
 * 2. **JS 一律关掉。** 页面是纯静态的，没有任何脚本需求；关掉之后即便将来 HTML 被改坏
 *    也执行不了东西。同理关掉文件与内容访问 —— 所有样式都内联，没有子资源要取。
 *
 * 3. **主题色注入而不是自适应。** HTML 里的双花括号占位符在载入前被替换成当前主题色，
 *    因此页面跟着浅色/深色壁纸与动态取色走，也不必在 CSS 里复刻 Material 的配色规则。
 */
class SubscriptionHelpActivity : BaseActivity() {

    private lateinit var binding: ActivitySubscriptionHelpBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubscriptionHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        applyWindowInsets()

        binding.webView.apply {
            settings.apply {
                javaScriptEnabled = false
                domStorageEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                setSupportZoom(false)
                builtInZoomControls = false
            }
            // 说明里没有外链，出现跳转只可能是内容被改坏了 —— 一律留在本页。
            webViewClient = WebViewClient()
            // 与页面同色，否则深色主题下首帧会闪一下白底。
            setBackgroundColor(themeColor("colorSurface", FALLBACK_COLORS.getValue("bg")))
        }

        loadHelpPage(helpAssetDir(currentLocale()))
    }

    /** enableEdgeToEdge（BaseActivity）之后，顶部给工具栏、底部给导航栏要自己让出来。 */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.appBar.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            binding.webView.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    /**
     * 载入指定语言的说明页。
     *
     * 用 [WebView.loadDataWithBaseURL]（baseUrl 传 null）而不是 `loadUrl("file:///android_asset/…")`：
     * 正文直接进渲染器、不经过文件系统，因此不必放开 allowFileAccess，页面也不带 file:// 来源。
     * 样式全部内联，没有相对路径要解析。
     */
    private fun loadHelpPage(dir: String) {
        val assetPath = "help/$dir/subscription_help.html"
        val html = try {
            assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            // 资源缺失只可能是打包出错；给一句可读的提示，不要留白屏。
            Log.e(TAG, "Missing help asset: $assetPath", e)
            binding.webView.loadDataWithBaseURL(null, FALLBACK_HTML, MIME_HTML, CHARSET, null)
            return
        }
        binding.webView.loadDataWithBaseURL(null, applyThemeColors(html), MIME_HTML, CHARSET, null)
    }

    /**
     * 把 HTML 里的主题色占位符替换成当前主题色。
     *
     * 占位符名单（[PLACEHOLDER_ATTRS]）必须和 assets 里 6 份 HTML 完全一致 ——
     * [PLACEHOLDER_NAMES] 对外可见就是为了让单测直接比对，改漏一门语言会立刻红。
     *
     * 逐个 replace 而不是正则整体替换：万一 HTML 里写了名单外的名字，
     * 字面量会留在页面上、评审时一眼可见，而不是静默变成空字符串。
     */
    private fun applyThemeColors(html: String): String {
        var out = html
        for ((name, attrName) in PLACEHOLDER_ATTRS) {
            val argb = themeColor(attrName, FALLBACK_COLORS.getValue(name))
            out = out.replace("{{$name}}", cssColor(argb))
        }
        if (out.contains("{{")) {
            Log.w(TAG, "Help HTML still contains an unresolved placeholder")
        }
        return out
    }

    /**
     * 按 attr 名解析主题色，取不到用 [fallback]。
     *
     * 走 [android.content.res.Resources.getIdentifier] 而不是编译期常量，是因为本仓的 R 类
     * 不传递库属性（`R.attr.colorSurfaceContainerHigh` 直接编译不过）；库属性合并进 app 的
     * resources.arsc 之后，按 app 自己的包名一定能查到。同约定见 HomeFragment.getThemeColor。
     */
    private fun themeColor(attrName: String, fallback: Int): Int {
        val attrId = resources.getIdentifier(attrName, "attr", packageName)
        return if (attrId == 0) fallback else MaterialColors.getColor(binding.webView, attrId, fallback)
    }

    private fun currentLocale(): Locale {
        val locales = resources.configuration.locales
        return if (locales.size() > 0) locales[0] else Locale.getDefault()
    }

    companion object {
        private const val TAG = "SubscriptionHelp"
        private const val MIME_HTML = "text/html"
        private const val CHARSET = "UTF-8"

        private const val FALLBACK_HTML =
            "<html><body><p>Help content is unavailable.</p></body></html>"

        /**
         * 占位符名 → 主题 attr 名。占位符名同时就是 HTML 里的双花括号名字，
         * 以及 [FALLBACK_COLORS] 的键。
         */
        private val PLACEHOLDER_ATTRS = listOf(
            "bg" to "colorSurface",
            "fg" to "colorOnSurface",
            "muted" to "colorOnSurfaceVariant",
            "accent" to "colorPrimary",
            "code_bg" to "colorSurfaceContainerHigh",
            "outline" to "colorOutlineVariant",
            "note_bg" to "colorTertiaryContainer",
            "note_fg" to "colorOnTertiaryContainer"
        )

        /**
         * HTML 里允许出现的占位符名字。
         *
         * 单测会拿它和 assets 里 6 份 HTML 实际出现的占位符集合做全等比对：
         * 新增一个名字就必须同时改 6 份 HTML，反之亦然。**不要**为了过测试而放宽断言。
         */
        internal val PLACEHOLDER_NAMES: List<String> = PLACEHOLDER_ATTRS.map { it.first }

        // 主题解析失败时的兜底（M3 浅色基线）。正常路径走不到：BaseActivity 已
        // applyToActivityIfAvailable，且 Theme.Stun 显式声明了这些颜色角色。
        private val FALLBACK_COLORS = mapOf(
            "bg" to 0xFFFFFBFE.toInt(),
            "fg" to 0xFF1C1B1F.toInt(),
            "muted" to 0xFF49454F.toInt(),
            "accent" to 0xFF6750A4.toInt(),
            "code_bg" to 0xFFECE6F0.toInt(),
            "outline" to 0xFFCAC4D0.toInt(),
            "note_bg" to 0xFFEADDFF.toInt(),
            "note_fg" to 0xFF21005D.toInt()
        )

        fun intent(context: Context): Intent =
            Intent(context, SubscriptionHelpActivity::class.java)

        /**
         * 语言 → assets 目录名。
         *
         * 只做 app 声明了 `values-<locale>` 的那几门语言（en/de/fr/ja/zh-rCN/zh-rTW），
         * 其余一律回落英文。中文再按"繁体还是简体"分：脚本标记优先，没有脚本标记时看地区
         * （TW/HK/MO 走繁体）—— 港澳用户读繁体更自然，而 app 的 zh-rTW 正是繁体资源。
         *
         * internal 是为了让单测直接测这张映射表，不必真起 Activity。
         */
        internal fun helpAssetDir(locale: Locale): String =
            when (locale.language.lowercase(Locale.ROOT)) {
                "zh" -> if (isTraditionalChinese(locale)) "zh-TW" else "zh-CN"
                "ja" -> "ja"
                "de" -> "de"
                "fr" -> "fr"
                else -> "en"
            }

        private fun isTraditionalChinese(locale: Locale): Boolean {
            when (locale.script.lowercase(Locale.ROOT)) {
                "hant" -> return true
                "hans" -> return false
            }
            return locale.country.uppercase(Locale.ROOT) in setOf("TW", "HK", "MO")
        }
    }
}

/**
 * 主题色 → CSS 颜色。不透明走 `#RRGGBB`，带透明度才用 `rgba()`（对老 WebView 更稳）。
 *
 * 用移位而不是 `android.graphics.Color`：后者的方法体在单元测试用的 mockable android.jar
 * 里是空的（调用即抛 "not mocked"），而这段纯计算没必要为测试背上 Robolectric。
 */
internal fun cssColor(argb: Int): String {
    val alpha = (argb ushr 24) and 0xFF
    val red = (argb ushr 16) and 0xFF
    val green = (argb ushr 8) and 0xFF
    val blue = argb and 0xFF
    return if (alpha == 255) {
        String.format(Locale.ROOT, "#%02X%02X%02X", red, green, blue)
    } else {
        String.format(Locale.ROOT, "rgba(%d,%d,%d,%.2f)", red, green, blue, alpha / 255f)
    }
}
