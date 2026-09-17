package app.fjj.stun.geo

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 海岸线资产的解码与语义测试。
 *
 * 资产本身入库（`core/src/main/assets/geo/coastline_110m.bin`，约 19.5 KB），所以这里
 * **不跳过**：找不到就是真问题。语义部分用奇偶射线法验证"哪块是陆地、哪块是海"，
 * 包括数据集里唯一的洞（里海）—— 洞有没有被正确保留，光看环数看不出来。
 */
class CoastlineDataTest {

    private val asset: File by lazy {
        var dir: File? = File("").absoluteFile
        repeat(8) {
            val d = dir ?: return@repeat
            val candidate = File(d, "core/src/main/assets/geo/coastline_110m.bin")
            if (candidate.isFile) return@lazy candidate
            dir = d.parentFile
        }
        File("core/src/main/assets/geo/coastline_110m.bin").absoluteFile
    }

    private fun decoded(): CoastlineData {
        assertTrue("未找到海岸线资产：${asset.absolutePath}", asset.isFile)
        val data = CoastlineData.decode(asset.readBytes())
        assertNotNull("资产解码失败", data)
        return data!!
    }

    /** 奇偶射线法；外环与内环一起统计，故里海会被正确判为海洋。 */
    private fun isLand(data: CoastlineData, lon: Float, lat: Float): Boolean {
        var inside = false
        for (ring in data.rings) {
            val n = ring.size / 2
            var j = n - 1
            for (i in 0 until n) {
                val xi = ring[i * 2]; val yi = ring[i * 2 + 1]
                val xj = ring[j * 2]; val yj = ring[j * 2 + 1]
                if ((yi > lat) != (yj > lat)) {
                    val xCross = xi + (lat - yi) * (xj - xi) / (yj - yi)
                    if (lon < xCross) inside = !inside
                }
                j = i
            }
        }
        return inside
    }

    @Test
    fun `解码真实资产的规模与包围盒`() {
        val data = decoded()
        assertEquals("环数", 128, data.ringCount)
        assertEquals("顶点数", 5015, data.pointCount)

        var minLon = Float.MAX_VALUE; var maxLon = -Float.MAX_VALUE
        var minLat = Float.MAX_VALUE; var maxLat = -Float.MAX_VALUE
        for (ring in data.rings) {
            for (i in 0 until ring.size / 2) {
                val lon = ring[i * 2]; val lat = ring[i * 2 + 1]
                if (lon < minLon) minLon = lon
                if (lon > maxLon) maxLon = lon
                if (lat < minLat) minLat = lat
                if (lat > maxLat) maxLat = lat
            }
        }
        assertEquals(-180f, minLon, 1e-3f)
        assertEquals(180f, maxLon, 1e-3f)
        assertEquals("南极必须到 -90，否则南极帽填充会破", -90f, minLat, 1e-3f)
        assertTrue("最北纬度不合理：$maxLat", maxLat in 82f..85f)
    }

    @Test
    fun `环首尾不重复_闭合由渲染侧负责`() {
        val data = decoded()
        for ((idx, ring) in data.rings.withIndex()) {
            val n = ring.size / 2
            assertTrue("环 $idx 顶点过少", n >= 3)
            val firstLon = ring[0]; val firstLat = ring[1]
            val lastLon = ring[(n - 1) * 2]; val lastLat = ring[(n - 1) * 2 + 1]
            assertTrue(
                "环 $idx 首尾点重复了（生成脚本应已去重）",
                firstLon != lastLon || firstLat != lastLat,
            )
        }
    }

    @Test
    fun `陆海判定正确_含里海洞`() {
        val data = decoded()
        // (经度, 纬度, 期望是否陆地)
        val cases = listOf(
            Triple(116.40f, 39.90f, true),    // 北京
            Triple(-0.13f, 51.51f, true),     // 伦敦
            Triple(18.42f, -33.92f, true),    // 开普敦
            Triple(-46.63f, -23.55f, true),   // 圣保罗
            Triple(-30f, 0f, false),          // 大西洋中部
            Triple(-140f, 0f, false),         // 太平洋中部
            Triple(50.5f, 42.0f, false),      // 里海——数据集唯一的洞
            Triple(0f, -85f, true),           // 南极洲内陆
        )
        for ((lon, lat, want) in cases) {
            assertEquals("($lon,$lat) 陆海判定不符", want, isLand(data, lon, lat))
        }
    }

    @Test
    fun `里海洞作为独立内环被保留`() {
        val data = decoded()
        val hole = data.rings.filter { ring ->
            val n = ring.size / 2
            (0 until n).all { i ->
                val lon = ring[i * 2]; val lat = ring[i * 2 + 1]
                lon in 46f..55f && lat in 36f..48f
            }
        }
        assertEquals("应恰好有一个里海内环", 1, hole.size)
        assertEquals("里海内环顶点数", 51, hole[0].size / 2)
    }

    @Test
    fun `资产体积在预算内`() {
        assertTrue("未找到海岸线资产", asset.isFile)
        val kb = asset.length() / 1024.0
        assertTrue("海岸线资产 ${"%.1f".format(kb)} KB 超出预算（40 KB）", kb < 40.0)
    }

    @Test
    fun `损坏或截断的字节流一律返回 null 而不是抛异常`() {
        val good = asset.readBytes()
        assertNotNull("原始资产应可解码", CoastlineData.decode(good))

        assertNull("空数组", CoastlineData.decode(ByteArray(0)))
        assertNull("长度不足头部", CoastlineData.decode(good.copyOfRange(0, 8)))

        val badMagic = good.copyOf()
        badMagic[0] = 'X'.code.toByte()
        assertNull("magic 错误", CoastlineData.decode(badMagic))

        val badVersion = good.copyOf()
        badVersion[8] = 99
        assertNull("版本不支持", CoastlineData.decode(badVersion))

        assertNull("截断一半", CoastlineData.decode(good.copyOfRange(0, good.size / 2)))

        val trailing = good + byteArrayOf(0, 0, 0, 0)
        assertNull("尾部多余字节应判为格式不符", CoastlineData.decode(trailing))

        val badScale = good.copyOf()
        badScale[10] = 0
        badScale[11] = 0
        assertNull("scale 为 0", CoastlineData.decode(badScale))
    }
}
