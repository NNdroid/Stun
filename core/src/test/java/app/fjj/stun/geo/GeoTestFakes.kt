package app.fjj.stun.geo

/**
 * 地理层的测试替身。放在独立文件里（同 `MmdbFixtures.kt` 的做法），
 * 让 `ActiveConnectionTest` 与 `GeoLocatorTest` 共用同一份，避免两处各写一个逐渐跑偏的假实现。
 */

/** 用一张查找表冒充 mmdb。同时记录被查过的 IP，用来断言"某条链路根本没走地理查询"。 */
internal class FakeGeoResolver(
    private val table: Map<String, GeoPoint>,
    override val available: Boolean = true,
) : GeoResolver {

    val queried = mutableListOf<String>()

    override fun resolve(ip: String): GeoPoint? {
        queried += ip
        return table[ip]
    }

    override fun close() = Unit
}

/** 用一张查找表冒充 DNS。记录被查过的域名，**这是"有没有发生域名解析"的唯一证据**。 */
internal class FakeHostLookup(
    private val table: Map<String, String> = emptyMap(),
) : HostAddressLookup {

    val queried = mutableListOf<String>()

    /** 每次批量解析收到的整批主机与预算，用来断言确实走了批量路径、且预算传下来了。 */
    val bulkQueries = mutableListOf<Pair<List<String>, Long>>()

    override fun lookup(host: String): String? {
        queried += host
        return table[host]
    }

    override fun lookupAll(hosts: Collection<String>, budgetMillis: Long): Map<String, String?> {
        bulkQueries += hosts.toList() to budgetMillis
        return hosts.associateWith { lookup(it) }
    }
}

internal fun geoPoint(latitude: Double, longitude: Double, countryCode: String? = null): GeoPoint =
    GeoPoint(latitude, longitude, countryCode)

internal fun geoPoint(latitude: Double, longitude: Double, countryCode: String?, city: String?): GeoPoint =
    GeoPoint(latitude, longitude, countryCode, city)
