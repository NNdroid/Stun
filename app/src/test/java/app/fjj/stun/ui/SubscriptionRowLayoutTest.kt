package app.fjj.stun.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.fjj.stun.R
import app.fjj.stun.core.R as CoreR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 订阅行信息区的结构约定（inflate 真实 XML，不是重画的模型）。
 *
 * 订阅主页（响应头 `profile-web-page-url`）的历史形态：
 * ① 标题行里与删除按钮并排的 40dp 无文字地球图标 —— 既容易被忽略，又紧挨破坏性操作；
 * ② 下移到信息行尾部做成"地球 + 域名"的文字链接 —— 域名对用户没有信息量。
 * 现在只留一个**链接图标**：`tv_row_home` 是纯图标入口，不写任何文字、不显域名。
 * 这里锁住"信息行内 + 只有图标 + 与删除不同容器"，避免被改回去。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubscriptionRowLayoutTest {

    private fun inflate(): View {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val themed = android.view.ContextThemeWrapper(controller.get(), R.style.Theme_Stun)
        controller.setup()
        val row = LayoutInflater.from(themed).inflate(R.layout.item_subscription_row, null)
        // 真挂到窗口上：TextView 的复合 drawable（drawableStart / drawableTint）要到
        // onAttachedToWindow 之后才解析，不 attach 的话预览图里图标会凭空消失。
        controller.get().setContentView(row)
        return row
    }

    @Test
    fun homeEntryIsAnIconOnlyLinkInsideTheInfoLine() {
        val row = inflate()
        val homeView: View? = row.findViewById(R.id.tv_row_home)
        assertNotNull("订阅主页入口必须存在", homeView)
        assertTrue("主页入口应是可点的 TextView，而不是与删除并排的图标按钮", homeView is TextView)
        assertEquals(
            "主页入口必须落在信息行容器内",
            R.id.row_info_line,
            (homeView!!.parent as View).id
        )
        val home = homeView as TextView
        assertNotNull(
            "主页入口必须带链接图标（drawableStart）",
            home.compoundDrawablesRelative.getOrNull(0)
        )
        assertEquals("主页入口只出图标、不显域名：文字必须为空", "", home.text.toString())
        assertNotNull(
            "主页图标必须显式 tint（ic_link 矢量是白的，不 tint 就是白底白图标）",
            home.compoundDrawableTintList
        )
    }

    @Test
    fun homeLinkIsNotAdjacentToDeleteButton() {
        val row = inflate()
        val home = row.findViewById<View>(R.id.tv_row_home)
        val remove = row.findViewById<View>(R.id.btn_row_remove)
        assertNotSame("主页链接不能与删除按钮并排（误触代价不对称）", remove.parent, home.parent)
    }

    @Test
    fun legacyHomeImageButtonIsRemoved() {
        val row = inflate()
        val legacyId = row.context.resources
            .getIdentifier("btn_row_home", "id", row.context.packageName)
        assertEquals("旧的 btn_row_home 图标按钮应已移除", 0, legacyId)
    }

    @Test
    fun infoLineStartsWithEverythingHidden() {
        // 未同步、无响应头元信息时，信息行整段收起，不留空白也不留死链接
        val row = inflate()
        assertEquals(View.GONE, row.findViewById<View>(R.id.row_info_line).visibility)
        assertEquals(View.GONE, row.findViewById<View>(R.id.tv_row_info).visibility)
        assertEquals(View.GONE, row.findViewById<View>(R.id.tv_row_home).visibility)
    }

    @Test
    fun infoAndHomeAreSiblingsInOneRow() {
        val row = inflate()
        val info = row.findViewById<View>(R.id.tv_row_info)
        val home = row.findViewById<View>(R.id.tv_row_home)
        val line = row.findViewById<ViewGroup>(R.id.row_info_line)
        assertNotSame(info, home)
        assertEquals("信息行必须是横向容器", android.widget.LinearLayout.HORIZONTAL,
            (line as android.widget.LinearLayout).orientation)
        assertEquals(2, line.childCount)
    }

    /**
     * 渲染一张带真实文案的订阅行预览图，供人工核对视觉（写进 build/reports/ui-preview/）。
     * 文案取自真实字符串资源，所以预览与线上格式一致。
     */
    @Test
    fun rendersPreviewWithTitleInfoAndHomeLink() {
        val row = inflate()
        val ctx = row.context
        row.findViewById<TextView>(R.id.tv_row_title).text = "机场 A"
        val info = listOf(
            ctx.getString(CoreR.string.subscription_nodes_count, 12),
            ctx.getString(CoreR.string.subscription_auto_interval, 24),
            ctx.getString(CoreR.string.subscription_synced_at, "14:30")
        ).joinToString(" · ")
        row.findViewById<TextView>(R.id.tv_row_info).apply {
            text = info
            visibility = View.VISIBLE
        }
        // 主页入口没有文字，只把可见性打开（图标来自 XML 的 drawableStart）。
        val home = row.findViewById<TextView>(R.id.tv_row_home).apply { visibility = View.VISIBLE }
        row.findViewById<View>(R.id.row_info_line).visibility = View.VISIBLE

        // BottomSheet 内容宽度 ≈ 屏宽 - 两侧边距
        val density = ctx.resources.displayMetrics.density
        val width = (361 * density).toInt()
        row.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        row.layout(0, 0, width, row.measuredHeight)

        val bitmap = Bitmap.createBitmap(width, row.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(235, 231, 227))
        row.draw(canvas)
        val directory = File("build/reports/ui-preview").apply { mkdirs() }
        File(directory, "subscription-row.png").outputStream()
            .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        assertTrue("预览行必须有内容高度", row.height > 0)
        // 图标必须是**真画出来**且不是白的：ic_link / ic_widget_globe 这类矢量 fillColor
        // 都是白色，一旦 drawableTint 丢了，浅色主题下就是白底白图标 —— 缩略图里肉眼很难发现，
        // 所以按像素卡一次（垫底色亮度 232，图标若真渲染出来会明显更暗）。
        val darkest = darkestLuminanceInside(bitmap, row, home)
        bitmap.recycle()
        assertTrue("主页图标没渲染出来，或仍是白色（实测最暗像素亮度 $darkest）", darkest < 200)
    }

    /** [view] 区域内的最暗不透明像素亮度；一个都没画到返回 255。 */
    private fun darkestLuminanceInside(bitmap: Bitmap, root: View, view: View): Int {
        var left = 0
        var top = 0
        var cursor: View? = view
        while (cursor != null && cursor !== root) {
            left += cursor.left
            top += cursor.top
            cursor = cursor.parent as? View
        }
        var darkest = 255
        for (y in top until minOf(top + view.height, bitmap.height)) {
            for (x in left until minOf(left + view.width, bitmap.width)) {
                val color = bitmap.getPixel(x, y)
                if (Color.alpha(color) < 200) continue
                val luminance = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
                if (luminance < darkest) darkest = luminance
            }
        }
        return darkest
    }
}
