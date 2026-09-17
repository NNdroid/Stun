package app.fjj.stun.ui.view

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * 速率柱状图绘制。被底栏与连接详情面板共用（[TrafficBarChartView] 是它唯一的宿主）。
 *
 * 视觉对齐设计稿：圆角柱、柔和的纵向渐变。明暗只是装饰，不编码任何数据含义——
 * 柱高始终是真实样本值，零值只画一条淡基线，绝不伪造柱高。
 */
class TrafficChartRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * @param slots 参与绘制的槽位数。槽位越少柱子越粗——底栏那种窄图必须降槽位，
     *              否则 15 根细柱挤在 70dp 里看起来像噪点。
     * @param unitLabel 坐标系单位（如 "Mbps"）。为 null 时坐标轴沿用「9M / 4M / 0」
     *              这种自带量级的刻度；非 null 时改用「10 / 5 / 0 + 左上角单位」的写法。
     *              两种写法不能混用：同一根轴上「Mbps」和「9M」会重复表达量级。
     * @param unitDivisor 样本值 → [unitLabel] 的换算除数。样本按 bps 传进来时，
     *              配 "Mbps" 要传 1e6（十进制兆，与界面上 `"%.1f Mbps"` 的口径一致）。
     */
    fun draw(
        canvas: Canvas,
        width: Float,
        height: Float,
        samples: List<Long>,
        color: Int,
        density: Float,
        axes: Boolean = false,
        labelColor: Int = Color.GRAY,
        fontScale: Float = 1f,
        slots: Int = 15,
        unitLabel: String? = null,
        unitDivisor: Double = 1.0
    ) {
        if (width <= 0 || height <= 0) return
        val slotCount = slots.coerceAtLeast(1)
        val values = samples.takeLast(slotCount)
        val rawMax = (values.maxOrNull() ?: 0L).toDouble().coerceAtLeast(1.0)
        val ceiling = if (unitLabel != null) {
            // 没有数据时按「一个单位」起跳。若老实按真实值（≈0）算，
            // 三根刻度会一起塌成 "0 / 0 / 0"，面板一打开就像坏了。
            val scaledMax = if (values.any { it > 0L }) rawMax / unitDivisor else DEFAULT_UNIT_SPAN
            niceCeiling(scaledMax) * unitDivisor
        } else {
            val magnitude = 10.0.pow(floor(log10(rawMax)))
            ceil(rawMax / magnitude) * magnitude
        }
        val leftInset = if (axes) 34f * density * fontScale else 0f
        val bottomInset = if (axes) 20f * density * fontScale else 0f
        // 带单位时要在刻度上方多留一行放单位标签，否则它会压在最高的那根刻度上
        val unitReserve = if (axes && unitLabel != null) 13f * density * fontScale else 0f
        val top = if (axes) 8f * density + unitReserve else 2f * density
        val bottom = height - bottomInset
        val plotWidth = (width - leftInset - density * 2).coerceAtLeast(1f)
        val plotHeight = (bottom - top).coerceAtLeast(1f)
        paint.shader = null
        if (axes) {
            paint.textSize = 9f * density * fontScale
            paint.textAlign = Paint.Align.RIGHT
            if (unitLabel != null) {
                paint.color = labelColor
                canvas.drawText(unitLabel, leftInset - 5 * density, 11f * density * fontScale, paint)
            }
            for (tick in 0..2) {
                val y = bottom - tick * plotHeight / 2f
                paint.color = alpha(labelColor, 22)
                paint.strokeWidth = density * 0.5f
                canvas.drawLine(leftInset, y, width, y, paint)
                paint.color = labelColor
                val value = ceiling * tick / 2
                val text = if (unitLabel != null) bareLabel(value / unitDivisor) else axisLabel(value)
                canvas.drawText(text, leftInset - 5 * density, y + 3 * density, paint)
            }
            listOf(slotCount, slotCount * 2 / 3, slotCount / 3, 0).forEachIndexed { i, seconds ->
                paint.textAlign = when (i) { 0 -> Paint.Align.LEFT; 3 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
                canvas.drawText("${seconds}s", leftInset + plotWidth * i / 3, height - 2 * density, paint)
            }
        }
        val slot = plotWidth / slotCount
        val barWidth = slot * 0.62f
        val radius = (barWidth / 2f).coerceAtMost(2.5f * density)
        val offset = slotCount - values.size
        val lightTone = lighten(color, 0.58f)
        values.forEachIndexed { i, sample ->
            val x = leftInset + (offset + i) * slot + (slot - barWidth) / 2f
            if (sample <= 0) {
                // 零速率只画一条淡基线，绝不伪造柱高
                paint.shader = null
                paint.color = alpha(color, 40)
                canvas.drawRoundRect(x, bottom - density, x + barWidth, bottom, radius, radius, paint)
            } else {
                val barHeight = (sample.coerceAtLeast(0) / ceiling * plotHeight).toFloat()
                val y = bottom - barHeight.coerceAtLeast(2f * density)
                paint.shader = LinearGradient(x, y, x, bottom, lightTone, color, Shader.TileMode.CLAMP)
                paint.alpha = 255
                canvas.drawRoundRect(x, y, x + barWidth, bottom, radius, radius, paint)
            }
        }
        paint.shader = null
        paint.alpha = 255
    }

    private fun axisLabel(value: Double): String = when {
        value >= 1_073_741_824 -> String.format(Locale.ROOT, "%.1fG", value / 1_073_741_824)
        value >= 1_048_576 -> String.format(Locale.ROOT, "%.0fM", value / 1_048_576)
        value >= 1_024 -> String.format(Locale.ROOT, "%.0fK", value / 1_024)
        else -> value.toLong().toString()
    }

    /**
     * 轴上界抬到「整」数刻度：1 / 2 / 2.5 / 5 / 10 × 10^n。
     * 纯 10 的幂不够用 —— 峰值 8.4 会得到 9 这个上界，中线正好落在 4.5，
     * 刻度就出现了「4.5」这种既不像整数也不像半格的数。
     */
    private fun niceCeiling(scaledMax: Double): Double {
        if (scaledMax <= 0.0) return 1.0
        val magnitude = 10.0.pow(floor(log10(scaledMax)))
        val normalized = scaledMax / magnitude
        return (NICE_STEPS.firstOrNull { it >= normalized } ?: 10.0) * magnitude
    }

    /** 带单位标签时的刻度写法：只写数字，量级交给左上角的单位。 */
    private fun bareLabel(value: Double): String {
        val rounded = Math.round(value * 100.0) / 100.0
        return if (rounded == floor(rounded)) rounded.toLong().toString()
        else String.format(Locale.ROOT, "%.1f", rounded)
    }

    private fun alpha(color: Int, alpha: Int) = (color and 0x00ffffff) or (alpha shl 24)

    private fun lighten(color: Int, fraction: Float) = Color.rgb(
        (Color.red(color) + (255 - Color.red(color)) * fraction).toInt(),
        (Color.green(color) + (255 - Color.green(color)) * fraction).toInt(),
        (Color.blue(color) + (255 - Color.blue(color)) * fraction).toInt()
    )

    private companion object {
        val NICE_STEPS = listOf(1.0, 2.0, 2.5, 5.0, 10.0)

        /** 空图（还没有任何样本）时的纵轴跨度，单位是 [unitLabel]。 */
        const val DEFAULT_UNIT_SPAN = 1.0
    }
}
