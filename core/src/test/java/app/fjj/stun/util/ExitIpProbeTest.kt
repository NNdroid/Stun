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
        val result = ExitIpProbe(fetch = {
            if (++calls == 1) throw java.io.IOException("fixture unavailable")
            """{"ip":"2001:db8::8","country_code":"DE","country":"Germany","city":"Berlin"}"""
        }, elapsedTime = { clock += 10; clock }).run()!!
        assertEquals(2, calls)
        assertEquals(10L, result.latencyMs)
        assertEquals("2001:db8::8 · 🇩🇪 Berlin Germany", result.displayText)
    }
    @Test fun plainIpFallbackDoesNotInventLocation() = runBlocking {
        var calls = 0
        val result = ExitIpProbe(fetch = { if (++calls < 3) null else "203.0.113.9\n" }).run()!!
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
    @Test fun everyProviderUsesHttps() {
        assertTrue("provider list must not be empty", ExitIpProbe.PROVIDERS.isNotEmpty())
        ExitIpProbe.PROVIDERS.forEach { provider ->
            assertTrue(
                "targetSdk 37 blocks cleartext, so ${provider.url} could never answer",
                provider.url.startsWith("https://")
            )
        }
    }
    @Test fun cancellationDoesNotLaunchFallbackRequests() {
        var calls = 0
        assertThrows(CancellationException::class.java) {
            runBlocking { ExitIpProbe(fetch = { calls++; throw CancellationException("fixture cancelled") }).run() }
        }
        assertEquals(1, calls)
    }
}
