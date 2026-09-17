package app.fjj.stun.util

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Probes the public exit address through the app's current routing. Call on Dispatchers.IO.
 *
 * Lives in `:core` so every platform module can share it. It used to be duplicated inline in
 * `tv/MainActivity` and the two copies drifted (the TV one kept a dead cleartext provider).
 */
class ExitIpProbe(
    private val fetch: (String) -> String? = ::readResponse,
    private val elapsedTime: () -> Long = SystemClock::elapsedRealtime
) {
    data class Result(val ip: String, val location: String, val latencyMs: Long) {
        val displayText: String get() = if (location.isBlank()) ip else "$ip · $location"
    }

    suspend fun run(): Result? {
        for (provider in PROVIDERS) {
            currentCoroutineContext().ensureActive()
            try {
                val start = elapsedTime()
                val response = fetch(provider.url) ?: continue
                currentCoroutineContext().ensureActive()
                val elapsed = (elapsedTime() - start).coerceAtLeast(0)
                provider.parse(response, elapsed)?.let { return it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A provider failure falls back without inventing an IP or location.
            }
        }
        return null
    }

    companion object {
        /**
         * Ordered fallback chain. Every entry is HTTPS on purpose: targetSdk 37 blocks cleartext
         * traffic, so an `http://` provider can never answer — it only burns its connect timeout
         * before the next one is tried. (ip-api.com used to sit here over plain HTTP.)
         */
        internal val PROVIDERS: List<Provider> = listOf(
            Provider("https://ipwho.is/", ::fromGeoJson),
            Provider("https://api.ip.sb/geoip", ::fromGeoJson),
            Provider("https://api64.ipify.org", ::fromPlainIp)
        )

        /** One fallback source: where to ask, and how to read its reply. */
        internal class Provider(val url: String, val parse: (String, Long) -> Result?)

        private fun readResponse(url: String): String? {
            val connection = URL(url).openConnection() as HttpURLConnection
            return try {
                connection.connectTimeout = 4000
                connection.readTimeout = 4000
                connection.setRequestProperty("User-Agent", "curl/7.88.1")
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.bufferedReader().use { it.readText().trim() }
                } else null
            } finally {
                connection.disconnect()
            }
        }

        /** `ip` / `country_code` / `city` JSON, as served by ipwho.is and api.ip.sb. */
        private fun fromGeoJson(body: String, elapsed: Long): Result? {
            val json = JSONObject(body)
            // Quota and lookup failures come back as HTTP 200 with success=false.
            if (json.has("success") && !json.optBoolean("success")) return null
            fun field(name: String) = if (json.isNull(name)) "" else json.optString(name).trim()
            val ip = field("ip").takeIf { it.isNotEmpty() } ?: return null
            val city = field("city")
            val country = field("country")
            val location = listOf(countryFlag(field("country_code")), city, country.takeUnless { it == city }.orEmpty())
                .filter { it.isNotEmpty() }.joinToString(" ")
            return Result(ip, location, elapsed)
        }

        /** A bare IP body, for providers that return no geo data. */
        private fun fromPlainIp(body: String, elapsed: Long): Result? =
            body.trim().takeIf { it.isNotEmpty() && it.length <= 80 && it.none(Char::isWhitespace) }
                ?.let { Result(it, "", elapsed) }

        internal fun countryFlag(countryCode: String): String {
            val code = countryCode.trim().uppercase(Locale.ROOT)
            if (code.length != 2 || code.any { it !in 'A'..'Z' }) return ""
            return code.map { Character.toChars(0x1F1E6 + (it - 'A')).concatToString() }.joinToString("")
        }
    }
}
