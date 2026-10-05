package app.fjj.stun.util

import android.app.Application
import android.content.Context
import app.fjj.stun.repo.SettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 出口信息的设备态落盘（[ExitInfoStore]）。
 *
 * 盯的是三件容易出事的地方：
 * 1. **v6 是加性列** —— 老版本只写三个 key，读回来必须是"没有第二族"而不是崩或读成空串以外的怪东西；
 * 2. **变更检测要把 v6 算进去** —— 只比 v4 的话，IPv6 换了（v6 前缀轮换很常见）会被判成"没变"，
 *    面板与小组件就一直挂着旧地址；
 * 3. **清空要连 v6 一起清** —— 漏一个 key 就会在下次连接时把上一次的 IPv6 显示出来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExitInfoStoreTest {

    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ExitInfoStore.clear(context)
    }

    @Test fun savesAndReadsBothAddressFamilies() {
        assertTrue(ExitInfoStore.save(context, "203.0.113.8", "🇸🇬 Singapore", "2001:db8::8"))
        val info = ExitInfoStore.read(context)!!
        assertEquals("203.0.113.8", info.ip)
        assertEquals("🇸🇬 Singapore", info.location)
        assertEquals("2001:db8::8", info.ipv6)
        assertEquals("2001:db8::8", info.ipv6OrNull)
        assertEquals("203.0.113.8 · 🇸🇬 Singapore\n2001:db8::8", info.displayTextDual)
    }

    @Test fun singleStackWriteKeepsTheOldSingleLineShape() {
        assertTrue(ExitInfoStore.save(context, "203.0.113.8", "🇸🇬 Singapore"))
        val info = ExitInfoStore.read(context)!!
        assertEquals("", info.ipv6)
        assertNull(info.ipv6OrNull)
        // 反事实：没有第二族时不能多出一个空行（那看起来就像"缺了个地址"）。
        assertEquals(info.displayText, info.displayTextDual)
        assertFalse("no stray newline in the one-line form", info.displayTextDual.contains('\n'))
    }

    @Test fun counterpartChangeAloneCountsAsAChange() {
        ExitInfoStore.save(context, "203.0.113.8", "🇸🇬 Singapore", "2001:db8::8")
        // v4 与位置都没动，只有 v6 变了（IPv6 前缀轮换）。漏判会让面板一直显示旧地址。
        assertTrue("ipv6-only change must be reported as changed",
            ExitInfoStore.save(context, "203.0.113.8", "🇸🇬 Singapore", "2001:db8::9"))
        assertEquals("2001:db8::9", ExitInfoStore.read(context)!!.ipv6)
    }

    @Test fun identicalWriteIsSuppressed() {
        assertTrue(ExitInfoStore.save(context, "203.0.113.8", "🇸🇬 Singapore", "2001:db8::8"))
        // 状态机抖动不该带来无谓的 APPWIDGET_UPDATE 广播。
        assertFalse(ExitInfoStore.save(context, "203.0.113.8", "🇸🇬 Singapore", "2001:db8::8"))
    }

    @Test fun clearRemovesTheCounterpartToo() {
        ExitInfoStore.save(context, "203.0.113.8", "🇸🇬 Singapore", "2001:db8::8")
        assertTrue(ExitInfoStore.clear(context))
        assertNull(ExitInfoStore.read(context))
        // 反事实：只清了 v4 的话，这里会读出一个"有位置、有时间、就是没地址"的残骸。
        assertNull("a leftover key means clear() missed one", SettingsManagerProbe.firstExitKey(context))
    }

    @Test fun readToleratesLegacyRowsWithoutTheIpv6Key() {
        // 模拟老版本写下的三键记录（升级前用户设备上就是这个状态）。
        SettingsManager.deviceState(context).edit()
            .putString("exit_info_ip", "203.0.113.8")
            .putString("exit_info_location", "🇸🇬 Singapore")
            .putLong("exit_info_updated_at", 1234L)
            .commit()
        val info = ExitInfoStore.read(context)!!
        assertEquals("203.0.113.8", info.ip)
        assertEquals("🇸🇬 Singapore", info.location)
        assertEquals("", info.ipv6)
        assertEquals(1234L, info.updatedAt)
    }

    @Test fun allBlankWriteIsTreatedAsClear() {
        ExitInfoStore.save(context, "203.0.113.8", "🇸🇬 Singapore", "2001:db8::8")
        // 全空 ⇒ 断开态。走 clear 而不是写一条空记录，否则小组件会把空行当"有出口"显示。
        assertTrue(ExitInfoStore.save(context, "", "", ""))
        assertNull(ExitInfoStore.read(context))
    }

    @Test fun whitespaceIsTrimmedOnEveryField() {
        ExitInfoStore.save(context, "  203.0.113.8\n", " 🇸🇬 Singapore ", "  2001:db8::8  ")
        val info = ExitInfoStore.read(context)!!
        assertEquals("203.0.113.8", info.ip)
        assertEquals("🇸🇬 Singapore", info.location)
        assertEquals("2001:db8::8", info.ipv6)
    }
}

/** 返回设备态库里仍残留的第一个出口 key；全清干净时返回 null。 */
private object SettingsManagerProbe {
    fun firstExitKey(context: Context): String? {
        val prefs = app.fjj.stun.repo.SettingsManager.deviceState(context)
        return listOf("exit_info_ip", "exit_info_ipv6", "exit_info_location", "exit_info_updated_at")
            .firstOrNull { prefs.contains(it) }
    }
}
