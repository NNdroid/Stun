package app.fjj.stun.ui

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.fjj.stun.R
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

/**
 * 设置页「桌面图标外观」小节的窄屏版式契约。
 *
 * 五档预览挤在一行里，320dp 是真正会出问题的宽度：
 * 256dp 可用宽 ÷ 5 格 = 每格 48dp，也就是标签的全部文字宽度（横向内边距已归零）。
 * 手算不算数，得真跑一次布局才知道标签会不会被压成「Clas…」。
 *
 * 标签长度由语言决定，且不是单调的：日文的长音符号（ー）和它前面的假名不可断行，
 * 所以 `グレープ` 在 40dp 宽度下会被排成「グ」+「レー…」——
 * 把横向内边距归零（拿到完整 48dp）正是为了消掉这个坑，第二条用例按语言逐个验。
 *
 * ⚠️ `@GraphicsMode(NATIVE)` 不能省：LEGACY 模式字体度量是假的，
 * 标签自然宽不随字号变化，截断这件事根本测不出来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconThemeSectionLayoutTest {

    private val pageHeightDp = 2400

    /** 一张预览卡 = 卡本身 + 卡内的预览图与标签。 */
    private val choices = listOf(
        Triple(R.id.card_icon_theme_default, R.id.iv_icon_theme_default, R.id.tv_icon_theme_default),
        Triple(R.id.card_icon_theme_aurora, R.id.iv_icon_theme_aurora, R.id.tv_icon_theme_aurora),
        Triple(R.id.card_icon_theme_ocean, R.id.iv_icon_theme_ocean, R.id.tv_icon_theme_ocean),
        Triple(R.id.card_icon_theme_mint, R.id.iv_icon_theme_mint, R.id.tv_icon_theme_mint),
        Triple(R.id.card_icon_theme_grape, R.id.iv_icon_theme_grape, R.id.tv_icon_theme_grape)
    )

    @Test
    fun `五档预览在窄屏与放大字体下都不截断`() {
        for (widthDp in intArrayOf(320, 360, 393)) {
            for (fontScale in floatArrayOf(1f, 1.5f)) {
                val root = render(fontScale = fontScale, locale = Locale.SIMPLIFIED_CHINESE, widthDp = widthDp)
                val tag = "${widthDp}dp / 字体 ${fontScale}"
                val problems = mutableListOf<String>()
                val cardWidths = mutableSetOf<Int>()

                assertNotNull("$tag 缺少图标外观预览行", root.findViewById<View>(R.id.row_icon_theme))

                for ((cardId, imageId, labelId) in choices) {
                    val card = root.findViewById<MaterialCardView>(cardId)
                    assertNotNull("$tag 缺少 ${resName(root, cardId)}", card)
                    cardWidths.add(card!!.width)
                    if (card.width == 0) {
                        problems += "${resName(root, cardId)} 宽度为 0"
                        continue
                    }

                    val image = root.findViewById<ImageView>(imageId)
                    if (image == null || image.drawable == null) {
                        problems += "${resName(root, cardId)} 的预览图为空（mipmap 资源没解析出来？）"
                    }

                    val label = root.findViewById<TextView>(labelId)
                    if (label == null || label.text.isEmpty()) {
                        problems += "${resName(root, cardId)} 缺少标签"
                        continue
                    }
                    if (ellipsisCount(label) > 0) {
                        problems += "${resName(root, cardId)} 标签被截断：${label.text}（省略号 ${ellipsisCount(label)} 处）"
                    }
                    if (label.width > card.width) {
                        problems += "${resName(root, cardId)} 标签(${label.width}px)比卡片(${card.width}px)还宽"
                    }
                }

                // weight=1 意味着五张卡应等宽。整数权重分配会有 ±1px 的舍入差，容忍它；
                // 一张被挤窄几像素以上才是真问题。
                val spread = (cardWidths.maxOrNull() ?: 0) - (cardWidths.minOrNull() ?: 0)
                assertTrue(
                    "$tag 五张预览卡宽度差 ${spread}px（应只有整数权重舍入的 1px 差）",
                    spread <= 1
                )
                assertTrue("$tag\n${problems.joinToString("\n")}", problems.isEmpty())
            }
        }
    }

    @Test
    fun `六门语言的标签在最窄屏与最大字号下都不截断`() {
        // 上一条只跑简体中文。标签宽度由语言决定：英文「Classic」7 个字符是全集里最长的，
        // 德语「Symbol-Erscheinungsbild」虽长但在标题行里、不受格子宽度约束。
        // 这里只在最坏宽度 320dp × 1.5 倍字号上验六门语言，别把 5 档全铺开。
        val locales = listOf(
            "en" to Locale.ENGLISH,
            "zh-rCN" to Locale.SIMPLIFIED_CHINESE,
            "zh-rTW" to Locale.TRADITIONAL_CHINESE,
            "de" to Locale.GERMAN,
            "fr" to Locale.FRENCH,
            "ja" to Locale.JAPANESE,
        )

        for ((tag, locale) in locales) {
            val root = render(fontScale = 1.5f, locale = locale, widthDp = 320)
            val problems = mutableListOf<String>()
            for ((cardId, imageId, labelId) in choices) {
                val label = root.findViewById<TextView>(labelId) ?: continue
                if (label.text.isEmpty()) {
                    problems += "$tag：${resName(root, cardId)} 缺少标签"
                    continue
                }
                if (ellipsisCount(label) > 0) {
                    problems += "$tag：${resName(root, cardId)} 标签被截断：${label.text}"
                }
            }
            assertTrue(problems.joinToString("\n"), problems.isEmpty())
        }
    }

    @Test
    fun `导出图标外观小节预览图`() {
        // 出图落 app/build/reports/ui-preview/icon-theme-section.png，人工过一眼配色与标签。
        val root = render(fontScale = 1f, locale = Locale.SIMPLIFIED_CHINESE, widthDp = 412)
        val card = root.findViewById<View>(R.id.card_icon_theme_section)!!
        val rect = android.graphics.Rect(0, 0, card.width, card.height)
        (root as android.view.ViewGroup).offsetDescendantRectToMyCoords(card, rect)
        val bitmap = exportBitmap(root, rect, "icon-theme-section")
        assertTrue("预览图不应是空的", bitmap.width > 0 && bitmap.height > 0)
    }

    private fun exportBitmap(root: View, rect: android.graphics.Rect, name: String): Bitmap {
        val bitmap = Bitmap.createBitmap(rect.width() * 2, rect.height() * 2, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(MaterialColors.getColor(root, attrId(root, "colorSurface"), Color.WHITE))
            // 2x 放大：1x 下 9sp 标签糊在一起，看不出有没有被截断。
            scale(2f, 2f)
            translate(-rect.left.toFloat(), -rect.top.toFloat())
            root.draw(this)
        }
        File("build/reports/ui-preview").apply { mkdirs() }
        File("build/reports/ui-preview", "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return bitmap
    }

    private fun attrId(view: View, name: String): Int =
        view.resources.getIdentifier(name, "attr", view.context.packageName)

    @Test
    fun `小节顺序在 UDP 网关小节之前`() {
        // 顺序错了不仅观感割裂，还会让 UdpGatewaySettingsI18nTest 的行号区间判据误判。
        val root = render(fontScale = 1f, locale = Locale.SIMPLIFIED_CHINESE, widthDp = 393)
        val iconTheme = root.findViewById<View>(R.id.card_icon_theme_section)!!
        val udpgw = root.findViewById<View>(R.id.card_udpgw_section)!!
        assertTrue(
            "图标外观(${iconTheme.top}px)应在 UDP 网关(${udpgw.top}px)之前",
            iconTheme.top < udpgw.top
        )
    }

    private fun render(fontScale: Float, locale: Locale, widthDp: Int): View {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        controller.setup()
        val config = Configuration(activity.resources.configuration).apply {
            this.fontScale = fontScale
            setLocale(locale)
            uiMode = Configuration.UI_MODE_NIGHT_NO
        }
        val themed = ContextThemeWrapper(activity.createConfigurationContext(config), R.style.Theme_Stun)
        val root = LayoutInflater.from(activity).cloneInContext(themed).inflate(R.layout.activity_settings, null)
        activity.setContentView(root)

        val dm = root.resources.displayMetrics
        val w = (widthDp * dm.density).toInt()
        val h = (pageHeightDp * dm.density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, w, h)
        return root
    }

    /** 所有行被省略号吃掉的字符总数 —— 被压窄到装不下时这里就是正的。 */
    private fun ellipsisCount(tv: TextView): Int {
        val layout = tv.layout ?: return 0
        return (0 until layout.lineCount).sumOf { layout.getEllipsisCount(it) }
    }

    private fun resName(root: View, id: Int): String =
        try {
            root.resources.getResourceEntryName(id)
        } catch (e: Exception) {
            "0x${id.toString(16)}"
        }
}
