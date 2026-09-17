package app.fjj.stun.geo

/**
 * 拓扑构建的输入：一个订阅节点。
 *
 * 刻意只有两个字段 —— 曾经有过 `pinned`（收藏/最近/Top-N 豁免聚合），
 * 在"每个节点都逐个出点"定案后它失去了全部用途，已删除。
 */
data class GlobeNode(
    val address: String,
    val name: String? = null,
)

/**
 * 地球上的一个落点。**用标志位而不是 kind 枚举**：一个点常常同时是好几件事
 * （跳板机往往就是当前节点 + 出口；被你用着的节点同时是订阅节点和活跃目标），
 * 枚举会逼着调用方去排序优先级，标志位只是各行其是。
 */
data class GlobeMarker(
    val point: GeoPoint,
    /** hub：所有弧的起点。整张图最多一个。 */
    val isCurrent: Boolean = false,
    /** 流量最终落地的出口。画成虚线环，与实心节点区分。 */
    val isExit: Boolean = false,
    /** 这一个落点上叠了几个订阅节点（见 [cellOf] 的坐标格去重）。>1 说明它们坐标几乎重合。 */
    val nodeCount: Int = 1,
    /** 这一个落点上落了几条活跃连接 —— 决定点的大小与光晕。 */
    val connectionCount: Int = 0,
    /** 该落点的瞬时速率（字节/秒），决定弧上脉冲的快慢。 */
    val bytesPerSecond: Long = 0L,
    val label: String? = null,
    /**
     * 这个落点的主地址（订阅节点的 SSH 地址优先，其次是活跃连接的主机）。
     *
     * 点选地球上的落点弹气泡时，光有 [label] 不够 —— 名字可以重复，地址才是用户核对的凭据。
     * **先到先得**（与 [label] 同一条优先级）：订阅节点先于活跃连接。
     * 坐标格合并了多个节点时它只代表其中一个，所以气泡里要配合 [nodeCount] 说明。
     */
    val address: String? = null,
) {
    /** 坐标是"国家代表坐标"兜底来的，不是记录自带 → 画得更虚一点（空心点）。 */
    val approximate: Boolean get() = point.approximate

    val countryCode: String? get() = point.countryCode
}

/** 从 hub 拉出的一条弧。[from] 恒等于 hub 的坐标，复制一份是为了让渲染侧不必回查。 */
data class GlobeArc(
    val from: GeoPoint,
    val to: GeoPoint,
    val connectionCount: Int,
    val bytesPerSecond: Long,
)

/**
 * 渲染 [GlobeView](app 模块) 需要的全部几何信息。
 *
 * @property available 地理库是否可用。false 时 [markers] 必为空，展示层据此显示"下载地理库"引导
 *   —— 这与"库可用但一个点都没解析出来"是两件不同的事，所以不能用 `isEmpty` 代替。
 */
data class GlobeTopology(
    val markers: List<GlobeMarker>,
    val arcs: List<GlobeArc>,
    val available: Boolean,
) {
    val hub: GlobeMarker? get() = markers.firstOrNull { it.isCurrent }

    val isEmpty: Boolean get() = markers.isEmpty()

    companion object {
        val EMPTY = GlobeTopology(emptyList(), emptyList(), available = false)
    }
}

/**
 * 把"订阅节点 + 活跃连接 + 当前节点 + 出口"聚合成一张可渲染的拓扑。
 *
 * ## 规则（用户定案：**不聚合，每个节点逐个出点**）
 * - **当前节点**永远是 hub，只要它能被定位；定位不了则**出口顶上当 hub**（出口也能定位时，
 *   画成"实心点 + 虚线环"），两者都定位不了就只有散点、没有弧。
 * - **出口**与当前节点坐标格相同时**合并成一个点**（跳板机就是出口的情况很常见），
 *   两个标志同时为真、画成"实心点 + 虚线环"。
 * - **订阅节点全部逐个出点**，数量多少都不归并 —— 曾按国家归并过一版，用户明确否掉了。
 * - **活跃连接**按 host 去重后定位，落在已有落点上的**合并进那个点**
 *   （于是"正在用的节点"会自然亮起来），落在别处的单独出点。
 * - 坐标落在**同一格**（见 [GRID_DEG]）的点会合成一个 [GlobeMarker]，`nodeCount` 记下有几个
 *   —— 这不是聚合，是去重：同一个机房/城市的多个节点本来就画在同一个像素上，
 *   叠着画除了浪费性能什么都看不出来。
 *
 * ## 规模提醒
 * 出点数是"订阅节点数"，弧数是"出点数 - 1"。几百个节点时每帧要处理的顶点在万级，
 * Canvas 扛得住，但这是个会随订阅规模线性增长的量 —— 真要上千个节点，得在渲染侧做抽稀，
 * 而不是在这里悄悄丢数据。
 *
 * ## 线程
 * [GlobeTopologyBuilder.build] 内部会做 DNS（订阅地址常常是域名），**必须在后台线程调用**。
 */
class GlobeTopologyBuilder(private val locator: GeoLocator) {

    fun build(
        currentNode: GlobeNode?,
        nodes: List<GlobeNode> = emptyList(),
        connections: List<ActiveConnection> = emptyList(),
        exitIp: String? = null,
        rateOf: (ActiveConnection) -> Long = { 0L },
    ): GlobeTopology {
        if (!locator.available) return GlobeTopology.EMPTY

        val acc = LinkedHashMap<Cell, Acc>()

        // ① 当前节点 = hub。先放它，于是同一落点上的 label 会优先取节点的显示名。
        var hubLocated = false
        currentNode?.let { node ->
            locator.locate(node.address)?.let {
                acc.merge(it, isCurrent = true, nodeCount = 1, label = node.name, address = node.address)
                hubLocated = true
            }
        }

        // ② 出口。当前节点定位不了时出口降级为 hub：弧的起点宁可对到"流量真正出去的地方"，
        // 也好过没有弧 —— 彗星只跑在弧上，没弧整张图就是死的。
        exitIp?.let { ip -> locator.locate(ip)?.let { acc.merge(it, isCurrent = !hubLocated, isExit = true) } }

        // ③ 订阅节点：全部逐个出点
        addNodes(acc, nodes)

        // ④ 活跃连接
        addConnections(acc, connections, rateOf)

        // ⑤ 出图
        val markers = acc.values.map(Acc::toMarker).sortedWith(MARKER_ORDER)
        val hub = markers.firstOrNull { it.isCurrent }
        // hub 自己不是目的地，从目的地集合里排掉。
        val destinations = markers.filterNot { it.isCurrent }
        val arcs = if (hub == null) {
            // 当前节点与出口都定位不了 → 没有弧的起点。散点照样画，总比空着强。
            emptyList()
        } else {
            destinations.map { GlobeArc(hub.point, it.point, it.connectionCount, it.bytesPerSecond) }
        }

        return GlobeTopology(markers, arcs, available = true)
    }

    private fun addNodes(acc: MutableMap<Cell, Acc>, nodes: List<GlobeNode>) {
        if (nodes.isEmpty()) return

        // 一次批量定位：域名部分并发解析，避免上百个订阅节点串行等到天荒地老。
        val locatedPoints = locator.locateAll(nodes.map { it.address })
        nodes.forEach { node ->
            locatedPoints[node.address]?.let { point ->
                acc.merge(point, nodeCount = 1, label = node.name, address = node.address)
            }
        }
    }

    private fun addConnections(
        acc: MutableMap<Cell, Acc>,
        connections: List<ActiveConnection>,
        rateOf: (ActiveConnection) -> Long,
    ) {
        if (connections.isEmpty()) return

        // 先按 host 去重再定位：同一个目标上开 50 条连接只该产生一个点，
        // 而不是让解析器白跑 50 次。
        val byHost = connections.groupBy { it.host }
        val points = locator.locateAll(byHost.keys)

        byHost.forEach { (host, group) ->
            val point = points[host] ?: return@forEach
            acc.merge(
                point,
                connectionCount = group.size,
                bytesPerSecond = group.sumOf { rateOf(it) },
                // 只有这个落点还没有名字时才用主机名顶上（已有订阅节点名时不该被覆盖）。
                label = point.city ?: host,
                address = host,
            )
        }
    }
}

// ---------------------------------------------------------------------- 内部实现

/** 合并网格的边长（度）。0.25° ≈ 27km。**只用来去重坐标几乎重合的点**，不做任何国家归并。 */
private const val GRID_DEG = 0.25

private typealias Cell = Pair<Int, Int>

private fun cellOf(point: GeoPoint): Cell = Pair(
    Math.round(point.latitude / GRID_DEG).toInt(),
    Math.round(point.longitude / GRID_DEG).toInt(),
)

/** 可变累加器；只在 [GlobeTopologyBuilder.build] 内部活着。 */
private class Acc(var point: GeoPoint) {
    var isCurrent = false
    var isExit = false
    var nodeCount = 0
    var connectionCount = 0
    var bytesPerSecond = 0L
    var label: String? = null
    var address: String? = null

    fun toMarker(): GlobeMarker = GlobeMarker(
        point = point,
        isCurrent = isCurrent,
        isExit = isExit,
        nodeCount = nodeCount,
        connectionCount = connectionCount,
        bytesPerSecond = bytesPerSecond,
        label = label,
        address = address,
    )
}

private fun MutableMap<Cell, Acc>.merge(
    point: GeoPoint,
    isCurrent: Boolean = false,
    isExit: Boolean = false,
    nodeCount: Int = 0,
    connectionCount: Int = 0,
    bytesPerSecond: Long = 0L,
    label: String? = null,
    address: String? = null,
) {
    val acc = getOrPut(cellOf(point)) { Acc(point) }
    acc.isCurrent = acc.isCurrent || isCurrent
    acc.isExit = acc.isExit || isExit
    acc.nodeCount += nodeCount
    acc.connectionCount += connectionCount
    acc.bytesPerSecond += bytesPerSecond
    // 已有名字不覆盖：先来的（当前节点 → 锚点 → 订阅节点）优先级更高。
    if (acc.label == null && !label.isNullOrBlank()) acc.label = label
    // 地址同理：先到先得。出口 IP 刻意不参与 —— 出口与订阅节点同格时，节点的 SSH 地址更值得展示。
    if (acc.address == null && !address.isNullOrBlank()) acc.address = address
}

/**
 * 固定的出图顺序。渲染侧会拿它当绘制顺序，而且它必须是**确定**的 ——
 * 否则同样的输入在两次刷新间会换位置，看起来像在闪。
 */
private val MARKER_ORDER: Comparator<GlobeMarker> =
    compareByDescending<GlobeMarker> { it.isCurrent }
        .thenByDescending { it.isExit }
        .thenByDescending { it.connectionCount }
        .thenByDescending { it.nodeCount }
        .thenBy { it.label ?: "" }
        .thenBy { it.point.latitude }
        .thenBy { it.point.longitude }
