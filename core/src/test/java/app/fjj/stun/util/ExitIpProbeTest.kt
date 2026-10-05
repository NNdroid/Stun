package app.fjj.stun.util

import android.app.Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Guards the shared fallback chain that lives in `:core` and is used by every platform module. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExitIpProbeTest {
    @Test fun includesIpCountryFlagAndCity() = runBlocking {
        val result = ExitIpProbe(fetch = {
            """{"ip":"203.0.113.8","success":true,"country_code":"sg","country":"Singapore","city":"Singapore"}"""
        }).run()!!
        assertEquals("203.0.113.8 · 🇸🇬 Singapore", result.displayText)
    }
    @Test fun fallsBackAndMeasuresOnlySuccessfulRequest() = runBlocking {
        var calls = 0
        var clock = 0L
        val result = ExitIpProbe(fetch = { url ->
            when {
                // 补探测会再打一次定族端点（主链拿到的是 v6），这里让它失败 —— 本例只关心主链降级与计时。
                // 必须按 host 精确匹配：`api64.ipify.org` 是主链自己的兜底，contains("ipify") 会把它一起打掉。
                url.startsWith("https://api.ipify.org") -> null
                ++calls == 1 -> throw java.io.IOException("fixture unavailable")
                else -> """{"ip":"2001:db8::8","country_code":"DE","country":"Germany","city":"Berlin"}"""
            }
        }, elapsedTime = { clock += 10; clock }).run()!!
        assertEquals(2, calls)
        assertEquals(10L, result.latencyMs)
        assertEquals("2001:db8::8 · 🇩🇪 Berlin Germany", result.displayText)
    }
    @Test fun plainIpFallbackDoesNotInventLocation() = runBlocking {
        var calls = 0
        val result = ExitIpProbe(fetch = { url ->
            when {
                // 精确匹配定族端点，别用 contains("ipify")：api64.ipify.org 是主链的兜底，会被误伤。
                url.startsWith("https://api.ipify.org") || url.startsWith("https://api6.ipify.org") -> null
                ++calls < 3 -> null
                else -> "203.0.113.9\n"
            }
        }).run()!!
        assertEquals("203.0.113.9", result.displayText)
        assertEquals("", result.location)
    }
    @Test fun missingGeoFieldsDoNotDisplayNullOrFakeFlags() = runBlocking {
        val result = ExitIpProbe(fetch = {
            """{"ip":"203.0.113.8","success":true,"country_code":"12","country":null,"city":null}"""
        }).run()!!
        assertEquals("203.0.113.8", result.displayText)
        assertEquals("", ExitIpProbe.countryFlag("中国"))
    }
    @Test fun failedProvidersReturnNoResult() = runBlocking {
        assertNull(ExitIpProbe(fetch = { null }).run())
    }
    @Test fun httpOkWithFailureFlagIsNotAcceptedAsAResult() = runBlocking {
        // ipwho.is answers quota and lookup failures with HTTP 200 and success=false.
        assertNull(ExitIpProbe(fetch = { """{"success":false,"message":"reserved range"}""" }).run())
    }
    @Test fun cancellationDoesNotLaunchFallbackRequests() {
        var calls = 0
        assertThrows(CancellationException::class.java) {
            runBlocking { ExitIpProbe(fetch = { calls++; throw CancellationException("fixture cancelled") }).run() }
        }
        assertEquals(1, calls)
    }

    // ─────────────────────────────── 双栈补探测

    @Test fun v4PrimaryIsCompletedWithAnIpv6Counterpart() = runBlocking {
        val asked = mutableListOf<String>()
        val result = ExitIpProbe(fetch = { url ->
            asked += url
            when {
                // 主链首个端点就答 v4（带地理）
                url.contains("ipwho.is") ->
                    """{"ip":"203.0.113.8","success":true,"country_code":"sg","country":"Singapore","city":"Singapore"}"""
                url.contains("api6.ipify.org") -> "2001:db8::8"
                else -> null
            }
        }).run()!!
        assertEquals("203.0.113.8 · 🇸🇬 Singapore", result.displayText)
        assertEquals("2001:db8::8", result.ipv6)
        // 主地址是 v4 ⇒ 补探测必须去问 v6 端点，不能又问一遍跟随系统栈的 api64。
        assertTrue("must ask the v6-only endpoint, asked=$asked", asked.any { it.contains("api6.ipify.org") })
    }

    @Test fun v6PrimaryIsCompletedWithAnIpv4Counterpart() = runBlocking {
        val asked = mutableListOf<String>()
        val result = ExitIpProbe(fetch = { url ->
            asked += url
            when {
                url.contains("ipwho.is") ->
                    """{"ip":"2001:db8::8","success":true,"country_code":"DE","country":"Germany","city":"Berlin"}"""
                url.contains("api.ipify.org") -> "198.51.100.7"
                else -> null
            }
        }).run()!!
        assertEquals("2001:db8::8", result.ip)
        assertEquals("198.51.100.7", result.ipv6)
        assertTrue("must ask the v4-only endpoint, asked=$asked", asked.any { it.contains("api.ipify.org") })
    }

    @Test fun singleStackNetworkStillReturnsThePrimaryResult() = runBlocking {
        // 补探测全灭（纯 v6 网络 / 端点不可达）：**不能**因此丢掉已拿到的主地址。
        val result = ExitIpProbe(fetch = { url ->
            if (url.contains("api6.ipify.org")) throw java.io.IOException("no v6 route") else
                """{"ip":"203.0.113.8","success":true,"country_code":"sg","country":"Singapore","city":"Singapore"}"""
        }).run()!!
        assertEquals("203.0.113.8", result.ip)
        assertNull("no counterpart means null, not a blank string", result.ipv6)
    }

    @Test fun dualStackDisplaysTwoLinesButSingleStackStaysOnOne() {
        val dual = ExitIpProbe.Result("203.0.113.8", "🇸🇬 Singapore", 12L, "2001:db8::8")
        assertEquals("203.0.113.8 · 🇸🇬 Singapore\n2001:db8::8", dual.displayTextDual)
        // 单行视图（TV / 小组件）绝不能被双行污染。
        assertEquals("203.0.113.8 · 🇸🇬 Singapore", dual.displayText)
        val single = ExitIpProbe.Result("203.0.113.8", "🇸🇬 Singapore", 12L, null)
        assertEquals(single.displayText, single.displayTextDual)
    }

    @Test fun counterpartRejectsNonAddressesAndWrongFamily() {
        // 网关错误页 / 限流 HTML / JSON —— 旧实现只查"短且无空白"，这些全都会被当成 IP 显示出去。
        val junk = listOf(
            """{"error":"rate limited"}""",
            "<html><body>502 Bad Gateway</body></html>",
            "Service Unavailable",
            "2001:db8::8 extra",            // 带空白
            "999.1.1.1",                    // v4 段越界
            "203.0.113.8.9",                // 5 段
            "not-an-ip",
        )
        junk.forEach { body ->
            assertFalse("must reject: $body", ExitIpProbe.isAddressOf(body, ipv6 = true))
            assertFalse("must reject: $body", ExitIpProbe.isAddressOf(body, ipv6 = false))
        }
        assertTrue(ExitIpProbe.isAddressOf("2001:db8::8", ipv6 = true))
        assertTrue(ExitIpProbe.isAddressOf("::1", ipv6 = true))
        assertTrue(ExitIpProbe.isAddressOf("203.0.113.8", ipv6 = false))
        assertTrue(ExitIpProbe.isAddressOf("0.0.0.0", ipv6 = false))
        // 族必须匹配：v4 端点回了个 v6（或反过来）说明端点行为变了，不能默默收下。
        assertFalse(ExitIpProbe.isAddressOf("2001:db8::8", ipv6 = false))
        assertFalse(ExitIpProbe.isAddressOf("203.0.113.8", ipv6 = true))
    }

    @Test fun counterpartProbeDoesNotInventLocation() = runBlocking {
        // 补探测走的是裸串端点，没有地理信息；位置必须沿用主地址那一份，而不是留空或重复编造。
        val result = ExitIpProbe(fetch = { url ->
            if (url.contains("ipwho.is"))
                """{"ip":"203.0.113.8","success":true,"country_code":"sg","country":"Singapore","city":"Singapore"}"""
            else "2001:db8::8"
        }).run()!!
        assertEquals("🇸🇬 Singapore", result.location)
        // 反事实：位置只出现一次，没有被拼成两遍。
        assertEquals("203.0.113.8 · 🇸🇬 Singapore\n2001:db8::8", result.displayTextDual)
    }

    @Test fun everyProviderUsesHttpsIncludingCounterparts() {
        assertTrue("provider list must not be empty", ExitIpProbe.PROVIDERS.isNotEmpty())
        ExitIpProbe.PROVIDERS.forEach { provider ->
            assertTrue(
                "targetSdk 37 blocks cleartext, so ${provider.url} could never answer",
                provider.url.startsWith("https://")
            )
        }
        listOf(true, false).forEach { wantIpv6 ->
            ExitIpProbe.providerFor(wantIpv6).forEach { provider ->
                assertTrue("counterpart ${provider.url} must be HTTPS", provider.url.startsWith("https://"))
            }
        }
        // 反事实：两个定族端点必须是**不同**的域，否则补探测只是把同一个答案又问了一遍。
        val v4 = ExitIpProbe.providerFor(false).single().url
        val v6 = ExitIpProbe.providerFor(true).single().url
        assertTrue("v4=$v4 v6=$v6", v4 != v6)
    }
}
