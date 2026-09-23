package app.fjj.stun.ui.view

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.core.graphics.ColorUtils
import app.fjj.stun.R
import app.fjj.stun.util.ThemeColors

/**
 * 连接详情面板里「服务器提示」那一框的虚线框，颜色从**主题角色**取。
 *
 * ### 为什么不在 XML 里写
 *
 * `<shape>` 的 `<solid android:color>` 和 `<stroke android:color>` **不吃** `?attr/` 主题引用，
 * 而颜色资源本身也不能引用主题属性 —— 想让它"跟着主题走"只能运行时装上去。
 *
 * 旧实现就是踩在这条上：写死了 `#D98C86`（虚线）+ `#FFF8F7`（底色）那对暖粉色。本 app 启用了
 * `DynamicColors`（`StunApp` / `BaseActivity`），基线种子是橙棕、真机上换成壁纸取色，于是这一框
 * **永远是粉的**——主题换到蓝青色后它跟整个面板格格不入。断开按钮是同一个病（见
 * `bottom_sheet_connection_details.xml` 里 `btn_detail_disconnect` 的注释）。
 *
 * ### 取色
 *
 * - 底色 `colorSurfaceContainerHigh`：比承载它的卡片高一级。靠**明度对比**而不是颜色分界，
 *   这样框里的 ANSI 彩色文本（服务端可能发来任意 SGR 色）不会被一层同色底色吃掉。
 * - 虚线 `colorPrimary` 降透明度：保留"这是个框、内容是外部输入"的边界感，但颜色本身是主题色。
 *
 * 两处都刻意**不走 error 角色**：M3 的 error 调色板在 DynamicColors 下是**固定红**，不随壁纸变，
 * 换成 error 同样还是粉色。
 *
 * 只在真正展示这行时调用（`HomeFragment` 把 `row_detail_ssh_notice` 设成 VISIBLE 的同一处）。
 * 行默认 `gone`，不存在"框还没上色就先显示出来"的空窗；布局里也不留 `android:background`，
 * 所以配色不可能从某个写死色值的 drawable 里漏出来（这条由 `ConnectionDetailsThemeTest` 兜着）。
 */
object ServerNoticeBox {

    /**
     * 虚线相对实线的弱化比例。虚线本身已经比实线弱一截，再叠一层透明度，
     * 让它读起来是"边界"而不是"警告"——满饱和度实心的主色边框会喧宾夺主。
     *
     * `internal` 是给 `ConnectionDetailsThemeTest` 用的：它按同一个常量复算期望值，
     * 改这一处不需要再去测试里改一遍数字。
     */
    internal const val STROKE_ALPHA = 115

    fun applyTo(view: View) {
        val context = view.context
        val density = context.resources.displayMetrics.density
        val stroke = ColorUtils.setAlphaComponent(
            ThemeColors.color(context, "colorPrimary", Color.BLUE),
            STROKE_ALPHA,
        )
        val fill = ThemeColors.color(context, "colorSurfaceContainerHigh", Color.LTGRAY)

        val box = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = context.resources.getDimension(R.dimen.corner_xs)
            // 宽度与虚线节距沿用旧版 XML 的比例（1dp / 4dp 节 / 3dp 空），只换颜色。
            setStroke((1 * density).toInt().coerceAtLeast(1), stroke, 4f * density, 3f * density)
        }
        view.background = box
    }
}
