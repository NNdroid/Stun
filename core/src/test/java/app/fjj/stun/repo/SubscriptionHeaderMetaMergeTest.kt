package app.fjj.stun.repo

import app.fjj.stun.repo.SubscriptionManager.SubEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 响应头解析出的订阅元信息（名称 / 主页 / 自动更新间隔）都是**可选**的：
 * `content-disposition`、`profile-web-page-url`、`profile-update-interval` 服务端未必每次都回。
 *
 * 这里锁住「缺字段时沿用已存值」的合并语义。改造前是无条件覆盖，
 * 一次少带头的同步就会把订阅名和主页按钮永久抹掉，且用户无从感知。
 */
class SubscriptionHeaderMetaMergeTest {

    private val stored = SubEntry(
        url = "https://sub.example.com/a",
        pin = "1234",
        name = "机场",
        homePage = "https://example.com",
        updateIntervalHours = 24
    )

    @Test
    fun blankHeaderValuesKeepStoredMeta() {
        // 本次响应一个字段都没带 → 三条全部沿用
        assertEquals(stored, SubscriptionManager.mergeHeaderMeta(stored, "", "", 0))
    }

    @Test
    fun presentHeaderValuesOverwrite() {
        val merged = SubscriptionManager.mergeHeaderMeta(stored, "New", "https://new.example", 6)
        assertEquals("New", merged.name)
        assertEquals("https://new.example", merged.homePage)
        assertEquals(6, merged.updateIntervalHours)
    }

    @Test
    fun onlyTouchedFieldsChange() {
        val merged = SubscriptionManager.mergeHeaderMeta(stored, "New", "", 0)
        assertEquals("New", merged.name)
        assertEquals(stored.homePage, merged.homePage)
        assertEquals(stored.updateIntervalHours, merged.updateIntervalHours)
    }

    @Test
    fun homePageOnlyDoesNotClearName() {
        // 只有主页更新时，名称与其他字段必须原封不动
        val merged = SubscriptionManager.mergeHeaderMeta(stored, "", "https://other.example", 0)
        assertEquals(stored.name, merged.name)
        assertEquals("https://other.example", merged.homePage)
        assertEquals(stored.updateIntervalHours, merged.updateIntervalHours)
    }

    @Test
    fun intervalOnlyAcceptsPositiveValues() {
        // 0 视为"未指定"，不能把已存的自动更新间隔打回兜底
        assertEquals(24, SubscriptionManager.mergeHeaderMeta(stored, "", "", 0).updateIntervalHours)
        assertEquals(24, SubscriptionManager.mergeHeaderMeta(stored, "", "", -1).updateIntervalHours)
        assertEquals(3, SubscriptionManager.mergeHeaderMeta(stored, "", "", 3).updateIntervalHours)
    }

    @Test
    fun urlAndPinAreNeverTouched() {
        // 合并只负责响应头元信息，绝不能碰订阅链接与 PIN
        val merged = SubscriptionManager.mergeHeaderMeta(stored, "N", "https://h", 12)
        assertEquals(stored.url, merged.url)
        assertEquals(stored.pin, merged.pin)
    }

    @Test
    fun emptyStoredEntryTakesIncomingValues() {
        val merged = SubscriptionManager.mergeHeaderMeta(SubEntry(url = "https://x/y"), "N", "https://h", 12)
        assertEquals("N", merged.name)
        assertEquals("https://h", merged.homePage)
        assertEquals(12, merged.updateIntervalHours)
    }
}
