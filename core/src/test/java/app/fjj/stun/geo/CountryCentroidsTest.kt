package app.fjj.stun.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校验自动生成的 [CountryCentroids] 表本身。
 *
 * 这张表是脚本产物（`scripts/geo/gen_country_centroids.py`），代码评审时肉眼扫 250 条不现实，
 * 所以这里把"整表都在合法范围"变成断言，另外钉住几个关键取值当作"是否换了数据源"的哨兵。
 */
class CountryCentroidsTest {

    @Test
    fun `表规模与内容都在合法范围`() {
        val codes = CountryCentroids.codes
        assertEquals("codes 应当是整表的一个副本", CountryCentroids.size, codes.size)
        assertTrue("国家/地区数明显偏少，表可能没生成完整：${codes.size}", codes.size >= 200)

        listOf("US", "CN", "JP", "DE", "SG", "GB", "FR", "RU", "BR", "AU", "IN", "HK").forEach { code ->
            assertTrue("$code 应该出现在表里", code in codes)
        }

        codes.forEach { code ->
            assertTrue("$code 不是两位大写字母", code.length == 2 && code.all { it in 'A'..'Z' })
            val coordinates = CountryCentroids.of(code)
            assertNotNull("$code 取不到坐标", coordinates)
            assertEquals("$code 应当是一对经纬度", 2, coordinates!!.size)
            assertTrue("$code 纬度越界：${coordinates[0]}", coordinates[0] in -90.0..90.0)
            assertTrue("$code 经度越界：${coordinates[1]}", coordinates[1] in -180.0..180.0)
        }
    }

    @Test
    fun `查表容忍大小写与空白 认不出的返回 null`() {
        assertEquals(CountryCentroids.of("US")!![0], CountryCentroids.of(" us ")!![0], 0.0)
        listOf(null, "", " ", "USA", "U", "1A", "Z!", "ZZ").forEach { code ->
            assertNull("${code ?: "null"} 不该查到坐标", CountryCentroids.of(code))
        }
    }

    /**
     * 哨兵：这些值是脚本从 sapics city 库导出的结果（该国家出现最频繁的坐标）。
     * 它们变了就意味着**重新生成过表**（换了数据源、或改了归并精度）—— 应当是有意为之，
     * 那就顺手更新这里的期望值，而不是让改动悄悄溜过去。
     */
    @Test
    fun `关键取值与生成结果一致`() {
        assertCentroid("US", 37.751, -97.822)
        assertCentroid("CN", 34.773, 113.722)
        assertCentroid("HK", 22.258, 114.166)
        assertCentroid("JP", 35.69, 139.69)
        assertCentroid("SG", 1.287, 103.851)
        assertCentroid("DE", 51.299, 9.491)
    }

    private fun assertCentroid(code: String, latitude: Double, longitude: Double) {
        val coordinates = CountryCentroids.of(code)
        assertNotNull("$code 应有代表坐标", coordinates)
        assertEquals("$code 纬度", latitude, coordinates!![0], 1e-9)
        assertEquals("$code 经度", longitude, coordinates[1], 1e-9)
    }
}
