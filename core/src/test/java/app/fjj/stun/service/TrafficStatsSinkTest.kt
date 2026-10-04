package app.fjj.stun.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TrafficStatsSink.delta] —— 流量增量口径。
 *
 * 这是两种服务模式曾经**漂移**过的地方，而漂移的后果不是样式差异：
 * tproxy 侧写成 `else txTotal`，Go 计数器重置（进程内 proxy 重启 / 重连）时会把当前累计值
 * 整段当成"本次新增"写进库，用户看到流量凭空多出一截。
 */
class TrafficStatsSinkTest {

    @Test
    fun computesPlainDifference() {
        assertEquals(100L, TrafficStatsSink.delta(0L, 100L))
        assertEquals(40L, TrafficStatsSink.delta(100L, 140L))
        assertEquals(0L, TrafficStatsSink.delta(100L, 100L))
    }

    @Test
    fun counterResetYieldsZeroNotTheWholeTotal() {
        // 反事实：这里若返回 current，一次计数器重置就会把整个累计值重复写库。
        assertEquals(0L, TrafficStatsSink.delta(5_000L, 12L))
    }

    @Test
    fun neverReturnsNegative() {
        assertEquals(0L, TrafficStatsSink.delta(1_000_000L, 0L))
    }

    // ── step()：增量与基准必须在同一个函数里产出 ──────────────────

    @Test
    fun stepReturnsBothDeltaAndNewBase() {
        val s = TrafficStatsSink.step(prevTx = 100L, prevRx = 200L, newTx = 150L, newRx = 260L)
        assertEquals(50L, s.dTx)
        assertEquals(60L, s.dRx)
        assertEquals(150L, s.baseTx)
        assertEquals(260L, s.baseRx)
    }

    @Test
    fun stepOnCounterResetAdvancesBaseButYieldsZeroDelta() {
        // 这是 flushPending 存在的场景：Go 侧计数器被重置。
        // 增量必须为 0（否则整个累计值被当成新增写库），但基准**必须**跟着推进到新值 ——
        // 否则下一帧又拿旧基准去比，差值会一直算错。反事实：baseTx 若仍停在旧值，本断言即失败。
        val s = TrafficStatsSink.step(prevTx = 5_000L, prevRx = 7_000L, newTx = 12L, newRx = 30L)
        assertEquals(0L, s.dTx)
        assertEquals(0L, s.dRx)
        assertEquals(12L, s.baseTx)
        assertEquals(30L, s.baseRx)
    }

    @Test
    fun stepChainedFramesSumToTotalWithoutDoubleCounting() {
        // 逐帧累加 0→100→250→250 必须等于 250；重复帧（无新流量）贡献 0。
        var base = 0L
        var sum = 0L
        for (total in listOf(100L, 250L, 250L)) {
            val s = TrafficStatsSink.step(base, 0L, total, 0L)
            sum += s.dTx
            base = s.baseTx
        }
        assertEquals(250L, sum)
    }

    @Test
    fun flushPendingWouldNotDoubleCountWhatIngestAlreadyWrote() {
        // 收尾补齐只能补"未落库"的那一段：ingest 已推进基准到 250，
        // flushPending 再传 250 时 delta 必须是 0（否则这 250 被加第二遍）。
        val afterIngest = TrafficStatsSink.step(0L, 0L, 250L, 250L)
        assertEquals(250L, afterIngest.dTx)

        val onFlush = TrafficStatsSink.step(afterIngest.baseTx, afterIngest.baseRx, 250L, 250L)
        assertEquals(0L, onFlush.dTx)
        assertEquals(0L, onFlush.dRx)
    }

    @Test
    fun flushPendingStillPicksUpTheTailAfterLastIngest() {
        // 最后一帧 250 之后又跑了 30 字节才停服：这 30 必须被补齐，不能丢。
        val afterIngest = TrafficStatsSink.step(0L, 0L, 250L, 250L)
        val onFlush = TrafficStatsSink.step(afterIngest.baseTx, afterIngest.baseRx, 280L, 250L)
        assertEquals(30L, onFlush.dTx)
        assertEquals(0L, onFlush.dRx)
    }
}
