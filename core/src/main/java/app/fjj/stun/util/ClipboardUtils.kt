package app.fjj.stun.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import app.fjj.stun.core.R as CoreR

/**
 * 「复制到剪贴板 + 告知用户」的**唯一入口**。
 *
 * ## 为什么要收口
 * 仓库里有 9 处 `setPrimaryClip`，原先每处自己写一遍
 * `getSystemService(CLIPBOARD_SERVICE) as ClipboardManager` + `ClipData.newPlainText` + `Toast`。
 * 复制这种操作**看起来成功了但没提示**，用户第一反应是"按钮坏了"，然后反复点 ——
 * 所以"复制后必须给反馈"是硬约定，不是锦上添花。收口就是为了让这条约定无法被漏掉。
 *
 * ## label 是必填的
 * [ClipboardManager.setPrimaryClip] 的 `label` 会进系统的剪贴板历史（Android 13+ 的
 * 「粘贴」弹窗第一行就显示它）。随手写 `clipboard` 或 `text` 会让用户在跨应用粘贴时
 * 看到毫无意义的一行字。
 */
object ClipboardUtils {

    /**
     * 复制 [text] 到剪贴板，并用 [copiedMessage] 告知用户。
     *
     * @param label 剪贴板标签，进系统剪贴板历史。写清楚是什么内容（如 `"Stun Node URI"`）。
     * @param copiedMessage 提示文案。传 `null` 用默认的「已复制到剪贴板」。
     * @return 是否真的写入了。`ClipboardManager` 取不到时返回 false 且**不弹提示** ——
     *   弹一个"已复制"但其实没复制，比不弹更糟。
     */
    fun copy(
        context: Context,
        label: String,
        text: String,
        copiedMessage: CharSequence? = null,
    ): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(
            context,
            copiedMessage ?: context.getString(CoreR.string.copy_success),
            Toast.LENGTH_SHORT,
        ).show()
        return true
    }
}
