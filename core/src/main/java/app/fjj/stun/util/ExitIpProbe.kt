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
    data class Result(
        /**
         * 主出口地址，**IPv4 优先**：有 v4 就是 v4，只有 v6 时才退化为 v6。
         *
         * 它同时也是「地理信息锚点」—— [location] 是按这个地址查出来的，v6 复用同一份位置。
         */
        val ip: String,
        val location: String,
        val latencyMs: Long,
        /**
         * 第二个地址族的地址（主地址是 v4 时这里是 v6，反之亦然）；该族不可用时为 null。
         *
         * 刻意做成**加性**字段：老版本 AAR / 只走单栈的 provider 解析出来的结果会留 null，
         * 此时 [displayTextDual] 退化成 [displayText]，界面与旧版完全一致。
         */
        val ipv6: String? = null
    ) {
        /** 单行形态：`203.0.113.8 · 🇸🇬 Singapore`。TV 端与桌面小组件都吃这一行。 */
        val displayText: String get() = if (location.isBlank()) ip else "$ip · $location"

        /**
         * 双行形态（连接详情面板）：v4 与 v6 各占一行，位置只出现一次。
         *
         * 只在两个地址族都拿到时才换行 —— 单栈网络下不该给用户一个"看起来像缺了东西"的多行块。
         */
        val displayTextDual: String
            get() = ipv6?.takeIf { it.isNotBlank() }?.let { "$displayText\n$it" } ?: displayText
    }

    suspend fun run(): Result? {
        val primary = runChain(PROVIDERS) ?: return null
        // 主链只保证"有一个可用地址"（多数网络下系统栈会协商出 v4）。要凑齐双栈就得**针对缺失的族**
        // 再问一次固定端点：api.ipify.org 只答 v4、api6.ipify.org 只答 v6，主链给的是哪个就问另一个。
        // 补探测失败只是少一个地址，**不能反过来把已经拿到的结果作废**。
        val counterpart = runChain(providerFor(!isIpv6(primary.ip)))
        return primary.copy(ipv6 = counterpart?.ip)
    }

    /** 顺次降级跑一条链，返回第一个可解析的结果；全灭返回 null。 */
    private suspend fun runChain(providers: List<Provider>): Result? {
        for (provider in providers) {
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

        /**
         * 补齐双栈用的**定族**端点：这两个域各自只有一个地址族，不会协商出别的结果。
         * 靠 `api64.ipify.org` 是补不出 v6 的 —— 它跟随系统栈，主链走完还是同一个答案。
         */
        private val IPV4_ONLY = Provider("https://api.ipify.org", { b, e -> fromPlainIp(b, e, false) })
        private val IPV6_ONLY = Provider("https://api6.ipify.org", { b, e -> fromPlainIp(b, e, true) })

        /** 主链拿到 wantIpv6 之外的族时，去问哪个端点。 */
        internal fun providerFor(wantIpv6: Boolean): List<Provider> =
            if (wantIpv6) listOf(IPV6_ONLY) else listOf(IPV4_ONLY)

        /**
         * 地址族判别。**不读 provider 自报的 `type`**：那只在 ipwho.is 上有，api.ip.sb 压根不是 JSON，
         * 而 ipify 家族返回的是裸串。冒号是 IPv6 字面量里唯一不会出现的分隔符，比解析可靠。
         */
        internal fun isIpv6(address: String): Boolean = address.contains(':')

        /**
         * 裸串 provider（ipify 家族）的地址族硬校验。
         *
         * 原来 [fromPlainIp] 只查"短且无空白"，那对**单个**地址够用：ipwho.is 先跑，JSON 已被
         * `fromGeoJson` 吃掉，裸串解析器拿到的几乎只可能是真 IP。可它现在还要解析**补探测**的响应，
         * 那里遇到的是网关错误页、限流 HTML、JSON —— 都会被"短且无空白"放行并当成第二行地址显示出去。
         * 所以这里要求：只含该族合法字符，且**恰好是对应那个族**（v4 端点回了个 v6 也算错）。
         */
        internal fun isAddressOf(body: String, ipv6: Boolean): Boolean {
            if (ipv6) {
                // 至少要有 `::` 压缩或满 8 组才可能是真地址；这里只做字符集与基本形状把关，
                // 完整 RFC 校验交给系统，不自己写正则解析器。
                if (body.length > 45 || body.any { it !in "0123456789abcdefABCDEF:." }) return false
                if (body.count { it == ':' } < 2) return false
                if (body.contains(":::")) return false
                return true
            }
            if (body.length > 15) return false
            val parts = body.split('.')
            if (parts.size != 4) return false
            return parts.all { part ->
                part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } &&
                    part.toInt() in 0..255
            }
        }

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

        /**
         * A bare IP body, for providers that return no geo data.
         *
         * @param wantIpv6 该端点**应当**只答的族。`null` 表示"来什么都收"（主链最后一个兜底，
         *   此时只有字符集把关）；补探测的定族端点必须传值，才能挡住"v4 端点回了段错误页 JSON"。
         */
        private fun fromPlainIp(body: String, elapsed: Long, wantIpv6: Boolean? = null): Result? {
            val trimmed = body.trim()
            if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) return null
            if (wantIpv6 == null) {
                // 主链兜底：保持原有的宽松判定（≤80 字、无空白）。这里放宽是安全的 —— 主链前面已经有
                // 两个几何 provider 兜底，能走到这说明它们都挂了，宽松反而多救回一个站点。
                return trimmed.takeIf { it.length <= 80 }?.let { Result(it, "", elapsed) }
            }
            if (!isAddressOf(trimmed, wantIpv6)) return null
            return Result(trimmed, "", elapsed)
        }

        internal fun countryFlag(countryCode: String): String {
            val code = countryCode.trim().uppercase(Locale.ROOT)
            if (code.length != 2 || code.any { it !in 'A'..'Z' }) return ""
            return code.map { Character.toChars(0x1F1E6 + (it - 'A')).concatToString() }.joinToString("")
        }
    }
}
