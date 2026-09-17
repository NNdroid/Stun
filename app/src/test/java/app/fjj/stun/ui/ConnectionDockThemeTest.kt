package app.fjj.stun.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import app.fjj.stun.R
import app.fjj.stun.ui.view.ConnectionDockLayout
import com.google.android.material.button.MaterialButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 底栏配色必须来自**主题角色**，不能是写死的色值。
 *
 * 背景：`StunApp` / `BaseActivity` 启用了 `DynamicColors`（Android 12+ 壁纸取色），
 * 它替换的是主题属性。写死的色值（`R.color.connection_surface` 那类）不在替换范围内，
 * 于是底栏永远停在旧配色上 —— 这正是"底栏没跟着整个 app 主题走"的根因。
 *
 * 断言方式是**按真实像素取色**（NATIVE 渲染，把底栏画进 Bitmap 再采样），
 * 而不是重算一遍常量：只有真的画出来，才能证明配色确实来自主题。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectionDockThemeTest {

    @Test
    fun dockSurfaceIsThemeDrivenNotHardcoded() {
        val themed = themedContext()
        val (_, bitmap) = paintedDock(themed)
        val painted = dominantOpaqueColor(bitmap)

        assertEquals(
            "底栏承载面必须等于主题的 colorSurfaceContainer",
            themed.attrColor(COLOR_SURFACE_CONTAINER),
            painted
        )
        // 回归闸门：旧实现写死 #FFFAF7（暖奶油）。若有人改回字面量色值，这条立刻红。
        assertNotEquals("底栏承载面不能再是写死的奶油色", 0xFFFFFAF7.toInt(), painted)
    }

    @Test
    fun avatarAndFabFollowThemeRoles() {
        val themed = themedContext()
        val (dock, bitmap) = paintedDock(themed)

        val avatar = dock.findViewById<View>(R.id.tv_bottom_avatar_letter)
        assertEquals(
            "头像圈必须取主题的 colorPrimaryContainer",
            themed.attrColor("colorPrimaryContainer"),
            dominantColorIn(bitmap, dock, avatar)
        )

        val fab = dock.findViewById<MaterialButton>(R.id.fab_start_stop)
        assertEquals(
            "主按钮底必须取主题的 colorErrorContainer（破坏性操作 = error 角色）",
            themed.attrColor("colorErrorContainer"),
            dominantColorIn(bitmap, dock, fab)
        )
    }

    /**
     * 底栏用到的每个主题角色都必须能被当前主题解析出来。
     * 漏定义不会崩，只会**静默退化成透明/黑色**——比崩溃更难发现，所以单独兜一条。
     */
    @Test
    fun everyRoleUsedByDockResolves() {
        val themed = themedContext()
        for (name in listOf(
            COLOR_SURFACE_CONTAINER, "colorOnSurface", "colorOnSurfaceVariant",
            "colorPrimaryContainer", "colorOnPrimaryContainer",
            "colorError", "colorErrorContainer", "colorOutlineVariant"
        )) {
            assertNotEquals("主题未定义该角色（会退化成透明）: $name", 0, themed.attrColor(name))
        }
    }

    // ── 夹具 ──

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

    /** 走真实布局 inflate + 真实 draw，返回（底栏视图, 画好的位图）。 */
    private fun paintedDock(themed: Context): Pair<ConnectionDockLayout, Bitmap> {
        val home = LayoutInflater.from(themed).inflate(R.layout.fragment_home, null)
        val dock = home.findViewById<ConnectionDockLayout>(R.id.bottom_container)
        (dock.parent as ViewGroup).removeView(dock)
        val width = (393 * themed.resources.displayMetrics.density).toInt()
        dock.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        dock.layout(0, 0, width, dock.measuredHeight)
        val bitmap = Bitmap.createBitmap(width, dock.height, Bitmap.Config.ARGB_8888)
        dock.draw(Canvas(bitmap))
        return dock to bitmap
    }

    /** 底栏承载面是画面上最大的一块纯色 —— 出现次数最多的不透明像素就是它。 */
    private fun dominantOpaqueColor(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return dominant(pixels.asIterable())
    }

    /** 单个控件矩形内出现次数最多的不透明像素 = 它的底色（文字/图标只占少数字素）。 */
    private fun dominantColorIn(bitmap: Bitmap, dock: ViewGroup, child: View): Int {
        val bounds = Rect(0, 0, child.width, child.height)
        dock.offsetDescendantRectToMyCoords(child, bounds)
        val sampled = mutableListOf<Int>()
        for (y in bounds.top until bounds.bottom) {
            if (y !in 0 until bitmap.height) continue
            for (x in bounds.left until bounds.right) {
                if (x !in 0 until bitmap.width) continue
                sampled += bitmap.getPixel(x, y)
            }
        }
        return dominant(sampled.filter { Color.alpha(it) != 0 })
    }

    private fun dominant(pixels: Iterable<Int>): Int {
        val counts = HashMap<Int, Int>()
        for (p in pixels) counts[p] = (counts[p] ?: 0) + 1
        return (counts.maxByOrNull { it.value } ?: error("该区域没有画出任何不透明像素")).key
    }

    /**
     * 按**名字**解析主题属性，与 `HomeFragment.getThemeColor` 同款。
     *
     * 关键实测结论：库属性经 aapt 合并后归属**本 app 的包名** ——
     * `getIdentifier(name, "attr", "com.google.android.material")` 与 `androidx.appcompat` 都返回 0，
     * 必须用 `packageName`。`"android"` 只为兜住框架自带同名属性（如 `android:colorError`），
     * 而本项目要的是 app 侧那个，所以 packageName 必须排在前面。
     */
    private fun Context.attrColor(name: String): Int {
        val id = listOf(packageName, "android")
            .map { resources.getIdentifier(name, "attr", it) }
            .firstOrNull { it != 0 } ?: error("找不到主题属性: $name")
        val value = TypedValue()
        check(theme.resolveAttribute(id, value, true)) { "主题解析不到: $name" }
        return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
    }

    private companion object {
        const val COLOR_SURFACE_CONTAINER = "colorSurfaceContainer"
    }
}
