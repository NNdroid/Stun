package app.fjj.stun.ui

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import app.fjj.stun.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 收尾阶段发现的**触摸目标回归**（inflate 真实 XML，不是重画的模型）。
 *
 * 背景：一批图标按钮当年是照着「视觉直径」写的 `40dp`，把**可点区域**也一起钉死在 40dp ——
 * 低于 Material 的最小触摸目标 48dp。手指在 40dp 上点不中，特别是单手、走路、或用拇指
 * 去够屏幕右上角那三个按钮（关闭 / 更多 / 星空模式）时。
 *
 * 统一后：可点框走 `@dimen/touch_target_min`（48dp），可见图标靠 `padding=space_3`（12dp）
 * 收回 24dp —— 图标视觉大小不变，热区变大。当按钮用的 TextView（`btn_usage_trend`、
 * `tv_row_home`）没有背景固有尺寸，所以改走 `minWidth`/`minHeight`。
 *
 * 这里断言的是**可点尺寸本身**（精确尺寸的按钮看 `layout_*`，`wrap_content` 的文本入口
 * 单独 measure 取固有尺寸），而不是渲染像素：一个字都没画，所以既不依赖字体度量、
 * 也不依赖父容器可见性（用量卡默认 GONE），跑得快也稳。
 * 谁把哪个按钮改回 40dp，这条会立刻红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TouchTargetLayoutTest {

    private fun inflate(@LayoutRes layout: Int): View {
        // AppCompatActivity 不能换成裸 Activity：非 AppCompat 的 inflater 没有 view factory，
        // app:tint / app:icon 之类的属性会被静默丢掉。
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        val themed = ContextThemeWrapper(activity, R.style.Theme_Stun)
        val view = LayoutInflater.from(activity).cloneInContext(themed).inflate(layout, null)
        activity.setContentView(view)
        return view
    }

    /**
     * 可点尺寸：`layout_*` 是精确值时直接取；`wrap_content` 轴**单独** measure 取固有尺寸。
     *
     * 为什么不整体 measure 根布局：用量卡 `card_subscription_usage` 默认 `GONE`，
     * 走整树测量的话里面两个按钮都会量到 0px，测试就变成假的。单独 measure 一个视图
     * 不受父容器可见性影响，而且天然把 `minWidth`/`minHeight`、padding、文字/图标尺寸
     * 都算进去了 —— 正是"手指能点到多大"。
     */
    private fun touchTargetSize(v: View): Int {
        val lp = v.layoutParams
        if (lp.width <= 0 || lp.height <= 0) {
            v.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
        }
        val w = if (lp.width > 0) lp.width else v.measuredWidth
        val h = if (lp.height > 0) lp.height else v.measuredHeight
        return minOf(w, h)
    }

    private fun assertTouchTargets(root: View, vararg ids: Int) {
        val min = root.resources.getDimensionPixelSize(R.dimen.touch_target_min)
        for (id in ids) {
            val v = requireNotNull(root.findViewById<View>(id)) {
                "找不到视图 id=$id —— 布局改名后这条断言会静默失效，所以这里直接失败"
            }
            val size = touchTargetSize(v)
            assertTrue(
                "view id=$id 的可点尺寸只有 ${size}px，低于最小触摸目标 ${min}px —— " +
                    "图标按钮请把 layout_width/Height 走 @dimen/touch_target_min " +
                    "（可见图标用 padding 收回原尺寸），文本按钮请加 minWidth/minHeight",
                size >= min,
            )
        }
    }

    @Test
    fun `连接详情面板的图标按钮都达到最小触摸目标`() {
        val sheet = inflate(R.layout.bottom_sheet_connection_details)
        assertTouchTargets(
            sheet,
            R.id.btn_detail_close,
            R.id.btn_detail_more,
            R.id.btn_detail_favorite,
            R.id.btn_topology_mode,
            R.id.btn_globe_more,
            R.id.btn_globe_reset,
        )
    }

    @Test
    fun `订阅行的图标按钮与主页文本入口都达到最小触摸目标`() {
        val row = inflate(R.layout.item_subscription_row)
        assertTouchTargets(row, R.id.btn_row_sync, R.id.btn_row_more, R.id.tv_row_home)
    }

    @Test
    fun `订阅头部与用量卡的按钮都达到最小触摸目标`() {
        val header = inflate(R.layout.item_subscription_header)
        assertTouchTargets(
            header,
            R.id.btn_subscription_help,
            R.id.btn_usage_copy,
            R.id.btn_usage_trend,
        )
    }

    @Test
    fun `主菜单侧栏的关闭按钮达到最小触摸目标`() {
        val sideSheet = inflate(R.layout.side_sheet_main)
        assertTouchTargets(sideSheet, R.id.btn_close_main_menu)
    }
}
