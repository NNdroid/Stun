package app.fjj.stun.util

import android.content.Context
import androidx.lifecycle.MutableLiveData
import app.fjj.stun.repo.Profile
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.service.VpnConfigBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import myssh.SpeedTestProgressCallback
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * 真正的带宽测速（下行 + 上行）。
 *
 * 与“测速=延迟探测”（[LatencyProber] / pingNodes）不同，这里走 Go 侧 [myssh.SshTProxy.speedTest]：
 * 先 [VpnConfigBuilder.buildMySshConfig] 建好节点隧道，再经 SSH 隧道把 HTTP 流量导出去，
 * 因此测到的是「本机 → 节点 → 出口」的隧道真实吞吐，而不是本机网卡直连速率。
 *
 * 进度从 Go 测速连接直接返回，不依赖当前 VPN 的数据库流量计数。
 */
object SpeedTestManager {

    private const val SOCKS_PORT = 1080
    private const val DNS_PORT = 53
    private const val MAX_SAMPLES = 240 // ~2 分钟窗口

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    val running = MutableLiveData(false)
    val result = MutableLiveData<SpeedTestResult?>(null)
    val phase = MutableLiveData(SpeedTestPhase.IDLE)
    val downProgressMbps = MutableLiveData<Double?>(null)
    val upProgressMbps = MutableLiveData<Double?>(null)
    /** 实时速率曲线样本：每个采样点的瞬时吞吐（bps）。 */
    val samples = MutableLiveData<List<Long>>(emptyList())

    /** 解析 Go speedTest 返回的 JSON（字段见 myssh.SpeedTestResult）。 */
    fun parse(json: String?): SpeedTestResult {
        if (json.isNullOrBlank()) return SpeedTestResult.unknown
        return runCatching {
            val o = JSONObject(json)
            SpeedTestResult(
                ok = o.optBoolean("ok", false),
                downMbps = o.optDouble("downMbps", 0.0),
                upMbps = o.optDouble("upMbps", 0.0),
                downBps = o.optDouble("downBps", 0.0),
                upBps = o.optDouble("upBps", 0.0),
                bytesDown = o.optLong("bytesDown", 0),
                bytesUp = o.optLong("bytesUp", 0),
                durationMs = o.optLong("durationMs", 0),
                error = o.optString("error", "")
            )
        }.getOrDefault(SpeedTestResult.unknown)
    }

    fun run(context: Context, profile: Profile) {
        if (running.value == true) return
        job?.cancel()
        result.postValue(null)
        samples.postValue(emptyList())
        phase.postValue(SpeedTestPhase.DOWNLOAD)
        downProgressMbps.postValue(null)
        upProgressMbps.postValue(null)
        running.postValue(true)
        job = scope.launch {
            try {
                val configJson = VpnConfigBuilder.buildMySshConfig(context, profile, SOCKS_PORT, DNS_PORT)
                val configuredDownUrl = SettingsManager.getSpeedTestDownUrl(context)
                val downBytes = SettingsManager.getSpeedTestDownBytes(context).coerceAtLeast(1L)
                val downUrl = if (configuredDownUrl.startsWith("https://speed.cloudflare.com/__down?")) {
                    configuredDownUrl.replace(Regex("bytes=\\d+"), "bytes=$downBytes")
                } else configuredDownUrl
                val upUrl = SettingsManager.getSpeedTestUpUrl(context)
                val upBytes = SettingsManager.getSpeedTestUpBytes(context)
                val timeoutMs = SettingsManager.getSpeedTestTimeoutMs(context)
                val buf = ArrayDeque<Long>()
                var lastPhase = ""
                var lastBytes = 0L
                var lastElapsed = 0L
                val callback = object : SpeedTestProgressCallback {
                    override fun onSpeedTestProgress(stage: String, transferred: Long, elapsedMs: Long) {
                        val currentPhase = if (stage.startsWith("download")) SpeedTestPhase.DOWNLOAD else SpeedTestPhase.UPLOAD
                        if (stage != lastPhase && !stage.endsWith("_done")) {
                            lastPhase = stage
                            lastBytes = 0L
                            lastElapsed = 0L
                            buf.clear()
                            samples.postValue(emptyList())
                            phase.postValue(currentPhase)
                        }
                        val mbps = if (elapsedMs > 0) transferred * 8.0 / elapsedMs / 1000.0 else 0.0
                        if (currentPhase == SpeedTestPhase.DOWNLOAD) downProgressMbps.postValue(mbps)
                        else upProgressMbps.postValue(mbps)
                        if (stage.endsWith("_done")) return
                        if (elapsedMs > lastElapsed && transferred > lastBytes) {
                            val bps = ((transferred - lastBytes) * 8000.0 / (elapsedMs - lastElapsed)).toLong()
                            buf.addLast(bps)
                            while (buf.size > MAX_SAMPLES) buf.removeFirst()
                            samples.postValue(buf.toList())
                            lastBytes = transferred
                            lastElapsed = elapsedMs
                        }
                    }
                }
                val json = StunRepository.proxy.speedTestWithProgress(configJson, downUrl, upUrl, upBytes, timeoutMs, callback)
                result.postValue(parse(json))
            } catch (e: Exception) {
                result.postValue(SpeedTestResult.unknown.copy(error = e.message ?: "exception"))
            } finally {
                phase.postValue(SpeedTestPhase.IDLE)
                running.postValue(false)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        phase.postValue(SpeedTestPhase.IDLE)
        running.postValue(false)
    }
}

enum class SpeedTestPhase { IDLE, DOWNLOAD, UPLOAD }

data class SpeedTestResult(
    val ok: Boolean,
    val downMbps: Double,
    val upMbps: Double,
    val downBps: Double,
    val upBps: Double,
    val bytesDown: Long,
    val bytesUp: Long,
    val durationMs: Long,
    val error: String
) {
    companion object {
        val unknown = SpeedTestResult(false, 0.0, 0.0, 0.0, 0.0, 0, 0, 0, "")
    }
}
