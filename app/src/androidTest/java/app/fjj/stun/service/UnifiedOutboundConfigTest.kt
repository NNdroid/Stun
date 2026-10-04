package app.fjj.stun.service

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.fjj.stun.repo.Profile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UnifiedOutboundConfigTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun mysshUsesTargetDrivenAutoOutboundPolicy() {
        val profile = Profile(dnsOverride = true)
        val json = JSONObject(VpnConfigBuilder.buildMySshConfig(context, profile, 10808, 10553))
        assertEquals("auto", json.getString("ipv6_egress_mode"))
    }

    @Test
    fun hevMapDnsStaysDisabledUntilItSupportsFullDualStackDns() {
        val yaml = VpnConfigBuilder.buildHevSocks5TunnelConfig(10808)
        assertFalse(
            "HEV mapdns currently does not synthesize AAAA; enabling it would break IPv6-first/IPv6-only DNS",
            yaml.contains("mapdns:")
        )
    }
}
