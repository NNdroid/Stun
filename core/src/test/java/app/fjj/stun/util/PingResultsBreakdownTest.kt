package app.fjj.stun.util

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `PingResults.breakdown` 的解析契约。
 *
 * 背景：Go 侧 [myssh.latency] 原来只返回一个总数（SSH 握手 + 隧道内 HTTP 往返），
 * 用户看到「Stun 显示 200ms、同节点系统 ping 只有 30ms」会以为是 bug。
 * 现在拆成 `handshakeMs` / `httpMs` 两段，且 **总数语义保持为两段之和**，
 * 所以这是加性改动 —— 老版本 Go 侧不返回新键时必须能安全降级，不能崩、不能显示 0。
 *
 * 必须用 Robolectric：纯 JVM 下 `org.json` 只是 android.jar 里的 stub，
 * `JSONArray` 构造与取值都返回 null / 抛 NPE（表现为 `NullPointerException` 而非断言失败），
 * 很容易误判成"解析逻辑写错了"。
 *
 * `sdk = [35]`：本仓 targetSdk 37 超出当前 Robolectric 支持上限（报
 * `Package targetSdkVersion=37 > maxSdkVersion=36`），与既有 Robolectric 测试一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PingResultsBreakdownTest {

    private fun json(vararg entries: String) = "[${entries.joinToString(",")}]"

    @Test
    fun splitsHandshakeFromHttpAndKeepsTotalAsTheirSum() {
        val b = PingResults.breakdown(
            json(
                """{"id":"n1","ok":true,"latencyMs":200,"handshakeMs":150,"httpMs":50}""",
            ),
            "n1",
        )!!
        assertEquals(200L, b.totalMs)
        assertEquals(150L, b.handshakeMs)
        assertEquals(50L, b.httpMs)
        assertTrue(b.hasBreakdown)
        // 握手占大头 ⇒ 该优化的是连接复用/握手次数，而不是换节点。
        assertEquals(0.75f, b.handshakeShare, 0.001f)
    }

    @Test
    fun picksTheRequestedNodeOutOfABatch() {
        // pingNodes 一次返回整批结果，取错 id 会把别的节点的延迟显示成自己的。
        val b = PingResults.breakdown(
            json(
                """{"id":"n1","ok":true,"latencyMs":100,"handshakeMs":80,"httpMs":20}""",
                """{"id":"n2","ok":true,"latencyMs":300,"handshakeMs":90,"httpMs":210}""",
            ),
            "n2",
        )!!
        assertEquals(300L, b.totalMs)
        assertEquals(90L, b.handshakeMs)
    }

    @Test
    fun degradesGracefullyWhenGoOmitsTheBreakdownFields() {
        // 老版本 Go 侧没有 handshakeMs / httpMs。optLong 缺省返回 -1，
        // 此时 hasBreakdown=false、share=-1，让 UI 隐藏分段而不是显示 "0ms"。
        val b = PingResults.breakdown(
            json("""{"id":"n1","ok":true,"latencyMs":200}"""),
            "n1",
        )!!
        assertEquals(200L, b.totalMs)
        assertFalse(b.hasBreakdown)
        assertEquals(-1f, b.handshakeShare, 0.001f)
    }

    @Test
    fun returnsNullForFailedNode() {
        // 失败节点没有延迟可言。返回 null 而非 total=0，否则 UI 会显示 "0ms"
        // —— 那看起来像"节点极快"，与事实相反。
        assertNull(
            PingResults.breakdown(
                json("""{"id":"n1","ok":false,"error":"timeout","errorType":"timeout"}"""),
                "n1",
            )
        )
    }

    @Test
    fun returnsNullForMissingNode() {
        assertNull(PingResults.breakdown(json("""{"id":"n1","ok":true,"latencyMs":10}"""), "n2"))
    }

    @Test
    fun returnsNullForMalformedJson() {
        // 上游返回脏数据时不能让探测循环崩掉 —— LatencyProber.start 的
        // try/catch 只能护住抛异常，parse 内部必须自己吞掉。
        assertNull(PingResults.breakdown("not json", "n1"))
        assertNull(PingResults.breakdown("", "n1"))
    }

    @Test
    fun treatsNonPositiveTotalAsNoData() {
        // latencyMs=0 或缺字段都不构成有效测量。
        assertNull(PingResults.breakdown(json("""{"id":"n1","ok":true,"latencyMs":0}"""), "n1"))
        assertNull(PingResults.breakdown(json("""{"id":"n1","ok":true}"""), "n1"))
    }
}
