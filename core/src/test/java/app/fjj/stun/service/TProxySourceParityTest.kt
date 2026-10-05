package app.fjj.stun.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tproxy 模式的**跨文件**护栏。
 *
 * 这条链上大部分是「文本喂给 shell」和「root 命令字符串」，改错了照样编译通过、
 * 照样连上、照样 exit 0，只是行为悄悄变了。所以把几条最贵的约定按文件钉住：
 *
 * 1. **配置文件名两侧一致** —— Kotlin 写出去的路径与 `tproxy.sh` source 的路径必须同名。
 *    不同名时脚本走"用内置默认值"分支且**不报错**（端口、DNS 劫持、分应用代理全变）。
 * 2. **`tproxy.conf` 这个名字彻底退役** —— 历史上 VPN 与 tproxy 两种模式抢同一个文件名，
 *    而 `tproxy.sh` 会 source 它，于是切过一次模式后脚本会去解析一份 YAML。
 * 3. **收尾不挂在 GlobalScope 上** —— `onDestroy` 紧接着 cancel `serviceScope`，
 *    收尾必须有自己的作用域，否则每次断开都会留下没人清的 iptables 规则。
 * 4. **后台白名单有加必有撤** —— `deviceidle whitelist` 跨重启留存。
 * 5. **两种模式不各自留字面量** —— 通知节流、分应用代理取值都收敛到共享单元。
 */
class TProxySourceParityTest {

    private fun source(relative: String): String {
        val candidates = listOf(
            File(relative),
            File("core/$relative"),
            File("../$relative"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("找不到 $relative：尝试过 ${candidates.map { it.absolutePath }}")
    }

    private val tproxySh get() = source("src/main/assets/scripts/tproxy.sh")
    private val tproxyService get() = source("src/main/java/app/fjj/stun/service/MyTransparentProxyService.kt")
    private val vpnService get() = source("src/main/java/app/fjj/stun/service/MyVpnService.kt")
    private val builder get() = source("src/main/java/app/fjj/stun/service/TransparentProxyConfigBuilder.kt")

    /** 取 `private fun name(...)` 到下一个同缩进 `private fun` 之间的函数体。 */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("找不到 $signature", start >= 0)
        val next = source.indexOf("\n    private fun ", start + 1)
        val end = if (next < 0) source.length else next
        return source.substring(start, end)
    }

    // ── 1. 配置文件名两侧一致 ────────────────────────────────────────

    @Test
    fun kotlinWritesTheFileTheScriptSources() {
        val name = Regex("""FILE_TPROXY_RULES\s*=\s*"([^"]+)"""").find(tproxyService)?.groupValues?.get(1)
        assertTrue("MyTransparentProxyService 里找不到 FILE_TPROXY_RULES 常量", name != null)
        val check = """if [ -f "${'$'}CONFIG_DIR/$name" ]"""
        assertTrue(
            "tproxy.sh 没有 source Kotlin 写出的配置。probe=<$check> len=${tproxySh.length}",
            tproxySh.contains(check),
        )
        val source = """. "${'$'}CONFIG_DIR/$name${'"'}"""
        assertTrue(
            "tproxy.sh 没有真正 source 该文件。probe=<$source> len=${tproxySh.length}",
            tproxySh.contains(source),
        )
    }

    // ── 6. 应用过滤链里 bypass 必须排在 proxy 之前 ──────────────────

    /**
     * `setup_app_chain` 的 **whitelist** 分支必须先按 `BYPASS_APPS_LIST` 加 `-j ACCEPT`，
     * 再按 `PROXY_APPS_LIST` 加 `-j RETURN`，最后才是链尾。
     *
     * 顺序是行为本身，不是风格问题：
     *  - bypass 在前 ⇒ 命中即终止遍历 = 直连；
     *  - bypass 在后 ⇒ 先命中 proxy 的 `-j RETURN`（**继续往下走**），最终落到
     *    `PROXY_OUTPUT` 链尾的 REDIRECT ⇒ **隧道 socket 被抓回本地 socks5，死循环**。
     *
     * 为什么 whitelist 需要读 bypass：SO_MARK 不可用时隧道 socket 只能按 uid 放行，
     * 而同一 uid 下无法区分 App 的其他 socket ⇒ 整个 App 必须直连。没有这条 bypass
     * 承载者，App 侧就只能把模式降级成 blacklist ��— 代价是误伤用户的白名单。
     *
     * 反事实：把下面两个 `indexOf` 的顺序对调（或删掉 bypass 段），本测试立刻失败。
     */
    @Test
    fun allowListBranchAppliesBypassBeforeProxy() {
        // ⚠️ 必须锚到**最后一处** `case "$APP_PROXY_MODE" in`：脚本里出现两次
        // （前一处只是取值合法性校验，无分支体；后一处才是真正的应用过滤链）。
        // 同理 `whitelist)` 在 MAC 过滤链里也有一份，不锚定就会切错分支而假绿/假红。
        val anchor = "case \"\$APP_PROXY_MODE\" in"
        val script = tproxySh
        val caseAt = script.lastIndexOf(anchor)
        assertTrue(
            "tproxy.sh 里找不到应用过滤的 case 语句（锚点出现 ${script.count { it == 'c' }} 个 'c'）",
            caseAt >= 0,
        )
        val branchLines = script.substring(caseAt).lineSequence().toList()
        val startIdx = branchLines.indexOfFirst { it.trim() == "whitelist)" }
        assertTrue("应用过滤链里找不到 whitelist 分支", startIdx >= 0)
        val endIdx = (startIdx + 1 until branchLines.size).firstOrNull { branchLines[it].trim() == ";;" }
        assertTrue("whitelist 分支没有闭合的 ;;", endIdx != null)
        // 只看可执行代码行：注释里也提到这两个变量名，会把位置判断带偏。
        val code = branchLines.subList(startIdx + 1, endIdx!!)
            .filterNot { it.trimStart().startsWith("#") }
            .joinToString("\n")

        val bypassAt = code.indexOf("BYPASS_APPS_LIST")
        val proxyAt = code.indexOf("PROXY_APPS_LIST")
        assertTrue("whitelist 分支没有读 BYPASS_APPS_LIST —— uid 放行将无处生效", bypassAt >= 0)
        assertTrue("whitelist 分支没有读 PROXY_APPS_LIST", proxyAt >= 0)
        assertTrue(
            "whitelist 分支里 BYPASS_APPS_LIST 必须排在 PROXY_APPS_LIST 之前：" +
                "放后面会先命中 proxy 的 -j RETURN（继续往下走），" +
                "隧道 socket 最终被 REDIRECT 回本地 socks5 ⇒ 死循环",
            bypassAt < proxyAt,
        )
        // bypass 命中必须是 ACCEPT（终止遍历 = 直连），不能是 RETURN。
        val bypassRule = code.substring(bypassAt, proxyAt)
        assertTrue(
            "bypass 规则必须用 -j ACCEPT（终止遍历）",
            Regex("""--uid-owner\s+"\${'$'}uid"\s+-j\s+ACCEPT""").containsMatchIn(bypassRule),
        )
        // 反事实：bypass 之后仍需保留链尾 ACCEPT，否则不在任何名单里的应用会掉出链尾。
        assertTrue("whitelist 分支缺链尾 -j ACCEPT", code.trimEnd().endsWith("-j ACCEPT"))
    }

    // ── 2. tproxy.conf 这个名字彻底退役 ─────────────────────────────

    @Test
    fun ambiguousConfNameIsGoneFromBothSides() {
        // 只查"被 source / 被写"的位置：`runtime_tproxy.conf` 是脚本自己在 CONFIG_DIR 下
        // 生成的运行时文件，名字里含 tproxy.conf 属于正常，不能一起禁掉。
        val sourced = Regex("""CONFIG_DIR/([A-Za-z0-9_.-]+[.]conf)""")
            .findAll(tproxySh).map { it.groupValues[1] }.toSet()
        assertFalse("tproxy.sh 仍在读写歧义文件名 tproxy.conf：$sourced", "tproxy.conf" in sourced)
        assertTrue("应在集合里：${sourced}", "tproxy_rules.conf" in sourced)

        for ((name, src) in listOf("MyVpnService" to vpnService, "MyTransparentProxyService" to tproxyService)) {
            assertFalse("$name 仍在用 tproxy.conf 这个名字", src.contains("\"tproxy.conf\""))
        }
        assertTrue("VPN 侧应改用 hev-socks5-tunnel.conf", vpnService.contains("hev-socks5-tunnel.conf"))
    }

    // ── 3. 收尾作用域 ───────────────────────────────────────────────

    @Test
    fun teardownDoesNotRideOnGlobalScope() {
        val stop = functionBody(tproxyService, "private fun stopTProxy()")
        assertFalse("stopTProxy 不该用 GlobalScope（无归属、无法等待）", stop.contains("GlobalScope"))
        assertFalse("NonCancellable 放在 launch context 里是空操作", stop.contains("NonCancellable"))
        assertTrue("收尾必须走 teardownScope", stop.contains("teardownScope.launch"))
        assertTrue("服务应持有独立的收尾作用域", tproxyService.contains("private val teardownScope = CoroutineScope("))
    }

    @Test
    fun teardownStopsEveryLayerItStarted() {
        val stop = functionBody(tproxyService, "private fun stopTProxy()")
        for (step in listOf("stopTrafficMonitor()", "stopWatchdog()", "applyRules(enabled = false)", "stopCoreEngine()")) {
            assertTrue("收尾漏了 $step", stop.contains(step))
        }
        assertTrue("收尾必须停前台并停服务", stop.contains("stopForeground(STOP_FOREGROUND_REMOVE)"))
        assertTrue("收尾必须把状态置回未连接", stop.contains("VpnState.DISCONNECTED"))
    }

    // ── 4. 后台白名单有加有撤 ───────────────────────────────────────

    @Test
    fun backgroundWhitelistIsRevertedOnStop() {
        // 命令字面量已收口到 BackgroundExemptions，所以这里查的是**入口**，
        // 具体命令串由 BackgroundExemptionsTest 钉住（一条规则一份实现，各查各的）。
        assertTrue(tproxyService.contains("private fun revertBackgroundOptimizations()"))
        val revert = functionBody(tproxyService, "private fun revertBackgroundOptimizations()")
        assertTrue("撤销必须走统一入口", revert.contains("BackgroundExemptions.revertViaRoot("))

        // 另一半：也得确实有"加"，否则上面那条可以靠一个空壳转绿。
        val apply = functionBody(tproxyService, "private fun optimizeSystemForBackground()")
        assertTrue("应用豁免必须走统一入口", apply.contains("BackgroundExemptions.applyViaRoot("))

        // appops 复位只能回 `default`（交回 ROM 默认）；`ignore` 等于替用户永久禁止后台运行。
        assertFalse("tproxy 不该再内联 appops 复位命令", revert.contains("appops"))
        assertFalse("appops 不能复位成 ignore", revert.contains("ignore"))

        val stop = functionBody(tproxyService, "private fun stopTProxy()")
        assertTrue("stopTProxy 必须调用撤销", stop.contains("revertBackgroundOptimizations()"))
    }

    // ── 5. 两种模式不各自留字面量 ───────────────────────────────────

    @Test
    fun bothServicesShareTheThrottleAndTheFilterResolver() {
        for ((name, src) in listOf("MyVpnService" to vpnService, "MyTransparentProxyService" to tproxyService)) {
            assertTrue("$name 应共用 SpeedRefreshThrottle", src.contains("SpeedRefreshThrottle()"))
            assertFalse("$name 不该再自带通知节流字面量", src.contains("lastNotificationUpdateTime"))
            assertTrue("$name 应遵守测速开关", src.contains("getShowNotificationSpeed"))
            assertTrue("$name 应共用 AppFilterResolver", src.contains("AppFilterResolver.resolve("))
        }
        assertFalse(
            "tproxy 不该再有 1000ms 私有节流",
            tproxyService.contains("< 1000L"),
        )
    }

    @Test
    fun watchdogNameComesFromTheSharedConstant() {
        val stop = functionBody(tproxyService, "private fun stopWatchdog()")
        assertTrue(stop.contains("SCRIPT_WATCHDOG"))
        assertFalse("不该再硬编码脚本名", stop.contains("\"watchdog.sh\""))
        // 常量本身必须与 AppBootstrap 的部署判定同名，否则看门狗会变成杀不掉的孤儿进程。
        val const = Regex("""SCRIPT_WATCHDOG\s*=\s*"([^"]+)"""").find(tproxyService)?.groupValues?.get(1)
        assertEquals("watchdog.sh", const)
        assertTrue(source("src/main/java/app/fjj/stun/util/AppBootstrap.kt").contains("""SCRIPT_WATCHDOG = "watchdog.sh""""))
    }

    // ── 6. 生成出去的配置项，脚本必须真的读 ──────────────────────────

    @Test
    fun scriptActuallyHonoursTheKeysWeEmit() {
        // 我们写进配置的每个键都必须在脚本里有对应的默认值兜底行，否则它就是一句空话。
        for (key in listOf(
            "PROXY_TCP_PORT", "PROXY_UDP_PORT", "PROXY_MODE", "DNS_HIJACK_ENABLE", "DNS_PORT",
            "APP_PROXY_ENABLE", "APP_PROXY_MODE", "BYPASS_APPS_LIST", "PROXY_APPS_LIST", "DRY_RUN",
        )) {
            // 脚本里的形态是 `KEY="${KEY:-$DEFAULT_...}"`，探针只取到 `:-` 为止。
            val probe = "$key=\"\${$key:-"
            assertTrue(
                "tproxy.sh 不读 $key（我们写进配置的值会被忽略）。probe=<$probe> len=${tproxySh.length}",
                tproxySh.contains(probe),
            )
        }
    }

    @Test
    fun scriptAcceptsTheTwoProxyModesWeEmit() {
        assertTrue(
            "tproxy.sh 的 APP_PROXY_MODE 校验必须接受我们写出的两个值",
            tproxySh.contains("blacklist | whitelist"),
        )
    }

    // ── 7. 死代码已清理 ─────────────────────────────────────────────

    @Test
    fun deadJniBridgeIsGone() {
        val dead = listOf(
            File("src/main/java/hev/htp/TProxyService.kt"),
            File("core/src/main/java/hev/htp/TProxyService.kt"),
        )
        assertTrue("零引用的 hev.htp.TProxyService 应该已删除", dead.none { it.exists() })
        for ((name, src) in listOf("MyVpnService" to vpnService, "MyTransparentProxyService" to tproxyService)) {
            assertFalse("$name 不该再 import 他", src.contains("hev.htp.TProxyService"))
        }
        // 隧道模式的桥还在，别一起删了。
        assertTrue(vpnService.contains("import hev.htp.TTunnelService"))
    }

    @Test
    fun unusedMathImportsAreGone() {
        assertFalse(tproxyService.contains("import kotlin.math.log10"))
        assertFalse(tproxyService.contains("import kotlin.math.pow"))
    }

    @Test
    fun noOverloadedBuilderNamesRemain() {
        // 曾经是两个同名重载（一个生成 shell 变量、一个生成 YAML），只靠参数类型区分。
        assertTrue(tproxyService.contains("TransparentProxyConfigBuilder.buildShellRules("))
        assertTrue(tproxyService.contains("TransparentProxyConfigBuilder.buildCoreYaml("))
        assertFalse("旧的重载名不该再有调用点", tproxyService.contains("buildHevSocks5TProxyConfig"))
        assertEquals(1, Regex("fun buildShellRules\\(").findAll(builder).count().toInt())
        assertEquals(1, Regex("fun buildCoreYaml\\(").findAll(builder).count().toInt())
    }
}
