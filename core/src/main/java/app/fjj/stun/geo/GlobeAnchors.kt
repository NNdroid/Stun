package app.fjj.stun.geo

/**
 * 地球上的两个锚点。其余标记都是"从这里出发"的弧，所以这两个点是整张图的坐标系原点。
 *
 * @property currentNode 你**当前连出去**的那台机器 —— 取自当前 profile 的 ssh/proxy 地址。
 *   展示层画成实心高亮点（在线语义色）。
 * @property exit 流量的**最终出口公网 IP**（[app.fjj.stun.util.ExitInfoStore] 采到的那个）。
 *   它与 [currentNode] 常常不同：前者是你拨上去的跳板，后者是流量真正落地的地方。
 *   展示层画成虚线圆环，与普通节点区分开。
 */
data class GlobeAnchors(
    val currentNode: GeoPoint?,
    val exit: GeoPoint?,
) {
    val isEmpty: Boolean get() = currentNode == null && exit == null

    companion object {

        val EMPTY = GlobeAnchors(null, null)

        /**
         * 解析出两个锚点。任一取不到就留 null（对应位置不画）。
         *
         * 入参直接收"地址"而不收 Profile/[app.fjj.stun.util.ExitInfoStore].Info，
         * 是为了让本模块不反向依赖 repo/util —— 谁调用谁来取值，这里只负责把地址变成坐标。
         *
         * @param nodeAddress 当前节点的 `host[:port]`，如 `185.248.33.40:22` 或 `hk1.example.com:443`。
         * @param exitIp 出口 IP 字面量。
         */
        fun of(locator: GeoLocator, nodeAddress: String?, exitIp: String?): GlobeAnchors =
            GlobeAnchors(
                currentNode = locator.locate(nodeAddress),
                exit = locator.locate(exitIp),
            )
    }
}
