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
        for (key in listOf(
            "PROXY_TCP_PORT", "PROXY_UDP_PORT", "PROXY_MODE", "DNS_HIJACK_ENABLE", "DNS_PORT",
            "APP_PROXY_ENABLE", "APP_PROXY_MODE", "BYPASS_APPS_LIST", "PROXY_APPS_LIST", "DRY_RUN",
            "BYPASS_DST_LIST", "SSH_SERVER_ENTRY",
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
