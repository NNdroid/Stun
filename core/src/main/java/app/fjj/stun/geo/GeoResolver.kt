package app.fjj.stun.geo

/**
 * 一个可以画到地球上的点。
 *
 * @property latitude 纬度，-90..90。
 * @property longitude 经度，-180..180。
 * @property countryCode ISO 3166-1 alpha-2，大写。库里没有则为 null。
 * @property city 城市名。库里常有空串（表示"只有国家级精度"），此处已归一成 null。
 * @property approximate 坐标为**国家代表坐标兜底**而来，不是记录里自带的精确坐标。
 *   展示层据此决定要不要画得更"虚"一点（半径更大、更透明）。
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val countryCode: String? = null,
    val city: String? = null,
    val approximate: Boolean = false,
)

/**
 * IP 字面量 → 地理位置。
 *
 * 两条硬约束，实现方都必须守住：
 *
 * 1. **绝不触发 DNS**。入参来自订阅里的服务器地址，一次域名解析就会把
 *    "用户正在连哪台机器"泄露给 DNS 服务器。所以只接受 IP 字面量。
 * 2. **绝不抛异常**。地球是装饰件，任何一条脏数据都只应该让那个点消失。
 *
 * 实现必须线程安全：[MmdbReader.lookup] 会被并发调用（例如在后台线程解析整份订阅）。
 */
interface GeoResolver : AutoCloseable {

    /** 是否真的有能力解析。false 时 [resolve] 恒为 null，展示层据此显示"下载地理库"引导。 */
    val available: Boolean

    /** 解析一个 IP 字面量；查不到（或地址族没有对应的库）返回 null。 */
    fun resolve(ip: String): GeoPoint?

    override fun close()
}

/**
 * 没有地理库时的空实现。
 *
 * 用 object + 恒 null 而不是让调用方到处判空：这样"没下载过地理库"这件事
 * 只体现在 [available] 上，展示层不必区分"没有库"和"这条查不到"。
 */
object NullGeoResolver : GeoResolver {

    override val available: Boolean = false

    override fun resolve(ip: String): GeoPoint? = null

    override fun close() = Unit
}
