package app.fjj.stun.ui

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.fjj.stun.R
import app.fjj.stun.core.R as CoreR
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 设置页「应用分流」小节的**渲染预览 + 结构契约**，出图落
 * `app/build/reports/ui-preview/settings-filter-{light,dark}.png`。
 *
 * 钉住的硬约束（这次重做的视觉）：
 * 1. 模式选择是两张**可勾选卡片**，各带一个 radio（白名单 / 黑名单互斥），
 *    不再是 ExposedDropdownMenu 的 spinner。
 * 2. 有「选择应用」按钮（文案 = select_apps）+ 计数 TextView（tv_filter_selected_count）。
 * 3. 包名输入框 et_filter_apps 恢复为**可编辑**（之前是 click-only 只读框），
 *    旧的 spinner_filter_mode 必须已移除。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsFilterSectionPreviewTest {

    @Test
    fun `应用分流区结构契约`() {
        val root = buildSettings(night = false)

        // 模式选择：两张可勾选卡片，各带一个 radio
        val cardAllow = root.findViewById<MaterialCardView>(R.id.card_filter_allow)
        val cardDisallow = root.findViewById<MaterialCardView>(R.id.card_filter_disallow)
        assertNotNull("白名单模式卡片缺失", cardAllow)
        assertNotNull("黑名单模式卡片缺失", cardDisallow)
        assertTrue("白名单卡片应可勾选", cardAllow.isCheckable)
        assertTrue("黑名单卡片应可勾选", cardDisallow.isCheckable)
        assertNotNull("白名单 radio 缺失", root.findViewById<RadioButton>(R.id.rb_filter_allow))
        assertNotNull("黑名单 radio 缺失", root.findViewById<RadioButton>(R.id.rb_filter_disallow))

        // 管理行：计数 + 选择应用按钮（文案 = select_apps，点击进应用选择器）
        assertNotNull("已选计数 TextView 缺失", root.findViewById<TextView>(R.id.tv_filter_selected_count))
        val manage = root.findViewById<MaterialButton>(R.id.btn_manage_filter_apps)
        assertNotNull("选择应用按钮缺失", manage)
        assertTrue(
            "选择应用按钮文案应为 select_apps",
            manage.text.toString().contains(root.context.getString(CoreR.string.select_apps)),
        )

        // 包名输入框恢复为可编辑（不再是点击即弹窗的只读框）
        val et = root.findViewById<TextInputEditText>(R.id.et_filter_apps)
        assertNotNull("包名输入框缺失", et)
        assertTrue("包名输入框应可聚焦编辑", et.isFocusable)

        // 旧的 dropdown spinner 必须已移除
        assertNull("旧的 spinner_filter_mode 应已移除", root.findViewById<View>(R.id.spinner_filter_mode))

        writePng(root, "settings-filter-light")
    }

    @Test
    fun `深色预览`() {
        val root = buildSettings(night = true)
        writePng(root, "settings-filter-dark")
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

    // ────────────────────────────────────────────────────────── 出图（只裁应用分流卡片）

    private fun writePng(root: View, name: String) {
        val cardAllow = root.findViewById<MaterialCardView>(R.id.card_filter_allow)
        // card_filter_allow → 分段行 LinearLayout → 卡片内层 padding LinearLayout → 外层应用分流卡片
        val segmentedRow = cardAllow.parent as View
        val content = segmentedRow.parent as View
        val filterCard = content.parent as MaterialCardView

        val density = root.resources.displayMetrics.density
        val width = (393 * density).toInt()
        filterCard.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        filterCard.layout(0, 0, width, filterCard.measuredHeight)

        val layer = Bitmap.createBitmap(width, filterCard.height, Bitmap.Config.ARGB_8888)
        Canvas(layer).apply { filterCard.draw(this) }
        val bitmap = Bitmap.createBitmap(width, filterCard.height, Bitmap.Config.ARGB_8888)
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
}
