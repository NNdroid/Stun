package app.fjj.stun.qr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.zip.CRC32

/**
 * [AnimatedQrAssembler] 的纯 JVM 单测 —— 它守的是"摄像头前面那个不合作的世界"。
 *
 * 刻意不使用 Robolectric：本类零 Android 依赖。
 *
 * 覆盖的真实场景：手抖丢帧、一帧停太久被扫两遍、先扫到后面的帧、扫花（CRC 不符）、
 * 用户把摄像头挪到另一个码上（换会话）、以及"收齐了但拼出来的东西不对"（整体 SHA 校验）。
 */
class AnimatedQrAssemblerTest {

    // ── 独立按协议文档拼帧（刻意不复用生产代码的编码器，两边对不上就该红）──

    private fun buildFrame(
        sessionId: Int,
        total: Int,
        index: Int,
        shaHead: String,
        payload: ByteArray,
        totalBytes: Int,
        degree: Int = 1,
        seed: Int = index,
    ): String {
        val body = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        val crc = CRC32().apply { update(payload) }.value
        return AnimatedQrProtocol.MAGIC +
            "%08x".format(sessionId) + "%04d".format(total) + "%08x".format(totalBytes) +
            "%01d".format(degree) + "%08x".format(seed) +
            "%08x".format(crc) + shaHead + body
    }

    /**
     * 按 chunkSize 切分出一整套帧（正常发送端应当产出的样子）。
     * 分片**补齐到等长**（末尾补 0）—— 生产侧为了 XOR 也这么做，这里必须一致，
     * 真实长度由帧头的 totalBytes 带过去。
     */
    private fun framesOf(payload: ByteArray, chunkSize: Int = 800, sessionId: Int = 42): List<ByteArray> {
        val n = if (payload.isEmpty()) 1 else (payload.size + chunkSize - 1) / chunkSize
        val chunks = ArrayList<ByteArray>(n)
        for (i in 0 until n) {
            val c = ByteArray(chunkSize)
            val from = i * chunkSize
            if (from < payload.size) payload.copyInto(c, 0, from, minOf(from + chunkSize, payload.size))
            chunks += c
        }
        val sha = AnimatedQrProtocol.sha256Head(payload)
        return chunks.mapIndexed { i, c -> buildFrame(sessionId, n, i, sha, c, payload.size).toByteArray() }
    }

    private fun text(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1)

    private fun payloadOf(size: Int) = ByteArray(size) { (it * 31 + 7).toByte() }

    // ── 正常收齐 ──

    @Test
    fun `collects in order`() {
        val payload = payloadOf(2500)
        val assembler = AnimatedQrAssembler()
        var done: ByteArray? = null
        for (f in framesOf(payload)) {
            val offer = assembler.offer(text(f))
            if (offer is AnimatedQrAssembler.Offer.Completed) done = offer.payload
        }
        assertArrayEquals(payload, done)
    }

    @Test
    fun `collects out of order`() {
        val payload = payloadOf(2500)
        val frames = framesOf(payload).reversed()          // 先扫到后面的帧
        val assembler = AnimatedQrAssembler()
        var done: ByteArray? = null
        for (f in frames) {
            val offer = assembler.offer(text(f))
            if (offer is AnimatedQrAssembler.Offer.Completed) done = offer.payload
        }
        assertArrayEquals(payload, done)
    }

    @Test
    fun `single frame payload completes immediately`() {
        val payload = payloadOf(120)
        val assembler = AnimatedQrAssembler()
        val offer = assembler.offer(text(framesOf(payload).first()))
        assertTrue(offer is AnimatedQrAssembler.Offer.Completed)
        assertArrayEquals(payload, (offer as AnimatedQrAssembler.Offer.Completed).payload)
    }

    @Test
    fun `empty payload completes with empty result`() {
        val assembler = AnimatedQrAssembler()
        val offer = assembler.offer(text(framesOf(ByteArray(0)).first()))
        assertTrue(offer is AnimatedQrAssembler.Offer.Completed)
        assertEquals(0, (offer as AnimatedQrAssembler.Offer.Completed).payload.size)
    }

    // ── 重复帧 ──

    @Test
    fun `duplicate frames are ignored and counted`() {
        val payload = payloadOf(1000)
        val frames = framesOf(payload)
        val assembler = AnimatedQrAssembler()
        // 第一帧停留过久：连扫三次
        assertEquals(
            AnimatedQrAssembler.Offer.Accepted(1, 2, duplicate = false, newSession = true),
            assembler.offer(text(frames[0])),
        )
        assertEquals(
            AnimatedQrAssembler.Offer.Accepted(1, 2, duplicate = true, newSession = false),
            assembler.offer(text(frames[0])),
        )
        assembler.offer(text(frames[0]))
        assertEquals(1, assembler.snapshot()!!.received)
        assertEquals(2, assembler.snapshot()!!.duplicateFrames)

        val done = assembler.offer(text(frames[1]))
        assertTrue(done is AnimatedQrAssembler.Offer.Completed)
        assertArrayEquals(payload, (done as AnimatedQrAssembler.Offer.Completed).payload)
    }

    // ── 丢帧 / 坏帧：不阻塞，等下一轮 ──

    @Test
    fun `missing frame stalls progress until the loop replays it`() {
        val payload = payloadOf(2500)                     // 4 帧
        val frames = framesOf(payload)
        val assembler = AnimatedQrAssembler()
        // 第一轮丢掉第 2 帧（index=1）
        for (i in intArrayOf(0, 2, 3)) assembler.offer(text(frames[i]))
        val stalled = assembler.snapshot()!!
        assertEquals(3, stalled.received)
        assertEquals(4, stalled.totalFrames)
        assertEquals(75, stalled.percent)

        // 第二轮补上
        val done = assembler.offer(text(frames[1]))
        assertTrue(done is AnimatedQrAssembler.Offer.Completed)
        assertArrayEquals(payload, (done as AnimatedQrAssembler.Offer.Completed).payload)
    }

    @Test
    fun `corrupt frame is counted and does not occupy a slot`() {
        val payload = payloadOf(1000)
        val frames = framesOf(payload)
        val assembler = AnimatedQrAssembler()
        assembler.offer(text(frames[0]))
        // 改载荷段第一个字符（不能改末位：base64 末位的低位是填充位，改了字节可能不变）
        val raw = text(frames[1])
        val at = AnimatedQrProtocol.HEADER_LEN
        val corrupt = raw.substring(0, at) + (if (raw[at] == 'A') 'B' else 'A') + raw.substring(at + 1)
        val offer = assembler.offer(corrupt)
        assertEquals(
            AnimatedQrAssembler.Offer.Corrupt(AnimatedQrProtocol.FrameError.CRC_MISMATCH),
            offer,
        )
        assertEquals(1, assembler.snapshot()!!.received)
        assertEquals(1, assembler.snapshot()!!.corruptFrames)
    }

    // ── 换码 / 会话切换 ──

    @Test
    fun `a different session resets the progress`() {
        val a = framesOf(payloadOf(2500), sessionId = 1)
        val b = framesOf(payloadOf(1000), sessionId = 2)
        val assembler = AnimatedQrAssembler()
        assembler.offer(text(a[0]))
        assertEquals(1, assembler.snapshot()!!.received)

        val offer = assembler.offer(text(b[0]))
        assertTrue(offer is AnimatedQrAssembler.Offer.Accepted)
        assertEquals(true, (offer as AnimatedQrAssembler.Offer.Accepted).newSession)
        assertEquals(1, assembler.snapshot()!!.received)
        assertEquals(2, assembler.snapshot()!!.totalFrames)
    }

    @Test
    fun `same session id but different target fingerprint starts over`() {
        val a = framesOf(payloadOf(2500), sessionId = 9)
        val b = framesOf(payloadOf(1600), sessionId = 9)   // 同样的会话号，但载荷不同 ⇒ sha 头不同
        val assembler = AnimatedQrAssembler()
        assembler.offer(text(a[0]))
        assembler.offer(text(a[1]))
        assertEquals(2, assembler.snapshot()!!.received)

        val offer = assembler.offer(text(b[0]))
        assertEquals(true, (offer as AnimatedQrAssembler.Offer.Accepted).newSession)
        assertEquals(1, assembler.snapshot()!!.received)
    }

    @Test
    fun `plain qr is passed through so callers can fall back`() {
        val assembler = AnimatedQrAssembler()
        assertEquals(
            AnimatedQrAssembler.Offer.NotAnimatedFrame,
            assembler.offer("eyJ2IjoxLCJnIjoxfQ"),
        )
        assertNull(assembler.snapshot())
    }

    // ── 整体校验与安全边界 ──

    @Test
    fun `whole payload checksum failure rejects everything and resets`() {
        // 每帧 CRC 都对（改动的是 sha 头，不进 CRC），所以只有收齐后的整体校验能抓到
        val payload = payloadOf(1000)
        val bogus = "0123456789abcdef"
        val assembler = AnimatedQrAssembler()
        val chunks = listOf(payload.copyOfRange(0, 500), payload.copyOfRange(500, 1000))
        assembler.offer(text(buildFrame(5, 2, 0, bogus, chunks[0], payload.size).toByteArray()))
        val offer = assembler.offer(text(buildFrame(5, 2, 1, bogus, chunks[1], payload.size).toByteArray()))

        assertEquals(
            AnimatedQrAssembler.RejectReason.CHECKSUM_MISMATCH,
            (offer as AnimatedQrAssembler.Offer.Rejected).reason,
        )
        assertNull("校验失败后必须重置，否则用户重扫会拼上脏数据", assembler.snapshot())
    }

    @Test
    fun `conflicting content in the same slot is rejected`() {
        val payload = payloadOf(1000)
        val sha = AnimatedQrProtocol.sha256Head(payload)
        val assembler = AnimatedQrAssembler()
        assembler.offer(text(buildFrame(7, 2, 0, sha, payload.copyOfRange(0, 500), payload.size).toByteArray()))
        val offer = assembler.offer(
            text(buildFrame(7, 2, 0, sha, payload.copyOfRange(500, 1000), payload.size).toByteArray()),
        )
        assertEquals(
            AnimatedQrAssembler.RejectReason.CHECKSUM_MISMATCH,
            (offer as AnimatedQrAssembler.Offer.Rejected).reason,
        )
        assertNull(assembler.snapshot())
    }

    @Test
    fun `payload above the size cap is rejected`() {
        // 刻意让"声明的 totalBytes"还在额度内、但**补齐后的累计**超限（900 → 2×500=1000）：
        // 这样测的是逐片累计那条路径，而不是"声明体积本身超标"的提前拒。
        val assembler = AnimatedQrAssembler(maxTotalBytes = 950)
        val payload = payloadOf(900)
        val frames = framesOf(payload, chunkSize = 500)
        assembler.offer(text(frames[0]))
        val offer = assembler.offer(text(frames[1]))
        assertEquals(
            AnimatedQrAssembler.RejectReason.PAYLOAD_TOO_LARGE,
            (offer as AnimatedQrAssembler.Offer.Rejected).reason,
        )
        assertNull(assembler.snapshot())
    }

    // ── 进度 ──

    @Test
    fun `progress reports received total missing and percent`() {
        val assembler = AnimatedQrAssembler()
        val frames = framesOf(payloadOf(2500))            // 4 帧
        assembler.offer(text(frames[0]))
        val s = assembler.snapshot()!!
        assertEquals(1, s.received)
        assertEquals(4, s.totalFrames)
        assertEquals(3, s.missing)
        assertEquals(25, s.percent)
        assertTrue(s.isActive)
    }

    @Test
    fun `stale session can be cleared`() {
        val assembler = AnimatedQrAssembler()
        assembler.offer(text(framesOf(payloadOf(1600)).first()))
        val timeout = 3000L
        val now = System.currentTimeMillis()
        assertFalse("刚喂过帧不该被清", assembler.resetIfStale(now, timeout))
        assertTrue("超时后应清掉", assembler.resetIfStale(now + timeout + 1000, timeout))
        assertNull(assembler.snapshot())
    }

    @Test
    fun `reset drops progress`() {
        val assembler = AnimatedQrAssembler()
        assembler.offer(text(framesOf(payloadOf(1600)).first()))
        assembler.reset()
        assertNull(assembler.snapshot())
        assertFalse(assembler.resetIfStale(System.currentTimeMillis() + 99_999, 1))
    }
}
