package app.fjj.stun.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.appcompat.content.res.AppCompatResources
import app.fjj.stun.core.R as CoreR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 品牌图标的「轻微 3D」回归：把光照写进矢量之后，钉住它不会被悄悄改回平涂。
 *
 * ## 为什么要像素级断言
 *
 * 图标是纯 XML，没有编译期检查。`fillColor` 从渐变退回单色、monochrome 又被指回彩色前景，
 * 这两类改动**编译通过、五端assembleDebug 全绿、lint 干净**，只有肉眼看真机才发现。
 * 所以这里用 Robolectric 的原生渲染（`GraphicsMode.NATIVE`，走Android 自己的
 * VectorDrawable 实现而不是我们的模拟器）取真实像素来判。
 *
 * ## 断言为什么带反事实
 *
 * 每个测试都先证明「采样确实落在目标上」再断言结果。少了这一步，一个偏了几个像素的
 * 采样点会读到白色吻部或轮廓外的透明区，测试照样绿——这种假绿比没有测试更贵。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconLightingTest {

    private fun raster(res: Int, size: Int = 256): Bitmap {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val d = requireNotNull(AppCompatResources.getDrawable(context, res)) { "drawable $res" }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bmp))
        return bmp
    }

    /**
     * 视口坐标 → 像素。**必须**走group 变换：图标 group 是 pivot 54,54 / scale 0.8，
     * 直接拿视口坐标当像素取会偏出去（曾因此把白色吻部当成了头部暗部）。
     */
    private fun pxAt(b: Bitmap, vx: Float, vy: Float): Int {
        val s = b.width / 108f
        val gx = 54f + (vx - 54f) * 0.8f
        val gy = 54f + (vy - 54f) * 0.8f
        return b.getPixel((gx * s).toInt(), (gy * s).toInt())
    }

    /** Orange head flesh, not the white muzzle and not the transparent outside. */
    private fun isHeadOrange(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        // red-minus-blue separates orange from the white muzzle (r == b) without
        // pinning green, which legitimately runs 74..196 across the shading.
        return r > 200 && b < 130 && (r - b) > 80
    }

    private fun lum(c: Int) =
        0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)

    @Test
    fun foregroundHeadIsLitFromUpperLeftNotFlat() {
        val bmp = raster(app.fjj.stun.R.drawable.ic_launcher_foreground)

        // 三个点都必须在**橙色头部**内。白色吻部占x28..80 / y65..102，
        // 取到那儿会读出纯白，测试就因为跟光照无关的原因失败。
        val lit = pxAt(bmp, 36f, 48f)
        val mid = pxAt(bmp, 60f, 45f)
        val shade = pxAt(bmp, 86f, 78f)

        // 反事实：先证明三个点都真的落在头部橙色上，不是吻部/背景。
        for ((name, c) in listOf("lit" to lit, "mid" to mid, "shade" to shade)) {
            assertTrue("$name is not head orange: ${String.format("%08X", c)}", isHeadOrange(c))
        }

        val lLit = lum(lit)
        val lMid = lum(mid)
        val lShade = lum(shade)
        assertTrue("no directional shading: lit=$lLit shade=$lShade", lLit - lShade > 25.0)
        // 单调：中间点必须落在两端之间，否则说明渐变轴被改成了非单调的形状。
        assertTrue("gradient not monotonic: $lLit / $lMid / $lShade", lMid < lLit && lMid > lShade)
    }

    @Test
    fun monochromePunchesAlphaHolesForEyesAndNose() {
        val bmp = raster(app.fjj.stun.R.drawable.ic_launcher_monochrome)

        val head = pxAt(bmp, 54f, 45f)
        val eyeL = pxAt(bmp, 43f, 68f)
        val eyeR = pxAt(bmp, 65f, 68f)
        val nose = pxAt(bmp, 54f, 82f)

        // 反事实：先证明取样点确实在头部实体上。
        assertEquals("sample point is off the head", 255, Color.alpha(head))
        assertTrue("eyeL sample is not over a hole", Color.alpha(eyeL) < 128)
        assertTrue("eyeR sample is not over a hole", Color.alpha(eyeR) < 128)
        assertTrue("nose sample is not over a hole", Color.alpha(nose) < 128)

        // 主题图标只读 alpha 通道：五官必须是**镂空**，而不是不同颜色。
        // 曾因monochrome 指向彩色前景而糊成一团，这里就是那道护栏。
        assertEquals("left eye must be fully transparent", 0, Color.alpha(eyeL))
        assertEquals("right eye must be fully transparent", 0, Color.alpha(eyeR))
        assertEquals("nose must be fully transparent", 0, Color.alpha(nose))
    }

    @Test
    fun inAppLogoKeepsFlatArtForRemoteViewsAndQrSafety() {
        // ic_fox_logo 被 4 个 RemoteViews 桌面组件、2 个 QS 磁贴、二维码徽标和
        // about 的 app:tint 复用，这些位置都吃不下渐变与描边。它必须保持平涂。
        val flat = raster(CoreR.drawable.ic_fox_logo)

        // 比同一半区内的两点：扁平原版的**左侧本来就有 #E64A19 硬阴影**，
        // 跨左右比必然不同，那测的是原有设计而不是「有没有渐变」。
        val a = pxAt(flat, 75f, 60f)
        val b = pxAt(flat, 86f, 78f)

        for ((name, c) in listOf("a" to a, "b" to b)) {
            assertTrue("$name is not head orange: ${String.format("%08X", c)}", isHeadOrange(c))
        }
        assertEquals(
            "ic_fox_logo must stay flat (RemoteViews / QR / tint depend on it)",
            a, b
        )
    }

    @Test
    fun logoVariantsShareTheSameSilhouette() {
        // 前景与 logo 变体是手抄的两份（形状必须一致，只有光照不同）。
        // 尺寸不同不好直接比alpha 通道，改为比对「非透明像素的包围盒宽高比」。
        fun aspect(res: Int): Float {
            val b = raster(res, 256)
            var minX = 256; var maxX = -1; var minY = 256; var maxY = -1
            for (y in 0 until 256) for (x in 0 until 256) {
                if (Color.alpha(b.getPixel(x, y)) > 16) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
            assertTrue("drawable $res rendered empty", maxX > minX && maxY > minY)
            return (maxX - minX).toFloat() / (maxY - minY).toFloat()
        }

        val a = aspect(app.fjj.stun.R.drawable.ic_launcher_foreground)
        val b = aspect(CoreR.drawable.ic_fox_logo_3d)
        // scale 0.8 vs 1.05 只影响大小，不影响比例。
        assertTrue("silhouettes diverged: fg=$a logo3d=$b", kotlin.math.abs(a - b) < 0.05f)
    }
}
