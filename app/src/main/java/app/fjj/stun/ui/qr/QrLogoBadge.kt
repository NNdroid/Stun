package app.fjj.stun.ui.qr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

/**
 * 在二维码正中间挖一块白色圆角方块，把**半透明**的应用图标放进去。
 *
 * ## 这不是"贴纸"，是真的挖掉了数据
 *
 * 二维码没有"留白区"这种东西：中心那块同样是数据模块，盖上图标等于**物理销毁**了那些模块，
 * 只能靠 Reed-Solomon 冗余把它算回来。所以尺寸不是审美问题，是容量问题。
 *
 * ## 边长占比是怎么定下来的
 *
 * 用 zxing 对真实帧做过扫描（`QrFrameRenderer` 的 L 级、version 24，位图按 6px/模块）：
 *
 * ```
 * 边长占比   15%   18%   20%   22%   25%   28%
 * 解码(清晰) 3/3   3/3   3/3   3/3   3/3   0/3
 * 解码(模糊) 3/3   3/3   3/3   3/3   3/3   0/3
 * ```
 *
 * 失效点落在 25%~28% 之间，正好对得上 L 级约 7% 的纠错容量（25% 边长 ≈ 6.3% 面积，卡在
 * 临界；28% ≈ 7.8% 直接超）。当初取 20%（≈4% 面积）用了六成冗余，其实**远没到失效线**，
 * 只是那轮探测的最小有意义刻度就是 15%。
 *
 * 现在压到 15%（≈2.25% 面积，约三成容量）：徽标要的是"看得出是谁"，不是"占据版面"，
 * 多出来的余量让给反光、手抖、对焦不实这些真实损耗 —— 上面"模糊"那一行就是先做 3x3
 * 均值再解的，已经算进去。往**上**调必须重跑遮挡探测；往下调只多留余量，不用再测。
 *
 * 同一轮探测里也试过升到 M 级：版本从 24 涨到 27，360dp 下每模块从 3.19dp 掉到 2.88dp
 * （**模块变小 = 更难扫**），换来的是更大的遮挡余量，而 15% 用不到那份余量。
 * 所以纠错级**保持 L 不变**，理由见 [app.fjj.stun.qr.QrFrameRenderer] 的类注释。
 *
 * ## 为什么是纯白、无描边、无阴影
 *
 * 二值化时任何灰边都会变成一圈随机噪点，白白吃掉纠错预算。纯白底是"最省"的遮挡：
 * 它让边界干净，解码器把这块当成一个完整的错误区块去纠。同理图标外面留一圈白环
 * （[ICON_FRACTION]）而不是让图标铺满方块。
 *
 * ## 半透明是纯装饰，跟解码无关
 *
 * 想清楚顺序：**白底方块是不透明的**，图标是画在白底之上的。所以图标再透，底下那一片
 * 数据模块也已经被白底物理销毁了 —— 透明度既让不了任何数据"漏回来"（无正向作用），
 * 也伤不了它（无负向作用）：被改写的只是"这块已销毁区域二值化后读成白还是读成黑"，
 * 那些模块本来就全算在纠错开销里，读成什么都是错的。解码余量由 [BADGE_FRACTION] 决定，
 * 跟 alpha 无关。
 *
 * 这不是推理，是量过的：真图标、6px 与 3px/模块、清晰与 3x3 模糊，alpha 从 0.3 一路扫到
 * 1.0（完全不透）全部解得出来，一列 FAIL 都没有 —— 见 [QrLogoBadgeTest] 的
 * `transparency costs no decoding headroom`。所以别指望靠调 alpha 换解码余量（换不到），
 * 也不用担心把 alpha 调低会损害扫描。取 0.5 只是因为**好看**：图标轻一点，不压二维码。
 *
 * 顺带记一段走错的路，免得下一个人再挖一遍：一度以为"最暗部件混到白底上的亮度必须高于
 * 二值化阈值"是条硬约束，还为此复刻了 zxing 的阈值算法去量。实测阈值落在 168，而本仓库
 * `ic_fox_logo` 的眼睛（`#212121`，亮度 33）按 0.5 混出来只有 144，**低于**阈值 ——
 * 图标中心确实被读成了一小块黑，但既然它整块都在白底方块里面，扫描毫无影响。
 * 那条怀疑不成立，护栏就落在解码测试上。
 *
 * ## 为什么图标要先光栅化成 Bitmap
 *
 * [AnimatedQrPlayer] 是在**后台线程**编码的，而 `VectorDrawable.draw()` 会写自己的缓存
 * 位图，跟主线程同时画同一个实例（对话框头部那个同款图标）会打架。所以图标在
 * 主线程 [rasterize] 一次，之后每帧只是[drawInto]一张只读位图。透明度在 [drawInto]
 * 里用 `Paint.alpha` 混合到已有的白底上，每帧零额外开销。
 */
object QrLogoBadge {

    /** 白色底方块边长 ÷ 二维码位图边长。取值依据见类注释，往上调必须重跑遮挡探测。 */
    const val BADGE_FRACTION = 0.15f

    /** 图标边长 ÷ 底方块边长；剩下的是那圈白色留白。 */
    private const val ICON_FRACTION = 0.74f

    /** 图标透明度：1.0 = 完全不透。取 0.5 是纯审美；跟解码无关的理由见类注释。 */
    const val ICON_ALPHA = 0.5f

    /** 圆角半径 ÷ 底方块边长。 */
    private const val CORNER_FRACTION = 0.22f

    /** 底方块边长（px）。对外暴露是为了让单测直接断言几何，不用去量像素。 */
    fun badgeSide(qrSizePx: Int): Int = (qrSizePx * BADGE_FRACTION).roundToInt()

    /** 图标边长（px）。 */
    fun iconSide(qrSizePx: Int): Int = (badgeSide(qrSizePx) * ICON_FRACTION).roundToInt()

    /**
     * 把矢量图标按 [sizePx] 光栅化成一张 ARGB_8888 位图。**必须在主线程调用**（见类注释）。
     *
     * 返回 null 而不是抛：图标是装饰，取不到就让二维码保持原样，不该把弹窗搞崩。
     */
    fun rasterize(drawable: Drawable, sizePx: Int): Bitmap? {
        if (sizePx <= 0) return null
        return runCatching {
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(Canvas(bitmap))
            bitmap
        }.getOrNull()
    }

    /** 就地把徽标画到二维码位图上（不新建位图 —— 动画路径每帧都要画一次）。 */
    fun drawInto(qr: Bitmap, logo: Bitmap?) = drawInto(qr, logo, ICON_ALPHA)

    /**
     * 同 [drawInto]，但把透明度作为参数暴露出来。
     *
     * 生产路径只用默认值；参数化是为了让 [app.fjj.stun.ui.qr.QrLogoBadgeTest] 能扫一遍
     * 透明度区间，把"再调暗多少就会扫不上"这条线量出来而不是拍在注释里。
     *
     * @param alpha 1.0 = 完全不透；调大只会让图标更重，**不会**让任何数据透出来
     *              （不透明的白底已经在图标下面了）。
     */
    fun drawInto(qr: Bitmap, logo: Bitmap?, alpha: Float) {
        if (logo == null || qr.width <= 0 || qr.height <= 0) return
        val size = minOf(qr.width, qr.height)
        val side = badgeSide(size)
        if (side <= 0) return

        val left = (qr.width - side) / 2f
        val top = (qr.height - side) / 2f
        val radius = side * CORNER_FRACTION

        val canvas = Canvas(qr)
        canvas.drawRoundRect(
            RectF(left, top, left + side, top + side),
            radius, radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            },
        )

        val icon = iconSide(size)
        if (icon <= 0) return
        val iLeft = (qr.width - icon) / 2f
        val iTop = (qr.height - icon) / 2f
        canvas.drawBitmap(
            logo, null,
            RectF(iLeft, iTop, iLeft + icon, iTop + icon),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                this.alpha = (alpha * 255).roundToInt()
            },
        )
    }
}
