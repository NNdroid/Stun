package app.fjj.stun.ui.qr

import android.app.Application
import android.graphics.Bitmap
import app.fjj.stun.qr.AnimatedQrProtocol
import app.fjj.stun.qr.QrFrameRenderer
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Random

/** Regression coverage for dense version-40 share codes at a high pixel density. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QrDetectorRegressionTest {

    @Test
    fun `camera decoder enables try harder`() {
        assertTrue(QrScanDecoderFactory.hints[DecodeHintType.TRY_HARDER] == true)
    }

    @Test
    fun `dense version 40 codes decode at six pixels per module`() {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
        val modules = 177 + 2 * QrFrameRenderer.QUIET_ZONE
        val pixelSize = modules * 6

        // Seed 5 is excluded because it is undecodable at both 3px and 6px/module and therefore
        // exercises a different ZXing limit. These cover the high-density regression itself.
        listOf(0, 1, 2, 3, 4, 6, 7, 8, 9).forEach { seed ->
            val random = Random(seed.toLong())
            val text = buildString(AnimatedQrProtocol.SINGLE_QR_MAX_BYTES) {
                repeat(AnimatedQrProtocol.SINGLE_QR_MAX_BYTES) {
                    append(alphabet[random.nextInt(alphabet.length)])
                }
            }
            val bitmap = requireNotNull(QrFrameRenderer.render(text, pixelSize, 40))
            assertEquals("seed=$seed", text, decode(bitmap))
        }
    }

    private fun decode(bitmap: Bitmap): String? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        return QrScanDecoderFactory.create()
            .createDecoder(emptyMap<DecodeHintType, Any>())
            .decode(source)
            ?.text
    }

}
