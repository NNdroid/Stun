package app.fjj.stun.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix

import app.fjj.stun.repo.StunLogger

object QRUtils {
    fun generateQRCode(text: String, width: Int, height: Int): Bitmap? {
        return try {
            val bitMatrix: BitMatrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            // setPixel() crosses into Bitmap native storage once per pixel and
            // becomes noticeably slow for high-density QR images. Build the
            // buffer in memory and copy it in one operation instead.
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val rowOffset = y * width
                for (x in 0 until width) {
                    pixels[rowOffset + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            bitmap
        } catch (e: Exception) {
            StunLogger.e("QRUtils", "Failed to generate QR code", e)
            null
        }
    }
}
