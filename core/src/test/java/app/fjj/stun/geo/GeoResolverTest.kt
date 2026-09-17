package app.fjj.stun.geo

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MmdbGeoResolver] 与记录 → [GeoPoint] 的转换。
 *
 * 分两半：**真实库**验证地址族路由与坐标读取（需要 `.workbuddy/tmp` 里的双栈库，缺则跳过）；
 * **纯 Map** 验证 schema 兼容与坐标兜底 —— 真实库的每条记录都自带坐标，
 * "有国家码没坐标"和"官方嵌套 schema"这两条分支在真实数据里走不到，只能自己造。
 */
class GeoResolverTest {

    // ---------------------------------------------------------------- 真实库：路由与坐标

    @Test
    fun `双栈分别路由到对应的库`() {
        MmdbGeoResolver.open(MmdbFixtures.v4File(), MmdbFixtures.v6File()).use { resolver ->
            assertTrue(resolver.available)

            // IPv4 地址 → IPv4 库
            val hangzhouV4 = resolver.resolve("223.5.5.5")
            assertNotNull(hangzhouV4)
            assertEquals("CN", hangzhouV4!!.countryCode)
            assertEquals("Hangzhou", hangzhouV4.city)
            assertEquals(30.294300079345703, hangzhouV4.latitude, 1e-6)
            assertEquals(120.16629791259766, hangzhouV4.longitude, 1e-6)
            assertFalse("记录自带坐标，不该标成兜底", hangzhouV4.approximate)

            // IPv6 地址 → IPv6 库（同一个城市，验证确实走了另一份库）
            val hangzhouV6 = resolver.resolve("2400:3200::1")
            assertNotNull(hangzhouV6)
            assertEquals("CN", hangzhouV6!!.countryCode)
            assertEquals("Hangzhou", hangzhouV6.city)

            // 城市字段在库里是空串 → 必须归一成 null，而不是把空串透给界面
            val unitedStates = resolver.resolve("8.8.8.8")
            assertNotNull(unitedStates)
            assertEquals("US", unitedStates!!.countryCode)
            assertNull("空城市名应归一成 null", unitedStates.city)
            assertEquals(37.750999450683594, unitedStates.latitude, 1e-6)
            assertFalse(unitedStates.approximate)
        }
    }

    @Test
    fun `查不到 私网 地址族不匹配 都返回 null`() {
        MmdbGeoResolver.open(MmdbFixtures.v4File(), MmdbFixtures.v6File()).use { resolver ->
            // 这些结果都由 mmdb_vectors.json 里的官方向量确认过：就是 null
            listOf(
                "1.1.1.1", "10.0.0.1", "192.168.1.1", "0.0.0.0",
                "::1", "fe80::1", "2606:4700:4700::1111",
            ).forEach { ip ->
                assertNull("resolve($ip) 应为 null", resolver.resolve(ip))
            }
        }
    }

    @Test
    fun `只给 IPv4 库时 IPv4 仍可用 IPv6 返回 null`() {
        MmdbGeoResolver.open(MmdbFixtures.v4File(), null).use { resolver ->
            assertTrue("只缺一份库不该让整个功能不可用", resolver.available)
            assertEquals("CN", resolver.resolve("223.5.5.5")!!.countryCode)
            assertNull("IPv4 库装不下 IPv6 地址", resolver.resolve("2400:3200::1"))
        }
    }

    /**
     * 单文件双栈库（官方 GeoLite2-City 就是这个形态）会把同一个路径填到两个槽位。
     * 此时必须复用同一个 reader：IPv6 查得到，IPv4 也会回落到它。
     * 这里用 sapics 的 IPv6 库代跑 —— 它没有 v4-mapped 数据，所以 IPv4 查到 null 是**正确**结果，
     * 要断言的是"回落发生了且没崩"，而不是 IPv4 一定能查到。
     */
    @Test
    fun `同一个文件填两个槽位时复用同一 reader`() {
        val shared = MmdbFixtures.v6File()
        MmdbGeoResolver.open(shared, shared).use { resolver ->
            assertTrue(resolver.available)
            assertEquals("CN", resolver.resolve("2400:3200::1")!!.countryCode)
            assertNull(resolver.resolve("8.8.8.8"))
        }
    }

    @Test
    fun `close 之后不再解析`() {
        val resolver = MmdbGeoResolver.open(MmdbFixtures.v4File(), MmdbFixtures.v6File())
        assertTrue(resolver.available)
        assertEquals("CN", resolver.resolve("223.5.5.5")!!.countryCode)

        resolver.close()
        assertFalse(resolver.available)
        assertNull("关掉之后必须返回 null，而不是去碰已释放的 mmap", resolver.resolve("223.5.5.5"))
    }

    @Test
    fun `没有可用库时退化成空实现`() {
        val missing = File(File(System.getProperty("java.io.tmpdir") ?: "."), "stun-no-such-db.mmdb")
        listOf(
            MmdbGeoResolver.open(null, null),
            MmdbGeoResolver.open(missing, missing),
        ).forEach { resolver ->
            assertSame(NullGeoResolver, resolver)
            assertFalse(resolver.available)
            assertNull(resolver.resolve("8.8.8.8"))
        }
    }

    /**
     * 契约是"绝不抛"。这里刻意塞进一批边界与脏输入，只要求不抛、不断言结果 ——
     * 其中"看起来像域名"和"看起来像 IPv4 但越界"的几项同时守着 DNS 泄露那条线。
     */
    @Test
    fun `resolve 对脏输入不抛异常`() {
        MmdbGeoResolver.open(MmdbFixtures.v4File(), MmdbFixtures.v6File()).use { resolver ->
            listOf(
                "", " ", ".", "..", "...", "1.2.3.4.5", "-1.2.3.4", "8.8.8.8/24",
                "2001:db8::/32", "::ffff:1.2.3.4", "1.2.3.4%eth0", "例子.公司",
                "example.com", "999.1.1.1", "8.8.8.8\n", "a".repeat(300), "1:2:3:4:5:6:7:8:9",
            ).forEach { input ->
                resolver.resolve(input)
            }
        }
    }

    // ---------------------------------------------------------------- 纯 Map：schema 与兜底

    @Test
    fun `记录缺坐标时用国家代表坐标兜底`() {
        val point = mapOf<String, Any?>("country_code" to "US").toGeoPoint()
        assertNotNull(point)
        assertEquals("US", point!!.countryCode)
        assertEquals(37.751, point.latitude, 1e-9)
        assertEquals(-97.822, point.longitude, 1e-9)
        assertTrue("兜底得来的坐标必须标记为 approximate", point.approximate)
        assertNull(point.city)
    }

    @Test
    fun `国家码归一化后仍能兜底 认不出来就返回 null`() {
        assertEquals("US", mapOf<String, Any?>("country_code" to " us ").toGeoPoint()!!.countryCode)

        listOf("ZZ", "12", "USA", "", "U", "u1").forEach { code ->
            assertNull(
                "国家码 ${code.quote()} 不该兜出坐标",
                mapOf<String, Any?>("country_code" to code).toGeoPoint(),
            )
        }
        assertNull("既没坐标也没国家码，只能返回 null", mapOf<String, Any?>("latitude" to 1.0).toGeoPoint())
        assertNull("只有经度也拼不出点", mapOf<String, Any?>("longitude" to 1.0).toGeoPoint())
    }

    /** 大量地理库拿 `(0, 0)` 当"未知"；真画出来会落在大西洋几内亚湾。 */
    @Test
    fun `零坐标按缺失处理`() {
        val withCountry = mapOf<String, Any?>("country_code" to "CN", "latitude" to 0.0, "longitude" to 0.0)
            .toGeoPoint()
        assertNotNull(withCountry)
        assertTrue(withCountry!!.approximate)
        assertEquals(34.773, withCountry.latitude, 1e-9)

        assertNull(
            "没有国家可兜底时只能返回 null",
            mapOf<String, Any?>("latitude" to 0.0, "longitude" to 0.0).toGeoPoint(),
        )
    }

    @Test
    fun `兼容 MaxMind 官方嵌套 schema`() {
        val point = mapOf<String, Any?>(
            "country" to mapOf("iso_code" to "jp"),
            "location" to mapOf("latitude" to 35.6895, "longitude" to 139.6917),
            "city" to mapOf("names" to mapOf("en" to "Tokyo", "zh-CN" to "东京")),
        ).toGeoPoint()

        assertNotNull(point)
        assertEquals("JP", point!!.countryCode)
        assertEquals("Tokyo", point.city)
        assertEquals(35.6895, point.latitude, 1e-9)
        assertFalse("嵌套 schema 同样自带坐标，不该标成兜底", point.approximate)
    }

    @Test
    fun `越界与非有限坐标按缺失处理`() {
        val broken = listOf(
            91.0 to 0.5,
            -91.0 to 0.5,
            0.5 to 181.0,
            0.5 to -181.0,
            Double.NaN to 1.0,
            Double.POSITIVE_INFINITY to 1.0,
            1.0 to Double.NEGATIVE_INFINITY,
        )
        broken.forEach { (latitude, longitude) ->
            val withCountry = mapOf<String, Any?>(
                "country_code" to "US",
                "latitude" to latitude,
                "longitude" to longitude,
            ).toGeoPoint()
            assertNotNull("($latitude, $longitude) 应退到国家兜底", withCountry)
            assertTrue(withCountry!!.approximate)

            assertNull(
                "($latitude, $longitude) 且没有国家码时只能是 null",
                mapOf<String, Any?>("latitude" to latitude, "longitude" to longitude).toGeoPoint(),
            )
        }
    }

    private fun String.quote(): String = "\"$this\""
}
