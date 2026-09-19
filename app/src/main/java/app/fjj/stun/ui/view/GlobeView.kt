package app.fjj.stun.ui.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import app.fjj.stun.R
import app.fjj.stun.geo.CoastlineData
import app.fjj.stun.geo.DayNightCompositor
import app.fjj.stun.geo.GeoPoint
import app.fjj.stun.geo.GlobeMarker
import app.fjj.stun.geo.GlobeProjection
import app.fjj.stun.geo.GlobeTopology
import app.fjj.stun.geo.StarfieldResampler
import com.google.android.material.color.MaterialColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 连接详情面板里的 3D 地球：海岸线 + 全拓扑标记 + hub 弧线 + 城市名胶囊。
 *
 * ## 方形自适应
 * `onMeasure` 里**高度直接取宽度**（XML 写 `layout_height="wrap_content"`）。
 * 球半径按较短的边算，所以外层若显式写死高度也不会溢出，只是球会变小。
 *
 * ## 每帧只做点积，不做三角函数
 * 海岸线 5015 个顶点在数据到手时**一次性**转成单位球向量，每帧只剩 `clip` 里的点积。
 * 若每帧从 (lon,lat) 现算 `sin/cos`，那是两万次三角函数/帧 —— 白白烧电。
 *
 * ## 颜色只在构造时解析一次
 * `resources.getIdentifier` 是查表操作，而标记/弧线是**每帧**都要上色的。
 * 所有颜色都在 init 里定下来存成字段，绘制期一次都不查。
 *
 * ## 弧线靠"彗星头 + 尾迹"变活
 * 每条弧上都有一颗亮点从 hub 奔向目的地：头是实心圆、外面套一圈淡光晕，尾迹分
 * [COMET_TAIL_TIERS] 段、越靠头越亮。**空闲弧也跑**（只是整体更暗），
 * 只让"带流量"的弧动的话，绝大多数连线是死的，整张图看着不动。
 * 速度 ∝ 流量，分 [COMET_MAX_TIER] 档，**档位取整数是有意的**：彗星相位 = 全局相位 × 档位
 * + 每条弧的错相，全局相位回绕时只有整数倍才连续，非整数倍会在回绕瞬间让所有彗星一起跳。
 *
 * ## 自转 = 有限摆动，不是整圈转
 * 绕一个**中心经度**左右各摆 [SWAY_DEG] 度（正弦）。整圈转的毛病是必然周期性把 hub 转到背面，
 * 那时它的弧线只剩视界附近一截尾巴，看着像噪点。摆动让 hub 一直待在正面。
 * 中心由两处设定：对准 hub 时（`swayPhase` 同时归零 ⇒ 第一眼就是 hub 在盘心），
 * 以及用户惯性停手的那一刻（见 [anchorSwayToCamera]，拖到哪儿就绕哪儿摆）。
 *
 * ## 手势
 * 横向分量占优且超过 touch slop 才接管（`requestDisallowInterceptTouchEvent(true)`），
 * 否则纵向拖让给外面的 sheet/NestedScrollView。松手后先按甩动速度惯性转，1.2s 后由摆动接管。
 *
 * 两指是**缩放**（[zoom]）：第二根手指一落下就自己接管事件，否则两指一分一合会变成滚面板。
 * 放大只作用于几何 —— 落点半径与城市名胶囊的字号仍按 dp 走（见 [zoom] 的说明）。
 *
 * 无障碍：位图读屏念不出来，所以对外是**一个 contentDescription 节点**（`importantForAccessibility = YES`）。
 * 描述文本由接线方在每次提交拓扑后写入（见 HomeFragment.globeAccessibilityLabel），
 * 并且 XML 里也给了缺省串，未接线时不会变成一个没名字的节点。
 */
class GlobeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // ──────────────────────────────────────────────────────────── 颜色（只解析一次）

    private val colorLand = themeColor("colorOnSurface", FALLBACK_ON_SURFACE)
    private val colorCoastline = colorLand
    private val colorOcean = themeColor("colorPrimary", FALLBACK_PRIMARY)
    private val colorRim = themeColor("colorOutlineVariant", FALLBACK_OUTLINE_VARIANT)
    private val colorNode = themeColor("colorPrimary", FALLBACK_PRIMARY)
    private val colorArc = themeColor("colorOnSurfaceVariant", FALLBACK_ON_SURFACE_VARIANT)
    private val colorSurface = themeColor("colorSurface", FALLBACK_SURFACE)
    private val colorExitRing = themeColor("colorOnSurfaceVariant", FALLBACK_ON_SURFACE_VARIANT)

    /**
     * 扁平模式的托盘色（球体外面那一圈方框底）。刻意取**卡片自己的** `colorSurfaceContainerLow`：
     * 托盘唯一职责是"别让这块变成透明的"，配色上必须与卡片**完全融合**，
     * 否则球外会浮出一个色差方框，看着像绘制残留。值必须与
     * `bottom_sheet_connection_details.xml` 里拓扑卡的 `app:cardBackgroundColor` 保持一致（改一处要改两处）。
     */
    private val colorTray = themeColor("colorSurfaceContainerLow", FALLBACK_SURFACE_CONTAINER_LOW)
    // 语义色刻意保留字面量资源（不跟 DynamicColors 走）：状态灯与上下行数据色是全局约定。
    private val colorCurrent = context.getColor(R.color.connection_state_online)
    private val colorActive = context.getColor(R.color.widget_down_accent)

    /**
     * 一套完整配色。扁平模式逐值等于历史字段（保证既有像素测试不破）；
     * 星空模式换成"太空 + 青色彗尾"的一套。构造期各定一次，绘制期只读 [palette]，零查表。
     */
    private data class Palette(
        val ocean: Int, val land: Int, val coast: Int, val rim: Int,
        val node: Int, val arc: Int, val active: Int, val current: Int,
        val exitRing: Int, val surface: Int
    )

    private val flatPalette = Palette(
        ocean = colorOcean, land = colorLand, coast = colorCoastline, rim = colorRim,
        node = colorNode, arc = colorArc, active = colorActive, current = colorCurrent,
        exitRing = colorExitRing, surface = colorSurface
    )

    // 星空模式：深蓝海洋 + 偏绿陆地（无贴图时的写实矢量回退）+ 亮青弧/彗星；hub 仍在线绿，
    // 城市胶囊沿用深底白字（本就与参考图一致，不随模式变）。有 earth_day.webp 时海洋/陆地被贴图覆盖。
    private val starryPalette = Palette(
        ocean = 0xFF0B2E52.toInt(), land = 0xFF2E4B33.toInt(),
        coast = 0xFF6FA8DC.toInt(), rim = 0x8038BDF8.toInt(),
        node = 0xFF7DD3FC.toInt(), arc = 0xFF38BDF8.toInt(), active = 0xFF38BDF8.toInt(),
        current = colorCurrent, exitRing = 0xFF94A3B8.toInt(), surface = colorSurface
    )

    private var palette = flatPalette

    /**
     * 星空模式开关。切换即换 [palette] 并重绘；背景/星野只在星空模式下出现。
     * 由外部（连接详情面板的切换按钮）驱动，选择持久化在 SettingsManager。
     */
    var starryMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            palette = if (value) starryPalette else flatPalette
            applyPaletteToPaints()
            if (value) ensureTextures()
            starDirty = true
            invalidate()
        }

    /**
     * 星空模式用的"现在"（毫秒）。**只为测试留的口子**：晨昏线压暗与城市灯光都按太阳直射点算，
     * 而直射点随运行时刻漂 —— 不钉住它，任何"贴图对位"的像素断言都会随时刻漂移
     * （同一个点的采样值可能是白昼贴图，也可能是几乎全黑的夜面）。null = 用真实时间。
     */
    internal var sunEpochMillis: Long? = null

    /**
     * 把当前 [palette] 灌进那些"只在 init 定过一次色"的画笔。
     *
     * ⚠️ **每设一次 `color` 都必须紧跟一次 `alpha`**：`Paint.setColor()` 会把 alpha 通道
     * 一并重置成**传入色自己的** alpha，而 [Palette] 里的值全是 FF，于是
     * `LAND_ALPHA / COASTLINE_ALPHA / OCEAN_ALPHA` 会被静默冲成 255。
     *
     * 症状很能骗人：`init` 里是先 `color =` 再 `alpha =`，所以**从没切过模式**的球是柔和的
     * 半透明淡洗（陆 #745F4B 量级、海 #D5B594 量级，靠托盘透出来）；
     * 只要动过一次星空开关（开或关都算），整颗球就变成**不透明**的实心近黑陆地
     * (#201A17) + 实心琥珀海洋 (#904D00)，与浅色卡片完全不搭 —— 用户报的
     * "关闭星光模式之后配色和默认打开不一致"就是这个。
     * 由 `切换星空模式开关不改变扁平模式配色` 逐像素钉着（整盘 ~25 万像素全变）。
     */
    private fun applyPaletteToPaints() {
        landPaint.color = palette.land
        landPaint.alpha = LAND_ALPHA
        coastlinePaint.color = palette.coast
        coastlinePaint.alpha = COASTLINE_ALPHA
        oceanPaint.color = palette.ocean
        oceanPaint.alpha = OCEAN_ALPHA
        rimPaint.color = palette.rim
        haloPaint.color = palette.active
        exitRingPaint.color = palette.exitRing
        // 海洋径向高光随模式重建（球体感）。
        oceanShaderRadius = 0f
    }

    /** 海洋径向高光 shader 对应的半径；变化（含模式切换/尺寸变化）时在 onDraw 重建。 */
    private var oceanShaderRadius = 0f

    /** 星空模式的星野缓存位图（种子固定，尺寸变化时重建），每帧只 blit 一次。
     *  仅在 `geo/stars.jpg` 缺失时作为回退；有贴图时 [drawStars] 直接画贴图。 */
    private var starBitmap: Bitmap? = null
    private var starDirty = true

    /**
     * 真实卫星贴图（assets/geo/，均为 equirectangular 2:1）。
     *
     * 白天/夜景**只留像素数组，不留 Bitmap**：每帧要画的是它们**合成**出来的那张
     * "此刻的晨昏贴图"（见 [ensureCompositeTexture]），源图位图烘完就再没用处 ——
     * 留着只是白占 4MB（两张 1024×512 ARGB）。
     *
     * 星空背景是唯一的例外：星野是**点光源**，不能和地球贴图一起降采样（见
     * [StarfieldResampler]），所以它单独走"整尺寸解码 → 取块最大值减半 → 增益"这条路。
     *
     * 任一缺失时各自回退（白天缺 → 矢量海岸线；夜景缺 → 纯色夜面；星空缺 → 程序化星野）。
     */
    private var dayPixels: IntArray? = null
    private var nightPixels: IntArray? = null
    private var starsTexture: Bitmap? = null
    private var textureWidth = 0
    private var textureHeight = 0
    private var texturesLoaded = false

    /**
     * "此刻的晨昏贴图"：把 day / night 按 `N·S` 逐纹素烘成的一张不透明位图，
     * 每帧只 [Canvas.drawBitmapMesh] 一次。缓存键见 [ensureCompositeTexture]。
     */
    private var compositeBitmap: Bitmap? = null
    private var compositePixels: IntArray? = null
    private var compositeAnchorLon = Float.NaN
    private var compositeSunLon = Float.NaN
    private var compositeSunLat = Float.NaN

    /** 矢量回退（没有白天贴图）用的纯色夜空遮罩。尺寸刻意小 —— 它只是一层平滑渐变。 */
    private var nightMaskBitmap: Bitmap? = null
    private var nightMaskPixels: IntArray? = null
    private var nightMaskAnchorLon = Float.NaN
    private var nightMaskSunLon = Float.NaN
    private var nightMaskSunLat = Float.NaN

    private val texturePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * 太阳直射点经纬度，[updateSubsolar] 每帧算一次。
     *
     * 只用直射点的**方向**：昼夜边界由 `N·S`（`N` 是纹素自己的球面法线）逐纹素算出来，
     * 与相机姿态完全无关 —— 见 [DayNightCompositor] 与 [ensureCompositeTexture]。
     */
    private var subsolarLatDeg = 0f
    private var subsolarLonDeg = 0f

    // ──────────────────────────────────────────────────────────── 渲染数据

    /** 海岸线顶点，按环存**单位球向量**（xyz 交错）——`clip` 直接吃这个格式。 */
    private var coastlineVectors: Array<FloatArray>? = null

    private var markers: List<RenderMarker> = emptyList()
    private var arcs: List<RenderArc> = emptyList()

    /** hub 的单位球向量。用来判断"弧的源头转到背面了没有"（见 [drawArcs]）。 */
    private var hubVector: FloatArray? = null

    private var aimedAtHub = false
    private var aimedLon = 0f
    private var aimedLat = 0f

    // ──────────────────────────────────────────────────────────── 相机与复用缓冲

    private val basis = GlobeProjection.Basis()
    private val clipBuffer = GlobeProjection.ClipBuffer(capacity = 4096)
    private val projection = FloatArray(3)
    private val arcScratch = FloatArray(ARC_SCRATCH_FLOATS)

    /**
     * 一条弧采样后的**屏幕坐标**（每点 2 个 float）与可见性。
     *
     * 采样一次、两处用：底弧要它连折线，彗星要它按参数位置取点。分成两趟算的话，
     * 每帧的点积和投影都要翻倍 —— 弧可能有几十条、每条上百个点，这个量不小。
     */
    private val arcScreen = FloatArray(ARC_MAX_POINTS * 2)
    private val arcVisible = BooleanArray(ARC_MAX_POINTS)

    private val fillPath = Path()
    private val strokePath = Path()
    private val arcPath = Path()

    private var lonDeg = INITIAL_LON_DEG
    private var latDeg = INITIAL_LAT_DEG

    /**
     * 摆动的**中心经度**：自转不是整圈转，而是绕这个经度左右各摆 [SWAY_DEG] 度。
     * 它由两件事设定 —— "相机对准了 hub"（[submitTopology]）与"用户拖完停手的那一刻"
     * （[anchorSwayToCamera]）。用户拖到哪儿就绕哪儿摆，不会把他的操作拽回来。
     */
    private var swayCenterLon = INITIAL_LON_DEG

    /** 摆动相位，恒在 `0..1` 回绕。`0` 时偏移恰好为 0 ⇒ 相机正好在中心。 */
    private var swayPhase = 0f

    // ──────────────────────────────────────────────────────────── 点选

    /**
     * 选中的落点。用**落点坐标**当身份而不是下标 —— 拓扑每 2s 重建一次，
     * 一条连接开始/结束就会改排序（见 `MARKER_ORDER` 的 connectionCount），下标会对不上别的落点。
     */
    private var selectedPoint: GeoPoint? = null

    /** [selectedPoint] 在当前 [markers] 里的下标；-1 表示已不存在（落点消失 / 还没提交拓扑）。 */
    private var selectedIndex = -1

    /**
     * 选中变化回调。收到 `null` = 取消选中。
     *
     * 拓扑每 2s 刷新一次，连接数与速率在变，所以**只要选中还活着就会再发** ——
     * 相机在选中期间是冻结的，锚点不会漂，接线方只改文本、不必重建视图（重建会闪）。
     */
    var onSelectionChanged: ((Selection?) -> Unit)? = null

    // ──────────────────────────────────────────────────────────── 画笔

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    private val landPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorLand
        alpha = LAND_ALPHA
    }

    private val coastlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.6f)
        strokeJoin = Paint.Join.ROUND
        color = colorCoastline
        alpha = COASTLINE_ALPHA
    }

    private val oceanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorOcean
        alpha = OCEAN_ALPHA
    }

    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = colorRim
    }

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorNode
    }

    private val markerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        color = colorSurface
    }

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorActive
    }

    /** 彗星头（实心圆 + 一圈淡光晕）。颜色按弧的忙闲现改，所以不在这里定色。 */
    private val cometPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /**
     * 彗星头的**帧内收集池**：弧趟只记位置与 alpha，帧末 [drawCometHeads] 统一画在
     * 标记与胶囊之上。池子跨帧复用，绘制期零分配。
     */
    private class CometHead {
        var x = 0f
        var y = 0f
        var radius = 0f
        var glowAlpha = 0
        var coreAlpha = 0
    }
    private val cometHeads = ArrayList<CometHead>()
    private var headsUsed = 0

    /** 选中环。球上已有的语义色是"在线绿"（hub）与"流动绿"（彗星/光晕），蓝色是唯一和它们都区分得开的 —— 顺带一提它在小组件里是上行色，此处只当"选中"用。 */
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = context.getColor(R.color.widget_up_accent)
    }

    private val exitRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
        color = colorExitRing
        pathEffect = DashPathEffect(floatArrayOf(dp(2.6f), dp(2.2f)), 0f)
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = colorArc
    }

    /**
     * 城市名胶囊：深色底 + 白字，与点选气泡（bg_globe_bubble）同一套配色 ——
     * 浅色主题的球面是米色陆地 + 彩色海洋，只有深底白字在两种主题下都压得住。
     */
    private val labelChipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = LABEL_CHIP_BG
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
        textSize = dp(LABEL_TEXT_SIZE_DP)
        color = Color.WHITE
    }
    /** 胶囊高度与文字基线偏移只跟字号有关，构造期算一次，绘制期零分配。 */
    private val labelChipHeight: Float =
        labelTextPaint.fontMetrics.run { bottom - top } + dp(LABEL_CHIP_PAD_V_DP) * 2f
    private val labelTextAscent: Float = labelTextPaint.fontMetrics.ascent

    /** 本帧已贴出的胶囊矩形，按优先级复用（不每帧新建对象）。 */
    private val labelRects = ArrayList<RectF>()

    // ──────────────────────────────────────────────────────────── 动画与手势状态

    private var animating = false
    private var lastFrameNanos = 0L

    /**
     * 全局彗星相位，恒在 `0..1` 回绕。每条弧上彗星的位置 = `(这个值 × 档位 + 错相) % 1`。
     *
     * 档位是**整数**，所以这里回绕（1 → 0）时"× 档位"恰好变化一个整数，取模后位置不变 ——
     * 否则所有彗星会在同一瞬间一起跳一下。
     */
    private var cometPhase = 0f

    /** 光晕呼吸相位，2.5s 一个来回。 */
    private var breathPhase = 0f

    private var touching = false
    private var claimed = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastMoveMs = 0L
    private var lastMoveDx = 0f
    private var flingDxPerSec = 0f
    private var inertiaRemainingMs = 0f

    /**
     * 缩放倍率，`1f` = 默认（球正好填满这块方形区域），上限 [MAX_ZOOM]。
     *
     * 它只放大**几何**：球面半径、海岸线、弧线、以及落点的投影位置。
     * 落点半径与城市名胶囊的字号仍按 dp 走、不跟着放大 —— 放大后要的正是"那一片看得更清"，
     * 把标记和文字一起放大只会让屏幕更快被同色填满，还会让读屏用的位置失去参照。
     */
    private var zoom = 1f

    /** 是否正在两指缩放。为真时不走旋转分支（双指下 `event.x` 只是第一根手指）。 */
    private var pinching = false
    private var pinchStartSpan = 0f
    private var pinchStartZoom = 1f

    /** 默认倍率的球面圆，放大后靠它裁掉溢出的部分（见 [onDraw]）。 */
    private val baseCirclePath = Path()

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!animating) {
                lastFrameNanos = 0L
                return
            }
            if (lastFrameNanos == 0L) lastFrameNanos = frameTimeNanos
            val dtSeconds = ((frameTimeNanos - lastFrameNanos) / 1_000_000_000.0)
                .coerceIn(0.0, MAX_FRAME_SECONDS)
                .toFloat()
            lastFrameNanos = frameTimeNanos

            advance(dtSeconds)
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        // 位图本身读屏念不出来，所以对外暴露成**一个带 contentDescription 的节点**：
        // 描述由 HomeFragment 在每次提交拓扑时改写（「地球上 N 个节点 · M 条活跃连接」），
        // 具体是哪些节点，面板下方的字段卡里有文本可读。
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        // 它是装饰件而不是控件：不抢焦点、不响应"点击"这种无障碍动作。
        isClickable = false
        isFocusable = false
    }

    // ──────────────────────────────────────────────────────────── 对外 API

    /**
     * 提交一份拓扑。传 null 表示"还没有数据"（只画球体与海岸线）。
     *
     * 第一次拿到 hub（或 hub 换了位置）时把相机**对准它** —— 打开面板第一眼就看见自己连的节点。
     */
    fun submitTopology(value: GlobeTopology?) {
        if (value == null || value.markers.isEmpty()) {
            markers = emptyList()
            arcs = emptyList()
            hubVector = null
            // 拓扑清空 = 选中的落点也没了。不收的话气泡会悬在一个已经不存在的点上。
            clearSelection()
            invalidate()
            return
        }

        markers = value.markers.map { marker ->
            RenderMarker(
                vector = GlobeProjection.unitVector(
                    marker.point.longitude.toFloat(),
                    marker.point.latitude.toFloat(),
                ),
                marker = marker,
            ).apply {
                labelText = labelOf(marker)
                labelText?.let { labelWidth = labelTextPaint.measureText(it) }
            }
        }
        // 弧的命中要映射到它的**终点落点**（弧没有独立于两个端点的信息），先建好坐标→下标的表。
        val markerIndexByPoint = HashMap<GeoPoint, Int>(markers.size)
        markers.forEachIndexed { index, item -> markerIndexByPoint[item.marker.point] = index }
        arcs = value.arcs.map { arc ->
            RenderArc(
                from = GlobeProjection.unitVector(arc.from.longitude.toFloat(), arc.from.latitude.toFloat()),
                to = GlobeProjection.unitVector(arc.to.longitude.toFloat(), arc.to.latitude.toFloat()),
                connectionCount = arc.connectionCount,
                bytesPerSecond = arc.bytesPerSecond,
                phase = cometStartPhase(arc.to),
                toMarkerIndex = markerIndexByPoint[arc.to] ?: -1,
            )
        }

        val hub = value.hub
        hubVector = hub?.let {
            GlobeProjection.unitVector(it.point.longitude.toFloat(), it.point.latitude.toFloat())
        }
        if (hub != null) {
            val hubLon = hub.point.longitude.toFloat()
            val hubLat = hub.point.latitude.toFloat()
                .coerceIn(-GlobeProjection.MAX_PITCH_DEG, GlobeProjection.MAX_PITCH_DEG)
            // 只在"没对准过"或 hub 真的换了地方时才搬相机，否则每次刷新都会跳一下。
            if (!aimedAtHub || hubLon != aimedLon || hubLat != aimedLat) {
                // 相机要搬家，选中的落点跟着球转走了 —— 气泡锚不准，先收掉。
                clearSelection()
                lonDeg = hubLon
                latDeg = hubLat
                aimedAtHub = true
                aimedLon = hubLon
                aimedLat = hubLat
                // 摆动的中心也跟着搬到新 hub，**且相位归零**：归零那一刻偏移为 0，
                // 于是"打开面板第一眼看见自己连的节点"这条契约不被摆动带偏。
                swayCenterLon = hubLon
                swayPhase = 0f
            }
        }

        // 选中按**落点坐标**当身份：排序会随连接数变，下标对不上。
        selectedIndex = if (selectedPoint == null) -1 else markers.indexOfFirst { it.marker.point == selectedPoint }
        if (selectedPoint != null && selectedIndex < 0) {
            // 落点消失了（连接结束、节点被移出订阅）→ 收掉气泡，别让它悬在半空。
            // selectedIndex 已经是 -1，emitSelection 发出 null，接线方据此收起气泡。
            selectedPoint = null
            emitSelection()
        }
        if (selectedIndex >= 0) {
            // 速率/连接数在变，气泡文案要跟着动。只改文本不重建视图（重建会闪）。
            emitSelection()
        }
        invalidate()
    }

    /**
     * 开/关自转。详情面板展开时开、收起时**必须关** ——
     * BottomSheet 收起后视图仍挂在窗口上，`onDetachedFromWindow` 不会触发，会一直空转烧电。
     */
    fun setAnimating(value: Boolean) {
        if (animating == value) return
        animating = value
        if (value) {
            lastFrameNanos = 0L
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    val isAnimating: Boolean get() = animating

    /** 当前缩放倍率（`1f` = 默认）。接线方用它决定右下角复位按钮的亮/暗。 */
    val zoomRatio: Float get() = zoom

    /**
     * 缩放倍率变化回调 —— 双指缩放与 [resetView] 都会走它。
     *
     * 复位按钮的状态挂在这上面：默认倍率下它没有可复位的东西，该压暗。
     */
    var onZoomChanged: ((Float) -> Unit)? = null

    /**
     * 复位视角：倍率收回 1、相机重新对准当前节点、顺带收掉点选气泡。
     *
     * 三件事必须一起做。只复位倍率的话，用户把球拖走再按一下会发现"什么都没归位"；
     * 摆动中心也一并搬回来并归零相位，于是复位后第一眼仍是 hub 在盘心（与打开面板时同一条契约）。
     */
    fun resetView() {
        clearSelection()
        inertiaRemainingMs = 0f
        flingDxPerSec = 0f
        if (aimedAtHub) {
            lonDeg = aimedLon
            latDeg = aimedLat
            swayCenterLon = aimedLon
            swayPhase = 0f
        }
        setZoom(1f)
        invalidate()
    }

    /** 夹到 `MIN_ZOOM..MAX_ZOOM` 并播出去。倍率没变就直接返回，免得每帧都白重绘一次。 */
    private fun setZoom(value: Float) {
        val next = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (next == zoom) return
        zoom = next
        invalidate()
        onZoomChanged?.invoke(zoom)
    }

    // ──────────────────────────────────────────────────────────── 尺寸

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        // 方形自适应：高度跟随宽度。外层显式写死高度（EXACTLY）时尊重它。
        val side = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) heightSize else widthSize
        setMeasuredDimension(widthSize, side)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animating = false
        lastFrameNanos = 0L
    }

    // ──────────────────────────────────────────────────────────── 手势

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                claimed = false
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                lastMoveMs = SystemClock.uptimeMillis()
                lastMoveDx = 0f
                flingDxPerSec = 0f
                inertiaRemainingMs = 0f
                // 先不抢：纵向拖要留给外面的 sheet / NestedScrollView。
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (pinching) {
                    // 双指：按指距比例改倍率。基准取自**第二指落下那一刻**的指距与倍率，
                    // 不是上一帧 —— 逐帧累乘会随着抖动一点点漂走。
                    val span = spanOf(event)
                    if (span > 0f && pinchStartSpan > 0f) {
                        setZoom(pinchStartZoom * (span / pinchStartSpan))
                    }
                    return true
                }
                if (!touching) return false
                val totalX = event.x - downX
                val totalY = event.y - downY
                if (!claimed) {
                    if (abs(totalX) > touchSlop && abs(totalX) > abs(totalY)) {
                        claimed = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    } else if (abs(totalY) > touchSlop) {
                        // 判定为纵向手势：彻底放手交给 sheet，转入惯性倒计时，
                        // 否则旋转会一直停着（我们再收不到 UP 事件了）。
                        touching = false
                        inertiaRemainingMs = INERTIA_MS
                        return false
                    }
                }
                if (claimed) {
                    val dx = event.x - lastX
                    rotateBy(dx, event.y - lastY)
                    val now = SystemClock.uptimeMillis()
                    val elapsed = (now - lastMoveMs).coerceAtLeast(MIN_SAMPLE_MS)
                    // 记录最近一次位移的方向与速度，供松手后的惯性使用。
                    lastMoveDx = dx
                    flingDxPerSec = -dx / (elapsed / 1000f)
                    lastMoveMs = now
                }
                lastX = event.x
                lastY = event.y
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 缩放结束时不能走"拖动"那条路：那会顺手清掉选中并甩一下惯性，
                // 而用户只是捏了两根手指，并没有想换角度。
                val wasPinch = pinching
                pinching = false
                pinchStartSpan = 0f
                val wasClaimed = claimed && !wasPinch
                val wasTap = event.actionMasked == MotionEvent.ACTION_UP && !claimed &&
                    abs(event.x - downX) <= touchSlop && abs(event.y - downY) <= touchSlop
                touching = false
                claimed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (wasClaimed) {
                    // 拖动 = 用户要换角度看，不是在看那个点 → 取消选中，摆动恢复。
                    if (selectedPoint != null) clearSelection()
                    // 松手后先是惯性，1.2s 后回到匀速自转。
                    inertiaRemainingMs = INERTIA_MS
                    // 最后一段几乎没动 → 当成"停住"，别用残留的老速度甩出去。
                    if (abs(lastMoveDx) < dp(FLING_MIN_DP)) flingDxPerSec = 0f
                } else if (wasTap) {
                    handleTap(event.x, event.y)
                }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 第二根手指落下 = 开始缩放。这里必须把事件抢过来（并一直攥到全部手指抬起）：
                // 让出去的话，两指一分一合会变成滚面板，球反而不动。
                val span = spanOf(event)
                if (span > 0f) {
                    pinching = true
                    claimed = true
                    touching = true
                    pinchStartSpan = span
                    pinchStartZoom = zoom
                    // 缩放期间不该再有旋转惯性：用户已经在做另一件事了。
                    inertiaRemainingMs = 0f
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 抬起一指：缩放到此为止，但手势还没结束（另一根手指还在屏幕上）。
                // 把 downX/downY 挪到剩下的那根手指上，免得它被当成"从第二指的位置拖了很远"。
                pinching = false
                pinchStartSpan = 0f
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 两指间距（缩放用）。指针对数不足时返回 0，调用方据此忽略这一次。 */
    private fun spanOf(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
    }

    private fun rotateBy(dx: Float, dy: Float) {
        lonDeg -= dx * ROTATE_DEG_PER_DP / density
        latDeg = (latDeg + dy * ROTATE_DEG_PER_DP / density)
            .coerceIn(-GlobeProjection.MAX_PITCH_DEG, GlobeProjection.MAX_PITCH_DEG)
        lonDeg = wrapLon(lonDeg)
    }

    // ──────────────────────────────────────────────────────────── 点选

    /** 一次点选的结果。[anchorX] / [anchorY] 是**本视图坐标系**里的落点，供气泡贴上去。 */
    data class Selection(
        val marker: GlobeMarker,
        val anchorX: Float,
        val anchorY: Float,
    )

    private fun handleTap(x: Float, y: Float) {
        val hit = hitTest(x, y)
        if (hit == null || hit.marker.point == selectedPoint) {
            // 点空白处、或再点同一个落点 = 收掉。后者是最常见的"我看完关掉"手势，不能漏。
            clearSelection()
            return
        }
        selectedPoint = hit.marker.point
        selectedIndex = markers.indexOfFirst { it.marker.point == selectedPoint }
        emitSelection()
        invalidate()
    }

    /** 清除选中并通知接线方。公开给接线方在面板收起 / 视图销毁时调用。 */
    fun clearSelection() {
        if (selectedPoint == null) return
        selectedPoint = null
        selectedIndex = -1
        emitSelection()
        invalidate()
    }

    private fun emitSelection() {
        val listener = onSelectionChanged ?: return
        if (selectedIndex < 0) {
            listener(null)
            return
        }
        // 相机状态是唯一真值来源，这里重建一次基，保证锚点与下一次 onDraw 完全一致。
        basis.set(lonDeg, latDeg)
        val anchor = markerAnchor(selectedIndex)
        listener(
            if (anchor == null) null else Selection(markers[selectedIndex].marker, anchor.first, anchor.second),
        )
    }

    /** 落点的屏幕位置（本视图坐标系，已含缩放）；转到背面返回 null（选中只该发生在正面）。 */
    private fun markerAnchor(index: Int): Pair<Float, Float>? {
        if (index < 0 || index >= markers.size) return null
        val side = min(width, height).toFloat()
        if (side <= 0f) return null
        val v = markers[index].vector
        if (v[0] * basis.zx + v[1] * basis.zy + v[2] * basis.zz < 0f) return null
        toScreen(v[0], v[1], v[2], width / 2f, height / 2f, side / 2f * SPHERE_FILL_RATIO * zoom)
        return projection[0] to projection[1]
    }

    /**
     * 命中测试：先点（目标多、半径小），再弧。弧的命中直接映射到它的**终点落点**。
     *
     * 落点的视觉半径只有 2~5dp，按它判命中手指永远点不中，所以给的是 Material 的最小触控半径。
     * 只在主线程调用：它复用 [arcScratch] / [projection]，而 [onDraw] 同样用它们 ——
     * 两者都在主线程上串行，不会互相踩。
     */
    internal fun hitTest(x: Float, y: Float): Selection? {
        if (markers.isEmpty() || width <= 0 || height <= 0) return null
        basis.set(lonDeg, latDeg)
        val cx = width / 2f
        val cy = height / 2f
        // 半径带上缩放：命中判据必须和绘制用的是同一个球面半径，否则放大后只有球心附近点得中。
        val radius = min(width, height) / 2f * SPHERE_FILL_RATIO * zoom
        val markerTouch = dp(MARKER_TOUCH_RADIUS_DP)

        var best = -1
        var bestDist = Float.MAX_VALUE
        for (i in markers.indices) {
            val v = markers[i].vector
            if (v[0] * basis.zx + v[1] * basis.zy + v[2] * basis.zz < 0f) continue
            toScreen(v[0], v[1], v[2], cx, cy, radius)
            val d = hypot(projection[0] - x, projection[1] - y)
            if (d <= markerTouch && d < bestDist) {
                bestDist = d
                best = i
            }
        }
        if (best >= 0) {
            val anchor = markerAnchor(best) ?: return null
            return Selection(markers[best].marker, anchor.first, anchor.second)
        }

        val arcTouch = dp(ARC_TOUCH_RADIUS_DP)
        var bestArc = -1
        bestDist = Float.MAX_VALUE
        for (i in arcs.indices) {
            val arc = arcs[i]
            if (arc.toMarkerIndex < 0) continue
            val count = GlobeProjection.sampleArc(arc.from, arc.to, arcScratch)
            var prevX = 0f
            var prevY = 0f
            var started = false
            for (j in 0 until count) {
                val vx = arcScratch[j * 3]
                val vy = arcScratch[j * 3 + 1]
                val vz = arcScratch[j * 3 + 2]
                val visible = (vx * basis.zx + vy * basis.zy + vz * basis.zz) >= 0f
                if (!visible) {
                    // 跨到背面必须断开，否则会算出一条"穿过地球"的假距离。
                    started = false
                    continue
                }
                toScreen(vx, vy, vz, cx, cy, radius)
                val px = projection[0]
                val py = projection[1]
                if (started) {
                    val d = pointSegmentDistance(x, y, prevX, prevY, px, py)
                    if (d <= arcTouch && d < bestDist) {
                        bestDist = d
                        bestArc = i
                    }
                }
                prevX = px
                prevY = py
                started = true
            }
        }
        if (bestArc < 0) return null
        val markerIndex = arcs[bestArc].toMarkerIndex
        val anchor = markerAnchor(markerIndex) ?: return null
        return Selection(markers[markerIndex].marker, anchor.first, anchor.second)
    }

    /** 点到线段的最短距离。线段退化成点时按点距算。 */
    private fun pointSegmentDistance(
        px: Float, py: Float,
        ax: Float, ay: Float,
        bx: Float, by: Float,
    ): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 <= 0f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + dx * t), py - (ay + dy * t))
    }

    /**
     * 推进一帧：相位前进 + 自转/惯性结算。
     *
     * `internal` 而不是 `private`：测试需要**确定性地**走帧。走 Choreographer + `idleFor` 这条路
     * 在这里走不通 —— 帧回调会自我重投且延迟为零，消息队列永远不空，`idleFor` 到不了目标时刻，
     * 测试会挂住（实测 5 分钟不返回）。直接喂 dt 既快又和时钟无关。
     */
    internal fun advance(dtSeconds: Float) {
        cometPhase += dtSeconds * COMET_CYCLES_PER_SEC
        if (cometPhase >= 1f) cometPhase -= 1f
        breathPhase += dtSeconds * BREATH_CYCLES_PER_SEC
        if (breathPhase >= 1f) breathPhase -= 1f

        if (touching) return

        if (selectedPoint != null) {
            // 选中期间相机冻结：气泡锚在被点的落点上，球一晃气泡就飘，既点不准也看不清。
            // 彗星相位在上面已经推进过了 —— "数据还在流"这件事不该跟着冻结。
            return
        }

        if (inertiaRemainingMs > 0f) {
            inertiaRemainingMs -= dtSeconds * 1000f
            // ⚠️ 单位必须换算成角度：`flingDxPerSec` 是**像素/秒**，而 lonDeg 是度。
            // 不换算就是"把 px 当 deg 用"，惯性比手指快 `density / ROTATE_DEG_PER_DP` 倍
            // （xhdpi 5 倍、xxhdpi 7.5 倍）—— 症状是松手后球疯转几圈再停在一个跟手指
            // 完全无关的经度上。换算因子与 [rotateBy] 同一条，惯性才真的"接着手指的速度走"。
            lonDeg = wrapLon(lonDeg + flingDxPerSec * ROTATE_DEG_PER_DP / density * dtSeconds)
            flingDxPerSec *= (1f - min(1f, dtSeconds * INERTIA_DECAY_PER_SEC))
            // 只在惯性真的走完的这一帧锚定：早一帧会把还没走完的位移丢掉，画面会顿一下。
            if (inertiaRemainingMs <= 0f) anchorSwayToCamera()
            return
        }

        swayPhase += dtSeconds / SWAY_PERIOD_SEC
        if (swayPhase >= 1f) swayPhase -= 1f
        lonDeg = wrapLon(swayCenterLon + SWAY_DEG * sin(TWO_PI * swayPhase))
    }

    /**
     * 把摆动中心锚到**相机当前所在的位置**，并把相位归零（此刻偏移恰为 0 ⇒ 画面不跳）。
     *
     * 惯性结束后由此接管自转。这条是"用户拖到哪儿就绕哪儿摆"的实现 ——
     * 不锚定的话，松手瞬间球会被拽回 hub 或初始角度。
     */
    private fun anchorSwayToCamera() {
        swayCenterLon = lonDeg
        swayPhase = 0f
    }

    private fun wrapLon(value: Float): Float = when {
        value > 180f -> value - 360f
        value < -180f -> value + 360f
        else -> value
    }

    // ──────────────────────────────────────────────────────────── 绘制

    override fun onDraw(canvas: Canvas) {
        val side = min(width, height).toFloat()
        if (side <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = side / 2f * SPHERE_FILL_RATIO
        val radius = baseRadius * zoom

        basis.set(lonDeg, latDeg)

        // 放大后球面会溢出这块方形区域 —— 按**默认倍率的圆**裁一刀：溢出的部分被切掉，
        // 看到的就是"凑近看球面"的那一块（球缘与描边也随之退到视野之外，正好）。
        // 倍率为 1 时不裁：星空模式的太空底色与星野是铺满整屏的，裁圆会把它们切出一个圈。
        if (zoom > 1f) {
            baseCirclePath.reset()
            baseCirclePath.addCircle(cx, cy, baseRadius, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(baseCirclePath)
        }

        if (starryMode) {
            // 近黑太空铺满视图，再叠一层星野（随相机经度视差平移，跟着地球转）。
            canvas.drawColor(SPACE_BG)
            drawStars(canvas)
            // 直射点只算一次，晨昏线压暗与夜景灯光共用同一个方向。
            updateSubsolar()
        } else {
            // 扁平模式也要铺一层**不透明**托盘，两个理由：
            // ① 海 / 陆 / 海岸线都是半透明淡色，本来就靠背后这层当底；
            // ② 以前这里是 `drawColor(TRANSPARENT, CLEAR)`：托盘连同方框一起没了，用户看到的就是
            //    "关掉星空后背景变成透明的"。顺带一提，CLEAR 原本是为了治"切换模式后残留上一帧
            //    黑底与星点"才加的 —— 铺一层不透明色同样治得了，还不会把方框擦没。
            canvas.drawColor(colorTray)
        }
        // 球面高光两种模式都要：少了它，扁平模式就是一枚死平的色饼，完全没有"球"的感觉。
        oceanPaint.shader = oceanShader(cx, cy, radius)

        val composite = if (starryMode) ensureCompositeTexture() else null
        if (starryMode && composite != null) {
            // 昼夜已经**烘进这一张**贴图里了：球面只画一次网格，不存在"每帧重新判定哪块是
            // 夜面、再裁出区域"这回事 —— 转动当然不可能翻面（见 [DayNightCompositor]）。
            drawComposite(canvas, cx, cy, radius, composite)
        } else {
            canvas.drawCircle(cx, cy, radius, oceanPaint)
            drawCoastline(canvas, cx, cy, radius)
            // 矢量回退也要有昼夜感（仅星空模式）：叠一层逐纹素烘出来的纯色夜空遮罩。
            if (starryMode) drawNightMask(canvas, cx, cy, radius)
        }
        canvas.drawCircle(cx, cy, radius, rimPaint)
        drawArcs(canvas, cx, cy, radius)
        drawMarkers(canvas, cx, cy, radius)
        drawLabels(canvas)
        // 彗星头压在所有东西最上：hub 的胶囊立在自己的弧扇上，头若被胶囊盖住，
        // "每条弧都在动"的契约每圈就断一次。头是小亮圆点，浮在字上也读得清。
        drawCometHeads(canvas)

        if (zoom > 1f) canvas.restore()
    }

    /**
     * 海洋径向高光：左上方向的一块反光 + 右下压暗，用来营造球体感。半径变化时重建，否则复用缓存。
     *
     * 高光与暗部都**从 [Palette.ocean] 自己推导**（往白/往黑混），不写死某个色：扁平模式的海洋底色
     * 是琥珀系、星空（矢量回退）是深蓝，同一支渐变得在两种底色上都成立 —— 早先高光是往一个写死的
     * 蓝(#3B6EA5)混的，套到琥珀底色上会直接灰掉。
     */
    private fun oceanShader(cx: Float, cy: Float, radius: Float): Shader {
        if (oceanShaderRadius != radius) {
            oceanShaderRadius = radius
            val base = palette.ocean
            oceanPaint.shader = RadialGradient(
                cx - radius * 0.35f, cy - radius * 0.35f, radius * 1.35f,
                intArrayOf(
                    blend(base, Color.WHITE, 0.45f),
                    base,
                    blend(base, Color.BLACK, 0.42f),
                ),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
            )
        }
        return oceanPaint.shader!!
    }

    /** 生成/复用星野位图：种子固定（每次布局一致），尺寸变化或首次进入星空模式才重建。 */
    private fun ensureStarBitmap() {
        if (!starDirty && starBitmap != null) return
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val rnd = java.util.Random(0x577E77L)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val count = (w * h / 9000f).toInt().coerceIn(60, 220)
        for (i in 0 until count) {
            val x = rnd.nextFloat() * w
            val y = rnd.nextFloat() * h
            val r = 0.4f + rnd.nextFloat() * 1.1f
            paint.alpha = (70 + rnd.nextInt(185))
            c.drawCircle(x, y, r * density, paint)
        }
        starBitmap?.recycle()
        starBitmap = bmp
        starDirty = false
    }

    /** 两色按 [fraction] 线性混合（星空高光/暗边用，避免每帧建色）。 */
    private fun blend(from: Int, to: Int, fraction: Float): Int {
        val inv = 1f - fraction
        fun ch(s: (Int) -> Int) = (s(from) * inv + s(to) * fraction).toInt().coerceIn(0, 255)
        return Color.argb(ch { Color.alpha(it) }, ch { Color.red(it) }, ch { Color.green(it) }, ch { Color.blue(it) })
    }

    /**
     * 星野随相机经度做视差平移（地球转、星空跟着动），横向铺两遍做无缝环绕。
     * 有真实星空贴图（geo/stars.jpg，equirectangular）时按 cover 缩放直接画贴图；
     * 缺贴图回退程序化星野位图。
     */
    private fun drawStars(canvas: Canvas) {
        val tex = starsTexture
        if (tex == null) {
            drawProceduralStars(canvas)
            return
        }
        val scale = max(width / tex.width.toFloat(), height / tex.height.toFloat())
        val drawW = tex.width * scale
        val drawH = tex.height * scale
        val top = (height - drawH) / 2f
        // lonDeg 摆动 ±SWAY_DEG 映射到星野水平位移；一整圈贴图当环绕周期，位移量取小比例更自然。
        var shift = ((lonDeg / 360f) * drawW * 3f) % drawW
        if (shift < 0f) shift += drawW
        texturePaint.isFilterBitmap = true
        starRect.set(-shift, top, -shift + drawW, top + drawH)
        canvas.drawBitmap(tex, null, starRect, texturePaint)
        starRect.set(-shift + drawW, top, -shift + 2f * drawW, top + drawH)
        canvas.drawBitmap(tex, null, starRect, texturePaint)
    }

    private val starRect = RectF()

    /** 程序化星野回退：生成/复用星野位图（种子固定，每次布局一致），尺寸变化或首次进入星空模式才重建。 */
    private fun drawProceduralStars(canvas: Canvas) {
        ensureStarBitmap()
        val bmp = starBitmap ?: return
        val w = bmp.width.toFloat()
        // lonDeg 摆动 ±SWAY_DEG 映射到星野水平位移；整屏宽当一圈，位移量取小比例更自然。
        val shift = ((lonDeg / 360f) * w * 3f) % w
        canvas.drawBitmap(bmp, -shift, 0f, null)
        canvas.drawBitmap(bmp, -shift + w, 0f, null)
    }

    /**
     * 太阳直射点（经纬度，度）。用 UTC 日期粗算赤纬与时角，精度到"哪个半球偏亮"足够：
     * 赤纬随季节 ±23.44°，时角随一天 24h 扫 360°。不追求分秒级（地球仪观感用途）。
     */
    private fun subsolarPoint(nowMillis: Long): Pair<Float, Float> {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = nowMillis }
        val dayOfYear = cal.get(java.util.Calendar.DAY_OF_YEAR)
        val hourUtc = cal.get(java.util.Calendar.HOUR_OF_DAY) + cal.get(java.util.Calendar.MINUTE) / 60f
        val declination = -23.44f * kotlin.math.cos(Math.toRadians((360.0 / 365.24) * (dayOfYear + 10)).toFloat())
        val subsolarLon = (12f - hourUtc) * 15f   // 正午直射点经度≈0（忽略均时差）
        return declination to subsolarLon
    }

    /**
     * 记下太阳直射点经纬度，星空模式每帧一次。存的是**方向**而不是屏幕投影 —— 晨昏线的
     * 形状是球面几何，跟相机姿态无关，只有落到屏幕上那一步才用得着 [basis]。
     */
    private fun updateSubsolar() {
        val (lat, lon) = subsolarPoint(sunEpochMillis ?: System.currentTimeMillis())
        subsolarLatDeg = lat
        subsolarLonDeg = lon
    }

    // ──────────────────────────────────────────────────────────── 昼夜（逐纹素合成）

    /** 球面贴图与夜空遮罩共用的裁圆 Path。 */
    private val compositeClipPath = Path()

    /** 夜景贴图缺失时的整张替身缓存的数组（只会在资源坏掉时用到）。 */
    private var solidNightPixels: IntArray? = null

    /**
     * 相机经度 → **贴图锚点**（贴图第 0 列对应的世界经度），量化到 [ROLL_STEP_DEG] 的整数倍。
     *
     * 取"相机对足点"是因为贴图上有条缝（第 0 列与第 W−1 列在内容上并不相邻），
     * 而 [Canvas.drawBitmapMesh] 的贴图坐标是**隐式均匀网格**（第 col 列恒取 `col/meshWidth`，
     * 没有 UV 参数），背面网格列只能靠"整段塌到相邻正面列、退化成零面积"来隐藏 ——
     * 这就要求缝必须落在**背面**（否则背面会成一段横跨盘面的带子，把贴图糊满正面）。
     * 既然贴图是我们自己烘的，烘的时候直接把缝转到对足点即可，见 [DayNightCompositor.offsetPxFor]。
     *
     * 量化到 30°：缝只要落在背面（离对足点 90° 以内都算）就够了，于是拖动/摆动时锚点极少变，
     * 极少重烘。真正用于建网格的经度由**实际用的整数列偏移**反推，所以对位不损失精度。
     */
    private fun anchorLonFor(cameraLonDeg: Float, width: Int): Float {
        if (width <= 0) return -180f
        val target = ((cameraLonDeg + 180f) / ROLL_STEP_DEG).roundToInt() * ROLL_STEP_DEG
        return DayNightCompositor.anchorLonDeg(
            DayNightCompositor.offsetPxFor(target, width),
            width,
        )
    }

    /** 把角度量化到 [stepDeg] 的整数倍。 */
    private fun quantizeDeg(deg: Float, stepDeg: Float): Float =
        (deg / stepDeg).roundToInt() * stepDeg

    /** 夜景贴图缺失时的替身：整张 [NIGHT_TINT]，好让合成只有一条代码路径。 */
    private fun ensureSolidNightPixels(n: Int): IntArray =
        solidNightPixels ?: IntArray(n) { NIGHT_TINT }.also { solidNightPixels = it }

    /**
     * 烘焙"**此刻的晨昏贴图**"：把 day / night 两张等距圆柱贴图按 `N·S` 逐纹素混成一张。
     *
     * ## 为什么这样做就没有"闪"
     * 相机姿态**不是**烘焙的入参。同一时刻、同一相机经度，烘出来的永远是同一张图；
     * 而"哪个纹素偏亮"只由 `N·S` 决定 —— `N` 是纹素自己的球面法线（贴图上钉死的），
     * `S` 是太阳方向。转动地球只是把这张图**整体投影到不同的屏幕位置**，
     * 不可能出现"这一帧整块翻到另一边"。旧实现每帧重新算"夜面区域 ∩ 可见半球"，
     * 而 θ=90° 那层在数值上退化（交点对径 ⇒ 视界补弧的方向由浮点零的符号决定），
     * 于是每帧在两种错误形状间跳 —— 那才是用户看到的"一闪一闪"。
     *
     * ## 缓存
     * 太阳位置量化到 [SUN_STEP_DEG]（0.5° ≈ 2 分钟）、锚点量化到 [ROLL_STEP_DEG]，
     * 三者都没跨台阶时直接复用上一张。实测一次 1024×512 烘焙约 1.6ms（JVM），
     * 所以即便拖动时频繁跨锚点台阶也不会掉帧。
     *
     * 返回 null 表示没有白天贴图 ⇒ 调用方走矢量海岸线 + [drawNightMask] 回退。
     */
    private fun ensureCompositeTexture(): Bitmap? {
        val day = dayPixels ?: return null
        val w = textureWidth
        val h = textureHeight
        if (w <= 0 || h <= 0) return null
        val night = nightPixels ?: ensureSolidNightPixels(w * h)

        val anchor = anchorLonFor(lonDeg, w)
        val sunLon = quantizeDeg(subsolarLonDeg, SUN_STEP_DEG)
        val sunLat = quantizeDeg(subsolarLatDeg, SUN_STEP_DEG)
        val cached = compositeBitmap
        if (cached != null && anchor == compositeAnchorLon &&
            sunLon == compositeSunLon && sunLat == compositeSunLat
        ) {
            return cached
        }

        val bmp = cached?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val out = compositePixels?.takeIf { it.size >= w * h }
            ?: IntArray(w * h).also { compositePixels = it }
        DayNightCompositor.compose(day, night, w, h, sunLon, sunLat, anchor, out)
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        compositeBitmap = bmp
        compositeAnchorLon = anchor
        compositeSunLon = sunLon
        compositeSunLat = sunLat
        return bmp
    }

    /**
     * 把烘好的晨昏贴图铺上球面：一次 [Canvas.drawBitmapMesh]。
     *
     * ⚠️ 网格锚点（[compositeAnchorLon]）必须与烘焙时用的那个**完全一致**：差一个量化台阶，
     * 贴图就会整体转 30°。不崩、不报错，只是大陆跑到别处去 —— 这条由
     * `滚动贴图在非退化相机经度下仍然对位` 从像素上钉着。
     */
    private fun drawComposite(canvas: Canvas, cx: Float, cy: Float, radius: Float, tex: Bitmap) {
        canvas.save()
        compositeClipPath.reset()
        compositeClipPath.addCircle(cx, cy, radius, Path.Direction.CW)
        canvas.clipPath(compositeClipPath)
        buildTextureMesh(cx, cy, radius, compositeAnchorLon)
        texturePaint.isFilterBitmap = true
        canvas.drawBitmapMesh(tex, MESH_COLS, MESH_ROWS, meshVerts, 0, null, 0, texturePaint)
        canvas.restore()
    }

    /**
     * 矢量回退用的**纯色夜空遮罩**：白昼侧 alpha = 0，夜面按"进入多深"渐浓，
     * 让海岸线还能从夜色里隐约透出来。
     *
     * 它和贴图版共用同一套"逐纹素按 `N·S` 判定"的思路。旧实现是把夜面裁成 5 层嵌套区域
     * 再逐层叠实色 —— 同样会随转动翻面，理由见 [ensureCompositeTexture] 与 [DayNightCompositor]。
     *
     * 尺寸刻意小（[NIGHT_MASK_W]×[NIGHT_MASK_H]）：遮罩只是一层平滑渐变，没有细节要保留，
     * 而它本来就是按 cover 放大铺上去的。
     */
    private fun ensureNightMask(): Bitmap? {
        val w = NIGHT_MASK_W
        val h = NIGHT_MASK_H
        val anchor = anchorLonFor(lonDeg, w)
        val sunLon = quantizeDeg(subsolarLonDeg, SUN_STEP_DEG)
        val sunLat = quantizeDeg(subsolarLatDeg, SUN_STEP_DEG)
        val cached = nightMaskBitmap
        if (cached != null && anchor == nightMaskAnchorLon &&
            sunLon == nightMaskSunLon && sunLat == nightMaskSunLat
        ) {
            return cached
        }

        val bmp = cached?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val out = nightMaskPixels?.takeIf { it.size >= w * h }
            ?: IntArray(w * h).also { nightMaskPixels = it }
        DayNightCompositor.composeTint(
            w = w, h = h,
            sunLonDeg = sunLon, sunLatDeg = sunLat,
            anchorLonDeg = anchor,
            tint = NIGHT_TINT,
            out = out,
            maxAlpha = NIGHT_MASK_MAX_ALPHA,
        )
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        nightMaskBitmap = bmp
        nightMaskAnchorLon = anchor
        nightMaskSunLon = sunLon
        nightMaskSunLat = sunLat
        return bmp
    }

    private fun drawNightMask(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val mask = ensureNightMask() ?: return
        canvas.save()
        compositeClipPath.reset()
        compositeClipPath.addCircle(cx, cy, radius, Path.Direction.CW)
        canvas.clipPath(compositeClipPath)
        buildTextureMesh(cx, cy, radius, nightMaskAnchorLon)
        texturePaint.isFilterBitmap = true
        canvas.drawBitmapMesh(mask, MESH_COLS, MESH_ROWS, meshVerts, 0, null, 0, texturePaint)
        canvas.restore()
    }

    // ── 贴图球面网格的复用缓冲（行主序，`drawBitmapMesh` 要的顺序） ──

    /** [GlobeProjection.unitVector] / [GlobeProjection.project] 的输出 scratch。 */
    private val meshVector = FloatArray(3)

    /** 网格顶点（屏幕坐标，x,y 交错）。行主序：`[(row * (MESH_COLS + 1) + col) * 2]`。 */
    private val meshVerts = FloatArray((MESH_COLS + 1) * (MESH_ROWS + 1) * 2)

    /**
     * 逐行算网格用的 scratch：投影出的 (right, up) 与"这一列在不在正面"。
     * 必须先整行算完再写顶点 —— 背面列的落点要**按整行的正面区间**插值（见 [buildTextureMesh]）。
     */
    private val meshRight = FloatArray(MESH_COLS + 1)
    private val meshUp = FloatArray(MESH_COLS + 1)
    private val meshFront = BooleanArray(MESH_COLS + 1)

    // ── 贴图锚点 ──
    //
    // 历史实现这里是"把位图沿经度滚一圈"：`ensureRolledTexture` 按目标经度做一次循环移位，
    // 按源图分两格缓存（白天/夜景各一格），每张各画两笔（`-offPx` 与 `W-offPx`）。
    //
    // 现在**没有这一步了** —— 贴图是我们自己逐纹素烘的（见 [ensureCompositeTexture]），
    // 烘的时候就让第 0 列落在 [anchorLonFor] 给出的世界经度上，于是"循环移位"和"逐纹素合成"
    // 合并成同一次遍历：少一次 2MB 位图拷贝，也少一整套失效/分槽逻辑。
    // 接缝仍在相机对足点（背面），背面临界列仍靠"整段塌到相邻正面列"退化成零面积 ——
    // 这套约定没变，见 [buildTextureMesh] 与 [writeTextureRow]。

    /**
     * 按当前相机姿态铺一遍网格，行主序写进 [meshVerts]（屏幕坐标）。
     *
     * [anchorLonDeg] 必须与烘焙那张贴图时用的锚点**完全一致**（见 [anchorLonFor]）：第 col 列的
     * **世界经度**就是 `anchorLonDeg + 360·col/MESH_COLS`，它同时决定"这一列取到贴图哪个位置"
     * （隐式 u = col/MESH_COLS ⇒ 贴图上正好也是同一条经线）。
     *
     * ## 网格为什么必须铺满整张 360°
     * `Canvas.drawBitmapMesh` **没有 UV 参数**，贴图坐标是隐式均匀网格：第 col 列的 u 恒为
     * `col / meshWidth`，跟顶点被挪到哪儿毫无关系。所以"某一列取到贴图哪个位置"只能由
     * **列号**决定 —— 网格必须整张 360° 宽、按世界经度锚定（第 col 列的 `col / MESH_COLS`
     * 恰好就是贴图上的经度）。只铺可见的 180° 会让 `col / meshWidth` 把整张贴图压进正面半球
     * （全球地图糊在球心）。这条有回归测试盯着：
     * `drawBitmapMesh 的贴图坐标是隐式均匀网格_不随顶点位置移动`。
     *
     * ## 背面怎么处理
     * 背面（`v·forward < 0`）的点在正交投影下不可见，但隐式网格不能"抽掉"列。
     * 做法是把每一段**数组里连续**的背面列，整段塌到与它相邻的那个正面边界列的**同一个位置**上：
     * 段内四边形四个角两两重合 ⇒ 面积恒为 0，画了等于没画（见 [writeTextureRow]）。详见 [writeTextureRow]。
     */
    private fun buildTextureMesh(cx: Float, cy: Float, radius: Float, anchorLonDeg: Float) {
        val total = MESH_COLS + 1
        var k = 0
        for (row in 0..MESH_ROWS) {
            val lat = 90f - 180f * row / MESH_ROWS
            // 整行先投一遍：背面列的落点要按**整行的正面区间**来插值，不能边投边写。
            var frontCount = 0
            var deepest = 0
            var deepestDepth = -2f
            for (col in 0 until total) {
                val lon = anchorLonDeg + 360f * col / MESH_COLS
                GlobeProjection.unitVector(lon, lat, meshVector, 0)
                GlobeProjection.project(
                    meshVector[0], meshVector[1], meshVector[2], basis, projection, 0,
                )
                meshRight[col] = projection[0]
                meshUp[col] = projection[1]
                // ⚠️ 判据要留负容差：极点那一行整行的 depth 都是 cos 90° 的浮点尾巴（≈±1e-17），
                // 严格 `> 0` 会让"正/背面"由噪声决定，把极点整行推到视界上去。
                meshFront[col] = projection[2] > -BACK_EPS
                if (meshFront[col]) frontCount++
                if (projection[2] > deepestDepth) {
                    deepestDepth = projection[2]
                    deepest = col
                }
            }
            k = writeTextureRow(k, total, frontCount, deepest, lat, cx, cy, radius)
        }
    }

    /**
     * 写一行的顶点：正面列用真实投影位置，背面列**整段塌到相邻的正面边界列**上。返回新的写指针。
     *
     * ## 背面列为什么只能"塌成零面积"
     * 正交投影把背面各点投在盘**内**，而隐式网格不能抽掉列，所以背面列必须被安置到某个位置。
     * 唯一安全的去处是"让它跟左右邻居重合"——四边形四角只剩两个位置 ⇒ 面积恒为 0 ⇒ 一条边都不画
     * （零面积不画有回归测试钉着：`零面积格子画不画`）。反过来，只要有一列背面顶点被摆到别的方向
     * ——哪怕只是相邻两行摆向了盘的两侧——它们之间那个四边形就会**横跨整个盘面**，
     * 把接缝那一行的贴图糊满正面：观感就是"北半球整块陆地被太平洋盖掉"。
     *
     * 历史写法踩的正是这个坑：把背面列的方位角从 `az(last)` 线性插值到 `az(first)`。方位角本身没错，
     * 但那两个角在相机"正对的那一行"（行内存在一个 `(right, up) ≈ (0, 0)` 的对跖列）恰好相差 180°，
     * `atan2(sin, cos)` 归一化后走 +180° 还是 −180° 完全由浮点噪声决定；于是相邻两行各自朝盘的
     * 另一侧绕，夹在中间的那个四边形就成了横跨盘面的"带"。
     *
     * ## 现在的做法
     * 直接从数组结构上消灭这个可能：背面列被**数组**（注意不是环）切成两段 ——
     * `[0, first-1]` 紧挨 `first`、`[last+1, MESH_COLS]` 紧挨 `last` —— 各自塌到贴着的那一列上。
     * 段内每个四边形四角重合；两个接缝四边形是"正面边界列 vs 塌到它身上的背面列"，四角同样只剩两个
     * 位置；而这两段之间**根本没有四边形**（列 0 与列 MESH_COLS 在网格里不构成格子，网格不环绕）。
     * 于是整行背面一个像素都不画，且与相机姿态无关：没有方位角、没有插值、没有 ±180° 的符号歧义。
     *
     * ⚠️ 前提是正面列在数组里必须是**一整段**（背面才是首尾两段）。接缝若落在正面里，背面就成了一段、
     * 两端各贴一个正面列，其中一头必然跨盘。所以贴图要烘成"缝落在相机对足点上"—— 见 [anchorLonFor]。
     */
    private fun writeTextureRow(
        k0: Int,
        total: Int,
        frontCount: Int,
        deepest: Int,
        lat: Float,
        cx: Float,
        cy: Float,
        radius: Float,
    ): Int {
        var k = k0
        when {
            // 整行都在背面（正对着另一极时的极冠）：整行塌成一个视界点，四角两两重合 ⇒ 面积恒 0。
            frontCount == 0 -> {
                var right = meshRight[deepest]
                var up = meshUp[deepest]
                val len = hypot(right, up)
                if (len < 1e-4f) {
                    right = 0f
                    up = if (lat >= 0f) 1f else -1f
                } else {
                    right /= len
                    up /= len
                }
                repeat(total) {
                    putVertex(k, right, up, cx, cy, radius)
                    k += 2
                }
            }
            // 整行都在正面（凑近看本极那一带会出现）：照实投。
            frontCount == total -> for (col in 0 until total) {
                putVertex(k, meshRight[col], meshUp[col], cx, cy, radius)
                k += 2
            }
            else -> {
                val p = meshFront.indexOfFirst { it }
                var first = p
                var last = p
                // 从任意一个正面列出发，两个方向各走一遍就是这段环绕区间的两端。
                // 走法是"是正面列才挪"，越界那一步自然停住；repeat 只用来封顶，防死循环。
                repeat(frontCount) {
                    if (meshFront[(first - 1 + total) % total]) first = (first - 1 + total) % total
                }
                repeat(frontCount) {
                    if (meshFront[(last + 1) % total]) last = (last + 1) % total
                }
                for (col in 0 until total) {
                    if (meshFront[col]) {
                        putVertex(k, meshRight[col], meshUp[col], cx, cy, radius)
                    } else {
                        // 背面列整段塌到**它在数组里挨着的那个正面边界列**上（col < first 的那段贴
                        // first，col > last 的那段贴 last）。于是这段里每一个四边形 —— 包括与正面
                        // 相接的那两个 —— 四角只剩两个位置，面积恒为 0 ⇒ 一条边都不画（零面积不画
                        // 有回归测试钉着，见 `零面积格子画不画`）。
                        val anchor = if (col < first) first else last
                        putVertex(k, meshRight[anchor], meshUp[anchor], cx, cy, radius)
                    }
                    k += 2
                }
            }
        }
        return k
    }

    /** 写一个顶点：`(right, up)` 是正交投影出的屏幕方向（单位 = 球半径）。 */
    private fun putVertex(index: Int, right: Float, up: Float, cx: Float, cy: Float, radius: Float) {
        meshVerts[index] = cx + right * radius
        // 屏幕 y 轴朝下、球面 y 轴朝北 → 取负。
        meshVerts[index + 1] = cy - up * radius
    }

    /**
     * 仅供测试：按当前相机姿态算一遍网格，返回**整张**网格顶点（行主序 x,y 交错）。
     *
     * 抽出来是为了能用**纯几何**断言"贴图真的按球面投上去了"：正交投影下相邻经线的屏幕间距
     * 必须按 cos 因子收向两侧视界（球缘处趋于 0），而平面裁窗处处等距；同时列必须按世界经度
     * 锚定。这些判据在像素上做不干净（抗锯齿、贴图内容都会干扰），在顶点上则是精确可判的。
     *
     * 用的锚点与真正绘制时**同一个**（[debugAnchorLonDeg]），所以断言里要拿它去换算世界经度。
     */
    internal fun debugMeshVerts(): FloatArray {
        val side = min(width, height).toFloat()
        basis.set(lonDeg, latDeg)
        buildTextureMesh(
            width / 2f, height / 2f, side / 2f * SPHERE_FILL_RATIO * zoom, debugAnchorLonDeg(),
        )
        return meshVerts.copyOf()
    }

    /**
     * 仅供测试：这一帧铺网格/烘贴图用的锚点经度（贴图第 0 列对应的世界经度）。
     *
     * 暴露出来是因为测试不能把锚点当成 −180° 来硬编码：锚点跟着相机经度走
     * （缝要落在相机对足点上，见 [anchorLonFor]），测试得按**实际锚点**去换算
     * "第 col 列的世界经度"，否则会把自己算错的那 30° 当成贴图错位。
     */
    internal fun debugAnchorLonDeg(): Float =
        anchorLonFor(lonDeg, if (textureWidth > 0) textureWidth else MESH_COLS)

    /**
     * 仅供测试：当前**相机经度**（度）。
     *
     * 动效判据要量的是"这一帧转了多少度"（惯性、摆动），这在像素上量不出来（亚度级角位移
     * 落在一两个纹素以内），只能从这里读 —— 见 `松手后的惯性角速度与手指拖动的角速度一致`。
     */
    internal fun debugCameraLonDeg(): Float = lonDeg

    /**
     * 懒加载 assets/geo/ 三张贴图（缺哪张回退哪张：白天缺 → 矢量海岸线，夜景缺 → 纯色夜面，
     * 星空缺 → 程序化星野）。只在星空模式首次需要时各读一次。
     *
     * ## 三张图三种解码方式（**不能统一**）
     * - 白天 / 夜景：`inSampleSize = 2` 盒式平均降到 1024×512。它们是大面积同色的照片贴图，
     *   降采样只省内存、不掉信息，而且合成阶段本来就是逐纹素重采样，全尺寸毫无收益。
     * - 星空：**整尺寸解码**再取块最大值降采样（[StarfieldResampler]）。星野是点光源，
     *   盒式平均会把 1~2px 的星压掉 3/4 —— 实测就是"星空看着是纯黑的"。
     */
    private fun ensureTextures() {
        if (texturesLoaded) return
        texturesLoaded = true

        decodeTexturePixels("geo/earth_day.webp", inSampleSize = 2)?.let {
            dayPixels = it.pixels
            textureWidth = it.width
            textureHeight = it.height
        }

        nightPixels = decodeTexturePixels("geo/earth_night.webp", inSampleSize = 2)?.let {
            // 城市灯光增益只做一次（它跟太阳位置无关），烘的时候直接用。
            DayNightCompositor.boostNightLights(it.pixels)
            it.pixels
        }
        starsTexture = decodeStars()
    }

    /** 解码出来的一张等距圆柱贴图：像素 + 尺寸。 */
    private class TexelData(val pixels: IntArray, val width: Int, val height: Int)

    /** 解码成 ARGB 像素数组并**立刻回收位图** —— 这里要的是数据，不是 Bitmap。 */
    private fun decodeTexturePixels(path: String, inSampleSize: Int): TexelData? = runCatching {
        val options = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        val bmp = context.assets.open(path).use { BitmapFactory.decodeStream(it, null, options) }
            ?: return@runCatching null
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        bmp.recycle()
        TexelData(pixels, w, h)
    }.getOrNull()

    /**
     * 星空贴图：整尺寸解码 → 取块最大值降一半 → 增益 → 回收源位图。
     *
     * 走这条"绕路"而不是 `inSampleSize = 2`，是因为星野是**点光源**：盒式平均会把一颗
     * 1~2px 的星按面积压掉，实测盘外背景平均亮度只剩 0.093/255。详见 [StarfieldResampler]。
     */
    private fun decodeStars(): Bitmap? = runCatching {
        val src = context.assets.open("geo/stars.jpg").use { BitmapFactory.decodeStream(it, null, null) }
            ?: return@runCatching null
        val dw = (src.width / 2).coerceAtLeast(1)
        val dh = (src.height / 2).coerceAtLeast(1)
        val srcPixels = IntArray(src.width * src.height)
        src.getPixels(srcPixels, 0, src.width, 0, 0, src.width, src.height)
        src.recycle()
        val out = IntArray(dw * dh)
        StarfieldResampler.downsampleBrightest(srcPixels, src.width, src.height, dw, dh, out)
        Bitmap.createBitmap(out, dw, dh, Bitmap.Config.ARGB_8888)
    }.getOrNull()

    private fun drawCoastline(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val rings = coastlineVectors ?: ensureCoastline() ?: return

        fillPath.reset()
        strokePath.reset()

        for (ring in rings) {
            GlobeProjection.clip(ring, basis, clipBuffer)
            // 整环都在背面 → 裁剪后连一个三角形都凑不出，直接跳过。
            if (GlobeProjection.invisible(clipBuffer)) continue

            // ── 填充：全部环塞进同一个 Path，靠 EVEN_ODD 自动挖洞（数据里唯一的洞是里海）。
            var fillStarted = false
            GlobeProjection.forEachFillVertex(clipBuffer, basis) { x, y, z, _ ->
                toScreen(x, y, z, cx, cy, radius)
                if (fillStarted) fillPath.lineTo(projection[0], projection[1])
                else {
                    fillPath.moveTo(projection[0], projection[1])
                    fillStarted = true
                }
            }
            if (fillStarted) fillPath.close()

            // ── 描边：forEachStrokeEdge 已丢掉 horizon↔horizon 的闭合边（海岸线该在视界处断开），
            //    断开处必须重新 moveTo，否则会跨过缺口连一条直线。
            var strokeStarted = false
            var lastEndX = 0f
            var lastEndY = 0f
            GlobeProjection.forEachStrokeEdge(clipBuffer) { ax, ay, az, bx, by, bz ->
                toScreen(ax, ay, az, cx, cy, radius)
                val x0 = projection[0]
                val y0 = projection[1]
                toScreen(bx, by, bz, cx, cy, radius)
                val x1 = projection[0]
                val y1 = projection[1]
                if (!strokeStarted || x0 != lastEndX || y0 != lastEndY) strokePath.moveTo(x0, y0)
                else strokePath.lineTo(x0, y0)
                strokePath.lineTo(x1, y1)
                strokeStarted = true
                lastEndX = x1
                lastEndY = y1
            }
        }

        fillPath.fillType = Path.FillType.EVEN_ODD
        canvas.drawPath(fillPath, landPaint)
        canvas.drawPath(strokePath, coastlinePaint)
    }

    /**
     * 画 hub 出发的全部弧线，并在每条弧上跑一颗彗星。
     *
     * 采样**只做一次**：屏幕坐标与可见性缓存进 [arcScreen] / [arcVisible]，底弧与彗星共用
     * （点积、投影都不重复算）。
     */
    private fun drawArcs(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        headsUsed = 0
        if (arcs.isEmpty()) return
        val maxRate = arcs.maxOf { it.bytesPerSecond }

        // 弧的语义是"从 hub 出发"。hub 转到背面之后，弧只剩视界附近那几段尾巴，
        // 看着像随机噪点 —— 按 hub 的可见程度整体压暗，让它退成"来自地平线之外"的暗示。
        val sourceVisibility = hubVector?.let { hub ->
            val forward = hub[0] * basis.zx + hub[1] * basis.zy + hub[2] * basis.zz
            ARC_SOURCE_MIN_RATIO + (1f - ARC_SOURCE_MIN_RATIO) * forward.coerceIn(0f, 1f)
        } ?: 1f

        for (arc in arcs) {
            val count = GlobeProjection.sampleArc(arc.from, arc.to, arcScratch)
            if (count < 2) continue

            var visibleCount = 0
            for (i in 0 until count) {
                val x = arcScratch[i * 3]
                val y = arcScratch[i * 3 + 1]
                val z = arcScratch[i * 3 + 2]
                val visible = (x * basis.zx + y * basis.zy + z * basis.zz) >= 0f
                arcVisible[i] = visible
                if (!visible) continue
                toScreen(x, y, z, cx, cy, radius)
                arcScreen[i * 2] = projection[0]
                arcScreen[i * 2 + 1] = projection[1]
                visibleCount++
            }
            // 整条弧都在背面：没有一段可画，后面那些绘制调用全都可以省掉。
            if (visibleCount == 0) continue

            val busy = arc.connectionCount > 0
            val width = arcWidthOf(arc)

            // ── 底弧：静态细线。跨到背面必须断开，不能连一条弦过去（由 [buildArcPath] 保证）。
            arcPaint.color = palette.arc
            arcPaint.strokeWidth = width
            arcPaint.strokeCap = Paint.Cap.ROUND
            arcPaint.alpha = ((if (busy) ARC_BUSY_ALPHA else ARC_IDLE_ALPHA) * sourceVisibility)
                .toInt().coerceIn(0, 255)
            if (buildArcPath(0f, (count - 1).toFloat(), count)) canvas.drawPath(arcPath, arcPaint)

            drawComet(canvas, arc, count, maxRate, sourceVisibility)
        }
    }

    /**
     * 彗星：一颗亮点沿弧从 hub 奔向目的地，后面拖一条渐隐的尾巴。
     *
     * 头是实心圆 + 一圈淡光晕 —— 用两层同心圆而不是 `RadialGradient`，是因为按位置新建
     * shader 等于每条弧每帧一个对象，几十条弧就是几十次分配。
     *
     * 为什么不沿用原先的 `DashPathEffect` 走马灯：那只是"整条虚线在爬"，既没有亮点也没有
     * 亮度梯度，看着不像"闪耀"。这里换成一颗会发光的头。
     */
    private fun drawComet(
        canvas: Canvas,
        arc: RenderArc,
        count: Int,
        maxRate: Long,
        sourceVisibility: Float,
    ) {
        val tier = pulseTier(arc, maxRate)
        // 全局相位 × 整数档位 + 每条弧的错相。档位是整数 ⇒ 全局相位回绕处这里是连续的。
        val head = ((cometPhase * tier + arc.phase) % 1f) * (count - 1)

        val busy = arc.connectionCount > 0
        // 头/尾都用"在用"那支语义色（与标记光晕同色）。空闲弧也用它是权衡过的：
        // 改用弧线本色的话，空闲弧的彗星压在深色底弧上几乎看不见，"每条线都在动"就落空了。
        // 忙闲的区分交给**亮度**（[COMET_BUSY_ALPHA] / [COMET_IDLE_ALPHA]）以及弧本身的粗细与浓淡。
        val peak = if (busy) COMET_BUSY_ALPHA else COMET_IDLE_ALPHA
        val width = arcWidthOf(arc)

        // ── 尾迹。宽度**恒定**、只有 alpha 递减：每段各给一个宽度的话，放大到像素上就是
        //    台阶状的侧影，看着像片叶子而不是彗星。亮度按平方衰减 —— 尾巴该"很快淡掉"。
        //    段与段首尾相接，所以用平头（BUTT）而不是圆头：圆头会在接缝处互相叠出一串亮疙瘩。
        val tailSpan = cometTailSpan(arc, count)
        val step = tailSpan / COMET_TAIL_TIERS
        arcPaint.color = palette.active
        arcPaint.strokeWidth = width + dp(COMET_TAIL_EXTRA_DP)
        arcPaint.strokeCap = Paint.Cap.BUTT
        for (k in 0 until COMET_TAIL_TIERS) {
            val strength = (k + 1).toFloat() / COMET_TAIL_TIERS
            val from = max(0f, head - tailSpan + step * k)
            // 最亮那段（贴着头的）多探出半格，免得头和尾之间被整点取整拉开一道缝。
            val to = if (k == COMET_TAIL_TIERS - 1) head + 0.5f else head - tailSpan + step * (k + 1)
            if (to <= 0f || to <= from) continue
            if (!buildArcPath(from, to, count)) continue
            arcPaint.alpha = (
                peak * COMET_TAIL_PEAK_RATIO * strength * strength * sourceVisibility
                ).toInt().coerceIn(0, 255)
            canvas.drawPath(arcPath, arcPaint)
        }

        // ── 头。屏幕空间里插值：采样步长 2°，不插值的话头会一跳一跳地走。
        val i0 = floor(head).toInt()
        if (i0 < 0 || i0 >= count || !arcVisible[i0]) return
        val i1 = (i0 + 1).coerceAtMost(count - 1)
        // 下一个采样点已经在背面时不要往它那边插值：头会飘到视界外面去。
        val f = if (arcVisible[i1]) head - i0 else 0f
        val hx = arcScreen[i0 * 2] + (arcScreen[i1 * 2] - arcScreen[i0 * 2]) * f
        val hy = arcScreen[i0 * 2 + 1] + (arcScreen[i1 * 2 + 1] - arcScreen[i0 * 2 + 1]) * f

        // 头必须比尾粗：尾巴是"底弧加宽 [COMET_TAIL_EXTRA_DP]"。头若写死半径，
        // 连接数多的弧（底弧本来就粗）上尾巴反而比头宽，就不像"一颗亮点在跑"了。
        // 头不在这里画：收进池子，等标记与胶囊都落位后由 [drawCometHeads] 统一压顶，
        // 否则 hub 的胶囊会把路过的那颗头整个盖掉。
        val rec = if (headsUsed < cometHeads.size) cometHeads[headsUsed]
        else CometHead().also { cometHeads.add(it) }
        headsUsed++
        rec.x = hx
        rec.y = hy
        rec.radius = width / 2f + dp(COMET_HEAD_EXTRA_DP)
        rec.glowAlpha = (peak * COMET_GLOW_RATIO * sourceVisibility).toInt().coerceIn(0, 255)
        rec.coreAlpha = (peak * sourceVisibility).toInt().coerceIn(0, 255)
    }

    /** 帧末统一画彗星头（收集过程见 [drawComet]）。只遍历 [headsUsed] 条 —— 池子可能比本帧的弧多。 */
    private fun drawCometHeads(canvas: Canvas) {
        if (headsUsed == 0) return
        cometPaint.color = palette.active
        for (i in 0 until headsUsed) {
            val head = cometHeads[i]
            cometPaint.alpha = head.glowAlpha
            canvas.drawCircle(head.x, head.y, head.radius + dp(COMET_GLOW_EXTRA_DP), cometPaint)
            cometPaint.alpha = head.coreAlpha
            canvas.drawCircle(head.x, head.y, head.radius, cometPaint)
        }
    }

    /** 弧的描边宽度：底宽 + 每条连接加粗一点，封顶 [ARC_MAX_CONN_STEPS] 级。 */
    private fun arcWidthOf(arc: RenderArc): Float =
        dp(ARC_BASE_WIDTH_DP) + dp(ARC_WIDTH_PER_CONN_DP) * min(arc.connectionCount, ARC_MAX_CONN_STEPS)

    /**
     * 把 `fromIdx..toIdx`（含端点，可带小数）之间的**可见**采样点连成 [arcPath]。
     * 返回是否至少连出了一条线段 —— 整段都在背面时返回 false，调用方直接跳过这次绘制。
     */
    private fun buildArcPath(fromIdx: Float, toIdx: Float, count: Int): Boolean {
        arcPath.reset()
        val lo = max(0, floor(fromIdx).toInt())
        val hi = min(count - 1, ceil(toIdx).toInt())
        var started = false
        var drew = false
        for (i in lo..hi) {
            if (!arcVisible[i]) {
                // 弧绕到背面再转回来时绝不能跨过去连一条弦。
                started = false
                continue
            }
            val px = arcScreen[i * 2]
            val py = arcScreen[i * 2 + 1]
            if (started) {
                arcPath.lineTo(px, py)
                drew = true
            } else {
                arcPath.moveTo(px, py)
            }
            started = true
        }
        return drew
    }

    /**
     * 尾迹的**点数**跨度。按角度定长（[COMET_TAIL_DEG]），但在短弧上按弧长比例缩一缩
     * （[COMET_TAIL_MAX_FRACTION]）—— 不然尾巴比整条弧还长，看着就是"一根粗线在闪"。
     */
    private fun cometTailSpan(arc: RenderArc, count: Int): Float {
        val totalDeg = GlobeProjection.angleDeg(arc.from, arc.to)
        if (totalDeg <= 0f) return 0f
        val tailDeg = min(COMET_TAIL_DEG, totalDeg * COMET_TAIL_MAX_FRACTION)
        return tailDeg / totalDeg * (count - 1)
    }

    /**
     * 每条弧上彗星的**起步错相**，落在 `0..1`。
     *
     * 所有彗星同时出发会显得很机械。错相由**目的地经纬度**算出而不是列表下标：拓扑每 2s
     * 重建一次，用下标的话节点集合一变相位就跳；用几何量则同一个目的地永远得到同一个相位。
     */
    private fun cometStartPhase(point: GeoPoint): Float {
        val raw = (point.longitude * 7.0 + point.latitude * 11.0) / 360.0
        return (raw - floor(raw)).toFloat()
    }

    /**
     * 彗星速度档位，**必须是整数**且落在 `1..[COMET_MAX_TIER]`：位置由"全局相位 × 档位"得到，
     * 全局相位回绕时只有整数倍才连续，否则所有彗星会在同一瞬间一起跳一下。
     *
     * 快慢 ∝ 速率；没有速率但有连接时按连接数给个慢档，别让"正在用"看起来是死的。
     */
    private fun pulseTier(arc: RenderArc, maxRate: Long): Float {
        val steps = COMET_MAX_TIER.toInt()
        if (arc.bytesPerSecond <= 0L || maxRate <= 0L) {
            val byConnections = 1 + min(arc.connectionCount, ARC_MAX_CONN_STEPS) * (steps - 1) / ARC_MAX_CONN_STEPS
            return byConnections.coerceAtMost(steps).toFloat()
        }
        val ratio = arc.bytesPerSecond.toFloat() / maxRate
        return (1f + ratio * (COMET_MAX_TIER - 1f)).roundToInt().coerceIn(1, steps).toFloat()
    }

    private fun drawMarkers(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val breath = (0.5f + 0.5f * sin((breathPhase * 2f * Math.PI).toFloat()))
            .coerceIn(0f, 1f)

        for (item in markers) {
            val marker = item.marker
            val v = item.vector
            // 背面的点不画：正交投影下背面点会叠到前面来。
            if (v[0] * basis.zx + v[1] * basis.zy + v[2] * basis.zz < 0f) {
                item.front = false
                continue
            }
            item.front = true

            toScreen(v[0], v[1], v[2], cx, cy, radius)
            val px = projection[0]
            val py = projection[1]
            item.screenX = px
            item.screenY = py

            val base = dp(MARKER_BASE_RADIUS_DP) +
                dp(MARKER_PER_CONN_RADIUS_DP) * min(marker.connectionCount, ARC_MAX_CONN_STEPS)
            item.screenRadius = base

            if (marker.connectionCount > 0) {
                haloPaint.alpha = (MARKER_HALO_MIN_ALPHA +
                    (MARKER_HALO_MAX_ALPHA - MARKER_HALO_MIN_ALPHA) * breath).toInt()
                canvas.drawCircle(px, py, base + dp(MARKER_HALO_EXTRA_DP), haloPaint)
            }

            when {
                marker.isCurrent -> {
                    markerPaint.style = Paint.Style.FILL
                    markerPaint.color = palette.current
                    canvas.drawCircle(px, py, base + dp(1.4f), markerPaint)
                    canvas.drawCircle(px, py, base + dp(1.4f), markerRingPaint)
                }
                // 坐标是"国家代表坐标"兜底来的 → 空心点，别假装它精确。
                marker.approximate -> {
                    markerPaint.style = Paint.Style.STROKE
                    markerPaint.strokeWidth = dp(1.4f)
                    markerPaint.color = palette.node
                    canvas.drawCircle(px, py, base, markerPaint)
                }
                else -> {
                    markerPaint.style = Paint.Style.FILL
                    markerPaint.color = palette.node
                    canvas.drawCircle(px, py, base, markerPaint)
                }
            }

            if (marker.isExit) {
                canvas.drawCircle(px, py, base + dp(EXIT_RING_GAP_DP), exitRingPaint)
            }
        }

        // 选中环最后画：盖在所有标记之上，避免被相邻的点压住。
        if (selectedIndex >= 0) {
            val item = markers.getOrNull(selectedIndex)
            if (item != null) {
                val v = item.vector
                if (v[0] * basis.zx + v[1] * basis.zy + v[2] * basis.zz >= 0f) {
                    toScreen(v[0], v[1], v[2], cx, cy, radius)
                    val base = dp(MARKER_BASE_RADIUS_DP) +
                        dp(MARKER_PER_CONN_RADIUS_DP) * min(item.marker.connectionCount, ARC_MAX_CONN_STEPS)
                    canvas.drawCircle(projection[0], projection[1], base + dp(SELECTION_RING_GAP_DP), selectionPaint)
                }
            }
        }
    }

    /**
     * 哪些落点配一个城市名胶囊：hub、出口、带活跃连接的落点。
     * 订阅可能有几百个节点，全标会把地图糊成一坨字；有名字的就该是"值得看一眼"的那几个。
     *
     * 文案优先级：城市名 → 节点显示名 → **本地化国家名**。中间刻意跳过 IP：
     * 活跃连接点的 label 在查不到城市时会兜底成主机名（GlobeTopologyBuilder.addConnections），
     * 那常常就是裸 IP —— 胶囊里露 IP 既难读又没信息量，直接让位给国家名。
     * 国家码（"US"）也不直接上屏，翻成当前语言的国名（"美国" / "United States"）。
     */
    internal fun labelOf(marker: GlobeMarker): String? {
        if (!marker.isCurrent && !marker.isExit && marker.connectionCount <= 0) return null
        marker.point.city?.takeIf { it.isNotBlank() }?.let { return it }
        marker.label?.takeIf { it.isNotBlank() && !isIpLiteral(it) }?.let { return it }
        return marker.countryCode?.takeIf { it.isNotBlank() }?.let { displayCountryName(it) }
    }

    /**
     * 是否 IP 字面量。主机名永远不含冒号、也不会是四段点分十进制，这两条判据足够；
     * 括号形态的 IPv6（`[::1]`）也一并认下。
     */
    private fun isIpLiteral(text: String): Boolean {
        val bare = text.removeSurrounding("[", "]")
        return bare.contains(':') || IPV4_LITERAL.matches(bare)
    }

    /** 国家码 → 当前语言的国名。非法码（脏数据）原样返回，不让胶囊因此整个消失。 */
    private fun displayCountryName(code: String): String = try {
        java.util.Locale.Builder().setRegion(code).build()
            .getDisplayCountry(java.util.Locale.getDefault())
    } catch (_: IllegalArgumentException) {
        code
    }

    /**
     * 画城市名胶囊。**碰撞避让按优先级**：markers 已按 hub → 出口 → 连接数排序（MARKER_ORDER），
     * 先贴的占位，后贴的和已贴矩形相交就整个跳过 —— 宁可不显示，也不叠字。
     *
     * 默认贴在落点正上方；上方出界（贴着球顶）就翻到下方，左右再夹进视图内。
     */
    private fun drawLabels(canvas: Canvas) {
        var placed = 0
        val centerX = width / 2f
        val centerY = height / 2f
        val visibleRadius = min(width, height) / 2f * SPHERE_FILL_RATIO
        for (item in markers) {
            val text = item.labelText ?: continue
            if (!item.front) continue
            // 放大之后落点可能已经被推到可见圆之外 —— 这类标签整个跳过。不跳的话它会被下面的
            // 夹紧逻辑拉回边缘，在边上堆成一排"说不清标的是谁"的胶囊。
            // 倍率为 1 时这条永不成立（正面的点必落在球面内），行为与从前一致。
            if (hypot(item.screenX - centerX, item.screenY - centerY) > visibleRadius) continue
            val chipW = item.labelWidth + dp(LABEL_CHIP_PAD_H_DP) * 2f
            var left = item.screenX - chipW / 2f
            var top = item.screenY - item.screenRadius - dp(MARKER_HALO_EXTRA_DP) -
                dp(LABEL_CHIP_GAP_DP) - labelChipHeight
            if (top < dp(2f)) {
                top = item.screenY + item.screenRadius + dp(MARKER_HALO_EXTRA_DP) + dp(LABEL_CHIP_GAP_DP)
            }
            left = left.coerceIn(dp(2f), max(dp(2f), width - chipW - dp(2f)))

            val rect = if (placed < labelRects.size) labelRects[placed]
            else RectF().also { labelRects.add(it) }
            rect.set(left, top, left + chipW, top + labelChipHeight)
            if ((0 until placed).any { RectF.intersects(rect, labelRects[it]) }) continue

            canvas.drawRoundRect(rect, dp(LABEL_CHIP_CORNER_DP), dp(LABEL_CHIP_CORNER_DP), labelChipPaint)
            canvas.drawText(
                text,
                rect.left + dp(LABEL_CHIP_PAD_H_DP),
                rect.top + dp(LABEL_CHIP_PAD_V_DP) - labelTextAscent,
                labelTextPaint,
            )
            placed++
        }
    }

    /** 三维点 → 盘面像素，结果写进 [projection] 的 (0, 1)。 */
    private fun toScreen(x: Float, y: Float, z: Float, cx: Float, cy: Float, radius: Float) {
        GlobeProjection.project(x, y, z, basis, projection, 0)
        // 屏幕 y 轴朝下、球面 y 轴朝北 → 取负。
        projection[0] = cx + projection[0] * radius
        projection[1] = cy - projection[1] * radius
    }

    /** 懒加载海岸线并**一次性**转成单位球向量。资产缺失时返回 null（退化成"有球无海岸线"）。 */
    private fun ensureCoastline(): Array<FloatArray>? {
        coastlineVectors?.let { return it }
        val data = CoastlineData.load(context.assets) ?: return null
        val vectors = Array(data.rings.size) { index ->
            val flat = data.rings[index]
            val count = flat.size / 2
            val out = FloatArray(count * 3)
            for (i in 0 until count) {
                GlobeProjection.unitVector(flat[i * 2], flat[i * 2 + 1], out, i * 3)
            }
            out
        }
        coastlineVectors = vectors
        return vectors
    }

    /**
     * 取主题角色色。属性 id 必须走 Material 库的 R —— 本项目是非传递 R 类，
     * app 自己的 `R.attr` 里没有库属性。取不到属性时返回兜底值。
     */
    private fun themeColor(attrName: String, fallback: Int): Int {
        val attrId = resources.getIdentifier(attrName, "attr", context.packageName)
        if (attrId == 0) return fallback
        return MaterialColors.getColor(this, attrId, fallback)
    }

    // ──────────────────────────────────────────────────────────── 内部数据

    private class RenderMarker(val vector: FloatArray, val marker: GlobeMarker) {
        /** 城市名胶囊文本；null = 不配拥有名字（只有 hub / 出口 / 带活跃连接的落点会拿到）。 */
        var labelText: String? = null
        var labelWidth: Float = 0f
        // 标记趟缓存的屏幕坐标与半径，标签趟直接复用，不再重投影一次。
        var screenX = 0f
        var screenY = 0f
        var screenRadius = 0f
        var front = false
    }

    private class RenderArc(
        val from: FloatArray,
        val to: FloatArray,
        val connectionCount: Int,
        val bytesPerSecond: Long,
        /** 彗星的起步错相（`0..1`），见 [cometStartPhase]。 */
        val phase: Float,
        /** 弧的**终点**在 [markers] 里的下标。点线时选中它（弧没有独立于两端点的信息）；找不到为 -1。 */
        val toMarkerIndex: Int,
    )

    /**
     * 常量表。`internal` 而非 private 的只有 [MESH_COLS] / [MESH_ROWS]：
     * 单测要按这两个数把网格切出某一行做纯几何断言（见 `GlobeViewTest.meshRowX`）。
     */
    internal companion object {
        const val INITIAL_LON_DEG = 20f
        const val INITIAL_LAT_DEG = 20f

        /** 球半径占"短边一半"的比例，留一点余量给描边。 */
        const val SPHERE_FILL_RATIO = 0.94f

        /** 缩放下限 = 默认倍率。比这更小只会让球缩在方形中间、四周留出一圈空，没有信息量。 */
        const val MIN_ZOOM = 1f

        /**
         * 缩放上限。2.2 是"能看清一小片海岸线"与"整屏只剩同一块颜色"之间的折中 ——
         * 再大就基本只有一条海岸线的直线段了。超过它也没有裁剪之外的内容可看。
         */
        const val MAX_ZOOM = 2.2f

        /**
         * 自转是**有限摆动**而不是整圈转：绕中心经度左右各摆这么多度。
         *
         * 为什么不做整圈：整圈转必然周期性把当前节点（hub）转到背面，而 hub 一到背面，
         * 它的弧线就只剩视界附近一截尾巴，看起来像噪点。25° 的摆动既保留了"球是活的"，
         * 又保证 hub 一直待在正面。
         */
        const val SWAY_DEG = 25f

        /** 一个完整来回（左 → 右 → 左）的秒数。12s 的峰值角速度约 13°/s，比原先匀速自转略快一点。 */
        const val SWAY_PERIOD_SEC = 12f

        /** `2π`。相位用的是"周期比例"（0..1）而不是弧度，只在算波形时乘这一次。 */
        val TWO_PI = (2.0 * PI).toFloat()

        /** 手指每移动 1dp 转多少度。 */
        const val ROTATE_DEG_PER_DP = 0.4f

        const val INERTIA_MS = 1200f
        const val INERTIA_DECAY_PER_SEC = 2.2f
        const val FLING_MIN_DP = 0.5f
        const val MIN_SAMPLE_MS = 1L

        const val MAX_FRAME_SECONDS = 0.05

        /**
         * 球体三层的不透明度。
         *
         * 历史值是 26 / 56 / 130（≈10% / 22% / 51%），那是按**浅色卡片**调的一套"淡洗"：
         * 浅色主题下是米色淡球，还好看；但深色主题下 `colorPrimary`(=#FFB77B) @10% 铺在
         * 卡片(#282220)上只剩 #3D3128 —— 跟卡片几乎同色，球直接糊进背景里
         * （用户报的"关闭星空后球体的配色很深，与正常情况完全不同"就是这个）。
         * 现在这套值让深浅主题下都能一眼看出"这里有个球"，且陆地/海岸线仍有足够层次。
         */
        const val LAND_ALPHA = 128
        const val COASTLINE_ALPHA = 166
        const val OCEAN_ALPHA = 115

        /**
         * 太阳位置的量化步长（度）。太阳 1 小时只走 15°，0.5° 相当于**每 2 分钟**才重烘一次
         * 晨昏贴图；而 0.5° 的晨昏线位移在 300dp 的球上不到 1px，看不出台阶。
         */
        private const val SUN_STEP_DEG = 0.5f

        /** 缺少夜景贴图时的夜面实色，也是矢量回退夜空遮罩的颜色：留一点蓝，别死黑。 */
        private const val NIGHT_TINT = 0xFF060D1A.toInt()

        /**
         * 矢量回退夜空遮罩的尺寸。它只承载一层平滑渐变、没有细节要保留，而混合带本身就有
         * 4.6° 宽 —— 256×128 的每纹素 1.4° 比它还细，放大后不会看出格子。
         */
        private const val NIGHT_MASK_W = 256
        private const val NIGHT_MASK_H = 128

        /** 矢量回退夜空遮罩的最深不透明度。留一点余量让海岸线从夜色里透出来。 */
        private const val NIGHT_MASK_MAX_ALPHA = 210

        /**
         * 贴图球面网格的格数。**列数是按整张 360° 贴图算的**（每列 5°），不是按可见半球
         * —— 网格必须整张锚定，可见那半圈自然就是其中 36 列。行是 180°（每行 10°）。
         *
         * 所以顶点数是 73×19，其中约一半落在背面（会被压到视界上、退化成零面积）。
         * 盘面直径约 300dp 时每格 8dp，已看不出折线感；再密只是白烧 CPU
         * （每帧都要把全部顶点重投一遍）。
         *
         * `internal` 而非 private：单测要据此把网格切出某一行来做纯几何断言。
         */
        internal const val MESH_COLS = 72
        internal const val MESH_ROWS = 18

        /**
         * 判定"这一列在正面"时给 depth 留的负容差。极点行整行的 depth 都是 `cos 90°` 的浮点尾巴
         * （量级 1e-17），严格 `> 0` 会让正/背面由噪声决定 —— 见 [writeTextureRow]。
         */
        private const val BACK_EPS = 1e-4f

        /**
         * 贴图锚点（缝所在的世界经度）的量化步长。缝只要落在**背面**（离相机对足点 90° 以内都行）
         * 就够了，不必正好压在对足点：30° 量化给缝留了 ±15° 余量，仍然稳稳在背面，
         * 而拖动 / 摆动时重烘贴图的次数降到几乎为零 —— 见 [anchorLonFor]。
         */
        private const val ROLL_STEP_DEG = 30f

        const val ARC_BASE_WIDTH_DP = 1.1f
        const val ARC_WIDTH_PER_CONN_DP = 0.35f
        const val ARC_MAX_CONN_STEPS = 6
        const val ARC_IDLE_ALPHA = 70
        const val ARC_BUSY_ALPHA = 235

        /** hub 转到背面时弧线保留的最低不透明度比例（不归零，否则会突然整片消失）。 */
        const val ARC_SOURCE_MIN_RATIO = 0.22f

        /** 档位 1 的彗星扫过速度（周期/秒）。实际速度 = 该值 × 档位。 */
        const val COMET_CYCLES_PER_SEC = 0.3f

        /** 档位上限。档位数必须落在 `1..COMET_MAX_TIER` 且为整数（见 [pulseTier]）。 */
        const val COMET_MAX_TIER = 4f

        /** 尾迹按角度定长：这么多度。短弧上再按 [COMET_TAIL_MAX_FRACTION] 缩一缩。 */
        const val COMET_TAIL_DEG = 26f
        const val COMET_TAIL_MAX_FRACTION = 0.45f

        /**
         * 尾迹分几段画。**不能逐采样点分段**：一条长弧有 180 个点，几十条弧就是上万次
         * drawPath，必然掉帧。四段（越靠头越亮）已经足够骗过眼睛。
         */
        const val COMET_TAIL_TIERS = 4

        /**
         * 尾迹最亮处占头核心亮度的比例。**必须小于 1**：尾巴和最靠近头的那段若与头同亮，
         * 头和尾就糊成一条绿棒，"一颗亮点在跑"的观感全没了。
         */
        const val COMET_TAIL_PEAK_RATIO = 0.6f

        /** 尾迹整体比底弧宽多少 dp。**恒定值**，不随分段变化（见 [drawComet] 的说明）。 */
        const val COMET_TAIL_EXTRA_DP = 1.1f

        const val COMET_BUSY_ALPHA = 255
        const val COMET_IDLE_ALPHA = 150

        /** 头半径 = 弧宽的一半 + 这个值（dp）。 */
        const val COMET_HEAD_EXTRA_DP = 1.5f

        /** 光晕比头核心再大出多少 dp。 */
        const val COMET_GLOW_EXTRA_DP = 2.6f

        /** 光晕相对头核心的 alpha 比例 —— 太亮就糊成一坨，看不出"闪光"的层次。 */
        const val COMET_GLOW_RATIO = 0.42f

        const val BREATH_CYCLES_PER_SEC = 0.4f

        const val MARKER_BASE_RADIUS_DP = 2.6f
        const val MARKER_PER_CONN_RADIUS_DP = 0.5f
        const val MARKER_HALO_EXTRA_DP = 3.2f
        const val MARKER_HALO_MIN_ALPHA = 30
        const val MARKER_HALO_MAX_ALPHA = 110
        const val EXIT_RING_GAP_DP = 2.6f

        /** 命中标记的触控半径。落点的视觉半径只有 2~5dp，按它判手指永远点不中。 */
        const val MARKER_TOUCH_RADIUS_DP = 24f

        /** 命中弧的触控半径。弧是细线，给得比标记略小，让"点"始终优先于"线"。 */
        const val ARC_TOUCH_RADIUS_DP = 20f

        /** 选中环比标记大出多少 dp。 */
        const val SELECTION_RING_GAP_DP = 3.4f

        // ── 城市名胶囊（见 drawLabels）
        const val LABEL_TEXT_SIZE_DP = 11f
        const val LABEL_CHIP_PAD_H_DP = 7f
        const val LABEL_CHIP_PAD_V_DP = 3.5f
        const val LABEL_CHIP_GAP_DP = 5f
        const val LABEL_CHIP_CORNER_DP = 9f

        /** 胶囊底色：半透明近黑，与点选气泡 bg_globe_bubble 同一挂；白字直接写死。 */
        val LABEL_CHIP_BG = 0xCC202124.toInt()

        /** 星空模式的太空底色。 */
        val SPACE_BG = 0xFF05070D.toInt()

        /** 粗判 IP 字面量用（见 isIpLiteral），IPv6 靠冒号识别，不需要正则。 */
        val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        /** 一条弧的采样点数上限（180 步 + 1），也是 [arcScreen] / [arcVisible] 的容量。 */
        const val ARC_MAX_POINTS = 181

        /** 弧采样的 scratch 需要 `ARC_MAX_POINTS * 3` 个 float。 */
        const val ARC_SCRATCH_FLOATS = ARC_MAX_POINTS * 3

        // ── 兜底色值：只在主题里查不到属性时才会用到（正常永不命中）。
        const val FALLBACK_ON_SURFACE = 0xFF1C1B1F.toInt()
        const val FALLBACK_ON_SURFACE_VARIANT = 0xFF49454F.toInt()
        const val FALLBACK_PRIMARY = 0xFF6750A4.toInt()
        const val FALLBACK_OUTLINE_VARIANT = 0xFFCAC4D0.toInt()
        const val FALLBACK_SURFACE = 0xFFFFFFFF.toInt()
        const val FALLBACK_SURFACE_CONTAINER_LOW = 0xFFF7F2FA.toInt()
    }
}
