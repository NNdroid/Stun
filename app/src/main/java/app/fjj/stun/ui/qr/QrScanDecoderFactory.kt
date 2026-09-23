package app.fjj.stun.ui.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.journeyapps.barcodescanner.DefaultDecoderFactory

/** Shared decoder configuration for both dense single QR codes and animated frames. */
internal object QrScanDecoderFactory {
    internal val hints: Map<DecodeHintType, Any> = mapOf(
        // Dense v40 codes are sensitive to small finder-pattern dimension errors. ZXing 3.5.4
        // fixes the known computeDimension() failure; TRY_HARDER adds a slower fallback search
        // for real camera frames that are cropped, scaled, or slightly out of focus.
        DecodeHintType.TRY_HARDER to true,
    )

    fun create(): DefaultDecoderFactory = DefaultDecoderFactory(
        listOf(BarcodeFormat.QR_CODE),
        hints,
        null,
        0,
    )
}
