package app.fjj.stun.geo

import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DayNightCompositor] 的纯数学单测：不挂 Robolectric（core 模块没配默认 SDK，
 * 挂 runner 会在 `DefaultSdkPicker` 处抛 `IllegalArgumentException`）。
 *
 * 判据全部落在"点积 → 混合因子"上，所以**全部用两张纯色贴图**构造：
 * 白天 = `(10, 11, 12)`、夜晚 = `(200, 201, 202)`，于是输出红通道就是混合因子的
 * 直接读数（`r = 10·f + 200·(1−f)`），不用反解任何贴图内容。
 *
 * 直射点统一放在 `(0°, 0°)` ⇒ `N·S = cos(lon)·cos(lat)`，于是**昼夜分界就是 |lon| = 90°**。
 *
 * 最关键的回归是 [旋转锚点不改变同一条经线上的颜色]：它是"转动不再闪"的**结构证明** ——
 * 相机姿态压根不进 [DayNightCompositor.compose]，换锚点只是把同一张结果绕经度循环移位，
 * 屏幕上同一块球面取到的颜色因此不可能变。
 */
class DayNightCompositorTest {

    private val w = 360
    private val h = 180
    private val daySolid = 0xFF0A0B0C.toInt()
    private val nightSolid = 0xFFC8C9CA.toInt()

    private fun solid(color: Int, n: Int) = IntArray(n) { color }

    /** 纹素中心的世界经度（等距圆柱 + 纹素中心约定）。 */
    private fun texelLon(x: Int, anchor: Float) = anchor + (x + 0.5f) * 360f / w

    /** 离 ([lonDeg], [latDeg]) 最近的那个纹素在扁平数组里的下标。 */
    private fun texelAt(lonDeg: Float, latDeg: Float, anchor: Float): Int {
        val x = ((lonDeg - anchor) * w / 360f - 0.5f).roundToInt().mod(w)
        val y = ((90f - latDeg) * h / 180f - 0.5f).roundToInt().coerceIn(0, h - 1)
        return y * w + x
    }

    private fun compose(
        sunLon: Float,
        sunLat: Float,
        anchor: Float,
        wide: Int = w,
        tall: Int = h,
        twilight: Float = 0f,
    ): IntArray = DayNightCompositor.compose(
        day = solid(daySolid, wide * tall),
        night = solid(nightSolid, wide * tall),
        w = wide,
        h = tall,
        sunLonDeg = sunLon,
        sunLatDeg = sunLat,
        anchorLonDeg = anchor,
        out = IntArray(wide * tall),
        twilight = twilight,
    )

    private fun red(c: Int) = (c ushr 16) and 0xFF

    /** 读某个世界经纬度的红通道（[wide]/[tall] 与 [compose] 一致）。 */
    private fun readAt(buf: IntArray, wide: Int, tall: Int, lon: Float, lat: Float, anchor: Float): Int {
        val x = ((lon - anchor) * wide / 360f - 0.5f).roundToInt().mod(wide)
        val y = ((90f - lat) * tall / 180f - 0.5f).roundToInt().coerceIn(0, tall - 1)
        return red(buf[y * wide + x])
    }

    // ── 基本判定 ────────────────────────────────────────────────────────────

    /** 直射点在 (0,0)、锚点 −180 时，昼侧纹素必须逐位等于白天贴图、夜侧等于夜晚贴图。 */
    @Test
    fun 白昼侧是白天贴图_夜侧是夜晚贴图() {
        val out = compose(sunLon = 0f, sunLat = 0f, anchor = -180f)
        // nd = cos(lon) ⇒ |lon| < 90 是白昼。
        for (lon in intArrayOf(-80, -60, -45, -30, -10, 0, 10, 30, 45, 60, 80)) {
            assertEquals(
                "经度 $lon°（nd=%.2f）应在白昼侧、逐位等于白天贴图".format(kotlin.math.cos(Math.toRadians(lon.toDouble()))),
                daySolid,
                out[texelAt(lon.toFloat(), 0f, -180f)],
            )
        }
        // |lon| > 92 才是干净的夜面（±90 附近是混合带）。
        for (lon in intArrayOf(100, 120, 145, 170, -100, -120, -145, -170)) {
            assertEquals(
                "经度 $lon°（nd=%.2f）应在夜侧、逐位等于夜晚贴图".format(kotlin.math.cos(Math.toRadians(lon.toDouble()))),
                nightSolid,
                out[texelAt(lon.toFloat(), 0f, -180f)],
            )
        }
    }

    /**
     * 昼夜分界必须落在**与直射点角距 90°** 处 —— 也就是真正的晨昏线。
     *
     * 这条是"旧实现错在哪"的直接对照：旧几何实现算出来的分界偏到**夜侧 10~12°**
     * （实测可见层界落在 λ ≈ 10°/22°/36°），且白昼半盘被整体压暗 37.6%。
     */
    @Test
    fun 昼夜分界落在与直射点角距90度处() {
        val out = compose(sunLon = 0f, sunLat = 0f, anchor = -180f)
        val row = h / 2

        // 沿赤道行扫：红通道从夜晚侧（≈200）跨进白昼侧（≈10），第一次低于 105 的点即分界。
        fun crossingLon(step: Int): Float {
            var x = if (step > 0) 0 else w - 1
            while (x in 0 until w) {
                if (red(out[row * w + x]) < 105) return texelLon(x, -180f)
                x += step
            }
            return Float.NaN
        }

        // 从 −180°（夜）往东扫 ⇒ 先进白昼 ⇒ 晨昏线**西支**，应落在 lon ≈ −90°。
        val westEdge = crossingLon(1)
        // 从 +180°（夜）往西扫 ⇒ 晨昏线**东支**，应落在 lon ≈ +90°。
        val eastEdge = crossingLon(-1)
        assertTrue("没扫到昼夜分界（西支），输出可能是常数", !westEdge.isNaN())
        assertTrue("没扫到昼夜分界（东支），输出可能是常数", !eastEdge.isNaN())
        assertTrue(
            "晨昏线西支应落在 lon ≈ −90°，实测 $westEdge°（偏到夜侧就是旧实现那个 bug）",
            abs(westEdge + 90f) <= 1.5f,
        )
        assertTrue(
            "晨昏线东支应落在 lon ≈ +90°，实测 $eastEdge°",
            abs(eastEdge - 90f) <= 1.5f,
        )
    }

    /** 直射点北移时晨昏线跟着倾斜：夏至时北纬 55° 仍白昼，南纬 55° 已入夜。 */
    @Test
    fun 赤纬变化时晨昏线跟着倾斜() {
        val lon = 80f
        val summer = compose(sunLon = 0f, sunLat = 23.44f, anchor = -180f)
        // nd = cos(lat)·cos(80°) + sin(lat)·sin(23.44°)
        //   lat=+55 → 0.0996 + 0.3258 = +0.425 ⇒ 白昼
        //   lat=−55 → 0.0996 − 0.3258 = −0.226 ⇒ 黑夜
        assertTrue(
            "夏至时北纬 55° 应仍在白昼",
            readAt(summer, w, h, lon, 55f, -180f) < 105,
        )
        assertTrue(
            "夏至时南纬 55° 应已入夜",
            readAt(summer, w, h, lon, -55f, -180f) > 105,
        )
    }

    /**
     * ⭐ **转动不再闪的结构证明**：相机姿态不是 [DayNightCompositor.compose] 的入参。
     *
     * 换一个锚点只是把同一张结果沿经度循环移位（见 [DayNightCompositor.offsetPxFor]），
     * 所以"世界经度 L 这一点是什么颜色"必须与锚点无关。屏幕上的闪烁来自每帧重新裁切区域，
     * 这条一旦成立，那条路就被彻底堵死了。
     */
    @Test
    fun 旋转锚点不改变同一条经线上的颜色() {
        val a1 = compose(sunLon = 0f, sunLat = 0f, anchor = -180f)
        val a2 = compose(sunLon = 0f, sunLat = 0f, anchor = -150f)
        // 深昼（±0°、±59.5°）与深夜（±120.5°）都取；偏离分界远，不受 1 纹素取整影响。
        for (lon in floatArrayOf(-59f, -30f, 0f, 30f, 59f, 120f, -120f)) {
            assertEquals(
                "锚点 −180° 与 −150° 在经度 $lon° 处取到了不同颜色（锚点没有真正等价于循环移位）",
                a1[texelAt(lon, 0f, -180f)],
                a2[texelAt(lon, 0f, -150f)],
            )
        }
    }

    /** 输出必须恒不透明：球面是实心的，出现透明纹素会透出背后的星野。 */
    @Test
    fun 输出恒不透明() {
        val out = compose(sunLon = 37f, sunLat = -21f, anchor = -30f)
        for (c in out) {
            assertEquals("出现非不透明纹素", 255, (c ushr 24) and 0xFF)
        }
    }

    /** 过渡带必须连续：相邻纹素的读数不能出现台阶（硬边会在球面上呈锯齿）。 */
    @Test
    fun 过渡带连续_相邻纹素不跳变() {
        val wide = 1024
        val tall = 512
        val out = compose(sunLon = 0f, sunLat = 0f, anchor = -180f, wide = wide, tall = tall)
        val row = tall / 2
        var maxJump = 0
        for (x in 1 until wide) {
            val jump = abs(red(out[row * wide + x]) - red(out[row * wide + x - 1]))
            if (jump > maxJump) maxJump = jump
        }
        assertTrue(
            "过渡带出现台阶：相邻纹素红通道最大跳变 $maxJump，应远小于昼/夜差 190 —— " +
                "跳变大说明混合带被写成了硬边",
            maxJump < 40,
        )
    }

    /** 暮光只在晨昏线附近叠暖色：昼/夜深处一点不动，最暖处落在 `N·S = 0`。 */
    @Test
    fun 暮光只叠在晨昏线附近() {
        val wide = 1024
        val tall = 512
        val plain = compose(sunLon = 0f, sunLat = 0f, anchor = -180f, wide = wide, tall = tall, twilight = 0f)
        val warm = compose(sunLon = 0f, sunLat = 0f, anchor = -180f, wide = wide, tall = tall, twilight = 1f)

        fun dR(lon: Float) = readAt(warm, wide, tall, lon, 0f, -180f) - readAt(plain, wide, tall, lon, 0f, -180f)

        val dDeep = dR(0f)        // nd = 1 ⇒ 直射点，最深处
        val dEdge = dR(90f)       // nd ≈ 0 ⇒ 晨昏线正中
        val dNight = dR(120f)     // nd ≈ −0.5 ⇒ 已远离晨昏线
        assertTrue("晨昏线上没有被叠暖色（Δr=$dEdge，应 ≥ 15）", dEdge >= 15)
        assertEquals("直射点处不该被暮光影响", 0, dDeep)
        assertEquals("夜面深处不该被暮光影响", 0, dNight)
        assertTrue("晨昏线上暖色应远强于两侧（$dEdge vs $dDeep / $dNight）", dEdge > dDeep + 10)
    }

    // ── 锚点换算 ────────────────────────────────────────────────────────────

    /** [DayNightCompositor.offsetPxFor] 与 [DayNightCompositor.anchorLonDeg] 必须互为逆运算。 */
    @Test
    fun 锚点与列偏移互为逆运算() {
        for (width in intArrayOf(360, 1024, 2048)) {
            val step = 360f / width
            for (target in floatArrayOf(-180f, -150f, -30f, 0f, 37f, 150f, 179f)) {
                val off = DayNightCompositor.offsetPxFor(target, width)
                assertTrue("列偏移越界：target=$target width=$width off=$off", off in 0 until width)
                val back = DayNightCompositor.anchorLonDeg(off, width)
                assertTrue(
                    "锚点往返误差过大：target=$target 回到 $back（一个纹素 = $step°）",
                    abs(back - target) <= step * 0.5f + 1e-3f,
                )
            }
        }
    }

    // ── 夜图增益 ────────────────────────────────────────────────────────────

    @Test
    fun 夜图增益只抬亮并夹到255() {
        val px = intArrayOf(
            0xFF000000.toInt(),           // 纯黑保持不变
            0xFF0A1414.toInt(),           // (10,20,20) × 1.15 = (11,23,23)
            0xFFF0F0F0.toInt(),           // 接近饱和 ⇒ 夹到 255
        )
        DayNightCompositor.boostNightLights(px, 1.15f)
        assertEquals("纯黑不该被抬起来", 0xFF000000.toInt(), px[0])
        assertEquals(11, (px[1] ushr 16) and 0xFF)
        assertEquals(23, (px[1] ushr 8) and 0xFF)
        assertEquals(255, (px[2] ushr 16) and 0xFF)
    }

    @Test
    fun 夜图增益不大于1时不动数据() {
        val px = intArrayOf(0xFF0A1414.toInt())
        DayNightCompositor.boostNightLights(px, 1f)
        assertEquals(0xFF0A1414.toInt(), px[0])
    }

    // ── 纯色遮罩（矢量回退） ────────────────────────────────────────────────

    /** 矢量回退的实色夜面：白昼侧透明、夜面随深度变浓、RGB 恒等于 tint。 */
    @Test
    fun 纯色遮罩白昼透明夜面渐浓() {
        val tint = 0xFF060D1A.toInt()
        val wide = 512
        val tall = 256
        val out = DayNightCompositor.composeTint(
            w = wide, h = tall,
            sunLonDeg = 0f, sunLatDeg = 0f,
            anchorLonDeg = -180f,
            tint = tint,
            out = IntArray(wide * tall),
        )
        fun alphaAt(lon: Float, lat: Float): Int {
            val x = ((lon + 180f) * wide / 360f - 0.5f).roundToInt().mod(wide)
            val y = ((90f - lat) * tall / 180f - 0.5f).roundToInt().coerceIn(0, tall - 1)
            return (out[y * wide + x] ushr 24) and 0xFF
        }

        assertEquals("白昼侧必须完全透明", 0, alphaAt(-40f, 0f))
        assertEquals("直射点（nd=1）必须完全透明", 0, alphaAt(0f, 0f))
        assertEquals("晨昏线附近（|nd| < softness）仍应透明", 0, alphaAt(88f, 0f))

        // nd = cos(lon) ⇒ 夜面在 |lon| > 90°。
        val deep = alphaAt(130f, 0f)     // nd ≈ −0.643 ⇒ 越过 ramp 起点，已到满
        val half = alphaAt(110f, 0f)     // nd ≈ −0.342 ⇒ 半途
        val antipode = alphaAt(175f, 0f) // 对足点 ⇒ 最深
        assertTrue("夜面深处应已到满 alpha（实测 $deep）", deep >= 200)
        assertTrue("夜面半途应比深处浅（half=$half < deep=$deep）", half in 1 until deep)
        assertTrue("对足点最深（$antipode ≥ $deep）", antipode >= deep)

        for (c in out) {
            assertEquals("遮罩的 RGB 必须是 tint 本身", tint and 0x00FFFFFF, c and 0x00FFFFFF)
        }
    }

    // ── 性能（不设死线，只打印基线） ─────────────────────────────────────────

    /**
     * 烘焙一张 1024×512 的耗时。这条不设硬上限（机器差异大），只把基线打进日志：
     * 单次烘焙要在"几毫秒"量级，否则拖动时每跨 30° 经度重烘一次会掉帧。
     */
    @Test
    fun 烘焙耗时基线打印() {
        val wide = 1024
        val tall = 512
        val day = solid(daySolid, wide * tall)
        val night = solid(nightSolid, wide * tall)
        val out = IntArray(wide * tall)
        repeat(3) {
            DayNightCompositor.compose(day, night, wide, tall, 0f, 0f, -180f, out)
        }
        val t0 = System.nanoTime()
        val runs = 10
        repeat(runs) {
            DayNightCompositor.compose(day, night, wide, tall, 12f, 5f, -30f, out)
        }
        val ms = (System.nanoTime() - t0) / 1_000_000.0 / runs
        println("[DayNightCompositor] 1024x512 单次烘焙 %.2f ms".format(ms))
        assertTrue("单次烘焙 %.2f ms，超过 200ms 说明内层循环退化（每纹素三角函数？）".format(ms), ms < 200.0)
    }
}
