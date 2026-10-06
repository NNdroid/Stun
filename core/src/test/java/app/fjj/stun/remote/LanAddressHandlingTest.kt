package app.fjj.stun.remote

import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.fail

/**
 * 局域网地址与 URL 拼装的护栏。
 *
 * ## 它钉住的故障
 * 手机端能**搜到** TV、但每一台都显示「无法连接」。发现面是好的（NSD 正常回包），
 * 坏在发现之后的**地址选用**与 **URI 拼装**两步上：
 *
 *  - mDNS resolve 返回的地址顺序不保证 IPv4 在前，而服务端 `embeddedServer(CIO, port)`
 *    不传 host = 只监听 `0.0.0.0`（纯 IPv4）；
 *  - IPv6 字面量含冒号，直接拼进 URI 会让 Ktor **抛 `URLParserException`**（不是连接失败）；
 *  - 三个客户端方法各自 catch 异常后返回 null / ERROR / false，调用点只能解读成「设备不在线」。
 *
 * 三段链路各漏一点都会得到同一个症状，且 logcat 里没有任何异常可见，所以用结构断言钉住。
 */
class LanAddressHandlingTest {

    private val remoteSync: String =
        File("src/main/java/app/fjj/stun/remote/RemoteSyncManager.kt").readText()

    // ── lanBaseUrl ────────────────────────────────────────────────

    @Test
    fun `IPv4 基地址保持不变且可解析`() {
        val base = RemoteSyncManager.lanBaseUrl("192.168.1.50", 54321)
        assertEquals("http://192.168.1.50:54321", base)

        val b = URLBuilder().takeFrom("$base/api/status")
        assertEquals("192.168.1.50", b.host)
        assertEquals(54321, b.port)
        assertEquals("http://192.168.1.50:54321/api/status", b.buildString())
    }

    @Test
    fun `IPv6 字面量必须加方括号，否则 Ktor 直接抛解析异常`() {
        val global = RemoteSyncManager.lanBaseUrl("2408:4003:1234:5678::1", 54321)
        assertEquals("http://[2408:4003:1234:5678::1]:54321", global)

        val linkLocal = RemoteSyncManager.lanBaseUrl("fe80::1", 54321)
        assertEquals("http://[fe80::1]:54321", linkLocal)

        for (base in listOf(global, linkLocal)) {
            val b = URLBuilder().takeFrom("$base/api/status")
            assertEquals(54321, b.port)
            assertTrue(
                "加括号后 host 必须保留完整字面量（含冒号），实际= ${b.host}",
                b.host.contains(":")
            )
            assertEquals("$base/api/status", b.buildString())
        }
    }

    @Test
    fun `未加括号的 IPv6 authority 确实解析不了，这正是历史故障点`() {
        // 用真实解析器复现故障形态，而不是靠字符串比对：这样一旦有人把 lanBaseUrl 改回裸插值，
        // 本测试能证明「改回去就是坏的」，而不是只报一个字符串不相等。
        val broken = "http://2408:4003:1234:5678::1:54321"
        try {
            URLBuilder().takeFrom("$broken/api/status")
            fail("裸插 IPv6 应当抛解析异常，否则本测试的前提就不成立了")
        } catch (expected: Exception) {
            assertTrue(
                "应为 URLParserException，实际= ${expected::class.simpleName}",
                expected.message.orEmpty().contains("parse url", ignoreCase = true)
            )
        }
    }

    // ── addressRank ───────────────────────────────────────────────

    @Test
    fun `地址排序是 IPv4 优先，链路本地 IPv6 垫底`() {
        assertEquals(0, RemoteSyncManager.addressRank("192.168.1.50"))
        assertEquals(0, RemoteSyncManager.addressRank("localhost"))

        assertEquals(1, RemoteSyncManager.addressRank("2408:4003:1234:5678::1"))
        assertEquals(1, RemoteSyncManager.addressRank("fd00::1"))

        // fe80::/10：hostAddress 丢掉 %scope 后链路本地地址本来就路由不了
        assertEquals(2, RemoteSyncManager.addressRank("fe80::1"))
        assertEquals(2, RemoteSyncManager.addressRank("fe80::abcd:1234:5678:9abc"))
        // 字面量大小写不敏感（防御性：某些来源会给大写十六进制）
        assertEquals(2, RemoteSyncManager.addressRank("FE80::1"))
    }

    @Test
    fun `混合地址按可用性排序而非按数组顺序`() {
        val ranked = listOf("fe80::1", "192.168.1.50", "2408:4003:1234:5678::1")
            .sortedBy { RemoteSyncManager.addressRank(it) }
        assertEquals(listOf("192.168.1.50", "2408:4003:1234:5678::1", "fe80::1"), ranked)
    }

    // ── 结构护栏 ──────────────────────────────────────────────────

    @Test
    fun `客户端不能再用裸字符串拼 URL`() {
        // 这一串内插就是本次故障的原始形态；一旦回来，IPv6 探测必然再次静默失败。
        val forbidden = "\"http://\$host:\$port"
        assertTrue(
            "禁止裸插 \$host:\$port，必须走 lanBaseUrl",
            !remoteSync.contains(forbidden)
        )
        for (endpoint in listOf("/push_profile", "/api/status", "/api/control")) {
            assertTrue(
                "$endpoint 必须经 lanBaseUrl 构造",
                remoteSync.contains("lanBaseUrl(host, port)}$endpoint")
            )
        }
    }

    @Test
    fun `地址候选不能用单个 host 字段，也不能按 API 34 分叉`() {
        // 旧写法在 API 34 以下只能取 getHost() 返回的**单个**地址，拿到 IPv6 就再无退路；
        // 而 getHostAddresses() 自 API 23 起可用（minSdk 28），分叉纯属多余且是 bug 来源。
        assertTrue("必须取 hostAddresses 全量地址", remoteSync.contains("serviceInfo.hostAddresses"))
        assertTrue("禁止再读已废弃的单数 host", !remoteSync.contains("serviceInfo.host?"))
        assertTrue(
            "地址选择必须走统一的 resolveHostCandidates，不能就地拼 if/else",
            remoteSync.contains("fun resolveHostCandidates")
        )
        assertTrue("必须取排序后的第一个而非数组第一个", remoteSync.contains("candidates.first()"))
    }

    @Test
    fun `解析出的地址必须落到日志里，否则这条链再次静默时还是查不出来`() {
        // 本故障在 logcat 里没有任何异常可见：解析异常被客户端 catch 后只剩一句
        // 「Failed to fetch TV status」。没有「解析到了哪个地址」这条日志，下次只能再靠猜。
        assertTrue("resolve 结果必须记日志", remoteSync.contains("Resolved \${serviceInfo.serviceName}"))
        assertTrue("候选数量必须记日志", remoteSync.contains("candidateCount"))
    }

    @Test
    fun `空候选列表必须明确告警而不是静默丢弃设备`() {
        assertTrue("空候选要 warn，不能无声 return", remoteSync.contains("returned no usable address"))
    }

    // ── 双栈监听 ─────────────────────────────────────────────────

    @Test
    fun `服务端必须双栈监听，不能只绑 IPv4`() {
        // 只绑 0.0.0.0 时，网段一旦是 IPv6-only 接入，客户端拿到的地址一个都连不上，
        // 而候选地址排序再正确也变不出一个能连的地址 —— 那是服务端的问题，不是排序的问题。
        assertTrue("必须有双栈绑定地址常量", remoteSync.contains("BIND_HOST_DUAL_STACK = \"::\""))
        assertTrue("必须有 IPv4 回落地址常量", remoteSync.contains("BIND_HOST_IPV4_ONLY = \"0.0.0.0\""))
        assertTrue(
            "优先绑定必须走双栈地址",
            remoteSync.contains("host = BIND_HOST_DUAL_STACK, module = module")
        )
        assertTrue(
            "双栈失败必须有 IPv4 回落分支",
            remoteSync.contains("host = BIND_HOST_IPV4_ONLY, module = module")
        )
    }

    @Test
    fun `双栈退化判定只能基于行为观测，不能靠绑定异常`() {
        // 绑定抛出来的异常分不出「不支持 IPv6」和「端口冲突」，靠异常粘滞降级会把一台完全
        // 支持双栈的机器永久降成纯 IPv4。判定必须落在「IPv4 环回是否真的可达」上。
        assertTrue(
            "必须以 IPv4 环回可达作为健康判据",
            remoteSync.contains("probeHostRepeatedly(\"127.0.0.1\", port")
        )
        // 回落分支 = 「绑定抛异常」到「记录回落后的绑定地址」之间的那段。这段里绝不能出现粘滞标记。
        val fallbackBranch = remoteSync
            .substringAfter("Dual-stack bind failed")
            .substringBefore("activeBindHost = BIND_HOST_IPV4_ONLY")
        assertFalse(
            "回落分支不得直接置 forceIpv4OnlyBind（会把绑定失败误判成平台不支持 IPv6）",
            fallbackBranch.contains("forceIpv4OnlyBind")
        )
        // 全文件只允许一个赋值点，就在验活分支里
        val assignmentCount = remoteSync.split("forceIpv4OnlyBind = true").size - 1
        assertEquals(
            "forceIpv4OnlyBind 只能有一个赋值点",
            1,
            assignmentCount
        )
        val verifyBody = remoteSync.substringAfter("private fun verifyListening")
        assertTrue(
            "验活分支必须能置粘滞标记并重绑",
            verifyBody.contains("forceIpv4OnlyBind = true")
        )
        assertTrue(
            "退化必须触发一次 IPv4-only 重绑",
            verifyBody.contains("rebinding IPv4-only")
        )
        assertTrue(
            "重绑失败必须能终止而不是死循环",
            verifyBody.contains("rolling back")
        )
    }

    @Test
    fun `实际绑定方式必须进日志，否则又变成查不出来的静默状态`() {
        // 「IPv4 环回通不通」这种事实只能从日志看出来；不记就只能再靠猜一遍。
        assertTrue("启动日志必须带实际绑定地址", remoteSync.contains("(bind=\$activeBindHost)"))
        assertTrue("实际绑定地址必须有字段记录", remoteSync.contains("activeBindHost"))
    }

    @Test
    fun `探测失败必须给出可判别的端口诊断，否则用户只能看到同一个红字`() {
        // 「裝置暫時無法連線」在三种完全不同的故障下是同一句话：TV 进程没起、
        // 路由/防火墙挡住、TV 在跑但 HTTP 层卡住。界面上分不出来，日志里必须分得出来。
        assertTrue(
            "HTTP 失败必须带异常类型，message 单独看不判别",
            remoteSync.contains("Failed to fetch TV status from \$host:\$port: \${e::class.simpleName}")
        )
        assertTrue("HTTP 失败后必须补一道 TCP 端口诊断", remoteSync.contains("diagnoseLanPort(host, port)"))
        assertTrue("必须能区分端口拒连（TV 进程没起）", remoteSync.contains("catch (e: ConnectException)"))
        assertTrue("必须能区分端口超时（路由/防火墙）", remoteSync.contains("catch (e: SocketTimeoutException)"))
    }
}
