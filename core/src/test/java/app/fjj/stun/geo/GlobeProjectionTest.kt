package app.fjj.stun.geo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GlobeProjection] 的纯数学测试（不需要 Robolectric）。
 *
 * 重点盯住"假边"这个坑：视界处的闭合边必须沿视界圆展开成弧，不能退化成两点的弦。
 * 用 [视界闭合边必须展开成弧_假边回归] 把这条钉死——它是本轮唯一"做错就一眼看穿"的地方。
 */
class GlobeProjectionTest {

    private val eps = 1e-4f

    /** 构造以 (cLon,cLat) 为心、角距 [radiusDeg] 的小圆环（单位向量，首尾不重复）。 */
    private fun smallCircle(cLon: Float, cLat: Float, radiusDeg: Float, samples: Int): FloatArray {
        val b = GlobeProjection.Basis().apply { set(cLon, cLat) }
        val cx = b.zx; val cy = b.zy; val cz = b.zz          // 圆心（forward）
        val ex = b.xx; val ey = b.xy; val ez = b.xz          // east
        val nx = b.yx; val ny = b.yy; val nz = b.yz          // north
        val r = Math.toRadians(radiusDeg.toDouble())
        val cr = cos(r).toFloat()
        val sr = sin(r).toFloat()
        val out = FloatArray(samples * 3)
        for (i in 0 until samples) {
            val t = 2.0 * Math.PI * i / samples
            val ct = cos(t).toFloat()
            val st = sin(t).toFloat()
            out[i * 3] = cx * cr + (ex * ct + nx * st) * sr
            out[i * 3 + 1] = cy * cr + (ey * ct + ny * st) * sr
            out[i * 3 + 2] = cz * cr + (ez * ct + nz * st) * sr
        }
        return out
    }

    // ── 基 ──────────────────────────────────────────────────────────────────

    @Test
    fun `相机基是单位正交右手系`() {
        for (lon in listOf(-175f, -90f, 0f, 45f, 130f, 180f)) {
            for (lat in listOf(-60f, -20f, 0f, 33f, 60f)) {
                val b = GlobeProjection.Basis().apply { set(lon, lat) }
                val e = floatArrayOf(b.xx, b.xy, b.xz)
                val n = floatArrayOf(b.yx, b.yy, b.yz)
                val f = floatArrayOf(b.zx, b.zy, b.zz)
                assertEquals("|east|", 1f, len(e), eps)
                assertEquals("|north|", 1f, len(n), eps)
                assertEquals("|forward|", 1f, len(f), eps)
                assertEquals("e·n", 0f, e[0] * n[0] + e[1] * n[1] + e[2] * n[2], eps)
                assertEquals("e·f", 0f, e[0] * f[0] + e[1] * f[1] + e[2] * f[2], eps)
                assertEquals("n·f", 0f, n[0] * f[0] + n[1] * f[1] + n[2] * f[2], eps)
                // east × north = forward（右手系）
                val gx = e[1] * n[2] - e[2] * n[1]
                val gy = e[2] * n[0] - e[0] * n[2]
                val gz = e[0] * n[1] - e[1] * n[0]
                assertEquals("e×n 应等于 forward(x)", 0f, gx - f[0], eps)
                assertEquals("e×n 应等于 forward(y)", 0f, gy - f[1], eps)
                assertEquals("e×n 应等于 forward(z)", 0f, gz - f[2], eps)
            }
        }
    }

    private fun len(v: FloatArray) = kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    @Test
    fun `视点正中投影到盘心且对跖点最远`() {
        val lon = 116.4f
        val lat = 39.9f
        val b = GlobeProjection.Basis().apply { set(lon, lat) }
        val out = FloatArray(3)
        val center = GlobeProjection.unitVector(lon, lat)
        GlobeProjection.project(center[0], center[1], center[2], b, out, 0)
        assertEquals(0f, out[0], eps)
        assertEquals(0f, out[1], eps)
        assertEquals(1f, out[2], eps)

        val anti = GlobeProjection.unitVector(lon + 180f, -lat)
        GlobeProjection.project(anti[0], anti[1], anti[2], b, out, 0)
        assertEquals(-1f, out[2], eps)
    }

    @Test
    fun `unitVector 归一且极点与经度无关`() {
        for (lon in listOf(-180f, -37f, 0f, 91f, 180f)) {
            val p = GlobeProjection.unitVector(lon, 90f)
            assertEquals(0f, p[0], eps)
            assertEquals(0f, p[1], eps)
            assertEquals(1f, p[2], eps)
        }
        assertEquals(1f, len(GlobeProjection.unitVector(23f, -56f)), eps)
    }

    // ── 裁剪 ────────────────────────────────────────────────────────────────

    @Test
    fun `整环在正面时不产生视界点`() {
        val b = GlobeProjection.Basis().apply { set(0f, 0f) }
        val ring = smallCircle(0f, 0f, 40f, 48)
        val buf = GlobeProjection.ClipBuffer()
        GlobeProjection.clip(ring, b, buf)
        assertEquals("整环可见应原样保留", 48, buf.size)
        assertTrue(GlobeProjection.fullyVisible(buf))
    }

    @Test
    fun `整环在背面时裁剪为空`() {
        val b = GlobeProjection.Basis().apply { set(0f, 0f) }
        val ring = smallCircle(180f, 0f, 40f, 48)
        val buf = GlobeProjection.ClipBuffer()
        GlobeProjection.clip(ring, b, buf)
        assertEquals(0, buf.size)
        assertTrue(GlobeProjection.invisible(buf))
    }

    @Test
    fun `跨视界的环产生视界交点且交点落在盘缘`() {
        val b = GlobeProjection.Basis().apply { set(60f, 0f) }
        val ring = smallCircle(0f, 0f, 80f, 72)
        val buf = GlobeProjection.ClipBuffer()
        GlobeProjection.clip(ring, b, buf)
        assertTrue("应有顶点保留", buf.size > 0)
        assertFalse(GlobeProjection.fullyVisible(buf))

        val out = FloatArray(3)
        var horizonPoints = 0
        for (i in 0 until buf.size) {
            if (!buf.horizon[i]) continue
            horizonPoints++
            GlobeProjection.project(buf.xs[i], buf.ys[i], buf.zs[i], b, out, 0)
            val radius = kotlin.math.sqrt(out[0] * out[0] + out[1] * out[1])
            assertEquals("视界交点必须正好落在盘缘", 1f, radius, 1e-3f)
        }
        assertEquals("一条跨越视界的环应有 2 个交点", 2, horizonPoints)
    }

    /**
     * 假边回归：用一条**大圆**（恰好一半可见）做最坏情况——两个交点沿视界相隔 180°。
     * 若填充时用弦连接，这里会读到 ~180° 的跨度；正确实现会把弦展开成 3° 步长的弧。
     */
    @Test
    fun `视界闭合边必须展开成弧_假边回归`() {
        val b = GlobeProjection.Basis().apply { set(0f, 0f) }
        val greatCircle = smallCircle(90f, 0f, 90f, 180)
        val buf = GlobeProjection.ClipBuffer()
        GlobeProjection.clip(greatCircle, b, buf)

        val proj = FloatArray(3)
        var horizonCount = 0
        var maxGapDeg = 0.0
        var prevAngle = 0.0
        var prevWasHorizon = false
        GlobeProjection.forEachFillVertex(buf, b) { x, y, z, isHorizon ->
            if (isHorizon) {
                horizonCount++
                GlobeProjection.project(x, y, z, b, proj, 0)
                val ang = kotlin.math.atan2(proj[1].toDouble(), proj[0].toDouble())
                if (prevWasHorizon) {
                    var d = abs(ang - prevAngle) % (2 * Math.PI)
                    if (d > Math.PI) d = 2 * Math.PI - d
                    maxGapDeg = maxOf(maxGapDeg, Math.toDegrees(d))
                }
                prevAngle = ang
                prevWasHorizon = true
            } else {
                prevWasHorizon = false
            }
        }

        assertTrue("大圆被裁开应产生视界点（实际 $horizonCount）", horizonCount > 2)
        assertTrue(
            "相邻视界点跨度 ${"%.1f".format(maxGapDeg)}° 过大——弦代替了弧（假边回归）",
            maxGapDeg <= 6.0,
        )
    }

    @Test
    fun `描边不画视界闭合边`() {
        val b = GlobeProjection.Basis().apply { set(0f, 0f) }
        val greatCircle = smallCircle(90f, 0f, 90f, 180)
        val buf = GlobeProjection.ClipBuffer()
        GlobeProjection.clip(greatCircle, b, buf)

        val proj = FloatArray(3)
        var edges = 0
        var maxEdgeLen = 0f
        GlobeProjection.forEachStrokeEdge(buf) { ax, ay, az, bx, by, bz ->
            edges++
            GlobeProjection.project(ax, ay, az, b, proj, 0)
            val xa = proj[0]; val ya = proj[1]
            GlobeProjection.project(bx, by, bz, b, proj, 0)
            val len = kotlin.math.hypot((proj[0] - xa).toDouble(), (proj[1] - ya).toDouble())
            maxEdgeLen = maxOf(maxEdgeLen, len.toFloat())
        }
        assertTrue("应留下真实的可见海岸线边", edges > 0)
        // 真实海岸线边的投影长度受采样步长约束（这里环按 2° 采样，单边约 0.035）；
        // 若把视界闭合边当海岸线画，会得到一条横贯球面的弦（长度可达 2.0）。
        // 不用"两端是否贴盘缘"来判：2° 采样的真实顶点本来就可能落在盘缘 0.3% 以内。
        assertTrue(
            "最大可见边长 ${"%.3f".format(maxEdgeLen)} 过大——视界闭合边被当成海岸线画出来了",
            maxEdgeLen < 0.25f,
        )
    }

    // ── 大圆弧 ──────────────────────────────────────────────────────────────

    @Test
    fun `大圆弧只输出可见段并在断点标记新子路径`() {
        val b = GlobeProjection.Basis().apply { set(0f, 0f) }
        val scratch = FloatArray(181 * 3)
        val a = GlobeProjection.unitVector(-120f, 0f)
        val c = GlobeProjection.unitVector(0f, 0f)

        var count = 0
        var starts = 0
        GlobeProjection.forEachArcVertex(a, c, b, scratch) { x, y, z, startsNew ->
            count++
            if (startsNew) starts++
            assertTrue("不得输出背面顶点", z >= 0f)
            assertEquals("输出点必须是单位向量", 1f, kotlin.math.sqrt(x * x + y * y + z * z), 1e-3f)
        }
        assertTrue("应有可见段", count > 0)
        assertEquals("可见段只应起步一次", 1, starts)
    }

    @Test
    fun `完全背面的弧不输出任何顶点`() {
        val b = GlobeProjection.Basis().apply { set(0f, 0f) }
        val scratch = FloatArray(181 * 3)
        val a = GlobeProjection.unitVector(120f, 0f)
        val c = GlobeProjection.unitVector(170f, 0f)
        var count = 0
        GlobeProjection.forEachArcVertex(a, c, b, scratch) { _, _, _, _ -> count++ }
        assertEquals(0, count)
    }

    @Test
    fun `对跖两点不产生 NaN`() {
        // steps=4 → slerp 返回 5 个点，缓冲必须是 (steps+1)*3
        val out = FloatArray(5 * 3)
        val n = GlobeProjection.slerp(
            GlobeProjection.unitVector(0f, 0f),
            GlobeProjection.unitVector(180f, 0f),
            4,
            out,
        )
        assertEquals(5, n)
        for (i in 0 until n) {
            assertNotNull(out[i * 3])
            assertFalse("slerp 结果出现 NaN", out[i * 3].isNaN())
            assertEquals(1f, len(floatArrayOf(out[i * 3], out[i * 3 + 1], out[i * 3 + 2])), 1e-3f)
        }
    }

    @Test
    fun `夹角计算对称且与方向无关`() {
        val a = GlobeProjection.unitVector(-100f, 13f)
        val c = GlobeProjection.unitVector(40f, -27f)
        assertEquals(GlobeProjection.angleDeg(a, c), GlobeProjection.angleDeg(c, a), 1e-3f)
        assertEquals(0f, GlobeProjection.angleDeg(a, a), 1e-3f)
    }
}
