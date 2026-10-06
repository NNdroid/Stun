package app.fjj.stun.util

import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 宽屏把「一行一个」的节点列表切成「一行多列」，把横向空间用起来。
 *
 * 四个模块各有一份节点列表（app / tv / car / xr），**列数规则必须一致**，所以规则和接线都放在
 * core 里，各模块只在自己的 `dimens.xml` 里给一个"每列至少多宽"。同一条规则以前只长在
 * app 的 HomeFragment 里（`(dpWidth / 360)`），tv/car/xr 一直是单列 —— 电视上 960dp 的横屏
 * 铺一张整宽卡片，横向空间基本全废。
 *
 * ⚠️ 列数一律按 **RecyclerView 自己的实际宽度**算，不用 `resources.displayMetrics`：
 * - XR 的列表只占屏宽 60%（`layout_constraintHorizontal_weight="0.6"`），按屏宽算会多给一倍列数；
 * - car 的列表外面还有 12dp padding；
 * - app 在分屏 / 多窗口 / 桌面模式下窗口宽也不等于屏宽。
 *
 * ⚠️ 宽度变化时只在**列数真的变了**才换 LayoutManager：换 LayoutManager 会重置滚动位置，
 * 每帧都换会让列表永远停在顶部。
 *
 * ⚠️ 别把这里的 [bind] 换成 `doOnLayout`：`doOnLayout` 只跑一次，若首次布局时列表宽度是 0
 * （父容器还 GONE、或空态把列表藏起来了），就会定格在单列，之后宽度回来了也不会重算。
 * 布局变化监听器每次宽度变化都会回调，0 → 全宽 那一次也能接住。
 */
object GridSpans {

    /** 列数上限：再宽也不无限加列（超宽桌面窗口下防止切出一堆读不了的窄条）。 */
    const val DEFAULT_MAX_COLUMNS = 6

    /**
     * 该可用宽度下应该排几列；返回 1 表示**保持单列**（调用方该用 LinearLayoutManager）。
     *
     * 规则：窄于 [minGridWidthPx] 单列；否则按 [minColumnWidthPx] 整除，结果夹在 2..[maxColumns]。
     *
     * 下限夹到 **2** 而不是 1 是刻意的：刚到门槛时整除可能算出 1（600/360），但既然已经宽到
     * 该分列了，就该至少看见两列，而不是"宽了一点点却还是单列"。
     *
     * 全程整数比较（px 对 px），不经过 density 浮点换算 —— 免得 600dp 这个边界被
     * 0.9999 之类的误差判到单列那一侧去。
     */
    fun columnCount(
        availableWidthPx: Int,
        minColumnWidthPx: Int,
        minGridWidthPx: Int,
        maxColumns: Int = DEFAULT_MAX_COLUMNS,
    ): Int {
        if (availableWidthPx < minGridWidthPx) return 1
        val perColumn = minColumnWidthPx.coerceAtLeast(1)
        val max = maxColumns.coerceAtLeast(2)
        return (availableWidthPx / perColumn).coerceIn(2, max)
    }

    /**
     * 把 [recyclerView] 的列数绑到它自己的宽度上，并跟随宽度变化重算。
     *
     * 阈值默认取 core 的 `node_grid_min_width` / `node_grid_min_column`；模块用同名资源覆盖即可
     * （取的是 **RecyclerView 所属 context 的**资源，所以模块的覆盖自动生效，调用方不用传参）。
     *
     * ⚠️ LayoutManager 统一是 [GridLayoutManager]，列数变化走 [GridLayoutManager.setSpanCount]。
     * **绝不能**在布局回调里替换 LayoutManager 实例：OnLayoutChangeListener 是在
     * `View.setFrame` 里（onMeasure 的 auto-measure 已经填完子 View、onLayout 还没跑）触发的，
     * 这个窗口里 `setLayoutManager` 会把子 View 全部回收，而紧跟着的 `dispatchLayout`
     * 不再重新填充 —— 症状是 adapter 里明明有数据、计数也更新了，列表却**永久空白**，
     * 后续遍历也不自愈（TV 端「右上角计数=1 但列表空」就是这么来的）。
     * `setSpanCount` 只置标记 + requestLayout，本轮保持旧几何、下一遍正常重排，是安全的。
     * 单列与多列在 GridLayoutManager 下排布结果完全一致（spanSizeLookup 默认全跨）。
     */
    fun bind(recyclerView: RecyclerView, maxColumns: Int = DEFAULT_MAX_COLUMNS) {
        // 先按当前宽度（多半还是 0）装一个，把"一定有 LayoutManager"这个不变量立刻立住 ——
        // 否则在第一次布局回调之前 RecyclerView 是没有 LayoutManager 的。
        applyColumnCount(recyclerView, maxColumns)
        recyclerView.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            // 只在**宽度**变化时重算；高度变化（列表增删、内容变高）不该动列数。
            if (right - left == oldRight - oldLeft) return@addOnLayoutChangeListener
            applyColumnCount(recyclerView, maxColumns)
        }
    }

    private fun applyColumnCount(recyclerView: RecyclerView, maxColumns: Int) {
        val res = recyclerView.resources
        val availableWidth = recyclerView.width - recyclerView.paddingLeft - recyclerView.paddingRight
        val columns = columnCount(
            availableWidthPx = availableWidth,
            minColumnWidthPx = res.getDimensionPixelSize(app.fjj.stun.core.R.dimen.node_grid_min_column),
            minGridWidthPx = res.getDimensionPixelSize(app.fjj.stun.core.R.dimen.node_grid_min_width),
            maxColumns = maxColumns,
        )
        val lm = recyclerView.layoutManager
        if (lm is GridLayoutManager) {
            // 列数没变就别动：setSpanCount 会 requestLayout，每帧都动列表永远停不下来。
            if (lm.spanCount == columns) return
            lm.spanCount = columns
            return
        }
        // 还没有 LayoutManager（bind 后第一次）才会走到这 —— 换实例只允许发生在布局开始之前
        // （此刻宽度是 0），宽度变化触发的布局回调里永远只走上面的 setSpanCount 分支。
        recyclerView.layoutManager = GridLayoutManager(recyclerView.context, columns)
    }
}
