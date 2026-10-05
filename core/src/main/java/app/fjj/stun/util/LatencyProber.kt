package app.fjj.stun.util

import android.content.Context
import app.fjj.stun.repo.Profile
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.service.TProxyPorts
import app.fjj.stun.service.VpnConfigBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 连接期间的周期性延迟测量：复用 Go 侧 [myssh.SshTProxy.pingNodes] 的真握手延迟，
 * 与节点列表 / 详情弹窗显示的延迟完全一致（约 200ms 量级）。
 *
 * 早期实现用未 protect 的 Kotlin 裸 TCP 探针（Socket().connect），会被自家 VPN 路由进
 * 本地 tun / 代理环路，测得的是 3–5ms 的本地回环而非真实节点 RTT，导致底栏延迟与节点列表
 * 严重不符。现统一改为 Go 握手延迟，单一数据源，消除失真。
 *
 * ## 测到的是什么
 * 不是 ICMP ping。Go 侧 `testSingleNodeTrueLatency` 的计时覆盖
 * **TCP 建连 + SSH 握手（KEX/认证）+ 隧道内一次 HTTP 往返**，
 * 所以数值天然高于系统 ping（同一节点 200ms vs 30ms 是正常的）。
 * 详见 `LatencyBreakdown` —— 需要拆分展示时用那个。
 *
 * ## 为什么不受 tproxy 旁路影响
 * HTTP 流量由 Go 侧 `sshClient.Dial` 塞进**已建立的 SSH 通道**，不新建到公网地址的连接 ——
 * 没有数据包走到 `OUTPUT` 链上按公网目的地址匹配，旁路规则无从下手。
 * 这跟「旁路只作用于本进程 uid」无关：tproxy 在 `PREROUTING` 与 `OUTPUT` 同时生效，
 * 重定向本身不按 uid 限定。
 *
 * [ExitIpProbe] 则要经受这一层：它要回答「App 自己的流量实际从哪出去」，只有真走一遍
 * OS 栈才有答案。结果分两种，别把第二种当成常态：
 *  - **mark 可用**（`SO_MARK probe succeeded`）：`tproxy.sh` 的 `-m mark -j ACCEPT` 排在
 *    `_add_chain_jumps` 与 TPROXY/REDIRECT **之前**，只放行打了 mark 的隧道 socket；
 *    ExitIpProbe 的 socket 没打 mark，会继续走到 APP_CHAIN → TPROXY，
 *    **照常走隧道、显示远端 IP**。
 *  - **mark 不可用**（`SO_MARK probe FAILED`，降级 uid 放行）：同一 uid 下无法区分 App 的
 *    其它 socket，整个 App 被写进 `BYPASS_APPS_LIST` ⇒ 直连 ⇒ 显示**真实**出口 IP。
 *
 * 状态看 `MyTransparentProxyService` 的 `Rules: mark bypass ACTIVE|INACTIVE`。
 * 所以「延迟正常但出口 IP 是本地的」不是隧道坏了，是第二种情况 —— 查 `SO_MARK probe`
 * 那一行，而不是查规则或 GeoIP。
 *
 * 结果缓存在 [StunRepository.latencyMs]（-1 表示未测得），供底栏 / 详情 / 小组件读取。
 */
object LatencyProber {

    /**
     * 探测间隔。
     *
     * 60s 而非原先的 30s：每次探测都从零建一条 SSH 连接（`DialNode` 不复用任何连接），
     * 30s 一次等于持续的握手开销，弱网下还会与正常连接争抢资源。
     * 60s 对"感知连接质量变化"仍足够灵敏。
     */
    private const val INTERVAL_MS = 60_000L

    private const val TIMEOUT_MS = 8_000L
    private const val PING_URL = "http://cp.cloudflare.com/generate_204"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start(context: Context) {
        stop()
        job = scope.launch {
            while (isActive) {
                try {
                    val profile = ProfileManager.getSelectedProfile(context)
                    val res = probe(profile, context)
                    StunRepository.latencyMs.postValue(res?.totalMs ?: -1L)
                    if (res != null) {
                        StunRepository.recordLatencySample(res.totalMs)
                        lastBreakdown = res
                    }
                    StunRepository.recomputeConnectionQuality()
                } catch (_: Exception) {
                }
                delay(INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        lastBreakdown = null
        StunRepository.latencyMs.postValue(-1L)
        StunRepository.clearConnectionQuality()
    }

    /**
     * 最近一次成功探测的分段耗时，供详情面板解释"慢在握手还是慢在链路"。
     *
     * `@Volatile` 是必要的：写者是 IO 协程，读者是主线程 UI。
     * 失败或未探测过时为 null —— 此时不应显示分段（而不是显示 0）。
     */
    @Volatile
    var lastBreakdown: PingResults.Breakdown? = null
        private set

    /**
     * 通过 Go 侧 pingNodes 取得当前节点的真握手延迟（与节点列表同源）。
     *
     * 返回分段结构而非裸 Long，是为了让 [lastBreakdown] 与展示值来自**同一次**
     * 探测 —— 分两次调用会出现"总数是这次的、分段是上次的"的不一致。
     */
    private fun probe(profile: Profile, context: Context): PingResults.Breakdown? {
        return try {
            // 端口取自单一事实来源，不再自带一份可能过期的副本。
            // （原值 1080/53 与服务实际监听的 10808/10553 不符；Go 侧当前不读
            //  local_addr 所以尚未炸，但那是隐式依赖，不能靠运气维持。）
            val configJson = VpnConfigBuilder.buildMySshConfig(
                context,
                profile,
                TProxyPorts.SOCKS,
                TProxyPorts.DNS,
            )
            val reqArray = JSONArray().apply {
                put(JSONObject().put("id", profile.id).put("config", JSONObject(configJson)))
            }
            val resStr = StunRepository.proxy.pingNodes(reqArray.toString(), PING_URL, TIMEOUT_MS)
            PingResults.breakdown(resStr, profile.id)
        } catch (_: Exception) {
            null
        }
    }
}
