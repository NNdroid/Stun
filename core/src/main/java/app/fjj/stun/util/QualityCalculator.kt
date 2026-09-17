package app.fjj.stun.util

import android.graphics.Color

/**
 * 连接质量综合评分（星级 + 状态色）。
 *
 * 输入：当前延迟（复用 [StunRepository.latencyMs]，Go 真握手）、jitter（延迟样本相邻差均值）、
 * 出口稳定性（本会话出口 IP 变化次数）。输出 1–5 星 + 颜色。
 *
 * 颜色严格用 M3 状态语义色（见 MEMORY「M3 主题色」），**不使用** colorPrimary/colorTertiary 的橙/琥珀：
 * 绿 `#10B981` / 琥珀 `#F59E0B` / 红 `#EF4444`。
 */
data class QualityScore(val stars: Int, val colorInt: Int) {
    companion object {
        val unknown = QualityScore(0, Color.GRAY)
    }
}

object QualityCalculator {

    private const val COLOR_GOOD = 0xFF10B981.toInt()
    private const val COLOR_WARN = 0xFFF59E0B.toInt()
    private const val COLOR_BAD = 0xFFEF4444.toInt()

    /** 延迟分段阈值（ms）：≤80→5★，≤150→4★，≤300→3★，≤600→2★，其余→1★。 */
    fun compute(latencyMs: Long, jitterMs: Long, exitChanges: Int): QualityScore {
        if (latencyMs < 0) return QualityScore.unknown
        var stars = when {
            latencyMs <= 80 -> 5
            latencyMs <= 150 -> 4
            latencyMs <= 300 -> 3
            latencyMs <= 600 -> 2
            else -> 1
        }
        // jitter 扣分：>150ms 扣 2 星，>50ms 扣 1 星
        stars -= if (jitterMs > 150) 2 else if (jitterMs > 50) 1 else 0
        // 出口不稳定扣分：本会话变化 ≥3 次扣 1 星
        if (exitChanges >= 3) stars -= 1
        stars = stars.coerceIn(1, 5)
        val color = when (stars) {
            in 4..5 -> COLOR_GOOD
            in 2..3 -> COLOR_WARN
            else -> COLOR_BAD
        }
        return QualityScore(stars, color)
    }

    /** 由延迟样本序列算 jitter = 相邻差绝对值的均值（样本 <2 时返回 0）。 */
    fun jitter(samples: List<Long>): Long {
        if (samples.size < 2) return 0L
        var sum = 0L
        var prev = samples[0]
        for (i in 1 until samples.size) {
            sum += kotlin.math.abs(samples[i] - prev)
            prev = samples[i]
        }
        return sum / (samples.size - 1)
    }
}
