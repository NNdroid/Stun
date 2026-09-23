package app.fjj.stun.ui.view

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.View
import app.fjj.stun.R
import app.fjj.stun.geo.GeoPoint
import app.fjj.stun.geo.GlobeMarker
import app.fjj.stun.geo.GlobeTopology
import java.io.File
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 球缘**大气层**的回归：昼侧亮蓝、晨昏线一条很薄的暖色、夜侧只剩极弱的冷辉光。
 *
 * 判据分两层：
 * - [GlobeView.atmosphereColorAt] 是纯函数（`nd` → ARGB），直接按点积断言整套配色规律，不渲染；
 * - 渲染层再确认**配色真的落到了对的那一侧** —— 这一条同时钉住 `SweepGradient` 的旋向
 *   （Android 从 3 点钟起顺时针、屏幕 y 朝下），旋向若写反了，"蓝在太阳那侧"会当场失败。
 *
 * 相机姿态取 `(0, 0)`、太阳放在 `lon = −90°`：此时太阳方向的相机平面投影恰好是 `(−1, 0)`，
 * 即贴到球缘上后 `nd(α) = −cos α`。于是球缘四段各有确定归属：
 *
 * | 屏幕角 α | 位置 | nd | 应该有 |
 * |---|---|---|---|
 * | 180° | 左 | +1 | 亮蓝（太阳那侧） |
 * | ±90° | 上 / 下 | 0 | 暖色带（晨昏线过球缘的两点） |
 * | 0° | 右 | −1 | 极暗的冷辉光（午夜那侧） |
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlobeAtmosphereTest {

    // ── 第一层：纯函数 ────────────────────────────────────────────────────────

    @Test
    fun `昼侧最亮最蓝_夜侧几乎不亮`() {
        val view = newSizedView()
        val day = view.atmosphereColorAt(1f)
        val night = view.atmosphereColorAt(-1f)

        assertTrue(
            "昼侧大气必须偏蓝（B 应明显大于 R），实际 r=${Color.red(day)} b=${Color.blue(day)}",
            Color.blue(day) - Color.red(day) > 60,
        )
        assertTrue(
            "昼侧大气太淡了，alpha=${Color.alpha(day)}（应 ≥ 180，否则球缘那条亮蓝看不见）",
            Color.alpha(day) >= 180,
        )
        assertTrue(
            "夜侧大气太亮，alpha=${Color.alpha(night)}（应 ≤ 60，用户要求「几乎看不到亮蓝」）",
            Color.alpha(night) <= 60,
        )
        assertTrue(
            "昼侧球缘不比夜侧亮蓝：blue 昼=${Color.blue(day)} 夜=${Color.blue(night)} —— " +
                "两侧一样亮就回到了「一圈均匀描边」的老做法",
            Color.blue(day) - Color.blue(night) > 60,
        )
    }

    @Test
    fun `晨昏线是暖色峰_而且很薄不饱和`() {
        val view = newSizedView()
        val terminator = view.atmosphereColorAt(0f)

        assertTrue(
            "晨昏线处必须偏暖（R 应大于 B），实际 r=${Color.red(terminator)} b=${Color.blue(terminator)}",
            Color.red(terminator) - Color.blue(terminator) > 60,
        )
        assertTrue(
            "晨昏线暖色太饱和了，alpha=${Color.alpha(terminator)}（应 < 昼侧，且 ≤ 170）",
            Color.alpha(terminator) <= 170,
        )
        assertTrue(
            "晨昏线暖色不比昼侧蓝更暗 —— 它是一条薄带，不该比整条昼侧球缘还抢眼",
            Color.alpha(terminator) < Color.alpha(view.atmosphereColorAt(1f)),
        )

        // 带宽：nd 一过 ATMO_WARM_BAND (=0.10) 就不该再有任何**暖色残留** —— 判据是"颜色回到蓝多于红"，
        // 而不是"总 alpha 很小"：nd=0.3 处本来就有白昼蓝（alpha≈159）在，用 alpha 判会把白昼蓝误判成暖色。
        // 允许 `red == blue`：nd = −0.15 处整层已经透明（alpha 0），"没有暖色"同样成立。
        for (nd in listOf(-0.15f, 0.15f, -0.3f, 0.3f)) {
            val c = view.atmosphereColorAt(nd)
            assertTrue(
                "nd=$nd 处还有暖色残留（r=${Color.red(c)} b=${Color.blue(c)}，alpha=${Color.alpha(c)}）" +
                    " —— 暖色带太宽了，用户明确要「不要太宽」",
                Color.red(c) <= Color.blue(c),
            )
        }
        // 反过来钉"峰确实在 nd=0 且很陡"：正中间的红蓝差必须远大于偏开一点的地方。
        val peakRed = Color.red(terminator) - Color.blue(terminator)
        val offRed = Color.red(view.atmosphereColorAt(0.15f)) - Color.blue(view.atmosphereColorAt(0.15f))
        assertTrue(
            "暖色带不够陡：nd=0 处 r-b=$peakRed，nd=0.15 处 r-b=$offRed —— 带宽写大了",
            peakRed - offRed > 100,
        )
    }

    @Test
    fun `白昼蓝随 nd 单调爬升`() {
        val view = newSizedView()
        var prev = -1
        for (nd in listOf(0.10f, 0.20f, 0.30f, 0.40f, 0.60f, 1f)) {
            val a = Color.alpha(view.atmosphereColorAt(nd))
            assertTrue("nd=$nd 的 alpha=$a 没有比上一档（$prev）更大 —— 爬升区间写反了", a >= prev)
            prev = a
        }
        assertTrue("nd=1 时应该已经爬到满值附近，实际 alpha=$prev", prev >= 180)
    }

    // ── 第二层：真的画到对的那一侧 ─────────────────────────────────────────────

    @Test
    fun `蓝在太阳那侧_暖在晨昏线_夜侧最暗`() {
        // 太阳放在相机以西 90°：直射点正好压在球缘左侧。
        val bmp = render(sunLonOffsetDeg = -90f, name = "globe-atmo-sun-west")
        val r = minOf(bmp.width, bmp.height) / 2f * DISC_FILL_RATIO
        val cx = bmp.width / 2f
        val cy = bmp.height / 2f

        // 取**球缘外一点**：那一圈全是"大气环叠在近黑太空上"，不受贴图昼夜明暗干扰。
        val sampleR = r * ATMO_SAMPLE_RATIO
        val day = sectorMedian(bmp, cx, cy, sampleR, 180f)
        val warmUp = sectorMedian(bmp, cx, cy, sampleR, -90f)
        val warmDown = sectorMedian(bmp, cx, cy, sampleR, 90f)
        val night = sectorMedian(bmp, cx, cy, sampleR, 0f)
        println(
            "ATMO 昼侧=%08X 暖上=%08X 暖下=%08X 夜侧=%08X".format(day, warmUp, warmDown, night),
        )
        println(
            "ATMO 昼侧 b-r=%d  暖上 r-b=%d  暖下 r-b=%d  夜侧 b-r=%d  亮度 昼=%.1f 夜=%.1f".format(
                Color.blue(day) - Color.red(day),
                Color.red(warmUp) - Color.blue(warmUp),
                Color.red(warmDown) - Color.blue(warmDown),
                Color.blue(night) - Color.red(night),
                lum(day), lum(night),
            ),
        )

        assertTrue(
            "太阳那一侧的球缘不是蓝的：b-r=${Color.blue(day) - Color.red(day)}（应 > 25）—— " +
                "要么 sweep 旋向反了（蓝跑到对侧去了），要么这层根本没画",
            Color.blue(day) - Color.red(day) > 25,
        )
        assertTrue(
            "晨昏线过球缘的点不暖：上侧 r-b=${Color.red(warmUp) - Color.blue(warmUp)}、" +
                "下侧 r-b=${Color.red(warmDown) - Color.blue(warmDown)}（两侧都应 > 15）",
            Color.red(warmUp) - Color.blue(warmUp) > 15 &&
                Color.red(warmDown) - Color.blue(warmDown) > 15,
        )
        // 暖色必须**只**在晨昏线两侧，不能整圈都暖：昼侧那点不该比暖点更红。
        assertTrue(
            "昼侧球缘比晨昏线还红（昼 r-b=${Color.red(day) - Color.blue(day)}，" +
                "暖上 r-b=${Color.red(warmUp) - Color.blue(warmUp)}）—— 暖色带糊成一整圈了",
            Color.red(day) - Color.blue(day) < Color.red(warmUp) - Color.blue(warmUp),
        )
        assertTrue(
            "夜侧球缘太亮（%.1f vs 昼侧 %.1f）—— 用户要求夜侧大气「几乎看不到明显亮蓝」".format(
                lum(night), lum(day),
            ),
            lum(night) < lum(day) * 0.55f,
        )
    }

    /**
     * `SweepGradient` 的角向配色是**连续**的：晨昏线两侧各只占很窄一段弧，离它 20° 就该基本褪干净。
     *
     * ⚠️ 这一条只有 nd 的映射对（`nd(α) = −cos α`）才成立 —— 旋向写反时"暖点"会出现在另外两处，
     * 上面那条会先失败，这条是它的补充（钉"带宽"而不是"位置"）。
     */
    @Test
    fun `暖色带很窄_偏 20 度就褪掉`() {
        val bmp = render(sunLonOffsetDeg = -90f, name = "globe-atmo-band")
        val r = minOf(bmp.width, bmp.height) / 2f * DISC_FILL_RATIO
        val cx = bmp.width / 2f
        val cy = bmp.height / 2f
        val sampleR = r * ATMO_SAMPLE_RATIO

        val onBand = sectorMedian(bmp, cx, cy, sampleR, -90f)
        val offBand = sectorMedian(bmp, cx, cy, sampleR, -70f)
        val onRed = Color.red(onBand) - Color.blue(onBand)
        val offRed = Color.red(offBand) - Color.blue(offBand)
        println("ATMO 带内 r-b=%d  偏 20° 处 r-b=%d".format(onRed, offRed))
        assertTrue(
            "暖色带太宽了：正中 r-b=$onRed，偏 20° 还有 r-b=$offRed（应至少衰减一半）",
            offRed < onRed * 0.5f,
        )
    }

    /** 关掉开关必须退回**旧行为**（均匀描边），否则"关掉某一层"的既有测试基线会被悄悄改掉。 */
    @Test
    fun `关掉大气层后退回均匀描边`() {        val off = render(sunLonOffsetDeg = -90f, name = "globe-atmo-off", enabled = false)
        val r = minOf(off.width, off.height) / 2f * DISC_FILL_RATIO
        val cx = off.width / 2f
        val cy = off.height / 2f
        val sampleR = r * ATMO_SAMPLE_RATIO
        val day = sectorMedian(off, cx, cy, sampleR, 180f)
        val night = sectorMedian(off, cx, cy, sampleR, 0f)
        println("ATMO(off) 昼侧=%08X 夜侧=%08X".format(day, night))
        assertTrue(
            "关掉大气层后昼夜两侧球缘应当**一样**（就是原来那圈均匀描边），" +
                "实际昼=%.1f 夜=%.1f".format(lum(day), lum(night)),
            kotlin.math.abs(lum(day) - lum(night)) < 6f,
        )
    }

    /**
     * 只出图、不断言：三种典型太阳位置各来一张，供肉眼确认观感。
     *
     * - `−90`：太阳贴球缘 ⇒ 昼侧蓝占半圈、晨昏线在上下两点（上面那条测试用的姿态）。
     * - `−45`：常见姿态 ⇒ 昼蓝一段弧 + 两个分开的暖点。
     * - `0`：太阳在盘心（背对太阳看地球）⇒ `|S⊥| ≈ 0`，**整圈都逼近晨昏线**，应当是一圈暖光。
     *   这一张专门用来确认"没有把 |S⊥| 归一化掉"——归一化了这里就会变成"半圈蓝半圈暗"。
     */
    @Test
    fun `三种太阳位置出图`() {
        for (sun in listOf(-90f, -45f, 0f)) {
            render(sunLonOffsetDeg = sun, name = "globe-atmo-sun${sun.roundToInt()}")
        }
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    private fun render(sunLonOffsetDeg: Float, name: String, enabled: Boolean = true): Bitmap {
        val view = newSizedView().apply {
            starryMode = true
            sunEpochMillis = EQUINOX_NOON_UTC - (sunLonOffsetDeg / 15f * 3_600_000L).toLong()
            // 另外两层光效都摘掉：它们都在球缘/盘面上叠亮度，会把这一层要比的色差冲淡。
            sunGlowEnabled = false
            sphereSheenEnabled = false
            atmosphereEnabled = enabled
            submitTopology(
                GlobeTopology(
                    markers = listOf(GlobeMarker(point = GeoPoint(0.0, 0.0, "CN"), isCurrent = true)),
                    arcs = emptyList(),
                    available = true,
                ),
            )
        }
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val dir = File("build/reports/ui-preview").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }

    /**
     * 在球缘上按屏幕角取一串点，返回**逐通道中位数**。
     *
     * 为什么不用单点、也不用平均：星空模式背景有随机星点（约 0.7% 像素），单点可能正好采到一颗
     * 而整条断言失真；平均则会被一颗亮星拉偏。中位数对"少数离群点"免疫。
     */
    private fun sectorMedian(bmp: Bitmap, cx: Float, cy: Float, r: Float, centerDeg: Float): Int {
        val rs = ArrayList<Int>(SECTOR_SAMPLES)
        val gs = ArrayList<Int>(SECTOR_SAMPLES)
        val bs = ArrayList<Int>(SECTOR_SAMPLES)
        for (i in 0 until SECTOR_SAMPLES) {
            val deg = centerDeg + (i - (SECTOR_SAMPLES - 1) / 2f) * 3f
            val a = Math.toRadians(deg.toDouble())
            val x = (cx + r * cos(a)).roundToInt().coerceIn(0, bmp.width - 1)
            val y = (cy + r * sin(a)).roundToInt().coerceIn(0, bmp.height - 1)
            val c = bmp.getPixel(x, y)
            rs += Color.red(c); gs += Color.green(c); bs += Color.blue(c)
        }
        rs.sort(); gs.sort(); bs.sort()
        return Color.rgb(rs[rs.size / 2], gs[gs.size / 2], bs[bs.size / 2])
    }

    private fun lum(c: Int): Float =
        0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)

    private fun newSizedView(): GlobeView {
        val view = GlobeView(themedContext())
        val width = (GLOBE_SIZE_DP * view.resources.displayMetrics.density).roundToInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, width, view.measuredHeight)
        return view
    }

    private fun themedContext(): Context {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        val config = Configuration(activity.resources.configuration).apply { fontScale = 1f }
        val themed = ContextThemeWrapper(activity.createConfigurationContext(config), R.style.Theme_Stun)
        controller.setup()
        return themed
    }

    private companion object {
        const val GLOBE_SIZE_DP = 300f
        const val DISC_FILL_RATIO = 0.94f

        /**
         * 取样半径，相对球半径。取在球缘**外面**一点点：那里整圈都是"大气环叠在近黑太空上"，
         * 不会掺进贴图本身的昼夜明暗 —— 但仍在径向峰值的覆盖面里（带中心正落在 R 上）。
         */
        const val ATMO_SAMPLE_RATIO = 1.02f

        const val SECTOR_SAMPLES = 5

        /** 2026-09-21T12:00Z（秋分正午）：直射点 = (0, 0)，于是经度可直接按 sunLon 换算时刻。 */
        const val EQUINOX_NOON_UTC = 1_789_992_000_000L
    }
}
