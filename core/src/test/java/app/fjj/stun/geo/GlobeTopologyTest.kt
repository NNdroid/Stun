package app.fjj.stun.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GlobeTopologyBuilder] 的出图规则。
 *
 * 这里的每条断言都对应一个**产品决策**，不是实现细节：谁是 hub、什么时候合并、什么时候不合并、
 * 弧怎么来。规则改了这些用例就该红。
 *
 * 其中最重要的一条：**订阅节点全部逐个出点，不做任何国家归并**。
 * 曾经按国家归并过一版（`nodeCount` 气泡），被用户明确否掉；下面的用例就是这条决策的回归闸门。
 */
class GlobeTopologyTest {

    // ------------------------------------------------------------------ 库不可用

    @Test
    fun `地理库不可用时返回EMPTY`() {
        val topology = builder(NullGeoResolver).build(
            currentNode = GlobeNode("1.2.3.4:22", "HK"),
            nodes = listOf(GlobeNode("5.6.7.8:22", "US")),
            connections = listOf(conn(1, "9.9.9.9")),
            exitIp = "9.9.9.9",
        )

        assertFalse(topology.available)
        assertTrue(topology.isEmpty)
        assertTrue(topology.arcs.isEmpty())
        assertNull(topology.hub)
    }

    // ------------------------------------------------------------------ hub 与弧

    @Test
    fun `当前节点是hub_弧连到每一个其它落点`() {
        val geo = fakeGeo(
            "185.248.33.40" to geoPoint(43.29, 5.39, "FR"),
            "104.244.42.1" to geoPoint(37.75, -97.82, "US"),
            "142.250.72.14" to geoPoint(51.50, -0.12, "GB"),
        )

        val topology = GlobeTopologyBuilder(GeoLocator(geo, FakeHostLookup())).build(
            currentNode = GlobeNode("185.248.33.40:22", "FR-1"),
            nodes = listOf(GlobeNode("104.244.42.1:22", "US-1")),
            connections = listOf(conn(1, "142.250.72.14")),
            exitIp = "104.244.42.1",
        )

        // FR(hub) / US(节点+出口) / GB(活跃目标) → 3 个点、2 条弧。
        assertEquals(3, topology.markers.size)
        assertEquals(2, topology.arcs.size)

        val hub = topology.hub
        assertNotNull(hub)
        assertEquals("FR-1", hub!!.label)
        assertEquals(0, topology.markers.indexOf(hub))

        // 每条弧都必须从 hub 出发。
        topology.arcs.forEach { arc ->
            assertEquals(hub.point.latitude, arc.from.latitude, 0.0)
            assertEquals(hub.point.longitude, arc.from.longitude, 0.0)
        }

        val us = topology.markers.single { !it.isCurrent && it.isExit }
        assertEquals(37.75, us.point.latitude, 0.0)
        assertEquals("US-1", us.label)
        // 弧要落到每个目的地的真实坐标上。
        assertEquals(1, topology.arcs.count { it.to.latitude == 37.75 })
    }

    @Test
    fun `当前节点定位不了时没有弧但散点照画`() {
        val geo = fakeGeo("104.244.42.1" to geoPoint(37.75, -97.82, "US"))

        val topology = GlobeTopologyBuilder(GeoLocator(geo, FakeHostLookup())).build(
            currentNode = GlobeNode("10.0.0.1:22", "unknown"), // 库里没有
            nodes = listOf(GlobeNode("104.244.42.1:22", "US-1")),
        )

        assertNull(topology.hub)
        assertTrue(topology.arcs.isEmpty())
        assertEquals(1, topology.markers.size)
        assertTrue(topology.available)
    }

    @Test
    fun `当前节点定位不了时出口降级为hub`() {
        // 内网/解析不出来的当前节点很常见；若因此没有弧，彗星动画整条链路就静默消失。
        // 出口是"流量真正出去的地方"，顶上当 hub 比没有 hub 强。
        val geo = fakeGeo(
            "104.244.42.1" to geoPoint(37.75, -97.82, "US"),
            "142.250.72.14" to geoPoint(51.50, -0.12, "GB"),
        )

        val topology = GlobeTopologyBuilder(GeoLocator(geo, FakeHostLookup())).build(
            currentNode = GlobeNode("10.0.0.1:22", "unknown"), // 库里没有
            nodes = listOf(GlobeNode("142.250.72.14:22", "GB-1")),
            exitIp = "104.244.42.1",
        )

        val hub = topology.hub
        assertNotNull("出口应顶上当 hub", hub)
        assertTrue("它同时还是出口", hub!!.isExit)
        // 弧从出口出发，连到每个其它落点。
        assertEquals(1, topology.arcs.size)
        val arc = topology.arcs.single()
        assertEquals(hub.point.latitude, arc.from.latitude, 0.0)
        assertEquals(hub.point.longitude, arc.from.longitude, 0.0)
        assertEquals(51.50, arc.to.latitude, 0.0)
    }

    @Test
    fun `出口与当前节点同格时合并成一个点_两个标志都为真`() {
        // "跳板机本身就是出口"是很常见的拓扑：一个点，两种身份。
        val geo = fakeGeo("185.248.33.40" to geoPoint(43.29, 5.39, "FR"))

        val topology = GlobeTopologyBuilder(GeoLocator(geo, FakeHostLookup())).build(
            currentNode = GlobeNode("185.248.33.40:22", "FR-1"),
            exitIp = "185.248.33.40",
        )

        assertEquals(1, topology.markers.size)
        val only = topology.markers[0]
        assertTrue(only.isCurrent)
        assertTrue(only.isExit)
        // 没有别的落点 → 没有弧（hub 自己不算目的地）。
        assertTrue(topology.arcs.isEmpty())
    }

    // ------------------------------------------------------------------ 全逐个（核心决策）

    @Test
    fun `订阅节点全部逐个出点_同国也不归并`() {
        // 40 个节点全部标成 US、但落在不同城市 —— 这是"禁止按国家归并"的最强证伪：
        // 只要还有一点点按国家分组，这里就不可能出来 40 个点。
        val ips = (0 until 40).map { "10.9.0.${it + 1}" }
        val geo = fakeGeo(
            *(
                ips.mapIndexed { index, ip ->
                    ip to geoPoint(20.0 + index * 0.5, 100.0 + index * 0.5, "US")
                } + HUB_ENTRY
                ).toTypedArray(),
        )

        val topology = GlobeTopologyBuilder(GeoLocator(geo, FakeHostLookup())).build(
            currentNode = GlobeNode("203.0.113.1:22", "hub"),
            nodes = ips.mapIndexed { index, ip -> GlobeNode("$ip:22", "us-$index") },
        )

        assertEquals(41, topology.markers.size) // hub + 40 个节点
        assertEquals(40, topology.arcs.size) // 弧数 = 出点数 - 1，每个目的地一条
        assertTrue("每个节点都必须有自己的点", topology.markers.all { it.nodeCount == 1 })
        assertEquals(
            "每个节点的显示名都要保留",
            (0 until 40).map { "us-$it" }.toSet(),
            topology.markers.mapNotNull { it.label }.filterNot { it == "hub" }.toSet(),
        )
    }

    @Test
    fun `坐标几乎重合的节点合成一个点_并记录数量`() {
        // 这不是聚合而是去重：同一个坐标上的多个节点本来就画在同一个像素上，
        // 叠着画除了浪费性能什么都看不出来。nodeCount 把"这里其实有几个"记下来。
        val geo = fakeGeo("10.3.0.1" to geoPoint(1.0, 1.0, null), HUB_ENTRY)

        val topology = GlobeTopologyBuilder(GeoLocator(geo, FakeHostLookup())).build(
            currentNode = GlobeNode("203.0.113.1:22", "hub"),
            nodes = (0 until 5).map { GlobeNode("10.3.0.1:22", "x-$it") },
        )

        assertEquals(2, topology.markers.size) // hub + 1 个去重后的点
        val merged = topology.markers.single { !it.isCurrent }
        assertEquals(5, merged.nodeCount)
        // 用的是真实落点，不是替身坐标 → 不该标近似。
        assertFalse(merged.approximate)
    }

    // ------------------------------------------------------------------ 活跃连接

    @Test
    fun `活跃连接按host聚合_同一目标只定位一次`() {
        val geo = fakeGeo("142.250.72.14" to geoPoint(51.50, -0.12, "GB"), HUB_ENTRY)

        val topology = GlobeTopologyBuilder(GeoLocator(geo, FakeHostLookup())).build(
            currentNode = GlobeNode("203.0.113.1:22", "hub"),
            connections = listOf(
                conn(1, "142.250.72.14"),
                conn(2, "142.250.72.14"),
                conn(3, "142.250.72.14"),
            ),
        )

        assertEquals(2, topology.markers.size)
        val active = topology.markers.single { !it.isCurrent }
        assertEquals(3, active.connectionCount)
        assertEquals("同一个目标只该被定位一次", 1, geo.queried.count { it == "142.250.72.14" })
    }

    @Test
    fun `活跃连接落在已有节点上时合并进那个点`() {
        val geo = fakeGeo(
            "185.248.33.40" to geoPoint(43.29, 5.39, "FR"),
            "142.250.72.14" to geoPoint(51.50, -0.12, "GB"),
        )

        val topology = GlobeTopologyBuilder(GeoLocator(geo, FakeHostLookup())).build(
            currentNode = GlobeNode("185.248.33.40:22", "FR-1"),
            nodes = listOf(GlobeNode("142.250.72.14:22", "GB-1")),
            connections = listOf(conn(1, "142.250.72.14"), conn(2, "142.250.72.14")),
        )

        // 只有一个 GB 落点：既是订阅节点，也是正在用的目标。
        assertEquals(2, topology.markers.size)
        val gb = topology.markers.single { !it.isCurrent }
        assertEquals("订阅节点的名字不该被主机名覆盖", "GB-1", gb.label)
        assertEquals(1, gb.nodeCount)
        assertEquals(2, gb.connectionCount)
    }

    @Test
    fun `活跃连接落在别处时单独出点并带上速率`() {
        val geo = fakeGeo(
            "185.248.33.40" to geoPoint(43.29, 5.39, "FR"),
            "142.250.72.14" to geoPoint(51.50, -0.12, "GB"),
        )
        val rateByHost = mapOf("142.250.72.14" to 400L)

        val topology = GlobeTopologyBuilder(GeoLocator(geo, FakeHostLookup())).build(
            currentNode = GlobeNode("185.248.33.40:22", "FR-1"),
            connections = listOf(conn(1, "142.250.72.14"), conn(2, "142.250.72.14")),
            rateOf = { rateByHost[it.host] ?: 0L },
        )

        val gb = topology.markers.single { !it.isCurrent }
        assertEquals(2, gb.connectionCount)
        assertEquals(800L, gb.bytesPerSecond) // 两条连接各 400
        // 它不是订阅节点，只是"正在被用的一个目标" → nodeCount 记 0。
        assertEquals(0, gb.nodeCount)

        val arc = topology.arcs.single()
        assertEquals(800L, arc.bytesPerSecond)
        assertEquals(2, arc.connectionCount)
    }

    @Test
    fun `活跃连接解析不出来时不产出点也不崩`() {
        val geo = fakeGeo("185.248.33.40" to geoPoint(43.29, 5.39, "FR"))

        val topology = GlobeTopologyBuilder(GeoLocator(geo, FakeHostLookup())).build(
            currentNode = GlobeNode("185.248.33.40:22", "FR-1"),
            connections = listOf(conn(1, "203.0.113.9")), // 库里没有
        )

        assertEquals(1, topology.markers.size)
        assertTrue(topology.arcs.isEmpty())
    }

    // ------------------------------------------------------------------ 身份（点选气泡要用）

    @Test
    fun `落点要带主地址_订阅节点地址优先于连接主机`() {
        val geo = fakeGeo(
            "185.248.33.40" to geoPoint(43.29, 5.39, "FR"),
            "142.250.72.14" to geoPoint(51.50, -0.12, "GB"),
        )

        val topology = GlobeTopologyBuilder(GeoLocator(geo, FakeHostLookup())).build(
            currentNode = GlobeNode("185.248.33.40:22", "FR-1"),
            nodes = listOf(GlobeNode("142.250.72.14:22", "GB-1")),
            connections = listOf(conn(1, "142.250.72.14")),
        )

        val gb = topology.markers.single { !it.isCurrent }
        assertEquals(
            "点选气泡要靠地址回查节点 —— 名字会重名，地址才是用户核对的凭据",
            "142.250.72.14:22",
            gb.address,
        )
        assertEquals("185.248.33.40:22", topology.hub!!.address)
    }

    @Test
    fun `纯出口落点不带地址_让展示层退回到地理信息`() {
        // 出口与任何订阅节点/活跃连接都不同格：地址留空。
        // 展示层看到空地址就得退回到城市/国家，而不是显示一个空行。
        val geo = fakeGeo(
            "185.248.33.40" to geoPoint(43.29, 5.39, "FR"),
            "104.244.42.1" to geoPoint(37.75, -97.82, "US"),
        )

        val topology = GlobeTopologyBuilder(GeoLocator(geo, FakeHostLookup())).build(
            currentNode = GlobeNode("185.248.33.40:22", "FR-1"),
            exitIp = "104.244.42.1",
        )

        val exit = topology.markers.single { it.isExit }
        assertNull(exit.address)
        assertNull(exit.label)
    }

    // ------------------------------------------------------------------ 稳定性

    @Test
    fun `反复build出图结果完全一致`() {
        // 渲染侧拿这个顺序当绘制顺序；顺序漂了看起来就像在闪。
        val geo = fakeGeo(
            "203.0.113.1" to geoPoint(43.29, 5.39, "FR"),
            "203.0.113.2" to geoPoint(37.75, -97.82, "US"),
            "203.0.113.3" to geoPoint(51.50, -0.12, "GB"),
        )
        val builder = GlobeTopologyBuilder(GeoLocator(geo, FakeHostLookup()))
        fun run() = builder.build(
            currentNode = GlobeNode("203.0.113.1:22", "FR-1"),
            nodes = listOf(GlobeNode("203.0.113.2:22", "US-1"), GlobeNode("203.0.113.3:22", "GB-1")),
            connections = listOf(conn(1, "203.0.113.3")),
            exitIp = "203.0.113.2",
        )

        assertEquals(run(), run())
        assertEquals(true, run().markers.first().isCurrent)
    }

    // ------------------------------------------------------------------ 域名节点

    @Test
    fun `域名节点走批量解析且预算被传下去`() {
        val dns = FakeHostLookup(mapOf("a.example.com" to "198.51.100.1", "b.example.com" to "198.51.100.2"))
        val geo = fakeGeo(
            "185.248.33.40" to geoPoint(43.29, 5.39, "FR"),
            "198.51.100.1" to geoPoint(35.68, 139.69, "JP"),
            "198.51.100.2" to geoPoint(1.28, 103.85, "SG"),
        )

        val topology = GlobeTopologyBuilder(GeoLocator(geo, dns)).build(
            currentNode = GlobeNode("185.248.33.40:22", "FR-1"),
            nodes = listOf(GlobeNode("a.example.com:443", "JP-1"), GlobeNode("b.example.com:443", "SG-1")),
        )

        assertEquals(3, topology.markers.size)
        // 两个域名必须**合成一批**交给 DNS，而不是各问一次。
        assertEquals(1, dns.bulkQueries.size)
        assertEquals(setOf("a.example.com", "b.example.com"), dns.bulkQueries[0].first.toSet())
        assertTrue("总时长预算必须传下去", dns.bulkQueries[0].second > 0L)
    }

    // ------------------------------------------------------------------ 工具

    /** 各用例统一用这个地址当"当前节点"，所以它必须能被定位，否则 hub 会消失。 */
    private val HUB_ENTRY = "203.0.113.1" to geoPoint(43.29, 5.39, "FR")

    private fun builder(geo: GeoResolver): GlobeTopologyBuilder =
        GlobeTopologyBuilder(GeoLocator(geo, FakeHostLookup()))

    private fun fakeGeo(vararg entries: Pair<String, GeoPoint>): FakeGeoResolver =
        FakeGeoResolver(entries.toMap())

    private fun conn(id: Long, host: String, read: Long = 0L, write: Long = 0L): ActiveConnection =
        ActiveConnection(
            id = id,
            targetAddr = "$host:443",
            targetHost = host,
            proxyAddr = "185.248.33.40:443",
            startedAtMillis = 0L,
            readBytes = read,
            writeBytes = write,
        )
}
