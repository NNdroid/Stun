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
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.fjj.stun.R
import app.fjj.stun.core.R as CoreR
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
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
 * 设置页「WebDAV 云备份」小节的**渲染预览 + 结构契约**，出图落
 * `app/build/reports/ui-preview/settings-webdav-{light,dark}.png`。
 *
 * 钉住的硬约束（照效果图重做后的版式）：
 * 1. 头部是**卡片内**的徽标行（`bg_icon_badge` 圆角底 + `ic_cloud` + 标题 + 副标题），
 *    不再是卡片外面一条纯文字小节标签。
 * 2. 自动备份开关就放在这条**头部行里**（右对齐），所以从 `switch_webdav_auto`
 *   往上数两级是「卡片内层 padding 容器」，不是卡片本身 —— 取卡片必须向上找
 *   第一个 `MaterialCardView`，不能写死层数（本测试就是因为写死层数而失败的）。
 * 3. 五个输入框（URL / User / Pass / PIN / Interval）各带 startIcon：
 *    ic_link / ic_person / ic_lock / ic_key / ic_schedule。
 * 4. 字段顺序、自动备份开关、上次备份文案、备份/恢复按钮**逻辑与 id 不变**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsWebDavSectionPreviewTest {

    @Test
    fun `WebDAV 区结构契约`() {
        val root = buildSettings(night = false)

        // 五个输入框都在，且 id 未变（保证 Kotlin 逻辑零改动）
        assertNotNull("URL 输入框缺失", root.findViewById<TextInputEditText>(R.id.et_webdav_url))
        assertNotNull("User 输入框缺失", root.findViewById<TextInputEditText>(R.id.et_webdav_user))
        assertNotNull("Pass 输入框缺失", root.findViewById<TextInputEditText>(R.id.et_webdav_pass))
        assertNotNull("PIN 输入框缺失", root.findViewById<TextInputEditText>(R.id.et_webdav_pin))
        assertNotNull("Interval 输入框缺失", root.findViewById<TextInputEditText>(R.id.et_webdav_interval))

        // 开关 / 上次备份 / 两个按钮都在
        val sw = root.findViewById<MaterialSwitch>(R.id.switch_webdav_auto)
        assertNotNull("自动备份开关缺失", sw)
        assertNotNull("上次备份文案缺失", root.findViewById<TextView>(R.id.tv_webdav_last))
        assertNotNull("备份按钮缺失", root.findViewById<View>(R.id.btn_webdav_backup))
        assertNotNull("恢复按钮缺失", root.findViewById<View>(R.id.btn_webdav_restore))

        // 头部行：卡片 → 内层 padding 容器 → 第 0 个子节点
        val card = webDavCard(root)
        val container = card.getChildAt(0) as ViewGroup
        val header = container.getChildAt(0) as ViewGroup

        // 头部行 1) 是横向排布；2) 首个子节点是徽标 ImageView（带圆角底）；
        // 3) 含一个加粗标题 TextView，文案就是本节标题；4) 自动备份开关就挂在头部行里。
        assertEquals("头部行应为横向 LinearLayout", android.widget.LinearLayout.HORIZONTAL, (header as android.widget.LinearLayout).orientation)

        val badge = header.getChildAt(0)
        assertTrue("头部首个元素应为徽标 ImageView", badge is ImageView)
        assertNotNull("徽标应有圆角底色 bg_icon_badge", badge.background)

        val title = textViews(header).firstOrNull { it.text == root.context.getString(CoreR.string.webdav_title) }
        assertNotNull("头部应含本节标题", title)
        assertTrue("本节标题应加粗", title!!.typeface.isBold)

        val subtitle = root.context.getString(CoreR.string.webdav_subtitle)
        assertTrue("头部应含副标题", textViews(header).any { it.text == subtitle })

        assertTrue("自动备份开关应位于头部行内", sw.parent === header)

        writePng(root, "settings-webdav-light")
    }

    @Test
    fun `深色预览`() {
        val root = buildSettings(night = true)
        writePng(root, "settings-webdav-dark")
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

    /** 从开关往上找**第一个** MaterialCardView —— 别写死层数（头部开关会改层级）。 */
    private fun webDavCard(root: View): MaterialCardView {
        var node: View? = root.findViewById<MaterialSwitch>(R.id.switch_webdav_auto)
        while (node != null && node !is MaterialCardView) node = node.parent as? View
        assertNotNull("找不到 WebDAV 卡片", node)
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

    // ────────────────────────────────────────────────────────── 出图（只裁 WebDAV 卡片）

    private fun writePng(root: View, name: String) {
        val card = webDavCard(root)

        val density = root.resources.displayMetrics.density
        val width = (393 * density).toInt()
        card.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        card.layout(0, 0, width, card.measuredHeight)

        val layer = Bitmap.createBitmap(width, card.height, Bitmap.Config.ARGB_8888)
        Canvas(layer).apply { card.draw(this) }
        val bitmap = Bitmap.createBitmap(width, card.height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(com.google.android.material.color.MaterialColors.getColor(root, attrId(root, "colorSurface"), Color.WHITE))
            drawBitmap(layer, 0f, 0f, null)
        }
        val dir = File("build/reports/ui-preview").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** 属性 id 得在本 app 的包名里查（非传递 R 类，与现有预览测试同一套取法）。 */
    private fun attrId(view: View, name: String): Int =
        view.resources.getIdentifier(name, "attr", view.context.packageName)
}
