package app.fjj.stun.ui

import android.app.Activity
import android.app.Application
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.fjj.stun.R
import app.fjj.stun.ui.view.ConnectionDockLayout
import app.fjj.stun.ui.view.ConnectionRateLabel
import app.fjj.stun.ui.view.TrafficBarChartView
import com.google.android.material.button.MaterialButton
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the production XML and native Canvas, not a separately drawn mock-up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectionDockLayoutTest {
    @Test fun referenceProportions() = render("dock-393", 393, 1f)
    @Test fun narrowScreenDoesNotClip() = render("dock-320", 320, 1f)
    @Test fun largeFontDoesNotClip() = render("dock-large-font", 393, 1.5f)
    @Test fun tabletDoesNotStretchContent() = render("dock-tablet", 800, 1f)
    @Test fun nightPalette() = render("dock-night", 393, 1f, night = true)
    @Test fun normalSmallPhone() = render("dock-360", 360, 1f)
    @Test fun disconnectedHasNoEmptyTrafficRow() = render("dock-disconnected", 393, 1f, connected = false)
    @Test fun exitIpAndFlagHaveTheirOwnRow() = render("dock-exit", 393, 1f,
        exitText = "203.0.113.8 · 🇸🇬 Singapore")
    @Test fun longExitAddressWrapsAtLargeFont() = render("dock-exit-large-font", 320, 1.5f,
        exitText = "2001:db8:1234:5678:abcd:1234:5678:9abc · 🇩🇪 Frankfurt am Main Germany")

    /**
     * 底栏出口行是**预留**的（可见性只跟连接状态，不跟探测结果）：所以占位文案和真实值
     * 必须渲染出一样高的底栏。否则探测结果回来那一刻底栏会跳一下 —— 这正是"很突兀"的根因。
     *
     * 注意 `tv_bottom_exit` 必须带 `fallbackLineSpacing="false"`：否则中文占位会被 CJK
     * 回退字体撑高约 4dp，而真实值（拉丁字母 + 国旗）不会，用例就会红。
     */
    @Test fun exitRowKeepsTheSameHeightWhenTheValueArrives() {
        render("dock-exit-pending", 393, 1f, exitText = "正在获取出口地址…")
        render("dock-exit", 393, 1f, exitText = "203.0.113.8 · 🇸🇬 Singapore")
        assertEquals(
            "Reserved exit row must render the same dock height for placeholder and value",
            dockHeights["dock-exit-pending"], dockHeights["dock-exit"]
        )
    }

    private companion object {
        /** render() 每次产出的底栏高度。JUnit4 每个用例都是新实例，字段存不住跨用例的值。 */
        val dockHeights = mutableMapOf<String, Int>()
    }

    private fun render(name: String, widthDp: Int, fontScale: Float, night: Boolean = false,
                       connected: Boolean = true, exitText: String? = null) {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        val config = Configuration(activity.resources.configuration).apply {
            this.fontScale = fontScale
            uiMode = if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val themed = android.view.ContextThemeWrapper(activity.createConfigurationContext(config), R.style.Theme_Stun)
        controller.setup()
        val home = LayoutInflater.from(themed).inflate(R.layout.fragment_home, null)
        val dock = home.findViewById<ConnectionDockLayout>(R.id.bottom_container)
        (dock.parent as ViewGroup).removeView(dock)
        val density = themed.resources.displayMetrics.density
        fun text(id: Int, value: String) { dock.findViewById<TextView>(id).text = value }
        text(R.id.tv_bottom_avatar_letter, "G")
        text(R.id.tv_status, "Guangzhou Home")
        text(R.id.tv_status_subtitle, if (connected) "56 ms" else "未连接")
        dock.findViewById<TextView>(R.id.tv_bottom_exit).apply {
            text = exitText.orEmpty()
            visibility = if (exitText == null) View.GONE else View.VISIBLE
        }
        dock.findViewById<TextView>(R.id.tv_up_rate).text = ConnectionRateLabel.format(1468006, true,
            themed.getColor(R.color.widget_up_accent), themed.getColor(R.color.widget_text_secondary))
        dock.findViewById<TextView>(R.id.tv_down_rate).text = ConnectionRateLabel.format(8598323, false,
            themed.getColor(R.color.widget_down_accent), themed.getColor(R.color.widget_text_secondary))
        dock.findViewById<View>(R.id.layout_traffic).visibility = if (connected) View.VISIBLE else View.GONE
        dock.findViewById<View>(R.id.status_dot).backgroundTintList = ColorStateList.valueOf(themed.getColor(
            if (connected) R.color.connection_state_online else R.color.connection_state_offline))
        dock.findViewById<TextView>(R.id.tv_status_subtitle).setTextColor(themed.getColor(
            if (connected) R.color.connection_state_online else R.color.connection_state_offline))
        dock.findViewById<MaterialButton>(R.id.fab_start_stop).apply {
            setIconResource(if (connected) R.drawable.ic_stop_rounded else R.drawable.ic_play)
            text = if (connected) "断开" else "连接"
        }
        // Fixed samples exist only in this test fixture, never in app traffic rendering.
        listOf(R.id.iv_compact_up_chart to R.color.widget_up_accent,
            R.id.iv_compact_down_chart to R.color.widget_down_accent).forEach { (id, color) ->
            dock.findViewById<TrafficBarChartView>(id).apply {
                slots = 8
                submitSamples(listOf(0, 1, 3, 5, 7, 6, 9, 12).map { it * 1024L }, themed.getColor(color))
            }
        }
        val width = (widthDp * density).toInt()
        dock.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        dock.layout(0, 0, width, dock.measuredHeight)
        dockHeights[name] = dock.height
        if (widthDp >= 360 && fontScale == 1f && connected) {
            assertEquals("The reference node name must stay on one line", 1, dock.findViewById<TextView>(R.id.tv_status).lineCount)
            assertEquals("Upload speed must stay on one line", 1, dock.findViewById<TextView>(R.id.tv_up_rate).lineCount)
            assertEquals("Download speed must stay on one line", 1, dock.findViewById<TextView>(R.id.tv_down_rate).lineCount)
        }
        assertTrue("Dock must have content height", dock.height >= (80 * density).toInt())
        val textIds = mutableListOf(R.id.tv_status, R.id.tv_status_subtitle, R.id.fab_start_stop)
        if (connected) textIds += listOf(R.id.tv_up_rate, R.id.tv_down_rate)
        if (exitText != null) textIds += R.id.tv_bottom_exit
        textIds.forEach { id ->
            val view = dock.findViewById<TextView>(id)
            assertTrue("Text must have positive width: $id", view.width > 0)
            val layout = view.layout
            assertNotNull("Text must be laid out: $id", layout)
            for (line in 0 until layout.lineCount) assertEquals("No ellipsis: $id", 0, layout.getEllipsisCount(line))
            assertTrue("Text height must fit: $id", layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
            val bounds = Rect(0, 0, view.width, view.height)
            dock.offsetDescendantRectToMyCoords(view, bounds)
            assertTrue("Text must remain within dock: $id", bounds.left >= 0 && bounds.right <= dock.width && bounds.top >= 0 && bounds.bottom <= dock.height)
        }
        if (!connected) assertTrue("Hidden traffic must not reserve a second row", dock.height < 110 * density)
        val bitmap = Bitmap.createBitmap(width, dock.height + (12 * density).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (night) Color.rgb(35, 32, 31) else Color.rgb(235, 231, 227))
        canvas.translate(0f, 12 * density)
        dock.draw(canvas)
        val directory = File("build/reports/ui-preview").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        controller.pause().stop().destroy()
    }
}
