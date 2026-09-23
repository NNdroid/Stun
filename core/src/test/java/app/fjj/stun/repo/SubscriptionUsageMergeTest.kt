package app.fjj.stun.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SubscriptionManager.mergeUsageSnapshots] 的纯 JVM 单测：云备份恢复时流量记录的合并规则。
 *
 * 这段最容易被写错的是**日/月基线**。基线是"本周期从这个 used 值开始算"的锚点，跟日期绑定：
 * 跨天/跨设备恢复时若照搬备份里的旧锚点，"今日新增 / 本月新增"会变成两个周期之间的差额
 * （甚至是负数）。所以日期对不上时必须重置为当前 used。手点界面试不出这种跨天场景，
 * 只能靠单测钉住。
 *
 * 身份键是 **subId**（2026-09-22 由 URL 换成 subId 之后）：URL 是可变的，拿它当键会让
 * "换个订阅域名"把用量历史接丢。`url` 仍然随快照走，但只用于旧备份的降级认领。
 *
 * 刻意不使用 Robolectric —— 被测函数不碰 Context / 库。
 */
class SubscriptionUsageMergeTest {

    private val subId = "sub-uuid-airport"
    private val url = "https://airport.example/sub"
    private val today = "2026-09-17"
    private val thisMonth = "2026-09"

    private fun snapshot(
        subId: String = this.subId,
        url: String = this.url,
        upload: Long = 100,
        download: Long = 400,
        updatedAt: Long = 1_000,
        dayDate: String = today,
        dayBase: Long = 300,
        monthDate: String = thisMonth,
        monthBase: Long = 200,
        history: List<SubscriptionManager.UsagePoint> = emptyList()
    ) = SubscriptionManager.UsageSnapshot(
        subId = subId, url = url, upload = upload, download = download, total = 10_000, expire = 0,
        updatedAt = updatedAt, dayDate = dayDate, dayBase = dayBase,
        monthDate = monthDate, monthBase = monthBase, history = history
    )

    @Test
    fun `本地更新过时备份不覆盖_保留本地的用量与基线`() {
        val local = mapOf(subId to snapshot(upload = 900, download = 900, updatedAt = 5_000, dayBase = 1_500))
        val merged = SubscriptionManager.mergeUsageSnapshots(
            local, listOf(snapshot(upload = 1, download = 1, updatedAt = 1_000)), today, thisMonth
        )
        val kept = merged.getValue(subId)
        assertEquals(1_800, kept.upload + kept.download)
        assertEquals(1_500, kept.dayBase)
    }

    @Test
    fun `备份更新时覆盖本地`() {
        val local = mapOf(subId to snapshot(upload = 10, download = 10, updatedAt = 1_000))
        val merged = SubscriptionManager.mergeUsageSnapshots(
            local, listOf(snapshot(upload = 900, download = 600, updatedAt = 7_000)), today, thisMonth
        )
        assertEquals(1_500, merged.getValue(subId).let { it.upload + it.download })
        assertEquals(7_000, merged.getValue(subId).updatedAt)
    }

    @Test
    fun `同日恢复沿用备份基线_今日新增不会丢`() {
        // 备份里：已用 500，今日基线 300 ⇒ 今天涨了 200，这个信息要保住
        val merged = SubscriptionManager.mergeUsageSnapshots(
            emptyMap(), listOf(snapshot(upload = 100, download = 400, dayBase = 300)), today, thisMonth
        )
        assertEquals(300, merged.getValue(subId).dayBase)
    }

    @Test
    fun `跨天恢复把日基线重置为当前已用_今日新增归零`() {
        val merged = SubscriptionManager.mergeUsageSnapshots(
            emptyMap(),
            listOf(snapshot(upload = 100, download = 400, dayDate = "2026-09-10", dayBase = 50)),
            today, thisMonth
        )
        // 若不重置，今日新增会算成 500 - 50 = 450（跨了一周的差额）
        assertEquals(500, merged.getValue(subId).dayBase)
    }

    @Test
    fun `跨月恢复把月基线重置为当前已用`() {
        val merged = SubscriptionManager.mergeUsageSnapshots(
            emptyMap(),
            listOf(snapshot(upload = 100, download = 400, monthDate = "2026-08", monthBase = 10)),
            today, thisMonth
        )
        assertEquals(500, merged.getValue(subId).monthBase)
    }

    @Test
    fun `日与月各自独立判定_同月但跨天时只重置日基线`() {
        val merged = SubscriptionManager.mergeUsageSnapshots(
            emptyMap(),
            listOf(
                snapshot(
                    upload = 100, download = 400,
                    dayDate = "2026-09-10", dayBase = 50,       // 跨天 → 重置为 500
                    monthDate = thisMonth, monthBase = 320      // 同月 → 沿用
                )
            ),
            today, thisMonth
        )
        assertEquals(500, merged.getValue(subId).dayBase)
        assertEquals(320, merged.getValue(subId).monthBase)
    }

    @Test
    fun `新 subId 并入_本地已有的其它订阅原样保留`() {
        val other = "sub-uuid-other"
        val local = mapOf(other to snapshot(subId = other, url = "https://other.example/sub", upload = 1, download = 2, updatedAt = 9))
        val merged = SubscriptionManager.mergeUsageSnapshots(
            local, listOf(snapshot()), today, thisMonth
        )
        assertEquals(setOf(other, subId), merged.keys)
        assertEquals(3, merged.getValue(other).let { it.upload + it.download })
    }

    /**
     * 反事实：**同一个 subId、URL 变了**（用户换了订阅域名）时必须合并到同一条 ——
     * 这正是重构前的漂移：那时键是 url，改完域名备份里的记录会在本地另起一条，
     * 老的那条永远不再更新。
     */
    @Test
    fun `同一 subId 但 url 变了_仍并入同一条`() {
        val local = mapOf(subId to snapshot(url = "https://old.example/sub", upload = 10, download = 10, updatedAt = 1_000))
        val merged = SubscriptionManager.mergeUsageSnapshots(
            local, listOf(snapshot(url = "https://new.example/sub", upload = 900, download = 600, updatedAt = 7_000)),
            today, thisMonth
        )
        assertEquals(setOf(subId), merged.keys)
        assertEquals(1_500, merged.getValue(subId).let { it.upload + it.download })
    }

    /**
     * subId 为空的快照一律丢弃：它跟任何一行都对不上。旧备份（只有 url）必须先由
     * [SubscriptionManager.importUsageSnapshot] 按 URL 认领出 subId 才进得来。
     */
    @Test
    fun `subId 为空的条目被丢弃`() {
        val merged = SubscriptionManager.mergeUsageSnapshots(
            emptyMap(), listOf(snapshot(subId = "", url = ""), snapshot()), today, thisMonth
        )
        assertEquals(setOf(subId), merged.keys)
    }

    @Test
    fun `趋势点超长时只保留最近 60 个`() {
        // 上限 60：USAGE_HISTORY_MAX（见 SubscriptionManager），备份文件可以被手工改大
        val points = (1..100).map { SubscriptionManager.UsagePoint(it.toLong(), it.toLong()) }
        val merged = SubscriptionManager.mergeUsageSnapshots(
            emptyMap(), listOf(snapshot(history = points)), today, thisMonth
        )
        val history = merged.getValue(subId).history
        assertEquals(60, history.size)
        assertEquals(100L, history.last().t)
        assertTrue(history.first().t > 1L)
    }
}
