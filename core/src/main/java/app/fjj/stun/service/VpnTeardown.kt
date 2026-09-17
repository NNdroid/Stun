package app.fjj.stun.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * VPN 服务销毁时的收尾编排。
 *
 * 背景：`onDestroy` 跑在主线程，原来是一把 `runBlocking { saveFinalTrafficStats();
 * cleanupNativeResources() }` —— 主线程被按住直到**整段**收尾跑完。而 `cleanupNativeResources`
 * 里的 `TTunnelStopService()` / `proxy.stop()` 是 native 调用，耗时不可控（还要负责把卡在
 * `wgWait` 里的会话循环踹醒），撞上系统收服务或看门狗强制停服时就是主线程无界阻塞。
 *
 * 这里按「数据的必要性」把两件事拆成两种等待策略：
 *
 * ① **流量统计必须落库**（`saveFinalTrafficStats` 走 Room，而 `AppDatabase` 没开
 *    `allowMainThreadQueries`，所以只能留在 IO），因此给它一个**有界等待**：既保证
 *    「最后一次回调到会话结束」的残差不会丢，又不让主线程被无限期按住。
 *    正常路径上 `stopVpnService` / 循环 `finally` 已经写过一遍，delta 为 0，
 *    这一步几乎零成本；只有异常路径才真的发生写库。
 * ② **原生资源释放不再等**：释放的都是本进程内资源（TUN fd / Go proxy / HEV），
 *    进程真被回收时内核会一并回收，没有为它冻结主线程的必要。正常路径同样已清过两遍，
 *    这里只是兜底，且它不能拖在写库后面 —— 它得尽早去踹醒 `wgWait`。
 *
 * 抽成独立对象是为了让这套时序契约能在纯 JVM 单测里钉死（[MyVpnService] 本体依赖 JNI，
 * 测试里无法实例化）。
 */
internal object VpnTeardown {

    /**
     * 统计落库的等待上限。
     *
     * 取值依据：ANR 阈值是秒级（输入 5s / 前台服务 20s），300ms 远在其下；而这里等的是
     * 一次**热** Room UPDATE（会话期间 `updateStats` 每秒都在写同一行，DB 早已打开、
     * page cache 已热），实测量级是个位数毫秒，300ms 是给低端机 + 主线程本身也在忙
     * 留的余量。超时**不取消**写库任务（见 [run]），所以超时最坏只丢"这一次的计时"，
     * 不影响数据最终落库。
     */
    const val STATS_FLUSH_TIMEOUT_MS = 300L

    /**
     * 执行收尾。**会阻塞调用线程至多 [timeoutMs]**（正常路径接近 0），
     * 其中只为统计落库等待，原生资源释放为纯异步。
     *
     * @param flushStats 必须落库的统计写入（Room，跑在 IO 上）
     * @param cleanupNative 本机资源释放（幂等、可异步、失败不回抛）
     * @param onError 两类任务抛 `Throwable` 时的上报口（收尾路径不该因为异常再炸一次，
     *                所以连 `Error` 一起吞 —— 例如 JNI 库不可用时抛的是 `UnsatisfiedLinkError`）
     * @return 调用线程实际被阻塞的毫秒数，供调用方打点
     */
    fun run(
        flushStats: suspend () -> Unit,
        cleanupNative: suspend () -> Unit,
        timeoutMs: Long = STATS_FLUSH_TIMEOUT_MS,
        onError: (String, Throwable) -> Unit = { _, _ -> },
    ): Long {
        // 收尾专用作用域：**刻意不挂在调用方的 serviceScope 下**（调用方紧接着就会 cancel 它），
        // 也**刻意不 cancel 自己** —— cancel 会把还在跑的统计写库一起掐掉。两个任务各自都有
        // Throwable 兜底、不会向作用域传播失败，跑完即无人引用，交给 GC 即可。
        val scope = CoroutineScope(Dispatchers.IO)
        val startNs = System.nanoTime()

        val statsJob = scope.launch {
            try {
                flushStats()
            } catch (t: Throwable) {
                onError("stats flush", t)
            }
        }
        // 与统计并行：释放资源要负责踹醒 wgWait 里的会话循环，拖在写库后面等于白白延后它。
        scope.launch {
            try {
                cleanupNative()
            } catch (t: Throwable) {
                onError("native cleanup", t)
            }
        }

        // 有界等待：超时只结束"等待"本身，statsJob 仍在后台把库写完（数据不丢）。
        runBlocking {
            withTimeoutOrNull(timeoutMs) { statsJob.join() }
        }
        return (System.nanoTime() - startNs) / 1_000_000
    }
}
