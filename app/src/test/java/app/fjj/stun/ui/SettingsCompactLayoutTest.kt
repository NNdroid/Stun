package app.fjj.stun.ui

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import app.fjj.stun.R
import app.fjj.stun.core.R as CoreR
import com.google.android.material.button.MaterialButton
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
 * 设置页「瘦身」版式契约，出图落 `app/build/reports/ui-preview/settings-compact-{light,dark}.png`。
 *
 * 这一版按用户标示改了三处，本文件把三处都钉死（都靠**真跑一次布局**才看得见）：
 *
 * 1. **取消拥挤**：UDP 网关实现的 tun2proxy/badvpn 两张卡、应用分流的「排除/仅指定」两张卡，
 *    都只留「radio + 名称」——Recommended 徽标与 4 条说明文案（含整个 Tips 子卡）已删除。
 *    判据：每张选择卡里只应有 **1 个** TextView；UDP 网关小节内应只剩 3 块（头部行 /
 *    实现子卡 / 地址子卡）。
 * 2. **WebDAV 页脚分行**：原来「上次备份」和两个按钮挤在一行，窄屏上 label 被压成
 *    「Last b / ackup:」两条竖排窄字。现在页脚内层是纵向容器、恰好两行。
 *    判据：按钮行的 `top` 必须 ≥「上次备份」的 `bottom`。
 * 3. **按钮不许被压扁**：320dp 下这两个按钮的自然宽合计约 267dp，而卡片内可用只有约 232dp，
 *    所以行 2 外面套了 `HorizontalScrollView`（仓库 home / logs / 应用分流同款做法）。
 *    判据：`布局后宽度 ≥ 自身自然宽度` 且 `getEllipsisCount == 0` —— 注意**不能用**
 *    「有没有视图越出父容器」来判，被压扁时 `right` 始终在父容器内，会假阴性通过。
 *
 * ⚠️ `@GraphicsMode(NATIVE)` 不能省：LEGACY 模式字体度量是假的，自然宽不随字号/语言变化，
 * 第 3 条会被测不出来。`offsetDescendantRectToMyCoords` 只能从 `ViewGroup` 上调，
 * 所以根视图要按 `ViewGroup` 用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsCompactLayoutTest {

    // ─────────────────────────────────────────── 1. 四张选择卡只剩 radio + 名称

    @Test
    fun `四张选择卡只剩 radio 与名称`() {
        val root = render(fontScale = 1f, locale = Locale.SIMPLIFIED_CHINESE, widthDp = 393)
        val bad = mutableListOf<String>()

        for (id in SELECTION_CARDS) {
            val card = root.findViewById<MaterialCardView>(id)
            if (card == null) {
                bad += "${resName(root, id)} 缺失"
                continue
            }
            val labels = labelsIn(card).map { it.text.toString() }
            if (labels.size != 1) {
                bad += "${resName(root, id)} 里应只剩 1 个标题 TextView（radio + 名称）—— " +
                    "RadioButton 自身也是 TextView，所以这里只数非按钮类的文字。" +
                    "实际 ${labels.size} 个：$labels"
            } else if (labels.first().isBlank()) {
                bad += "${resName(root, id)} 里那个 TextView 是空的 —— 只剩卡片名了？"
            }
        }

        assertTrue(
            "选择卡瘦身契约不满足（Recommended 徽标 / 说明文案应已删除）：\n" + bad.joinToString("\n"),
            bad.isEmpty(),
        )
        writePagePng(root, "settings-compact-light")
        writePagePng(root, "settings-compact-narrow", widthDp = 320)
    }

    // ─────────────────────────────────────────── 2. UDP 网关不再有提示子卡

    @Test
    fun `UDP 网关小节不再有提示子卡`() {
        val root = render(fontScale = 1f, locale = Locale.SIMPLIFIED_CHINESE, widthDp = 393)
        val card = root.findViewById<MaterialCardView>(R.id.card_udpgw_section)
        assertNotNull("UDP 网关小节卡缺失", card)

        val container = card!!.getChildAt(0) as ViewGroup
        val shapes = (0 until container.childCount)
            .map { "child#$it=${container.getChildAt(it).javaClass.simpleName}" }
        assertTrue(
            "UDP 网关小节内应只剩 3 块（头部行 + 实现子卡 + 地址子卡），实际 ${container.childCount} 块：$shapes\n" +
                "原来的第 4 块「小提示」（Tips 标题 + 两条 • 说明）必须整块删掉。",
            container.childCount == 3,
        )

        val bullets = textViewsIn(card).map { it.text.toString() }.filter { it.trimStart().startsWith("•") }
        assertTrue("UDP 网关小节里仍有以「•」开头的提示行：$bullets", bullets.isEmpty())
    }

    // ─────────────────────────────────────────── 3. WebDAV 页脚两行 + 按钮不被压扁

    @Test
    fun `WebDAV 页脚拆成两行且按钮在窄屏不被压扁`() {
        val bad = mutableListOf<String>()

        for (widthDp in WIDTHS) {
            for (fontScale in FONT_SCALES) {
                val where = "${widthDp}dp @${fontScale}×"
                val root = render(fontScale, Locale.SIMPLIFIED_CHINESE, widthDp) { r ->
                    // 让「上次备份」带上真文案再布局，否则量到的是空串、挤压测不出来。
                    r.findViewById<TextView>(R.id.tv_webdav_last).text =
                        r.context.getString(CoreR.string.webdav_last_sync, "2026-09-20 12:00")
                }

                val last = root.findViewById<TextView>(R.id.tv_webdav_last)
                val backup = root.findViewById<MaterialButton>(R.id.btn_webdav_backup)
                val restore = root.findViewById<MaterialButton>(R.id.btn_webdav_restore)
                if (last == null || backup == null || restore == null) {
                    bad += "$where 页脚控件缺失（tv_webdav_last / btn_webdav_backup / btn_webdav_restore）"
                    continue
                }

                // 结构：页脚卡片内层 = 纵向容器，恰好 2 行；行 2 = HSV → 按钮行。
                val buttonsRow = backup.parent as? LinearLayout
                val hsv = buttonsRow?.parent as? HorizontalScrollView
                val row2 = hsv?.parent as? LinearLayout
                val footer = row2?.parent as? LinearLayout
                if (footer == null) {
                    bad += "$where 页脚层级不是「纵向容器 → 行2 → HorizontalScrollView → 按钮行」" +
                        "（实际 ${backup.parent?.javaClass?.simpleName} …）"
                    continue
                }
                if (footer.orientation != LinearLayout.VERTICAL || footer.childCount != 2) {
                    bad += "$where 页脚内层应为纵向且恰好 2 行，实际 " +
                        "orientation=${footer.orientation} childCount=${footer.childCount}"
                }
                if (hsv.isFillViewport) {
                    bad += "$where 行 2 的 HorizontalScrollView 不该开 fillViewport —— " +
                        "开了就会以 EXACTLY 测量按钮行，压扁原样回来"
                }

                // 几何：按钮行必须落在「上次备份」下面（真正分行）。
                val lastRect = boundsIn(root, last)
                val backupRect = boundsIn(root, backup)
                if (backupRect.top < lastRect.bottom) {
                    bad += "$where 没有分行：备份按钮 top=${backupRect.top} 不低于" +
                        "「上次备份」bottom=${lastRect.bottom}"
                }

                // 「上次备份」是带 `maxLines=2` 的说明文字，大字号下**允许折行** ——
                // 所以判据是「没被省略号截断」+（正常字号下）「单行放得下」；
                // 不能拿自然宽去比（第一次跑就是这条假失败：1.5× 下 360px < 482px，那是正常折行）。
                val lastEllipsis = ellipsisCount(last)
                if (lastEllipsis > 0) bad += "$where 「上次备份」文案被省略号截断（吃掉 $lastEllipsis 个字符）"
                if (fontScale == 1f && (last.layout?.lineCount ?: 0) != 1) {
                    bad += "$where 「上次备份」在正常字号下被挤成了 ${last.layout?.lineCount} 行 —— " +
                        "原版就是这里被压成「Last b / ackup:」两条竖排窄字"
                }

                for (b in listOf(backup, restore)) {
                    val name = resName(root, b.id)
                    val ellipsis = ellipsisCount(b)
                    val natural = naturalWidth(b)
                    if (b.width < natural) {
                        bad += "$where $name 被压扁：布局后 ${b.width}px < 自然宽 ${natural}px" +
                            "（行 2 外面那层 HorizontalScrollView 被去掉了？）"
                    }
                    if (ellipsis > 0) bad += "$where $name 文字被省略号截断（吃掉 $ellipsis 个字符）"
                }
            }
        }

        assertTrue("WebDAV 页脚分行/防挤压契约不满足：\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    // ─────────────────────────────────────────── 4. 深色预览

    @Test
    fun `深色预览`() {
        val root = render(fontScale = 1f, locale = Locale.SIMPLIFIED_CHINESE, widthDp = 393, night = true)
        writePagePng(root, "settings-compact-dark")
    }

    // ─────────────────────────────────────────── harness

    /**
     * inflate 真实 XML → `beforeLayout` 钩子 → 按目标宽度精确 measure/layout。
     *
     * `AppCompatActivity` 不能换裸 `Activity`：非 AppCompat 的 inflater 没有 view factory，
     * `app:tint` / `app:icon` 会被静默丢掉。`ContextThemeWrapper` 必须套 `Theme.Stun`，
     * 否则 `?attr/colorOnSurfaceVariant` 一类主题角色取不到值。
     *
     * 宽度直接由 `widthDp × density` 给出（不改 qualifier）：本页没有 `values-w*` 资源分叉，
     * 布局只关心 measure spec 给多宽。
     */
    private fun render(
        fontScale: Float,
        locale: Locale,
        widthDp: Int,
        night: Boolean = false,
        beforeLayout: (View) -> Unit = {},
    ): View {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        controller.setup()
        val config = Configuration(activity.resources.configuration).apply {
            this.fontScale = fontScale
            setLocale(locale)
            uiMode = if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val themed = ContextThemeWrapper(activity.createConfigurationContext(config), R.style.Theme_Stun)
        val root = LayoutInflater.from(activity).cloneInContext(themed).inflate(R.layout.activity_settings, null)
        activity.setContentView(root)
        beforeLayout(root)

        val dm = root.resources.displayMetrics
        val w = (widthDp * dm.density).toInt()
        val h = (PAGE_HEIGHT_DP * dm.density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, w, h)
        return root
    }

    /** 目标视图在根视图坐标系里的位置（跨了多个 LinearLayout，不能直接比 left/top）。 */
    private fun boundsIn(root: View, target: View): Rect {
        val r = Rect(0, 0, target.width, target.height)
        (root as ViewGroup).offsetDescendantRectToMyCoords(target, r)
        return r
    }

    /** 无约束量出的固有宽度 —— 布局后的宽度小于它，就说明被父容器压扁了。 */
    private fun naturalWidth(v: View): Int {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return v.measuredWidth
    }

    /** 所有行被省略号吃掉的字符总数。 */
    private fun ellipsisCount(tv: TextView): Int {
        val layout = tv.layout ?: return 0
        return (0 until layout.lineCount).sumOf { layout.getEllipsisCount(it) }
    }

    private fun textViewsIn(group: ViewGroup): List<TextView> = buildList {
        for (i in 0 until group.childCount) {
            when (val c = group.getChildAt(i)) {
                is TextView -> add(c)
                is ViewGroup -> addAll(textViewsIn(c))
            }
        }
    }

    /**
     * 卡片里的「纯文字」TextView。
     *
     * ⚠️ `RadioButton` / `Button` 都是 `TextView` 的子类，所以必须把 `CompoundButton`
     * 排除掉，否则每张选择卡都会多算一个 radio（第一次跑就是这么假失败的：
     * 实际 `["", "tun2proxy"]`，第 0 个是 RadioButton 本身、不是残留文案）。
     */
    private fun labelsIn(group: ViewGroup): List<TextView> =
        textViewsIn(group).filter { it !is CompoundButton }

    private fun resName(root: View, id: Int): String =
        runCatching { root.resources.getResourceEntryName(id) }.getOrDefault("id=$id")

    // ─────────────────────────────────────────── 出图（整页，只裁滚动内容）

    /**
     * 整页出图。宽度可以给得和页面本身不同：这里是把 `scroll_view` 的**内容**重新按
     * `widthDp` 精确测一遍，所以能从同一棵已 inflate 的树上同时出 393dp 和 320dp 两张图。
     */
    private fun writePagePng(root: View, name: String, widthDp: Int = 393) {
        // 页脚那行「上次备份」是运行时才灌文案的，预览里补上，否则出图只剩一个时钟图标。
        root.findViewById<TextView>(R.id.tv_webdav_last).text =
            root.context.getString(CoreR.string.webdav_last_sync, "2026-09-20 12:00")

        val scroll = root.findViewById<NestedScrollView>(R.id.scroll_view)
        val content = scroll.getChildAt(0) as View

        val density = root.resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        content.layout(0, 0, width, content.measuredHeight)

        // 分两层：内容画在透明层上，再合成到主题 surface 垫底上（自绘视图每帧 CLEAR 自己区域，
        // 直接画在垫底上会把垫底擦成透明，看图器补白底 ⇒ 只有深色预览露白）。
        val layer = Bitmap.createBitmap(width, content.height, Bitmap.Config.ARGB_8888)
        Canvas(layer).apply { content.draw(this) }
        val bitmap = Bitmap.createBitmap(width, content.height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(MaterialColors.getColor(root, attrId(root, "colorSurface"), Color.WHITE))
            drawBitmap(layer, 0f, 0f, null)
        }
        val dir = File("build/reports/ui-preview").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** 属性 id 得在本 app 的包名里查（非传递 R 类，与现有预览测试同一套取法）。 */
    private fun attrId(view: View, name: String): Int =
        view.resources.getIdentifier(name, "attr", view.context.packageName)

    private companion object {
        const val PAGE_HEIGHT_DP = 851

        /** 320dp = 现存最窄机型；393dp = 效果图那台；360dp = 主流。 */
        val WIDTHS = intArrayOf(320, 360, 393)

        /** 1.5× 是系统「大字体」档，最容易把按钮顶爆。 */
        val FONT_SCALES = floatArrayOf(1f, 1.5f)

        val SELECTION_CARDS = intArrayOf(
            R.id.card_udpgw_tun2proxy,
            R.id.card_udpgw_badvpn,
            R.id.card_filter_disallow,
            R.id.card_filter_allow,
        )
    }
}
