package app.fjj.stun.ui

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import app.fjj.stun.R
import com.google.android.material.color.MaterialColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 设置页 / 编辑节点页底栏的**渲染预览**：真的画成 PNG 落到
 * `app/build/reports/ui-preview/{settings,profile-edit}-bottom-bar-{light,dark}.png`。
 *
 * 为什么要把内容**滚到末尾**再画：用户报的正是"页面末尾被保存按钮压住"，
 * 顶部区域两张图看起来不会有差别。滚到底才是那个状态的呈现。
 *
 * 出图之外只卡两条硬约束（图形差异当断言太脆）：
 * 1. 预览图必须处处不透明（绘制顺序把垫底擦掉时，看图器会补白底）；
 * 2. 保存按钮那一点必须真的被画成主题主色 —— 结构断言证明不了"画出来了"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BottomBarPreviewTest {

    @Test
    fun `设置页底栏预览-浅色-已滚到末尾`() =
        render(R.layout.activity_settings, night = false, name = "settings-bottom-bar-light")

    @Test
    fun `设置页底栏预览-深色-已滚到末尾`() =
        render(R.layout.activity_settings, night = true, name = "settings-bottom-bar-dark")

    @Test
    fun `编辑节点页底栏预览-浅色-已滚到末尾`() =
        render(R.layout.activity_profile_edit, night = false, name = "profile-edit-bottom-bar-light")

    @Test
    fun `编辑节点页底栏预览-深色-已滚到末尾`() =
        render(R.layout.activity_profile_edit, night = true, name = "profile-edit-bottom-bar-dark")

    private fun render(@LayoutRes layout: Int, night: Boolean, name: String) {
        // 必须用 AppCompatActivity：图标只写了 app:tint，靠 AppCompat 的 view factory 才生效；
        // 夜间则要「换配置上下文 + cloneInContext」——前者拿夜间资源，后者保住这个 factory。
        // 另外：AppCompatActivity 取的是 manifest 里 <application android:theme> 的 Theme.Stun。
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        controller.setup()
        val config = Configuration(activity.resources.configuration).apply {
            fontScale = 1f
            uiMode = if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val themed = ContextThemeWrapper(activity.createConfigurationContext(config), R.style.Theme_Stun)
        val root = LayoutInflater.from(activity).cloneInContext(themed).inflate(layout, null)
        activity.setContentView(root)

        // 按整屏高度排版（不是 wrap_content）：这两页内容很长，wrap 出来会是一张几千 dp 的巨图。
        val dm = root.resources.displayMetrics
        root.measure(exactly(dm.widthPixels), exactly(dm.heightPixels))
        root.layout(0, 0, dm.widthPixels, dm.heightPixels)

        // 滚到末尾 —— 页面末尾与底栏的关系正是本次要看的。
        root.findViewById<NestedScrollView>(R.id.scroll_view)?.let { scroll ->
            scroll.scrollTo(0, scroll.getChildAt(0)?.height ?: 0) // ScrollView 自己会夹到 scrollRange
        }

        writePng(root, name)
    }

    private fun writePng(root: View, name: String) {
        val width = root.width
        val height = root.height

        // 分两层：先把 root 画进独立图层，再合成到垫好底的成品上。
        // 布局根没有 background，落笔处是透明的 —— 垫底层必须来自主题角色，写死浅色会让深色预览露白。
        val layer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(layer).apply { root.draw(this) }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(themeColor(root, "colorSurface"))
            drawBitmap(layer, 0f, 0f, null)
        }

        assertFullyOpaque(bitmap)
        assertSaveButtonIsPainted(root, bitmap)

        File("build/reports/ui-preview").apply { mkdirs() }
            .let { File(it, "$name.png").outputStream().use { s ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, s) } }
    }

    private fun assertFullyOpaque(bitmap: Bitmap) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val transparent = pixels.count { Color.alpha(it) == 0 }
        assertEquals(
            "预览图里有 $transparent 个全透明像素 —— 绘制顺序把垫底擦掉了，看图器会给它补白底",
            0,
            transparent,
        )
    }

    /** 采"按钮左侧内 8dp、垂直居中"那一点：中心是按钮文字，会采到字形而不是底色。 */
    private fun assertSaveButtonIsPainted(root: View, bitmap: Bitmap) {
        val bar = root.findViewById<View>(R.id.btn_save).parent as View
        val inset = (8 * root.resources.displayMetrics.density).toInt()
        val x = bar.left + bar.paddingLeft + inset
        val y = (bar.top + bar.bottom) / 2
        val expected = themeColor(root, "colorPrimary")
        val pixel = bitmap.getPixel(x, y)

        assertTrue(
            "底栏保存按钮没被画出来：采样点 (${x},${y}) 是 #${hex(pixel)}，" +
                "期望接近主题主色 #${hex(expected)}",
            near(pixel, expected),
        )
    }

    /** 容差 8/通道：按钮可能叠一层很淡的 state layer，卡死相等会因为一两个色阶而红。 */
    private fun near(a: Int, b: Int): Boolean =
        Math.abs(Color.red(a) - Color.red(b)) <= 8 &&
            Math.abs(Color.green(a) - Color.green(b)) <= 8 &&
            Math.abs(Color.blue(a) - Color.blue(b)) <= 8

    private fun hex(color: Int) = String.format("%06X", color and 0xFFFFFF)

    /**
     * 主题角色取色。属性 id 得在本 app 包名里查：app 的 `R.attr` 里没有库属性，
     * `R.attr.colorPrimary` 直接编译不过（见 `HomeFragment.getThemeColor` 同一套约定）。
     */
    private fun themeColor(root: View, name: String): Int {
        val id = root.resources.getIdentifier(name, "attr", root.context.packageName)
        return if (id == 0) Color.WHITE else MaterialColors.getColor(root, id, Color.WHITE)
    }

    private fun exactly(px: Int) = View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY)
}
