package app.fjj.stun.ui.view

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import app.fjj.stun.util.AppUtils

/** Compact dock label: keep MB/s together and do not reduce the user's text size. */
object ConnectionRateLabel {
    fun format(bytesPerSecond: Long, upload: Boolean, accent: Int, unitColor: Int): CharSequence {
        val prefix = if (upload) "⬆" else "⬇"
        val value = AppUtils.formatBytes(bytesPerSecond.coerceAtLeast(0)).replace(" ", "")
        val text = SpannableString("$prefix$value/s")
        if (prefix.isNotEmpty()) text.setSpan(ForegroundColorSpan(accent), 0, prefix.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val unitStart = text.indexOfFirst { it.isLetter() }.takeIf { it >= 0 } ?: text.length
        text.setSpan(ForegroundColorSpan(unitColor), unitStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return text
    }
}
