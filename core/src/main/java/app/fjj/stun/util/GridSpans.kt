package app.fjj.stun.util

import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
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
     * 单列时用 [LinearLayoutManager]、多列才用 [GridLayoutManager] —— 保持单列路径与改造前
     * 完全一致（GridLayoutManager(1) 虽然等价，但会多一层 span 计算）。
     */
    fun bind(recyclerView: RecyclerView, maxColumns: Int = DEFAULT_MAX_COLUMNS) {
        // 先按当前宽度（多半还是 0）装一个，把"一定有 LayoutManager"这个不变量立刻立住 ——
        // 否则在第一次布局回调之前 RecyclerView 是没有 LayoutManager 的。此刻宽度为 0 ⇒ 单列，
        // 与改造前"窄屏走 LinearLayoutManager"一致；真正的列数等第一次布局回调补上。
        applyColumnCount(recyclerView, maxColumns)
        recyclerView.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            // 只在**宽度**变化时重算；高度变化（列表增删、内容变高）不该动 LayoutManager。
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
        val current = when (val lm = recyclerView.layoutManager) {
            // null 记 0 而不是 1：第一次进来必须真的装上 LayoutManager（null 时列表根本不排版）。
            null -> 0
            is GridLayoutManager -> lm.spanCount
            else -> 1
        }
        if (columns == current) return
        recyclerView.layoutManager = if (columns <= 1) {
            LinearLayoutManager(recyclerView.context)
        } else {
            GridLayoutManager(recyclerView.context, columns)
        }
    }
}
