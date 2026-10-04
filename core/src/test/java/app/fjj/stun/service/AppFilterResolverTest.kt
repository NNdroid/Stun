package app.fjj.stun.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AppFilterResolver] 的取值契约 —— VPN 与 tproxy 两种模式**共用**这一份语义。
 *
 * 这里最容易被"顺手优化"坏的是 [AppFilterResolver.resolve] 里两个刻意保留的旧行为：
 *
 *  - **空列表 = 不过滤**。VPN 侧靠 `if (filterApps.isNotBlank())` 短路，tproxy 侧若照抄成
 *    "白名单模式 + 空列表"，shell 会因为 `PROXY_APPS_LIST` 为空而给链尾补一条 `-j ACCEPT`，
 *    结果是**所有应用都不走代理** —— 与用户"没配任何东西 = 全部照常代理"的预期正好相反。
 *  - **非 1 的 mode 一律当黑名单**（旧实现是 `if (mode == 1) 允许 else 禁止`）。
 *
 * 两者都不会编译报错，只在真机上表现为"网络行为变了"，所以逐条钉死。
 */
class AppFilterResolverTest {

    @Test
    fun parsesCommaSeparatedListTrimmingBlanks() {
        assertEquals(
            listOf("com.a", "com.b", "com.c"),
            AppFilterResolver.parsePackageList(" com.a , com.b,\ncom.c ")
        )
    }

    @Test
    fun dropsEmptyEntries() {
        assertEquals(listOf("com.a"), AppFilterResolver.parsePackageList(",,com.a, ,"))
        assertTrue(AppFilterResolver.parsePackageList("").isEmpty())
        assertTrue(AppFilterResolver.parsePackageList("   ").isEmpty())
    }

    @Test
    fun usesProfileValuesWhenOverrideEnabled() {
        val filter = AppFilterResolver.resolve(
            overrideEnabled = true,
            profileFilterApps = "com.profile",
            profileFilterMode = AppFilterResolver.MODE_ALLOW,
            globalFilterApps = "com.global",
            globalFilterMode = AppFilterResolver.MODE_BLOCK,
        )
        assertEquals(listOf("com.profile"), filter.packages)
        assertTrue(filter.isAllowList)
    }

    @Test
    fun usesGlobalValuesWhenOverrideDisabled() {
        val filter = AppFilterResolver.resolve(
            overrideEnabled = false,
            profileFilterApps = "com.profile",
            profileFilterMode = AppFilterResolver.MODE_ALLOW,
            globalFilterApps = "com.global",
            globalFilterMode = AppFilterResolver.MODE_BLOCK,
        )
        assertEquals(listOf("com.global"), filter.packages)
        assertFalse(filter.isAllowList)
    }

    @Test
    fun overrideSwitchesModeAndListTogether() {
        // profile 覆盖必须**成对**切换：只换列表不换模式（或反过来）会让"排除这些应用"
        // 变成"只代理这些应用"，是那种用户完全看不出但流量走向完全相反的 bug。
        val allow = AppFilterResolver.resolve(true, "com.a", AppFilterResolver.MODE_ALLOW, "com.b", AppFilterResolver.MODE_BLOCK)
        val block = AppFilterResolver.resolve(true, "com.a", AppFilterResolver.MODE_BLOCK, "com.b", AppFilterResolver.MODE_ALLOW)
        assertEquals(allow.packages, block.packages)
        assertTrue(allow.isAllowList)
        assertFalse(block.isAllowList)
    }

    @Test
    fun emptyListMeansNoFiltering() {
        for (csv in listOf("", "   ", ",", " , , ")) {
            val filter = AppFilterResolver.resolve(false, csv, AppFilterResolver.MODE_ALLOW, csv, AppFilterResolver.MODE_ALLOW)
            assertTrue("空列表必须退化成不过滤: '$csv'", filter.isEmpty)
        }
        // 注意：空列表时 mode 一律归一到 block（AppFilter.EMPTY 的定义），
        // 这样调用方只需判 isEmpty，不必再判 mode。
        assertFalse(AppFilterResolver.resolve(false, "", 1, "", 1).isAllowList)
    }

    @Test
    fun normalizesUnknownModeToBlock() {
        for (mode in listOf(0, 2, -1, 99)) {
            val filter = AppFilterResolver.resolve(true, "com.a", mode, "", 0)
            assertTrue("mode=$mode 应按黑名单处理", filter.packages.isNotEmpty())
            assertFalse("mode=$mode 不应被当成白名单", filter.isAllowList)
        }
    }
}
