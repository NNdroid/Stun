package app.fjj.stun.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * 只验证 [QrFrameRenderer] 里**不碰 Bitmap** 的那一半：版本测量与钉版本。
 *
 * 裸 JUnit：`core` 模块没配默认 SDK，挂 RobolectricTestRunner 会直接抛
 * `IllegalArgumentException`。真光栅化留给 app 侧的预览/真机验证。
 */
class QrFrameRendererTest {

    private fun frameText(payloadBytes: Int, chunkSize: Int = payloadBytes.coerceAtLeast(1)): String {
        val payload = ByteArray(payloadBytes).also { Random(payloadBytes.toLong()).nextBytes(it) }
        return AnimatedQrProtocol.split(payload, chunkSize = chunkSize).frameAt(0)
    }

    @Test
    fun `full frame lands in a sane version range`() {
        val v = QrFrameRenderer.versionFor(frameText(800))
        // 800B → base64url 1068 字符 + 44 头 ≈ 1112 字节；version 20~26 是预期区间（L 级）
        assertTrue("unexpected version $v", v in 20..28)
    }

    @Test
    fun `short frame naturally needs a much smaller version`() {
        // 这条正是"必须钉版本"的理由：不钉的话每轮几何都在跳
        val full = QrFrameRenderer.versionFor(frameText(800))
        val short = QrFrameRenderer.versionFor(frameText(80))
        assertTrue("short($short) should be well below full($full)", short < full - 6)
    }

    @Test
    fun `pinning forces every frame to the session version`() {
        val session = AnimatedQrProtocol.split(
            ByteArray(800 * 3 + 40).also { Random(7L).nextBytes(it) },
            chunkSize = 800,
        )
        val pinned = QrFrameRenderer.versionFor(session.frameAt(0))
        for (i in 0 until session.totalFrames) {
            assertEquals(
                "frame $i not pinned",
                pinned,
                QrFrameRenderer.pinnedVersion(session.frameAt(i), pinned),
            )
        }
    }

    @Test
    fun `pinning below what the content needs is refused rather than silently downgraded`() {
        // zxing 若忽略 QR_VERSION 会返回自然版本，这条就会失败 —— 也就等于替我们确认了钉版本真的生效
        assertNull(QrFrameRenderer.pinnedVersion(frameText(800), 1))
    }

    @Test
    fun `version measurement is stable across repeats`() {
        val text = frameText(500, chunkSize = 500)
        assertNotNull(QrFrameRenderer.versionFor(text))
        assertEquals(QrFrameRenderer.versionFor(text), QrFrameRenderer.versionFor(text))
    }

    /**
     * 把"单张二维码装得下多少字节"这个分界点钉死在 [AnimatedQrProtocol.SINGLE_QR_MAX_BYTES] 上。
     *
     * 为什么必须实测而不是照抄资料：这个值决定发送端**什么时候切动画模式**，
     * 而动画模式要求对方也是新版本 App。取小了会把本来塞得下的载荷白白升级（老版本收不了），
     * 取大了就直接生成失败。用 `pinnedVersion(text, 40)` 当容量探针 —— 强制 version 40，
     * 装不下时返回 null，正好等价于"version 40 还放得下吗"。
     */
    @Test
    fun `single QR byte capacity boundary matches zxing`() {
        val atLimit = byteModePayload(AnimatedQrProtocol.SINGLE_QR_MAX_BYTES)
        val overLimit = byteModePayload(AnimatedQrProtocol.SINGLE_QR_MAX_BYTES + 1)

        assertEquals(
            "${AnimatedQrProtocol.SINGLE_QR_MAX_BYTES} bytes should fit in a version-40 L QR",
            40,
            QrFrameRenderer.pinnedVersion(atLimit, 40),
        )
        assertNull(
            "${AnimatedQrProtocol.SINGLE_QR_MAX_BYTES + 1} bytes should NOT fit",
            QrFrameRenderer.pinnedVersion(overLimit, 40),
        )
    }

    /**
     * 造一段**必然走 byte 模式**的载荷。base64url 字母表含小写字母，
     * 而 QR 的 alphanumeric 模式不收小写 ⇒ zxing 只能选 byte 模式，
     * 这样测出来的才是 byte 模式容量。
     */
    private fun byteModePayload(length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
        val random = Random(length.toLong())
        return buildString(length) {
            repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }
}
