package app.fjj.stun.util

import android.content.Context
import app.fjj.stun.repo.Profile
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.StunRepository
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
 * 结果缓存在 [StunRepository.latencyMs]（-1 表示未测得），供底栏 / 详情 / 小组件读取。
 */
object LatencyProber {

    private const val INTERVAL_MS = 30_000L
    private const val TIMEOUT_MS = 8_000L
    private const val PING_URL = "http://cp.cloudflare.com/generate_204"
    private const val SOCKS_PORT = 1080
    private const val DNS_PORT = 53

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start(context: Context) {
        stop()
        job = scope.launch {
            while (isActive) {
                try {
                    val profile = ProfileManager.getSelectedProfile(context)
                    val lat = probe(profile, context)
                    StunRepository.latencyMs.postValue(lat ?: -1L)
                    if (lat != null && lat >= 0L) StunRepository.recordLatencySample(lat)
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
        StunRepository.latencyMs.postValue(-1L)
        StunRepository.clearConnectionQuality()
    }

    /** 通过 Go 侧 pingNodes 取得当前节点的真握手延迟（与节点列表同源）。 */
    private fun probe(profile: Profile, context: Context): Long? {
        return try {
            val configJson = VpnConfigBuilder.buildMySshConfig(context, profile, SOCKS_PORT, DNS_PORT)
            val reqArray = JSONArray().apply {
                put(JSONObject().put("id", profile.id).put("config", JSONObject(configJson)))
            }
            val resStr = StunRepository.proxy.pingNodes(reqArray.toString(), PING_URL, TIMEOUT_MS)
            val arr = JSONArray(resStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optString("id", "") == profile.id && obj.optBoolean("ok", false)) {
                    val l = obj.optLong("latencyMs", -1)
                    if (l >= 0) return l
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
