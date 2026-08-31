package app.fjj.stun.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * QRUtils 测试：守护「分享二维码生成」。
 *  - 小文本应成功生成 Bitmap；
 *  - 超大文本（远超 QR 容量上限，对应此前 "data too big" 报错）应安全返回 null 而非抛异常。
 */
@RunWith(AndroidJUnit4::class)
class QRUtilsTest {

    @Test
    fun generateQRCode_smallText_returnsBitmap() {
        val bmp = QRUtils.generateQRCode("hello-stun", 512, 512)
        assertNotNull(bmp)
        assertEquals(512, bmp!!.width)
        assertEquals(512, bmp!!.height)
    }

    @Test
    fun generateQRCode_oversizedText_returnsNull() {
        // version 40 / 纠错级 L 上限约 2953 字节，远超此长度的文本应触发异常并被捕获返回 null
        val huge = "x".repeat(8000)
        val bmp = QRUtils.generateQRCode(huge, 512, 512)
        assertNull("超大文本应安全返回 null（data too big 的 guard），而非抛异常", bmp)
    }
}
