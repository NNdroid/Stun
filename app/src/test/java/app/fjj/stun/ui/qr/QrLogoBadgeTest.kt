package app.fjj.stun.ui.qr

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.appcompat.content.res.AppCompatResources
import app.fjj.stun.qr.AnimatedQrProtocol
import app.fjj.stun.qr.QrFrameRenderer
import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Random
import kotlin.math.roundToInt

/**
 * 二维码中心图标的两类回归：
 *
 * 1. **几何**——白底方块多大、图标多大、是不是真的居中、外面那圈白环还在不在；
 * 2. **能不能扫**——贴上徽标之后 zxing 还解不解得出来。这条才是这个功能的命门：
 *    徽标是**物理盖掉**了一片数据模块，全靠纠错码把它算回来，尺寸一旦踩过线，
 *    界面上完全看不出异常（图还是那张图），只有对面手机"怎么扫都扫不上"。
 *
 * 所以第 2 类测试故意**不做模糊豁免**：既测清晰图，也测先做 3x3 均值（模拟对焦不实）的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QrLogoBadgeTest {

    // ---------- 几何 ----------

    @Test
    fun `badge side is fifteen percent of the QR bitmap`() {
        assertEquals(54, QrLogoBadge.badgeSide(360))
        assertEquals(40, QrLogoBadge.iconSide(360)) // 54 * 0.74
    }

    @Test
    fun `badge stays well under the measured decoding ceiling`() {
        // 探测结果：L 级下 25% 边长仍能解（≈6.3% 面积，已经贴着 ~7% 的纠错容量），28% 全灭。
        // 这条是**护栏**：谁想把这个数往上调，必须先重跑遮挡探测，而不是拍脑袋改个常量。
        assertTrue(
            "badge fraction ${QrLogoBadge.BADGE_FRACTION} exceeds the measured safe ceiling",
            QrLogoBadge.BADGE_FRACTION <= 0.25f,
        )
        val areaRatio = QrLogoBadge.BADGE_FRACTION * QrLogoBadge.BADGE_FRACTION
        assertTrue("occluded area $areaRatio should leave ECC headroom", areaRatio < 0.05f)
    }

    @Test
    fun `rasterize produces an opaque square of the requested size`() {
        val bmp = QrLogoBadge.rasterize(ColorDrawable(Color.RED), 52)
        assertNotNull(bmp)
        bmp!!
        assertEquals(52, bmp.width)
        assertEquals(52, bmp.height)
        assertEquals(Color.RED, bmp.getPixel(26, 26))
        assertEquals(Color.RED, bmp.getPixel(0, 0))
    }

    @Test
    fun `rasterize refuses a non positive size instead of throwing`() {
        assertNull(QrLogoBadge.rasterize(ColorDrawable(Color.RED), 0))
    }

    @Test
    fun `the icon sits centered with a white ring around it and the QR outside untouched`() {
        val size = 400
        val qr = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val logo = QrLogoBadge.rasterize(ColorDrawable(Color.RED), QrLogoBadge.iconSide(size))!!

        QrLogoBadge.drawInto(qr, logo)

        val badge = QrLogoBadge.badgeSide(size)   // 60
        val icon = QrLogoBadge.iconSide(size)     // 44
        val cx = size / 2
        // 图标正中：红色和白色按 ICON_ALPHA 混合。红通道两端都是 255 不受影响，
        // 绿/蓝通道从 0 被拉到接近白的一半 —— 证明透明度真的生效。
        // （混合后是否还在二值化阈值之上，由专门的阈值测试判定，这里只看几何。）
        val center = qr.getPixel(cx, cx)
        assertEquals(255, Color.red(center))
        val tint = Color.green(center)
        assertTrue("icon should be translucent, got $tint", tint in 118..138)
        assertEquals(tint, Color.blue(center))
        // 白环：底方块内、图标外（取上边缘中点，避开圆角）
        assertEquals(Color.WHITE, qr.getPixel(cx, cx - badge / 2 + 2))
        assertEquals(Color.WHITE, qr.getPixel(cx - badge / 2 + 2, cx))
        // 底方块外：还是原来的黑
        assertEquals(Color.BLACK, qr.getPixel(cx, cx - badge / 2 - 3))
        assertEquals(Color.BLACK, qr.getPixel(2, 2))
        // 图标应该居中：左右两条对称位置的颜色一致
        assertEquals(qr.getPixel(cx - icon / 2 + 1, cx), qr.getPixel(cx + icon / 2 - 1, cx))
    }

    @Test
    fun `transparency costs no decoding headroom`() {
        // 图标整块都在**不透明**的白底方块里面，所以 alpha 改的只是"这块本来就被销毁的区域
        // 读成白还是读成黑"，碰不到白底以外的任何模块 —— 解码余量由 BADGE_FRACTION 决定，
        // 跟透明度无关。
        //
        // 实测：真图标、6px 与 3px/模块、清晰与 3x3 模糊，alpha 从 0.3 一路扫到 1.0 全部解得
        // 出来（一列 FAIL 都没有）。所以别指望靠调 alpha 换解码余量，这条路不通；反过来也别
        // 担心把 alpha 调低会损害扫描 —— 它是纯装饰。
        //
        // 这里钉住的是**两端**：默认值必须能扫，完全不透（旧行为）也必须能扫。
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        for (pixelPerModule in intArrayOf(6, 3)) {
            val text = frameText()
            val modules = 17 + 4 * QrFrameRenderer.versionFor(text) + 2 * QrFrameRenderer.QUIET_ZONE
            val size = modules * pixelPerModule
            val logo = AppCompatResources.getDrawable(context, app.fjj.stun.R.drawable.ic_fox_logo)
                ?.let { QrLogoBadge.rasterize(it, QrLogoBadge.iconSide(size)) }
            for (alpha in floatArrayOf(QrLogoBadge.ICON_ALPHA, 1.0f)) {
                for (soft in booleanArrayOf(false, true)) {
                    val qr = QrFrameRenderer.render(text, size, QrFrameRenderer.versionFor(text))!!
                    QrLogoBadge.drawInto(qr, logo, alpha)
                    assertEquals(
                        "alpha=$alpha px/module=$pixelPerModule soft=$soft",
                        text,
                        decode(qr, soft),
                    )
                }
            }
        }
    }

    @Test
    fun `a null logo leaves the QR untouched`() {
        val size = 200
        val qr = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        QrLogoBadge.drawInto(qr, null)
        assertEquals(Color.BLACK, qr.getPixel(size / 2, size / 2))
    }

    // ---------- 能不能扫 ----------

    /** 位图 → 灰度 → 可选模糊 → zxing 的 LuminanceSource。 */
    private class GraySource(private val gray: ByteArray, w: Int, h: Int) : LuminanceSource(w, h) {
        override fun getRow(y: Int, row: ByteArray?): ByteArray =
            (row ?: ByteArray(width)).also { gray.copyInto(it, 0, y * width, y * width + width) }
        override fun getMatrix(): ByteArray = gray.copyOf()
        override fun isCropSupported(): Boolean = false
        override fun crop(l: Int, t: Int, w: Int, h: Int): LuminanceSource = this
        override fun isRotateSupported(): Boolean = false
        override fun rotateCounterClockwise(): LuminanceSource = this
    }

    private fun grayOf(bitmap: Bitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return ByteArray(w * h).also { gray ->
            for (i in pixels.indices) {
                val p = pixels[i]
                gray[i] = ((0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p))).toInt().toByte()
            }
        }
    }

    /** 3x3 均值：粗略模拟相机对焦不实 / 屏幕像素栅格。 */
    private fun blur(gray: ByteArray, w: Int, h: Int): ByteArray {
        val out = gray.copyOf()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            var sum = 0
            for (dy in -1..1) for (dx in -1..1) sum += gray[(y + dy) * w + (x + dx)].toInt() and 0xFF
            out[y * w + x] = (sum / 9).toByte()
        }
        return out
    }

    private fun decode(bitmap: Bitmap, soft: Boolean): String? {
        val w = bitmap.width
        val h = bitmap.height
        val g = grayOf(bitmap)
        val final = if (soft) blur(g, w, h) else g
        return runCatching {
            QRCodeReader().decode(BinaryBitmap(GlobalHistogramBinarizer(GraySource(final, w, h)))).text
        }.getOrNull()
    }

    private fun frameText(): String {
        val payload = ByteArray(800).also { Random(11L).nextBytes(it) }
        return AnimatedQrProtocol.split(payload, chunkSize = 800).frameAt(0)
    }

    /** 每模块至少 6px，否则低分辨率本身就会解不出，那是探针的锅不是徽标的锅。 */
    private fun renderPixelSize(text: String): Int {
        val modules = 17 + 4 * QrFrameRenderer.versionFor(text) + 2 * QrFrameRenderer.QUIET_ZONE
        return modules * 6
    }

    @Test
    fun `a QR with the badge still decodes`() {
        val text = frameText()
        val size = renderPixelSize(text)
        val qr = QrFrameRenderer.render(text, size, QrFrameRenderer.versionFor(text))!!
        val logo = QrLogoBadge.rasterize(ColorDrawable(Color.DKGRAY), QrLogoBadge.iconSide(size))!!

        // 先确认没徽标时这张图本来就能解（对照组，免得徽标一挂就分不清是谁的锅）
        assertEquals("control: unbadged QR must decode", text, decode(qr, soft = false))

        QrLogoBadge.drawInto(qr, logo)
        assertEquals("badged QR must still decode", text, decode(qr, soft = false))
    }

    @Test
    fun `a QR with the badge still decodes when the camera is soft`() {
        val text = frameText()
        val size = renderPixelSize(text)
        val qr = QrFrameRenderer.render(text, size, QrFrameRenderer.versionFor(text))!!
        val logo = QrLogoBadge.rasterize(ColorDrawable(Color.DKGRAY), QrLogoBadge.iconSide(size))!!

        QrLogoBadge.drawInto(qr, logo)
        assertEquals(text, decode(qr, soft = true))
    }

    @Test
    fun `the badge survives on a dense version 40 payload too`() {
        // 静态单张码的上限载荷：version 40、177 模块。徽标是按**比例**算的，
        // 高版本下同样的比例盖掉的模块数更多，这条把最密的情况也钉住。
        //
        // ⚠️ 种子别乱换：zxing 的 `Detector.computeDimension()` 里有一句
        // `if ((dimension & 0x03) == 3) throw NotFoundException` —— 尺寸估算偏 2 个模块
        // （177 估成 175/179）就**直接放弃检测**。近容量（~2952 字节）的 v40 内容里
        // 有一批会撞上这个分支，表现为"干干净净的位图也解不出来"（实测 5 个种子里 1 个中招，
        // 且跟像素密度有关：3px/模块能解、6px/模块反而解不出）。
        // 这是 zxing 的既有行为，与徽标无关，但会让本测试随机挑种子时偶发假红。
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
        val rnd = Random(7L)
        val text = buildString(AnimatedQrProtocol.SINGLE_QR_MAX_BYTES) {
            repeat(AnimatedQrProtocol.SINGLE_QR_MAX_BYTES) { append(alphabet[rnd.nextInt(alphabet.length)]) }
        }
        val size = renderPixelSize(text)
        val qr = QrFrameRenderer.render(text, size, 40)!!
        val logo = QrLogoBadge.rasterize(ColorDrawable(Color.DKGRAY), QrLogoBadge.iconSide(size))!!

        assertEquals("control", text, decode(qr, soft = false))
        QrLogoBadge.drawInto(qr, logo)
        assertEquals("version 40 with badge", text, decode(qr, soft = false))
    }

    @Test
    fun `the badge really does occlude modules rather than being a no-op`() {
        // 反证：如果哪天 drawInto 因为尺寸算错画了个 0 大小的方块，
        // 上面的"还能解码"测试会照样全绿，而徽标其实根本没画上去。
        val size = 360
        val qr = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val logo = QrLogoBadge.rasterize(ColorDrawable(Color.RED), QrLogoBadge.iconSide(size))!!
        QrLogoBadge.drawInto(qr, logo)

        var changed = 0
        val expected = QrLogoBadge.badgeSide(size).let { it * it }
        for (y in 0 until size) for (x in 0 until size) if (qr.getPixel(x, y) != Color.BLACK) changed++
        // 圆角会削掉四个角，所以实际白色面积略小于 side²，但量级必须一致
        assertTrue("badge painted $changed px, expected roughly $expected", changed > expected * 0.9)
        assertEquals(
            "badge must be centered",
            qr.getPixel(size / 2 - QrLogoBadge.badgeSide(size) / 2 + 1, size / 2),
            qr.getPixel(size / 2 + QrLogoBadge.badgeSide(size) / 2 - 1, size / 2),
        )
    }

    /**
     * 出一张真实效果图（用的就是弹窗里那个 `ic_fox_logo`），落
     * `app/build/reports/ui-preview/qr-badge.png`，给肉眼验收用。
     */
    @Test
    fun `preview writes a png of the badged QR`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val text = frameText()
        val size = renderPixelSize(text)
        val qr = QrFrameRenderer.render(text, size, QrFrameRenderer.versionFor(text))!!
        val logo = AppCompatResources.getDrawable(context, app.fjj.stun.R.drawable.ic_fox_logo)
            ?.let { QrLogoBadge.rasterize(it, QrLogoBadge.iconSide(size)) }

        QrLogoBadge.drawInto(qr, logo!!)
        // 顺手确认一下：真图标贴上去之后照样解得出来
        assertEquals("badged with the real app icon", text, decode(qr, soft = false))

        val dir = File("build/reports/ui-preview").apply { mkdirs() }
        File(dir, "qr-badge.png").outputStream().use {
            assertTrue(qr.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test
    fun `icon side never exceeds the badge it sits on`() {
        for (s in listOf(120, 240, 360, 720, 1080)) {
            assertTrue(QrLogoBadge.iconSide(s) < QrLogoBadge.badgeSide(s))
            assertTrue(QrLogoBadge.badgeSide(s) < s / 4)
        }
        assertEquals(0.15f, QrLogoBadge.BADGE_FRACTION, 0f)
        // 120dp 的极小弹窗也得画得出一个能看见的图标（≥12px）
        assertTrue(QrLogoBadge.iconSide(120) >= 12)
        assertEquals((120 * 0.15f).roundToInt(), QrLogoBadge.badgeSide(120))
    }

    @Test
    fun `the translucent badge still decodes when the camera is soft too`() {
        // 上面那条阈值测试只看了灰度，这里用真图标 + 真模糊再走一遍完整解码链路：
        // 半透明是纯装饰，不能因为视觉变淡就把码搞废。
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val text = frameText()
        val size = renderPixelSize(text)
        val qr = QrFrameRenderer.render(text, size, QrFrameRenderer.versionFor(text))!!
        val logo = AppCompatResources.getDrawable(context, app.fjj.stun.R.drawable.ic_fox_logo)
            ?.let { QrLogoBadge.rasterize(it, QrLogoBadge.iconSide(size)) }

        QrLogoBadge.drawInto(qr, logo!!)
        assertEquals("translucent badge, crisp", text, decode(qr, soft = false))
        assertEquals("translucent badge, soft focus", text, decode(qr, soft = true))
    }
}
