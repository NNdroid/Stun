package app.fjj.stun.geo

import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 域名 → IP 字面量。
 *
 * 抽成接口有两个理由：
 * 1. 单测可以注入假实现，完全不碰真实 DNS；
 * 2. 把"这一处到底会不会发 DNS 查询"变成**显式**的决策点。本项目铁律是
 *    订阅里的服务器地址绝不解析域名（一次解析就把"用户在连哪台机器"泄露给 DNS），
 *    只有活跃连接 / 节点地址这条链路上才允许 —— 接口的存在让这件事必须被写出来，
 *    而不是藏在一行 `InetAddress.getByName` 里。
 */
fun interface HostAddressLookup {

    /** 解析失败返回 null；**绝不抛**。 */
    fun lookup(host: String): String?

    /**
     * 批量解析，**实现可以并发**。默认实现退化成逐个串行调用，所以假实现什么都不用写。
     *
     * 存在的理由：订阅里几百个节点全是域名时，串行 × 单个 1.5s 上限 = 分钟级等待，
     * 而地球是前台 UI。并发 + 总时长预算把最坏情况钉在 [budgetMillis] 附近。
     *
     * @param budgetMillis 整批的总时长上限；超预算还没轮上的主机**不算失败**，
     *   返回 null 但不缓存，下次刷新会继续尝试。
     * @return 键为**传入字符串去掉首尾空白后**的形式（只做 trim，不做大小写归一化）；
     *   前后大小写不同但指向同一主机的输入会合并成一次查询，再回填给每个键。
     */
    fun lookupAll(hosts: Collection<String>, budgetMillis: Long): Map<String, String?> =
        hosts.associateWith { lookup(it) }
}

/**
 * host（IP 字面量或域名）→ 地球坐标。是全拓扑标记与活跃连接标记**共用**的唯一入口。
 *
 * 分工严格：
 * - **IP 字面量**：只走 [geo]（mmdb，mmap 离线读）。**零 DNS**。
 * - **域名**：先走 [dns] 拿 IP，再走 [geo]。
 * - 地理库不可用（用户还没下载）时直接返回 null：这时候去解析域名只是白耗一次查询。
 *
 * **绝不抛**：任何一步失败都返回 null，最坏结果就是从地球上少画一个点。
 *
 * 线程约定：[locate] 命中域名分支时会做网络操作，所以**必须在后台线程调用**；
 * 批量场景优先用 [locateAll]（并发 + 有总时长预算）。
 */
class GeoLocator(
    private val geo: GeoResolver,
    private val dns: HostAddressLookup = SystemHostAddressLookup,
) {

    val available: Boolean get() = geo.available

    /** 单点定位。入参可以是 `host`、`host:port`、`[v6]:port`；端口/方括号会被自动剥掉。 */
    fun locate(host: String?): GeoPoint? {
        if (!geo.available) return null
        val text = hostOf(host.orEmpty())
        if (text.isEmpty()) return null
        return resolveText(text)
    }

    /**
     * 批量定位。域名部分交给 [HostAddressLookup.lookupAll] 并发解析，受 [budgetMillis] 约束。
     *
     * 返回的键是**调用方传入的原字符串**，取值可能为 null（解析不出/超预算）。
     * 之所以不 `.filterValues { it != null }`：调用方需要"这个地址我试过了，但没结果"
     * 与"这个地址我没传"这两件事可区分。
     */
    fun locateAll(
        hosts: Collection<String>,
        budgetMillis: Long = DEFAULT_BULK_BUDGET_MS,
    ): Map<String, GeoPoint?> {
        val out = HashMap<String, GeoPoint?>(hosts.size * 2)
        if (!geo.available) {
            hosts.forEach { out[it] = null }
            return out
        }

        // 先分拣：IP 字面量当场用离线库搞定，只有真域名才交给 DNS。
        // 这个分拣同时守住了"脏数字串不许落到 DNS 分支"这条铁律。
        val needDns = LinkedHashMap<String, String>() // 原字符串 → 剥好的 host
        hosts.forEach { raw ->
            val text = hostOf(raw)
            when {
                text.isEmpty() -> out[raw] = null
                // 是 IP 字面量 → 只查离线库。**查到与否都不去 DNS**：
                // 库里没有这条记录不代表它是个域名。
                isIpLiteral(text) -> out[raw] = geo.resolve(text)
                // 看着像地址却不是合法 IP → 脏数据，同样不许去 DNS。
                isNumericLooking(text) -> out[raw] = null
                else -> if (!needDns.containsKey(raw)) needDns[raw] = text
            }
        }

        if (needDns.isNotEmpty()) {
            val ips = dns.lookupAll(needDns.values, budgetMillis)
            needDns.forEach { (raw, text) ->
                val ip = ips[text]
                out[raw] = if (ip == null) null else geo.resolve(ip)
            }
        }

        return out
    }

    // ------------------------------------------------------------------ 内部

    private fun resolveText(text: String): GeoPoint? {
        if (text.isEmpty()) return null
        if (isIpLiteral(text)) return geo.resolve(text)
        if (isNumericLooking(text)) return null
        val ip = dns.lookup(text) ?: return null
        return geo.resolve(ip)
    }

    /**
     * 是不是 IP 字面量。必须用 [MmdbReader.packIp] 判定：它自己逐段校验八位组，
     * 不像 `InetAddress` 会把 `999.1.1.1` 当主机名拿去查 DNS。
     */
    private fun isIpLiteral(text: String): Boolean = MmdbReader.packIp(text) != null

    /**
     * 看着像数字地址（只由数字和点组成）。
     *
     * 这类字符串要么是合法 IP（上面已处理），要么是脏数据（`999.1.1.1` / `1.2.3` / `1.2.3.4.5`）。
     * 而脏数据一旦落进 DNS 分支，`InetAddress` 会把它当**主机名**去查 —— 正好踩中要避免的泄露。
     * 真正的域名不可能只由数字和点组成，所以这个判据不会误伤。
     */
    private fun isNumericLooking(text: String): Boolean = text.all { it.isDigit() || it == '.' }

    companion object {
        /** 批量定位的默认总时长预算。超时的那部分返回 null，下次刷新再补上。 */
        const val DEFAULT_BULK_BUDGET_MS = 2_500L
    }
}

/**
 * 默认的域名解析：`InetAddress.getByName` + 进程内缓存 + **并发批量 + 超时上限**。
 *
 * 为什么要自带线程池和超时：`InetAddress.getByName` 没有超时参数，而 Android 的解析器在
 * 查询无应答时会自己重试若干轮，实测能拖到十几秒。VPN 起来之后 DNS 报文还会被导进隧道，
 * 更容易卡。地球是"边构建边显示"的，不能因为一个域名卡住整条链路。
 *
 * 池子固定 4 个 daemon 线程：
 * - 单个查询有 [TIMEOUT_MS] 上限；
 * - 整批有调用方给的预算上限（见 [lookupAll]）；
 * - 万一某个域名真把线程卡死，剩下的还能继续，而且**失败（含超时）也会被缓存**，
 *   所以同一个卡死域名最多牺牲一个线程，不会被反复重试。
 *
 * ⚠️ [lookupAll] 的任务里**直接调 `InetAddress.getByName`，绝不回头调 [lookup]**：
 * 后者也是往同一个池子里提交任务，嵌套提交会在池子被占满时自锁。
 *
 * 缓存不设上界是有意的：键是"用户连过/订阅过的主机名"，量级几十个；
 * 加淘汰策略只会引入竞态和复杂度，换不来实际收益。换网络后可用 [clear] 手动失效。
 */
object SystemHostAddressLookup : HostAddressLookup {

    /** [ConcurrentHashMap] 装不了 null，用空串当"已知查不到"的哨兵。 */
    private const val MISS = ""

    private const val TIMEOUT_MS = 1_500L

    private val cache = ConcurrentHashMap<String, String>()

    private val executor = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "globe-host-lookup").apply { isDaemon = true }
    }

    override fun lookup(host: String): String? {
        val key = host.trim().lowercase()
        if (key.isEmpty()) return null
        cache[key]?.let { return it.ifEmpty { null } }

        val resolved = runCatching {
            executor.submit<String?> { resolveRaw(key) }.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrNull()

        cache[key] = resolved.orEmpty()
        return resolved
    }

    override fun lookupAll(hosts: Collection<String>, budgetMillis: Long): Map<String, String?> {
        val trimmed = hosts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (trimmed.isEmpty()) return emptyMap()
        // 大小写不同但其实是同一台主机的输入要合并成一次查询，最后再回填给每个原字符串。
        val byKey = trimmed.groupBy { it.lowercase() }

        val resolvedByKey = HashMap<String, String?>(byKey.size * 2)
        val pendingKeys = ArrayList<String>(byKey.size)
        byKey.forEach { (key, _) ->
            val cached = cache[key]
            if (cached != null) resolvedByKey[key] = cached.ifEmpty { null } else pendingKeys += key
        }

        if (pendingKeys.isNotEmpty()) {
            val futures = runCatching {
                executor.invokeAll(
                    pendingKeys.map { key -> Callable { key to resolveRaw(key) } },
                    budgetMillis,
                    TimeUnit.MILLISECONDS,
                )
            }.getOrNull()

            if (futures != null) {
                pendingKeys.forEachIndexed { index, key ->
                    val future = futures[index]
                    // 超预算被取消的**不算失败**：它只是还没轮上。这里刻意不写缓存，
                    // 否则一次慢刷新会把一批本来能解析的主机永久钉死成"查不到"。
                    if (future.isCancelled) return@forEachIndexed
                    val ip = runCatching { future.get()?.second }.getOrNull()
                    cache[key] = ip.orEmpty()
                    resolvedByKey[key] = ip
                }
            }
        }

        val out = HashMap<String, String?>(trimmed.size * 2)
        byKey.forEach { (key, inputs) -> inputs.forEach { out[it] = resolvedByKey[key] } }
        return out
    }

    /** 清空缓存（换网络、或测试之间）。 */
    fun clear() {
        cache.clear()
    }

    /** 只做一次 `getByName`，不碰缓存 —— 缓存读写一律由调用方负责，避免两处各写一套。 */
    private fun resolveRaw(key: String): String? =
        runCatching { InetAddress.getByName(key)?.hostAddress }.getOrNull()
}
