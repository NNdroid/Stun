package app.fjj.stun.util

import android.graphics.Rect
import android.view.View
import androidx.core.widget.NestedScrollView

/**
 * 把 [view] 滚进 [scroll] 的**真实可视区**（= 滚动容器高度 − 底部留白）。
 *
 * 为什么不直接用 `view.requestRectangleOnScreen()`：
 * [NestedScrollView] 的 `computeScrollDeltaToGetChildRectOnScreen()` 拿 `getHeight()` 当可视底边，
 * 全程不读 `getPaddingBottom()` —— 它是把"底部留白区"也算成可见的。
 * 而 targetSdk 35 起 `adjustResize` 不再压缩窗口（Android 15 强制 edge-to-edge，框架不再给
 * root view 补 IME padding），ScrollView 的底边就压在键盘下面：焦点框一落进被键盘盖住的那块，
 * 平台就判定"已经可见"从而不再滚动 —— 位于页面末尾的输入框于是始终被键盘压着。
 *
 * 这里改为自己按"可视底边界 = 高度 − 底部留白"算差值补齐，再交给 ScrollView 自带的 clamp
 * （它的 scrollRange 是扣 padding 的，所以只要底部留白够高，就一定滚得到）。
 *
 * @param animate `true` = `smoothScrollBy`（焦点切换时用，看得见移动过程）；
 *                `false` = 立即 `scrollBy`，供键盘动画逐帧调用，避免每帧启动一个新动画。
 */
fun revealAboveBottomPadding(scroll: NestedScrollView, view: View, animate: Boolean = true) {
    if (scroll.height <= 0) return
    val rect = Rect()
    view.getDrawingRect(rect)
    // 转成内容坐标系（相对 scroll，不含 scrollY 偏移），与 ScrollView 内部的判定口径一致。
    scroll.offsetDescendantRectToMyCoords(view, rect)
    val visibleBottom = scroll.height - scroll.paddingBottom
    val overlap = (rect.bottom - scroll.scrollY) - visibleBottom
    if (overlap <= 0) return
    if (animate) scroll.smoothScrollBy(0, overlap) else scroll.scrollBy(0, overlap)
}
