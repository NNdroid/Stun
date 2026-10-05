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
 * ## 为什么不受 tproxy bypass 影响
 * 探测流量由 Go 侧 `sshClient.Dial` 在**已建立的 SSH 通道内**发出，
 * 而 tproxy 的 bypass 只作用于 iptables `OUTPUT` 链（针对本进程 UID）。
 * 与 `ExitIpProbe` 相反：那个用 `HttpURLConnection` 从本进程发出，
 * 在 tproxy 模式下必然被 bypass、拿到本地出口 IP。
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
