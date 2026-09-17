package app.fjj.stun.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import app.fjj.stun.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 连接详情面板里**地球卡片**的布局回归。
 *
 * 这里不画像素（视觉那部分归 `GlobeViewTest`），只盯三件"接错了不会崩、但用户一眼就能看出"的事：
 *
 * 1. 卡片确实在面板里 —— 分区标题与 `GlobeView` 都挂了上来；
 * 2. 地球走的是**方形自适应** —— XML 高度必须是 `wrap_content`。
 *    哪天有人给它补一句 `layout_height="220dp"`，球就会被压成椭圆，这条会红；
 * 3. 状态行默认收起 —— 地理库已就绪时不该露出「下载」入口占着位置。
 *
 * 外加两条**资源**断言：它们守的是 aapt 那一层很容易悄悄坏掉的东西（百分号转义、
 * 复数类别的回退），坏了在所有语言里都会显示成奇怪的文字。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w393dp-h851dp-xhdpi")
class ConnectionDetailsGlobeLayoutTest {

    @Test
    fun `面板里必须挂着地球卡片`() {
        val sheet = inflateSheet()
        assertNotNull("找不到地球视图：layout 里 `globe_topology` 丢了", sheet.globe())
        assertNotNull("找不到状态行：layout 里 `row_globe_status` 丢了", sheet.statusRow())
    }

    @Test
    fun `地球在真实面板里是方形_高度跟随宽度`() {
        val sheet = inflateSheet()
        val globe = sheet.globe()

        assertTrue("地球没量出宽度，说明它被别的布局挤没了", globe.width > 0)
        assertEquals(
            "地球不是方形 —— XML 高度很可能被写死成了固定值（应保持 wrap_content 走方形自适应）",
            globe.width,
            globe.height,
        )
    }

    @Test
    fun `地球高度必须来自 wrap_content 而不是固定值`() {
        val sheet = inflateSheet()
        val params = sheet.globe().layoutParams
        assertEquals(
            "地球的高度必须交给视图自己算（wrap_content），写死高度会把球压扁",
            ViewGroup.LayoutParams.WRAP_CONTENT,
            params.height,
        )
        assertEquals(
            "地球应占满卡片宽度（match_parent）",
            ViewGroup.LayoutParams.MATCH_PARENT,
            params.width,
        )
    }

    @Test
    fun `地理库就绪时状态行默认收起`() {
        val sheet = inflateSheet()
        assertEquals(
            "状态行默认必须是 GONE：库已下载时它会白占一行，还会露出「下载」按钮",
            View.GONE,
            sheet.statusRow().visibility,
        )
    }

    /**
     * 点选气泡必须挂在球的容器里且默认收起：它靠 `translationX/Y` 钉在选中落点上，
     * 如果有人把它从 `globe_container` 里挪出去（或改成常驻可见），定位坐标系就散了。
     */
    @Test
    fun `点选气泡挂在球容器里且默认收起`() {
        val sheet = inflateSheet()
        val bubble = requireNotNull(sheet.findViewById<View>(R.id.bubble_globe_detail)) {
            "bubble_globe_detail not found —— 点选气泡从布局里丢了"
        }
        assertEquals(
            "气泡必须默认 GONE（没有任何选中时它不该占地方）",
            View.GONE,
            bubble.visibility,
        )
        assertEquals(
            "气泡的父容器必须是 globe_container（translationX/Y 依赖这个坐标系）",
            R.id.globe_container,
            (bubble.parent as View).id,
        )
    }

    /**
     * 地球是位图，读屏念不出内容 —— 所以 XML 里必须带一个缺省描述，
     * 否则接线还没跑起来时它就是个"没有名字的节点"。
     */
    @Test
    @Config(qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
    fun `地球默认带本地化的无障碍描述`() {
        val sheet = inflateSheet()
        assertEquals(
            "连接拓扑地球",
            sheet.globe().contentDescription?.toString(),
        )
    }

    /**
     * 进度文案里的百分号。写成 `%1$d%` 会被 aapt 当成格式串报错、写成 `%%` 才是一个字面 `%`，
     * 而这条只能在**运行时**验证（编译期 aapt 只做校验，不保证取出来的就是用户看到的）。
     */
    @Test
    @Config(qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
    fun `下载进度文案里的百分号能正确取出`() {
        val context = themedContext()
        assertEquals(
            "正在下载地理库… 42%",
            context.getString(R.string.connection_globe_downloading_format, 42),
        )
    }

    /** 中文没有单复数之分，`one` 类别必须回退到 `other` —— 缺回退会抛 Resources$NotFoundException。 */
    @Test
    @Config(qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
    fun `复数资源在中文下按 other 回退`() {
        val context = themedContext()
        assertEquals("地球上 1 个节点", context.resources.getQuantityString(R.plurals.connection_globe_nodes, 1, 1))
        assertEquals("地球上 7 个节点", context.resources.getQuantityString(R.plurals.connection_globe_nodes, 7, 7))
        assertEquals("1 条活跃连接", context.resources.getQuantityString(R.plurals.connection_globe_connections, 1, 1))
    }

    /** 英文有单复数，`one` 与 `other` 得各自成立。 */
    @Test
    fun `复数资源在英文下区分单复数`() {
        val context = themedContext()
        assertEquals("1 active connection", context.resources.getQuantityString(R.plurals.connection_globe_connections, 1, 1))
        assertEquals("3 active connections", context.resources.getQuantityString(R.plurals.connection_globe_connections, 3, 3))
        assertNotEquals(
            "一条连接与三条连接的文案不该一模一样",
            context.resources.getQuantityString(R.plurals.connection_globe_connections, 1, 1),
            context.resources.getQuantityString(R.plurals.connection_globe_connections, 3, 3),
        )
    }

    // ────────────────────────────────────────────────────────── 工具

    /** 按屏幕宽 393dp 量一遍面板并落位：`GlobeView` 的方形自适应要经真实父布局才算数。 */
    private fun inflateSheet(): View {
        val context = themedContext()
        val sheet = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_connection_details, null)
        val width = (SHEET_WIDTH_DP * context.resources.displayMetrics.density).toInt()
        sheet.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(
                (VIEWPORT_HEIGHT_DP * context.resources.displayMetrics.density).toInt(),
                View.MeasureSpec.AT_MOST,
            ),
        )
        sheet.layout(0, 0, sheet.measuredWidth, sheet.measuredHeight)
        return sheet
    }

    private fun View.globe(): View =
        requireNotNull(findViewById(R.id.globe_topology)) { "globe_topology not found" }

    private fun View.statusRow(): View =
        requireNotNull(findViewById(R.id.row_globe_status)) { "row_globe_status not found" }

    /** [GlobeViewTest] 用的是同一套：真实 Material 主题，否则 `?attr/colorSurface*` 一类取不到值。 */
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

    private companion object {
        const val SHEET_WIDTH_DP = 393f

        /** 视口够高，让面板按内容完整展开（面板本身是可滚动的，这里不需要真的滚）。 */
        const val VIEWPORT_HEIGHT_DP = 2400f
    }
}
