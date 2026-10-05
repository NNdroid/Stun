package app.fjj.stun.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TransparentProxyConfigBuilder] 生成的 tproxy.sh 配置。
 *
 * 这份配置是**纯文本产物**，消费方是一段 1900 行的 shell —— 改错了不会编译报错，
 * 只会安静地退回脚本内置默认值（`start` 依然 exit 0），所以必须在这里钉住。
 *
 * 重点是两条链的语义对应（详见 builder 里的对照表）：Stun 的 `MODE_BLOCK` ↔ shell 的
 * `APP_PROXY_MODE=blacklist` + `BYPASS_APPS_LIST`，`MODE_ALLOW` ↔ `whitelist` + `PROXY_APPS_LIST`。
 * 这层映射错了，表现为"分应用代理看起来开了但完全没效果"，没有任何报错。
 */
class TransparentProxyConfigBuilderTest {

    private val self = "app.fjj.stun"

    private fun rules(
        tproxyPort: Int = 10812,
        dnsPort: Int = 10553,
        filter: AppFilter = AppFilter.EMPTY,
        socketMark: Int = TProxyPorts.SOCKET_MARK,
    ) = TransparentProxyConfigBuilder.buildShellRules(self, tproxyPort, dnsPort, filter, socketMark)

    private fun varOf(conf: String, name: String): String? =
        conf.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.removePrefix("$name=")
            ?.trim('"')

    // ── 端口与基础键 ────────────────────────────────────────────────

    @Test
    fun writesPortsAndHijackKeys() {
        val conf = rules(tproxyPort = 1234, dnsPort = 5353)
        assertEquals("1234", varOf(conf, "PROXY_TCP_PORT"))
        assertEquals("1234", varOf(conf, "PROXY_UDP_PORT"))
        assertEquals("5353", varOf(conf, "DNS_PORT"))
        assertEquals("1", varOf(conf, "DNS_HIJACK_ENABLE"))
        assertEquals("1", varOf(conf, "PROXY_MODE"))
        assertEquals("0", varOf(conf, "DRY_RUN"))
    }

    @Test
    fun tcpAndUdpPortsFollowTheSameArgument() {
        // 改前是 `buildHevSocks5TProxyConfig(this, TPROXY_PORT, TPROXY_PORT, …)` 这种
        // "两个端口碰巧相等" 的写法；现在只有一个端口参数，两个键必然一致。
        val conf = rules(tproxyPort = 10812)
        assertEquals(varOf(conf, "PROXY_TCP_PORT"), varOf(conf, "PROXY_UDP_PORT"))
    }

    // ── 自己是否直连：改由 socket mark 决定 ────────────────────────────
    //
    // 旧契约是"自己永远在 BYPASS_APPS_LIST 里"（按 uid 放行整个 App）。代价是 App 内
    // 所有流量都直连 —— 出口地址永远显示本机 IP，WebUI / MCP 的出站请求也出不去。
    // 现在改成：隧道 socket 由 root 侧 sockmark 打 SO_MARK，tproxy 按 mark 精确放行，
    // App 自身 uid 不再进 bypass。只有 mark 通路不可用时才回落老的 uid 放行。

    @Test
    fun selfIsNotBypassedByUidWhenSocketMarkIsEnabled() {
        val conf = rules()
        assertEquals(
            "mark 通路开启时自己不应按 uid 放行（否则 App 内所有流量都直连）",
            "", varOf(conf, "BYPASS_APPS_LIST")
        )
        assertEquals("1", varOf(conf, "FORCE_MARK_BYPASS"))
        // ROUTING_MARK 必须与 tproxy.sh 侧的 -m mark --mark 匹配，也必须与 Go 侧一致。
        assertEquals("0x%x".format(TProxyPorts.SOCKET_MARK), varOf(conf, "ROUTING_MARK"))
    }

    @Test
    fun fallsBackToUidBypassWhenSocketMarkIsUnavailable() {
        // socketMark=0（helper 没部署 / 未授予 root）时的降级：死循环比"出口 IP 显示本地"严重得多，
        // 所以必须回落到按 uid 放行整个 App。
        val conf = rules(socketMark = 0)
        assertEquals("0:app.fjj.stun", varOf(conf, "BYPASS_APPS_LIST"))
        assertEquals("0", varOf(conf, "FORCE_MARK_BYPASS"))
        assertEquals("", varOf(conf, "ROUTING_MARK"))
    }

    @Test
    fun markAndUidBypassAreNeverBothActive() {
        // 反事实：两者同时开启时行为依赖规则顺序，属于"看起来能跑但语义不清"的状态。
        val withMark = rules(socketMark = TProxyPorts.SOCKET_MARK)
        val withoutMark = rules(socketMark = 0)
        assertFalse(
            "mark 开启时不应有 uid bypass（实际写入 '${varOf(withMark, "BYPASS_APPS_LIST")}'）",
            varOf(withMark, "BYPASS_APPS_LIST").orEmpty().contains(self)
        )
        assertTrue(
            "mark 关闭时必须有 uid bypass 兜底",
            varOf(withoutMark, "BYPASS_APPS_LIST").orEmpty().contains(self)
        )
    }

    @Test
    fun selfIsProxiedOnlyWhenTunnelSocketCanBeBypassedPrecisely() {
        // 设计原则：**只有底层连接能被精确 bypass 时，App 自身才进代理；否则整个 App bypass。**
        //
        // 隧道 socket 的放行粒度决定 App 自身的命运，而 tproxy 的两条放行路径差别很大：
        //  - mark 可用：`setup_proxy_chain` 把 mark ACCEPT 加在 `_add_chain_jumps` **之前**，
        //    隧道 socket 在进 APP_CHAIN 之前就被直连 ⇒ 放行精确，不牵连 App 的其他流量。
        //  - mark 不可用：只剩 uid 放行，而 App 内所有 socket 同属一个 uid ⇒ 必然连带整个 App。
        val allowFilter = AppFilter(AppFilterResolver.MODE_ALLOW, listOf(self, "0:$self", "com.foo"))

        // mark 可用 + whitelist：自己必须进 PROXY_APPS_LIST。链尾是 `-j ACCEPT`（直连），
        // 自己不在名单里就等于让整个 App 直连。
        val allowWithMark = rules(filter = allowFilter, socketMark = TProxyPorts.SOCKET_MARK)
        assertEquals("whitelist", varOf(allowWithMark, "APP_PROXY_MODE"))
        assertEquals(
            "mark 可用：自己必须进 proxy 列表，否则链尾 ACCEPT 让整个 App 直连",
            "0:$self 0:com.foo", varOf(allowWithMark, "PROXY_APPS_LIST"),
        )
        assertEquals("whitelist 下 bypass 列表恒空（shell 不读它）", "", varOf(allowWithMark, "BYPASS_APPS_LIST"))

        // mark 可用 + blacklist：自己不在任何列表 —— 其流量走隧道，隧道 socket 由 mark 放行。
        val blockWithMark = rules(
            filter = AppFilter(AppFilterResolver.MODE_BLOCK, listOf("com.foo")),
            socketMark = TProxyPorts.SOCKET_MARK,
        )
        assertEquals("blacklist", varOf(blockWithMark, "APP_PROXY_MODE"))
        assertEquals("0:com.foo", varOf(blockWithMark, "BYPASS_APPS_LIST"))
        assertFalse(
            "mark 可用时自己不得被 uid 放行，否则整个 App 直连",
            varOf(blockWithMark, "BYPASS_APPS_LIST").orEmpty().contains(self),
        )
    }

    @Test
    fun downgradesAllowListToBlockListWhenMarkIsUnavailable() {
        // mark 不可用时唯一可用的放行是 uid，而 uid 粒度必然连带整个 App。
        // 此时 whitelist 语义（「只有名单内的应用走代理」）无法成立：shell 的 whitelist
        // 分支只读 PROXY_APPS_LIST 加 `-j RETURN`，而 RETURN 是**继续往下走** ——
        // 隧道 socket 最终被 PROXY_OUTPUT 链尾的 REDIRECT 抓回本地 socks5，**死循环**。
        // 绝不能为了"让 App 进代理"而把自己塞进 PROXY_APPS_LIST。
        //
        // 正确降级：转成 blacklist，让 BYPASS_APPS_LIST 真正被 shell 读取，自己 uid 直连。
        // 代价是用户配的白名单暂时失效（属配置偏好），好过隧道死循环（属功能性损坏）。
        val conf = rules(
            filter = AppFilter(AppFilterResolver.MODE_ALLOW, listOf("com.foo")),
            socketMark = 0,
        )
        assertEquals("blacklist", varOf(conf, "APP_PROXY_MODE"))
        assertTrue(
            "mark 不可用：自己必须被 uid 放行，否则隧道 socket 死循环",
            varOf(conf, "BYPASS_APPS_LIST").orEmpty().contains(self),
        )
        assertEquals(
            "自己绝不能出现在 PROXY_APPS_LIST —— whitelist 的 RETURN 会让隧道 socket 被重定向",
            "", varOf(conf, "PROXY_APPS_LIST"),
        )
        assertEquals("0", varOf(conf, "FORCE_MARK_BYPASS"))
    }

    @Test
    fun dropsSelfFromBypassListInBlockModeToAvoidDuplicates() {
        val conf = rules(
            filter = AppFilter(AppFilterResolver.MODE_BLOCK, listOf("com.foo", self)),
            socketMark = 0,
        )
        assertEquals("0:app.fjj.stun 0:com.foo", varOf(conf, "BYPASS_APPS_LIST"))
    }

    // ── 分应用代理：两种模式的映射 ───────────────────────────────────

    @Test
    fun emptyFilterMeansNoAppIsBypassed() {
        val conf = rules(filter = AppFilter.EMPTY)
        assertEquals("blacklist", varOf(conf, "APP_PROXY_MODE"))
        assertEquals("", varOf(conf, "BYPASS_APPS_LIST"))
        assertEquals("", varOf(conf, "PROXY_APPS_LIST"))
        assertEquals("1", varOf(conf, "APP_PROXY_ENABLE"))
    }

    @Test
    fun blockModeWritesBypassListAndEmptiesProxyList() {
        // mark 通路下 bypass 只含用户选中的应用，不含自己。
        val conf = rules(filter = AppFilter(AppFilterResolver.MODE_BLOCK, listOf("com.a", "com.b")))
        assertEquals("blacklist", varOf(conf, "APP_PROXY_MODE"))
        assertEquals("0:com.a 0:com.b", varOf(conf, "BYPASS_APPS_LIST"))
        assertEquals("", varOf(conf, "PROXY_APPS_LIST"))
    }

    @Test
    fun allowModeWritesProxyListAndEmptiesBypassList() {
        val conf = rules(filter = AppFilter(AppFilterResolver.MODE_ALLOW, listOf("com.a")))
        assertEquals("whitelist", varOf(conf, "APP_PROXY_MODE"))
        // 自己也在 proxy 列表里：whitelist 链尾是 `-j ACCEPT`，自己不在名单就等于整个 App 直连。
        // 隧道 socket 不受影响 —— mark 放行规则加在 APP_CHAIN 之前，它压根走不到这里。
        assertEquals("0:app.fjj.stun 0:com.a", varOf(conf, "PROXY_APPS_LIST"))
        assertEquals("", varOf(conf, "BYPASS_APPS_LIST"))
    }

    @Test
    fun emittedModeIsAlwaysOneOfTheTwoTheScriptAccepts() {
        // tproxy.sh 第 397~403 行对 APP_PROXY_MODE 做 case 校验，非法值直接 return 1。
        for (mode in listOf(AppFilterResolver.MODE_BLOCK, AppFilterResolver.MODE_ALLOW, 0, 1, 2, -1)) {
            val value = varOf(rules(filter = AppFilter(mode, listOf("com.a"))), "APP_PROXY_MODE")
            assertTrue("非法 APP_PROXY_MODE: $value", value == "blacklist" || value == "whitelist")
        }
    }

    // ── core YAML ──────────────────────────────────────────────────

    @Test
    fun coreYamlBindsSocksAndTproxyPorts() {
        val yaml = TransparentProxyConfigBuilder.buildCoreYaml(socksPort = 10808, tproxyPort = 10812)
        assertTrue(yaml.contains("port: 10808"))
        assertTrue(yaml.contains("port: 10812"))
        // socks5 段必须指向本地 SOCKS 端口，tcp/udp 段指向 tproxy 端口。
        assertTrue(yaml.contains("socks5:\n  port: 10808"))
        assertTrue(yaml.contains("tcp:\n  port: 10812"))
        assertTrue(yaml.contains("udp:\n  port: 10812"))
    }

    @Test
    fun coreYamlHasNoDnsSection() {
        // tproxy.sh 已在 mangle 表做 DNS 劫持；core 再开一个 dns 段会与之冲突，故保持注释掉的状态。
        val yaml = TransparentProxyConfigBuilder.buildCoreYaml(10808, 10812)
        assertFalse(yaml.lineSequence().any { it.trimStart().startsWith("dns:") })
    }

    @Test
    fun twoBuildersProduceDifferentFormatsUnderDistinctNames() {
        // 原先是两个同名重载，只靠参数类型区分；现在函数名自带"谁消费我"。
        val shell = rules()
        val yaml = TransparentProxyConfigBuilder.buildCoreYaml(10808, 10812)
        assertTrue(shell.contains("PROXY_TCP_PORT="))
        assertTrue(yaml.contains("workers:"))
        assertFalse(shell.contains("workers:"))
        assertFalse(yaml.contains("PROXY_TCP_PORT="))
    }
}
