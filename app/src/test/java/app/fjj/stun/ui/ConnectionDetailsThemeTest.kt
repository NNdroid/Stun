package app.fjj.stun.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.fjj.stun.R
import app.fjj.stun.ui.view.ServerNoticeBox
import com.google.android.material.button.MaterialButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * 连接详情面板的「服务器提示」框与「断开」按钮必须**跟着主题走**。
 *
 * 这两个元素此前各卡了一对固定的粉/红：
 *
 * - 「服务器提示」的虚线框写死在 `bg_detail_notice.xml`（描边 `#D98C86` + 底色 `#FFF8F7`）；
 * - 「断开」按钮覆盖成 `?attr/colorErrorContainer`。
 *
 * 后一条看着"已经用了主题属性"，其实是假跟随 —— **M3 的 error 调色板在 DynamicColors 下
 * 是固定红，不随壁纸取色**。本 app 启用了 DynamicColors（`StunApp` / `BaseActivity`），
 * 所以主题换成蓝青色后 error 角色照样停在粉色。面板外的旁证：`ProfileAdapter.getDelayColor`
 * 拿 `colorError` 当"超时红"，跟主色毫无关系。
 *
 * 修法：断开按钮把三处 error 覆盖删掉，让 `Widget.Material3.Button.TonalButton` 用自己的默认
 * 配色（primaryContainer / onPrimaryContainer）；虚线框改由 `ServerNoticeBox` 在运行时装
 * （`<shape>` 的 solid/stroke 颜色不吃 `?attr/`，颜色资源也不能引用主题属性）。
 * 这几个断言就是回归闸门：谁把粉色改回去，这里立刻红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectionDetailsThemeTest {

    /**
     * 「是不是主色系」的每通道容差。Material 按钮的 tint 实际取到的角色色，
     * 比测试里直接解析 `?attr/` 得到的值差几个单位（实测蓝通道差 4），
     * 所以精确相等会误报；而写死回红/粉时差的是几十到一百多个单位，这个容差放不进任何一次回退。
     */
    private val colorTolerance = 8

    /** 两色各通道的最大差值。比 [Color.alpha] 那种单通道比较更耐抗锯齿。 */
    private fun channelDelta(a: Int, b: Int): Int {
        val diff = { x: Int, y: Int -> if (x > y) x - y else y - x }
        return maxOf(
            diff(Color.red(a), Color.red(b)),
            maxOf(diff(Color.green(a), Color.green(b)), diff(Color.blue(a), Color.blue(b))),
        )
    }

    /**
     * 「半透明 src 叠在不透明 dst 上」的实际像素值，按 Skia 的分量混合公式算：
     * 每个通道先算小数再四舍五入。
     *
     * 不用 `ColorUtils.compositeColors` —— 它走整数近似，实测 primary `#904D00` /
     * 底色 `#F2E5DE` / alpha 115 时它给 `0xFFC5A079`，Skia 实际画 `0xFFC6A07A`，
     * 差 1 个单位就一个像素都命中不了。
     */
    private fun blendOver(src: Int, alpha: Int, dst: Int): Int {
        val f = alpha / 255f
        val mix = { s: Int, d: Int -> (s * f + d * (1f - f)).roundToInt() }
        return (Color.alpha(dst) shl 24) or
            (mix(Color.red(src), Color.red(dst)) shl 16) or
            (mix(Color.green(src), Color.green(dst)) shl 8) or
            mix(Color.blue(src), Color.blue(dst))
    }

    @Test
    fun `断开按钮取主题主色容器而不是固定红的 error 角色`() {
        val themed = themedContext()
        val (sheet, bitmap) = paintedSheet(themed)

        val button = sheet.findViewById<MaterialButton>(R.id.btn_detail_disconnect)
        val painted = dominantColorIn(bitmap, sheet, button)

        // 画出来的像素必须就是按钮自己解析到的背景 tint —— 中间没插任何写死值。
        val tint = checkNotNull(button.backgroundTintList) { "按钮没有背景 tint" }.defaultColor
        assertEquals("按钮底色必须等于它自己解析到的背景 tint", tint, painted)
        // Material 的 tint 实际取到的角色色会比「直接解析 ?attr/」差几个单位
        // （实测 colorPrimaryContainer 0xFFFFDCC2 → tint 0xFFFFDCBE，蓝通道差 4），
        // 所以"是不是主色系"用通道容差比，不用精确相等。
        assertTrue(
            "按钮底色应落在主题的 colorPrimaryContainer 附近（实际 $painted vs " +
                themed.attrColor("colorPrimaryContainer") + "）",
            channelDelta(painted, themed.attrColor("colorPrimaryContainer")) <= colorTolerance,
        )
        // 回归闸门一：旧实现覆盖了 ?attr/colorErrorContainer。
        assertNotEquals("断开按钮不能再停在 error 角色上", themed.attrColor("colorErrorContainer"), painted)
        // 回归闸门二：字面值。0xFFFFDAD6 = md_theme_light_errorContainer，
        // 0xFFB3261E = HomeFragment 里 error 角色的兜底常量。两种写死法都排掉。
        assertNotEquals("断开按钮不能再是写死的粉底", 0xFFFFDAD6.toInt(), painted)
        assertNotEquals("断开按钮不能再是写死的 error 前景色", 0xFFB3261E.toInt(), painted)

        assertTrue(
            "按钮文字应落在主题的 colorOnPrimaryContainer 附近（实际 ${button.currentTextColor} vs " +
                themed.attrColor("colorOnPrimaryContainer") + "）",
            channelDelta(button.currentTextColor, themed.attrColor("colorOnPrimaryContainer")) <= colorTolerance,
        )
    }

    @Test
    fun `服务器提示框的底色与虚线都取自主题角色`() {
        val themed = themedContext()
        val sheet = inflateSheet(themed)
        val notice = sheet.findViewById<TextView>(R.id.tv_detail_ssh_notice)

        // 布局里不写 android:background：框只能来自代码。反过来断言一次，锁住这条约定，
        // 否则配色随时可能从某个写死色值的 drawable 里漏出来。
        assertNull("提示框不应在布局里声明背景（颜色必须运行时按主题装）", notice.background)

        ServerNoticeBox.applyTo(notice)
        val box = checkNotNull(notice.background as? GradientDrawable) {
            "提示框装上后应是 GradientDrawable"
        }

        val fill = checkNotNull(box.color) { "底色没有设置" }.defaultColor
        assertEquals(
            "底色必须是主题的 colorSurfaceContainerHigh",
            themed.attrColor("colorSurfaceContainerHigh"),
            fill,
        )
        assertNotEquals("底色不能再是写死的 #FFF8F7", 0xFFFFF8F7.toInt(), fill)
        assertEquals(
            "圆角沿用 corner_xs",
            themed.resources.getDimension(R.dimen.corner_xs),
            box.cornerRadius,
            0.5f,
        )

        val primary = themed.attrColor("colorPrimary")
        // 描边只能从像素上看（这个 SDK 的 GradientDrawable 没暴露描边相关的 getter）：
        // 把框单独画出来，确认画出的那条边是主题色、而且真的是虚线。
        val strokeCoverage = borderStrokeCoverage(box, primary, fill)
        assertTrue("描边没有画出来（框退化成无边界）", strokeCoverage.first > 4)
        val ratio = strokeCoverage.first.toDouble() / strokeCoverage.second
        assertTrue(
            "描边横向覆盖率 $ratio 太高：虚线框看起来像实线，就失去与普通字段值区分的作用了",
            ratio < 0.85,
        )
    }

    @Test
    fun `主色与固定红的 error 调色板确实是两个颜色`() {
        val themed = themedContext()
        // 先证明本测试的前提成立：如果主色和 error 同色，上面两条"不是 error"的断言就是空话。
        assertNotEquals(
            "colorPrimaryContainer 与 colorErrorContainer 必须不同色",
            themed.attrColor("colorErrorContainer"),
            themed.attrColor("colorPrimaryContainer"),
        )
        assertNotEquals(
            "colorPrimary 与 colorError 必须不同色",
            themed.attrColor("colorError"),
            themed.attrColor("colorPrimary"),
        )
    }

    // ── 夹具（与 ConnectionDockThemeTest / ConnectionDetailsLayoutPreviewTest 同口径） ──

    private fun themedContext(): Context {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        val config = Configuration(activity.resources.configuration).apply {
            fontScale = 1f
            uiMode = Configuration.UI_MODE_NIGHT_NO
        }
        val themed = ContextThemeWrapper(activity.createConfigurationContext(config), R.style.Theme_Stun)
        controller.setup()
        return themed
    }

    private fun inflateSheet(themed: Context): View =
        LayoutInflater.from(themed).inflate(R.layout.bottom_sheet_connection_details, null)

    /** 面板底部那颗按钮必须真的画出来才能取色：走真实 measure/layout/draw。 */
    private fun paintedSheet(themed: Context): Pair<ViewGroup, Bitmap> {
        val sheet = inflateSheet(themed) as ViewGroup
        val width = (393 * themed.resources.displayMetrics.density).toInt()
        sheet.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        sheet.layout(0, 0, width, sheet.measuredHeight)
        val bitmap = Bitmap.createBitmap(width, sheet.measuredHeight, Bitmap.Config.ARGB_8888)
        sheet.draw(Canvas(bitmap))
        return sheet to bitmap
    }

    /** 控件矩形里出现次数最多的**完全不透明**像素 = 它的底色（文字与图标只占少数字素）。 */
    private fun dominantColorIn(bitmap: Bitmap, root: ViewGroup, child: View): Int {
        val bounds = Rect(0, 0, child.width, child.height)
        root.offsetDescendantRectToMyCoords(child, bounds)
        val counts = HashMap<Int, Int>()
        for (y in bounds.top until bounds.bottom) {
            if (y !in 0 until bitmap.height) continue
            for (x in bounds.left until bounds.right) {
                if (x !in 0 until bitmap.width) continue
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) == 255) counts[pixel] = (counts[pixel] ?: 0) + 1
            }
        }
        assertTrue("断开按钮区域没有画出不透明像素 —— 按钮可能没被量到", counts.isNotEmpty())
        return counts.maxBy { it.value }.key
    }

    /**
     * 把虚线框单独画到位图上，统计顶边直边那一带里"颜色等于预期描边色"的横坐标个数，
     * 再除以那段直边的宽度 —— 得到描边的横向覆盖率。
     *
     * 预期描边色**不是主色本身**，而是主色降透明度后叠在底色上的合成结果（[blendOver]）：
     * 描边画在底色上面，像素是混出来的，直接跟主色比会一个像素都命中。
     *
     * 返回（命中像素数, 直边宽度）。实线约等于 1.0，4dp/3dp 的虚线约 0.57。
     */
    private fun borderStrokeCoverage(box: GradientDrawable, primary: Int, fill: Int): Pair<Int, Int> {
        val expected = blendOver(primary, ServerNoticeBox.STROKE_ALPHA, fill)
        val width = 200
        val height = 80
        box.setBounds(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.TRANSPARENT)
        box.draw(Canvas(bitmap))

        val straightLeft = box.cornerRadius.toInt()
        val straightRight = width - straightLeft
        val straightWidth = (straightRight - straightLeft).coerceAtLeast(1)

        val hit = HashSet<Int>()
        for (y in 0..2) {
            for (x in straightLeft until straightRight) {
                // ±2 吸收 Skia 抗锯齿在每节虚线两端造成的几个单位漂移；
                // 节内像素与 expected 精确相等，写死成红时差几十到上百个单位，这个容差兜不住。
                if (channelDelta(bitmap.getPixel(x, y), expected) <= 2) hit += x
            }
        }
        return hit.size to straightWidth
    }

    /**
     * 按**名字**解析主题属性，与 `ThemeColors.attrId` 同款（库属性经 aapt 合并后归属本 app 包名，
     * 必须用 packageName，`"android"` 只兜框架自带同名属性）。
     */
    private fun Context.attrColor(name: String): Int {
        val id = listOf(packageName, "android")
            .map { resources.getIdentifier(name, "attr", it) }
            .firstOrNull { it != 0 } ?: error("找不到主题属性: $name")
        val value = TypedValue()
        check(theme.resolveAttribute(id, value, true)) { "主题解析不到: $name" }
        return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
    }
}
