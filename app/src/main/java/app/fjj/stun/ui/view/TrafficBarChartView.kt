package app.fjj.stun.ui.view

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import app.fjj.stun.R

/**
 * 速率柱状图视图（底栏与连接详情面板共用）。
 * 布局只负责给尺寸，数据与配色由 [submitSamples] 推进来。
 */
class TrafficBarChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val renderer = TrafficChartRenderer()
    private var samples: List<Long> = emptyList()
    private var chartColor = context.getColor(R.color.widget_up_accent)

    /**
     * 参与绘制的样本数。值越小柱子越粗——底栏图表窄，给 8；面板宽，给 10。
     * 改这个值只影响"显示最近多少秒"，不影响数据来源。
     */
    var slots: Int = 15
        set(value) {
            if (field == value) return
            field = value
            samples = emptyList()
            invalidate()
        }

    var showAxes: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }

    /**
     * 纵轴单位标签（如 "Mbps"），配合 [unitDivisor] 使用。
     * 为 null（默认）时纵轴刻度自带量级（"9M"），与连接详情面板、订阅趋势图一致；
     * 带宽测速面板传 "Mbps"，刻度就变成「整数 + 左上角单位」的写法。
     */
    var unitLabel: String? = null
        set(value) { if (field != value) { field = value; invalidate() } }

    /** 样本值 → [unitLabel] 的换算除数（样本按 bps 传时，配 "Mbps" 传 1e6）。 */
    var unitDivisor: Double = 1.0
        set(value) { if (field != value) { field = value; invalidate() } }

    fun submitSamples(values: List<Long>, color: Int) {
        val next = values.takeLast(slots)
        if (next == samples && chartColor == color) return
        samples = next
        chartColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        renderer.draw(
            canvas,
            (width - paddingLeft - paddingRight).toFloat(),
            (height - paddingTop - paddingBottom).toFloat(),
            samples,
            chartColor,
            resources.displayMetrics.density,
            showAxes,
            context.getColor(R.color.widget_text_secondary),
            resources.configuration.fontScale,
            slots,
            unitLabel,
            unitDivisor
        )
        canvas.restore()
    }
}
