package app.fjj.stun.repo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SubscriptionManager.staleSubscriptionNodes] 的陈旧判定：同步成功后，归属本订阅但新 payload
 * 里已不存在的节点应被移除；收藏与"当前选中"节点豁免（不能静默删掉用户星标/正在连的）。
 *
 * 关联键是 `subId`（2026-09-22 由 URL 换成 subId 之后），所以这里也顺带钉住
 * 「空 subId = 手动节点，永不被任何订阅清理波及」这条边界 —— 它是"删订阅顺带删节点"
 * 变成清库的唯一防线。
 */
class SubscriptionStalePruneTest {

    private val sub = "sub-uuid-airport"
    private val other = "sub-uuid-other"

    private fun profile(id: String, source: String = sub, favorite: Boolean = false) =
        Profile(id = id, name = id, subId = source, favorite = favorite)

    @Test
    fun `payload 里消失的本订阅节点被判定为陈旧`() {
        val profiles = listOf(profile("keep"), profile("gone"), profile("also-gone"))
        val stale = SubscriptionManager.staleSubscriptionNodes(
            profiles, sub, keepIds = setOf("keep"), selectedId = null
        )
        assertEquals(listOf("gone", "also-gone"), stale.map { it.id })
    }

    @Test
    fun `收藏与当前选中节点豁免_其它订阅的节点不受影响`() {
        val profiles = listOf(
            profile("fav", favorite = true),        // 收藏 → 豁免
            profile("selected"),                    // 当前选中 → 豁免
            profile("other-sub", source = other),   // 别的订阅 → 不动
            profile("plain"),                       // 普通陈旧 → 删
        )
        val stale = SubscriptionManager.staleSubscriptionNodes(
            profiles, sub, keepIds = emptySet(), selectedId = "selected"
        )
        assertEquals(listOf("plain"), stale.map { it.id })
    }

    @Test
    fun `手动节点_subId 为空_永不被任何订阅清理波及`() {
        val profiles = listOf(profile("manual", source = ""))
        val stale = SubscriptionManager.staleSubscriptionNodes(
            profiles, sub, keepIds = emptySet(), selectedId = null
        )
        assertEquals(emptyList<String>(), stale.map { it.id })
    }

    /**
     * 反事实：若"本订阅"的 subId 恰好是空串（调用方传错），绝**不能**把全部手动节点
     * 判成它的陈旧节点 —— 那一次同步就会把用户手加的节点全删了。
     */
    @Test
    fun `以空 subId 作为本订阅时_不得命中任何手动节点`() {
        val profiles = listOf(profile("manual-a", source = ""), profile("manual-b", source = ""))
        val stale = SubscriptionManager.staleSubscriptionNodes(
            profiles, subId = "", keepIds = emptySet(), selectedId = null
        )
        assertEquals(emptyList<String>(), stale.map { it.id })
    }
}
