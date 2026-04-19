package app.fjj.stun.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.core.widget.NestedScrollView

class NonJumpingScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {

    // 拦截子 View 获取焦点时触发的自动滚动
    override fun requestChildRectangleOnScreen(
        child: View,
        rectangle: Rect,
        immediate: Boolean
    ): Boolean {
        // 直接返回 false，告诉系统：“我处理好了，你别瞎滚了”
        return false
    }
}