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
import java.io.File
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 球面光效两条的回归：**跟着直射点走的太阳光晕** 与 **贴图模式下的球面高光**。
 *
 * 两者都是叠在晨昏贴图**之上**的屏幕空间图层，所以判据不能用"像素等于贴图纹素"那一路
 * （`GlobeDayNightTest` / `GlobeViewTest` 会对不上，那些测试也因此把 [GlobeView.sunGlowEnabled]
 * 关掉了）。这里换成两条与贴图内容无关的办法：
 *
 * 1. **光晕**：同一姿势渲染两帧，只差 `sunGlowEnabled` —— 两帧除了这一层逐位相同，相减得到的
 *    差值就**精确等于**光晕的贡献（不含任何贴图噪声）。再把差值反解成 `SRC_OVER` 用的那个
 *    alpha（`Δc = a·(Sc − Cc)`），判据于是只跟"离直射点多远"有关，跟底下的陆/海、明/暗无关。
 * 2. **高光**：取一对"同经度、南北对称"的空旷洋面点，让它们分别落在球面高光的**最亮点**与
 *    **右下暗边**上。两点的纬度互为相反数 ⇒ 一天的 `N·S` 相同、贴图纹素又刻意挑成同色
 *    ⇒ 底层逐位相等，两帧的亮度差只可能来自高光。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlobeSunGlowTest {

    // ────────────────────────────────────────────── 太阳光晕

    @Test
    fun `太阳光晕跟着直射点走_离得越远越淡`() {
        // 秋分正午 UTC ⇒ 直射点在 (0°, 0°)；相机也对准它 ⇒ 直射点屏幕位置就是盘心，
        // 于是"到盘心的距离"等于"到直射点的距离"，径向剖面可以直接当判据。
        val view = starryView(EPOCH_EQUINOX_NOON, lat = 0f, lon = 0f)
        val on = render(view, "globe-glow-on")
        view.sunGlowEnabled = false
        val off = render(view, "globe-glow-off")

        val cx = view.width / 2f
        val cy = view.height / 2f
        val r = minOf(view.width, view.height) / 2f * DISC_FILL_RATIO

        val rings = RADII.map { frac -> ring(cx, cy, r * frac) }
        val profiles = rings.map { implied(on, off, it) }

        assertTrue(
            "前三个半径档上凑不出足够的采样点（底片太亮、反解不出 alpha），判据不成立：$profiles",
            profiles.take(3).all { it.n >= 4 },
        )

        val a0 = profiles[0].alpha
        val a1 = profiles[1].alpha
        val a2 = profiles[2].alpha

        assertTrue(
            "直射点附近没有看到光晕：反解出的 alpha=%.3f（期望 > 0.15）".format(a0),
            a0 > 0.15f,
        )
        assertTrue(
            "光晕的衰减不是单调的：%.3f → %.3f → %.3f（半径档 %s）".format(a0, a1, a2, RADII),
            a1 < a0 * 0.7f && a2 < a1 * 0.6f,
        )

        // 光晕半径是 0.9R：出了这个圈就该**一位都不加**（不是"淡到看不见"）。
        val outside = ring(cx, cy, r * 0.95f)
        val leaked = maxAbsDelta(on, off, outside)
        assertEquals("光晕越过自己的半径漏到了 0.95R 上：最大通道差 $leaked", 0, leaked)

        // 加进来的必须是那支**暖白**，不是中性白/冷白 —— 逐档反解出光源本身的三通道（式里的 alpha
        // 自己约掉了，见 [implied]）再比：绿必须明显高于蓝（暖），蓝又必须明显低于 255（不刺白）。
        for ((i, p) in profiles.take(3).withIndex()) {
            assertTrue(
                "光源不是暖白（半径档 $i 解出 rgb=${p.srcR}/${p.srcG}/${p.srcB}，期望 G > B + 15）",
                p.srcG > p.srcB + 15,
            )
            assertTrue(
                "光源偏刺白（半径档 $i 解出 blue=${p.srcB}，期望 < 240）",
                p.srcB < 240,
            )
        }
    }

    @Test
    fun `直射点绕到背面_盘上一分光都不加`() {
        // 相机仍钉在 (0°, 0°)；时刻 +12h ⇒ 直射点跑到经度 180°（球背面）。
        val view = starryView(EPOCH_EQUINOX_NOON + 12 * HOUR_MS, lat = 0f, lon = 0f)
        val on = render(view, "globe-glow-farside-on")
        view.sunGlowEnabled = false
        val off = render(view, "globe-glow-farside-off")

        // 不画就是不画：整幅画面必须逐位相同，不该留下任何半透明残迹。
        assertEquals(
            "直射点在背面时画面仍然变了 —— 光晕没有按 depth<=0 收掉",
            0,
            differingPixels(on, off),
        )
    }

    /**
     * 光晕的圆心必须是**直射点的屏幕位置**，不是盘心。
     *
     * 上一条刻意把相机对准直射点，光晕与盘心重合 —— 那种构图**分不出**"跟着太阳走"与
     * "永远铺在球心"两种实现。这里把太阳挪到与相机经度差 45° 处（屏幕 −0.707R），
     * 于是"朝着太阳那侧亮、背着那侧一位都不加"才成了判据。同时这条真实观感最接近面板里的样子
     * （hub 一般不在直射点上），出的预览图可以直接看效果。
     */
    @Test
    fun `太阳光晕的圆心是直射点的屏幕位置_不是盘心`() {
        // 相机在 (0°, 45°)，直射点在 (0°, 0°) ⇒ 屏幕 x = R·sin(0 − 45°) = −0.707R。
        val view = starryView(EPOCH_EQUINOX_NOON, lat = 0f, lon = 45f)
        val on = render(view, "globe-glow-offcenter-on")
        view.sunGlowEnabled = false
        val off = render(view, "globe-glow-offcenter-off")

        val cx = view.width / 2f
        val cy = view.height / 2f
        val r = minOf(view.width, view.height) / 2f * DISC_FILL_RATIO

        val sunward = implied(on, off, listOf(Pair(cx - 0.707f * r, cy)))
        assertTrue(
            "直射点所在的屏幕位置上没有光晕：反解出的 alpha=%.3f（期望 > 0.30）".format(sunward.alpha),
            sunward.alpha > 0.30f,
        )

        // 对径侧离直射点 1.414R，早已在 0.9R 的光晕半径之外 —— 必须一位都不变。
        val leaked = maxAbsDelta(on, off, listOf(Pair(cx + 0.707f * r, cy)))
        assertEquals(
            "背对直射点的一侧也被照亮了（最大通道差 $leaked）—— 光晕的圆心不是直射点（铺在盘心了？）",
            0,
            leaked,
        )
    }

    // ────────────────────────────────────────────── 贴图模式的球面高光

    @Test
    fun `贴图模式下球面高光也在_左上亮右下暗`() {
        val ctx = themedContext()
        val day = decode(ctx, "geo/earth_day.webp")

        // 找一对"同经度、南北对称"的空旷洋面点。挑同色是很关键的一步：这样"贴图那层"在
        // 两个点上逐位相等，两帧的亮度差就只可能来自屏幕空间的球面高光。
        val lat = 20.5f
        var lon = Float.NaN
        var probe = -70f
        while (probe <= 70f && lon.isNaN()) {
            // ⚠️ texel 的入参是 (经度, 纬度)：这里两点同经度、纬度互为相反数。
            val north = texel(day, probe, lat)
            val south = texel(day, probe, -lat)
            if (uniform(day, north) && uniform(day, south) && sameColor(day, north, south)) lon = probe
            probe += 5f
        }
        assertTrue("秋分白昼带里找不到一对同色的对称洋面点，判据失效", !lon.isNaN())

        // 让这两点分别落到屏幕的左上 / 右下：相机放在赤道上，把经度各偏开 Δ。
        // 正交投影下 right = cos(lat)·sin(lon − 相机经度)、up = sin(lat)，
        // 解 cos(lat)·sin(Δ) = 0.35 即得 Δ；两点于是分别落在 (∓0.35, ±0.35)。
        val delta = Math.toDegrees(
            asin(0.35 / cos(Math.toRadians(lat.toDouble()))),
        ).toFloat()

        // 光晕关掉：这条测的是高光，别把直射点那团暖光混进来。
        val upper = starryView(EPOCH_EQUINOX_NOON, lat = 0f, lon = lon + delta, glow = false)
        val lower = starryView(EPOCH_EQUINOX_NOON, lat = 0f, lon = lon - delta, glow = false)
        val top = render(upper, "globe-sheen-highlight")
        val bottom = render(lower, "globe-sheen-shade")

        val cx = top.width / 2f
        val cy = top.height / 2f
        val r = minOf(top.width, top.height) / 2f * DISC_FILL_RATIO

        // 高光的最亮点在 (cx − 0.35R, cy − 0.35R)，暗边在它的对径处 (cx + 0.35R, cy + 0.35R)。
        val highlight = avg(top, cx - 0.35f * r, cy - 0.35f * r)
        val shade = avg(bottom, cx + 0.35f * r, cy + 0.35f * r)
        val lh = lum(highlight)
        val ls = lum(shade)

        assertTrue(
            (
                "贴图模式下没看到球面高光：左上(高光位)亮度=%.1f，右下(暗边位)亮度=%.1f，" +
                    "应至少差 $MIN_SHEEN_CONTRAST。两点底图同色、纬度对称，差值只可能来自高光。"
                ).format(lh, ls),
            lh > ls + MIN_SHEEN_CONTRAST,
        )
    }

    // ────────────────────────────────────────────── 反解 / 采样

    /** 一圈 8 个采样点（屏幕坐标）。 */
    private fun ring(cx: Float, cy: Float, radius: Float): List<Pair<Float, Float>> =
        (0 until 8).map { k ->
            val a = k * (2.0 * Math.PI / 8.0)
            Pair(cx + (radius * cos(a)).toFloat(), cy + (radius * sin(a)).toFloat())
        }

    /**
     * 某个半径档上的反解结果：混合用的 alpha，以及**光源本身**解出来的三通道。
     *
     * alpha 只从**红通道**解（`a = Δr/(255 − Cr)`）是刻意的：`ensureSunGlowBitmap` 那支渐变四个档的
     * 红色都是 255，于是"源色的红 = 255"精确成立、不用假设。蓝/绿通道的源色是随半径从 250/234
     * 一路掉到 188/108 的（外圈更偏琥珀），拿常数去套会一口气把 alpha 解小三分之一。
     * 想知道"到底是不是暖白"，就逐档反解源色：`Sc = Cc + Δc/a`，式里的 a 自己约掉了。
     */
    private class Profile(val alpha: Float, val srcR: Int, val srcG: Int, val srcB: Int, val n: Int)

    private fun implied(on: Bitmap, off: Bitmap, pts: List<Pair<Float, Float>>): Profile {
        var sumA = 0f
        var sumR = 0f
        var sumG = 0f
        var sumB = 0f
        var n = 0
        for ((x, y) in pts) {
            val lit = avg(on, x, y)
            val dark = avg(off, x, y)
            val room = 255 - Color.red(dark)
            // 底色太亮 ⇒ 分母小、反解出的 alpha 全是噪声，跳过。
            if (room < 60) continue
            val dr = Color.red(lit) - Color.red(dark)
            if (dr <= 0) continue
            val a = dr / room.toFloat()
            sumA += a
            sumR += Color.red(dark) + (Color.red(lit) - Color.red(dark)) / a
            sumG += Color.green(dark) + (Color.green(lit) - Color.green(dark)) / a
            sumB += Color.blue(dark) + (Color.blue(lit) - Color.blue(dark)) / a
            n++
        }
        if (n == 0) return Profile(0f, 0, 0, 0, 0)
        return Profile(sumA / n, (sumR / n).toInt(), (sumG / n).toInt(), (sumB / n).toInt(), n)
    }

    /** 两帧在给定采样点上的最大通道差（取绝对值再取最大）。 */
    private fun maxAbsDelta(on: Bitmap, off: Bitmap, pts: List<Pair<Float, Float>>): Int {
        var worst = 0
        for ((x, y) in pts) {
            val a = avg(on, x, y)
            val b = avg(off, x, y)
            val d = maxOf(
                abs(Color.red(a) - Color.red(b)),
                abs(Color.green(a) - Color.green(b)),
                abs(Color.blue(a) - Color.blue(b)),
            )
            if (d > worst) worst = d
        }
        return worst
    }

    private fun lum(c: Int): Float = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)

    private fun avg(bitmap: Bitmap, x: Float, y: Float, r: Int = 2): Int {
        var sr = 0
        var sg = 0
        var sb = 0
        var n = 0
        for (dy in -r..r) {
            for (dx in -r..r) {
                val p = bitmap.getPixel(
                    (x + dx).toInt().coerceIn(0, bitmap.width - 1),
                    (y + dy).toInt().coerceIn(0, bitmap.height - 1),
                )
                sr += Color.red(p)
                sg += Color.green(p)
                sb += Color.blue(p)
                n++
            }
        }
        return Color.rgb(sr / n, sg / n, sb / n)
    }

    private fun differingPixels(a: Bitmap, b: Bitmap): Int {
        var count = 0
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) count++
            }
        }
        return count
    }

    // ────────────────────────────────────────────── 贴图取样

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

    private fun texel(t: Texels, lon: Float, lat: Float): Int =
        ((lon + 180f) / 360f * t.w).toInt().coerceIn(0, t.w - 1) +
            ((90f - lat) / 180f * t.h).toInt().coerceIn(0, t.h - 1) * t.w

    /** 该纹素周围 ±2 纹素是否同色 —— 不同质说明它压在海岸线上，不适合当判据。 */
    private fun uniform(t: Texels, index: Int): Boolean {
        val col = index % t.w
        val row = index / t.w
        val base = t.px[index]
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

    private fun sameColor(t: Texels, a: Int, b: Int): Boolean =
        maxOf(
            abs(Color.red(t.px[a]) - Color.red(t.px[b])),
            abs(Color.green(t.px[a]) - Color.green(t.px[b])),
            abs(Color.blue(t.px[a]) - Color.blue(t.px[b])),
        ) <= 6

    // ────────────────────────────────────────────── 夹具

    private fun starryView(epoch: Long, lat: Float, lon: Float, glow: Boolean = true): GlobeView {
        val view = newSizedView()
        view.starryMode = true
        view.sunEpochMillis = epoch
        view.sunGlowEnabled = glow
        view.submitTopology(
            GlobeTopology(
                // ⚠️ GeoPoint 是 (latitude, longitude)，别写反。
                markers = listOf(
                    GlobeMarker(point = GeoPoint(lat.toDouble(), lon.toDouble(), "CN"), isCurrent = true),
                ),
                arcs = emptyList(),
                available = true,
            ),
        )
        return view
    }

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

    /** 真实 draw 进 Bitmap，并落一张 PNG 到约定的预览目录（光效好不好看只能靠人眼扫一眼）。 */
    private fun render(view: GlobeView, name: String): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))

        val dir = File("build/reports/ui-preview").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
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

        /** 反解 alpha 的四个半径档（相对球半径）。最后两档用于验衰减，0.95R 在光晕半径之外。 */
        val RADII = listOf(0.30f, 0.55f, 0.80f, 0.95f)

        /** 左上高光位与右下暗边位的最小亮度差。实测约 34，这里只做"确实有高光"的下限。 */
        const val MIN_SHEEN_CONTRAST = 12

        const val HOUR_MS = 3_600_000L

        /** 2026-09-21T12:00Z（秋分正午）：直射点 = (0°, 0°)。 */
        const val EPOCH_EQUINOX_NOON = 1_789_992_000_000L
    }
}
