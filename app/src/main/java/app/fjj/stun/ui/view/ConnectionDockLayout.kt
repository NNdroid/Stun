package app.fjj.stun.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.google.android.material.color.MaterialColors
import app.fjj.stun.R
import kotlin.math.abs

/** Edge-to-edge connection dock with a raised, smoothly joined centre handle. */
class ConnectionDockLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    private var layoutMode = -1
    var onSwipeUp: (() -> Unit)? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val swipeDistance = maxOf(touchSlop * 2, 24 * resources.displayMetrics.density)
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var upward = false
    private var multiTouch = false
    init {
        // 承载面走主题角色，不写字面量色值。
        // 原因：StunApp/BaseActivity 启用了 DynamicColors（Android 12+ 壁纸取色），它替换的是**主题属性**；
        // `context.getColor(R.color.x)` 这类字面量不在替换范围内，底栏会永远停在旧配色上。
        // 属性 id 必须用 Material 库的 R（非传递 R 类，本项目 app 的 R 里没有库属性）。
        // 兜底值只是 API 要求的参数——本主题一定定义 colorSurfaceContainer，正常永不命中。
        background = DockSurface(
            MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorSurfaceContainer,
                context.getColor(R.color.md_theme_light_surfaceContainer)
            ),
            resources.displayMetrics.density
        )
        elevation = 4f * resources.displayMetrics.density
        clipToPadding = false
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                moved = false
                upward = false
                multiTouch = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                cancelTap(event)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> if (moved) return true
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    upward = dy < 0 && -dy > abs(dx) * 1.2f
                    // Cancel the original click target, including the stop button. A drag
                    // must never finish as a latency-test click or disconnect click.
                    cancelTap(event)
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (moved) {
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        val expand = !multiTouch && upward && -dy >= swipeDistance && -dy > abs(dx) * 1.2f
                        moved = false
                        parent?.requestDisallowInterceptTouchEvent(false)
                        if (expand) onSwipeUp?.invoke()
                    }
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                val consumed = moved
                moved = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (consumed) return true
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun cancelTap(event: MotionEvent) {
        if (moved) return
        moved = true
        val cancel = MotionEvent.obtain(event)
        cancel.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val content = findViewById<ConstraintLayout>(R.id.connection_dock_content)
        if (content != null) {
            val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            val narrow = available / density < 360 || resources.configuration.fontScale > 1.25f
            val compact = !narrow && available / density < 390
            val nextMode = if (narrow) 2 else if (compact) 1 else 0
            val params = findViewById<LinearLayout>(R.id.connection_dock_body).layoutParams as LayoutParams
            params.width = available.coerceAtMost((600 * density).toInt())
            params.gravity = android.view.Gravity.CENTER_HORIZONTAL
            if (layoutMode != nextMode) {
                layoutMode = nextMode
                val horizontalPadding = ((if (compact) 6 else 8) * density).toInt()
                content.setPadding(horizontalPadding, content.paddingTop, horizontalPadding, content.paddingBottom)
                val gap = ((if (compact) 4 else 6) * density).toInt()
                val constraints = ConstraintSet().apply { clone(content) }
                if (narrow) {
                    constraints.connect(R.id.layout_status, ConstraintSet.END, R.id.fab_start_stop, ConstraintSet.START, gap)
                    constraints.clear(R.id.layout_status, ConstraintSet.BOTTOM)
                    constraints.clear(R.id.fab_start_stop, ConstraintSet.BOTTOM)
                    constraints.connect(R.id.layout_traffic, ConstraintSet.TOP, R.id.layout_status, ConstraintSet.BOTTOM, (12 * density).toInt())
                    constraints.connect(R.id.layout_traffic, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                    constraints.connect(R.id.layout_traffic, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                    constraints.constrainWidth(R.id.layout_traffic, 0)
                } else {
                    constraints.connect(R.id.layout_status, ConstraintSet.END, R.id.layout_traffic, ConstraintSet.START, gap)
                    constraints.connect(R.id.layout_status, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                    constraints.connect(R.id.fab_start_stop, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                    constraints.connect(R.id.layout_traffic, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                    constraints.clear(R.id.layout_traffic, ConstraintSet.START)
                    constraints.connect(R.id.layout_traffic, ConstraintSet.END, R.id.fab_start_stop, ConstraintSet.START, gap)
                    constraints.constrainWidth(R.id.layout_traffic, ConstraintSet.WRAP_CONTENT)
                }
                constraints.applyTo(content)
                // 头像与文本间距压到最小值，把省下来的宽度让给速率柱状图。
                // 节点名在 360dp 上几乎没有富余（自然宽 113dp / 可用 114dp），
                // 所以这里每挤 1dp 都必须靠实测复核，不能凭感觉缩。
                // 头像统一 40dp + 文本区左边距 4/6dp + fab 58dp：NATIVE 字体下参考名
                // "Guangzhou Home" 自然宽 113dp，连接态 360/393dp 必须各留出 ≥1dp 富余，
                // 否则名字折成两行（ConnectionDockLayoutTest 的单行断言会红）。
                findViewById<android.view.View>(R.id.tv_bottom_avatar_letter).layoutParams.apply {
                    width = (40 * density).toInt()
                    height = width
                }
                (findViewById<LinearLayout>(R.id.dock_identity_text).layoutParams as LinearLayout.LayoutParams).marginStart =
                    ((if (compact) 4 else 6) * density).toInt()
                listOf(R.id.dock_up_column, R.id.dock_down_column).forEach { id ->
                    val column = findViewById<LinearLayout>(id)
                    (column.layoutParams as LinearLayout.LayoutParams).apply {
                        // 列宽决定图表宽度（图是 match_parent），实测值：常屏 68dp、紧凑屏 60dp。
                        // 窄屏（<360dp 或大字体）整行只有两列，让它们平分，否则会退化成
                        // 「141dp 的列里钉着一个 52dp 的小图」，右边全是空白。
                        width = if (narrow) 0 else ((if (compact) 60 else 68) * density).toInt()
                        weight = if (narrow) 1f else 0f
                        if (id == R.id.dock_down_column) marginStart = ((if (compact) 4 else 6) * density).toInt()
                    }
                }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private class DockSurface(color: Int, private val density: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private val path = Path()

        override fun onBoundsChange(bounds: Rect) {
            val left = bounds.left.toFloat()
            val right = bounds.right.toFloat()
            val bottom = bounds.bottom.toFloat()
            val top = bounds.top + 12 * density
            val center = bounds.exactCenterX()
            val radius = 28 * density
            path.reset()
            path.moveTo(left, top + radius)
            path.quadTo(left, top, left + radius, top)
            path.lineTo(center - 38 * density, top)
            path.cubicTo(center - 25 * density, top, center - 23 * density, bounds.top.toFloat(), center - 12 * density, bounds.top.toFloat())
            path.lineTo(center + 12 * density, bounds.top.toFloat())
            path.cubicTo(center + 23 * density, bounds.top.toFloat(), center + 25 * density, top, center + 38 * density, top)
            path.lineTo(right - radius, top)
            path.quadTo(right, top, right, top + radius)
            path.lineTo(right, bottom)
            path.lineTo(left, bottom)
            path.close()
        }
        override fun draw(canvas: Canvas) = canvas.drawPath(path, paint)
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Android")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
        override fun getOutline(outline: Outline) {
            if (Build.VERSION.SDK_INT >= 30) outline.setPath(path)
            else outline.setRoundRect(bounds.left, bounds.top + (12 * density).toInt(), bounds.right, bounds.bottom, 28 * density)
        }
    }
}
