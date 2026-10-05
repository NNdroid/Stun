package app.fjj.stun.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JniPatchContractTest {
    private fun patch(name: String): String {
        val root = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(root, "jni/patches/$name.patch"),
            File(root, "core/jni/patches/$name.patch"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("patch not found: $name; cwd=${root.absolutePath}")
        return file.readText()
    }

    @Test
    fun bothFrontendsRecoverDomainAndAvoidEchCoverNameRewrite() {
        for (name in listOf("hev-socks5-tproxy", "hev-socks5-tunnel")) {
            val text = patch(name)
            assertTrue("$name must parse TLS SNI", text.contains("sniff_tls"))
            assertTrue("$name must parse HTTP Host", text.contains("sniff_http"))
            assertTrue("$name must avoid ECH cover-name rewrites", text.contains("type == 0xfe0d"))
            assertTrue(
                "$name must tell 'need more bytes' apart from 'not TLS/HTTP'",
                text.contains("tls_res < 0 || http_res < 0"),
            )
        }
    }

    /**
     * 两侧的落地方式**故意不同**，不能用同一套断言：
     *
     * - tunnel 走 SOCKS5，把域名写进 base.addr 就会随 CONNECT 发给隧道 ⇒ 必须有
     *   `hev_socks5_addr_from_name`。
     * - tproxy 的目的地由内核经 IP_TRANSPARENT 绑在原 socket 上，splice 只做
     *   转发、不读 addr，所以改 base.addr 完全无效 —— 必须自己按域名重连。
     *   若哪天这里又出现 `hev_socks5_addr_from_name`，说明有人把无效逻辑搬回来了。
     */
    @Test
    fun tunnelEmitsSocksDomainTargetButTproxyReconnectsInstead() {
        val tunnel = patch("hev-socks5-tunnel")
        assertTrue(
            "tunnel must emit SOCKS domain targets",
            tunnel.contains("hev_socks5_addr_from_name"),
        )

        val tproxy = patch("hev-socks5-tproxy")
        assertTrue(
            "tproxy must reconnect to the recovered domain",
            tproxy.contains("hev_socks5_session_tcp_reconnect"),
        )
        assertTrue(
            "tproxy must wait for the first packet on the event loop, not poll",
            tproxy.contains("hev_task_io_socket_recv") && tproxy.contains("MSG_PEEK"),
        )
        assertFalse(
            "tproxy must not rewrite base.addr: the socket is already bound by IP_TRANSPARENT",
            tproxy.contains("hev_socks5_addr_from_name"),
        )
        // 只看 patch 新增的**代码行**：注释里会解释「为什么不用 MSG_DONTWAIT」，
        // 那正是要保留的说明，不能因为提到它就判定在用。
        assertFalse(
            "tproxy must not busy-poll with MSG_DONTWAIT: the fd is not in the event loop yet",
            tproxy.lineSequence().any { raw ->
                if (!raw.startsWith("+")) return@any false
                val body = raw.substring(1).trimStart()
                !body.startsWith("*") && !body.startsWith("//") && "MSG_DONTWAIT" in body
            },
        )
    }

    @Test
    fun sniffingDoesNotEnableIpv4OnlyMappedDns() {
        val tunnel = patch("hev-socks5-tunnel")
        assertFalse(tunnel.contains("mapdns:"))
    }

    /**
     * 「AAAA 排在前面但出口是 IPv4-only」必须能退回 IPv4，否则是静默失败。
     *
     * 这段逻辑住在 `src/core`（嵌套 submodule），所以由独立 patch 承载：
     * `hev-socks5-<x>--src-core.patch`。名字里的 `--` 与 `-` 由
     * `applyJniPatches` 的 `submoduleDirFor()` 解析成 `hev-socks5-<x>/src/core`。
     *
     * 四个要点都要钉住，少一个就退���坏行为：
     * 1. **并发**发起两个 attempt（RFC 6555 happy-eyeballs）—— 顺序重试会让
     *    「首选 family 完全不可达」的总耗时翻倍，用户能感知到；
     * 2. Connection Attempt Delay 必须是 250ms（RFC 6555 §1）；
     * 3. 失败要回收 fd —— `hev_task_del_fd` 只摘事件循环，**不** close，
     *    两者缺一不可，否则泄漏描述符；
     * 4. 解析时按 want_family 过滤 addrinfo 链，而不是无脑取链表头。
     */
    @Test
    fun coreConnectUsesRfc6555ConcurrentHappyEyeballs() {
        for (name in listOf("hev-socks5-tproxy--src-core", "hev-socks5-tunnel--src-core")) {
            val text = patch(name)
            assertTrue(
                "$name must race both address families instead of trying them in sequence",
                text.contains("hev_task_channel_select_read"),
            )
            assertTrue(
                "$name must run each attempt in its own task",
                text.contains("hev_task_run") && text.contains("hev_task_new"),
            )
            assertTrue(
                "$name must use RFC 6555's 250ms Connection Attempt Delay",
                text.contains("HEV_SOCKS5_HAPPY_EYEBALLS_DELAY_MS 250"),
            )
            assertTrue(
                "$name must free the socket on a failed attempt or leak the fd " +
                    "(del_fd only detaches from the event loop, it does not close)",
                text.contains("hev_task_del_fd") && text.contains("close (fd)"),
            )
            assertTrue(
                "$name must walk the addrinfo list instead of taking the head only",
                text.contains("ai = ai->ai_next"),
            )
            assertTrue(
                "$name must join every attempt before destroying the channel, " +
                    "otherwise the entry's hev_task_channel_write is a use-after-free",
                text.contains("hev_task_join") && text.contains("hev_task_unref"),
            )
        }
    }

    /**
     * 单 family 可用时必须走直连快路径。
     *
     * 域名只有 A 记录、或者目标是 IP 字面量时，第二个 family 根本无从解析。
     * 这时若仍进并发路径，就要为一次 connect 付出 spawn task + channel +
     * select 的全部开销。断言 `n_available == 1` 分支存在，
     * 防止有人"为了代码统一"把它删掉。
     */
    @Test
    fun coreConnectKeepsSingleFamilyFastPath() {
        for (name in listOf("hev-socks5-tproxy--src-core", "hev-socks5-tunnel--src-core")) {
            val text = patch(name)
            assertTrue(
                "$name must short-circuit when only one address family resolves",
                text.contains("n_available == 1"),
            )
        }
    }
}
