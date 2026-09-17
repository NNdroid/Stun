package app.fjj.stun.geo

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GeoLocator] 的分工与边界。
 *
 * 这一层最要紧的不是"能不能查到"（那是 mmdb 的事），而是**在什么情况下绝不去查 DNS**：
 * 项目的铁律是"订阅地址不做域名解析"，所以每条链路都要能证明它没碰 [FakeHostLookup]。
 */
class GeoLocatorTest {

    // ------------------------------------------------------------------ IP 字面量：纯离线

    @Test
    fun `IP字面量只走离线库_一次DNS都不发`() {
        val geo = FakeGeoResolver(mapOf("185.248.33.40" to geoPoint(37.75, -97.82, "US")))
        // 故意给这个 IP 也配一条 DNS 记录：要是实现走了 DNS 分支，断言立刻抓到。
        val dns = FakeHostLookup(mapOf("185.248.33.40" to "9.9.9.9"))

        val point = GeoLocator(geo, dns).locate("185.248.33.40:22")

        assertNotNull(point)
        assertEquals(37.75, point!!.latitude, 0.0)
        assertEquals("US", point.countryCode)
        assertTrue("IP 字面量绝不能触发域名解析", dns.queried.isEmpty())
        assertEquals(listOf("185.248.33.40"), geo.queried)
    }

    @Test
    fun `IPv6字面量会先剥掉方括号再查库`() {
        val geo = FakeGeoResolver(mapOf("2400:3200::1" to geoPoint(30.29, 120.16, "CN")))
        val dns = FakeHostLookup()

        val point = GeoLocator(geo, dns).locate("[2400:3200::1]:443")

        assertNotNull(point)
        assertEquals("CN", point!!.countryCode)
        assertTrue(dns.queried.isEmpty())
        // 库里存的是不带括号的字面量，剥错了这里就查不到。
        assertEquals(listOf("2400:3200::1"), geo.queried)
    }

    // ------------------------------------------------------------------ 域名：DNS 后查库

    @Test
    fun `域名先查DNS拿到IP再查离线库`() {
        val geo = FakeGeoResolver(mapOf("5.6.7.8" to geoPoint(22.28, 114.16, "HK")))
        val dns = FakeHostLookup(mapOf("hk1.example.com" to "5.6.7.8"))

        val point = GeoLocator(geo, dns).locate("hk1.example.com:443")

        assertNotNull(point)
        assertEquals("HK", point!!.countryCode)
        assertEquals(listOf("hk1.example.com"), dns.queried)
        assertEquals(listOf("5.6.7.8"), geo.queried)
    }

    @Test
    fun `DNS 解析不出来时只返回null`() {
        val geo = FakeGeoResolver(mapOf("5.6.7.8" to geoPoint(22.28, 114.16)))
        val dns = FakeHostLookup() // 什么都不知道

        assertNull(GeoLocator(geo, dns).locate("nowhere.example.com:443"))
        assertEquals(listOf("nowhere.example.com"), dns.queried)
        // 域名没解出来，就不该再拿域名本身去查库。
        assertTrue(geo.queried.isEmpty())
    }

    @Test
    fun `DNS解析成功但库里查不到时返回null`() {
        val geo = FakeGeoResolver(mapOf("5.6.7.8" to geoPoint(22.28, 114.16)))
        val dns = FakeHostLookup(mapOf("odd.example.com" to "203.0.113.7")) // 库里没有这个 IP

        assertNull(GeoLocator(geo, dns).locate("odd.example.com"))
    }

    // ------------------------------------------------------------------ 脏数据：绝不落到 DNS

    @Test
    fun `形似数字地址的脏串不触发DNS`() {
        // `999.1.1.1` 能骗过简单的正则，而 InetAddress 会把它当**主机名**拿去查 DNS ——
        // 正是要拦掉的那条路。`1.2.3` / `1.2.3.4.5` 同理。
        val geo = FakeGeoResolver(emptyMap())
        val dns = FakeHostLookup()

        listOf("999.1.1.1", "1.2.3", "1.2.3.4.5", "256.256.256.256").forEach { dirty ->
            assertNull("`$dirty` 不该解析出任何东西", GeoLocator(geo, dns).locate(dirty))
        }

        assertTrue("脏数字串绝不能触发域名解析", dns.queried.isEmpty())
        // 连离线库都不该被它们查到（packIp 自己就该判死）。
        assertTrue(geo.queried.isEmpty())
    }

    @Test
    fun `空与纯端口入参返回null`() {
        val geo = FakeGeoResolver(emptyMap())
        val dns = FakeHostLookup()

        assertNull(GeoLocator(geo, dns).locate(null))
        assertNull(GeoLocator(geo, dns).locate(""))
        assertNull(GeoLocator(geo, dns).locate("   "))
        assertNull(GeoLocator(geo, dns).locate(":443"))

        assertTrue(dns.queried.isEmpty())
    }

    // ------------------------------------------------------------------ 库不可用

    @Test
    fun `地理库不可用时连域名都不去解析`() {
        // 没库的时候解析域名是纯白耗：既画不出点，还白跑一次网络查询。
        val dns = FakeHostLookup(mapOf("hk1.example.com" to "5.6.7.8"))
        val locator = GeoLocator(NullGeoResolver, dns)

        assertFalse(locator.available)
        assertNull(locator.locate("hk1.example.com:443"))
        assertNull(locator.locate("185.248.33.40:22"))
        assertTrue("库不可用就不该有任何 DNS 开销", dns.queried.isEmpty())
    }

    // ------------------------------------------------------------------ 锚点

    @Test
    fun `当前节点与出口点可以分别解析`() {
        val geo = FakeGeoResolver(
            mapOf(
                "185.248.33.40" to geoPoint(43.29, 5.39, "FR"),
                "104.244.42.1" to geoPoint(37.75, -97.82, "US"),
            ),
        )
        val locator = GeoLocator(geo, FakeHostLookup())

        val anchors = GlobeAnchors.of(locator, "185.248.33.40:22", "104.244.42.1")

        assertFalse(anchors.isEmpty)
        assertEquals("FR", anchors.currentNode?.countryCode)
        assertEquals("US", anchors.exit?.countryCode)
    }

    @Test
    fun `任一锚点取不到就留空而不影响另一个`() {
        val geo = FakeGeoResolver(mapOf("185.248.33.40" to geoPoint(43.29, 5.39, "FR")))
        val locator = GeoLocator(geo, FakeHostLookup())

        // 出口 IP 还没采到（刚连上时的常态）：节点照样要画出来。
        val onlyNode = GlobeAnchors.of(locator, "185.248.33.40:22", null)
        assertNotNull(onlyNode.currentNode)
        assertNull(onlyNode.exit)
        assertFalse(onlyNode.isEmpty)

        assertTrue(GlobeAnchors.of(locator, null, null).isEmpty)
        assertTrue(GlobeAnchors.EMPTY.isEmpty)
    }

    // ------------------------------------------------------------------ 批量定位

    @Test
    fun `批量定位保留原始入参作为键_失败也为null而不消失`() {
        val geo = FakeGeoResolver(mapOf("1.2.3.4" to geoPoint(1.0, 2.0)))
        val locator = GeoLocator(geo, FakeHostLookup())

        val inputs = listOf("1.2.3.4:22", "nope.example.com:443", "", "999.1.1.1")
        val result = locator.locateAll(inputs)

        // 键必须原样保留：调用方要能区分"我传了但没结果"和"我没传"。
        assertEquals(inputs.toSet(), result.keys)
        assertNotNull(result["1.2.3.4:22"])
        assertNull(result["nope.example.com:443"])
        assertNull(result[""])
        assertNull(result["999.1.1.1"])
    }

    @Test
    fun `批量定位只把真域名交给DNS`() {
        val geo = FakeGeoResolver(
            mapOf("1.2.3.4" to geoPoint(1.0, 2.0), "5.6.7.8" to geoPoint(3.0, 4.0)),
        )
        val dns = FakeHostLookup(mapOf("a.example.com" to "5.6.7.8"))

        val result = GeoLocator(geo, dns).locateAll(listOf("1.2.3.4:22", "a.example.com:443", "999.1.1.1"))

        assertNotNull(result["1.2.3.4:22"])
        assertNotNull(result["a.example.com:443"])
        assertNull(result["999.1.1.1"])
        // IP 字面量走离线库、脏数字串被拦死 → 只有真域名进了 DNS，而且只进了一批。
        assertEquals(setOf("a.example.com"), dns.queried.toSet())
        assertEquals(1, dns.bulkQueries.size)
    }

    @Test
    fun `批量定位把整批域名一次性交给并发解析`() {
        val geo = FakeGeoResolver(mapOf("5.6.7.8" to geoPoint(3.0, 4.0), "9.9.9.9" to geoPoint(5.0, 6.0)))
        val dns = FakeHostLookup(mapOf("a.example.com" to "5.6.7.8", "b.example.com" to "9.9.9.9"))

        GeoLocator(geo, dns).locateAll(listOf("a.example.com:443", "b.example.com:443"))

        // 串行逐个问会让上百个域名节点的等待变成分钟级；必须一次性成批交出去。
        assertEquals(1, dns.bulkQueries.size)
        assertEquals(setOf("a.example.com", "b.example.com"), dns.bulkQueries[0].first.toSet())
        assertTrue("总时长预算必须传下去", dns.bulkQueries[0].second > 0L)
    }

    @Test
    fun `批量定位里完全相同的入参只交给DNS一次`() {
        val geo = FakeGeoResolver(mapOf("5.6.7.8" to geoPoint(3.0, 4.0)))
        val dns = FakeHostLookup(mapOf("a.example.com" to "5.6.7.8"))

        val result = GeoLocator(geo, dns).locateAll(listOf("a.example.com:443", "a.example.com:443"))

        assertEquals(1, dns.bulkQueries[0].first.size)
        assertEquals(1, result.size)
    }

    @Test
    fun `批量定位在库不可用时全为null且不碰DNS`() {
        val dns = FakeHostLookup(mapOf("a.example.com" to "1.2.3.4"))

        val result = GeoLocator(NullGeoResolver, dns).locateAll(listOf("a.example.com:443", "1.2.3.4:22"))

        assertEquals(setOf("a.example.com:443", "1.2.3.4:22"), result.keys)
        assertTrue(result.values.all { it == null })
        assertTrue(dns.queried.isEmpty())
        assertTrue(dns.bulkQueries.isEmpty())
    }

    // ------------------------------------------------------------------ 默认 DNS 实现

    @Test
    fun `默认批量实现对空串与IP字面量零网络开销`() {
        SystemHostAddressLookup.clear()

        val result = SystemHostAddressLookup.lookupAll(listOf("::1", "  127.0.0.1  ", "  "), 2_000L)

        // 键是 trim 之后的形式（只 trim，不做大小写归一化）。
        assertEquals(setOf("::1", "127.0.0.1"), result.keys)
        assertEquals(InetAddress.getByName("::1"), InetAddress.getByName(result["::1"]!!))
        assertEquals(InetAddress.getByName("127.0.0.1"), InetAddress.getByName(result["127.0.0.1"]!!))

        SystemHostAddressLookup.clear()
    }

    @Test
    fun `默认DNS实现对空串与IP字面量零网络开销`() {
        SystemHostAddressLookup.clear()

        assertNull(SystemHostAddressLookup.lookup(""))
        assertNull(SystemHostAddressLookup.lookup("   "))

        // IP 字面量走的是纯本地解析，不进网络；用它来验证缓存不会把结果改坏。
        val loopback = SystemHostAddressLookup.lookup("::1")
        assertNotNull(loopback)
        assertEquals(InetAddress.getByName("::1"), InetAddress.getByName(loopback!!))
        // 第二次命中缓存，结果必须一致。
        assertEquals(loopback, SystemHostAddressLookup.lookup("::1"))

        SystemHostAddressLookup.clear()
        assertEquals(loopback, SystemHostAddressLookup.lookup("::1"))
    }
}
