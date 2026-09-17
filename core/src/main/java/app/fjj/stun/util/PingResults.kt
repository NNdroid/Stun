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

    fun parse(context: Context, jsonStr: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id", "")
                if (id.isEmpty()) continue
                map[id] = if (obj.optBoolean("ok", false)) {
                    context.getString(R.string.latency_format, obj.optLong("latencyMs", 0))
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
