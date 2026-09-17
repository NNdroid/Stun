package app.fjj.stun.ui

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.NestedScrollView
import app.fjj.stun.R
import app.fjj.stun.core.R as CoreR
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 设置页「MCP Agent Server / 数据库 Web UI」两张卡的**版式回归 + 渲染预览**。
 *
 * 这一版把这两张卡从「一个重复的小节标签 + 一堆裸输入框」改成：卡片头部圆角图标徽标、
 * 字段前置图标、状态胶囊（带语义色地球）、复制按钮带图标、保存按钮带软盘图标。
 * 改动是纯视觉的，但有几个点一旦回退就会**静默坏掉**，所以在这里钉住：
 *
 * 1. 与卡片标题逐字重复的小节标签被删干净了（每张标题在布局里只应出现一次）；
 * 2. 徽标 / 胶囊 / 地球**真的被画出来**了（结构对 ≠ 画出来，颜色得按像素验）；
 * 3. 新增的 4 个手绘矢量图标 pathData 没写坏（写错会渲染成全透明或糊成实心块）；
 * 4. 徽标用的是**预期那个**图标（MCP 节点图 vs 数据库，两者不能混）。
 *
 * ⚠️ 采样坐标必须减掉 NestedScrollView 的 scrollY：`View.left/top` 是布局坐标，
 * 不含父容器滚动量。不滚就对不上（目标卡在视口外根本没被画进 bitmap），
 * 减错就等于在采样旁边那块内容 —— 两种错法都会让断言莫名其妙地飘。
 *
 * ⚠️ **不要用这些预览图判断 TextInputLayout 前置图标的留白**：Robolectric 里
 * `startIconDrawable` 不会把 EditText 的 paddingStart 让出来（实测 paddingLeft 恒等于
 * 盒内边距 32px），所以预览图上图标会压在 hint 文字上。这是 harness 的局限，不是布局写错 ——
 * 既有页面（编辑节点页）的 10 个同款字段在 Robolectric 里测得一模一样，而真机截图
 * （`screenshots/3_add_profile.png`）里它们是正常让位的。改这里别被预览图带偏。
 *
 * 另外出 PNG 到 `app/build/reports/ui-preview/settings-{mcp,db}-card-{light,dark}.png`，
 * 这两张卡「长什么样」最终得靠眼睛看。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsRemoteServicesCardTest {

    /** 两张卡：状态条 id / 徽标 id / 徽标应当用的图标。 */
    private val cards = listOf(
        Triple(R.id.tv_mcp_server_status, R.id.badge_mcp_server, R.drawable.ic_mcp),
        Triple(R.id.tv_db_web_status, R.id.badge_db_web, R.drawable.ic_database),
    )
    // ---------------------------------------------------------------- 预览出图

    @Test
    fun `MCP 卡预览-浅色`() = renderCard(R.id.tv_mcp_server_status, night = false, name = "settings-mcp-card-light")

    @Test
    fun `MCP 卡预览-深色`() = renderCard(R.id.tv_mcp_server_status, night = true, name = "settings-mcp-card-dark")

    @Test
    fun `数据库卡预览-浅色`() = renderCard(R.id.tv_db_web_status, night = false, name = "settings-db-card-light")

    @Test
    fun `数据库卡预览-深色`() = renderCard(R.id.tv_db_web_status, night = true, name = "settings-db-card-dark")

    // ---------------------------------------------------------------- 结构断言

    @Test
    fun `两张卡的小节标签已删除-标题在布局里各只出现一次`() {
        val root = inflate()
        assertEquals(
            "MCP 标题出现了多于一次 —— 卡片上方那个重复的小节标签又回来了",
            1,
            countTexts(root, root.resources.getString(CoreR.string.mcp_server_title)),
        )
        assertEquals(
            "数据库 WebUI 标题出现了多于一次 —— 卡片上方那个重复的小节标签又回来了",
            1,
            countTexts(root, root.resources.getString(CoreR.string.db_web_title)),
        )
    }

    @Test
    fun `每个输入字段都挂上了预期的前置图标`() {
        val root = inflate()
        val pairs = intArrayOf(
            R.id.et_mcp_server_port, R.drawable.ic_port,
            R.id.spinner_mcp_auth_mode, R.drawable.ic_shield,
            R.id.layout_mcp_auth_secret, R.drawable.ic_lock,
            R.id.et_db_web_port, R.drawable.ic_port,
            R.id.et_db_web_user, R.drawable.ic_person,
            R.id.layout_db_web_pass, R.drawable.ic_lock,
        )
        val themed = themedContext(night = false)
        var i = 0
        while (i < pairs.size) {
            val id = pairs[i]
            val expected = pairs[i + 1]
            // 有的是 TextInputLayout 自己的 id，有的是里面 EditText 的 id —— 一律往上找到 TextInputLayout。
            var til = root.findViewById<View>(id)
            while (til !is TextInputLayout) til = til.parent as View
            val actual = til.startIconDrawable
            assertNotNull("字段没有前置图标（${res(root.resources, id)}）", actual)
            assertEquals(
                "字段前置图标尺寸不是 24dp（${res(root.resources, id)}）",
                px(root, 24),
                actual!!.intrinsicWidth,
            )
            assertSameIcon(
                actual,
                AppCompatResources.getDrawable(themed, expected)!!,
                "字段前置图标（${res(root.resources, id)}）",
            )
            i += 2
        }
    }

    @Test
    fun `两个复制按钮和保存按钮都带图标且靠文字起始端`() {
        val root = inflate()
        for (id in intArrayOf(R.id.btn_copy_mcp_config, R.id.btn_copy_db_web_url, R.id.btn_save)) {
            val btn = root.findViewById<MaterialButton>(id)
            assertNotNull("按钮没有图标（${res(root.resources, id)}）", btn.icon)
            assertEquals(
                "图标应靠文字起始端排布（${res(root.resources, id)}）",
                MaterialButton.ICON_GRAVITY_TEXT_START,
                btn.iconGravity,
            )
        }
    }

    // ---------------------------------------------------------------- 像素断言

    @Test
    fun `状态条前置图标是地球且按 drawableTint 上了色-不是看不见的白`() {
        val root = inflate()
        val scroll = root.findViewById<NestedScrollView>(R.id.scroll_view)
        val themed = themedContext(night = false)
        val globe = AppCompatResources.getDrawable(themed, R.drawable.ic_widget_globe)!!
        // 布局里的 drawableTint 兜底 = 未启动态角色。运行时 SettingsFragment 会按运行状态覆写，
        // 这里只验「tint 有没有生效」：丢了 tint 就是白色地球，在浅色胶囊上等于隐形。
        val expected = themeColor(root, "colorOnSurfaceVariant")
        assertTrue(
            "colorOnSurfaceVariant 在浅色主题里不该接近白色，否则这条断言失去了判别力",
            !near(expected, Color.WHITE, slack = 60),
        )

        for (id in intArrayOf(R.id.tv_mcp_server_status, R.id.tv_db_web_status)) {
            val tv = root.findViewById<TextView>(id)
            val leading = tv.compoundDrawablesRelative[0]
            assertNotNull("状态条缺少前置图标（${res(root.resources, id)}）", leading)
            assertSameIcon(leading!!, globe, "状态条前置图标（${res(root.resources, id)}）")

            scroll.scrollTo(0, maxOf(0, offsetIn(scroll.getChildAt(0), tv).second - px(root, 8)))
            val bitmap = render(root)
            val (l, t) = offsetIn(root, tv)
            val top = t - scroll.scrollY

            // 地球是实心填充，园里应当有成片的纯色；全部落空说明 tint 丢了（白图标配浅底）。
            var hits = 0
            for (x in l until l + tv.paddingLeft + px(root, 24)) {
                for (y in top until top + tv.height) {
                    val p = bitmap.getPixel(x, y)
                    if (Color.alpha(p) > 200 && near(p, expected, slack = 40)) hits++
                }
            }
            assertTrue(
                "状态条地球没有上色（${res(root.resources, id)}）：采样区里只有 $hits 个接近 #${hex(expected)} 的像素，" +
                    "多半是 drawableTint 丢了 —— 白色地球在浅色胶囊上等于隐形",
                hits > 30,
            )
        }
    }

    @Test
    fun `卡片头部徽标按次级容器色画出来了且字形是深色的`() {
        val root = inflate()
        val scroll = root.findViewById<NestedScrollView>(R.id.scroll_view)
        val bg = themeColor(root, "colorSecondaryContainer")
        val fg = themeColor(root, "colorOnSecondaryContainer")

        for ((statusId, badgeId, _) in cards) {
            scroll.scrollTo(0, maxOf(0, cardTop(root, scroll, statusId) - px(root, 8)))
            val bitmap = render(root)
            val badge = root.findViewById<ImageView>(badgeId)
            val (l, t) = offsetIn(root, badge)
            val top = t - scroll.scrollY

            // 左侧竖直中线：40dp 徽标在这一行左边缘是直的，不会踩到 12dp 圆角。
            val padPixel = bitmap.getPixel(l + px(root, 3), top + badge.height / 2)
            assertTrue(
                "徽标底色不对（${res(root.resources, badgeId)}）：实测 #${hex(padPixel)}，" +
                    "期望接近次级容器色 #${hex(bg)}",
                near(padPixel, bg, slack = 12),
            )

            var glyph = 0
            for (x in l until l + badge.width) {
                for (y in top until top + badge.height) {
                    if (near(bitmap.getPixel(x, y), fg, slack = 40)) glyph++
                }
            }
            assertTrue(
                "徽标里没有深色字形（${res(root.resources, badgeId)}）：只有 $glyph 个接近 #${hex(fg)} 的像素 —— " +
                    "要么图标没画出来，要么 app:tint 丢了",
                glyph > 60,
            )
        }
    }

    @Test
    fun `状态胶囊底色与卡片底色不同层`() {
        val root = inflate()
        val scroll = root.findViewById<NestedScrollView>(R.id.scroll_view)
        val pill = themeColor(root, "colorSurfaceContainer")
        val card = themeColor(root, "colorSurfaceContainerLow")
        assertTrue("胶囊底色和卡片底色是同一个角色，看不出分层", !near(pill, card, slack = 4))

        for (id in intArrayOf(R.id.tv_mcp_server_status, R.id.tv_db_web_status)) {
            scroll.scrollTo(0, maxOf(0, cardTop(root, scroll, id) - px(root, 8)))
            val bitmap = render(root)
            val tv = root.findViewById<TextView>(id)
            val (l, t) = offsetIn(root, tv)
            val top = t - scroll.scrollY + tv.height / 2
            val pixel = bitmap.getPixel(l + px(root, 4), top)
            assertTrue(
                "胶囊左内侧应是容器色 #${hex(pill)}，实测 #${hex(pixel)}（${res(root.resources, id)}）",
                near(pixel, pill, slack = 12),
            )
        }
    }

    @Test
    fun `新增矢量图标路径都没写坏`() {
        val themed = themedContext(night = false)
        for (resId in intArrayOf(R.drawable.ic_mcp, R.drawable.ic_port, R.drawable.ic_shield, R.drawable.ic_save)) {
            val mask = alphaMask(AppCompatResources.getDrawable(themed, resId)!!)
            val frac = mask.count { it }.toDouble() / mask.size
            assertTrue(
                "图标 ${res(themed.resources, resId)} 几乎什么都没画（覆盖率 %.3f）—— 大概率是 pathData 写坏了".format(frac),
                frac > 0.02,
            )
            assertTrue(
                "图标 ${res(themed.resources, resId)} 几乎糊满了（覆盖率 %.3f）—— 不像是描边图标".format(frac),
                frac < 0.75,
            )
        }
    }

    @Test
    fun `两张卡的徽标用的是各自的图标`() {
        val root = inflate()
        val themed = themedContext(night = false)
        val masks = cards.map { (_, badgeId, _) ->
            coarseMask(root.findViewById<ImageView>(badgeId).drawable)
        }
        cards.forEachIndexed { i, (_, badgeId, iconRes) ->
            assertSameIcon(
                root.findViewById<ImageView>(badgeId).drawable,
                AppCompatResources.getDrawable(themed, iconRes)!!,
                "徽标（${res(root.resources, badgeId)}）/ 期望 ${res(themed.resources, iconRes)}",
            )
        }
        val cross = agreement(masks[0], masks[1])
        assertTrue(
            "两张卡的徽标撞成同一个图标了（一致率 %.3f，应低于 $DIFFERENT_ICON）".format(cross),
            cross < DIFFERENT_ICON,
        )
    }

    // ---------------------------------------------------------------- harness

    private fun inflate(night: Boolean = false): View {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        controller.setup()
        val root = LayoutInflater.from(activity)
            .cloneInContext(themedContext(night))
            .inflate(R.layout.activity_settings, null)
        activity.setContentView(root)
        val dm = root.resources.displayMetrics
        root.measure(exactly(dm.widthPixels), exactly(dm.heightPixels))
        root.layout(0, 0, dm.widthPixels, dm.heightPixels)
        return root
    }

    /**
     * 必须用 AppCompatActivity：图标只写了 `app:tint`，靠 AppCompat 的 view factory 才生效；
     * 夜间则要「换配置上下文 + cloneInContext」——前者拿夜间资源，后者保住这个 factory。
     */
    private fun themedContext(night: Boolean): ContextThemeWrapper {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        controller.setup()
        val config = Configuration(activity.resources.configuration).apply {
            fontScale = 1f
            uiMode = if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        return ContextThemeWrapper(activity.createConfigurationContext(config), R.style.Theme_Stun)
    }

    private fun renderCard(anchorId: Int, night: Boolean, name: String) {
        val root = inflate(night)
        val scroll = root.findViewById<NestedScrollView>(R.id.scroll_view)
        scroll.scrollTo(0, maxOf(0, cardTop(root, scroll, anchorId) - px(root, 8)))
        val bitmap = render(root)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertEquals("预览图里有透明像素 —— 绘制顺序把垫底擦掉了，看图器会补白", 0, pixels.count { Color.alpha(it) == 0 })
        File("build/reports/ui-preview").apply { mkdirs() }
            .let { File(it, "$name.png").outputStream().use { s -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, s) } }
    }

    /** 分两层：布局根没有 background，不垫底会留下一片透明，看图器补白后深色预览会露白。 */
    private fun render(root: View): Bitmap {
        val layer = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        Canvas(layer).apply { root.draw(this) }
        return Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply {
                drawColor(themeColor(root, "colorSurface"))
                drawBitmap(layer, 0f, 0f, null)
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** 状态条往上找到所属卡片，再取卡片头部的徽标 ImageView。 */
    private fun cardTop(root: View, scroll: NestedScrollView, anchorId: Int): Int {
        var card: View = root.findViewById(anchorId)
        while (card !is MaterialCardView) card = card.parent as View
        return offsetIn(scroll.getChildAt(0), card).second
    }

    private fun offsetIn(ancestor: View, view: View): Pair<Int, Int> {
        var x = 0
        var y = 0
        var v: View? = view
        while (v != null && v !== ancestor) {
            x += v.left
            y += v.top
            v = v.parent as? View
        }
        return x to y
    }

    private fun countTexts(root: View, text: String): Int {
        var n = 0
        fun walk(v: View) {
            if (v is TextView && v.text.toString() == text) n++
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return n
    }

    private fun alphaMask(d: Drawable, size: Int = 96): BooleanArray {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bmp))
        val px = IntArray(size * size)
        bmp.getPixels(px, 0, size, 0, 0, size, size)
        return BooleanArray(size * size) { Color.alpha(px[it]) > 8 }
    }

    /**
     * 图标身份判定用的粗掩码：96x96 细掩码按 6x6 压成 16x16，任一像素不透明即置位。
     *
     * 为什么不逐像素比 `alphaMask`：同一个 VectorDrawable 经过「ImageView 的 app:tint」
     * 和「直接取资源」两条路光栅化，描边边缘会有一两个像素的阈值翻转（实测逐像素一致率
     * 0.994~0.995，全等必红）。压成粗格之后这两个翻转被吃掉，实测同图标一致率 0.99+，
     * 而 ic_mcp 与 ic_database 之间只有 0.65 —— 判别力绰绰有余。
     */
    private fun coarseMask(d: Drawable, cells: Int = 16, fine: Int = 96): BooleanArray {
        val m = alphaMask(d, fine)
        val step = fine / cells
        return BooleanArray(cells * cells) { i ->
            val cx = i % cells
            val cy = i / cells
            var any = false
            for (y in cy * step until (cy + 1) * step) for (x in cx * step until (cx + 1) * step) {
                if (m[y * fine + x]) any = true
            }
            any
        }
    }

    private fun agreement(a: BooleanArray, b: BooleanArray) =
        a.indices.count { a[it] == b[it] }.toDouble() / a.size

    private fun assertSameIcon(actual: Drawable, expected: Drawable, what: String) {
        val agree = agreement(coarseMask(actual), coarseMask(expected))
        assertTrue("$what 不是预期的那个图标（形状一致率 %.3f，低于 $SAME_ICON）".format(agree), agree >= SAME_ICON)
    }

    private fun near(a: Int, b: Int, slack: Int = 8): Boolean =
        Math.abs(Color.red(a) - Color.red(b)) <= slack &&
            Math.abs(Color.green(a) - Color.green(b)) <= slack &&
            Math.abs(Color.blue(a) - Color.blue(b)) <= slack

    private fun hex(color: Int) = String.format("%06X", color and 0xFFFFFF)

    private fun px(root: View, dp: Int) = (dp * root.resources.displayMetrics.density).toInt()

    private fun res(resources: android.content.res.Resources, id: Int) = resources.getResourceEntryName(id)

    /** 属性 id 得在本 app 包名里查：app 的 `R.attr` 里没有库属性，直接引用编译不过。 */
    private fun themeColor(root: View, name: String): Int {
        val id = root.resources.getIdentifier(name, "attr", root.context.packageName)
        return if (id == 0) Color.WHITE else MaterialColors.getColor(root, id, Color.WHITE)
    }

    private fun exactly(px: Int) = View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY)

    private companion object {
        /** 同一图标的粗掩码一致率下限（实测同图标 0.99+）。 */
        const val SAME_ICON = 0.98

        /** 不同图标的粗掩码一致率上限（实测 ic_mcp vs ic_database 只有 0.65）。 */
        const val DIFFERENT_ICON = 0.85
    }
}
