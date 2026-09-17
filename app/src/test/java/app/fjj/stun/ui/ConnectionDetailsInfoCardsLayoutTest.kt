package app.fjj.stun.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.fjj.stun.R
import com.google.android.material.card.MaterialCardView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 连接详情面板里**两张信息卡**（节点详情 / 辅助信息）的版式回归。
 *
 * 这里守的是"用户一眼就能看出"的结构，而不是像素：
 *
 * 1. 标题**收进了卡片**（卡片头 = 图标 + 标题 + 副标题）。从前标题浮在卡片外，
 *    一旦有人把卡片头删掉、忘了加回分区标题，界面上就再也没有「节点详情」四个字；
 * 2. 每行都有**前置图标**。图标列一旦少一个，那一行的标签就会往左跳一截，整列看着就是断的；
 * 3. 服务器提示是**虚线框**里的外部文本（服务端可控），不是普通字段值；
 * 4. 拓扑卡的身份头与右下角复位按钮确实挂上来了。
 *
 * 图标本身是装饰（内容已由标签与值表达），所以还额外断言它**对读屏隐藏** ——
 * 否则 TalkBack 会在每行前面多念一个没有名字的图形。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w393dp-h851dp-xhdpi")
class ConnectionDetailsInfoCardsLayoutTest {

    @Test
    fun `两张信息卡都带卡头_标题与副标题各一份`() {
        val sheet = inflateSheet()
        val nodeCard = sheet.cardWithHeader("Node details")
        val auxCard = sheet.cardWithHeader("Additional information")

        for ((card, subtitle) in listOf(
            nodeCard to getString(R.string.connection_node_details_subtitle),
            auxCard to getString(R.string.connection_auxiliary_info_subtitle),
        )) {
            val texts = card.descendants().filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue("卡头缺少副标题「$subtitle」：卡头应包含标题 + 副标题两行", texts.contains(subtitle))
        }
    }

    /**
     * 标题只能出现一次。删掉卡片外的分区标题、把标题挪进卡头，正是这次改版的核心动作；
     * 若哪天有人"顺手"把分区标题加回来，界面上就会出现两个「节点详情」。
     */
    @Test
    fun `分区标题不再重复出现`() {
        val sheet = inflateSheet()
        val title = getString(R.string.connection_node_details)
        val count = sheet.descendants().filterIsInstance<TextView>().count { it.text.toString() == title }
        assertEquals("「$title」应只出现一次（卡头里），发现 $count 处 —— 卡片外的分区标题可能被加回来了", 1, count)
    }

    @Test
    fun `每张信息卡的每一行都有前置图标`() {
        val sheet = inflateSheet()
        for (title in listOf("Node details", "Additional information")) {
            val rows = sheet.cardWithHeader(title).infoRows()
            assertTrue("「$title」卡里一行信息行都没找到 —— 行结构可能被改了", rows.size >= 5)
            rows.forEachIndexed { index, row ->
                val icon = row.getChildAt(0)
                assertTrue(
                    "「$title」第 ${index + 1} 行的行首不是 ImageView —— 图标列会断在这里",
                    icon is ImageView,
                )
                assertNotNull("「$title」第 ${index + 1} 行的图标没有圆形底色（应为 bg_icon_circle）", icon.background)
                assertEquals(
                    "「$title」第 ${index + 1} 行的图标没有被读屏忽略 —— 装饰性图标不该被念出来",
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                    icon.importantForAccessibility,
                )
            }
        }
    }

    /** 图标行比文字行高一点：32dp 的圆形色块要塞进 46dp 的最小行高里还得留出上下呼吸。 */
    @Test
    fun `图标行的高度容得下圆形图标`() {
        val sheet = inflateSheet()
        val row = sheet.cardWithHeader("Node details").infoRows().first()
        val density = sheet.resources.displayMetrics.density
        val icon = row.getChildAt(0)
        assertTrue("行首图标的宽高应一致（圆形色块）", icon.layoutParams.width == icon.layoutParams.height)
        assertTrue(
            "行最小高度应 ≥ 46dp 才装得下 32dp 的图标圈",
            row.minimumHeight >= (46 * density).toInt() - 1,
        )
    }

    /**
     * 服务器提示（MOTD）是**服务端可控文本**，可能与普通字段值混淆 —— 它必须待在虚线框里。
     * 断言的是"有背景 + 居中"，不是背景具体是什么形状：形状归 drawable，这里只守接线没丢。
     */
    @Test
    fun `服务器提示值带虚线框背景且居中`() {
        val sheet = inflateSheet()
        val notice = sheet.findViewById<TextView>(R.id.tv_detail_ssh_notice)
        assertNotNull("找不到 tv_detail_ssh_notice：服务器提示行丢了", notice)
        assertNotNull("服务器提示没有背景 —— 虚线框是它与普通字段值的唯一区分", notice.background)
        assertEquals(
            "服务器提示应水平居中（与效果图一致）",
            Gravity.CENTER_HORIZONTAL,
            notice.gravity and Gravity.HORIZONTAL_GRAVITY_MASK,
        )
    }

    @Test
    fun `拓扑卡的身份头字段都挂上来了`() {
        val sheet = inflateSheet()
        for (id in listOf(
            R.id.tv_globe_avatar_letter,
            R.id.tv_globe_name,
            R.id.tv_globe_server,
            R.id.tv_globe_protocol_chip,
            R.id.tv_globe_magic_chip,
            R.id.tv_globe_latency,
            R.id.tv_globe_last,
            R.id.tv_globe_traffic,
            R.id.btn_globe_more,
        )) {
            assertNotNull("拓扑卡身份头缺少视图 id=$id", sheet.findViewById<View>(id))
        }
    }

    /** 复位按钮挂在球容器里（`bottom|end` 是相对球那块的方形区域定位的），且默认压暗。 */
    @Test
    fun `复位视角按钮在球容器右下角且默认压暗`() {
        val sheet = inflateSheet()
        val button = requireNotNull(sheet.findViewById<View>(R.id.btn_globe_reset)) {
            "btn_globe_reset not found —— 复位视角按钮从布局里丢了"
        }
        assertSame(
            "复位按钮的父容器必须是 globe_container（它按球的方形区域定位）",
            sheet.findViewById<View>(R.id.globe_container),
            button.parent,
        )
        assertTrue("复位按钮默认应压暗（默认倍率下没有可复位的东西）", button.alpha < 1f)
    }

    /** 短标签带 `%1$s`：运行时能取出，且不是原样吐出格式串。 */
    @Test
    @Config(qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
    fun `上次连接的短标签能正确格式化`() {
        val context = themedContext()
        assertEquals("上次：3 分钟前", context.getString(R.string.connection_last_connected_short, "3 分钟前"))
    }

    /** 副标题来自本地化资源（不是写死在布局里的），中文下要取到中文。 */
    @Test
    @Config(qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
    fun `卡片头副标题本地化`() {
        val context = themedContext()
        assertEquals("服务器连接与基本信息", context.getString(R.string.connection_node_details_subtitle))
        assertEquals("其他技术信息与服务器提示", context.getString(R.string.connection_auxiliary_info_subtitle))
    }

    /**
     * 身份头里的服务器地址是"连的是哪台机器"的唯一凭据（节点名会重、地址不会），
     * 窄屏上被省略号吃掉就等于这块信息没了。`maxLines=2` 必须真的够用。
     *
     * ⚠️ 这条**必须 NATIVE**：LEGACY 下字体度量是假的（measureText 恒给出 7dp），永远不折行，
     * 断言会变成"永远通过"。
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "zh-rCN-w320dp-h851dp-xhdpi")
    fun `窄屏下身份头的服务器地址不被省略`() {
        val sheet = inflateSheet()
        val server = sheet.findViewById<TextView>(R.id.tv_globe_server)
        server.text = "43.138.143.71:25000-26000 → 43.138.143.71:22"
        relayout(sheet)

        val layout = server.layout
        assertNotNull("服务器地址没有被排版 —— 视图可能根本没被量到", layout)
        for (line in 0 until layout.lineCount) {
            assertEquals(
                "服务器地址第 ${line + 1} 行被省略号截断 —— maxLines 不够放",
                0,
                layout.getEllipsisCount(line),
            )
        }
    }

    // ────────────────────────────────────────────────────────── 工具

    private fun inflateSheet(): View = sheetView(themedContext())

    private fun sheetView(context: Context): View {
        val sheet = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_connection_details, null)
        relayout(sheet)
        return sheet
    }

    /** 按整屏宽量一遍并落位。改了文案之后再调一次 —— 文案是运行时填的，量完才知道会不会折行。 */
    private fun relayout(sheet: View) {
        val metrics = sheet.resources.displayMetrics
        sheet.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(
                (VIEWPORT_HEIGHT_DP * metrics.density).toInt(),
                View.MeasureSpec.AT_MOST,
            ),
        )
        sheet.layout(0, 0, sheet.measuredWidth, sheet.measuredHeight)
    }

    /** 以「卡头标题」这一串文案定位卡片 —— 行本身没有 id，靠内容找比靠下标稳。 */
    private fun View.cardWithHeader(title: String): MaterialCardView =
        requireNotNull(
            descendants().filterIsInstance<MaterialCardView>().firstOrNull { card ->
                card.descendants().any { it is TextView && it.text.toString() == title }
            },
        ) { "找不到卡头标题为「$title」的卡片" }

    /** 卡内的信息行：首个孩子是图标、且后跟标签与值两个 TextView 的横向 LinearLayout。 */
    private fun MaterialCardView.infoRows(): List<LinearLayout> =
        descendants().filterIsInstance<LinearLayout>().filter { row ->
            row.orientation == LinearLayout.HORIZONTAL &&
                row.childCount >= 3 &&
                row.getChildAt(0) is ImageView &&
                row.getChildAt(1) is TextView &&
                row.getChildAt(2) is TextView
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

    private fun getString(resId: Int): String = themedContext().getString(resId)

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
        /** 视口够高，让面板按内容完整展开（面板本身可滚动，这里不需要真的滚）。 */
        const val VIEWPORT_HEIGHT_DP = 2600f
    }
}
