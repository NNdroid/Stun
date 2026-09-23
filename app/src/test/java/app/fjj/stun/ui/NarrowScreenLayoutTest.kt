package app.fjj.stun.ui

import android.app.Application
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import app.fjj.stun.R
import com.google.android.material.button.MaterialButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * 窄屏（320dp）回归：动画二维码两处界面在**小屏 + 大字号 + 长文案**下不能被压扁。
 *
 * 为什么必须真跑一次布局（`assembleDebug` 完全查不出来）：
 *
 * 静态写法上「三个按钮一行」在宽屏看着完全正常，窄屏出的事**不是溢出、而是压扁**——
 * `MaterialButtonToggleGroup` 本质是横向 `LinearLayout`，宽度不够时它不换行，而是把
 * **最后一个**按钮压到几乎为零。实测 320dp 窄屏 + 德语（Langsam/Mittel/Schnell）：
 *
 * | 字号 | 速度组自然宽 | `Schnell` 布局后宽 | 症状 |
 * |---|---|---|---|
 * | 1.0× | 285dp | 82.5dp | 省略号吃掉 4 个字符 |
 * | 1.5× | 365dp | 27.5dp | 省略号吃掉 7 个字符、低于 48dp 热区 |
 * | 2.0× | 405dp | 1.5dp  | 文字全没了、点都点不到 |
 *
 * 注意：这类故障**不会**让任何视图越出父容器边界，所以「扫一遍有没有横向溢出」的断言
 * 会**假阴性**通过（`right` 始终在父容器内，只是子按钮被压窄了）。真正能抓住它的判据是
 * 「布局后的宽度 ≥ 自身自然宽度」和「文字没被省略号截断」—— 本文件断言的就是这两条。
 *
 * ⚠️ 必须 `@GraphicsMode(NATIVE)`：LEGACY 模式下 Robolectric 的字体度量是假的
 * （`measureText` 恒给小值、`naturalWidth` 不随字号变化），上面那张表会全部退化成同一行，
 * 德语大字号的问题会被**测不出来**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w320dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NarrowScreenLayoutTest {

    // ------------------------------------------------- 分享弹窗：速度档位不能被压扁

    @Test
    fun `分享弹窗的速度档位在窄屏和各语言下都不被压扁`() {
        for ((tag, locale) in LOCALES) {
            for (fontScale in FONT_SCALES) {
                val root = render(R.layout.dialog_qr_code, fontScale, locale, revealStream = true)
                val where = "$tag @${fontScale}×"

                // 整组不能被压扁：放不下时必须靠横向滚动，而不是把子按钮挤窄。
                val group = root.findViewById<ViewGroup>(R.id.toggle_qr_stream_speed)
                val groupNatural = naturalWidth(group)
                assertTrue(
                    "$where 速度组被压扁了：布局后 ${group.width}px < 自身自然宽度 ${groupNatural}px。\n" +
                        "MaterialButtonToggleGroup 是横向 LinearLayout，宽度不够时它不换行、只压最后一个按钮；" +
                        "请保持外面那层 HorizontalScrollView（仓库里 home/logs/app-filter 三处同类做法）。",
                    group.width >= groupNatural,
                )

                for (id in SPEED_BUTTONS) {
                    val b = root.findViewById<MaterialButton>(id)
                    val label = root.resources.getResourceEntryName(id)
                    assertTrue(
                        "$where $label 被压扁：宽 ${b.width}px < 自然宽度 ${naturalWidth(b)}px",
                        b.width >= naturalWidth(b),
                    )
                    assertEquals(
                        "$where $label 的文字被省略号截断了（\"${b.text}\"）—— 窄屏上这档速度就看不出来了",
                        0,
                        (0 until b.layout!!.lineCount).sumOf { b.layout!!.getEllipsisCount(it) },
                    )
                    val min = root.resources.getDimensionPixelSize(R.dimen.touch_target_min)
                    assertTrue(
                        "$where $label 的可点区域只有 ${b.width}x${b.height}px，低于最小触摸目标 ${min}px",
                        b.width >= min && b.height >= min,
                    )
                }
            }
        }
    }

    // ------------------------------------------------- 接收端扫码页：整体不横向溢出

    @Test
    fun `接收端扫码页在窄屏和各语言下不横向溢出且提示文字完整`() {
        for ((tag, locale) in LOCALES) {
            for (fontScale in FONT_SCALES) {
                val root = render(R.layout.activity_qr_stream_scan, fontScale, locale)
                val where = "$tag @${fontScale}×"

                val overflow = mutableListOf<String>()
                collectOverflow(root, root.width - root.paddingRight, overflow)
                assertTrue(
                    "$where 扫码页有视图越出了父容器：\n${overflow.joinToString("\n")}",
                    overflow.isEmpty(),
                )

                for (id in intArrayOf(R.id.tv_qr_scan_hint, R.id.tv_qr_scan_status, R.id.tv_qr_scan_title)) {
                    val v = root.findViewById<android.widget.TextView>(id)
                    if (v.visibility == View.GONE) continue
                    val name = root.resources.getResourceEntryName(id)
                    assertTrue("$where $name 没有被布局（宽或高为 0）", v.width > 0 && v.height > 0)
                    assertEquals(
                        "$where $name 的文字被省略号截断了 —— 扫码提示是用户唯一知道该怎么做的地方",
                        0,
                        (0 until v.layout!!.lineCount).sumOf { v.layout!!.getEllipsisCount(it) },
                    )
                }
            }
        }
    }

    /**
     * 底部状态卡必须留在屏幕内。它是根 `FrameLayout` 的直接子视图，所以 `left/right/bottom`
     * 就是相对屏幕的坐标，不需要再做换算。
     */
    @Test
    fun `接收端扫码页的底部状态卡留在屏幕内`() {
        val root = render(R.layout.activity_qr_stream_scan, 2f, Locale.GERMAN)
        val card = root.findViewById<View>(R.id.qr_scan_status_card)
        assertTrue("大字号德语下状态卡没被布局（宽或高为 0）", card.width > 0 && card.height > 0)
        assertTrue("大字号德语下状态卡左边越界：${card.left}", card.left >= 0)
        assertTrue("大字号德语下状态卡右边越界：${card.right} > ${root.width}", card.right <= root.width)
        assertTrue("大字号德语下状态卡下边越界：${card.bottom} > ${root.height}", card.bottom <= root.height)
    }

    // ------------------------------------------------- harness

    /**
     * inflate 真实 XML → 按窄屏精确尺寸 measure/layout → 返回根视图。
     *
     * `AppCompatActivity` 不能换成裸 `Activity`：非 AppCompat 的 inflater 没有 view factory，
     * `app:tint` / `app:icon` 会被静默丢掉。`ContextThemeWrapper` 必须套 `Theme.Stun`，
     * 否则 `?attr/colorOnSurfaceVariant` 一类主题角色取不到值。
     */
    private fun render(layoutId: Int, fontScale: Float, locale: Locale, revealStream: Boolean = false): View {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        controller.setup()
        val config = Configuration(activity.resources.configuration).apply {
            this.fontScale = fontScale
            setLocale(locale)
        }
        val themed = ContextThemeWrapper(activity.createConfigurationContext(config), R.style.Theme_Stun)
        val root = LayoutInflater.from(activity).cloneInContext(themed).inflate(layoutId, null)
        if (revealStream) {
            for (id in intArrayOf(R.id.tv_qr_stream_info, R.id.qr_stream_controls, R.id.tv_qr_stream_hint)) {
                root.findViewById<View>(id).visibility = View.VISIBLE
            }
        }
        activity.setContentView(root)
        val dm = root.resources.displayMetrics
        root.measure(
            View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, dm.widthPixels, dm.heightPixels)
        return root
    }

    /** 无约束测出的固有宽度 —— 布局后的宽度小于它，就说明被父容器压扁了。 */
    private fun naturalWidth(v: View): Int {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return v.measuredWidth
    }

    /**
     * 递归收集右边越出父容器内容区的节点。子视图的 `right` 以父容器左边缘为原点。
     * 横向滚动容器内部不算溢出（那里的内容本来就该靠滑动看）。
     */
    private fun collectOverflow(v: View, limit: Int, out: MutableList<String>) {
        if (v.visibility == View.GONE) return
        if (v.right > limit + 1) {
            val name = runCatching { v.resources.getResourceEntryName(v.id) }.getOrDefault("?")
            out += "${v.javaClass.simpleName}#$name right=${v.right} > limit=$limit"
        }
        if (v is android.widget.HorizontalScrollView) return
        if (v is ViewGroup) {
            val childLimit = v.width - v.paddingRight
            for (i in 0 until v.childCount) collectOverflow(v.getChildAt(i), childLimit, out)
        }
    }

    private companion object {
        /** 德语标签最长（Langsam/Mittel/Schnell），中文最短 —— 两头都测。 */
        val LOCALES = listOf(
            "zh" to Locale.SIMPLIFIED_CHINESE,
            "zh-TW" to Locale.TRADITIONAL_CHINESE,
            "de" to Locale.GERMAN,
            "fr" to Locale.FRENCH,
            "ja" to Locale.JAPANESE,
        )
        val FONT_SCALES = floatArrayOf(1f, 1.5f, 2f)

        val SPEED_BUTTONS = intArrayOf(
            R.id.btn_qr_speed_slow,
            R.id.btn_qr_speed_medium,
            R.id.btn_qr_speed_fast,
        )
    }
}
