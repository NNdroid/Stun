package app.fjj.stun.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import com.google.zxing.qrcode.encoder.QRCode

/**
 * 把 [AnimatedQrProtocol] 的一帧字符串光栅化成二维码位图。
 *
 * ## 为什么整轮必须钉死同一个 QR 版本
 *
 * 这是动画二维码能不能被扫到的**成败关键**，比"每帧塞多少字节"重要得多。
 *
 * zxing 默认按内容长度挑**最小可用版本**。一帧装 800 字节 → version 23 附近，最后一帧只剩几十
 * 字节 → 可能掉到 version 8。两者模块数从 109 掉到 49，同一个 [render] 的 sizePx 下每模块边长
 * 差一倍多：播放时整张码的"格子密度"每轮都在跳，相机的自动对焦与寻像框跟着来回调整，
 * 结果是**帧率越高越扫不到**（每帧都还没锁定就被切走了）。
 *
 * 所以这里先量出"最长一帧"需要的版本（[versionFor]），再用 [EncodeHintType.QR_VERSION] 把
 * **所有帧**钉到那个版本：模块数恒定 ⇒ 每模块边长恒定 ⇒ 画面里只有内容在变，几何完全静止。
 * 短的帧只是右下方少填几行数据，观感上码框纹丝不动。
 *
 * ## 为什么纠错级用 L
 *
 * 屏幕→摄像头这条信道比印刷品"干净"得多：没有磨损、没有油污，主要误差来源是反光与摩尔纹，
 * 而这些是**整块糊掉**而不是零星误码，Reed-Solomon 帮不上忙（真正兜底的是每帧 CRC32 + 整份
 * SHA-256，见 [AnimatedQrProtocol]）。同时 L 容量最大，同样边长下能把版本压得更低、模块更大
 * 更好扫。用 H 只会让版本暴涨、模块变小，净亏。
 *
 * ## 实现说明
 *
 * 渲染走 `QRCodeWriter.encode`；测版本走 `Encoder.encode`。两者**同源**
 * （`QRCodeWriter.encode` 内部就是 `Encoder.encode` + 光栅化），所以量出来的版本与
 * 渲染时实际用的一定一致。
 */
object QrFrameRenderer {

    /** 纠错级，理由见类注释。 */
    private val EC_LEVEL = ErrorCorrectionLevel.L

    /** 静默区（模块数）。规范要求 4；显式传入，不依赖 zxing 的默认值。 */
    const val QUIET_ZONE = 4

    /**
     * 一帧字符串自然需要的最小版本号（1..40）。
     *
     * 发送端用它量"最长一帧"，再把结果喂给 [render] 钉住整轮。
     * 纯计算、不碰 Bitmap，因此可在裸 JVM 里断言。
     */
    fun versionFor(text: String): Int = Encoder.encode(text, EC_LEVEL, hints(null)).version.versionNumber

    /**
     * 用固定 [version] 编码，返回实际采用的版本号；放不下（正常流程不该发生）返回 null 而不是抛
     * —— 播放循环里抛异常会把动画打断。
     *
     * 若 zxing 忽略了 `QR_VERSION` 提示，这里会返回**自然**版本号而不是 null，
     * 于是"钉版本没生效"这件事会在单测里暴露出来，而不是在用户面前表现为"扫不到"。
     */
    internal fun pinnedVersion(text: String, version: Int): Int? =
        runCatching { Encoder.encode(text, EC_LEVEL, hints(version)).version.versionNumber }.getOrNull()

    /**
     * 光栅化。
     *
     * @param sizePx 目标边长；**同一轮的所有帧必须传同一个值**，否则模块边长还是会变。
     * @param version [versionFor] 量出来的整轮统一版本。
     */
    fun render(text: String, sizePx: Int, version: Int): Bitmap? = runCatching {
        val hints = hints(version).apply { this[EncodeHintType.MARGIN] = QUIET_ZONE }
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        matrix.toBitmap()
    }.getOrNull()

    private fun hints(version: Int?): MutableMap<EncodeHintType, Any> {
        val hints = HashMap<EncodeHintType, Any>(3)
        // 固定 ISO-8859-1：帧内容已保证是纯 ASCII（见 AnimatedQrProtocol 类注释），
        // 这样 zxing 不会写 ECI 段，解码端也就不会走 guessEncoding 那条路。
        hints[EncodeHintType.CHARACTER_SET] = "ISO-8859-1"
        if (version != null) hints[EncodeHintType.QR_VERSION] = version
        return hints
    }

    /**
     * `BitMatrix` → `Bitmap`。
     *
     * 逐像素 `setPixel()` 每次都要跨进 Bitmap 的 native 存储，高密度码上会明显拖慢帧率；
     * 先在内存里拼好整块再一次性 `setPixels`。
     */
    private fun BitMatrix.toBitmap(): Bitmap {
        val w = width
        val h = height
        // RGB_565：纯黑白图，用不着 32 位色。动画每帧都要新建位图，省一半内存与带宽。
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                pixels[row + x] = if (get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }
}
