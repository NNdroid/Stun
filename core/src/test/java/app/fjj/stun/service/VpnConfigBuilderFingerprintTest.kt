package app.fjj.stun.service

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnConfigBuilderFingerprintTest {

    @Test
    fun keepsCanonicalOpenSshFingerprint() {
        val value = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        assertEquals(value, VpnConfigBuilder.normalizeSshFingerprint(value))
    }

    @Test
    fun convertsLegacyHexDigestToOpenSshFingerprint() {
        assertEquals(
            "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            VpnConfigBuilder.normalizeSshFingerprint("00:".repeat(31) + "00")
        )
    }
}
