package app.fjj.stun.ui

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import app.fjj.stun.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设置页 / 节点编辑页的「底栏不许盖住页面」结构约定（inflate 真实 XML，不是重画的模型）。
 *
 * 背景：这两页的保存按钮原是 CoordinatorLayout 的悬浮叠层 —— 页面末尾能不能看全，
 * 全靠运行时往 scroll_view.paddingBottom 里写值。一旦这串写入有任何一程没跑到
 * （典型是"关键盘时 onEnd 被 ime.bottom > 0 的守卫跳过"，paddingBottom 停在半途的中间值），
 * 页面底部就被按钮压住，而且没有任何编译期信号。
 *
 * 现在改成布局流内的纵向三段式（顶栏 / 滚动区 / 底栏），并把留白降级成 layout 里的静态兜底值。
 * 这里锁的是**不变量**而非实现细节：无论以后谁再换布局容器，只要滚动区被底栏压住、
 * 或者兜底留白被改回 0，测试立刻红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
class BottomBarLayoutTest {

    private fun inflate(@LayoutRes layout: Int): View {
        // AppCompatActivity 不能换成裸 Activity：非 AppCompat 的 inflater 没有 view factory，
        // app:tint / app:icon 之类的属性会被静默丢掉。
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        val themed = ContextThemeWrapper(activity, R.style.Theme_Stun)
        val view = LayoutInflater.from(activity).cloneInContext(themed).inflate(layout, null)
        activity.setContentView(view)
        return view
    }

    /** 走一次真实测量 + 布局：断言的是渲染出来的几何，而不是 XML 里"看起来"写了什么。 */
    private fun measureAndLayout(root: View): View {
        val dm = root.resources.displayMetrics
        val w = View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
        root.measure(w, h)
        root.layout(0, 0, dm.widthPixels, dm.heightPixels)
        return root
    }

    private fun assertSaveBarSitsBelowTheScrollArea(@LayoutRes layout: Int) {
        val root = measureAndLayout(inflate(layout))
        val scroll = root.findViewById<NestedScrollView>(R.id.scroll_view)
        assertNotNull("页面必须有滚动容器", scroll)
        val bar = root.findViewById<View>(R.id.btn_save).parent as View
        val screenHeight = root.resources.displayMetrics.heightPixels

        // 先挡掉"根本没测量"的退化情况：测量失效时 scroll.bottom 与 bar.top 都是 0，
        // 下面的不重叠断言会变成永真的假绿。
        assertTrue("布局没被真正测量（滚动区高 ${scroll.height}）", scroll.height > screenHeight / 2)
        assertTrue("布局没被真正测量（底栏高 ${bar.height}）", bar.height > 0)

        assertTrue(
            "保存底栏必须与滚动区同级（在布局流内），不能是盖在内容上的叠层：" +
                "bar.parent=${bar.parent.javaClass.simpleName}, scroll.parent=${scroll.parent.javaClass.simpleName}",
            bar.parent === scroll.parent
        )
        assertTrue(
            "滚动区被底栏压住了：滚动区底边 ${scroll.bottom} 越过了底栏顶边 ${bar.top}",
            scroll.bottom <= bar.top
        )
        assertEquals("底栏必须落在页面最底部，否则下方会露出缝隙", screenHeight, bar.bottom)
    }

    private fun assertScrollAreaCarriesTheFallbackPadding(@LayoutRes layout: Int) {
        val root = inflate(layout)
        val scroll = root.findViewById<NestedScrollView>(R.id.scroll_view)
        assertNotNull("页面必须有滚动容器", scroll)

        val fallback = root.resources.getDimensionPixelSize(R.dimen.bottom_scroll_breathing_room)
        assertTrue("兜底留白不能是 0，否则等于没有兜底", fallback > 0)
        assertEquals(
            "静止态留白必须由 layout 里的兜底值给出：insets 回调一次都没跑到时也要是对的",
            fallback,
            scroll.paddingBottom
        )
        assertFalse(
            "键盘避让要把内容滚进这块留白里，clipToPadding 必须为 false",
            scroll.clipToPadding
        )
        assertEquals(
            "滚动区应吃掉顶栏与底栏之间的全部高度",
            1f,
            (scroll.layoutParams as LinearLayout.LayoutParams).weight,
            0f
        )
    }

    @Test
    fun settingsSaveBarSitsBelowTheScrollArea() =
        assertSaveBarSitsBelowTheScrollArea(R.layout.activity_settings)

    @Test
    fun settingsScrollAreaCarriesTheFallbackPadding() =
        assertScrollAreaCarriesTheFallbackPadding(R.layout.activity_settings)

    @Test
    fun profileEditSaveBarSitsBelowTheScrollArea() =
        assertSaveBarSitsBelowTheScrollArea(R.layout.activity_profile_edit)

    @Test
    fun profileEditScrollAreaCarriesTheFallbackPadding() =
        assertScrollAreaCarriesTheFallbackPadding(R.layout.activity_profile_edit)
}
