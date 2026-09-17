package app.fjj.stun.geo

import java.io.File

/**
 * 用双栈 mmdb（IPv4 一份 + IPv6 一份）实现 [GeoResolver]。
 *
 * ## 为什么要两份库
 * 地址族不通用，这是实测结论：IPv4 库查 IPv6 地址、IPv6 库查 IPv4 地址都会查空
 * （后者走 v4-mapped 捷径，但 city 库并没有 v4-mapped 数据）。所以必须按地址族选库，
 * 并且**只**给对得上的那份 —— 把 IPv6 地址喂给 IPv4 库不但查不到，还会越出树外。
 *
 * ## 记录 schema 兼容两种
 * - **sapics 扁平版**（当前默认）：`country_code` / `latitude` / `longitude` / `city` 全在顶层。
 * - **MaxMind 官方嵌套版**：`country.iso_code` / `location.latitude` / `location.longitude` /
 *   `city.names.en`。
 *
 * 两种都认是有意的：地理库地址是可配置的，用户换个 URL 就换了一套 schema，
 * 与其在解析处静默查空、不如在这里多认一层（成本只有十来行）。
 *
 * ## 坐标兜底
 * 记录里带 `country_code` 但没有经纬度时（换成 country-only 库就会这样），
 * 退到 [CountryCentroids] 的国家代表坐标，并把 [GeoPoint.approximate] 标为 true。
 * 精确的 `(0, 0)` 也按"缺坐标"处理 —— 大量地理库拿它当未知值，真画出来会落在几内亚湾。
 */
class MmdbGeoResolver internal constructor(
    private val ipv4Reader: MmdbReader?,
    private val ipv6Reader: MmdbReader?,
) : GeoResolver {

    @Volatile
    private var closed = false

    override val available: Boolean
        get() = !closed && (ipv4Reader != null || ipv6Reader != null)

    override fun resolve(ip: String): GeoPoint? {
        if (closed) return null
        return runCatching {
            val packed = MmdbReader.packIp(ip) ?: return@runCatching null
            // IPv4 地址优先用 IPv4 库；没有 IPv4 库时退回 IPv6 库 —— 官方 GeoLite2-City 是
            // **单文件双栈**（ip_version = 6，IPv4 以 v4-mapped 形式嵌在里面），这种情况只有
            // IPv6 库也能查 IPv4。反过来不成立：IPv4 库根本没有 IPv6 的空间。
            val reader = if (packed.size == 4) (ipv4Reader ?: ipv6Reader) else ipv6Reader
            // reader.lookup 内部已保证不抛；这里再兜一层是为了让"绝不抛"这个契约在本地成立
            reader?.lookup(ip)?.toGeoPoint()
        }.getOrNull()
    }

    override fun close() {
        closed = true
        runCatching { ipv4Reader?.close() }
        runCatching { ipv6Reader?.close() }
    }

    override fun toString(): String = buildString {
        append("MmdbGeoResolver(v4=")
        append(ipv4Reader?.toString() ?: "none")
        append(", v6=")
        append(ipv6Reader?.toString() ?: "none")
        append(')')
    }

    companion object {

        /**
         * 尽力打开：**任一**能打开就返回可用的 resolver，两个都打不开才退化成
         * [NullGeoResolver]。单栈用户（例如只有 IPv4 网络）不该因为缺 IPv6 库就完全没得用。
         *
         * 两个路径相同时**复用同一个 reader**（单文件双栈库就是这个用法），不再重复 mmap 一份；
         * 复用导致的重复 close 是安全的，[MmdbReader.close] 内部已 runCatching。
         */
        fun open(ipv4File: File?, ipv6File: File?): GeoResolver {
            val sameFile = ipv4File != null && ipv4File == ipv6File
            val v4 = ipv4File?.let { MmdbReader.open(it) }
            val v6 = if (sameFile) v4 else ipv6File?.let { MmdbReader.open(it) }
            return if (v4 == null && v6 == null) NullGeoResolver else MmdbGeoResolver(v4, v6)
        }
    }
}

/**
 * 把一条 mmdb 记录（已解成扁平 Map）转成 [GeoPoint]。
 *
 * 抽成 internal 顶层函数是为了能单测：真实库的每条记录都自带坐标，
 * "有国家码没坐标"这条兜底分支在真实数据里走不到，只能自己造 Map 喂进来。
 */
internal fun Map<String, Any?>.toGeoPoint(): GeoPoint? {
    val country = countryCodeOf()?.let(::normalizeCountryCode)
    val city = cityNameOf()

    val coordinates = coordinatesOf()
    if (coordinates != null) {
        return GeoPoint(coordinates[0], coordinates[1], country, city)
    }

    val fallback = CountryCentroids.of(country) ?: return null
    return GeoPoint(fallback[0], fallback[1], country, city, approximate = true)
}

private fun Map<String, Any?>.countryCodeOf(): String? {
    val flat = this["country_code"] as? String
    if (!flat.isNullOrBlank()) return flat
    val nested = this["country"] as? Map<*, *>
    return (nested?.get("iso_code") as? String)?.takeIf { it.isNotBlank() }
}

private fun Map<String, Any?>.cityNameOf(): String? {
    val flat = this["city"] as? String
    if (!flat.isNullOrBlank()) return flat
    val nested = (this["city"] as? Map<*, *>)?.get("names") as? Map<*, *>
    return (nested?.get("en") as? String)?.takeIf { it.isNotBlank() }
}

/** 返回 `[纬度, 经度]`；缺失或恰为 `(0, 0)` 视为没有坐标。 */
private fun Map<String, Any?>.coordinatesOf(): DoubleArray? {
    val latitude = (this["latitude"] as? Number)?.toDouble()
        ?: ((this["location"] as? Map<*, *>)?.get("latitude") as? Number)?.toDouble()
    val longitude = (this["longitude"] as? Number)?.toDouble()
        ?: ((this["location"] as? Map<*, *>)?.get("longitude") as? Number)?.toDouble()
    if (latitude == null || longitude == null) return null
    if (!latitude.isFinite() || !longitude.isFinite()) return null
    if (latitude == 0.0 && longitude == 0.0) return null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    return doubleArrayOf(latitude, longitude)
}

/** 归一成大写两位字母；形如 `12` / `A` 这种脏值直接丢掉。 */
private fun normalizeCountryCode(raw: String): String? {
    val code = raw.trim().uppercase()
    return if (code.length == 2 && code.all { it in 'A'..'Z' }) code else null
}
