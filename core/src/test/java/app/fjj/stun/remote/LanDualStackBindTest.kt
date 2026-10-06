package app.fjj.stun.remote

import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.response.respond
import java.net.ServerSocket
import java.net.Socket
import org.junit.Test
import org.junit.Assert.fail

/**
 * 局域网监听面的**绑定行为**实测。
 *
 * 把服务端从 `0.0.0.0`（纯 IPv4）改成 `::`（双栈）不是「换个字符串」：它依赖两件
 * 底层事实，而这件事实不成立时故障形态和「改之前」不一样，很难在真机上看出是谁引起的。
 * 所以在这里用真实 socket 把语义钉住：
 *
 *  1. 绑 `::` 之后 **IPv4 连接照样收**（Linux `ipv6.bindv6only` 默认 0，走 IPv4 映射地址）。
 *     如果这台平台不是这样，服务端会从「能收所有 IPv4 客户端」退化成「只能收 IPv6」，
 *     是最隐蔽的一种回归。
 *  2. 绑定失败必须**同步抛** —— 这样「双栈失败 → 回落纯 IPv4」可以在 `startServer` 里
 *     就地完成，不用赌异步验活。
 */
class LanDualStackBindTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun probe(host: String, port: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(java.net.InetSocketAddress(host, port), 1_500)
                // 只探到 TCP 握手不够：确认对端真的在发 HTTP 响应
                s.outputStream.write("GET /p HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".toByteArray())
                s.outputStream.flush()
                s.setSoTimeout(1_500)
                val buf = ByteArray(256)
                val n = s.inputStream.read(buf)
                n > 0 && String(buf, 0, n).startsWith("HTTP/1.1")
            }
        } catch (t: Throwable) {
            false
        }
    }

    private val module: Application.() -> Unit = {
        routing {
            get("/p") { call.respond("pong") }
        }
    }

    @Test
    fun `绑定双栈通配地址后 IPv4 客户端仍然连得上`() {
        val port = freePort()
        val server = embeddedServer(CIO, port = port, host = "::", module = module).start(wait = false)
        try {
            val v4 = probe("127.0.0.1", port)
            val v6 = probe("::1", port)
            println("[bind ::]        v4/127.0.0.1=$v4  v6/::1=$v6")
            // 这是双栈改造的前提：IPv4 不能被 :: 绑定排除掉
            assert(v4) { "绑定 :: 后 127.0.0.1 必须仍然可达（${v4}）" }
            assert(v6) { "绑定 :: 后 ::1 应当可达（${v6}）" }
        } finally {
            server.stop(500, 500)
        }
    }

    @Test
    fun `绑定纯 IPv4 通配地址至少收得到 IPv4 客户端`() {
        // 注意：不同平台的 IPv4 通配符是否也收 IPv6 并不一致（Windows JVM 的 DualStackSupported
        // 会让 0.0.0.0 同时收 IPv6，Linux 通常不会），所以这里只断言 IPv4 必须可用。
        val port = freePort()
        val server = embeddedServer(CIO, port = port, host = "0.0.0.0", module = module).start(wait = false)
        try {
            val v4 = probe("127.0.0.1", port)
            val v6 = probe("::1", port)
            println("[bind 0.0.0.0]   v4/127.0.0.1=$v4  v6/::1=$v6")
            assert(v4) { "绑定 0.0.0.0 后 127.0.0.1 必须可达（${v4}）" }
        } finally {
            server.stop(500, 500)
        }
    }

    @Test
    fun `绑定失败是同步抛出的，可以在调用点就地回落`() {
        val port = freePort()
        // 先占住这个端口，制造必现的绑定冲突
        val blocker = ServerSocket(port)
        try {
            try {
                embeddedServer(CIO, port = port, host = "::", module = module).start(wait = false)
                fail("端口被占用时 start(wait = false) 应当同步抛出，而不是静默成功")
            } catch (t: Throwable) {
                // 实测抛的是 JobCancellationException（仍属 Exception），不是 BindException ——
                // 所以回落分支只按「是否抛了 Exception」处理，不要靠异常类型区分失败原因。
                println("[bind conflict] 抛的是 ${t::class.simpleName}: ${t.message?.take(120)}")
                assert(t is Exception) { "应为 Exception，便于用 catch(Exception) 兜住，实际=${t::class.simpleName}" }
            }
        } finally {
            blocker.close()
        }
    }

    @Test
    fun `端口全被占用时回落目标也可能失败，必须能一路抛出`() {
        // 双栈失败 → 回落 0.0.0.0；若回落也失败，startServer 要看到失败而不是以为起来了。
        val port = freePort()
        val blocker = ServerSocket(port)
        try {
            try {
                embeddedServer(CIO, port = port, host = "0.0.0.0", module = module).start(wait = false)
                fail("0.0.0.0 绑定冲突也应当抛出")
            } catch (t: Throwable) {
                println("[ipv4 fallback conflict] 抛的是 ${t::class.simpleName}")
            }
        } finally {
            blocker.close()
        }
    }
}
