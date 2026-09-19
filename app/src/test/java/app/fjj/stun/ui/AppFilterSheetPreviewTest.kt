package app.fjj.stun.ui

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import app.fjj.stun.R
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.databinding.ItemAppBinding
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
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
import kotlin.math.abs

/**
 * 应用分流选择器的**渲染预览 + 版式断言**，出图落
 * `app/build/reports/ui-preview/app-filter-{light,dark}.png`。
 *
 * 钉住的硬约束：分类 chip 单选且必选（「全部」兜底）、底栏是常规第三段（取消/确定都在），
 * 行卡片选中态 = 主色容器底 + 主色描边（这是「已勾选哪些应用」最直观的视觉反馈，
 * 布局写错选择器就会悄悄退化成和未选中一个样）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppFilterSheetPreviewTest {

    @Test
    fun `筛选区结构契约`() {
        val sheet = buildSheet(night = false)

        val group = sheet.findViewById<ChipGroup>(R.id.chip_group_filter)
        assertTrue("分类筛选必须单选", group.isSingleSelection)
        assertTrue("必须必选（永远有一个分类在生效，否则列表会空到看不出原因）", group.isSelectionRequired)
        assertEquals("效果图是 5 个分类（全部 / 已选择 / 常用 / 系统 / 第三方）", 5, group.childCount)

        // 顶部必须是拖拽把手，没有工具栏/返回按钮
        assertNotNull(sheet.findViewById<com.google.android.material.bottomsheet.BottomSheetDragHandleView>(R.id.drag_handle))
        assertNotNull(sheet.findViewById<TextView>(R.id.tv_title))
        assertNotNull(sheet.findViewById<TextView>(R.id.tv_subtitle))

        // 底栏：计数 + 取消 + 确定（顶部不再放返回按钮）
        assertNotNull(sheet.findViewById<View>(R.id.tv_bottom_count))
        assertNotNull(sheet.findViewById<View>(R.id.btn_cancel))
        assertNotNull(sheet.findViewById<View>(R.id.btn_ok))
        // 全选/反选/清空收进标题右侧的筛选图标；排序行只剩排序控件
        assertNotNull(sheet.findViewById<View>(R.id.btn_header_action))
        assertNotNull(sheet.findViewById<View>(R.id.btn_sort))

        writePng(sheet, "app-filter-header-light")
    }

    @Test
    fun `行卡片选中态配色`() {
        val sheet = buildSheet(night = false)
        val rows = swapInSampleRows(sheet)
        measureAndLayout(sheet)

        val selectedRow = rows[1] as MaterialCardView
        val unselectedRow = rows[0] as MaterialCardView
        assertTrue("选中行卡片处于 checked 态", selectedRow.isChecked)
        assertTrue("未选中行卡片不该是 checked 态", !unselectedRow.isChecked)

        // 选中行背景 = colorPrimaryContainer 再叠上卡片 checked 态自带的 ~11% onSurface
        // state layer（m3_card_foreground 的默认渲染，真机同样如此），所以期望值按叠加后算。
        // 真正要抓的回归是「checked 选择器没生效 → 底色和未选中一个样」，这条断言能抓住它。
        val container = MaterialColors.getColor(sheet, attrId(sheet, "colorPrimaryContainer"))
        val onSurface = MaterialColors.getColor(sheet, attrId(sheet, "colorOnSurface"))
        assertNear(
            "选中行底色没有吃到主色容器色（checked 选择器没生效）",
            blend(container, onSurface, 0.11f),
            bgColorOf(selectedRow),
        )
        // 未选中行背景 = colorSurfaceContainerLow
        assertNear(
            "未选中行底色不是低阶面色",
            MaterialColors.getColor(sheet, attrId(sheet, "colorSurfaceContainerLow")),
            bgColorOf(unselectedRow),
        )

        // 每行都有徽章：第三方显示「已安装」，系统应用显示「系统」
        val ctx = sheet.context
        val badge0 = rows[0].findViewById<TextView>(R.id.tv_app_badge)
        val badge2 = rows[2].findViewById<TextView>(R.id.tv_app_badge)
        assertEquals(View.VISIBLE, badge0.visibility)
        assertEquals(ctx.getString(CoreR.string.badge_installed), badge0.text.toString())
        assertEquals(View.VISIBLE, badge2.visibility)
        assertEquals(ctx.getString(CoreR.string.filter_system), badge2.text.toString())
    }

    @Test
    fun `浅色预览`() {
        val sheet = buildSheet(night = false)
        fillSampleData(sheet)
        swapInSampleRows(sheet)
        measureAndLayout(sheet)
        writePng(sheet, "app-filter-light")
    }

    @Test
    fun `深色预览`() {
        val sheet = buildSheet(night = true)
        fillSampleData(sheet)
        val rows = swapInSampleRows(sheet)
        measureAndLayout(sheet)

        // 深色下选中行的底仍应是主色容器（动态取色后为暗橙系），不能退化成与未选中同色
        val selectedBg = bgColorOf(rows[1] as MaterialCardView)
        val unselectedBg = bgColorOf(rows[0] as MaterialCardView)
        assertTrue(
            "深色下选中行与未选中行底色相同，选中态丢失",
            abs(Color.red(selectedBg) - Color.red(unselectedBg)) +
                abs(Color.green(selectedBg) - Color.green(unselectedBg)) +
                abs(Color.blue(selectedBg) - Color.blue(unselectedBg)) > 12,
        )

        writePng(sheet, "app-filter-dark")
    }

    // ────────────────────────────────────────────────────────── 拼装

    /**
     * 真实 attach 到窗口。两个必须遵守的点（少一个预览就会骗人）：
     * 1. **必须用 AppCompatActivity**：图标只写了 `app:tint`，靠 AppCompat 的 view factory
     *    才生效；裸 Activity 的 inflater 没有 factory，图标会退回矢量里写死的白色。
     * 2. **夜间要换配置上下文 + `cloneInContext`**：前者拿夜间资源，后者保住第 1 点的 factory。
     */
    private fun buildSheet(night: Boolean): View {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        controller.setup()
        val config = Configuration(activity.resources.configuration).apply {
            fontScale = 1f
            uiMode = if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val themed = ContextThemeWrapper(activity.createConfigurationContext(config), R.style.Theme_Stun)
        val inflater = LayoutInflater.from(activity).cloneInContext(themed)
        val sheet = inflater.inflate(R.layout.fragment_app_filter, null, false)
        activity.setContentView(sheet)
        return sheet
    }

    /** 文案都是运行时填的（`tools:text` 不渲染），不填出来就是一张空壳。 */
    private fun fillSampleData(sheet: View) {
        val ctx = sheet.context
        sheet.findViewById<TextView>(R.id.tv_bottom_count).text =
            ctx.getString(CoreR.string.selected_count, 2)
        // 副标题在布局里没法给死，这里模拟 Fragment 的 updateCountDisplay
        val tvSubtitle = sheet.findViewById<TextView>(R.id.tv_subtitle)
        tvSubtitle.text = ctx.getString(CoreR.string.selected_count, 2)
        // chip 计数由代码拼进文案，预览里照拼一遍让图接近真实观感
        setChipText(sheet, R.id.chip_filter_all, CoreR.string.filter_all, 268)
        setChipText(sheet, R.id.chip_filter_selected, CoreR.string.filter_selected, 2)
        setChipText(sheet, R.id.chip_filter_frequently_used, CoreR.string.filter_frequently_used, 18)
        setChipText(sheet, R.id.chip_filter_system, CoreR.string.filter_system, 92)
        setChipText(sheet, R.id.chip_filter_third_party, CoreR.string.filter_third_party, 176)
    }

    private fun setChipText(sheet: View, id: Int, labelRes: Int, count: Int) {
        sheet.findViewById<TextView>(id).text = sheet.context.getString(labelRes) + " ($count)"
    }

    /** 用三行假数据替换 RecyclerView（静态 inflate 的 recycler 出不了行）。 */
    private fun swapInSampleRows(sheet: View): List<MaterialCardView> {
        val root = sheet as ViewGroup
        val recycler = sheet.findViewById<RecyclerView>(R.id.recycler_view)
        val index = root.indexOfChild(recycler)
        root.removeView(recycler)
        val container = LinearLayout(sheet.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        val rows = listOf(
            row(sheet, "Agoda 安可达", "com.agoda.mobile.consumer", isSystem = false, checked = false),
            row(sheet, "AlipayHK", "hk.alipay.wallet", isSystem = false, checked = true),
            row(sheet, "Analytics", "com.miui.analytics", isSystem = true, checked = false),
        )
        rows.forEach { container.addView(it) }
        root.addView(container, index)
        return rows
    }

    private fun row(
        sheet: View,
        name: String,
        pkg: String,
        isSystem: Boolean,
        checked: Boolean,
    ): MaterialCardView {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(sheet.context))
        binding.tvAppName.text = name
        binding.tvPackageName.text = pkg
        binding.tvAppBadge.text = if (isSystem) {
            sheet.context.getString(CoreR.string.filter_system)
        } else {
            sheet.context.getString(CoreR.string.badge_installed)
        }
        binding.cbSelected.isChecked = checked
        // 选中态的底色/描边由卡片 checked state 的颜色选择器驱动，这里只推状态
        binding.cardApp.isChecked = checked
        // ic_fox_logo 是白色填充矢量：给个主色 tint，别让图标在浅色底上隐身
        binding.ivAppIcon.setImageResource(R.drawable.ic_fox_logo)
        binding.ivAppIcon.imageTintList = android.content.res.ColorStateList.valueOf(
            MaterialColors.getColor(sheet, attrId(sheet, "colorPrimary")),
        )
        return binding.root as MaterialCardView
    }

    // ────────────────────────────────────────────────────────── 出图

    private fun measureAndLayout(sheet: View): Int {
        val density = sheet.resources.displayMetrics.density
        val width = (SHEET_WIDTH_DP * density).toInt()
        sheet.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        sheet.layout(0, 0, width, sheet.measuredHeight)
        return width
    }

    private fun writePng(sheet: View, name: String) {
        val width = measureAndLayout(sheet)

        // 先把内容画进独立图层，再合成到垫好底的成品上：弹层根节点的圆角是透明的，
        // 直接在同一张位图上垫底会被内容里的透明区域混掉。
        val layer = Bitmap.createBitmap(width, sheet.height, Bitmap.Config.ARGB_8888)
        Canvas(layer).apply { sheet.draw(this) }
        val bitmap = Bitmap.createBitmap(width, sheet.height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(backdropColor(sheet))
            drawBitmap(layer, 0f, 0f, null)
        }
        assertFullyOpaque(bitmap)
        val dir = File("build/reports/ui-preview").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** 预览图必须处处不透明，否则深色图在看图器里会被透明处露出刺眼的白底。 */
    private fun assertFullyOpaque(bitmap: Bitmap) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val transparent = pixels.count { Color.alpha(it) == 0 }
        assertEquals("预览图里有 $transparent 个全透明像素", 0, transparent)
    }

    private fun backdropColor(sheet: View): Int {
        for (name in listOf("colorSurfaceContainer", "colorSurface")) {
            val id = attrId(sheet, name)
            if (id != 0) return MaterialColors.getColor(sheet, id, Color.WHITE)
        }
        return Color.WHITE
    }

    // ────────────────────────────────────────────────────────── 取色

    /** 取卡片内左缘附近的背景色：避开图标、文字与 checkbox，落在纯背景上。 */
    private fun bgColorOf(card: MaterialCardView): Int {
        val bitmap = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply { card.draw(this) }
        val density = card.resources.displayMetrics.density
        val x = (6 * density).toInt()   // 卡片内容 padding 12dp，6dp 处已在圆角半径内、图标 16dp 之外
        val y = card.height / 2
        val pixel = bitmap.getPixel(x, y)
        assertTrue("采样点 (${x},$y) 是透明的，落在圆角外了", Color.alpha(pixel) > 200)
        return pixel
    }

    private fun assertNear(message: String, expected: Int, actual: Int) {
        val distance = abs(Color.red(expected) - Color.red(actual)) +
            abs(Color.green(expected) - Color.green(actual)) +
            abs(Color.blue(expected) - Color.blue(actual))
        assertTrue("$message（期望 ${hex(expected)}，实际 ${hex(actual)}）", distance <= 12)
    }

    /** 把 overlay 按 fraction 叠在 base 上（模拟 Material 的 state layer 合成）。 */
    private fun blend(base: Int, overlay: Int, fraction: Float): Int = Color.argb(
        255,
        (Color.red(base) * (1 - fraction) + Color.red(overlay) * fraction).toInt(),
        (Color.green(base) * (1 - fraction) + Color.green(overlay) * fraction).toInt(),
        (Color.blue(base) * (1 - fraction) + Color.blue(overlay) * fraction).toInt(),
    )

    private fun hex(color: Int) = String.format("#%06X", color and 0xFFFFFF)

    /**
     * 属性 id 得在本 app 的包名里查：非传递 R 类，`R.attr` 里没有库属性，
     * 资源合并后库属性挂在 app 包名下（与 HomeFragment.getThemeColor 同一套取法）。
     */
    private fun attrId(view: View, name: String): Int =
        view.resources.getIdentifier(name, "attr", view.context.packageName)

    private companion object {
        const val SHEET_WIDTH_DP = 393f
    }
}
