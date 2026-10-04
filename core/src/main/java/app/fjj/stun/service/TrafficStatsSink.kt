package app.fjj.stun.service

import android.content.Context
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunRepository

/**
 * 流量统计的**唯一落库口**，两种服务模式共用一个实例语义。
 *
 * ## 为什么必须是同一个类
 * 这段逻辑原先在 `MyVpnService.updateStats` 与 `MyTransparentProxyService.updateStats` 里各写一份，
 * 而且**已经漂移出三个真实差异**（不是风格问题，是行为不一致）：
 *
 * | | VPN | tproxy（改前） | 后果 |
 * |---|---|---|---|
 * | 计数器回绕时 delta | `else 0L` | `else txTotal` | Go 侧计数器重置时把**整个累计值**再写一遍库，流量虚增 |
 * | 落库守卫 | `if (deltaTx > 0 \|\| deltaRx > 0)` | 无 | 没有流量时每个回调都写一行库 |
 * | `StunRepository.txTotal/rxTotal` | `postValue` | **漏了** | tproxy 模式下 UI 的累计流量**从不刷新** |
 *
 * 三处差异都不会编译报错，只在真机上表现为"数字不对"。收口之后，"delta 怎么算"这种
 * 规则只有一份实现，改一处就够。
 *
 * ## 生命周期语义（刻意）
 * `lastTx/lastRx` 是**实例字段**且不在重连时清零：Go 的 TxTotal/RxTotal 是进程级全局单调
 * 计数器，会话开始时若把基准清零，会把整个累计值当成"本次会话的流量"重新写库，跨重连重复计数。
 * 实例随 Service 一起活着，所以重连时基准自然保留；只有进程重启才归零 —— 那时 Go 侧计数器
 * 也同样从 0 开始，基准与计数器仍然对齐。
 */
internal class TrafficStatsSink(context: Context) {

    private val appContext = context.applicationContext
    private var lastTx = 0L
    private var lastRx = 0L

    /**
     * 吃掉一帧 MySSH 的流量回调：算增量、落库、刷新 LiveData。
     *
     * 必须在 IO 上调用（内部会写 Room，而 `AppDatabase` 没开 `allowMainThreadQueries`）。
     */
    fun ingest(txRate: Long, rxRate: Long, txTotal: Long, rxTotal: Long) {
        val s = step(lastTx, lastRx, txTotal, rxTotal)
        persist(s.dTx, s.dRx)
        advance(s)

        StunRepository.txRate.postValue(txRate)
        StunRepository.rxRate.postValue(rxRate)
        StunRepository.txTotal.postValue(txTotal)
        StunRepository.rxTotal.postValue(rxTotal)
        StunRepository.recordRateSample(txRate, rxRate)
    }

    /**
     * 会话结束前补齐「最后一次 [ingest] 回调 → 现在」之间的那一小段流量。
     *
     * 为什么需要它：MySSH 的统计回调是周期性的，停止服务时距离上一帧通常还差几十 KB。
     * 这段既没进过 [ingest]，也不会再有下一帧 —— 不补就永久丢在库里之外。
     *
     * 基准（`lastTx/lastRx`）是私有字段、刻意不暴露：一旦让调用方自己算 delta，
     * "回绕返回 0"这条规则就会重新散落到各个 Service 去，于是又出现第二份实现。
     * 所以补齐也必须走这里，复用同一条 [delta] 规则。
     *
     * 必须在 IO 上调用（同 [ingest]，内部写 Room）。
     */
    fun flushPending(txTotal: Long, rxTotal: Long) {
        val s = step(lastTx, lastRx, txTotal, rxTotal)
        persist(s.dTx, s.dRx)
        advance(s)
    }

    /** 把新基准写回字段。两个入口都调它 —— 漏一处就会重复落库同一段流量。 */
    private fun advance(s: Step) {
        lastTx = s.baseTx
        lastRx = s.baseRx
    }

    /** 增量落库的**唯一**写点：两个 public 入口共用，避免守卫条件在两处各写一遍后漂移。 */
    private fun persist(deltaTx: Long, deltaRx: Long) {
        if (deltaTx <= 0L && deltaRx <= 0L) return
        SettingsManager.getSelectedProfileId(appContext)?.let { id ->
            ProfileManager.addTrafficStats(appContext, id, deltaTx, deltaRx)
        }
    }

    companion object {
        /** 一次"吃进新总量"的完整结果：增量 + 新基准。 */
        internal data class Step(val dTx: Long, val dRx: Long, val baseTx: Long, val baseRx: Long)

        /**
         * 一次基准推进的完整结果。
         *
         * 抽成纯函数是为了让 [flushPending] 的语义也能被纯 JVM 测试钉住（它本身要 Context + Room）。
         * 增量与基准必须**同一个函数**产出 —— 若调用方分开算，迟早会出现"落库用了旧基准、
         * 字段却推到新基准"这种双写 bug，而这种 bug 在真机上只是流量对不上，不会崩。
         */
        internal fun step(prevTx: Long, prevRx: Long, newTx: Long, newRx: Long) = Step(
            dTx = delta(prevTx, newTx),
            dRx = delta(prevRx, newRx),
            baseTx = newTx,
            baseRx = newRx,
        )

        /**
         * 逐帧差值。计数器**回绕/重置**时（`current < previous`）返回 0 而不是 `current` ——
         * 否则一次重置就会把当前累计值整段当成增量写进库。
         */
        fun delta(previous: Long, current: Long): Long =
            if (current >= previous) current - previous else 0L
    }
}
