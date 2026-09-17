package app.fjj.stun.ui

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import app.fjj.stun.R
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.ui.view.TrafficBarChartView
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.CircularProgressIndicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * 带宽测速面板的**渲染预览 + 版式断言**：
 * 真的画成 PNG 落到 `app/build/reports/ui-preview/speed-sheet-{light,dark}.png`。
 *
 * 出图不当断言用（图形差异太脆），但下面几条是版式上会「看着不对」而单测不看就漏掉的硬约束：
 * 数值与单位的字号层次、数值不折行不被挤爆、「圆 + 数值 + 单位」在窄屏卡片里放得下、
 * 状态行不复述副标题、进度圈静止态不占位、开始测试按钮独占整行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpeedTestSheetPreviewTest {

    @Test
    fun `浅色测速面板预览`() {
        val sheet = buildSheet(night = false)

        // 静止态：进度圈必须先占位不显示，否则空转的圈会让「还没开始」看着像「卡住了」。
        assertEquals(
            "静止态的进度圈应该是 GONE",
            View.GONE,
            sheet.findViewById<CircularProgressIndicator>(R.id.progress_speed).visibility,
        )

        fillSampleData(sheet)
        val width = measureAndLayout(sheet)

        val down = sheet.findViewById<TextView>(R.id.tv_down_value)
        val downUnit = sheet.findViewById<TextView>(R.id.tv_down_unit)
        val upUnit = sheet.findViewById<TextView>(R.id.tv_up_unit)

        // ① 数值只写数字：单位若跟着进同一个 TextView，两者只能同字号，就出不来效果图那种层次
        assertFalse("数值里不该再带单位：${down.text}", down.text.contains("Mbps"))
        assertEquals("Mbps", downUnit.text.toString())
        assertEquals("Mbps", upUnit.text.toString())
        assertTrue(
            "单位必须比数值小一号（数值 ${down.textSize} / 单位 ${downUnit.textSize}）",
            downUnit.textSize < down.textSize,
        )

        // ② 数值不能被省略号吃掉 —— 它是这块面板唯一的结论
        val layout = down.layout ?: error("数值没有被排版")
        for (line in 0 until layout.lineCount) {
            assertEquals(0, layout.getEllipsisCount(line))
        }

        // ③ 长一点的值最容易把卡片挤爆（"123.4 Mbps" 比效果图里的 "8.4 Mbps" 宽一倍）
        down.text = "123.4"
        measureAndLayout(sheet)
        val card = sheet.findViewById<View>(R.id.metric_circle_down).parent as View
        val side = sheet.resources.getDimensionPixelSize(R.dimen.space_3)
        assertTrue(
            "「数值 + 单位」溢出了结果卡：单位右边界 ${downUnit.right}，卡片内容右边界 ${card.right - side}",
            downUnit.right <= card.right - side,
        )
        down.text = "8.4"
        measureAndLayout(sheet)

        // ④ 状态行不能说副标题那句 —— 同一句话在同一屏出现两次是这套改版专门要治的毛病
        val ctx = sheet.context
        assertNotEquals(
            "未连接提示词不能和副标题共用一条串",
            ctx.getString(CoreR.string.bandwidth_test_hint),
            ctx.getString(CoreR.string.bandwidth_test_need_vpn),
        )

        // ⑤ 左下角的圆形「历史」按钮已按决策去掉，开始测试按钮应该独占整行
        val sheetSide = sheet.resources.getDimensionPixelSize(R.dimen.space_5)
        val btn = sheet.findViewById<View>(R.id.btn_start_speed_test)
        assertEquals(
            "开始测试按钮没有占满整行（说明左边还挤着别的控件）",
            width - 2 * sheetSide,
            btn.width,
        )

        // ⑥ 头部徽标：圆形底 + 图形都要在，且图形用「次级容器上的前景色」而不是默认黑
        val badge = sheet.findViewById<FrameLayout>(R.id.badge_speed_test)
        val badgeIcon = badge.getChildAt(0) as ImageView
        assertNotNull("头部徽标缺少圆形底", badge.background)
        assertNotNull("头部徽标缺少图形", badgeIcon.drawable)
        assertEquals(
            "徽标图形没有按主题角色着色",
            MaterialColors.getColor(sheet, attrId(sheet, "colorOnSecondaryContainer")),
            badgeIcon.imageTintList?.defaultColor,
        )

        // ⑦ 下载/上行两个圆必须是实心强调色，而且要能区分开
        val downCircle = centerColorOf(sheet.findViewById(R.id.metric_circle_down))
        val upCircle = centerColorOf(sheet.findViewById(R.id.metric_circle_up))
        assertNear("下载圆不是下行绿", ContextCompat.getColor(ctx, R.color.widget_down_accent), downCircle)
        assertNear("上传圆不是上行蓝", ContextCompat.getColor(ctx, R.color.widget_up_accent), upCircle)

        writePng(sheet, "speed-sheet-light")
    }

    @Test
    fun `深色测速面板预览`() {
        val sheet = buildSheet(night = true)
        fillSampleData(sheet)
        measureAndLayout(sheet)

        // 语义色是刻意保留的字面量，切到深色底也该原样保留（不跟动态取色变）
        assertNear(
            "深色下下载圆被主题带跑色了",
            ContextCompat.getColor(sheet.context, R.color.widget_down_accent),
            centerColorOf(sheet.findViewById(R.id.metric_circle_down)),
        )

        // 箭头是画在实心圆上的白色矢量 —— 没有 app:tint 时 Robolectric 会退回矢量里
        // 写死的白，看不出问题；但真机上若 tint 丢失就会变成深色，压在绿/蓝圆上直接看不见。
        assertTrue("下行圆上的箭头不是白的（tint 可能没生效）", whitePixelCount(sheet.findViewById(R.id.metric_circle_down)) > 80)
        assertTrue("上行圆上的箭头不是白的（tint 可能没生效）", whitePixelCount(sheet.findViewById(R.id.metric_circle_up)) > 80)

        // 两个箭头必须是反的：下行箭头重心偏下、上行箭头重心偏上
        assertTrue("下行箭头画反了", arrowBias(sheet.findViewById(R.id.metric_circle_down)) > 0.5f)
        assertTrue("上行箭头画反了", arrowBias(sheet.findViewById(R.id.metric_circle_up)) < 0.5f)

        writePng(sheet, "speed-sheet-dark")
    }

    // ────────────────────────────────────────────────────────── 拼装

    /**
     * 真实 attach 到窗口。两个必须遵守的点（少一个预览就会骗人）：
     * 1. **必须用 AppCompatActivity**：图标只写了 `app:tint`，靠 AppCompat 的 view factory
     *    才生效；裸 Activity 的 inflater 没有 factory，图标会退回矢量里写死的白色。
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
        val sheet = inflater.inflate(R.layout.bottom_sheet_speed_test, null, false)
        activity.setContentView(sheet)
        return sheet
    }

    /** 面板上几乎所有文案都是运行时填的（`tools:text` 不渲染），不填出来就是一张空壳。 */
    private fun fillSampleData(sheet: View) {
        val ctx = sheet.context
        sheet.findViewById<TextView>(R.id.tv_down_value).text = "8.4"
        sheet.findViewById<TextView>(R.id.tv_up_value).text = "5.5"
        sheet.findViewById<TextView>(R.id.tv_speed_status).text =
            ctx.getString(CoreR.string.bandwidth_test_uploading)
        // ⚠️ 预览图里那个进度圈只是一个 3~4px 的小点，**不是画坏了**：
        // CircularProgressIndicator 的不确定动画是靠帧推进的弧长扫描，静态渲染停在 t=0，
        // 弧长≈0 就只剩个点。真机上它会转起来（订阅行里的那个是同一个组件，一直正常）。
        // 别为了"让预览好看"把它改成 determinate —— 那就不是在预览真实界面了。
        sheet.findViewById<CircularProgressIndicator>(R.id.progress_speed).visibility = View.VISIBLE
        sheet.findViewById<TrafficBarChartView>(R.id.chart_speed).apply {
            slots = 30
            showAxes = true
            unitLabel = ctx.getString(CoreR.string.bandwidth_unit_mbps)
            unitDivisor = 1_000_000.0
            submitSamples(SAMPLE_BARS, ContextCompat.getColor(ctx, R.color.widget_up_accent))
        }
    }

    // ────────────────────────────────────────────────────────── 出图

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

        // 先把内容画进独立图层，再合成到垫好底的成品上：弹层根节点的圆角是透明的，
        // 直接在同一张位图上垫底会被内容里的透明区域混掉。
        val layer = Bitmap.createBitmap(width, sheet.height, Bitmap.Config.ARGB_8888)
        Canvas(layer).apply { sheet.draw(this) }
        val bitmap = Bitmap.createBitmap(width, sheet.height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(backdropColor(sheet))
            drawBitmap(layer, 0f, 0f, null)
        }
        assertFullyOpaque(bitmap)
        val dir = File("build/reports/ui-preview").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * 预览图必须处处不透明。透明像素一旦落到 PNG 里，看图器会补白底 ——
     * 浅色看不出来，深色就在透明处露出一片刺眼的白。
     */
    private fun assertFullyOpaque(bitmap: Bitmap) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val transparent = pixels.count { Color.alpha(it) == 0 }
        assertEquals("预览图里有 $transparent 个全透明像素", 0, transparent)
    }

    /** 弹层底的承载面色（bg_main_menu_sheet 用的就是它），圆角处要靠它垫底。 */
    private fun backdropColor(sheet: View): Int {
        for (name in listOf("colorSurfaceContainer", "colorSurface")) {
            val id = attrId(sheet, name)
            if (id != 0) return MaterialColors.getColor(sheet, id, Color.WHITE)
        }
        return Color.WHITE
    }

    // ────────────────────────────────────────────────────────── 取色 / 取形

    /**
     * 采样结果卡里的实心圆。**不能取正中心** —— 正中央是那个白色箭头。
     * 横向 18% 处落在圆内（半径 50%）、又在箭头左边界之外（箭头只占中间那 50%）。
     */
    private fun centerColorOf(view: View): Int {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply { view.draw(this) }
        return bitmap.getPixel((view.width * 0.18f).toInt(), view.height / 2)
    }

    private fun whitePixelCount(circle: View): Int {
        val bitmap = Bitmap.createBitmap(circle.width, circle.height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply { circle.draw(this) }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count {
            Color.alpha(it) > 200 && Color.red(it) > 230 && Color.green(it) > 230 && Color.blue(it) > 230
        }
    }

    /**
     * 圆内图形（箭头）的垂直重心，归一化到 0..1。
     * 下行箭头的三角头压在下方，重心 > 0.5；上行箭头反之。
     * 比「两个图标逐像素比对」稳 —— 镜像图形做粗掩码匹配会撞车。
     */
    private fun arrowBias(circle: View): Float {
        val arrow = (circle as FrameLayout).getChildAt(0)
        val bitmap = Bitmap.createBitmap(arrow.width, arrow.height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply { arrow.draw(this) }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var mass = 0.0
        var weighted = 0.0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val alpha = Color.alpha(pixels[y * bitmap.width + x])
                if (alpha > 40) {
                    mass += alpha
                    weighted += alpha * y
                }
            }
        }
        assertTrue("箭头是空的，没画出任何像素", mass > 0.0)
        return (weighted / mass / bitmap.height).toFloat()
    }

    private fun assertNear(message: String, expected: Int, actual: Int) {
        val distance = abs(Color.red(expected) - Color.red(actual)) +
            abs(Color.green(expected) - Color.green(actual)) +
            abs(Color.blue(expected) - Color.blue(actual))
        assertTrue("$message（期望 ${hex(expected)}，实际 ${hex(actual)}）", distance <= 12)
    }

    private fun hex(color: Int) = String.format("#%06X", color and 0xFFFFFF)

    /**
     * 属性 id 得在本 app 的包名里查：非传递 R 类，`R.attr` 里没有库属性，
     * 资源合并后库属性挂在 app 包名下（与 HomeFragment.getThemeColor 同一套取法）。
     */
    private fun attrId(view: View, name: String): Int =
        view.resources.getIdentifier(name, "attr", view.context.packageName)

    private companion object {
        const val SHEET_WIDTH_DP = 393f

        /** 30 个槽位铺 24 个样本，形状抄效果图：前面一片贴地，后半段起峰、峰值约 17 Mbps。 */
        val SAMPLE_BARS = listOf(
            0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
            1_200_000L, 0L, 11_000_000L, 9_000_000L, 13_500_000L, 17_000_000L,
            0L, 2_000_000L, 3_500_000L, 9_500_000L, 10_500_000L,
            0L, 5_500_000L, 0L, 0L, 0L,
        )
    }
}
