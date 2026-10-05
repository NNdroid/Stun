package app.fjj.stun.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.fjj.stun.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 连接详情面板「出口地址」行的双栈版式回归。
 *
 * 出口值现在可能是**两行**（IPv4 与 IPv6 各一行）。值列是 `layout_weight=1` 的横向行里的一格，
 * 右边没有第二列可以借宽度，所以这里量的是"两行会不会被裁掉 / 会不会把行挤爆"：
 *
 * 1. 填了双栈文本后，`tv_detail_exit` 的 `lineCount` 确实是 2（没被 `singleLine` 之类的默认值压回一行）；
 * 2. 两行高度仍被它的 `wrap_content` 接住 —— 行高要 ≥ 值列高度，不能出现文字溢出到下一行；
 * 3. 单栈文本时行高回到一行，别给用户留一个空着的第二行（那看着像"缺了个地址"）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w393dp-h851dp-xhdpi")
class ConnectionDetailsExitRowLayoutTest {

    @Test
    fun `双栈时值列显示两行`() {
        val sheet = sheetWithExitText("203.0.113.8 · 🇸🇬 Singapore\n2001:db8::8")
        val exitValue = requireNotNull(sheet.exitValue()) { "找不到出口地址的值 TextView（tv_detail_exit）" }
        assertEquals("双栈文本应渲染成两行", 2, exitValue.lineCount)
    }

    @Test
    fun `两行文字完整落在行内不溢出`() {
        val sheet = sheetWithExitText("203.0.113.8 · 🇸🇬 Singapore\n2001:db8::8")
        val exitValue = requireNotNull(sheet.exitValue())
        val row = exitValue.parent as ViewGroup
        val density = sheet.resources.displayMetrics.density

        assertTrue(
            "值列高度应容得下两行（实测 ${exitValue.height}px），否则第二行会被裁掉",
            exitValue.height >= 2 * exitValue.lineHeight * 0.9f,
        )
        assertTrue(
            "信息行高应 ≥ 值列高度（行 ${row.height}px < 值列 ${exitValue.height}px），否则文字会压到下一行",
            row.height + 1 >= exitValue.height,
        )
        // 行高不能失控地撑高：两行文本比单行只该多一点，不该把整行顶成一大块留白。
        val single = sheetWithExitText("203.0.113.8 · 🇸🇬 Singapore")
            .exitValue()!!.let { it.parent as ViewGroup }.height
        assertTrue(
            "两行与一行的行高差应小于一行文字高（差 ${row.height - single}px）",
            row.height - single < (exitValue.lineHeight * density).toInt() + 2,
        )
    }

    @Test
    fun `单栈时只有一行_不留下空行`() {
        val sheet = sheetWithExitText("203.0.113.8 · 🇸🇬 Singapore")
        val exitValue = requireNotNull(sheet.exitValue())
        assertEquals("单栈文本应只有一行", 1, exitValue.lineCount)
    }

    @Test
    fun `出口行仍带前置图标与标签`() {
        // 反事实：加双栈时若顺手重排了这一行，标签或图标列可能掉。
        val sheet = sheetWithExitText("203.0.113.8\n2001:db8::8")
        val exitValue = requireNotNull(sheet.exitValue())
        val row = exitValue.parent as ViewGroup
        assertTrue(
            "行首应是装饰性图标，实际是 ${row.getChildAt(0).javaClass.simpleName}",
            row.getChildAt(0) is android.widget.ImageView,
        )
        val label = row.getChildAt(1) as TextView
        assertEquals(themedContext().getString(R.string.connection_exit), label.text.toString())
    }

    // ───────────────────────────────────────────── helpers

    private fun sheetWithExitText(text: String): View {
        val sheet = LayoutInflater.from(themedContext())
            .inflate(R.layout.bottom_sheet_connection_details, null)
        val exitValue = requireNotNull(sheet.exitValue()) { "找不到出口地址的值 TextView（tv_detail_exit）" }
        exitValue.text = text
        relayout(sheet)
        return sheet
    }

    private fun View.exitValue(): TextView? = descendants().firstOrNull { it.id == R.id.tv_detail_exit } as? TextView

    private fun relayout(sheet: View) {
        val metrics = sheet.resources.displayMetrics
        sheet.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((2600 * metrics.density).toInt(), View.MeasureSpec.AT_MOST),
        )
        sheet.layout(0, 0, sheet.measuredWidth, sheet.measuredHeight)
    }

    private fun View.descendants(): List<View> {
        val out = ArrayList<View>()
        fun walk(view: View) {
            out.add(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(this)
        return out
    }

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
}
