package app.fjj.stun.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * [AppUtils] 的单位格式化。
 *
 * 这两个函数原先各有一份手抄副本（桌面组件里那个只做到 MB），已经漂移成
 * "1GB/s 显示成 1024.0 MB/s"。这里把单位阶梯钉死，防止再退化。
 */
class AppUtilsFormatTest {

    @Test
    fun formatBytesPicksTheRightUnitLadder() {
        assertEquals("0 B", AppUtils.formatBytes(0))
        assertEquals("0 B", AppUtils.formatBytes(-1))          // 负数不能崩，也不能出现 "-1.0 B"
        assertEquals("512.0 B", AppUtils.formatBytes(512))
        assertEquals("1.0 KB", AppUtils.formatBytes(1024))
        assertEquals("1.0 MB", AppUtils.formatBytes(1024L * 1024))
        assertEquals("1.0 GB", AppUtils.formatBytes(1024L * 1024 * 1024))
        assertEquals("1.0 TB", AppUtils.formatBytes(1024L * 1024 * 1024 * 1024))
    }

    @Test
    fun formatBytesDoesNotOverflowAtTheTopOfTheLadder() {
        // 反事实：Ladder 顶端的 coerceIn(0, size-1) 是有意义的 —— 超过 1024 TB 时
        // 仍然要停在 TB，不能数组越界抛异常。
        val beyond = 1024L * 1024 * 1024 * 1024 * 4096
        assertEquals("4096.0 TB", AppUtils.formatBytes(beyond))
    }

    /**
     * 这条是本次修的**真 bug**：桌面组件那份手写副本只做到 MB，
     * 于是 1GB/s 会渲染成 "1024.0 MB/s" —— 同一个 App 里两处速率单位自相矛盾。
     */
    @Test
    fun formatSpeedReachesGigabytesPerSecondNotMegabytes() {
        val oneGbs = 1024L * 1024 * 1024
        val formatted = AppUtils.formatSpeed(oneGbs)
        assertEquals("1.0 GB/s", formatted)
        assertTrue(
            "不能出现 1024.0 MB/s 这种没换算的输出，实际是 $formatted",
            !formatted.contains("1024.0"),
        )
    }

    /**
     * 紧凑格式的阶梯必须与 [formatSpeed] **一致**。
     *
     * 组件宽度只有几十 dp，所以紧凑版省略空格与 `/s`，但单位字母若比完整版少一档
     * （旧副本那样），用户会在组件上看到 "1024.0M" 而在 App 内看到 "1.0 GB/s"。
     */
    @Test
    fun compactSpeedLadderMatchesTheFullOne() {
        assertEquals("0", AppUtils.formatSpeedCompact(0))
        assertEquals("0", AppUtils.formatSpeedCompact(-5))        // 负数同 0
        assertEquals("950", AppUtils.formatSpeedCompact(950))      // <1KB 不带小数，省字符
        assertEquals("1.0K", AppUtils.formatSpeedCompact(1024))
        assertEquals("1.0M", AppUtils.formatSpeedCompact(1024L * 1024))
        assertEquals("1.0G", AppUtils.formatSpeedCompact(1024L * 1024 * 1024))
        assertEquals("1.0T", AppUtils.formatSpeedCompact(1024L * 1024 * 1024 * 1024))
    }

    @Test
    fun compactSpeedAndFullSpeedAgreeOnTheUnitIndex() {
        // 逐档对照：同一数值，两者选中的单位必须是同一档（字母 vs 全称）。
        val fullUnits = listOf("B", "KB", "MB", "GB", "TB")
        val compactUnits = listOf("", "K", "M", "G", "T")
        var v = 1L
        for (i in fullUnits.indices) {
            val full = AppUtils.formatSpeed(v)
            val compact = AppUtils.formatSpeedCompact(v)
            assertEquals(
                "档位 $i (${v}B) 不一致：full=$full compact=$compact",
                full.contains(fullUnits[i]),
                compact.contains(compactUnits[i]),
            )
            if (i < fullUnits.size - 1) v *= 1024
        }
    }

    @Test
    fun formattingIsLocaleIndependent() {
        // ⚠️ 必须恒用 Locale.US：某些设备默认 locale 用逗号作小数点，
        // "1,5 MB" 粘到别处会出问题。旧实现已如此，这里钉住防止被"优化"掉。
        val prev = Locale.getDefault()
        try {
            for (l in listOf(Locale.GERMANY, Locale.FRANCE, Locale.CHINA)) {
                Locale.setDefault(l)
                assertEquals("1.5 MB", AppUtils.formatBytes(1024L * 1536))
                assertEquals("1.5M", AppUtils.formatSpeedCompact(1024L * 1536))
            }
        } finally {
            Locale.setDefault(prev)
        }
    }
}
