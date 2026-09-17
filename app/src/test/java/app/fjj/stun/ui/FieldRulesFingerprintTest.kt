package app.fjj.stun.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldRulesFingerprintTest {

    @Test
    fun acceptsOpenSshSha256FingerprintReturnedByMyssh() {
        assertTrue(FieldRules.isSshFingerprint("SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
        assertTrue(FieldRules.isSshFingerprint("SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="))
    }

    @Test
    fun acceptsLegacyHexSha256Fingerprint() {
        assertTrue(FieldRules.isSshFingerprint("00".repeat(32)))
        assertTrue(FieldRules.isSshFingerprint(List(32) { "00" }.joinToString(":")))
    }

    @Test
    fun rejectsMalformedOpenSshFingerprint() {
        assertFalse(FieldRules.isSshFingerprint("SHA256:short"))
        assertFalse(FieldRules.isSshFingerprint("SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA!"))
        assertFalse(FieldRules.isSshFingerprint("MD5:00:11:22:33"))
    }

    @Test
    fun certificateFingerprintRemainsHexOnly() {
        assertTrue(FieldRules.isFingerprint("00".repeat(32)))
        assertFalse(FieldRules.isFingerprint("SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
    }
}
