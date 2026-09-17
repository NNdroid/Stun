package app.fjj.stun.geo

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.time.OffsetDateTime

/**
 * Go 侧 `globalTrafficManager.activeMap` 的一条快照（`myssh.Myssh.getActiveConnectionsJSON()` 导出）。
 *
 * 字段与 Go 的 `connInfoExport` 一一对应，**原样透传**：
 * 连接可能来自 TCP（`WrapConn`）也可能来自 UDP（`WrapPacketConn`），
 * 两条路径给 [proxyAddr] 填的语义不同（远端地址 / 本地地址），所以展示层别依赖它。
 *
 * @property targetAddr `host:port` 原始目标地址，TCP 与 UDP 都有。
 * @property targetHost Go 用 `net.SplitHostPort` 切出来的**纯 host**。注意它的三种取值：
 *   切得动 → 纯 host；切不动且原串不是 IP 字面量 → 原串；切不动且原串是 IP 字面量 → 空串。
 *   所以想拿可用的目标主机**必须走 [host]**，不要直接读这个字段。
 * @property startedAtMillis `start_time`（Go `time.Time`，RFC3339 带纳秒）。解析失败或为
 *   零值时间时记 0，绝不抛 —— 连接时长对本视图只是锦上添花。
 * @property readBytes / [writeBytes] 该连接累计读写字节数，用来给弧线定粗细与脉冲速度。
 */
data class ActiveConnection(
    val id: Long,
    val targetAddr: String,
    val targetHost: String,
    val proxyAddr: String,
    val startedAtMillis: Long,
    val readBytes: Long,
    val writeBytes: Long,
) {
    /** 可用于地理查询的目标主机（IP 字面量或域名）；完全取不到时为空串。 */
    val host: String get() = targetHost.ifBlank { hostOf(targetAddr) }

    /** 该连接跑过的总字节数，弧线粗细与脉冲速度的输入。 */
    val totalBytes: Long get() = readBytes + writeBytes
}

/**
 * 从 `host[:port]` 形式的地址里切出 host。**绝不触发 DNS。**
 *
 * 三种形态都要认，光靠"数冒号"会错一半：
 * - `185.248.33.40:443`  → `185.248.33.40`
 * - `[2400:3200::1]:22`  → `2400:3200::1`（带方括号的 IPv6 必须靠**括号**定位，否则冒号分不清）
 * - `2400:3200::1`       → `2400:3200::1`（裸 IPv6 没有端口，冒号是地址的一部分）
 */
fun hostOf(raw: String): String {
    val text = raw.trim()
    if (text.isEmpty()) return ""
    if (text.startsWith('[')) {
        val end = text.indexOf(']')
        return if (end > 1) text.substring(1, end) else text
    }
    // `host:port` 恰好一个冒号；裸 IPv6 至少两个。这一个判据就能把两者分开。
    return if (text.count { it == ':' } == 1) text.substringBefore(':') else text
}

/**
 * 解析 Go 导出的活跃连接 JSON。
 *
 * **契约：绝不抛。** 地球是装饰件：Go 侧改个字段名、或某一条记录烂掉，
 * 正确行为是"少画几个点"，而不是把连接详情面板打崩。
 * 因此逐条独立解析（烂条目只丢自己），整段格式错误才退化成空列表。
 */
object ActiveConnections {

    /** 单次解析的条目上限。活跃连接正常是几十条，这个上限只为防病态输入把内存吃满。 */
    private const val MAX_ITEMS = 4096

    /**
     * 原始 JSON 长度上限，**故意与 [MAX_ITEMS] 对齐**：4096 条 × 每条约 150B ≈ 600KB，
     * 这里留一倍余量。也就是说两条上限只在"病态输入"上才会生效，正常数据谁也碰不到。
     */
    private const val MAX_RAW_CHARS = 1 shl 20

    /**
     * @return 解析出的连接；**永远非 null**。没有任何可用目标主机的条目会被丢弃
     *   （画不到地球上的点没有意义，留着只会让下游多一层判空）。
     */
    fun parse(raw: String?): List<ActiveConnection> {
        if (raw.isNullOrBlank()) return emptyList()
        // 超长直接放弃，**不截断**：截断后的 JSON 语法必然残缺，Gson 一样解不出来，
        // 白白多花一次解析；更糟的是它把"病态输入"伪装成了"没有活跃连接"。
        // 放弃是同样的结果，但意图是明确的。
        if (raw.length > MAX_RAW_CHARS) return emptyList()

        val array = runCatching { JsonParser.parseString(raw) }.getOrNull() as? JsonArray
            ?: return emptyList()
        val count = minOf(array.size(), MAX_ITEMS)
        if (count == 0) return emptyList()

        val out = ArrayList<ActiveConnection>(count)
        for (i in 0 until count) {
            val obj = array.get(i) as? JsonObject ?: continue
            val connection = obj.toActiveConnection()
            if (connection.host.isNotEmpty()) out += connection
        }
        return out
    }

    private fun JsonObject.toActiveConnection(): ActiveConnection = ActiveConnection(
        id = this["id"].asLongSafe() ?: 0L,
        targetAddr = this["target_addr"].asStringSafe(),
        targetHost = this["target_host"].asStringSafe(),
        proxyAddr = this["proxy_addr"].asStringSafe(),
        startedAtMillis = parseGoTime(this["start_time"].asStringSafe()),
        readBytes = this["read_bytes"].asLongSafe() ?: 0L,
        writeBytes = this["write_bytes"].asLongSafe() ?: 0L,
    )
}

/** 缺字段、类型不对（比如把数字写成了非数字字符串）一律当"没有"。 */
private fun JsonElement?.asLongSafe(): Long? =
    (this as? JsonPrimitive)?.let { runCatching { it.asLong }.getOrNull() }

/** 同上，取字符串。`null` 字面量不是 [JsonPrimitive]，会自然落到空串。 */
private fun JsonElement?.asStringSafe(): String =
    (this as? JsonPrimitive)?.let { runCatching { it.asString }.getOrNull() }.orEmpty()

/**
 * 解析 Go `time.Time` 的 RFC3339 表示（带纳秒与偏移，如 `2026-09-15T21:00:00.123456789+08:00`）。
 *
 * 解析失败**记 0 而不是抛**：时间戳只影响排序，不值得为它中断整条解析链。
 * 零值时间（`0001-01-01T00:00:00Z`）会解成一个大负数，这里一并归零 ——
 * 否则下游看到"连接于公元前 1 年建立"会做出很奇怪的排序。
 */
private fun parseGoTime(raw: String): Long {
    if (raw.isBlank()) return 0L
    val millis = runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull() ?: return 0L
    return if (millis < 0L) 0L else millis
}
