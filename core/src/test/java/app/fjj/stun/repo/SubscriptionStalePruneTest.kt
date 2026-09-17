package app.fjj.stun.repo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SubscriptionManager.staleSubscriptionNodes] 的陈旧判定：同步成功后，归属本订阅但新 payload
 * 里已不存在的节点应被移除；收藏与"当前选中"节点豁免（不能静默删掉用户星标/正在连的）。
 */
class SubscriptionStalePruneTest {

    private val sub = "https://airport.example/sub"
    private val other = "https://other.example/sub"

    private fun profile(id: String, source: String = sub, favorite: Boolean = false) =
        Profile(id = id, name = id, sourceSubscriptionUrl = source, favorite = favorite)

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
    fun `手动节点_来源为空_永不被任何订阅清理波及`() {
        val profiles = listOf(profile("manual", source = ""))
        val stale = SubscriptionManager.staleSubscriptionNodes(
            profiles, sub, keepIds = emptySet(), selectedId = null
        )
        assertEquals(emptyList<String>(), stale.map { it.id })
    }
}
