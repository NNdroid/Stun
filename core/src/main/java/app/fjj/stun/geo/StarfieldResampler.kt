package app.fjj.stun.geo

/**
 * 星野贴图的降采样：**取块内最大值**，而不是盒式平均。
 *
 * ## 为什么星野不能和地球贴图用同一种降采样
 * 星空是一张"点光源"图：2048×1024 里绝大多数像素是纯黑（中位亮度 0），只有 0.67% 的像素
 * 亮度 ≥ 10，亮点普遍只有 1~2 px。`BitmapFactory` 的 `inSampleSize = 2` 是**盒式平均**：
 * 一个 2×2 块里若只有一个亮像素，平均之后它就只剩 1/4 —— 原本 lum 8 的星变成 lum 2，
 * 和纯黑没区别。地球贴图（大面积同色）这么降采样毫无损失，星野这么降就是**把星星抹掉**。
 *
 * 实测症状：贴图 2048×1024 经 `inSampleSize = 2` 变成 1024×512，盘外背景平均亮度只剩
 * **0.093/255**、亮度 ≥ 6 的像素只占 **0.39%** —— 用户看到的就是"星空是纯黑的"。
 *
 * 取最大值就对了：块里只要有一颗星，输出就保留它原有的亮度。这就是图像处理里的
 * "膨胀/最大值滤波"，点光源检测的常规做法。
 *
 * ## 为什么要顺带做增益
 * 即使一个星点都不丢，这张图本身也很暗（mean lum **0.51**、p99 = 6）—— 它是给"满屏 1:1
 * 观看"准备的，而这里要拿它铺满一块几百 dp 的方框。乘一个增益把星点抬到看得见，
 * 纯黑处乘完仍是纯黑，所以不需要任何"保暗部"的曲线。
 *
 * 纯 [IntArray] 数学，可在普通 JVM 单测里直接跑。
 */
object StarfieldResampler {

    /**
     * 默认亮度增益。
     *
     * 实测（640×640 的渲染帧，盘外背景）：
     * - 只换成"取块最大值"、不加增益：mean ≈ 0.28。星点不丢了，但整体仍偏暗。
     * - 本值 2.2：mean ≈ **0.85**，亮度 ≥ 6 的像素约 **3%**（修复前是 0.39%）。
     *
     * 之所以敢直接乘：星野源图底色是**纯黑**（中位亮度 0），乘完还是黑，
     * 只有星点被抬起来，不需要任何"保暗部"的曲线。
     */
    const val DEFAULT_GAIN = 2.2f

    /** 定点增益的分母。用整数乘 + 移位替代逐通道浮点乘。 */
    private const val GAIN_SHIFT = 10

    /**
     * 把 [src]（`sw × sh`）按"块内逐通道取最大"降到 `dstW × dstH` 写进 [out]，
     * 再乘 [gain] 并夹到 255。
     *
     * [dstW] / [dstH] 必须不大于源尺寸（这是降采样器，不做放大）。
     * 输出恒为不透明（星野源图是不透明 RGB）。
     */
    fun downsampleBrightest(
        src: IntArray,
        sw: Int,
        sh: Int,
        dstW: Int,
        dstH: Int,
        out: IntArray,
        gain: Float = DEFAULT_GAIN,
    ): IntArray {
        require(sw > 0 && sh > 0 && dstW > 0 && dstH > 0) {
            "Sizes must be positive: src ${sw}x$sh dst ${dstW}x$dstH"
        }
        require(dstW <= sw && dstH <= sh) {
            "Downsampler only: destination must not exceed source: src ${sw}x$sh dst ${dstW}x$dstH"
        }
        require(src.size >= sw * sh && out.size >= dstW * dstH) {
            "Buffer too small: src=${src.size} (need ${sw * sh}) out=${out.size} (need ${dstW * dstH})"
        }

        val gainQ = (gain.coerceAtLeast(0f) * (1 shl GAIN_SHIFT)).toInt()

        var o = 0
        for (dy in 0 until dstH) {
            // 目标行 [dy, dy+1) 覆盖的源行区间，向上取整保证区间非空且不重叠、不遗漏。
            val sy0 = dy * sh / dstH
            val sy1 = (((dy + 1) * sh + dstH - 1) / dstH).coerceIn(sy0 + 1, sh)
            for (dx in 0 until dstW) {
                val sx0 = dx * sw / dstW
                val sx1 = (((dx + 1) * sw + dstW - 1) / dstW).coerceIn(sx0 + 1, sw)
                var r = 0
                var g = 0
                var b = 0
                for (sy in sy0 until sy1) {
                    var i = sy * sw + sx0
                    for (sx in sx0 until sx1) {
                        val c = src[i++]
                        val cr = (c ushr 16) and 0xFF
                        if (cr > r) r = cr
                        val cg = (c ushr 8) and 0xFF
                        if (cg > g) g = cg
                        val cb = c and 0xFF
                        if (cb > b) b = cb
                    }
                }
                val r2 = ((r * gainQ) shr GAIN_SHIFT).coerceAtMost(255)
                val g2 = ((g * gainQ) shr GAIN_SHIFT).coerceAtMost(255)
                val b2 = ((b * gainQ) shr GAIN_SHIFT).coerceAtMost(255)
                out[o++] = (0xFF shl 24) or (r2 shl 16) or (g2 shl 8) or b2
            }
        }
        return out
    }
}
