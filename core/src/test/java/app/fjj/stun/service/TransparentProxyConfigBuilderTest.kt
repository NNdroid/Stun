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
    ) = TransparentProxyConfigBuilder.buildShellRules(self, tproxyPort, dnsPort, filter)

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

    // ── 自己必须直连（死循环防线）────────────────────────────────────

    @Test
    fun alwaysBypassesSelfInBlockMode() {
        assertEquals("0:app.fjj.stun", varOf(rules(), "BYPASS_APPS_LIST"))
    }

    @Test
    fun neverListsSelfInTheProxyList() {
        // whitelist 模式下 shell 完全不读 BYPASS_APPS_LIST，自己只能靠"不在 proxy 列表里 +
        // 链尾 -j ACCEPT"兜底；用户手填 `0:app.fjj.stun` 也不能把自己送进去。
        val conf = rules(filter = AppFilter(AppFilterResolver.MODE_ALLOW, listOf(self, "0:$self", "com.foo")))
        assertEquals("0:com.foo", varOf(conf, "PROXY_APPS_LIST"))
        assertEquals("", varOf(conf, "BYPASS_APPS_LIST"))
    }

    @Test
    fun dropsSelfFromBypassListInBlockModeToAvoidDuplicates() {
        val conf = rules(filter = AppFilter(AppFilterResolver.MODE_BLOCK, listOf("com.foo", self)))
        assertEquals("0:app.fjj.stun 0:com.foo", varOf(conf, "BYPASS_APPS_LIST"))
    }

    // ── 分应用代理：两种模式的映射 ───────────────────────────────────

    @Test
    fun emptyFilterMeansBlacklistOfSelfOnly() {
        val conf = rules(filter = AppFilter.EMPTY)
        assertEquals("blacklist", varOf(conf, "APP_PROXY_MODE"))
        assertEquals("0:app.fjj.stun", varOf(conf, "BYPASS_APPS_LIST"))
        assertEquals("", varOf(conf, "PROXY_APPS_LIST"))
        assertEquals("1", varOf(conf, "APP_PROXY_ENABLE"))
    }

    @Test
    fun blockModeWritesBypassListAndEmptiesProxyList() {
        val conf = rules(filter = AppFilter(AppFilterResolver.MODE_BLOCK, listOf("com.a", "com.b")))
        assertEquals("blacklist", varOf(conf, "APP_PROXY_MODE"))
        assertEquals("0:app.fjj.stun 0:com.a 0:com.b", varOf(conf, "BYPASS_APPS_LIST"))
        assertEquals("", varOf(conf, "PROXY_APPS_LIST"))
    }

    @Test
    fun allowModeWritesProxyListAndEmptiesBypassList() {
        val conf = rules(filter = AppFilter(AppFilterResolver.MODE_ALLOW, listOf("com.a")))
        assertEquals("whitelist", varOf(conf, "APP_PROXY_MODE"))
        assertEquals("0:com.a", varOf(conf, "PROXY_APPS_LIST"))
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
