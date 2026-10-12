package app.fjj.stun.repo

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest

/**
 * 规则文件下载校验的契约。
 *
 * 每一组断言都对齐 myssh 的 geo_download_test.go / geo_repair_test.go，Kotlin 侧改坏
 * 任何一层校验都会在这里变红。三层校验分别是：
 *
 *  1. 传输完整性 —— 服务端声明的 Content-Length 与实际字节数一致；
 *  2. 内容完整性 —— 与发布源随文件一起发布的 sha256 sidecar 比对；
 *  3. 结构完整性 —— 顶层 protobuf wire 流真的走到文件末尾。
 *
 * 必须挂 Robolectric：[SettingsManager] 的日志走 [StunLogger]，它会初始化 [LogEntry.LogLevel]，
 * 而那个 enum 的构造器里直接调 `android.graphics.Color.parseColor`——纯 JVM 里那是 stub，
 * 抛 RuntimeException("not mocked")，把整组测试连同真正的校验结论一起拖垮。这里其实不需要
 * Context，挂上只为让 android.* 变成真实现。
 *
 * [StunLogger.isLogcatEnabled] 关掉：校验路径每次都会打日志，单测里只想要结论。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GeoRuleDownloadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val servers = mutableListOf<TinyHttpServer>()
    private var sourceCounter = 0

    @Before
    fun setUp() {
        StunLogger.isLogcatEnabled = false
    }

    @After
    fun tearDown() {
        servers.forEach { it.close() }
        servers.clear()
        StunLogger.isLogcatEnabled = true
    }

    // ─────────────────────────── 1. sha256sum 解析 ───────────────────────────

    @Test
    fun `解析规则与 Go 的 parseSHA256Sum 完全一致`() {
        val good = DIGEST

        val cases = listOf(
            "sha256sum 双空格带文件名" to "$good  geoip.dat\n" to good,
            "裸摘要" to "$good\n" to good,
            "没有尾换行" to good to good,
            "大写摘要要转小写" to "${DIGEST.uppercase()}  geoip.dat\n" to good,
            "前置空行" to "\n\n$good  geoip.dat\n" to good,
            "文件名带空格" to "$good  some rule file.dat\n" to good,
            "GNU --binary 星号" to "$good *geoip.dat\n" to good,
            "空正文" to "" to "",
            "HTML 错误页" to "<!DOCTYPE html><html>404 Not Found</html>" to "",
            "63 位太短" to "$DIGEST_SHORT  geoip.dat\n" to "",
            "66 位太长" to "${good}aa  geoip.dat\n" to "",
            "非十六进制" to "$NON_HEX  geoip.dat\n" to "",
        )

        for (case in cases) {
            val (pair, expected) = case
            val (name, text) = pair
            assertEquals("$name：应为 [$expected]", expected, SettingsManager.parseSha256Sum(text))
        }
    }

    // ─────────────────────────── 2. 三层校验各自的行为 ───────────────────────────

    @Test
    fun `摘要匹配的载荷通过全部校验并落盘`() {
        val payload = ruleFileFixture(byteArrayOf(0x0A, 0x02, 0x63, 0x6E), byteArrayOf(0x0A, 0x01, 0x75))
        val source = sourceFile(payload)
        writeSidecarFor(source, payload)

        val dest = File(tmp.root, "verified.dat")
        val written = SettingsManager.downloadRuleFile(source.toURI().toString(), dest.absolutePath)

        assertEquals(payload.size.toLong(), written)
        assertArrayEquals(payload, dest.readBytes())
        assertFalse("校验通过不应留下临时文件", File(dest.absolutePath + ".tmp").exists())
    }

    @Test
    fun `摘要不符时拒绝安装且旧文件原样保留`() {
        // 载荷本身是一份**合法**规则文件——只有校验和能发现它不是发布源的那一份。
        // 这正对应 Go 的 TestDownloadFile_KeepsPreviousCopyOnMismatch。
        val payload = ruleFileFixture(byteArrayOf(0x0A, 0x02, 0x63, 0x6E))
        val otherEdition = ruleFileFixture(byteArrayOf(0x0A, 0x01, 0x75))
        val source = sourceFile(payload)
        writeSidecarFor(source, otherEdition)

        val previous = byteArrayOf(9, 8, 7, 6)
        val dest = File(tmp.root, "mismatch.dat").apply { writeBytes(previous) }

        val thrown = runCatching {
            SettingsManager.downloadRuleFile(source.toURI().toString(), dest.absolutePath)
        }.exceptionOrNull()

        assertTrue("应当因校验和不匹配而失败，实际：$thrown", thrown is IOException)
        assertTrue(
            "错误信息要能看出是校验和问题：${thrown?.message}",
            (thrown as? IOException)?.message.orEmpty().contains("sha256 mismatch"),
        )
        assertArrayEquals("校验不过时旧文件必须原样保留", previous, dest.readBytes())
    }

    @Test
    fun `CDN 滚动发布时重取摘要后第二次通过`() {
        // sidecar 与载荷是两个独立对象，跨发布时刻的请求会合法地配上「新字节 + 旧摘要」。
        // Go 的 TestDownloadFile_RecoversFromCDNRollover 钉住的就是这一点：
        // 失败一次之后**重新取摘要**再试一次，第二次就该成功。
        val payload = ruleFileFixture(byteArrayOf(0x0A, 0x02, 0x63, 0x6E))
        val goodSum = sha256Of(payload)
        val staleSum = sha256Of(ruleFileFixture(byteArrayOf(0x0A, 0x01, 0x01)))

        var sidecarCalls = 0
        val server = startServer { out, path ->
            if (path.contains(".sha256sum")) {
                val sum = if (sidecarCalls++ == 0) staleSum else goodSum
                respond200(out, "$sum  geoip.dat\n".toByteArray())
            } else {
                respond200(out, payload)
            }
        }

        val dest = File(tmp.root, "rollover.dat")
        val written = SettingsManager.downloadRuleFile("${server.baseUrl}/geoip.dat", dest.absolutePath)

        assertEquals(payload.size.toLong(), written)
        assertArrayEquals(payload, dest.readBytes())
        assertEquals("摘要必须在重试前重取一次", 2, sidecarCalls)
    }

    // ─────────────────────────── 3. 降级路径（拿不到 sidecar） ───────────────────────────

    @Test
    fun `sidecar 取不到时降级但仍然安装`() {
        val payload = ruleFileFixture(byteArrayOf(0x0A, 0x02, 0x63, 0x6E), byteArrayOf(0x0A, 0x01, 0x75))
        val source = sourceFile(payload)
        // 刻意不写 sidecar：fetchExpectedSha256 返回空串，只剩长度与结构两层检查。

        val dest = File(tmp.root, "degraded.dat")
        val written = SettingsManager.downloadRuleFile(source.toURI().toString(), dest.absolutePath)

        assertEquals(payload.size.toLong(), written)
        assertArrayEquals(payload, dest.readBytes())
    }

    @Test
    fun `sidecar 返回 404 时同样降级而不是让更新失败`() {
        val payload = ruleFileFixture(byteArrayOf(0x0A, 0x02, 0x63, 0x6E))
        val server = startServer { out, path ->
            if (path.contains(".sha256sum")) respondStatus(out, "404 Not Found", ByteArray(0))
            else respond200(out, payload)
        }

        val dest = File(tmp.root, "notfound-sidecar.dat")
        val written = SettingsManager.downloadRuleFile("${server.baseUrl}/geoip.dat", dest.absolutePath)

        assertEquals(payload.size.toLong(), written)
        assertArrayEquals(payload, dest.readBytes())
    }

    @Test
    fun `没有 sidecar 时截断的规则文件仍会被结构检查拦住`() {
        val complete = ruleFileFixture(byteArrayOf(0x0A, 0x02, 0x63, 0x6E), byteArrayOf(0x0A, 0x01, 0x75))
        val truncated = complete.copyOf(complete.size - 2) // 掐掉最后一个 entry 的结尾
        val source = sourceFile(truncated)
        // 不写 sidecar：这一层只剩结构检查能发现它。

        val previous = byteArrayOf(5, 4, 3, 2, 1)
        val dest = File(tmp.root, "truncated.dat").apply { writeBytes(previous) }

        val thrown = runCatching {
            SettingsManager.downloadRuleFile(source.toURI().toString(), dest.absolutePath)
        }.exceptionOrNull()

        assertTrue("应当因结构损坏而失败，实际：$thrown", thrown is IOException)
        assertArrayEquals("结构检查不过时旧文件必须原样保留", previous, dest.readBytes())
    }

    @Test
    fun `完整但比旧文件小的规则文件必须接受`() {
        // 钉住「结构扫描不是大小检查」：Go 的 TestDownloadFile_AcceptsCompleteShortFile。
        // 规则库版本回退或上游瘦身都是合法的，按大小挡会把更新永久卡住。
        val small = ruleFileFixture(ByteArray(4) { it.toByte() })
        val large = ruleFileFixture(ByteArray(1024) { it.toByte() })

        val source = sourceFile(small)
        writeSidecarFor(source, small)

        val dest = File(tmp.root, "smaller.dat").apply { writeBytes(large) }
        val written = SettingsManager.downloadRuleFile(source.toURI().toString(), dest.absolutePath)

        assertEquals(small.size.toLong(), written)
        assertArrayEquals(small, dest.readBytes())
    }

    // ─────────────────────────── 4. 传输层失败 ───────────────────────────

    @Test
    fun `声明长度与实际字节不符时立即拒绝且不重试`() {
        // jsDelivr 这类 CDN 会正常返回 200 然后中途停发，流照常收尾时看不出来。
        val payload = ruleFileFixture(byteArrayOf(0x0A, 0x02, 0x63, 0x6E), byteArrayOf(0x0A, 0x01, 0x75))
        val shortBody = payload.copyOf(payload.size - 3)
        var payloadCalls = 0

        val server = startServer { out, path ->
            if (path.contains(".sha256sum")) {
                respond200(out, "${sha256Of(payload)}  geoip.dat\n".toByteArray())
            } else {
                payloadCalls++
                out.write(
                    "HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
                        .toByteArray(),
                )
                out.write(shortBody)
                out.flush()
            }
        }

        val previous = byteArrayOf(1, 2, 3)
        val dest = File(tmp.root, "shortread.dat").apply { writeBytes(previous) }

        val thrown = runCatching {
            SettingsManager.downloadRuleFile("${server.baseUrl}/geoip.dat", dest.absolutePath)
        }.exceptionOrNull()

        assertTrue("应当因短读而失败，实际：$thrown", thrown is IOException)
        assertTrue(
            "错误信息要能看出收到了多少：${thrown?.message}",
            (thrown as? IOException)?.message.orEmpty().contains("bytes"),
        )
        assertEquals("短读不值得重试", 1, payloadCalls)
        assertArrayEquals(previous, dest.readBytes())
    }

    @Test
    fun `载荷返回 5xx 时立即拒绝且旧文件保留`() {
        var payloadCalls = 0
        val server = startServer { out, path ->
            if (path.contains(".sha256sum")) respond200(out, "0000000000000000  geoip.dat\n".toByteArray())
            else {
                payloadCalls++
                respondStatus(out, "500 Internal Server Error", ByteArray(0))
            }
        }

        val previous = byteArrayOf(7, 6, 5)
        val dest = File(tmp.root, "servererror.dat").apply { writeBytes(previous) }

        val thrown = runCatching {
            SettingsManager.downloadRuleFile("${server.baseUrl}/geoip.dat", dest.absolutePath)
        }.exceptionOrNull()

        assertTrue("应当因服务端错误而失败，实际：$thrown", thrown is IOException)
        assertEquals("服务端错误不值得重试", 1, payloadCalls)
        assertArrayEquals(previous, dest.readBytes())
    }

    // ─────────────────────────── 5. 结构扫描本身 ───────────────────────────

    @Test
    fun `结构扫描只在能证明损坏时判否，其余一律放行`() {
        val valid = ruleFileFixture(byteArrayOf(0x0A, 0x02, 0x63, 0x6E), byteArrayOf(0x0A, 0x01, 0x75))
        assertFalse("完整的规则文件必须通过", SettingsManager.isBrokenRuleFile(valid))

        val truncated = valid.copyOf(valid.size - 1)
        assertTrue("掐掉末尾必须判损坏", SettingsManager.isBrokenRuleFile(truncated))

        assertTrue("全 0xFF 垃圾必须判损坏", SettingsManager.isBrokenRuleFile(ByteArray(256) { 0xFF.toByte() }))

        // field 2 长度为 0：能解析到末尾，但一条规则都没有
        assertTrue("解析得完但一条规则都没有的空壳必须判损坏", SettingsManager.isBrokenRuleFile(byteArrayOf(0x12, 0x00)))

        // entry 内部畸形但顶层完好：Go 的 extractGeoFileTags 对 entry 内部是「放弃该条、
        // 继续扫」，同样不设 truncated。这里必须跟着放行——误判等于让规则更新从此失败。
        val weirdEntry = ruleFileFixture(
            byteArrayOf(0x12, 0x99.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x01),
        )
        assertFalse("entry 内部畸形不应判为顶层截断", SettingsManager.isBrokenRuleFile(weirdEntry))
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    private fun sourceFile(payload: ByteArray): File =
        tmp.newFile("source-${++sourceCounter}.dat").apply { writeBytes(payload) }

    private fun startServer(respond: (OutputStream, String) -> Unit): TinyHttpServer {
        val server = TinyHttpServer(respond)
        servers += server
        return server
    }

    private fun respond200(out: OutputStream, body: ByteArray) = respondStatus(out, "200 OK", body)

    private fun respondStatus(out: OutputStream, status: String, body: ByteArray) {
        out.write("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    companion object {
        private val DIGEST = "0123456789abcdef".repeat(4)
        private val DIGEST_SHORT = "0123456789abcdef".repeat(3) + "0123456789abcde"
        private val NON_HEX = "z".repeat(64)
    }
}

/**
 * 起一个只监听 loopback 临时端口的最简 HTTP 服务器，用来覆盖 `file://` 做不到的场景：
 * 声明了 Content-Length 却只发部分字节、返回非 2xx、以及每次请求返回不同内容。
 *
 * 只读一行请求行就把响应交给 [respond]（带上请求行里的路径），然后立刻关掉这个连接——
 * 不处理 keep-alive，测试里也不需要。线程是守护线程，测试挂掉也不会挂住 JVM。
 */
private class TinyHttpServer(private val respond: (OutputStream, String) -> Unit) {
    private val server = ServerSocket(0)
    private val thread: Thread
    val baseUrl: String get() = "http://127.0.0.1:${server.localPort}"

    init {
        server.soTimeout = 500
        thread = Thread {
            while (true) {
                val client = try {
                    server.accept()
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    break
                }
                try {
                    respond(client.getOutputStream(), readRequestLine(client))
                } catch (_: Exception) {
                } finally {
                    runCatching { client.close() }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun close() {
        runCatching { server.close() }
        runCatching { thread.join(2_000) }
    }

    private fun readRequestLine(client: Socket): String {
        val input = client.getInputStream()
        val buffer = ByteArray(512)
        var n = 0
        while (n < buffer.size - 1) {
            val b = input.read()
            if (b < 0) break
            buffer[n++] = b.toByte()
            if (n >= 2 && (buffer[n - 2].toInt() and 0xFF) == 0x0D && (buffer[n - 1].toInt() and 0xFF) == 0x0A) {
                break
            }
        }
        return if (n >= 2) String(buffer, 0, n - 2, Charsets.ISO_8859_1).trim() else ""
    }
}

// ─────────────────────────── 供本测试集与 GeoDataSyncTest 共用的 fixture ───────────────────────────

/** 造一份最小但结构正确的规则文件：一串顶层 `field 1, length-delimited` entry。 */
internal fun ruleFileFixture(vararg entries: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    for (entry in entries) {
        out.write(0x0A)
        writeVarint(out, entry.size.toLong())
        out.write(entry)
    }
    return out.toByteArray()
}

private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
    var v = value
    while (true) {
        val low = (v and 0x7L).toInt()
        v = v ushr 7
        if (v == 0L) {
            out.write(low)
            return
        }
        out.write(low or 0x80)
    }
}

/** 小写十六进制 SHA-256，与 sha256sum 文件里的格式一致。 */
internal fun sha256Of(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

/** 按 GNU coreutils 格式（`<摘要>  <文件名>`）写一份 sidecar。 */
internal fun writeSidecarFor(source: File, payload: ByteArray) {
    File(source.parentFile, source.name + ".sha256sum").writeText("${sha256Of(payload)}  ${source.name}\n")
}
