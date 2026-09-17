package app.fjj.stun.geo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 把 day / night 两张**等距圆柱**贴图逐纹素烘成"此刻的晨昏贴图"。
 *
 * ## 为什么不做"画晨昏线 + 裁出区域再压暗"
 * 旧实现是几何派：采样出"离直射点角距 = θ"的小圆 → Sutherland–Hodgman 裁前半球 →
 * 沿视界补弧 → 得到屏幕上的闭合区域 → `clipPath` + 铺图。
 * 形状上它是对的，但**每一帧都要重新判定"哪块在正面、哪块在背面"**，而在 θ = 90°（也就是
 * 晨昏线本身）那一层，这个判定在数值上是退化的：
 *
 * - θ = 90° 的边界是**大圆**，它与视界的两个交点**恒为对径点**，于是"沿视界往哪边走"
 *   由 `atan2(±0, −1)` 决定 —— **正负号来自浮点零的符号**。太阳相对相机一转，符号就翻，
 *   补出来的弧整个镜像到向阳侧。
 * - 太阳贴近盘心时，`cos(90°)` 在 float 下是 `+6.12e-17`（不是 0），整圈都满足 `v·forward ≥ 0`
 *   ⇒ 一个交点都不产生 ⇒ **整圈填充 = 整个盘面**。
 *
 * 两者叠加出来就是用户看到的"**一转动就一闪一闪**"：昼夜边界每帧在两种错误形状之间跳。
 *
 * ## 现在的做法：逐纹素
 * 每个纹素自己的世界经纬度是**固定已知**的（等距圆柱贴图的定义），球面法线 N 就是那个点的
 * 单位向量；再拿太阳方向 S 去点乘，`N·S > 0` 是白昼、`< 0` 是黑夜、`≈ 0` 就是晨昏线。
 * 于是"昼夜"从一个**需要裁切的区域**变成了一次**逐纹素的标量判定**，而这个判定跟相机姿态
 * **毫无关系** —— 相机怎么转，贴图内容都不变，屏幕上自然不可能闪。
 *
 * 这等价于把片元着色器 `mix(night, day, smoothstep(-e, e, dot(N, S)))` 搬到 CPU 上烘一张图。
 * 代价是每换一次太阳位置要重烘一遍，所以调用方要把太阳位置**量化**（太阳 1 小时才走 15°）。
 *
 * ## 点积为什么能化简成"每纹素 1 乘 1 加"
 * `N·S = cos(lat)·cos(latS)·cos(lon − lonS) + sin(lat)·sin(latS)`，
 * 于是只有 `cos(lon − lonS)` 随列变化、`cos(lat)·cos(latS)` 与 `sin(lat)·sin(latS)` 随行变化。
 * 两者各预表一次（W + H 次三角函数），内层循环就只剩 1 乘 1 加 —— 1024×512 一次烘焙里
 * 五十万个纹素全走这条路，不需要任何 `sqrt`/`acos`。
 *
 * 纯 [IntArray] 数学，不碰 Android 类型，因此能在普通 JVM 单测里直接跑。
 */
object DayNightCompositor {

    /**
     * 昼夜混合带的半宽，单位是点积值（1.0 ≈ 90°）。`0.04` 对应整条过渡带约 4.6° 弧长。
     *
     * 不能取 0：那会让晨昏线是一条硬边，在球面上呈明显的锯齿台阶（每个纹素 0.35°，
     * 硬边会被抗锯齿放大成阶梯）。
     */
    const val DEFAULT_SOFTNESS = 0.04f

    /** 暮光带半宽（`|N·S|` 小于它才往 RGB 上叠暖色）。`0.15` ⇒ 晨昏线两侧各约 8.6°。 */
    const val TWILIGHT_BAND = 0.15f

    /**
     * 暮光峰值往 RGB 各通道加多少（已含 255 的缩放）。偏红橙、几乎不加蓝 —— 取的是
     * "日出日落时低角度阳光穿过厚大气"的观感。峰值出现在 `N·S = 0`（晨昏线正中）。
     */
    const val TWILIGHT_R = 26
    const val TWILIGHT_G = 9
    const val TWILIGHT_B = 1

    /**
     * 夜图城市灯光的增益。夜图本身很暗（实测 mean lum 10.7 / 中位数 7.1），
     * 与白昼图（mean 99.6）拼在一起后夜侧的灯会被压得看不出"这是城市"。
     */
    const val NIGHT_LIGHTS_GAIN = 1.15f

    /**
     * 逐纹素合成一张**不透明**的晨昏贴图，结果写进 [out]（需要 `w * h` 个元素）。
     *
     * [day] / [night] 是同样尺寸（`w × h`）的等距圆柱贴图，ARGB 打包（两张源图都不透明，
     * 所以输出恒为 `alpha = 0xFF`）。[night] 应当已经过 [boostNightLights]。
     *
     * [anchorLonDeg] 是**输出贴图**第 0 列左边缘对应的世界经度；[day] / [night] 自己则以
     * `-180°` 为第 0 列（等距圆柱的常规约定），两者的差按像素取整后就是读源图时的列偏移。
     * 之所以要把输出旋转一个偏移，见 [offsetPxFor]。
     *
     * @return [out]，方便链式使用。
     */
    fun compose(
        day: IntArray,
        night: IntArray,
        w: Int,
        h: Int,
        sunLonDeg: Float,
        sunLatDeg: Float,
        anchorLonDeg: Float,
        out: IntArray,
        softness: Float = DEFAULT_SOFTNESS,
        twilight: Float = 1f,
    ): IntArray {
        require(w > 0 && h > 0) { "Texture size must be positive: ${w}x$h" }
        require(day.size >= w * h && night.size >= w * h && out.size >= w * h) {
            "Buffer too small: need ${w * h}, day=${day.size} night=${night.size} out=${out.size}"
        }

        // 每列的 cos(lon − sunLon)。用纹素**中心**的经度（+0.5 列），这是逐纹素计算的正确取法。
        val colCos = FloatArray(w)
        val off = offsetPxFor(anchorLonDeg, w)
        val anchor = anchorLonDeg(off, w)
        val lon0 = Math.toRadians(anchor.toDouble())
        val sunLon = Math.toRadians(sunLonDeg.toDouble())
        val lonStep = Math.toRadians(360.0 / w)
        // 先算第 0 列，其余靠递推旋转 —— cos 的加法公式，省掉 w 次三角函数。
        val d0 = lon0 + lonStep * 0.5 - sunLon
        var cc = cos(d0)
        var cs = sin(d0)
        val cStep = cos(lonStep)
        val sStep = sin(lonStep)
        for (x in 0 until w) {
            colCos[x] = cc.toFloat()
            val nc = cc * cStep - cs * sStep
            cs = cc * sStep + cs * cStep
            cc = nc
        }

        val sunLat = Math.toRadians(sunLatDeg.toDouble())
        val sinSunLat = sin(sunLat)
        val cosSunLat = cos(sunLat)

        val e = softness.coerceAtLeast(1e-4f)
        val inv2e = 1f / (2f * e)
        val twBand = TWILIGHT_BAND
        val twScale = twilight.coerceAtLeast(0f)

        var o = 0
        for (y in 0 until h) {
            // 纹素中心纬度：v 从 0（北纬 90°）线性走到 1（南纬 90°）。
            val lat = Math.toRadians(90.0 - 180.0 * (y + 0.5) / h)
            val a = (cos(lat) * cosSunLat).toFloat()
            val c = (sin(lat) * sinSunLat).toFloat()
            val rowBase = y * w
            // 源图列游标：输出第 x 列取源图第 (x + off) 列。用游标自增避免每纹素一次取模。
            var sp = off
            for (x in 0 until w) {
                val nd = a * colCos[x] + c
                val d = day[rowBase + sp]
                var res: Int
                if (nd >= e) {
                    res = d
                } else if (nd <= -e) {
                    res = night[rowBase + sp]
                } else {
                    // smoothstep(-e, e, nd)
                    val t = (nd + e) * inv2e
                    val f = t * t * (3f - 2f * t)
                    val fq = (f * 256f).toInt()
                    val dq = 256 - fq
                    val n = night[rowBase + sp]
                    val r = ((((d ushr 16) and 0xFF) * fq) + (((n ushr 16) and 0xFF) * dq)) shr 8
                    val g = ((((d ushr 8) and 0xFF) * fq) + (((n ushr 8) and 0xFF) * dq)) shr 8
                    val b = (((d and 0xFF) * fq) + ((n and 0xFF) * dq)) shr 8
                    res = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                if (twScale > 0f && nd < twBand && nd > -twBand) {
                    res = withTwilight(res, nd, twScale)
                }
                out[o] = res
                o++
                if (++sp == w) sp = 0
            }
        }
        return out
    }

    /**
     * 逐纹素合成一张**纯色夜面遮罩**（矢量回退用）：白昼侧 alpha = 0，夜面侧 alpha 按
     * "进入夜面多深"从 0 爬到 [maxAlpha]，RGB 恒为 [tint]。
     *
     * 为什么这也得逐纹素：它和贴图版要解决的是同一个问题 —— 旧实现的实色夜面同样是
     * "裁区域再铺"，一样会在转动时翻面。而且这块遮罩是**铺在球面上**的，用 [anchorLonDeg]
     * 锚定后才与网格同一套经度，否则会把夜面转到别处去。
     *
     * [ramp] 是"多少个点积值之后爬到满 alpha"。取 0.6（约 37° 弧长）让夜面有层次，
     * 而不是一到晨昏线就盖死（海岸线还要从夜色里隐约透出来）。
     */
    fun composeTint(
        w: Int,
        h: Int,
        sunLonDeg: Float,
        sunLatDeg: Float,
        anchorLonDeg: Float,
        tint: Int,
        out: IntArray,
        maxAlpha: Int = 210,
        ramp: Float = 0.6f,
        softness: Float = 0.06f,
    ): IntArray {
        require(w > 0 && h > 0) { "Texture size must be positive: ${w}x$h" }
        require(out.size >= w * h) { "Buffer too small: need ${w * h}, out=${out.size}" }

        val colCos = FloatArray(w)
        val lon0 = Math.toRadians(anchorLonDeg.toDouble())
        val sunLon = Math.toRadians(sunLonDeg.toDouble())
        val lonStep = Math.toRadians(360.0 / w)
        val d0 = lon0 + lonStep * 0.5 - sunLon
        var cc = cos(d0)
        var cs = sin(d0)
        val cStep = cos(lonStep)
        val sStep = sin(lonStep)
        for (x in 0 until w) {
            colCos[x] = cc.toFloat()
            val nc = cc * cStep - cs * sStep
            cs = cc * sStep + cs * cStep
            cc = nc
        }

        val sunLat = Math.toRadians(sunLatDeg.toDouble())
        val sinSunLat = sin(sunLat)
        val cosSunLat = cos(sunLat)

        val e = softness.coerceAtLeast(1e-4f)
        val rampSpan = (ramp - e).coerceAtLeast(1e-3f)
        val rgb = tint and 0x00FFFFFF

        var o = 0
        for (y in 0 until h) {
            val lat = Math.toRadians(90.0 - 180.0 * (y + 0.5) / h)
            val a = (cos(lat) * cosSunLat).toFloat()
            val c = (sin(lat) * sinSunLat).toFloat()
            for (x in 0 until w) {
                val nd = a * colCos[x] + c
                val alpha: Int
                if (-nd <= e) {
                    // `N·S ≥ -e`：白昼侧（含混合带靠昼的那一半），遮罩完全透明。
                    alpha = 0
                } else {
                    // 从混合带的外沿（nd = -e）开始爬，到 nd = -ramp 满值。
                    val t = ((-nd - e) / rampSpan).coerceIn(0f, 1f)
                    val s = t * t * (3f - 2f * t)
                    alpha = (maxAlpha * s).toInt().coerceIn(0, 255)
                }
                out[o] = (alpha shl 24) or rgb
                o++
            }
        }
        return out
    }

    /**
     * 把夜图整张抬亮（城市灯光增益），**就地**改写 [pixels]。
     *
     * 只抬亮、不压暗：夜图底色是纯黑（中位亮度 0），乘完还是黑，只有灯光会被拉起来，
     * 所以不需要做任何"保住暗部"的曲线。
     */
    fun boostNightLights(pixels: IntArray, gain: Float = NIGHT_LIGHTS_GAIN) {
        if (gain <= 1f) return
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (((c ushr 16) and 0xFF) * gain).toInt().coerceAtMost(255)
            val g = (((c ushr 8) and 0xFF) * gain).toInt().coerceAtMost(255)
            val b = ((c and 0xFF) * gain).toInt().coerceAtMost(255)
            pixels[i] = (c and (0xFF shl 24)) or (r shl 16) or (g shl 8) or b
        }
    }

    /**
     * 第 [offsetPx] 列左边缘对应的世界经度（等距圆柱贴图以 `-180°` 为第 0 列）。
     */
    fun anchorLonDeg(offsetPx: Int, width: Int): Float =
        offsetPx.toFloat() / width * 360f - 180f

    /**
     * 想让贴图第 0 列落在 [anchorLonDeg]，需要把源图往左滚多少列。
     *
     * ## 为什么输出贴图要旋转
     * 贴图上有条**缝**（第 0 列与第 `W−1` 列在内容上并不相邻）。而 `Canvas.drawBitmapMesh`
     * 的贴图坐标是**隐式均匀网格**：第 col 列恒取 `col / meshWidth`，没法给 UV。背面网格列
     * 又要靠"整段塌到相邻的正面列上、退化成零面积"来隐藏，这就要求缝必须落在**背面**
     * （否则背面网格会成一段横跨盘面的带子，把接缝那一行的贴图糊满正面半球）。
     *
     * 既然烘焙权在我们手里，就在烘的时候直接把缝转到相机对足点上 —— 输出第 0 列即 `anchorLonDeg`，
     * 取 `anchorLonDeg ≈ 相机经度 + 180°` 即可。返回值按整数列取整，所以
     * [anchorLonDeg] 少一点精度（`0.35°` @ W=1024），换来的是**不做插值**的整数列搬运。
     */
    fun offsetPxFor(anchorLonDeg: Float, width: Int): Int =
        ((anchorLonDeg + 180f) / 360f * width).roundToInt().mod(width)

    /** 晨昏线附近的暖色叠加。`nd` 越接近 0 越强，两侧各 [TWILIGHT_BAND] 之外归零。 */
    private fun withTwilight(color: Int, nd: Float, scale: Float): Int {
        val t = 1f - abs(nd) / TWILIGHT_BAND
        if (t <= 0f) return color
        val w = t * t * (3f - 2f * t) * scale
        if (w <= 0f) return color
        val r = (((color ushr 16) and 0xFF) + TWILIGHT_R * w).toInt().coerceAtMost(255)
        val g = (((color ushr 8) and 0xFF) + TWILIGHT_G * w).toInt().coerceAtMost(255)
        val b = ((color and 0xFF) + TWILIGHT_B * w).toInt().coerceAtMost(255)
        return (color and (0xFF shl 24)) or (r shl 16) or (g shl 8) or b
    }
}
