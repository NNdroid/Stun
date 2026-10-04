package app.fjj.stun.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SpeedRefreshThrottle] 的时间语义。
 *
 * 存在的理由是"两种服务模式必须同频同开关"：改前 VPN 是 2000ms 且读
 * `getShowNotificationSpeed`，tproxy 是 1000ms 且不读 —— 同一个设置在两种模式下表现不同。
 * 间隔值一旦在两个服务里各留一份字面量，这种漂移会重新长回来，所以钉在这里。
 */
class SpeedRefreshThrottleTest {

    /** 用真实量级的墙钟值，不要用 0 —— 首调判定是"距上次（0）够不够间隔"。 */
    private val base = 1_700_000_000_000L

    @Test
    fun firstCallAlwaysPasses() {
        assertTrue(SpeedRefreshThrottle(intervalMs = 2000L).shouldRefresh(nowMs = base))
    }

    @Test
    fun suppressesCallsInsideTheInterval() {
        val throttle = SpeedRefreshThrottle(intervalMs = 2000L)
        assertTrue(throttle.shouldRefresh(nowMs = base))
        assertFalse(throttle.shouldRefresh(nowMs = base + 1))
        assertFalse(throttle.shouldRefresh(nowMs = base + 1_999))
    }

    @Test
    fun passesAgainExactlyAtTheBoundary() {
        val throttle = SpeedRefreshThrottle(intervalMs = 2000L)
        assertTrue(throttle.shouldRefresh(nowMs = base))
        assertTrue("恰好等于间隔必须放行（只有落在区间内才拦）", throttle.shouldRefresh(nowMs = base + 2000))
        assertFalse(throttle.shouldRefresh(nowMs = base + 3999))
        assertTrue(throttle.shouldRefresh(nowMs = base + 4000))
    }

    @Test
    fun advancesTimestampOnlyWhenPassing() {
        // 时间戳只在放行时推进。若改成每次调用都推进，密集回调会把间隔算成"距上次调用"，
        // 节流退化成永真 —— 所以这两条要一起钉。
        val throttle = SpeedRefreshThrottle(intervalMs = 2000L)
        assertTrue(throttle.shouldRefresh(nowMs = base))
        assertFalse(throttle.shouldRefresh(nowMs = base + 500))
        assertTrue(throttle.shouldRefresh(nowMs = base + 2000))
    }

    @Test
    fun toleratesWallClockGoingBackwards() {
        // 墙钟被往回调（NTP 校时 / 用户改时间）时不能变成"永久不再刷新"：
        // 若按 `delta < intervalMs` 判，负 delta 会把通知按死到墙钟追上旧时间戳为止。
        val throttle = SpeedRefreshThrottle(intervalMs = 2000L)
        assertTrue(throttle.shouldRefresh(nowMs = base))
        assertTrue("时钟回拨后应立即放行一次", throttle.shouldRefresh(nowMs = base - 60_000))
        assertFalse(throttle.shouldRefresh(nowMs = base - 60_000 + 1_999))
    }

    @Test
    fun defaultIntervalIsTwoSeconds() {
        assertEquals(2000L, SpeedRefreshThrottle.DEFAULT_INTERVAL_MS)
    }

    @Test
    fun twoInstancesDoNotShareState() {
        // 每个服务各持一个实例；误用单例会让两个模式互相节流。
        val vpn = SpeedRefreshThrottle()
        val tproxy = SpeedRefreshThrottle()
        assertTrue(vpn.shouldRefresh(nowMs = base))
        assertTrue(tproxy.shouldRefresh(nowMs = base))
    }
}
