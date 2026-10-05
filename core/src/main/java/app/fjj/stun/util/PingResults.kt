package app.fjj.stun.util

import android.content.Context
import app.fjj.stun.core.R
import org.json.JSONArray

/**
 * 解析 Go 侧 `pingNodes` 返回的结构化 JSON（`[]PingResult`）为 `id -> 展示文案`。
 *
 * 合并背景：原先 app / tv / car / xr 各有一份 `parsePingResults` 拷贝，
 * 且 **app/tv 是完整版**（按 `errorType` 细分 timeout / dns / tls / http 并本地化），
 * **car/xr 是退化版**（一律回退「网络错误」）。这里以完整版为准统一，
 * car/xr 顺带升级到同样的语义；新增平台不必再抄一遍。
 *
 * 只依赖 `Context`（取本地化文案），可用 Robolectric 单测。
 */
object PingResults {

    /**
     * 一次探测的分段耗时。
     *
     * Go 侧 [myssh.latency] 把延迟拆成 **SSH 握手**（TCP + KEX + 认证）与
     * **隧道内 HTTP 往返** 两段，因为它们的含义完全不同：前者是连接建立成本
     * （跨洋节点里常占大头），后者才近似网络 RTT。混成一个数字时，用户看到
     * 「Stun 显示 200ms、系统 ping 只有 30ms」会以为是 bug。
     *
     * [totalMs] 恒等于两段之和（缺失时回落到 `latencyMs`），所以既有展示
     * 完全不受影响 —— 这是**加性**改动，老版本 Go 侧不返回分段时也能安全降级。
     */
    data class Breakdown(
        val totalMs: Long,
        val handshakeMs: Long,
        val httpMs: Long,
    ) {
        /**
         * 握手占比（0f~1f）。用于判断"慢在握手还是慢在链路"：
         * 占比高说明该优化的是连接复用/握手次数，而非换节点。
         *
         * 分段数据缺失（老版本 Go 侧）时返回 -1f，调用方据此隐藏分段 UI。
         */
        val handshakeShare: Float
            get() = if (handshakeMs < 0 || httpMs < 0 || totalMs <= 0) -1f
            else handshakeMs.toFloat() / totalMs.toFloat()

        val hasBreakdown: Boolean
            get() = handshakeMs >= 0 && httpMs >= 0
    }

    /**
     * 取出指定节点的分段耗时；节点不存在、失败或数据缺失时返回 null。
     *
     * 与 [parse] 分离：前者供**详情类**界面做分段展示，后者只出单行文案。
     */
    fun breakdown(jsonStr: String, nodeId: String): Breakdown? {
        return try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optString("id", "") != nodeId) continue
                if (!obj.optBoolean("ok", false)) return null
                val total = obj.optLong("latencyMs", -1L)
                // 必须用 <= 0 而不是 < 0：`latencyMs: 0` 不是有效测量（Go 侧
                // `time.Since(...).Milliseconds()` 在亚毫秒时会截断成 0，但也可能是
                // 上游漏填）。放行它会让 UI 显示 "0ms"，读起来像"节点极快"，
                // 与事实相反 —— 缺测量就该显示未测得。
                if (total <= 0) return null
                // 老版本 Go 侧没有这两个键：optLong 返回 -1，据此判定"无分段数据"。
                val handshake = obj.optLong("handshakeMs", -1L)
                val http = obj.optLong("httpMs", -1L)
                return Breakdown(total, handshake, http)
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun parse(context: Context, jsonStr: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id", "")
                if (id.isEmpty()) continue
                map[id] = if (obj.optBoolean("ok", false)) {
                    // 成功却没给 latencyMs（默认 -1）时不要显示 "0ms" ——
                    // 那读起来像"节点极快"。降级为「未测得」文案。
                    val ms = obj.optLong("latencyMs", -1L)
                    if (ms > 0) {
                        context.getString(R.string.latency_format, ms)
                    } else {
                        context.getString(R.string.latency_timeout)
                    }
                } else {
                    when (obj.optString("errorType", "other")) {
                        "timeout" -> context.getString(R.string.latency_timeout)
                        "connrefused" -> context.getString(R.string.latency_conn_refused)
                        "tls" -> context.getString(R.string.latency_ssl_error)
                        "dns" -> context.getString(R.string.latency_dns_error)
                        // HTTP 状态码不是可翻译文案，直接透出 Go 侧给的状态串
                        "http" -> "HTTP ${obj.optString("error", "")}"
                        else -> context.getString(R.string.latency_network_error)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return map
    }
}
