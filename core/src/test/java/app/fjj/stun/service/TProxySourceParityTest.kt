package app.fjj.stun.service

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
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
    private val rootShell get() = source("src/main/java/app/fjj/stun/util/RootShell.kt")

    /** 取 `name() {` 到**行首独占的** `}` 之间的 shell 函数体。 */
    private fun shellFunctionBody(script: String, name: String): String {
        val signature = "$name() {"
        val start = script.indexOf(signature)
        assertTrue("找不到 shell 函数 $name", start >= 0)
        val body = script.substring(start)
        val end = body.indexOf("\n}")
        assertTrue("找不到 $name 的闭合 }", end >= 0)
        return body.substring(0, end)
    }

    /** 取 `private fun name(...)` 到下一个同缩进 `private fun` 之间的函数体。 */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("找不到 $signature", start >= 0)
        val next = source.indexOf("\n    private fun ", start + 1)
        val end = if (next < 0) source.length else next
        return source.substring(start, end)
    }

    /**
     * 取应用过滤 case（`case "$APP_PROXY_MODE" in` 的**最后一处**出现，前一处只是取值
     * 校验、无分支体）里指定分支（`blacklist)` / `whitelist)` 到下一个 `;;`）的可执行
     * 代码行。整行注释已剔除 —— 注释里也提到这些变量名，会把位置判断带偏。
     *
     * 找不到锚点 / 分支 / 闭合 `;;` 时返回 null，失败原因由调用方断言。
     */
    private fun appFilterBranch(mode: String): List<String>? {
        val script = tproxySh
        val caseAt = script.lastIndexOf("case \"\$APP_PROXY_MODE\" in")
        if (caseAt < 0) return null
        val branchLines = script.substring(caseAt).lineSequence().toList()
        val startIdx = branchLines.indexOfFirst { it.trim() == "$mode)" }
        if (startIdx < 0) return null
        val endIdx = (startIdx + 1 until branchLines.size).firstOrNull { branchLines[it].trim() == ";;" }
            ?: return null
        return branchLines.subList(startIdx + 1, endIdx)
            .filterNot { it.trimStart().startsWith("#") }
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
        // ⚠️ 锚点必须取**最后一处** `case "$APP_PROXY_MODE" in`（appFilterBranch 已处理）：
        // 脚本里出现两次（前一处只是取值合法性校验，无分支体；后一处才是真正的应用过滤链）。
        // 同理 `whitelist)` 在 MAC 过滤链里也有一份，不锚定就会切错分支而假绿/假红。
        val body = appFilterBranch("whitelist")
        assertNotNull("应用过滤链里找不到 whitelist 分支（锚点丢失或分支未闭合）", body)
        val code = body!!.joinToString("\n")

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

    /**
     * 应用过滤两个分支的结构必须配平。
     *
     * whitelist/bypass 改造恰好坏在**结构**上：blacklist 分支开头悬着一个没有 `do` 的
     * `done`，whitelist 分支尾部混进一个缺 `done`/`fi` 的重复 bypass 块 —— `bash -n`
     * 解析直接失败，而 [allowListBranchAppliesBypassBeforeProxy] 照绿（锚点切对了分支、
     * 顺序与链尾断言也满足）。文本探针看不见 if/fi、for/done 的配对，所以单独钉住。
     */
    @Test
    fun appFilterBranchesAreStructurallyBalanced() {
        for (mode in listOf("blacklist", "whitelist")) {
            val body = appFilterBranch(mode)
            assertNotNull("应用过滤链里定位不到 $mode 分支", body)
            val lines = body!!
            // 只认**行首**的结构关键字：日志字符串里有 "bypass for UID"，按词边界数会把
            // 英文介词当成循环开头。
            val openIf = lines.count { Regex("^\\s*if\\b").containsMatchIn(it) }
            val openLoop = lines.count { Regex("^\\s*(for|while|until)\\b").containsMatchIn(it) }
            val closeIf = lines.count { Regex("^\\s*fi\\b").containsMatchIn(it) }
            val closeLoop = lines.count { Regex("^\\s*done\\b").containsMatchIn(it) }
            assertEquals(
                "$mode 分支 if 与 fi 不配平（if=$openIf fi=$closeIf）—— 有块没闭合，脚本会解析失败",
                openIf, closeIf,
            )
            assertEquals(
                "$mode 分支循环与 done 不配平（open=$openLoop done=$closeLoop）—— 悬空/缺失的 done 会让脚本解析失败",
                openLoop, closeLoop,
            )
            // bypass 块只能有一份：上次 whitelist 分支里就混进过第二个（未闭合的）bypass 块。
            val acceptRules = lines.count { it.contains("--uid-owner") && it.contains("-j ACCEPT") }
            assertEquals(
                "$mode 分支里 uid 的 -j ACCEPT 规则只能有一条：出现两条说明 bypass 块被复制了一份",
                1, acceptRules,
            )
        }
    }

    /**
     * 整份脚本必须能被 bash 解析。文本探针（indexOf / 正则）看不见结构，
     * 解析器看得见 —— 上次的损坏 `bash -n` 一秒就能报出来。
     */
    @Test
    fun tproxyShParsesCleanly() {
        assumeFalse(
            "Windows 上 PATH 里的 bash 可能是 WSL 的（路径语义不同会假失败），解析检查交给 Linux CI",
            System.getProperty("os.name")?.lowercase()?.contains("windows") == true,
        )
        val scriptFile = sequenceOf(
            File("src/main/assets/scripts/tproxy.sh"),
            File("core/src/main/assets/scripts/tproxy.sh"),
        ).firstOrNull { it.isFile }
        assertNotNull("找不到 tproxy.sh（工作目录不对）", scriptFile)
        val process = try {
            ProcessBuilder("bash", "-n", scriptFile!!.absolutePath)
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            assumeTrue("bash 不可用，跳过解析检查：${e.message}", false)
            return
        }
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(
            "tproxy.sh 无法通过 bash -n 解析 —— 真机上脚本一启动就会失败：\n$output",
            0, process.waitFor(),
        )
    }

    /**
     * `log()` 的 fd 分流和 RootShell 的兜底定级是**同一份约定的两端**，改一头必须改另一头。
     *
     * 历史上 `log()` 把**每个**级别都写到 stderr，App 侧看不到级别，只能按 fd 定级 ——
     * 于是每一条 `[Info]` 都被记成 ERROR：真机日志里 iptables 的正常输出全是红字，
     * 真正的失败反而被淹掉。现在脚本按级别分流，App 侧改按行首 `[Level]` 定级，
     * fd 只给无前缀的行兜底。任一端悄悄退化时，另一端会给出完全错误的日志级别，
     * 而编译、单测、exit code 全部正常 —— 所以这一对必须钉住。
     *
     * 反事实：把 `out_fd=1` 改回 2（回到"全部写 stderr"），或把 RootShell 的比较退回
     * 裸字面量 `"ERR"`（与回调传的 `"EXEC-ERR"` 对不上），本测试立刻失败。
     */
    @Test
    fun logRoutesFdsByLevelAndRootShellTrustsThem() {
        val body = shellFunctionBody(tproxySh, "log")

        // 默认走 stderr，只有"没出事"的两个级别才切到 stdout。
        assertTrue("log() 必须默认写 stderr", body.contains("local out_fd=2"))
        assertTrue(
            "Debug/Info 必须走 stdout：全写 stderr 会让 App 侧把每行 [Info] 记成 ERROR",
            Regex("""if \[ "\${'$'}level" = "Debug" \] \|\| \[ "\${'$'}level" = "Info" \]; then""")
                .containsMatchIn(body),
        )
        assertTrue("Debug/Info 分支必须把 fd 切到 1", body.contains("out_fd=1"))

        // 两条 printf 都写到选定的 fd —— 写死一条会让分流只生效一半。
        assertEquals(
            "两条 printf 必须都写到 \$out_fd",
            2, body.lines().count { it.contains(">&\"${'$'}out_fd\"") },
        )

        // 行首 `[Level]:` 前缀是 App 侧正则的锚点，改格式等于静默降级成"按 fd 定级"。
        assertTrue("log() 必须继续输出 [Level]: 前缀", body.contains("[${'$'}{level}]:"))

        // RootShell 那边：兜底比较要认回调真的传进去的标签。
        assertTrue("stderr 标签必须是常量，别在两处各写一份字面量",
            rootShell.contains("STREAM_EXEC_ERR = \"EXEC-ERR\""))
        assertTrue("两个回调必须共用同一个流标签常量",
            rootShell.contains("logShellLine(STREAM_EXEC_OUT") && rootShell.contains("logShellLine(STREAM_EXEC_ERR"))
        assertTrue("无前缀行的兜底必须按 ERR 标签判 error 级",
            Regex("""null -> if \(stream == STREAM_EXEC_ERR\) StunLogger\.e""").containsMatchIn(rootShell))
        // 前缀优先于 fd：四个级别仍被 App 侧识别。
        assertTrue("RootShell 必须认脚本声明的四个级别",
            rootShell.contains("""\[(Debug|Info|Warn|Error)\]:\s*"""))
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
        // ⚠️ APPS_LIST 两个键**必须保持** `${KEY:-…}`（空串也回退默认）：DEFAULT_BYPASS_APPS_LIST
        // 里的 App 自旁路是 uid 粒度防回环的最后防线 —— 2026-10 曾改成「仅未设置才回退」让
        // App 流量进隧道，结果在 mark 已死、BYPASS_DST 失配的设备上直接回环，服务端连不上。
        // App 自身流量的正确代理方式是显式走本地 SOCKS5（见 ExitIpProbe.contextAwareFetch）。
        for (key in listOf(
            "PROXY_TCP_PORT", "PROXY_UDP_PORT", "PROXY_MODE", "DNS_HIJACK_ENABLE", "DNS_PORT",
            "APP_PROXY_ENABLE", "APP_PROXY_MODE", "BYPASS_APPS_LIST", "PROXY_APPS_LIST", "DRY_RUN",
            "BYPASS_DST_LIST", "BYPASS_LOCAL_ADDRS", "SSH_SERVER_ENTRY",
        )) {
            // 脚本里的形态是 `KEY="${KEY:-$DEFAULT_...}"`，探针只取到 `:-` 为止。
            val probe = "$key=\"\${$key:-"
            assertTrue(
                "tproxy.sh 不读 $key（我们写进配置的值会被忽略）。probe=<$probe> len=${tproxySh.length}",
                tproxySh.contains(probe),
            )
        }
    }

    /**
     * 本机地址旁路的两条载体必须**同语义**，而且**每个地址族各自决定用哪条**。
     *
     * 局域网里按设备 IP 拨号的连接（不是 127.0.0.1，所以 `PROXY_INTERFACE -i lo` 兜不住）
     * 如果不旁路，会被整包推进隧道 —— WebUI / DB Web 开着时隧道连接数被刷高就是这条。
     * 首选载体是 `addrtype --dst-type LOCAL`（内核自己跟着地址表走，DHCP 换 IP 也不用管，
     * 连后台进程都不用）；没有 xt_addrtype 时退到 ipset + 后台进程。两条载体的规则必须成对
     * 出现且形状一致：**DNS 端口（udp/53）必须仍然进隧道**，劫挂才成立；其余协议才放行。
     *
     * 载体要逐族判：`NETFILTER_XT_MATCH_ADDRTYPE` 只是 v4 的符号，它存在不代表 ip6tables
     * 那半能用。
     *
     * 反事实：
     * - 把 ipset 那条 `-p udp ! --dport 53` 改成裸 `-j ACCEPT`，DNS 劫挂静默失效；
     * - 把规则引用集合前的 `ipset list` 护栏删掉，空集合会让整条规则插不进去；
     * - 把 v6 的 ip6tables 判定删掉，没装 ip6tables 的设备会静默丢掉 v6 旁路。
     */
    @Test
    fun localAddressBypassKeepsTheDnsHoleOnBothCarriers() {
        val chain = shellFunctionBody(tproxySh, "setup_proxy_chain")
        val addrtype = shellFunctionBody(tproxySh, "local_addr_rule_addrtype")
        val ipsetRule = shellFunctionBody(tproxySh, "local_addr_rule_ipset")

        // 两条载体各成对出现，且都排除 DNS 端口。
        assertTrue("addrtype 载体缺 DNS 例外",
            addrtype.contains("addrtype --dst-type LOCAL -p udp ! --dport 53 -j ACCEPT"))
        assertTrue("addrtype 载体缺非 UDP 放行",
            addrtype.contains("addrtype --dst-type LOCAL ! -p udp -j ACCEPT"))
        assertTrue("ipset 载体缺 DNS 例外",
            ipsetRule.contains("-m set --match-set \"\$set_name\" dst -p udp ! --dport 53 -j ACCEPT"))
        assertTrue("ipset 载体缺非 UDP 放行",
            ipsetRule.contains("-m set --match-set \"\$set_name\" dst ! -p udp -j ACCEPT"))
        // 引用不存在的 set 会让整条规则加不上 —— 必须有存在性护栏。
        assertTrue("ipset 载体必须先确认集合存在", ipsetRule.contains("ipset list \"\$set_name\""))

        // 建链按 local_addr_carrier 的逐族判定分叉，不自己重复判一遍能力。
        assertTrue("建链必须按地址族问载体",
            chain.contains("local _local_primary=\"\$(local_addr_carrier \"\$family\")\""))
        // addrtype 探测通过不代表规则一定插得进规则链 —— 失败必须能退回 ipset。
        assertTrue("addrtype 失败必须能退回 ipset 载体", chain.contains("local_addr_rule_ipset \"\$cmd\""))
        // 回退要现场建集合：初始那轮判定的是 addrtype、没建表，引用空集合等于没规则。
        assertTrue("回退要先建集合再插规则", chain.contains("setup_local_addr_sets \"\$family\""))
        assertTrue("回退动作要留痕", chain.contains("falling back to ipset"))
        // 两条路都失败时不能静默 —— 旁路失效就等于本机流量进隧道。
        assertTrue("旁路彻底不可用必须告警说明后果", chain.contains("still be proxied"))
    }

    @Test
    fun localAddressCarrierIsDecidedPerFamily() {
        val carrier = shellFunctionBody(tproxySh, "local_addr_carrier")
        val features = shellFunctionBody(tproxySh, "init_feature_flags")

        assertTrue("有 xt_addrtype 时优先用它", carrier.contains("[ \"\$HAS_ADDRTYPE\" -ne 1 ]"))
        assertTrue("ipset 载体必须同时具备集合工具与匹配模块",
            carrier.contains("[ \"\$HAS_IPSET\" -eq 1 ] && [ \"\$HAS_XT_SET\" -eq 1 ]"))
        // v6 单独判：v4 的 addrtype 符号在，不代表 ip6tables 那半能用。
        assertTrue("v6 必须单独看 ip6tables", carrier.contains("[ \"\$family\" = \"6\" ]"))
        assertTrue("ip6tables 探测必须单独采集", features.contains("command -v ip6tables"))
        assertTrue("ip6tables 探测结果必须落成标志位", features.contains("HAS_IP6TABLES=1"))
        assertTrue("载体三态齐全", carrier.contains("echo \"addrtype\"")
            && carrier.contains("echo \"ipset\"") && carrier.contains("echo \"none\""))
    }

    /**
     * 本机地址旁路「有加必有撤」，且**建集合必须早于建链**。
     *
     * BYPASS_IP 里的 `-m set --match-set localaddr` 在集合不存在时加不上，所以建表必须
     * 先于 setup_tproxy_chain4；后台同步进程又必须随 stop 一起收掉，否则 proxy 停了
     * 它还活着，会对着一个已经被销毁的集合反复刷日志。
     */
    @Test
    fun localAddressBypassIsTornDownOnStop() {
        val start = shellFunctionBody(tproxySh, "start_proxy")
        val stop = shellFunctionBody(tproxySh, "stop_proxy")

        val setsAt = start.indexOf("setup_local_addr_sets")
        val chainAt = start.indexOf("setup_tproxy_chain4")
        assertTrue("start_proxy 必须建本机地址集合", setsAt >= 0)
        assertTrue("start_proxy 必须建链", chainAt >= 0)
        assertTrue("setup_local_addr_sets 必须先于建链，否则 -m set 规则加不上", setsAt in 0 until chainAt)
        assertTrue("stop_proxy 必须清本机地址集合（含后台进程）", stop.contains("cleanup_local_addr"))
    }

    /**
     * stop 时的本机地址清理必须**无条件**执行。
     *
     * 集合和后台进程是**上一次**启动留下的，这一轮的配置值不能决定要不要清理它们。
     * 典型漏网场景：`runtime_tproxy.conf` 丢了（半路崩掉、CONFIG_DIR 不一致），stop 回落到
     * 当前配置，而用户恰好把开关关了 —— 按开关早退就会把一个一直刷日志的孤儿 watcher 和
     * 两个 ipset 留在那儿。反过来销毁不存在的集合是无害空操作，所以无条件清理总是对的。
     */
    @Test
    fun localAddressCleanupRunsUnconditionallyOnStop() {
        val cleanup = shellFunctionBody(tproxySh, "cleanup_local_addr")

        assertTrue("必须收掉后台进程", cleanup.contains("stop_local_addr_watcher"))
        assertTrue("必须销毁两个地址族的集合",
            cleanup.contains("ipset destroy localaddr") && cleanup.contains("ipset destroy localaddr6"))
        assertTrue("销毁必须容忍集合不存在", cleanup.contains("ipset destroy localaddr 2> /dev/null || true"))
        // 允许按当前开关打一条诊断日志，但不能 return —— 早退正是孤儿进程的来源。
        val guardAt = cleanup.indexOf("[ \"\$BYPASS_LOCAL_ADDRS\" -ne 1 ]")
        if (guardAt >= 0) {
            val guardBody = cleanup.substring(guardAt, cleanup.indexOf("\n    fi", guardAt))
            assertFalse("开关关闭时不能提前 return，只能继续清理", guardBody.contains("return"))
        }
    }

    /**
     * 本机地址旁路的开关与间隔必须校验成正整数。
     *
     * 这些值下面全用在算术比较（`[ "$X" -eq 1 ]`）里。写进非数字会让每次比较变成
     * `[: illegal number`，旁路于是**静默**失效 —— 日志里什么都看不到，本机流量照样进隧道。
     * 所以要么用合法值，要么回落默认值并响亮告警，不能默默变成立。
     */
    @Test
    fun localAddressBypassNumbersAreValidated() {
        val load = shellFunctionBody(tproxySh, "load_config")

        assertTrue("必须逐个校验本机地址旁路的数字配置", load.contains("for _var in BYPASS_LOCAL_ADDRS"))
        assertTrue("校验必须是正整数判定", load.contains("is_positive_integer \"\${!_var}\""))
        assertTrue("四个开关都要在校验范围内",
            load.contains("LOCAL_ADDR_USE_MONITOR") && load.contains("LOCAL_ADDR_POLL_INTERVAL")
                && load.contains("LOCAL_ADDR_SWEEP_INTERVAL"))
        assertTrue("不合法必须回落默认值", load.contains("DEFAULT_LOCAL_ADDR_POLL_INTERVAL"))
        assertTrue("回落必须留痕", load.contains("using defaults"))
    }

    /**
     * 后台同步有两种模式（`ip monitor address` 事件驱动 / 定时轮询），但**对账必须只有一份**。
     *
     * 两种模式各写一份对账逻辑，早晚会在某次改动里只改一处 —— 于是同一个地址变化在两种
     * 模式下得到不同结果，而退化路径（monitor 不可用自动切轮询）会把这个分歧藏掉：开发机上
     * monitor 可用，线上机型不一定。
     *
     * 退化必须是自动的：探测和尝试是同一个动作，monitor 起不来就退轮询，
     * 不需要人工判断的开关。
     *
     * 对账要覆盖两个地址族，所以共用点必须是最外层的 `sync_local_ipset_both`：只要有一处
     * 直接调 `sync_local_ipset ""`，两个族的一致性就会在那一处开始漂移。
     */
    @Test
    fun localAddressSyncIsSharedByEventModeAndPollingFallback() {
        val watch = shellFunctionBody(tproxySh, "local_addr_watch_loop")
        val monitor = shellFunctionBody(tproxySh, "local_addr_monitor_loop")
        val poll = shellFunctionBody(tproxySh, "local_addr_poll_loop")
        val both = shellFunctionBody(tproxySh, "sync_local_ipset_both")

        // 两种模式（含事件模式里的慢速清扫）和启动时的首次对账，走同一个两族入口。
        assertTrue("事件模式必须走 sync_local_ipset_both", monitor.contains("sync_local_ipset_both"))
        assertTrue("轮询模式必须走 sync_local_ipset_both", poll.contains("sync_local_ipset_both"))
        assertTrue("启动时的首次对账也必须走同一入口", watch.contains("sync_local_ipset_both"))
        // 一次对账两个族，且各族的返回值必须原样透传，不能塌成一个「失败」。
        assertTrue("两族都要对账", both.contains("sync_local_ipset_family 4")
            && both.contains("sync_local_ipset_family 6"))
        assertTrue("非 0 返回值必须原样透传", both.contains("return \"\$rc\""))

        // monitor 没跑起来必须退轮询，而不是报错就停着。
        assertTrue("事件模式挂了必须退回轮询", watch.contains("if ! local_addr_monitor_loop; then"))
        assertTrue("退化后必须真的进入轮询循环", watch.contains("local_addr_poll_loop"))
        // 开关默认开；配置里没写时按 `:-` 落到默认值，而不是落成空串。
        assertTrue(
            "monitor 开关必须走 :- 默认值",
            tproxySh.contains("LOCAL_ADDR_USE_MONITOR=\"\${LOCAL_ADDR_USE_MONITOR:-"),
        )
        assertTrue("默认应开启 monitor", tproxySh.contains("DEFAULT_LOCAL_ADDR_USE_MONITOR=1"))
    }

    /**
     * 对账的返回值必须能区分三种结局，否则调用方不知道该退、该重试、还是该当成功。
     *
     * - 0：已对齐；
     * - 1：集合没了（代理正在停）—— 调用方应当退出，这正是 watcher 的干净退场方式；
     * - 2：集合在但写不进去 —— 记日志、下个周期重试，**不能**当成功吞掉。
     *
     * 1 与 2 的差别是「退场」与「重试」。混成 2 会让 watcher 空转到下个周期才退；
     * 混成 0 会留一个永远报「正常」的进程对着已销毁的集合刷日志。
     */
    @Test
    fun localAddressSyncFailureCodesStayDistinct() {
        val sync = shellFunctionBody(tproxySh, "sync_local_ipset")

        assertTrue("集合不存在必须返回 1", sync.contains("return 1"))
        assertTrue("写不进去必须返回 2", sync.contains("return 2"))
        assertTrue("对齐成功必须返回 0", sync.contains("return 0"))
        // 并发同步是预期内的：事件循环和慢速清扫会同时对同一个集合做增删。add 撞
        // already-exists 必须当成功，否则每个并发周期都会误报 rc=2。
        assertTrue("并发 add 撞 already-exists 视为幂等成功", sync.contains("*\"already exists\"*)"))
        // 开头那次 list 过了、到 add 时集合才被销毁 —— 那是 1 不是 2，别记成误导性 Error。
        assertTrue("对账中途集合消失必须归为 rc=1", sync.contains("disappeared mid-sync"))
        // 删不到成员只能是并发同步同时动了它，下一个周期还会再对账，所以不算失败。
        assertTrue("del 失败必须当作并发幂等而不是错误", sync.contains("not a member"))
    }

    /**
     * 同步哪些地址族由「集合真建了没有」决定，不能照搬 PROXY_IPV6。
     *
     * `PROXY_IPV6` 表示「v6 代理链要不要建」，`LOCAL_ADDR_FAMILIES` 表示「localaddr6 这个
     * 集合在不在」。两者不等价：v6 走 addrtype 时代理链在、集合没建，同步它只会每个周期
     * 刷一条失败。
     *
     * 它是推导出来的状态、不在配置文件里，而 watcher 是重新执行本脚本的子进程 —— 不显式
     * 带过去，子进程会把默认值当事实，去同步一个根本没建的集合。
     */
    @Test
    fun localAddressFamiliesArePassedAcrossTheWatcherReexec() {
        val familyFn = shellFunctionBody(tproxySh, "sync_local_ipset_family")
        val watcher = shellFunctionBody(tproxySh, "start_local_addr_watcher")
        val watch = shellFunctionBody(tproxySh, "local_addr_watch_loop")

        // 族标签 4/6 与集合后缀 ""/6 不是同一个东西，映射错了集合名会变成 localaddr4。
        assertTrue("族标签到集合后缀必须有显式映射",
            familyFn.contains("6) suffix=\"6\" ;;") && familyFn.contains("4) suffix=\"\" ;;"))
        assertTrue("门控必须按 LOCAL_ADDR_FAMILIES 而不是 PROXY_IPV6",
            familyFn.contains("case \"\$LOCAL_ADDR_FAMILIES\" in"))
        // 子进程重跑本脚本，推导出来的状态必须靠环境变量带过去。
        assertTrue("watcher 子进程必须显式继承 LOCAL_ADDR_FAMILIES",
            watcher.contains("LOCAL_ADDR_FAMILIES=\"\$LOCAL_ADDR_FAMILIES\" nohup"))
        // 全局初始化不能硬赋值，否则会把继承来的值盖掉。
        assertTrue("全局默认值必须用 :- 保留继承值",
            tproxySh.contains("LOCAL_ADDR_FAMILIES=\"\${LOCAL_ADDR_FAMILIES:-4}\""))
        // 集合没了就退，别对着空集合刷日志。
        assertTrue("watcher 收到 rc=1 必须退出", watch.contains("exit 0"))
    }

    /**
     * 事件模式的生命周期必须自己收干净，否则每次 stop/start 都会漏东西。
     *
     * 四个后果，每一个都必须是「静默失败」而不是「炸一下就算了」：
     * - 用管道（`ip monitor | while read`）时 `exit` 只杀子 shell，watcher 会无限重启 monitor；
     * - `wait` 一个还活着的 monitor 会永久阻塞，集合销毁后进程退不出来；
     * - stop 走 SIGTERM，循环体内的清理一条都执行不到，monitor 会带着 netlink 句柄变孤儿；
     * - monitor 的 socket 活着却不吐事件时永远不 EOF，事件模式会静默停摆。
     */
    @Test
    fun eventModeWatcherCleansUpAfterItself() {
        val monitor = shellFunctionBody(tproxySh, "local_addr_monitor_loop")
        val stop = shellFunctionBody(tproxySh, "local_addr_monitor_stop")
        val poll = shellFunctionBody(tproxySh, "local_addr_poll_loop")
        val cleanup = shellFunctionBody(tproxySh, "cleanup_local_addr")

        // FIFO 而不是管道：read 必须在 watcher 自己的 shell 里阻塞，exit 才能真正退出。
        assertTrue("必须用 mkfifo 建命名管道", monitor.contains("mkfifo"))
        assertTrue("read 必须从 FIFO 读，不能是管道里的子 shell", monitor.contains("read -r _line < \"\$fifo\""))
        // 退出口必须能真正终止进程：monitor 此刻还活着，wait 会永久阻塞。
        assertTrue("退出前要收掉 monitor 子进程", stop.contains("kill \"\$1\""))
        assertTrue("退出前要收掉慢速清扫", stop.contains("kill \"\$3\""))
        assertTrue("退出前要清掉 FIFO", stop.contains("rm -f \"\$2\""))
        assertTrue("退出必须真正 exit", stop.contains("exit 0"))
        // SIGTERM 要兜住：信号到达时循环体里的清理不会执行。
        assertTrue("必须装信号 trap 兜 SIGTERM", monitor.contains("trap 'local_addr_monitor_stop"))
        // 集合被销毁是 rc=1：必须走统一收口，不能直接 exit 0 漏收子进程。
        assertTrue("集合销毁必须走统一收口",
            monitor.contains("local_addr_monitor_stop \"\$mp\" \"\$fifo\" \"\$sp\""))
        // 事件流 EOF 那条路也要收掉慢速清扫，不能只收 monitor。
        assertTrue("事件流 EOF 后必须收掉慢速清扫", monitor.contains("kill \"\$sp\""))
        // 轮询里的 sleep 得可中断，否则 stop 的 SIGTERM 最长要等一个完整周期。
        assertTrue("poll 的 sleep 必须放后台 + wait 才能被打断", poll.contains("sleep \"\$interval\" &"))
        assertTrue("poll 必须 wait 掉那个 sleep", poll.contains("wait \$!"))
        // monitor 的 socket 活着却不吐事件时永远不 EOF —— 靠独立节奏的慢速清扫兜住。
        assertTrue("事件模式必须挂一个独立节奏的慢速清扫",
            monitor.contains("local_addr_poll_loop \"\$LOCAL_ADDR_SWEEP_INTERVAL\" \"Local address sweep\""))
        assertTrue("清扫间隔必须可配且有默认值",
            tproxySh.contains("LOCAL_ADDR_SWEEP_INTERVAL=\"\${LOCAL_ADDR_SWEEP_INTERVAL:-")
                && tproxySh.contains("DEFAULT_LOCAL_ADDR_SWEEP_INTERVAL="))
        // SIGKILL 那条路 trap 跑不到，stop 时顺手把残留 FIFO 扫掉。
        assertTrue("stop 必须扫掉残留 FIFO", cleanup.contains("localaddr_monitor.*.fifo"))
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
