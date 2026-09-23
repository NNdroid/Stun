package app.fjj.stun.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AnimatedQrProtocol] 的纯 JVM 单测。
 *
 * 刻意不使用 Robolectric：本类零 Android 依赖，协议层不碰 Bitmap、不打日志。
 *
 * 这里守的都是**静默失败**型的约束 —— 破了不会崩，只会让接收端拼出错数据、
 * 或者让二维码在某些扫描实现下解出乱码，都属于事后很难查的那种。所以宁可写死。
 */
class AnimatedQrProtocolTest {

    private val protocol = AnimatedQrProtocol

    // ── 帧格式的硬约束 ──

    @Test
    fun `header length matches the documented layout`() {
        // 4(MAGIC) + 8(session) + 4(total) + 8(totalBytes) + 1(degree) + 8(seed) + 8(crc) + 16(sha) = 57
        assertEquals(57, AnimatedQrProtocol.HEADER_LEN)
    }

    @Test
    fun `frame is pure printable ascii`() {
        // ⚠️ 本协议最关键的一条：帧必须是纯可打印 ASCII。
        // 一旦混进非 ASCII/控制字符，zxing 解码时会去猜字符集（UTF-8 → Shift_JIS → ISO-8859-1），
        // 随机二进制很容易被猜成 Shift_JIS，于是同一张码在不同实现下解出不同字符串 —— 且是静默的。
        val payload = ByteArray(3000) { (it * 37 + 11).toByte() }   // 高熵，最容易被猜错的那种
        val session = protocol.split(payload, chunkSize = 512)
        for (i in 0 until session.sequenceLength) {
            val frame = session.frameAt(i)
            assertTrue(
                "第 $i 帧含非 ASCII 字符：${frame.filter { it.code !in 32..126 }}",
                frame.all { it.code in 32..126 },
            )
        }
    }

    @Test
    fun `frame uses only url-safe base64 alphabet`() {
        // URL-safe base64 不该产出 '+' '/'，否则长度字段与偏移假设都还好，但会让"纯 alnum"的
        // 一些下游优化（如字母数字模式）失效。这里把字母表锁死。
        val payload = ByteArray(256) { (it * 251).toByte() }
        val frame = protocol.split(payload, chunkSize = 200).frameAt(0)
        val body = frame.substring(AnimatedQrProtocol.HEADER_LEN)
        assertTrue("载荷段出现非法字符：$body", body.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' })
    }

    // ── 分片 ──

    @Test
    fun `chunk count rounds up`() {
        assertEquals(1, protocol.split(ByteArray(800), chunkSize = 800).totalFrames)
        assertEquals(2, protocol.split(ByteArray(801), chunkSize = 800).totalFrames)
        assertEquals(3, protocol.split(ByteArray(1601), chunkSize = 800).totalFrames)
    }

    @Test
    fun `every frame carries a full-size chunk so xor operands line up`() {
        // XOR 要求操作数等长，所以末片也要补齐 —— 少了这条，接收端算不出末片真实长度，
        // 而末片恰恰是最容易缺、最需要靠修复帧解出来的那一片。
        val session = protocol.split(ByteArray(1700) { it.toByte() }, chunkSize = 800)
        assertEquals(3, session.totalFrames)
        for (i in 0 until session.sequenceLength) {
            val ok = protocol.parse(session.frameAt(i)) as AnimatedQrProtocol.ParseResult.Ok
            assertEquals("第 $i 帧未补齐", 800, ok.frame.payload.size)
        }
    }

    @Test
    fun `empty payload still yields exactly one frame`() {
        val session = protocol.split(ByteArray(0), chunkSize = 800)
        assertEquals(1, session.totalFrames)
        val parsed = protocol.parse(session.frameAt(0)) as AnimatedQrProtocol.ParseResult.Ok
        // 帧体补齐成 800 字节了，真实长度只能从帧头的 totalBytes 读
        assertEquals(0, parsed.frame.totalBytes)
    }

    @Test
    fun `oversized payload is rejected instead of silently truncated`() {
        // 4 位十进制帧号的上限是 9999；超了必须炸，不能悄悄丢掉尾巴
        val payload = ByteArray(AnimatedQrProtocol.MAX_FRAMES * 800 + 1)
        assertThrows(IllegalArgumentException::class.java) { protocol.split(payload, chunkSize = 800) }
    }

    @Test
    fun `frame index out of range is rejected`() {
        val session = protocol.split(ByteArray(100), chunkSize = 50)
        // 序列含修复帧，所以上界是 sequenceLength 而不是 totalFrames
        assertThrows(IllegalArgumentException::class.java) { session.frameAt(session.sequenceLength) }
        assertThrows(IllegalArgumentException::class.java) { session.frameAt(-1) }
    }

    // ── 修复帧 ──

    @Test
    fun `play sequence carries repair frames on top of source frames`() {
        val session = protocol.split(ByteArray(4000) { it.toByte() }, chunkSize = 800)
        assertEquals(5, session.totalFrames)
        assertTrue(
            "序列必须比源分片数长（掺了修复帧），实际 ${session.sequenceLength}",
            session.sequenceLength > session.totalFrames,
        )
        // 前 N 个是度 1 的原始帧，之后才是修复帧
        for (i in 0 until session.totalFrames) {
            assertEquals(1, (protocol.parse(session.frameAt(i)) as AnimatedQrProtocol.ParseResult.Ok).frame.degree)
        }
        // 分片数少于 REPAIR_DEGREE 时度会被夹住，帧头写的必须是夹过的实际值
        val expectedDegree = minOf(AnimatedQrProtocol.REPAIR_DEGREE, session.totalFrames)
        for (i in session.totalFrames until session.sequenceLength) {
            val degree = (protocol.parse(session.frameAt(i)) as AnimatedQrProtocol.ParseResult.Ok).frame.degree
            assertEquals(expectedDegree, degree)
        }
    }

    @Test
    fun `a repair frame really is the xor of the source chunks it names`() {
        // 这条是收发两端"索引派生"一致性的唯一护栏：两边只要有一边算错，
        // 解出来的字节就是错的（最终会被整份 SHA-256 挡下，但那一轮白扫了）。
        val payload = ByteArray(3200) { (it % 251).toByte() }
        val session = protocol.split(payload, chunkSize = 800)
        val k = session.totalFrames      // 第一个修复帧
        val frame = protocol.parse(session.frameAt(k)) as AnimatedQrProtocol.ParseResult.Ok
        val indices = protocol.indicesFor(frame.frame.seed, frame.frame.degree, session.totalFrames)

        assertEquals(frame.frame.degree, indices.size)
        assertTrue("索引必须互不重复：${indices.toList()}", indices.toSet().size == indices.size)

        val expected = ByteArray(800)
        for (i in indices) {
            val from = i * 800
            for (j in 0 until 800) {
                val b = if (from + j < payload.size) payload[from + j] else 0.toByte()
                expected[j] = (expected[j].toInt() xor b.toInt()).toByte()
            }
        }
        assertTrue("修复帧内容与按索引异或的结果不一致", expected.contentEquals(frame.frame.payload))
    }

    @Test
    fun `index derivation is deterministic and clamped`() {
        val n = 40
        val a = protocol.indicesFor(12345, 6, n)
        val b = protocol.indicesFor(12345, 6, n)
        assertTrue(a.contentEquals(b))
        assertEquals(6, a.toSet().size)
        assertTrue(a.all { it in 0 until n })

        // 度超过总分片数时必须夹住，而不是死循环或越界
        assertTrue(protocol.indicesFor(7, 6, 3).toSet() == setOf(0, 1, 2))
        assertEquals(1, protocol.indicesFor(9, 1, 40).size)
    }

    @Test
    fun `same session id reproduces the same repair seeds`() {
        val payload = ByteArray(2400) { it.toByte() }
        val a = protocol.split(payload, chunkSize = 800, sessionId = 4242)
        val b = protocol.split(payload, chunkSize = 800, sessionId = 4242)
        assertEquals(a.frameAt(a.totalFrames), b.frameAt(b.totalFrames))
    }

    // ── 编解码往返 ──

    @Test
    fun `round trip preserves bytes across chunk boundaries`() {
        val payload = ByteArray(2500) { (it % 256).toByte() }
        val session = protocol.split(payload, chunkSize = 800)
        val joined = java.io.ByteArrayOutputStream()
        for (i in 0 until session.totalFrames) {
            val ok = protocol.parse(session.frameAt(i)) as AnimatedQrProtocol.ParseResult.Ok
            assertEquals(1, ok.frame.degree)
            assertEquals(i, ok.frame.seed)
            assertEquals(session.totalFrames, ok.frame.totalFrames)
            assertEquals(session.sessionId, ok.frame.sessionId)
            assertEquals(session.sha256Head, ok.frame.sha256Head)
            joined.write(ok.frame.payload)
        }
        // 分片补齐过，按帧头的 totalBytes 裁回真实长度
        val trimmed = joined.toByteArray().copyOf(session.totalBytes)
        assertTrue("往返后字节不一致", payload.contentEquals(trimmed))
    }

    @Test
    fun `round trip handles utf8 payload`() {
        val text = "节点名称：东京机房 🇯🇵 / 密码 p@ss,w=1 — 换行\n结束"
        val payload = text.toByteArray(Charsets.UTF_8)
        val session = protocol.split(payload, chunkSize = 7)      // 小分片，专挑多字节字符被切开的情况
        val joined = java.io.ByteArrayOutputStream()
        for (i in 0 until session.totalFrames) {
            joined.write((protocol.parse(session.frameAt(i)) as AnimatedQrProtocol.ParseResult.Ok).frame.payload)
        }
        assertEquals(text, String(joined.toByteArray().copyOf(session.totalBytes), Charsets.UTF_8))
    }

    @Test
    fun `sha head depends on whole payload only not on chunk size`() {
        val payload = ByteArray(1000) { it.toByte() }
        assertEquals(
            protocol.split(payload, chunkSize = 100).sha256Head,
            protocol.split(payload, chunkSize = 999).sha256Head,
        )
        assertNotEquals(
            protocol.split(payload, chunkSize = 100).sha256Head,
            protocol.split(ByteArray(1000) { (it + 1).toByte() }, chunkSize = 100).sha256Head,
        )
    }

    @Test
    fun `sha head is 16 lowercase hex chars`() {
        val head = protocol.sha256Head("hello".toByteArray())
        assertEquals(16, head.length)
        assertTrue(head.all { it in '0'..'9' || it in 'a'..'f' })
    }

    // ── 解析失败的分流 ──

    @Test
    fun `plain qr contents are reported as not ours so callers can fall back`() {
        // 用户扫到普通分享码时必须能回退到单码导入，而不是报错
        assertEquals(
            AnimatedQrProtocol.ParseResult.NotAnimatedFrame,
            protocol.parse("eyJ2IjoxLCJnIjoxLCJpdCI6MTAwMDB9"),
        )
        assertEquals(AnimatedQrProtocol.ParseResult.NotAnimatedFrame, protocol.parse("https://example.com"))
        assertEquals(null, (protocol.parse("SQR") as? AnimatedQrProtocol.ParseResult.Corrupt)?.reason)
    }

    @Test
    fun `truncated frame is detected`() {
        val session = protocol.split(ByteArray(100), chunkSize = 50)
        val cut = session.frameAt(0).substring(0, AnimatedQrProtocol.HEADER_LEN - 1)
        assertEquals(
            AnimatedQrProtocol.FrameError.TRUNCATED,
            (protocol.parse(cut) as AnimatedQrProtocol.ParseResult.Corrupt).reason,
        )
    }

    @Test
    fun `bad field characters are detected`() {
        val session = protocol.split(ByteArray(100), chunkSize = 50)
        val good = session.frameAt(0)
        // 把 total 字段（偏移 12，长 4）里的第一个字符换成一个非数字
        val bad = good.substring(0, 12) + "x" + good.substring(13)
        assertEquals(
            AnimatedQrProtocol.FrameError.BAD_FIELD,
            (protocol.parse(bad) as AnimatedQrProtocol.ParseResult.Corrupt).reason,
        )
    }

    @Test
    fun `degree outside 1-9 is detected`() {
        val session = protocol.split(ByteArray(100), chunkSize = 50)
        val good = session.frameAt(0)
        // degree 字段偏移 24，合法值 1..9；改成 0 必须被拒
        val bad = good.substring(0, 24) + "0" + good.substring(25)
        assertEquals(
            AnimatedQrProtocol.FrameError.BAD_FIELD,
            (protocol.parse(bad) as AnimatedQrProtocol.ParseResult.Corrupt).reason,
        )
    }

    @Test
    fun `crc catches a corrupted payload`() {
        val session = protocol.split("sensitive-node-json".toByteArray(), chunkSize = 100)
        val good = session.frameAt(0)
        val head = good.substring(0, AnimatedQrProtocol.HEADER_LEN)
        val body = good.substring(AnimatedQrProtocol.HEADER_LEN)
        // ⚠️ 必须改**载荷段第一个字符**：base64 末位字符的低位是未使用的填充位，
        // 改它解码出来的字节可能一模一样，CRC 自然也对得上 —— 那种写法测不出东西。
        val swapped = if (body.first() == 'A') 'B' else 'A'
        val bad = head + swapped + body.drop(1)
        assertEquals(
            AnimatedQrProtocol.FrameError.CRC_MISMATCH,
            (protocol.parse(bad) as AnimatedQrProtocol.ParseResult.Corrupt).reason,
        )
    }

    @Test
    fun `crc does not cover the sha head so a tampered head still parses`() {
        // 这是**有意为之**：逐帧 CRC 只保护本帧载荷。篡改 sha 头不会让单帧变"坏"，
        // 必须靠收齐后的整体校验抓 —— 两个校验分工不同，见类注释。
        val session = protocol.split("node".toByteArray(), chunkSize = 100)
        val good = session.frameAt(0)
        val tampered = good.substring(0, 41) + "0123456789abcdef" + good.substring(57)
        val parsed = protocol.parse(tampered) as AnimatedQrProtocol.ParseResult.Ok
        assertEquals("0123456789abcdef", parsed.frame.sha256Head)
        assertEquals("node", String(parsed.frame.payload.copyOf(parsed.frame.totalBytes)))
    }

    @Test
    fun `session ids differ between splits`() {
        val a = protocol.split(ByteArray(10), chunkSize = 10)
        val b = protocol.split(ByteArray(10), chunkSize = 10)
        // 随机会话号：极小概率相同，若这条偶发失败说明随机源坏了
        assertNotEquals(a.sessionId, b.sessionId)
    }

    @Test
    fun `is animated frame is a cheap prefix check`() {
        assertTrue(protocol.isAnimatedFrame("SQR2" + "0".repeat(40)))
        assertTrue(!protocol.isAnimatedFrame("sqr2" + "0".repeat(40)))
        // v1 的码必须被当成"不是我们的帧"，好让老版本走单码导入路径
        assertTrue(!protocol.isAnimatedFrame("SQR1" + "0".repeat(40)))
    }
}
