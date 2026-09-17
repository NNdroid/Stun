package app.fjj.stun.ui

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import app.fjj.stun.R
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.ui.view.TrafficBarChartView
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 订阅面板的**渲染预览**：把三个 item 布局拼成整页、填上效果图那份数据，
 * 真的画成 PNG 落到 `app/build/reports/ui-preview/subscription-sheet-{light,dark}.png`。
 *
 * 为什么不直接截真机图：改一行 XML 就能重出一版，深色换一组 qualifiers 即可，
 * 而且改版式时不用手举手机就能和设计稿逐块对。
 *
 * 为什么不 inflate `bottom_sheet_subscription.xml`：它的内容是 RecyclerView，
 * 三块 item 由适配器运行时塞进去，静态 inflate 出来是一张空壳 —— 预览不出任何版式。
 * 所以这里按适配器的顺序把 header / row / actions 手动叠起来。
 *
 * 面板上绝大多数文案是运行时填的（`tools:text` 不渲染），所以必须自己填数据，
 * 否则预览出来是一片空卡片。出图不当断言用（图形差异太脆），只顺手卡一条硬约束：
 * **一行读完的用量数值不能被省略号吃掉** —— 它现在同时承担"已用多少 / 套餐多大"两个信息。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubscriptionSheetPreviewTest {

    @Test
    fun `浅色订阅面板预览`() {
        val sheet = buildSheet(night = false)
        fillSampleData(sheet)
        // 断言要读 TextView.layout，必须先走一遍 measure/layout（attach 本身不排版）。
        measureAndLayout(sheet)

        // 顺手一条硬约束：用量数值是一行读到底的信息，不能被省略号吃掉。
        val amount = sheet.findViewById<TextView>(R.id.tv_usage_amount)
        val layout = amount.layout ?: error("用量数值没有被排版")
        for (line in 0 until layout.lineCount) {
            assertEquals(
                "「${amount.text}」第 ${line + 1} 行被省略号截断了 —— 用量数值是一行读到底的信息",
                0,
                layout.getEllipsisCount(line),
            )
        }

        writePng(sheet, "subscription-sheet-light")
    }

    @Test
    fun `深色订阅面板预览`() {
        val sheet = buildSheet(night = true)
        fillSampleData(sheet)
        writePng(sheet, "subscription-sheet-dark")
    }

    // ────────────────────────────────────────────────────────── 拼装

    /**
     * 真实 attach 到窗口（复合 drawable / 主题属性都走 `onAttachedToWindow`，不挂会画不出来）。
     *
     * 两个必须遵守的点，少一个预览就会骗人：
     * 1. **必须用 AppCompatActivity**：图标只写了 `app:tint`，靠 AppCompat 的 view factory
     *    才生效；裸 Activity 的 inflater 没有 factory，图标会退回矢量里写死的白色，
     *    浅色底上就是白底白图标。
     * 2. **夜间要换配置上下文 + `cloneInContext`**：前者拿夜间资源，后者保住第 1 点的 factory。
     */
    private fun buildSheet(night: Boolean): View {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        controller.setup()
        val config = Configuration(activity.resources.configuration).apply {
            fontScale = 1f
            uiMode = if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val themed = ContextThemeWrapper(activity.createConfigurationContext(config), R.style.Theme_Stun)
        val inflater = LayoutInflater.from(activity).cloneInContext(themed)
        val res = themed.resources

        val host = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(themed, R.drawable.bg_bottom_sheet)
            val side = res.getDimensionPixelSize(R.dimen.space_6)
            setPadding(side, 0, side, res.getDimensionPixelSize(R.dimen.space_7))
        }
        // 传 root 是为了拿到 LinearLayout.LayoutParams（match_parent 宽度），attachToRoot=false。
        host.addView(inflater.inflate(R.layout.item_subscription_header, host, false))
        host.addView(inflater.inflate(R.layout.item_subscription_row, host, false))
        host.addView(inflater.inflate(R.layout.item_subscription_actions, host, false))
        activity.setContentView(host)
        return host
    }

    // ────────────────────────────────────────────────────────── 填充效果图那份数据

    private fun fillSampleData(sheet: View) {
        val ctx = sheet.context

        // ── 标题区
        sheet.findViewById<MaterialSwitch>(R.id.sw_auto_sync).isChecked = true

        // ── 流量卡
        sheet.findViewById<View>(R.id.card_subscription_usage).visibility = View.VISIBLE
        sheet.findViewById<View>(R.id.ll_usage_detail).visibility = View.VISIBLE
        sheet.findViewById<View>(R.id.tv_usage_empty).visibility = View.GONE
        sheet.findViewById<TextView>(R.id.tv_usage_source).apply {
            visibility = View.VISIBLE
            text = "机场 A"
        }
        sheet.findViewById<TextView>(R.id.tv_usage_amount).text =
            ctx.getString(CoreR.string.subscription_usage_used_of, "12.3 GB", "100 GB")
        sheet.findViewById<TextView>(R.id.tv_usage_percent).text =
            ctx.getString(CoreR.string.subscription_usage_percent, 12)
        sheet.findViewById<LinearProgressIndicator>(R.id.progress_usage).progress = 12
        sheet.findViewById<TextView>(R.id.tv_usage_today).text =
            ctx.getString(CoreR.string.subscription_usage_today, "1.2 GB")
        sheet.findViewById<TextView>(R.id.tv_usage_month).text =
            ctx.getString(CoreR.string.subscription_usage_month, "8.4 GB")
        sheet.findViewById<TextView>(R.id.tv_usage_expire).text =
            ctx.getString(CoreR.string.subscription_usage_expire, "2026-12-31", 107)

        sheet.findViewById<TextView>(R.id.btn_usage_trend).visibility = View.VISIBLE
        sheet.findViewById<View>(R.id.btn_usage_copy).visibility = View.VISIBLE
        sheet.findViewById<TrafficBarChartView>(R.id.iv_usage_trend).apply {
            slots = SAMPLE_HISTORY.size
            showAxes = true
            submitSamples(SAMPLE_HISTORY, ContextCompat.getColor(ctx, R.color.widget_down_accent))
            visibility = View.VISIBLE
        }

        // ── 订阅行（展开态）
        sheet.findViewById<TextView>(R.id.tv_row_title).text = "机场 A"
        sheet.findViewById<TextView>(R.id.tv_row_info).apply {
            visibility = View.VISIBLE
            text = listOf(
                ctx.getString(CoreR.string.subscription_nodes_count, 12),
                ctx.getString(CoreR.string.subscription_auto_interval, 24),
                ctx.getString(CoreR.string.subscription_synced_at, "14:30"),
            ).joinToString(" · ")
        }
        // 主页入口没有文字（只出链接图标），只需打开可见性 —— 图标来自 XML 的 drawableStart。
        sheet.findViewById<TextView>(R.id.tv_row_home).visibility = View.VISIBLE
        sheet.findViewById<View>(R.id.row_info_line).visibility = View.VISIBLE

        sheet.findViewById<View>(R.id.ll_editor).visibility = View.VISIBLE
        // 四个输入框刻意**留空**（只显示 hint），不往里面填值。
        //
        // 原因：TextInputLayout 的浮动标签是靠动画把 label 从"居中"推到"浮起"的，
        // 静态渲染推进不了动画帧（Robolectric 下用 idleFor 推 Choreographer 帧会挂死），
        // 于是 label 停在未浮起的位置、和已经 setText 进去的值叠在同一行 —— 预览图会
        // 显示成明显错位，但那只是渲染假象，真机上动画会跑到终态。
        // 留空正好也贴近设计稿的占位态，前置图标、分区标题、间距一样能核对。
        //
        // ⚠️ 预览图里还会看到"占位文字压住了前置图标"（比如锁形图标盖住 PIN 的第一个字符）。
        // 这**同样是静态渲染的假象**：折叠态 label 的起始 X 依赖一次排布后的补偿计算，手动
        // measure/layout 到不了那一步。真机上不会 —— 本项目另有约 20 处 TextInputLayout 是
        // `app:startIconDrawable` + hint 的同一组合（activity_settings.xml / activity_profile_edit.xml
        // 等），一直显示正常。判断"图标有没有接对"请看 SubscriptionSheetLayoutTest 的断言，别拿这张图下结论。
    }

    // ────────────────────────────────────────────────────────── 出图

    /** 面板按 BottomSheet 内容宽度排版，返回排版后的像素宽度。 */
    private fun measureAndLayout(sheet: View): Int {
        val density = sheet.resources.displayMetrics.density
        val width = (SHEET_WIDTH_DP * density).toInt()
        sheet.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        sheet.layout(0, 0, width, sheet.measuredHeight)
        return width
    }

    private fun writePng(sheet: View, name: String) {
        val width = measureAndLayout(sheet)

        // 先把内容画进独立图层，再合成到垫好底的成品上。
        //
        // 为什么不能"先垫底再直接 draw"：TrafficBarChartView 每帧会 CLEAR 自己的区域
        // （它是非 opaque 的装饰件，真机上靠窗口背景当底色）。CLEAR 不认图 ——
        // 画在同一张位图上时会把先前垫的底一起擦成透明，PNG 里就出现 alpha=0 的方块，
        // 任何看图器都会给它补一层白底，深色预览那一片就刺眼。
        val layer = Bitmap.createBitmap(width, sheet.height, Bitmap.Config.ARGB_8888)
        Canvas(layer).apply { sheet.draw(this) }
        val bitmap = Bitmap.createBitmap(width, sheet.height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            // 垫底层必须走主题取"卡片承载面"色，写死浅色同样会让深色预览露白。
            drawColor(cardBackdropColor(sheet))
            drawBitmap(layer, 0f, 0f, null)
        }
        assertFullyOpaque(bitmap)
        val dir = File("build/reports/ui-preview").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * 预览图必须处处不透明。透明像素一旦落到 PNG 里，看图器会补白底 ——
     * 浅色看不出来，深色就在透明处露出一片刺眼的白。出图前卡一道，
     * 防止以后有人把绘制顺序改回"先垫底再画内容"。
     */
    private fun assertFullyOpaque(bitmap: Bitmap) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val transparent = pixels.count { Color.alpha(it) == 0 }
        assertEquals(
            "预览图里有 $transparent 个全透明像素 —— 绘制顺序把垫底擦掉了，看图器会给它补白底",
            0,
            transparent,
        )
    }

    /**
     * 面板里卡片的承载面角色色。与 `HomeFragment.getThemeColor` 同一套取法：
     * 属性 id 得在本 app 的包名里查（非传递 R 类，`R.attr` 里没有库属性，
     * 资源合并后库属性挂在 app 包名下）。
     */
    private fun cardBackdropColor(sheet: View): Int {
        val resources = sheet.resources
        for (name in listOf("colorSurfaceContainerLow", "colorSurface")) {
            val id = resources.getIdentifier(name, "attr", sheet.context.packageName)
            if (id != 0) return MaterialColors.getColor(sheet, id, Color.WHITE)
        }
        return Color.WHITE
    }

    private companion object {
        const val SHEET_WIDTH_DP = 393f

        /** 15 根柱子，形状抄效果图：平时贴地，中途一个尖峰。 */
        val SAMPLE_HISTORY = listOf(0, 4, 18, 0, 62, 8, 0, 140, 26, 0, 40, 96, 12, 0, 320)
            .map { it * 1024L }
    }
}
