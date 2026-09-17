package app.fjj.stun.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.fjj.stun.R
import androidx.core.content.ContextCompat
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
 * 节点卡片「来源订阅」徽标（`tv_sub_badge`）的版式回归。
 *
 * 这个徽标连着踩过两个坑，所以单独立一个用例守着：
 *
 * 1. **颜色**：`ic_widget_globe` 矢量里写死了 `fillColor="@android:color/white"`，
 *    不给 `drawableTint` 就是浅底上的白地球 —— 在缩略图里几乎看不出来（背景是浅的、
 *    文字又是深色，只有最左边那块"少了一点"），必须按像素量才能卡住。
 * 2. **内容**：从前贴的是订阅 URL 的**域名**，现在只贴订阅名（那部分逻辑在
 *    ProfileAdapter 里，用 URL 查 SubscriptionManager 的内存快照）。
 *
 * 底色是 `colorSecondaryContainer`（浅色主题 #FFDCBE），前景是
 * `colorOnSecondaryContainer`（#2A1707）—— 两者亮度差约 200，所以"地球比底色暗"
 * 这个断言容得下抗锯齿带来的偏差，但白地球（亮度 255）一定过不了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileSubBadgeLayoutTest {

    @Test
    fun `徽标地球必须有主题前景色_tint_而不是矢量自带的白色`() {
        val card = inflate()
        val badge = badgeWithText(card, SUBSCRIPTION_NAME)

        val expected = themeColor(badge.context, com.google.android.material.R.attr.colorOnSecondaryContainer)
        assertNotNull(
            "来源订阅徽标缺少 drawableTint —— ic_widget_globe 会用矢量自带的白色，浅色主题下就是白地球",
            badge.compoundDrawableTintList,
        )
        assertEquals(
            "来源订阅徽标的 drawableTint 应跟随 colorOnSecondaryContainer（与徽标文字同色）",
            expected,
            badge.compoundDrawableTintList!!.defaultColor,
        )
    }

    @Test
    fun `徽标地球真的被画成了前景色_不是白底白图标`() {
        val card = inflate()
        // 文字清空 → 徽标里只剩地球，量到的像素只可能来自图标本身。
        val badge = badgeWithText(card, "")
        val chipColor = themeColor(badge.context, com.google.android.material.R.attr.colorSecondaryContainer)
        val inkColor = themeColor(badge.context, com.google.android.material.R.attr.colorOnSecondaryContainer)

        val bitmap = render(card, "profile-sub-badge.png")
        val bounds = boundsInRoot(card, badge)
        val darkest = darkestLuminance(bitmap, bounds)
        val metrics = bounds
        bitmap.recycle()

        assertTrue("徽标没有量到尺寸（bounds=$metrics），地球不可能画出来", badge.width > 0 && badge.height > 0)
        val chip = luminance(chipColor)
        val ink = luminance(inkColor)
        assertTrue(
            "徽标地球对比底色几乎没有明暗差（地球最暗处亮度 $darkest，底色 $chip，前景应为 $ink）" +
                " —— 十有八九是白地球叠在浅色底上",
            chip - darkest > 80,
        )
    }

    // ────────────────────────────────────────────────────────── 工具

    private fun inflate(): View {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        val config = Configuration(activity.resources.configuration).apply {
            fontScale = 1f
            uiMode = Configuration.UI_MODE_NIGHT_NO
        }
        val themed = ContextThemeWrapper(activity.createConfigurationContext(config), R.style.Theme_Stun)
        controller.setup()
        val card = LayoutInflater.from(themed).inflate(R.layout.item_profile, null)
        // 真挂到窗口上：TextView 的复合 drawable（drawableStart / drawableTint）要到
        // onAttachedToWindow 之后才解析，不 attach 的话图标根本不会出现。
        activity.setContentView(card)
        return card
    }

    private fun badgeWithText(card: View, text: String): TextView {
        val badge = card.findViewById<TextView>(R.id.tv_sub_badge)
        badge.text = text
        badge.visibility = View.VISIBLE
        return badge
    }

    /** 量一遍整卡宽并落位，然后画进位图（垫一层浅色底，和真机浅色主题观感一致）。 */
    private fun render(card: View, fileName: String): Bitmap {
        val width = card.resources.displayMetrics.widthPixels
        card.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        card.layout(0, 0, width, card.measuredHeight)

        val bitmap = Bitmap.createBitmap(width, card.height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(235, 231, 227))
            card.draw(this)
        }
        val directory = File("build/reports/ui-preview").apply { mkdirs() }
        File(directory, fileName).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    /** [view] 在 [root] 坐标系里的矩形（不滚动的静态排版，逐级累加 left/top 就够）。 */
    private fun boundsInRoot(root: View, view: View): IntArray {
        var left = 0
        var top = 0
        var cursor: View? = view
        while (cursor != null && cursor !== root) {
            left += cursor.left
            top += cursor.top
            cursor = cursor.parent as? View
        }
        return intArrayOf(left, top, left + view.width, top + view.height)
    }

    /** 矩形内最暗的不透明像素亮度；一个像素都没落进来返回 255。 */
    private fun darkestLuminance(bitmap: Bitmap, bounds: IntArray): Int {
        val (left, top, right, bottom) = bounds
        var darkest = 255
        for (y in top until minOf(bottom, bitmap.height)) {
            for (x in left until minOf(right, bitmap.width)) {
                if (x < 0 || y < 0) continue
                val color = bitmap.getPixel(x, y)
                if (Color.alpha(color) < 200) continue
                val value = luminance(color)
                if (value < darkest) darkest = value
            }
        }
        return darkest
    }

    private fun luminance(color: Int): Int =
        (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000

    /** 按 attr 解析主题色。用 material 的 R 而不是 app 的 —— 库属性不在 app 的非传递 R 类里。 */
    private fun themeColor(context: Context, attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId) else value.data
    }

    private companion object {
        /** 真实订阅名，取自 content-disposition 而不是 URL 域名。 */
        const val SUBSCRIPTION_NAME = "机场 A"
    }
}
