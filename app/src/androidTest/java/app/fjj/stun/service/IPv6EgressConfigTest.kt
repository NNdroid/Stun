package app.fjj.stun.service

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.fjj.stun.repo.Profile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IPv6EgressConfigTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun buildMySshConfig_usesAutomaticIPv6EgressDetection() {
        val profile = Profile(dnsOverride = true)
        val json = JSONObject(VpnConfigBuilder.buildMySshConfig(context, profile, 1080, 10553))

        assertEquals("auto", json.getString("ipv6_egress_mode"))
    }
}
