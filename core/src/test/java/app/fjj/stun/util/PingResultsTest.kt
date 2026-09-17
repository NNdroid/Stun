package app.fjj.stun.util

import android.app.Application
import app.fjj.stun.core.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Guards the shared ping-result parser in `:core`.
 *
 * app / tv / car / xr 原先各有一份拷贝，且 car/xr 是「一律网络错误」的退化版；
 * 这里既锁行为，也锁「car/xr 已升级到按 errorType 细分」这一合并意图。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PingResultsTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test
    fun okResultIsFormattedThroughTheSharedLatencyFormat() {
        val map = PingResults.parse(ctx, """[{"id":"a","ok":true,"latencyMs":42}]""")
        assertEquals(ctx.getString(R.string.latency_format, 42L), map["a"])
    }

    @Test
    fun errorTypeIsMappedToItsOwnLocalisedMessage() {
        val map = PingResults.parse(
            ctx,
            """[
                 {"id":"t","ok":false,"errorType":"timeout"},
                 {"id":"d","ok":false,"errorType":"dns"},
                 {"id":"r","ok":false,"errorType":"connrefused"},
                 {"id":"s","ok":false,"errorType":"tls"}
               ]"""
        )
        assertEquals(ctx.getString(R.string.latency_timeout), map["t"])
        assertEquals(ctx.getString(R.string.latency_dns_error), map["d"])
        assertEquals(ctx.getString(R.string.latency_conn_refused), map["r"])
        assertEquals(ctx.getString(R.string.latency_ssl_error), map["s"])
    }

    @Test
    fun httpErrorSurfacesTheStatusCodeVerbatim() {
        val map = PingResults.parse(ctx, """[{"id":"h","ok":false,"errorType":"http","error":"502"}]""")
        assertEquals("HTTP 502", map["h"])
    }

    @Test
    fun unknownOrMissingErrorTypeFallsBackToNetworkError() {
        val map = PingResults.parse(
            ctx,
            """[{"id":"u","ok":false,"errorType":"weird"},{"id":"n","ok":false}]"""
        )
        val fallback = ctx.getString(R.string.latency_network_error)
        assertEquals(fallback, map["u"])
        assertEquals(fallback, map["n"])
    }

    @Test
    fun entriesWithoutAnIdAreSkipped() {
        val map = PingResults.parse(ctx, """[{"id":"","ok":true,"latencyMs":1},{"id":"b","ok":true,"latencyMs":2}]""")
        assertEquals(1, map.size)
        assertTrue(map.containsKey("b"))
    }

    @Test
    fun malformedJsonYieldsAnEmptyMapInsteadOfThrowing() {
        assertTrue(PingResults.parse(ctx, "not json at all").isEmpty())
        assertTrue(PingResults.parse(ctx, "").isEmpty())
    }
}
