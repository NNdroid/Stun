package app.fjj.stun.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 端口契约。
 *
 * 这组断言存在的原因是一次真实漂移：`LatencyProber` 与 `SpeedTestManager` 各自
 * 私藏了一份 `1080` / `53`，而服务真正监听的是 `10808` / `10553`。它当时**没有**
 * 造成线上故障 —— 已核实 Go 侧 `DialNode` 只读 `sshAddr` / `proxyAddr`，
 * `local_addr` 仅在 `engine.go` 起长期 SOCKS5 服务时才被消费。
 *
 * 但那意味着正确性依赖「Go 侧恰好不读这个字段」这一隐式约定：一旦哪天
 * `DialNode` 改为尊重 `local_addr` 以复用连接，探测会立刻因连不上
 * `127.0.0.1:1080` 而全量失败，且报错指向「网络不通」而非「端口写错」，
 * 排查成本极高。所以改成引用 [TProxyPorts] 单一来源，并在这里钉住。
 */
class TProxyPortsContractTest {

    @Test
    fun vpnAndTproxyAgreeOnSocksAndDnsPorts() {
        // 两种模式共用同一个本地 SOCKS5 / DNS 端口：myssh 的 local_addr 与
        // tproxy 的 socks5.port 都指向它。不一致会导致"一种模式能连另一种不能"。
        assertEquals(TProxyPorts.SOCKS, TProxyPorts.VPN.SOCKS)
        assertEquals(TProxyPorts.DNS, TProxyPorts.VPN.DNS)
        assertEquals(TProxyPorts.SOCKS, TProxyPorts.TProxy.SOCKS)
        assertEquals(TProxyPorts.DNS, TProxyPorts.TProxy.DNS)
    }

    @Test
    fun tproxyPortIsDistinctFromSocksPort() {
        // TPROXY 与 SOCKS 必须分开：同一个端口上跑两种 listener 会 bind 冲突。
        // 历史上就是 10808 / 10812 这一对。
        assertNotEquals(TProxyPorts.SOCKS, TProxyPorts.TPROXY)
        assertEquals(10808, TProxyPorts.TProxy.SOCKS)
        assertEquals(10812, TProxyPorts.TProxy.TPROXY)
    }

    @Test
    fun dnsPortIsNotThePrivilegedPort53() {
        // 绑定 53 需要特权，app 进程没有。所以服务一律用 10553 之类的非特权端口，
        // 53 只在 tproxy.sh 里作为"被劫持的目标端口"出现（重定向到 DNS_PORT）。
        // 有人图省事写 53 会直接 bind 失败，且只在真机暴露。
        assertNotEquals(53, TProxyPorts.DNS)
        assertEquals(10553, TProxyPorts.DNS)
    }

    @Test
    fun serviceCompanionsExposeTheSamePortsAsTheContract() {
        // 服务侧的别名必须与契约一致 —— 它们是给各自 service 内部用的，
        // 有人绕过 TProxyPorts 直接写新字面量时这里会炸。
        assertEquals(TProxyPorts.SOCKS, MyVpnService.SOCKS_PORT)
        assertEquals(TProxyPorts.DNS, MyVpnService.DNS_PORT)
    }

    @Test
    fun tproxyConfigBuilderEmitsTheContractPorts() {
        // 端到端：builder 写进 tproxy.sh / yaml 的端口必须就是契约里的值。
        // 这两个下游消费者（tproxy.sh 的 PROXY_TCP_PORT、yaml 的 socks5.port）
        // 是 Go 进程实际 bind 的依据，对不上就是"服务起不来但没报错"。
        val rules = TransparentProxyConfigBuilder.buildShellRules(
            selfPackage = "app.fjj.stun",
            tproxyPort = TProxyPorts.TProxy.TPROXY,
            dnsPort = TProxyPorts.DNS,
            appFilter = AppFilter.EMPTY,
        )
        assertTrue(rules.contains("PROXY_TCP_PORT=${TProxyPorts.TPROXY}"))
        assertTrue(rules.contains("PROXY_UDP_PORT=${TProxyPorts.TPROXY}"))
        assertTrue(rules.contains("DNS_PORT=${TProxyPorts.DNS}"))

        val yaml = TransparentProxyConfigBuilder.buildCoreYaml(
            socksPort = TProxyPorts.SOCKS,
            tproxyPort = TProxyPorts.TPROXY,
        )
        assertTrue(yaml.contains("port: ${TProxyPorts.TPROXY}"))
        assertTrue(yaml.contains("socks5:\n  port: ${TProxyPorts.SOCKS}"))
    }

    @Test
    fun shellDefaultsMatchTheContract() {
        // tproxy.sh 里的 DEFAULT_* 是 Kotlin 侧没传值时的兜底。两边不同步的话，
        // 「谁先写配置」决定实际端口，表现为偶发的连不上。
        //
        // assets 不在 JVM 测试的 classpath 上（它在 APK 里），所以只能按文件系统找。
        // 逐级上溯是因为 test 的工作目录是 `core/`，而脚本在 `core/src/main/assets/`。
        var dir: File? = File("").absoluteFile
        var script: File? = null
        while (dir != null && script == null) {
            script = File(dir, "src/main/assets/scripts/tproxy.sh")
            if (!script.isFile) script = null
            dir = dir.parentFile
        }
        val text = script?.readText()
            ?: error("tproxy.sh not found walking up from ${File("").absolutePath}")

        assertTrue(
            "tproxy.sh DEFAULT_PROXY_TCP_PORT must equal TProxyPorts.TPROXY",
            text.contains("""readonly DEFAULT_PROXY_TCP_PORT="${TProxyPorts.TPROXY}""""),
        )
        assertTrue(
            "tproxy.sh DEFAULT_PROXY_UDP_PORT must equal TProxyPorts.TPROXY",
            text.contains("""readonly DEFAULT_PROXY_UDP_PORT="${TProxyPorts.TPROXY}""""),
        )
        assertTrue(
            "tproxy.sh DEFAULT_DNS_PORT must equal TProxyPorts.DNS",
            text.contains("""readonly DEFAULT_DNS_PORT="${TProxyPorts.DNS}""""),
        )
    }

    @Test
    fun noSourceFileHardcodesTheStalePorts() {
        // 兜底扫描：曾经错误的 1080 / 53 不该再以字面量形式出现在**任何模块**的生产代码里。
        //
        // 扫两种形态：
        //  - `SOCKS_PORT = 1080` / `DNS_PORT = 53`：私藏一份常量的老写法（util 包里的初犯）。
        //  - `..., 1080, 53`：把错值当**位置参数**传给 buildMySshConfig。这种写法在 app / tv /
        //    car / xr 四个 UI 模块各复制了一份 —— 参数类型是 Int，1080 与 10808 编译起来毫无
        //    区别，只有文本扫描能抓住。
        //
        // 按模块目录递归扫而不是列文件白名单：白名单会让"新加一个模块、顺手复制一份老代码"
        // 悄悄绕过检查。因此这里枚举仓库根下所有带 src/main 的模块。
        //
        // 只扫 src/main：`app/src/androidTest` 的 VpnConfigBuilderTest 是**故意**传 1080/53 并
        // 断言 `"127.0.0.1:1080"`，它在验证端口参数被正确透传，属于参数化测试而非遗留副本。
        //
        // 断言必须真的执行：早先用 classLoader 读源码，读不到时 `return@forEach`
        // 静默跳过，测试照样 pass —— 那等于什么都没检查。找不到仓库根要 error。
        val repoRoot = locateRepoRoot()
            ?: error("repo root not found walking up from ${File("").absolutePath}")
        val modules = repoRoot.listFiles()
            ?.filter { it.isDirectory && File(it, "src/main").isDirectory }
            .orEmpty()
        val required = listOf("app", "core", "tv", "car", "xr")
        assertTrue(
            "expected production modules not found under $repoRoot: " +
                required.filterNot { modules.any { m -> m.name == it } },
            required.all { name -> modules.any { it.name == name } },
        )

        val staleAssignment = Regex("""(SOCKS|DNS)_PORT\s*=\s*(1080|53)\b""")
        val stalePositional = Regex("""\b1080\s*,\s*53\b|\b53\s*,\s*1080\b""")
        val offenders = mutableListOf<String>()
        var scanned = 0
        for (module in modules) {
            Files.walk(File(module, "src/main").toPath()).use { walk ->
                for (entry in walk.iterator().asSequence()
                    .filter { it.toFile().isFile && it.fileName.toString().endsWith(".kt") }) {
                    scanned++
                    val rel = repoRoot.toPath().relativize(entry).toString().replace('\\', '/')
                    entry.toFile().readLines().forEachIndexed { i, line ->
                        // 排除注释行：KDoc 里会说明"原值 1080/53 是错的"，那正是要保留的说明。
                        val code = line.trimStart().removePrefix("//").trimStart()
                        if (code.startsWith("*") || code.startsWith("/*") || code.startsWith("#")) {
                            return@forEachIndexed
                        }
                        if (staleAssignment.containsMatchIn(code) || stalePositional.containsMatchIn(code)) {
                            offenders += "$rel:${i + 1}  $code"
                        }
                    }
                }
            }
        }
        assertTrue("expected to scan production kt files under every module", scanned > 100)
        assertTrue(
            "stale hardcoded ports reintroduced:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** 从当前工作目录逐级上溯，找到仓库根（第一个含 settings.gradle[.kts] 的目录）。 */
    private fun locateRepoRoot(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile || File(dir, "settings.gradle").isFile) {
                return dir
            }
            dir = dir.parentFile
        }
        return null
    }

    /** 从当前工作目录逐级上溯，找到第一个存在的相对路径。 */
    private fun locateSourceDir(relative: String): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        return null
    }
}
