package app.fjj.stun.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [StarfieldResampler] 的纯数学单测。
 *
 * 核心判据是**点光源不丢**：这是它与 `BitmapFactory.inSampleSize`（盒式平均）的全部区别，
 * 也是"星空看不出是星空"的根因所在。
 */
class StarfieldResamplerTest {

    private fun px(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun red(c: Int) = (c ushr 16) and 0xFF
    private fun green(c: Int) = (c ushr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF

    /**
     * 一颗 1px 的星在 2×2 块里必须**原样保留**。
     *
     * 盒式平均会把它压成 1/4（实测 lum 8 → 2，等于消失）；取最大值则原样留下。
     * 为了排除增益的干扰，这条用 `gain = 1`。
     */
    @Test
    fun 一像素的星点不被平均值抹掉() {
        val sw = 4
        val sh = 2
        val src = IntArray(sw * sh)
        // 只在 (row=1, col=1) 放一颗星，其余全黑。
        src[1 * sw + 1] = px(40, 60, 80)
        val out = IntArray(2 * 1)

        StarfieldResampler.downsampleBrightest(src, sw, sh, 2, 1, out, gain = 1f)

        assertEquals("星点被抹掉了（盒式平均才会这样）", 40, red(out[0]))
        assertEquals(60, green(out[0]))
        assertEquals(80, blue(out[0]))
        assertEquals("纯黑块必须保持纯黑", 0, red(out[1]))
    }

    /** 逐通道取最大：块内不同通道的最亮值可以来自不同像素。 */
    @Test
    fun 逐通道取最大值() {
        val src = intArrayOf(px(200, 0, 0), px(0, 150, 0), px(0, 0, 100), px(0, 0, 0))
        val out = IntArray(1)
        StarfieldResampler.downsampleBrightest(src, 2, 2, 1, 1, out, gain = 1f)
        assertEquals(200, red(out[0]))
        assertEquals(150, green(out[0]))
        assertEquals(100, blue(out[0]))
    }

    /** 增益生效，并在 255 处夹住；纯黑乘任何增益仍是纯黑（不会泛灰）。 */
    @Test
    fun 增益抬亮并夹到255() {
        val src = intArrayOf(px(0, 0, 0), px(10, 20, 30), px(250, 250, 250), px(0, 0, 0))
        val out = IntArray(1)
        StarfieldResampler.downsampleBrightest(src, 2, 2, 1, 1, out, gain = 2f)
        // 块内逐通道最大 = (250,250,250) ⇒ ×2 夹到 255。
        assertEquals(255, red(out[0]))
        assertEquals(255, green(out[0]))
        assertEquals(255, blue(out[0]))

        val src2 = intArrayOf(px(10, 20, 30), px(0, 0, 0), px(0, 0, 0), px(0, 0, 0))
        val out2 = IntArray(1)
        StarfieldResampler.downsampleBrightest(src2, 2, 2, 1, 1, out2, gain = 2f)
        assertEquals("(10,20,30) × 2 应为 (20,40,60)", 20, red(out2[0]))
        assertEquals(40, green(out2[0]))
        assertEquals(60, blue(out2[0]))
    }

    /** 增益 <= 1 时不改变数值（除降采样本身）。 */
    @Test
    fun 增益不大于1时等同纯最大值降采样() {
        val src = intArrayOf(px(7, 8, 9), px(0, 0, 0), px(0, 0, 0), px(0, 0, 0))
        val out = IntArray(1)
        StarfieldResampler.downsampleBrightest(src, 2, 2, 1, 1, out, gain = 1f)
        assertEquals(7, red(out[0]))
        assertEquals(8, green(out[0]))
        assertEquals(9, blue(out[0]))
    }

    /**
     * **每个源像素都必须落到至少一个目标块里** —— 目标尺寸不能整除源尺寸时最容易漏掉
     * 边角（`sx1` 少算一格就会整条边消失）。
     */
    @Test
    fun 非整除缩放时每个源像素都被覆盖() {
        val sw = 5
        val sh = 3
        val dw = 3
        val dh = 2
        for (sy in 0 until sh) {
            for (sx in 0 until sw) {
                val src = IntArray(sw * sh)
                src[sy * sw + sx] = px(90, 90, 90)
                val out = IntArray(dw * dh)
                StarfieldResampler.downsampleBrightest(src, sw, sh, dw, dh, out, gain = 1f)
                assertTrue(
                    "源像素 ($sx,$sy) 在降采样后消失了 —— 目标块划分漏了它",
                    out.any { red(it) == 90 },
                )
            }
        }
    }

    /** 输出恒不透明（星野源图是不透明 RGB）。 */
    @Test
    fun 输出恒不透明() {
        val src = IntArray(4) { px(0, 0, 0) }
        val out = IntArray(1)
        StarfieldResampler.downsampleBrightest(src, 2, 2, 1, 1, out)
        assertEquals(255, (out[0] ushr 24) and 0xFF)
    }

    /** 这是降采样器：放大请求必须直接报错，而不是悄悄产出模糊的大图。 */
    @Test
    fun 放大请求被拒绝() {
        try {
            StarfieldResampler.downsampleBrightest(IntArray(4), 2, 2, 4, 4, IntArray(16))
            fail("放大请求应当抛 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // 期望
        }
    }

    /** 真实尺寸基线：2048×1024 → 1024×512 的耗时（只打印，不设死线）。 */
    @Test
    fun 真实尺寸耗时基线打印() {
        val src = IntArray(2048 * 1024) { if (it % 9973 == 0) px(60, 60, 60) else 0 }
        val out = IntArray(1024 * 512)
        StarfieldResampler.downsampleBrightest(src, 2048, 1024, 1024, 512, out)
        val t0 = System.nanoTime()
        StarfieldResampler.downsampleBrightest(src, 2048, 1024, 1024, 512, out)
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        println("[StarfieldResampler] 2048x1024 → 1024x512 单次 %.2f ms".format(ms))
    }
}
