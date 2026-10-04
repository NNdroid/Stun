package app.fjj.stun.service

/**
 * 通知栏里「速率 / 连接数 / CPU」那一行的节流阀，VPN 与 tproxy 两种模式共用同一个实例语义。
 *
 * 背景：这两个服务各自抄了一份「记时间戳 + 超过间隔才刷」的逻辑，而节流间隔和
 * 「用户是否要显示测速」这道开关**都只抄了一半**：
 *
 * | | 节流间隔 | 读 `getShowNotificationSpeed` |
 * |---|---|---|
 * | `MyVpnService` | 2000ms | ✅ 关掉就完全不刷 |
 * | `MyTransparentProxyService`（改前） | 1000ms | ❌ 用户关了照刷 |
 *
 * 结果是同一个开关在两种模式下表现不同（切换服务模式时通知刷新频率会突变），而且用户
 * 关掉测速后 tproxy 仍然在刷。抽成这一个类之后，"两种模式同频、同开关"变成**结构上的必然**，
 * 不再依赖两处代码手写对齐 —— 间隔值和"要不要刷"的判断都在 [shouldRefresh] 里，测试也能直接断言。
 *
 * 无 Android 依赖（不读 SP、不碰 Context），因此可以用纯 JVM 单测钉死时间语义。
 */
internal class SpeedRefreshThrottle(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    private var lastRefreshMs = 0L

    /**
     * 本次是否应当刷新通知。返回 true 时**顺带推进时间戳**（所以别把一次调用拆成
     * "先问再刷"两处调用，否则第二次问会拿到 false）。
     *
     * @param nowMs 便于测试注入；生产路径用墙钟。
     */
    fun shouldRefresh(nowMs: Long = System.currentTimeMillis()): Boolean {
        // 写成 `delta in 0 until intervalMs` 而不是 `delta < intervalMs`：墙钟被往回调时
        // （NTP 校时、用户改系统时间）delta 是负数，后者会把通知按死到墙钟追上旧时间戳为止 ——
        // 一次校时就能让状态栏静默几分钟。负数一律放行。
        val delta = nowMs - lastRefreshMs
        if (delta in 0 until intervalMs) return false
        lastRefreshMs = nowMs
        return true
    }

    companion object {
        /**
         * 2000ms —— 与 MySSH 的 sysinfo/traffic 推送频率（秒级）同量级：1s 节流等于每条回调都刷，
         * 而通知更新要经主线程 + 系统 IPC，收益肉眼不可见、成本却是实打实的。
         *
         * 这个常量被 [MyVpnService] 与 [MyTransparentProxyService] 共用，改它等于同时改两种模式的观感，
         * 所以刻意写成一个具名常量，而不是散落在两个服务里的字面量。
         */
        const val DEFAULT_INTERVAL_MS = 2000L
    }
}
