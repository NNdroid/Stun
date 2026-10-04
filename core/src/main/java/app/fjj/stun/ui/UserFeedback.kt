package app.fjj.stun.ui

import android.content.Context
import android.view.View
import android.widget.Toast
import com.google.android.material.snackbar.Snackbar

/**
 * 用户可见反馈的**统一入口**：Toast 还是 Snackbar，只在这里决定一次。
 *
 * ## 为什么要有这个类
 * 仓库里 Toast 与 Snackbar 曾经是 112 : 7 的悬殊比例，而且**比例本身不是问题，混用才是**：
 * 同一个「导入失败」在主界面弹 Toast、在设置页弹 Snackbar；用户学到的是"这个 App 的提示
 * 不可靠"，于是开始忽略所有提示 —— 真正的错误提示也跟着被忽略。
 *
 * ## 判据
 * | 反馈性质 | 用什么 | 例子 |
 * |---|---|---|
 * | **操作失败 / 原因** | Snackbar | 导入失败、权限被拒、扫码失败、测速出错 |
 * | 短告知 | Toast | 已选中、已删除、列表为空 |
 *
 * 理由：Toast 在横屏、多窗口、通知栏下拉时都可能被遮住，而且**无法被复制**。
 * 失败原因恰恰是用户最需要看清、最可能想转述给别人听的一类信息。
 *
 * ## 为什么用 `notify` 而不是 `error`
 * 只有一个入口（而不是 `error()` / `info()` 两个）是有意的：分类是一次性的判断，
 * 写进方法名会诱使每个调用点都自己再判一次。绝大多数调用点直接用 [notify]，
 * 确实要区分时用 [error]（等价于 Snackbar + 长时长）。
 *
 * ## 锚点
 * [notify] 的 `anchor` 为 null（或已 detach）时**回落 Toast**，绝不静默吞掉反馈 ——
 * "没提示"比"提示方式不理想"糟糕得多。
 */
object UserFeedback {

    /**
     * 弹一条反馈。
     *
     * @param anchor Snackbar 锚点，通常是 `binding.root`。传 null 则用 Toast。
     * @param longDuration 长文本或失败原因用 true（`LENGTH_LONG`）。
     */
    fun notify(
        context: Context,
        anchor: View?,
        message: CharSequence,
        longDuration: Boolean = false,
    ) {
        val duration = if (longDuration) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT
        val usableAnchor = anchor?.takeIf { it.isAttachedToWindow && it.width > 0 }
        if (usableAnchor != null) {
            Snackbar.make(usableAnchor, message, duration).show()
        } else {
            Toast.makeText(
                context,
                message,
                if (longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /**
     * 失败 / 原因类反馈：一律 Snackbar + 长时长。
     *
     * 绝大多数失败反馈应该用这个而不是 [notify] —— 它把"失败必须看得清"变成调用点
     * 的默认行为，而不是每次都要记得传参。
     */
    fun error(context: Context, anchor: View?, message: CharSequence) {
        notify(context, anchor, message, longDuration = true)
    }
}
