package app.fjj.stun.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
        // 兜底扫描：曾经错误的 1080 / 53 不该再以字面量形式出现在 util 包里。
        // 用文件级扫描而不是编译期检查，是因为这些常量是 `private const`，
        // 类型系统看不见它们的值 —— 只有文本扫描能抓住"复制一份新副本"这个动作。
        //
        // 断言必须真的执行：早先用 classLoader 读源码，读不到时 `return@forEach`
        // 静默跳过，测试照样 pass —— 那等于什么都没检查。找不到文件要 error。
        val utilDir = locateSourceDir("core/src/main/java/app/fjj/stun/util")
            ?: error("util source dir not found walking up from ${File("").absolutePath}")
        val offenders = mutableListOf<String>()
        var scanned = 0
        listOf("LatencyProber.kt", "SpeedTestManager.kt").forEach { name ->
            val file = File(utilDir, name)
            assertTrue("expected $name to exist at $file", file.isFile)
            scanned++
            file.readLines().forEachIndexed { i, line ->
                // 排除注释行：KDoc 里会说明"原值 1080/53 是错的"，那正是要保留的说明。
                val code = line.trimStart().removePrefix("//").trimStart()
                if (code.startsWith("*") || code.startsWith("/*") || code.startsWith("#")) {
                    return@forEachIndexed
                }
                val bad = Regex("""(SOCKS|DNS)_PORT\s*=\s*(1080|53)\b""").containsMatchIn(code)
                if (bad) offenders += "$name:${i + 1}  $code"
            }
        }
        assertEquals("expected to scan both probe files", 2, scanned)
        assertTrue(
            "stale hardcoded ports reintroduced:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
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
