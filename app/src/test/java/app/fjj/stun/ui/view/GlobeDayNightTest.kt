package app.fjj.stun.ui.view

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.View
import app.fjj.stun.R
import app.fjj.stun.geo.GeoPoint
import app.fjj.stun.geo.GlobeMarker
import app.fjj.stun.geo.GlobeTopology
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 星空模式的**昼夜贴合真实晨昏线**：把球面按屏幕点反投影成经纬度，再用**独立重算**的
 * `N·S` 公式（不调 `DayNightCompositor`）算出期望颜色，与渲染像素逐点比。
 *
 * 为什么要有这条（`GlobeViewTest` 里那几条不够）：
 * - `球面贴图按经纬度对位_盘心是几内亚湾` 一类只验**对位**，而且取点几乎都在白昼侧；
 * - 昼夜那几条验的是"不翻面 / 不闪"，即**自洽性**，不验"到底对不对得上真实太阳位置"；
 * - 于是"夜面、晨昏过渡带、以及太阳一天里连续挪动"这三块是**空白**，而用户看到的
 *   "有时候整盘发黑 / 晨昏线位置怪"恰恰只可能出在这里（太阳位置随真实时间走，
 *   一天里只有某些时刻才出错 ⇒ 表现为"有概率"）。
 *
 * 判据同时能抓住两类错法：① 贴图/网格转错角度（陆地点取到海）；② 合成把昼夜判反
 * （白昼侧铺了夜景贴图）。取样点挑"期望纹素周围 ±2 纹素同色"的位置，避开网格分段线性
 * 带来的几纹素偏差；盘心（hub 标记与辉光）与盘缘（描边）都不取样。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlobeDayNightTest {

    @Test
    fun `球面昼夜在一天的不同时刻都贴合真实晨昏线`() {
        val ctx = themedContext()
        val day = decode(ctx, "geo/earth_day.webp")
        val night = decode(ctx, "geo/earth_night.webp")

        // 三个有代表性的太阳位置（北京：正午 / 晨昏线正好压在经度上 / 午夜），
        // 再各配一个南半球相机位姿 —— 南北赤纬都要走到，免得只有北半球被验过。
        val epochs = listOf(
            "北京正午" to (EPOCH_BASE + 4 * HOUR_MS),
            "晨昏线过北京" to (EPOCH_BASE + 12 * HOUR_MS),
            "北京午夜" to (EPOCH_BASE + 16 * HOUR_MS),
        )
        val cameras = listOf(-30f to 20f, 20f to 116.4f)

        val failures = mutableListOf<String>()
        var checked = 0
        var worst = 0

        for ((label, epoch) in epochs) {
            val sun = subsolar(epoch)
            for ((camLat, camLon) in cameras) {
                val view = newSizedView(ctx).apply {
                    starryMode = true
                    sunEpochMillis = epoch
                    submitTopology(hubAt(camLat, camLon))
                }
                val bitmap = render(view)
                val w = bitmap.width
                val h = bitmap.height
                val px = IntArray(w * h)
                bitmap.getPixels(px, 0, w, 0, 0, w, h)

                val cx = w / 2f
                val cy = h / 2f
                val r = minOf(w, h) / 2f * DISC_FILL_RATIO

                var right = -0.90f
                while (right <= 0.901f) {
                    var up = -0.90f
                    while (up <= 0.901f) {
                        val d2 = right * right + up * up
                        if (d2 < 0.15f || d2 > 0.81f) {
                            up += GRID_STEP
                            continue
                        }
                        val depth = sqrt((1f - d2).coerceAtLeast(0f))
                        val v = inverse(right, up, depth, camLon, camLat)
                        val lon = Math.toDegrees(atan2(v[1].toDouble(), v[0].toDouble())).toFloat()
                        val lat = Math.toDegrees(
                            asin(v[2].toDouble().coerceIn(-1.0, 1.0)),
                        ).toFloat()

                        val dt = texel(day, lon, lat)
                        val nt = texel(night, lon, lat)
                        if (!uniform(day, dt) || !uniform(night, nt)) {
                            up += GRID_STEP
                            continue
                        }

                        val nd = nd(lon, lat, sun.second, sun.first)
                        val expected = expect(
                            day.px[dt.second * day.w + dt.first],
                            boost(night.px[nt.second * night.w + nt.first]),
                            nd,
                        )
                        val got = avg(px, w, h, (cx + r * right).toInt(), (cy - r * up).toInt())
                        val d = maxOf(
                            abs(Color.red(got) - Color.red(expected)),
                            abs(Color.green(got) - Color.green(expected)),
                            abs(Color.blue(got) - Color.blue(expected)),
                        )
                        checked++
                        if (d > worst) worst = d
                        if (d > TOL) {
                            failures += "$label 相机($camLat,$camLon) 点($lon,$lat) nd=${"%.3f".format(nd)} " +
                                "期望=$expected 实得=$got Δ=$d"
                        }
                        up += GRID_STEP
                    }
                    right += GRID_STEP
                }
            }
        }

        assertTrue("比对点数为 0，说明取样全被过滤掉了（判据失效）", checked > 100)
        assertTrue(
            "昼夜与真实晨昏线不符 ${failures.size} 处（最大通道差 $worst，详见失败消息）：\n" +
                failures.take(20).joinToString("\n"),
            failures.isEmpty(),
        )
    }

    // ────────────────────────────────────────────────── 独立重算（不调 DayNightCompositor）

    /** `DayNightCompositor.compose` 的同一套公式，在这里独立写一遍 —— 这样它本身出错也能抓住。 */
    private fun expect(dayTexel: Int, nightTexel: Int, nd: Float): Int {
        val e = 0.04f
        var res = when {
            nd >= e -> dayTexel
            nd <= -e -> nightTexel
            else -> {
                val t = (nd + e) / (2f * e)
                val f = t * t * (3f - 2f * t)
                val fq = (f * 256f).toInt()
                val dq = 256 - fq
                val r = ((((dayTexel ushr 16) and 0xFF) * fq) + (((nightTexel ushr 16) and 0xFF) * dq)) shr 8
                val g = ((((dayTexel ushr 8) and 0xFF) * fq) + (((nightTexel ushr 8) and 0xFF) * dq)) shr 8
                val b = (((dayTexel and 0xFF) * fq) + ((nightTexel and 0xFF) * dq)) shr 8
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        // 暮光带：|nd| < 0.15 时叠暖色 (26, 9, 1)。
        val band = 0.15f
        if (nd < band && nd > -band) {
            val t = 1f - abs(nd) / band
            if (t > 0f) {
                val wgt = t * t * (3f - 2f * t)
                if (wgt > 0f) {
                    val r = (((res ushr 16) and 0xFF) + 26 * wgt).toInt().coerceAtMost(255)
                    val g = (((res ushr 8) and 0xFF) + 9 * wgt).toInt().coerceAtMost(255)
                    val b = ((res and 0xFF) + 1 * wgt).toInt().coerceAtMost(255)
                    res = (res and (0xFF shl 24)) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        return res
    }

    /** 夜图的城市灯光增益 `boostNightLights`（×1.15），与 `NIGHT_LIGHTS_GAIN` 一致。 */
    private fun boost(texel: Int): Int {
        val r = (((texel ushr 16) and 0xFF) * 1.15f).toInt().coerceAtMost(255)
        val g = (((texel ushr 8) and 0xFF) * 1.15f).toInt().coerceAtMost(255)
        val b = ((texel and 0xFF) * 1.15f).toInt().coerceAtMost(255)
        return (texel and (0xFF shl 24)) or (r shl 16) or (g shl 8) or b
    }

    private fun nd(lon: Float, lat: Float, sunLon: Float, sunLat: Float): Float {
        val l = Math.toRadians(lon.toDouble())
        val f = Math.toRadians(lat.toDouble())
        val ls = Math.toRadians(sunLon.toDouble())
        val fs = Math.toRadians(sunLat.toDouble())
        return (cos(f) * cos(fs) * cos(l - ls) + sin(f) * sin(fs)).toFloat()
    }

    /** 与 `GlobeView.subsolarPoint` 同款的粗算（UTC 日 + 时角）。 */
    private fun subsolar(nowMillis: Long): Pair<Float, Float> {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = nowMillis }
        val doy = cal.get(Calendar.DAY_OF_YEAR)
        val hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
        val dec = -23.44f * cos(Math.toRadians((360.0 / 365.24) * (doy + 10)).toFloat())
        return dec to (12f - hour) * 15f
    }

    // ────────────────────────────────────────────────── 几何

    private fun inverse(right: Float, up: Float, depth: Float, camLon: Float, camLat: Float): FloatArray {
        val e = east(camLon)
        val n = north(camLon, camLat)
        val f = forward(camLon, camLat)
        return floatArrayOf(
            right * e[0] + up * n[0] + depth * f[0],
            right * e[1] + up * n[1] + depth * f[1],
            right * e[2] + up * n[2] + depth * f[2],
        )
    }

    private fun east(lon: Float): FloatArray {
        val l = Math.toRadians(lon.toDouble())
        return floatArrayOf((-sin(l)).toFloat(), cos(l).toFloat(), 0f)
    }

    private fun north(lon: Float, lat: Float): FloatArray {
        val l = Math.toRadians(lon.toDouble())
        val f = Math.toRadians(lat.toDouble())
        return floatArrayOf((-sin(f) * cos(l)).toFloat(), (-sin(f) * sin(l)).toFloat(), cos(f).toFloat())
    }

    private fun forward(lon: Float, lat: Float): FloatArray {
        val l = Math.toRadians(lon.toDouble())
        val f = Math.toRadians(lat.toDouble())
        return floatArrayOf((cos(f) * cos(l)).toFloat(), (cos(f) * sin(l)).toFloat(), sin(f).toFloat())
    }

    // ────────────────────────────────────────────────── 贴图取样

    private class Texels(val px: IntArray, val w: Int, val h: Int)

    private fun decode(ctx: Context, path: String): Texels {
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        val bmp = ctx.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("解码失败 $path")
        val out = IntArray(bmp.width * bmp.height)
        bmp.getPixels(out, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val texels = Texels(out, bmp.width, bmp.height)
        bmp.recycle()
        return texels
    }

    private fun texel(t: Texels, lon: Float, lat: Float): Pair<Int, Int> = Pair(
        ((lon + 180f) / 360f * t.w).toInt().coerceIn(0, t.w - 1),
        ((90f - lat) / 180f * t.h).toInt().coerceIn(0, t.h - 1),
    )

    /** 期望纹素周围 ±2 纹素是否同色 —— 不同质就不适合当判据（网格分段线性会有几纹素偏差）。 */
    private fun uniform(t: Texels, cell: Pair<Int, Int>): Boolean {
        val (col, row) = cell
        val base = t.px[row * t.w + col]
        for (dy in -2..2) {
            for (dx in -2..2) {
                val c = t.px[((row + dy + t.h) % t.h) * t.w + ((col + dx + t.w) % t.w)]
                if (maxOf(
                        abs(Color.red(c) - Color.red(base)),
                        abs(Color.green(c) - Color.green(base)),
                        abs(Color.blue(c) - Color.blue(base)),
                    ) > 6
                ) {
                    return false
                }
            }
        }
        return true
    }

    private fun avg(px: IntArray, w: Int, h: Int, x: Int, y: Int): Int {
        var sr = 0
        var sg = 0
        var sb = 0
        var n = 0
        for (dy in -1..1) {
            for (dx in -1..1) {
                val c = px[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
                sr += Color.red(c)
                sg += Color.green(c)
                sb += Color.blue(c)
                n++
            }
        }
        return Color.rgb(sr / n, sg / n, sb / n)
    }

    // ────────────────────────────────────────────────── 夹具

    private fun hubAt(lat: Float, lon: Float) = GlobeTopology(
        markers = listOf(GlobeMarker(point = GeoPoint(lat.toDouble(), lon.toDouble(), "CN"), isCurrent = true)),
        arcs = emptyList(),
        available = true,
    )

    private fun newSizedView(themed: Context): GlobeView {
        val view = GlobeView(themed)
        val width = (GLOBE_SIZE_DP * view.resources.displayMetrics.density).roundToInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, width, view.measuredHeight)
        return view
    }

    private fun render(view: GlobeView): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
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

        /** 网格分段线性 + 纹素取整带来的偏差上界；实测最大 27。 */
        const val TOL = 40

        const val GRID_STEP = 0.09f
        const val HOUR_MS = 3_600_000L

        /** 2026-09-20 16:00 UTC（秋分前后）。 */
        const val EPOCH_BASE = 1_789_920_000_000L
    }
}
