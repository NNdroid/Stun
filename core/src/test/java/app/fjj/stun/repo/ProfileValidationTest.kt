package app.fjj.stun.repo

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The three xhttp predicates, `errorsOf` and `sanitize`, plus the JVM side of the
 * Kotlin <-> browser parity gate.
 *
 * The case table lives in `src/test/resources/xhttp-validation-cases.json` and is
 * consumed by BOTH this test and `scripts/check-xhttp-validation-parity.mjs`.
 * That shared oracle is what stops the hand-written JS mirror in assets/web/app.js
 * from drifting away from [ProfileValidation]: previously the two were separate
 * implementations with no test covering the JS one at all.
 *
 * Every assertion carries a counterfactual — a predicate that always returns true,
 * or a sanitizer that always returns its input, must not be able to pass.
 */
class ProfileValidationTest {

    private val table: JsonObject by lazy {
        val stream = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("xhttp-validation-cases.json")
        ) { "xhttp-validation-cases.json missing from test resources" }
        Gson().fromJson(stream.bufferedReader().use { it.readText() }, JsonObject::class.java)
    }

    private fun casesFor(key: String): List<Pair<String, Boolean>> =
        table.getAsJsonArray(key).map { row ->
            val pair = row.asJsonArray
            pair[0].asString to pair[1].asBoolean
        }

    private fun assertPredicate(key: String, predicate: (String) -> Boolean) {
        val rows = casesFor(key)
        assertTrue("$key: case table too small (${rows.size})", rows.size >= 5)
        assertTrue("$key: needs valid samples", rows.any { it.second })
        assertTrue("$key: needs invalid counterfactual samples", rows.any { !it.second })
        val wrong = rows.filterNot { predicate(it.first) == it.second }
        assertTrue("$key disagrees with shared table: $wrong", wrong.isEmpty())
    }

    @Test
    fun paddingPredicateMatchesSharedTable() =
        assertPredicate("isXhttpPadding", ProfileValidation::isXhttpPadding)

    @Test
    fun windowPredicateMatchesSharedTable() =
        assertPredicate("isXhttpWindowSizeMB", ProfileValidation::isXhttpWindowSizeMB)

    @Test
    fun maxConnsPredicateMatchesSharedTable() =
        assertPredicate("isXhttpMaxConns", ProfileValidation::isXhttpMaxConns)

    @Test
    fun errorsOfReportsAllThreeFieldsOnlyForXhttp() {
        val bad = Profile(id = "x").apply {
            tunnelType = Profile.TUNNEL_TYPE_XHTTP
            xhttpPadding = "bogus"
            xhttpWindowSizeMB = 99
            xhttpMaxConns = -3
        }
        val errors = ProfileValidation.errorsOf(bad)
        assertEquals(3, errors.size)
        assertTrue(errors.any { it.startsWith("xhttpPadding") })
        assertTrue(errors.any { it.startsWith("xhttpWindowSizeMB") })
        assertTrue(errors.any { it.startsWith("xhttpMaxConns") })

        // Counterfactual: VpnConfigBuilder only emits these keys for xhttp, so a
        // stale value on another tunnel type must NOT block an otherwise valid save.
        val other = bad.copy(tunnelType = Profile.TUNNEL_TYPE_H2)
        assertTrue(ProfileValidation.errorsOf(other).isEmpty())

        val good = bad.copy(xhttpPadding = "400-600", xhttpWindowSizeMB = 8, xhttpMaxConns = 2000)
        assertTrue(ProfileValidation.errorsOf(good).isEmpty())
    }

    @Test
    fun sanitizeFallsBackToSdkDefaultsWithoutMutatingInput() {
        val bad = Profile(id = "x").apply {
            tunnelType = Profile.TUNNEL_TYPE_XHTTP
            xhttpPadding = "bogus"
            xhttpWindowSizeMB = 99
            xhttpMaxConns = -3
        }
        val cleaned = ProfileValidation.sanitize(bad)
        assertEquals("", cleaned.xhttpPadding)
        assertEquals(0, cleaned.xhttpWindowSizeMB)
        assertEquals(0, cleaned.xhttpMaxConns)
        assertEquals("bogus", bad.xhttpPadding) // must not mutate the caller's instance
        assertTrue(ProfileValidation.errorsOf(cleaned).isEmpty())

        // Counterfactual: a legal profile must be returned untouched (same instance).
        val good = bad.copy(xhttpPadding = "400-600", xhttpWindowSizeMB = 8, xhttpMaxConns = 2000)
        assertSame(good, ProfileValidation.sanitize(good))

        // Non-xhttp profiles are not touched at all, even with illegal leftovers.
        val inert = bad.copy(tunnelType = Profile.TUNNEL_TYPE_H2)
        assertSame(inert, ProfileValidation.sanitize(inert))
    }

    /**
     * Runs the browser-side mirror against the SAME case table via node:vm.
     * Fails hard when the script or node is missing — a skipped parity gate is not
     * a gate. GitHub-hosted runners and local dev machines both ship node.
     */
    @Test
    fun jsValidatorsAgreeWithSharedTable() {
        val script = listOf(
            File("scripts/check-xhttp-validation-parity.mjs"),
            File("../scripts/check-xhttp-validation-parity.mjs"),
            File("core/../scripts/check-xhttp-validation-parity.mjs"),
        ).firstOrNull { it.isFile }
            ?: throw AssertionError("scripts/check-xhttp-validation-parity.mjs not found")

        val proc = ProcessBuilder("node", script.absolutePath)
            .redirectErrorStream(true)
            .start()
        val finished = proc.waitFor(60, TimeUnit.SECONDS)
        if (!finished) proc.destroyForcibly()
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        assertTrue("node parity did not finish in 60s", finished)
        assertTrue(
            "node parity failed:\n$output",
            proc.exitValue() == 0
        )
    }
}
