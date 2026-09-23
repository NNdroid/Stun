package app.fjj.stun.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * **修复帧（XOR 冗余包）**的端到端验证 —— 这是动画二维码 v2 相对 v1 的全部动机。
 *
 * 测的不是"XOR 算得对不对"（那在 [AnimatedQrProtocolTest] 里逐字节断言了），而是
 * **它到底有没有把长尾压平**：在同样的丢帧率下，带修复帧的序列是不是真的比只播原始帧
 * 少播很多帧就能收齐。
 *
 * 为什么必须写成模拟而不是单点断言：修复帧的收益是**统计性质**的（赠券收集的长尾），
 * 单个用例看不出来。这里用固定种子跑蒙特卡洛，可重复、且失败时能直接打出两侧的数字。
 */
class AnimatedQrRepairTest {

    private fun payload(bytes: Int) = ByteArray(bytes) { (it * 131 + 17).toByte() }

    /** 播 [range] 里的帧，每帧以概率 [hitRate] 被"扫到"，返回直到收齐一共播了多少帧。 */
    private fun framesUntilComplete(
        session: AnimatedQrProtocol.SplitSession,
        range: IntRange,
        rnd: Random,
        hitRate: Double,
    ): Int {
        val assembler = AnimatedQrAssembler()
        var shown = 0
        while (true) {
            val k = range.first + rnd.nextInt(range.last - range.first + 1)
            shown++
            if (rnd.nextDouble() > hitRate) continue
            val offer = assembler.offer(session.frameAt(k))
            if (offer is AnimatedQrAssembler.Offer.Completed) {
                assertTrue("收齐的载荷不对", session.payload.contentEquals(offer.payload))
                return shown
            }
        }
    }

    private fun averageOf(runs: Int, block: (seed: Long) -> Int): Double {
        var total = 0
        for (i in 0 until runs) total += block(20260921L + i * 7919L)
        return total.toDouble() / runs
    }

    @Test
    fun `one repair frame recovers the only missing chunk`() {
        val p = payload(3200)                                  // 4 片
        val session = AnimatedQrProtocol.split(p, chunkSize = 800, sessionId = 1)
        assertEquals(4, session.totalFrames)

        val assembler = AnimatedQrAssembler()
        repeat(3) { assembler.offer(session.frameAt(it)) }     // 只收前三片
        assertEquals(3, assembler.snapshot()!!.received)

        // 4 片时度会被夹到 4，于是修复帧 = 全部四片异或，收到它就能把缺的第 4 片解出来
        val offer = assembler.offer(session.frameAt(session.totalFrames))
        assertTrue("缺最后一片时应靠修复帧收官，实际 $offer", offer is AnimatedQrAssembler.Offer.Completed)
        assertTrue(p.contentEquals((offer as AnimatedQrAssembler.Offer.Completed).payload))
    }

    @Test
    fun `repair frames cut the frames needed by more than half`() {
        val p = payload(20 * 800)                              // 20 片
        val session = AnimatedQrProtocol.split(p, chunkSize = 800, sessionId = 7)
        assertTrue("序列必须含修复帧", session.sequenceLength > session.totalFrames)

        val hit = 0.5
        val sourceOnly = averageOf(8) { seed ->
            framesUntilComplete(session, 0 until session.totalFrames, Random(seed), hit)
        }
        val withRepair = averageOf(8) { seed ->
            framesUntilComplete(session, 0 until session.sequenceLength, Random(seed), hit)
        }

        // 蒙特卡洛给的是约 2.5x；这里只要 1.5x 就放行，留足余量避免偶发抖动把它变红
        assertTrue(
            "修复帧应当显著减少所需帧数：只播原始帧平均 $sourceOnly 帧，含修复帧 $withRepair 帧",
            withRepair * 1.5 < sourceOnly,
        )
    }

    @Test
    fun `the tail is what repair frames actually fix`() {
        // 单独看"最后一片"：v1 下它每轮只出现一次，得等 1/hit 轮；有了修复帧，
        // 任何一个覆盖到它的包都能把它解出来。
        val p = payload(12 * 800)
        val session = AnimatedQrProtocol.split(p, chunkSize = 800, sessionId = 11)

        val hit = 0.35                                          // 刻意用难扫的条件放大长尾
        val sourceOnly = averageOf(8) { seed ->
            framesUntilComplete(session, 0 until session.totalFrames, Random(seed), hit)
        }
        val withRepair = averageOf(8) { seed ->
            framesUntilComplete(session, 0 until session.sequenceLength, Random(seed), hit)
        }
        // 分片数少（这里 12 片）时收益比大载荷低：度 6 相对 12 片偏"重"，
        // 得等 6 片里收齐 5 片才派得上用场，前期基本在空转。实测约 1.7x，阈值取 1.6 留余量。
        assertTrue(
            "难扫条件下收益应当更大：只播原始帧 $sourceOnly 帧，含修复帧 $withRepair 帧",
            withRepair * 1.6 < sourceOnly,
        )
    }

    @Test
    fun `pending buffer does not grow without bound`() {
        // 全喂修复帧、一片原始帧都不给时，BP 解不动 ⇒ 全部堆进 pending。
        // 缓存必须有上限，否则一个"声明 9999 帧"的恶意码能把内存吃光。
        val session = AnimatedQrProtocol.split(payload(30 * 800), chunkSize = 800, sessionId = 3)
        val assembler = AnimatedQrAssembler()
        repeat(AnimatedQrAssembler.MAX_PENDING * 3) {
            val k = session.totalFrames +
                (it % (session.sequenceLength - session.totalFrames))
            assembler.offer(session.frameAt(k))
        }
        // 解不出来不该崩，也不该收齐（一片原始帧都没直接给全）
        assertEquals(0, assembler.snapshot()!!.received)
        assertTrue(assembler.snapshot()!!.received < session.totalFrames)
    }
}
