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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.NestedScrollView
import app.fjj.stun.R
import app.fjj.stun.core.R as CoreR
import com.google.android.material.card.MaterialCardView
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

/**
 * 设置页**六张小节卡片**（全局设置 / UDP 网关 / 地理数据 / 自定义直连分流 / WebDAV 云备份 /
 * 应用分流）的整页渲染预览 + 头部版式契约，出图落
 * `app/build/reports/ui-preview/settings-sections-{light,dark}.png`。
 *
 * 照效果图重做后，六张卡共用同一套头部版式，本测试把这条**共用契约**钉死：
 * 1. 头部是**卡片内**的第 0 行：`bg_icon_badge` 圆角底徽标 + 加粗 `TitleMedium` 标题；
 * 2. 徽标用的就是本节该用的那个图标 —— 判据是「在候选图标池里**得分最高**的是它」，
 *    **不是**「和预期图标的绝对一致率 ≥ 某阈值」。绝对阈值会被光栅化噪声打穿：同一矢量
 *    走 `app:tint` 与直取资源两条路画到 32x32，边缘 1~2px 阈值翻转就能把一致率压到
 *    0.79~0.85（实测 ic_port 0.847 / ic_filter 0.792），而同图标仍是全池最高分；
 * 3. 卡片**前面不再有**卡片外的小节标签（旧版是每张卡前面一条全大写 TextView，
 *    现在的间距在卡片自己的 `layout_marginTop` 上）。
 *
 * 另外顺带把整页 PNG 出出来（量的是 `scroll_view` 的内容，UNSPECIFIED 高度 ⇒ 六张卡
 * 全在一张图上），用来和效果图逐节比对。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsSectionsPreviewTest {

    private class Section(
        val label: String,
        val anchorId: Int,
        val titleRes: Int,
        val iconRes: Int,
        val iconName: String,
    )

    private val sections = listOf(
        Section("全局设置", R.id.spinner_service_mode, CoreR.string.unified_config, R.drawable.ic_settings, "ic_settings"),
        Section("UDP 网关", R.id.card_udpgw_section, CoreR.string.udpgw_version, R.drawable.ic_port, "ic_port"),
        Section("地理数据", R.id.et_geosite_url, CoreR.string.geo_data_config, R.drawable.ic_database, "ic_database"),
        Section("自定义直连分流", R.id.et_geosite_direct, CoreR.string.custom_direct_routing, R.drawable.ic_filter, "ic_filter"),
        Section("WebDAV 云备份", R.id.switch_webdav_auto, CoreR.string.webdav_title, R.drawable.ic_cloud, "ic_cloud"),
        Section("应用分流", R.id.et_filter_apps, CoreR.string.app_filtering, R.drawable.ic_grid, "ic_grid"),
    )

    /** 候选池：本页新用的 6 个徽标图标 + 本页行内用到的其它图标当干扰项。 */
    private val iconPool = linkedMapOf(
        "ic_settings" to R.drawable.ic_settings,
        "ic_port" to R.drawable.ic_port,
        "ic_database" to R.drawable.ic_database,
        "ic_filter" to R.drawable.ic_filter,
        "ic_cloud" to R.drawable.ic_cloud,
        "ic_grid" to R.drawable.ic_grid,
        "ic_bell" to R.drawable.ic_bell,
        "ic_globe" to R.drawable.ic_globe,
        "ic_server" to R.drawable.ic_server,
        "ic_link" to R.drawable.ic_link,
        "ic_schedule" to R.drawable.ic_schedule,
        "ic_sync" to R.drawable.ic_sync,
    )

    @Test
    fun `六张小节卡片的共用头部契约`() {
        val root = buildSettings(night = false)
        val mismatches = mutableListOf<String>()

        for (s in sections) {
            val card = cardOf(root, s)
            val container = card.getChildAt(0) as ViewGroup
            val header = container.getChildAt(0)

            // 1) 头部是横向排布的行，首元素是带圆角底的徽标
            if (header !is LinearLayout || header.orientation != LinearLayout.HORIZONTAL) {
                mismatches += "${s.label}: 头部第 0 行不是横向 LinearLayout（实际 ${header.javaClass.simpleName}）"
                continue
            }
            val badge = header.getChildAt(0)
            if (badge !is ImageView) {
                mismatches += "${s.label}: 头部首元素不是徽标 ImageView"
                continue
            }
            if (badge.background == null) mismatches += "${s.label}: 徽标缺 bg_icon_badge 圆角底"

            // 2) 标题：文案 = 本节标题，且加粗
            val title = textViews(header).firstOrNull { it.text == root.context.getString(s.titleRes) }
            if (title == null) {
                mismatches += "${s.label}: 头部找不到本节标题"
            } else if (!title.typeface.isBold) {
                mismatches += "${s.label}: 标题未加粗"
            }

            // 3) 徽标图标就是这个：在候选池里它必须拿最高分
            val scores = iconPool
                .map { (name, res) -> name to agreement(coverage(badge.drawable, ICON_MASK), maskOf(root, res)) }
                .sortedByDescending { it.second }
            if (scores.first().first != s.iconName) {
                mismatches += "${s.label}: 徽标图标识别为 ${scores.first().first}（期望 ${s.iconName}）｜" +
                    "得分 " + scores.take(3).joinToString(" > ") { "${it.first} ${"%.3f".format(it.second)}" }
            }

            // 4) 卡片前一个兄弟不再是卡片外的小节标签
            val parent = card.parent as ViewGroup
            val prev = parent.getChildAt(parent.indexOfChild(card) - 1)
            if (prev is TextView) mismatches += "${s.label}: 卡片前仍有卡片外的小节标签「${prev.text}」"
        }

        assertTrue("六张小节卡片头部契约不满足：\n" + mismatches.joinToString("\n"), mismatches.isEmpty())

        // 整页出图（light）：量 scroll_view 的内容，高度不限 ⇒ 六张卡都在
        writePagePng(root, "settings-sections-light")
    }

    @Test
    fun `深色预览`() {
        val root = buildSettings(night = true)
        writePagePng(root, "settings-sections-dark")
    }

    // ────────────────────────────────────────────────────────── 查找

    /** 从锚点往上找**第一个** MaterialCardView —— 别写死层数（头部放开关会改层级）。 */
    private fun cardOf(root: View, s: Section): MaterialCardView {
        val anchor = root.findViewById<View>(s.anchorId)
        assertNotNull("${s.label}: 锚点 ${s.anchorId} 缺失", anchor)
        var node: View? = anchor
        while (node != null && node !is MaterialCardView) node = node.parent as? View
        assertNotNull("${s.label}: 找不到所属卡片", node)
        return node as MaterialCardView
    }

    private fun textViews(group: ViewGroup): List<TextView> = buildList {
        for (i in 0 until group.childCount) {
            when (val c = group.getChildAt(i)) {
                is TextView -> add(c)
                is ViewGroup -> addAll(textViews(c))
            }
        }
    }

    // ────────────────────────────────────────────────────────── 图标掩码比对

    /** 期望图标的掩码只算一次（同一个 drawable 反复 setBounds 是共享状态，别互相踩）。 */
    private val maskCache = HashMap<Int, BooleanArray>()

    private fun maskOf(root: View, resId: Int): BooleanArray =
        maskCache.getOrPut(resId) { coverage(AppCompatResources.getDrawable(root.context, resId), ICON_MASK) }

    /** 32x32 覆盖率掩码：只看出不出墨，不看颜色（tint 路径不同但形状同）。 */
    private fun coverage(drawable: Drawable?, size: Int): BooleanArray {
        val d = requireNotNull(drawable) { "drawable 为空" }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bmp))
        val px = IntArray(size * size)
        bmp.getPixels(px, 0, size, 0, 0, size, size)
        return BooleanArray(size * size) { Color.alpha(px[it]) > 40 }
    }

    private fun agreement(a: BooleanArray, b: BooleanArray): Float {
        var inter = 0
        var union = 0
        for (i in a.indices) {
            if (a[i] || b[i]) {
                union++
                if (a[i] && b[i]) inter++
            }
        }
        return if (union == 0) 1f else inter.toFloat() / union
    }

    // ────────────────────────────────────────────────────────── 拼装

    private fun buildSettings(night: Boolean): View {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        controller.setup()
        val config = Configuration(activity.resources.configuration).apply {
            fontScale = 1f
            uiMode = if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val themed = ContextThemeWrapper(activity.createConfigurationContext(config), R.style.Theme_Stun)
        val inflater = LayoutInflater.from(activity).cloneInContext(themed)
        val root = inflater.inflate(R.layout.activity_settings, null, false)
        activity.setContentView(root)
        return root
    }

    // ────────────────────────────────────────────────────────── 出图（整页，只裁滚动内容）

    private fun writePagePng(root: View, name: String) {
        val scroll = root.findViewById<NestedScrollView>(R.id.scroll_view)
        val content = scroll.getChildAt(0) as View

        val density = root.resources.displayMetrics.density
        val width = (393 * density).toInt()
        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        content.layout(0, 0, width, content.measuredHeight)

        // 分两层：内容画在透明层上，再合成到主题 surface 垫底上
        //（自绘视图每帧 CLEAR 自己区域，直接画在垫底上会把垫底擦成透明）。
        val layer = Bitmap.createBitmap(width, content.height, Bitmap.Config.ARGB_8888)
        Canvas(layer).apply { content.draw(this) }
        val bitmap = Bitmap.createBitmap(width, content.height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(MaterialColors.getColor(root, attrId(root, "colorSurface"), Color.WHITE))
            drawBitmap(layer, 0f, 0f, null)
        }
        val dir = File("build/reports/ui-preview").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** 属性 id 得在本 app 的包名里查（非传递 R 类，与现有预览测试同一套取法）。 */
    private fun attrId(view: View, name: String): Int =
        view.resources.getIdentifier(name, "attr", view.context.packageName)

    private companion object {
        const val ICON_MASK = 32
    }
}
