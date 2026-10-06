package app.fjj.stun.util

import android.app.Application
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [GridSpans.bind] 在真实 `RecyclerView` 上的接线：什么时候装网格、什么时候退回单列、什么时候**不许**动。
 *
 * 为什么要有这一层（[GridSpansTest] 已经覆盖了纯列数数学）：这里的价值全在**三条守卫**上，
 * 而它们每一条被写错都不会报错、只会表现成"列表怪怪的"：
 *
 * 1. `bind` 之后必须**立刻**有 LayoutManager —— 否则在第一次布局回调之前列表根本不排版；
 * 2. 只有**宽度**变化才重算 —— 高度变化（列表增删、内容变高）也换 LayoutManager 的话，
 *    每加一个节点列表就跳回顶部；
 * 3. 列数**没变**就不重建 —— 重建 LayoutManager 等于重置滚动位置。
 *
 * ⚠️ 放在 app 的测试源集而不是 core：core 没配默认 SDK，挂 `RobolectricTestRunner` 会抛
 * `IllegalArgumentException`（`DefaultSdkPicker`）。被测代码仍在 core，只是借用 app 的测试环境。
 *
 * ⚠️ 这里用 `rv.layout()` 直接驱动，不接窗口：`addOnLayoutChangeListener` 是 `View.layout()`
 * 内部在尺寸变化时派发的，所以手工 layout 一样能走到被测分支，而且不用等真实的渲染帧。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
class GridSpansBindingTest {

    // xhdpi ⇒ density = 2：core 的 node_grid_min_width=600dp ⇒ 1200px；node_grid_min_column=360dp ⇒ 720px。

    @Test
    fun `bind 之后立刻就有 LayoutManager，宽度未知时是单列`() {
        val rv = newRecyclerView()
        GridSpans.bind(rv)
        assertTrue(
            "bind 之后必须已经装了 LayoutManager —— 否则列表在第一次布局回调之前根本不排版",
            rv.layoutManager is LinearLayoutManager,
        )
        assertEquals(
            "宽度未知 ⇒ 单列：单列也统一用 GridLayoutManager(1)，见 GridSpans 的说明",
            1,
            (rv.layoutManager as GridLayoutManager).spanCount,
        )
    }

    @Test
    fun `宽度过了门槛就切成网格，列数按实际宽度算`() {
        val rv = newRecyclerView()
        GridSpans.bind(rv)
        layoutAt(rv, width = 2160) // 1080dp / 360dp = 3
        val lm = rv.layoutManager
        assertTrue("1080dp 该切网格了，实际是 ${lm?.javaClass?.simpleName}", lm is GridLayoutManager)
        assertEquals(3, (lm as GridLayoutManager).spanCount)
    }

    @Test
    fun `变窄回门槛以下要退回单列`() {
        val rv = newRecyclerView()
        GridSpans.bind(rv)
        layoutAt(rv, width = 2160)
        assertEquals(3, (rv.layoutManager as GridLayoutManager).spanCount)
        layoutAt(rv, width = 800) // 400dp < 600dp
        // 修复后单列不再换回 LinearLayoutManager 实例（布局中途换实例会打断布局状态机，
        // 症状是 adapter 有数据、列表永久空白 —— 见 GridSpans 说明），只把 spanCount 收回 1。
        val lm = rv.layoutManager
        assertTrue("缩回 400dp 仍是同一个 GridLayoutManager，实际是 ${lm?.javaClass?.simpleName}", lm is GridLayoutManager)
        assertEquals("缩回 400dp 必须退回单列", 1, (lm as GridLayoutManager).spanCount)
    }

    @Test
    fun `只改高度不许动 LayoutManager`() {
        val rv = newRecyclerView()
        GridSpans.bind(rv)
        layoutAt(rv, width = 2160, height = 800)
        val before = rv.layoutManager
        layoutAt(rv, width = 2160, height = 1600)
        assertSame("高度变化（列表增删/内容变高）不该换 LayoutManager，否则每加一个节点就跳回顶部", before, rv.layoutManager)
    }

    @Test
    fun `列数没变就不重建 LayoutManager`() {
        val rv = newRecyclerView()
        GridSpans.bind(rv)
        layoutAt(rv, width = 2160) // 3 列
        val before = rv.layoutManager
        layoutAt(rv, width = 2400) // 2400/720 = 3 列，列数没变
        assertSame("列数没变还重建 LayoutManager 会重置滚动位置", before, rv.layoutManager)
        assertEquals(3, (rv.layoutManager as GridLayoutManager).spanCount)
    }

    @Test
    fun `可用宽度要扣掉自己的 padding`() {
        val rv = newRecyclerView()
        rv.setPadding(360, 0, 360, 0) // 左右各 180dp
        GridSpans.bind(rv)
        layoutAt(rv, width = 2880) // 1440dp；扣 padding 后 1080dp ⇒ 3 列（不扣会算成 4 列）
        val lm = rv.layoutManager
        assertTrue(lm is GridLayoutManager)
        assertEquals(
            "列数必须按 RecyclerView 的**可见**宽度算：car 的列表外面有 12dp padding、XR 的列表只占屏宽 60%",
            3,
            (lm as GridLayoutManager).spanCount,
        )
    }

    @Test
    fun `超宽时列数封顶`() {
        val rv = newRecyclerView()
        GridSpans.bind(rv)
        layoutAt(rv, width = 10_000) // 5000dp / 360dp = 13 → 封顶
        assertEquals(GridSpans.DEFAULT_MAX_COLUMNS, (rv.layoutManager as GridLayoutManager).spanCount)
    }

    // ------------------------------------------------- harness

    private fun newRecyclerView(): RecyclerView {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        return RecyclerView(activity)
    }

    /** 直接驱动 `View.layout()`：尺寸一变，`addOnLayoutChangeListener` 就会被派发。 */
    private fun layoutAt(rv: RecyclerView, width: Int, height: Int = 800) {
        rv.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        rv.layout(0, 0, width, height)
    }
}
