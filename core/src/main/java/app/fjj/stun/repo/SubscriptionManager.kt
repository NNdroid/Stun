package app.fjj.stun.repo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData
import app.fjj.stun.core.R
import app.fjj.stun.util.AppUtils
import app.fjj.stun.util.ShareCryptoUtils
import app.fjj.stun.util.StunHttpClient
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.text.RegexOption
import okhttp3.Request
import java.util.UUID
import androidx.core.content.edit
import androidx.core.net.toUri

object SubscriptionManager {
    private const val TAG = "SubscriptionManager"
    private const val PREF_NAME = "stun_subscription_prefs"
    private const val KEY_SUBS = "subscription_list"
    private const val KEY_URL = "subscription_url"
    private const val KEY_LAST_SYNC = "subscription_last_sync"
    /** 每条订阅各自的上次成功同步时间（url -> epoch ms），用于周期同步按 profile-update-interval 门控。 */
    private const val KEY_LAST_SYNC_MAP = "subscription_last_sync_map"
    private const val KEY_SYNC_COUNT_MAP = "subscription_sync_count_map"

    private val gson = Gson()

    /** code: pin_required / pin_invalid，供 WebUI 等调用方做本地化映射 */
    class SubscriptionException(val code: String, message: String) : Exception(message)

    data class ParseResult(
        val profiles: List<Profile>,
        val pinRequired: Boolean = false,
        val pinInvalid: Boolean = false
    )

    /** 一条订阅：链接 + 可选 PIN（加密订阅用）+ 响应头解析出的元信息 */
    data class SubEntry(
        val url: String = "",
        val pin: String = "",
        /** content-disposition 解析出的订阅名称（无则为空） */
        val name: String = "",
        /** profile-web-page-url 解析出的首页地址（仅 http/https，无则为空） */
        val homePage: String = "",
        /** profile-update-interval 解析出的自动更新间隔（小时；0=未指定） */
        val updateIntervalHours: Int = 0
    )

    /** 同步错误码（供 UI/WebUI 做本地化映射；message 只用于日志，不再直接上屏）。 */
    object SyncError {
        const val URL_EMPTY = "url_empty"
        const val INVALID_URL = "invalid_url"
        const val HTTP = "http_error"
        const val NETWORK = "network_error"
        const val NO_VALID_NODES = "no_valid_nodes"
        const val PIN_REQUIRED = "pin_required"
        const val PIN_INVALID = "pin_invalid"
        const val SYNC_FAILED = "sync_failed"
    }

    /**
     * 多订阅同步的单条结果。
     *
     * [importedCount] 只数**新增**节点，[updatedCount] 数**更新**节点 —— 旧实现把两者
     * 混成一个 addedCount，导致"N 个节点已导入"虚高、且跨订阅重复的节点被重复计数。
     * [removedCount] 是本次同步后按来源清理掉的已下架节点数。
     */
    data class SyncResultItem(
        val url: String,
        val success: Boolean,
        val importedCount: Int = 0,
        val updatedCount: Int = 0,
        val removedCount: Int = 0,
        /** 失败时的错误码（[SyncError]）；成功为 null。 */
        val errorCode: String? = null,
        /** 人类可读详情，仅进日志，不上屏。 */
        val message: String? = null
    )

    /** 单条订阅在实时同步流里的状态，供订阅面板逐行显示进度/结果。 */
    enum class SubSyncStatus { SYNCING, SUCCESS, FAILED }

    data class SubSyncState(
        val url: String,
        val status: SubSyncStatus,
        val result: SyncResultItem? = null
    )

    /**
     * 实时同步状态流：每次同步开始/每条订阅落定都会推一份**全量**列表（空列表 = 空闲）。
     * 同步跑在 [syncScope]（进程级），不挂任何 Fragment/Activity 生命周期 ——
     * 旋转或退到后台不再中断同步，面板重开后还能观察到进度。
     */
    val syncStateLiveData = MutableLiveData<List<SubSyncState>>(emptyList())

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null

    /**
     * 落库专用的进程级 scope：**不挂任何 UI 生命周期**。
     *
     * 订阅面板点"删除"会立刻关面板；如果落库挂在面板的 lifecycleScope 上，
     * 队列里的 IO 任务会在对话框 dismiss 那一刻被当成已取消而整块跳过 ——
     * 用户明明确认了要删，结果什么都没删。落库这种事只认用户意图，不认 UI 死活。
     */
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 订阅链接必须是 TLS 加密的 https。
     * 明文 http / ftp 一律拒绝（避免订阅内容被中间人篡改）；
     * sftp 虽然是规范的合法协议，但本机拉取走 okhttp（仅 http/https），无 SFTP 客户端实现，
     * 故不列入白名单——避免"能填不能同步"的假支持。
     */
    fun isValidSubscriptionScheme(url: String): Boolean {
        val text = url.trim()
        if (text.isBlank()) return false
        val uri = text.toUri()
        val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT)
        return !uri.host.isNullOrBlank() && scheme == "https"
    }

    /** 仅 http/https 视为可点击的首页地址。 */
    private fun isValidWebUrl(url: String): Boolean {
        val text = url.trim()
        val uri = text.toUri()
        val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT)
        return !uri.host.isNullOrBlank() && (scheme == "http" || scheme == "https")
    }

    /**
     * 解析 `content-disposition` 取订阅名称。
     *
     * 优先 RFC 5987/6266 扩展式 `filename*=charset'lang'<pct-encoded>`（正确还原中文）。
     * lang 段允许为空（`UTF-8''机场`）也可能是语言标签（`UTF-8'en'…`），故按
     * 「两段单引号分隔」解析，不能写死 `''`。
     * 退化为普通式 `filename="test.yaml"` 或 `filename=test.yaml`——不带引号时
     * 必须吃满整段，否则只会捕获到首字符。
     * 最终剥掉常见文件扩展名（服务端 filename 常写成「名称.yaml」，当标题显示会拖一条无意义后缀）。
     */
    fun parseContentDisposition(header: String?): String {
        if (header.isNullOrBlank()) return ""

        // 扩展式：charset ' lang ' value
        Regex("""filename\*\s*=\s*([^']*)'[^']*'\s*([^;]+)""", RegexOption.IGNORE_CASE)
            .find(header)?.let { match ->
                val decoded = android.net.Uri.decode(match.groupValues[2]).trim().trim('"').trim()
                if (decoded.isNotBlank()) return stripNameExtension(decoded)
            }

        // 普通式：带引号走分组 1，不带引号走分组 2（两者都止于 `;`）
        Regex("""filename\s*=\s*(?:"([^"]*)"|([^;]*))""", RegexOption.IGNORE_CASE)
            .find(header)?.let { match ->
                val name = match.groupValues[1].ifBlank { match.groupValues[2] }.trim()
                if (name.isNotBlank()) return stripNameExtension(name)
            }

        return ""
    }

    /** 订阅标题里视为装饰性后缀的文件扩展名。 */
    private val NAME_EXTENSIONS = setOf("yaml", "yml", "txt", "json", "conf", "ini", "list")

    /**
     * 剥掉订阅名末尾的常见扩展名：`机场.yaml` → `机场`。
     * 前导点开头的名字（`.yaml`）与剥完会为空的极端情况一律原样返回，避免把标题弄丢。
     */
    private fun stripNameExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return name
        if (name.substring(dot + 1).lowercase(java.util.Locale.ROOT) !in NAME_EXTENSIONS) return name
        return name.substring(0, dot).trim().ifBlank { name }
    }

    fun getSubscriptionUrl(context: Context): String =
        getSubscriptions(context).firstOrNull()?.url ?: ""

    /** 订阅列表，旧版单订阅存储自动迁移 */
    fun getSubscriptions(context: Context): List<SubEntry> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_SUBS, null)
        if (json != null) {
            return try {
                gson.fromJson(json, Array<SubEntry>::class.java)?.filter { it.url.isNotBlank() } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
        // migrate legacy single-URL storage
        val legacy = prefs.getString(KEY_URL, "")?.trim() ?: ""
        return if (legacy.isNotBlank()) listOf(SubEntry(legacy)) else emptyList()
    }

    fun saveSubscriptions(context: Context, subs: List<SubEntry>) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val cleaned = subs.filter { it.url.isNotBlank() }
        prefs.edit { putString(KEY_SUBS, gson.toJson(cleaned)) }
        // URL 是 last-sync / count / usage 三张表的键；编辑或删除订阅会留下永不回收的孤儿项。
        // 每次落盘按当前 URL 集合裁一遍，防这三张表无界增长。
        val keep = cleaned.map { it.url }.toHashSet()
        pruneUrlMap(prefs, KEY_LAST_SYNC_MAP) { keep.contains(it) }
        pruneUrlMap(prefs, KEY_SYNC_COUNT_MAP) { keep.contains(it) }
        pruneUrlMap(prefs, KEY_USAGE_BY_URL) { keep.contains(it) }
        // 名称缓存就地重建成最新快照，而不是置空等懒加载：增/删/改名都从这里过，
        // 置空的话下一次节点列表 bind（主线程）就得读盘解 JSON —— 正是要避开的那个卡顿。
        subNameCache = cleaned.associate { it.url.trim() to it.name.trim() }
    }

    /**
     * 订阅 URL → 订阅名 的内存快照。
     *
     * 节点列表要给每个订阅导入的节点打"来源订阅"徽标，`bind()` 是滚动热路径 ——
     * 那里读 prefs + 解一次 JSON 太贵，所以缓一层；订阅表只在 [saveSubscriptions] 里改，
     * 那里会**就地重建成新表**（不是置空），所以正常流程下这里永远是热的。
     */
    @Volatile
    private var subNameCache: Map<String, String>? = null

    /**
     * 某条订阅的展示名（按 URL 精确匹配，未命名返回空串）。
     *
     * 缓存没热时（进程刚起）会读一次 prefs，所以列表页应先用
     * [warmSubscriptionNameCache] 在后台预热，别让第一次 `bind()` 在主线程读盘。
     */
    fun subscriptionNameFor(context: Context, url: String): String {
        if (url.isBlank()) return ""
        val cache = subNameCache ?: run {
            val built = getSubscriptions(context).associate { it.url.trim() to it.name.trim() }
            subNameCache = built
            built
        }
        return cache[url.trim()].orEmpty()
    }

    /** 预热上面的缓存（在后台线程调，避免列表第一帧在主线程解 JSON）。 */
    fun warmSubscriptionNameCache(context: Context) {
        if (subNameCache == null) {
            subNameCache = getSubscriptions(context).associate { it.url.trim() to it.name.trim() }
        }
    }

    /** 按 [keep] 裁剪一个 url→值 的 JSON map（键集合不变时跳过写入，避免无谓落盘）。 */
    private fun pruneUrlMap(prefs: android.content.SharedPreferences, key: String, keep: (String) -> Boolean) {
        val json = prefs.getString(key, null) ?: return
        val raw = runCatching {
            gson.fromJson<Map<String, Any>>(json, object : TypeToken<Map<String, Any>>() {}.type)
        }.getOrNull() ?: return
        if (raw.keys.all { keep(it) }) return
        val pruned = raw.filterKeys { keep(it) }
        prefs.edit { putString(key, gson.toJson(pruned)) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 实时同步编排（进程级 scope，见 [syncStateLiveData]）
    // ─────────────────────────────────────────────────────────────────────────

    /** 是否有一次同步正在跑。 */
    val isSyncing: Boolean get() = syncJob?.isActive == true

    /**
     * 触发"同步全部已保存订阅"，跑在 [syncScope]，进度实时推给 [syncStateLiveData]。
     * 重复调用时若已在同步则直接返回（防抖）。[onFinished] 在同步结束后于主线程回调。
     */
    fun startSyncAll(context: Context, onFinished: ((List<SyncResultItem>) -> Unit)? = null) {
        if (isSyncing) return
        val appContext = context.applicationContext
        syncJob = syncScope.launch {
            val subs = getSubscriptions(appContext)
            publishStates(subs.map { SubSyncState(it.url, SubSyncStatus.SYNCING) })
            val results = syncAllSubscriptions(appContext, subs)
            publishStates(results.map {
                SubSyncState(it.url, if (it.success) SubSyncStatus.SUCCESS else SubSyncStatus.FAILED, it)
            })
            onFinished?.let { cb -> withContext(Dispatchers.Main) { cb(results) } }
        }
    }

    /** 触发"只同步一条订阅"（行内单独同步按钮）。同样走 [syncStateLiveData] 报告该 url 的状态。 */
    fun startSyncOne(context: Context, entry: SubEntry, onFinished: ((SyncResultItem) -> Unit)? = null) {
        if (isSyncing) return
        val appContext = context.applicationContext
        syncJob = syncScope.launch {
            publishStates(listOf(SubSyncState(entry.url, SubSyncStatus.SYNCING)))
            val results = syncAllSubscriptions(appContext, listOf(entry))
            val item = results.firstOrNull()
                ?: SyncResultItem(entry.url, false, errorCode = SyncError.SYNC_FAILED)
            publishStates(listOf(SubSyncState(entry.url, if (item.success) SubSyncStatus.SUCCESS else SubSyncStatus.FAILED, item)))
            onFinished?.let { cb -> withContext(Dispatchers.Main) { cb(item) } }
        }
    }

    /** 取消正在跑的同步（协程级；已在途的网络请求在当前这条订阅结束后停止）。 */
    fun cancelSync() {
        syncJob?.cancel()
        syncJob = null
        publishStates(emptyList())
    }

    private fun publishStates(states: List<SubSyncState>) {
        syncStateLiveData.postValue(states)
    }

    fun saveSubscriptionUrl(context: Context, url: String) {
        val subs = getSubscriptions(context).toMutableList()
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        if (subs.none { it.url == trimmed }) subs.add(0, SubEntry(trimmed))
        saveSubscriptions(context, subs)
    }

    /**
     * 归属某订阅的节点数（来源 URL 精确匹配）。删订阅前用它问"要一起删 N 个节点吗"。
     *
     * suspend + IO：这是一次 Room 全表查询。订阅面板是在点击回调（主线程）里问它的，
     * 不切线程就是"主线程查库"——节点上千时肉眼可见地卡一下，确认框才弹出来。
     */
    suspend fun countSubscriptionNodes(context: Context, url: String): Int =
        withContext(Dispatchers.IO) {
            ProfileManager.getProfiles(context).count { it.sourceSubscriptionUrl == url }
        }

    /**
     * 删除归属某订阅的全部节点（收藏与当前选中豁免，避免误删正在用的）。返回删除数。
     *
     * suspend + IO 同上：一次全表查询 + N 次单条删除，全塞主线程就是 O(N) 次卡顿。
     * 注意块内没有挂起点，所以一旦开始跑就不会被取消到一半（不会删一半留一半）。
     */
    suspend fun deleteSubscriptionNodes(context: Context, url: String): Int =
        withContext(Dispatchers.IO) {
            val selectedId = SettingsManager.getSelectedProfileId(context)
            val doomed = ProfileManager.getProfiles(context).filter {
                it.sourceSubscriptionUrl == url && !it.favorite && it.id != selectedId
            }
            doomed.forEach { ProfileManager.deleteProfile(context, it) }
            doomed.size
        }

    /** 移除一条订阅（仅删订阅项与其元信息，不动节点）。 */
    fun removeSubscription(context: Context, url: String) {
        val subs = getSubscriptions(context).filter { it.url != url }
        saveSubscriptions(context, subs)
        clearUsageForUrl(context, url)
    }

    /**
     * 删订阅 +（可选）连它的节点，整段丢到 [persistScope] 上跑完。
     *
     * 调用方（订阅面板）在主线程上，点完确认就可以关面板；这里会先切 IO 再查库/删库，
     * 且不受面板生命周期影响（见 [persistScope] 的说明）。
     */
    fun removeSubscriptionAsync(context: Context, url: String, deleteNodes: Boolean) {
        if (url.isBlank()) return
        val appContext = context.applicationContext
        persistScope.launch {
            if (deleteNodes) deleteSubscriptionNodes(appContext, url)
            removeSubscription(appContext, url)
        }
    }

    fun getLastSyncTime(context: Context): Long {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_LAST_SYNC, 0L)
    }

    private fun saveLastSyncTime(context: Context, time: Long) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit { putLong(KEY_LAST_SYNC, time) }
    }

    /** 单条订阅的上次成功同步时间（用于周期同步按 interval 门控）。无记录返回 0。 */
    fun getLastSyncForUrl(context: Context, url: String): Long {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_LAST_SYNC_MAP, null) ?: return 0L
        val map = runCatching {
            gson.fromJson<Map<String, Long>>(json, object : TypeToken<Map<String, Long>>() {}.type)
        }.getOrNull() ?: return 0L
        return map[url] ?: 0L
    }

    /** 记录单条订阅的上次成功同步时间（手动/周期同步成功都调用，重置其自动更新计时）。
     *  count >= 0 时同时记录本次导入节点数，供订阅面板信息行展示。 */
    fun setLastSyncForUrl(context: Context, url: String, time: Long, count: Int = -1) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val map = HashMap<String, Long>()
        prefs.getString(KEY_LAST_SYNC_MAP, null)?.let { json ->
            runCatching {
                gson.fromJson<Map<String, Long>>(json, object : TypeToken<Map<String, Long>>() {}.type)
            }.getOrNull()?.let { map.putAll(it) }
        }
        map[url] = time
        prefs.edit { putString(KEY_LAST_SYNC_MAP, gson.toJson(map)) }
        if (count >= 0) {
            val counts = HashMap<String, Int>()
            prefs.getString(KEY_SYNC_COUNT_MAP, null)?.let { json ->
                runCatching {
                    gson.fromJson<Map<String, Int>>(json, object : TypeToken<Map<String, Int>>() {}.type)
                }.getOrNull()?.let { counts.putAll(it) }
            }
            counts[url] = count
            prefs.edit { putString(KEY_SYNC_COUNT_MAP, gson.toJson(counts)) }
        }
    }

    /** 单条订阅的同步元信息：上次成功同步时间 + 导入节点数（count=-1 表示旧数据未记录）。 */
    data class SyncMeta(val time: Long, val count: Int)

    fun getSyncMetaForUrl(context: Context, url: String): SyncMeta? {
        val time = getLastSyncForUrl(context, url)
        if (time <= 0L) return null
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val count = prefs.getString(KEY_SYNC_COUNT_MAP, null)?.let { json ->
            runCatching {
                gson.fromJson<Map<String, Int>>(json, object : TypeToken<Map<String, Int>>() {}.type)
            }.getOrNull()?.get(url)
        } ?: -1
        return SyncMeta(time, count)
    }

    /**
     * 同步全部已保存订阅（也可传入临时订阅列表）。逐条拉取，节点按 id 全局去重合并。
     * 返回每条订阅的结果；单条失败不影响其他订阅。
     */
    suspend fun syncAllSubscriptions(
        context: Context,
        entries: List<SubEntry>? = null
    ): List<SyncResultItem> = withContext(Dispatchers.IO) {
        val subs = entries ?: getSubscriptions(context)
        if (subs.isEmpty()) {
            return@withContext listOf(SyncResultItem("", false, errorCode = SyncError.URL_EMPTY))
        }

        val existingById = ProfileManager.getProfiles(context).associateBy { it.id }.toMutableMap()
        val results = mutableListOf<SyncResultItem>()
        StunLogger.d(
            TAG,
            "Batch sync start: ${subs.size} subscription(s), ${existingById.size} profile(s) already in DB"
        )

        for (sub in subs) {
            val item = syncSingle(context, sub, existingById)
            results.add(item)
            StunLogger.d(
                TAG,
                if (item.success) {
                    "  result [$sub.url]: OK +${item.importedCount} ~${item.updatedCount} " +
                        "-${item.removedCount}"
                } else {
                    "  result [$sub.url]: FAIL code=${item.errorCode ?: "-"} msg=${item.message ?: "-"}"
                }
            )
            if (item.success) {
                // 信息行"N 节点"记的是该订阅当前归属的节点总数（新增 + 更新），不是本次导入数。
                setLastSyncForUrl(context, sub.url, System.currentTimeMillis(), item.importedCount + item.updatedCount)
            }
        }

        val okCount = results.count { it.success }
        if (okCount > 0) {
            saveLastSyncTime(context, System.currentTimeMillis())
        }
        StunLogger.d(TAG, "Batch sync done: $okCount/${results.size} subscription(s) succeeded")
        results
    }

    /**
     * 兼容旧接口：单订阅同步。url 为空时用已保存的第一条；带 pin 时优先用 pin。
     */
    suspend fun syncSubscription(context: Context, targetUrl: String? = null, pin: String? = null): Result<Int> = withContext(Dispatchers.IO) {
        val subUrl = targetUrl?.trim() ?: getSubscriptionUrl(context)
        if (subUrl.isBlank()) {
            return@withContext Result.failure(SubscriptionException(SyncError.URL_EMPTY, "Subscription URL is empty"))
        }
        val entry = SubEntry(subUrl, pin.orEmpty())
        val items = syncAllSubscriptions(context, listOf(entry))
        val item = items.firstOrNull()
            ?: return@withContext Result.failure(SubscriptionException(SyncError.SYNC_FAILED, "Sync failed"))
        if (item.success) Result.success(item.importedCount + item.updatedCount)
        else Result.failure(SubscriptionException(item.errorCode ?: SyncError.SYNC_FAILED, item.message ?: "Sync failed"))
    }

    /** 同步单条订阅。节点导入按 existingById 全局去重（跨订阅相同 id 只导入一次）。 */
    private fun syncSingle(context: Context, sub: SubEntry, existingById: MutableMap<String, Profile>): SyncResultItem {
        val subUrl = sub.url
        if (!isValidSubscriptionScheme(subUrl)) {
            StunLogger.d(TAG, "Sync aborted: not an allowed subscription URL (https only): $subUrl")
            return SyncResultItem(subUrl, false, errorCode = SyncError.INVALID_URL,
                message = context.getString(R.string.error_invalid_subscription_url))
        }
        try {
            StunLogger.i(TAG, "Fetching subscription from: $subUrl")
            StunLogger.d(
                TAG,
                "Sync start [$subUrl]: pin=${if (sub.pin.isBlank()) "none" else "set"}, " +
                    "existing entries in DB: ${existingById.size}"
            )
            val request = Request.Builder()
                .url(subUrl)
                .get()
                .header("User-Agent", StunHttpClient.userAgent)
                .build()
            val startedAt = System.currentTimeMillis()
            val response = StunHttpClient.client.newCall(request).execute()
            StunLogger.d(
                TAG,
                "HTTP round-trip [$subUrl] took ${System.currentTimeMillis() - startedAt} ms"
            )
            response.use { resp ->
                val code = resp.code
                StunLogger.d(
                    TAG,
                    "HTTP ${code} ${resp.message}; contentType=${resp.header("Content-Type")}; " +
                        "contentLength=${resp.header("Content-Length") ?: "-"}"
                )
                if (code !in 200..299) {
                    StunLogger.e(TAG, "Subscription HTTP error [$subUrl]: $code ${resp.message}")
                    return SyncResultItem(subUrl, false, errorCode = SyncError.HTTP, message = "HTTP Error: $code ${resp.message}")
                }

                // 订阅名称：content-disposition（含 RFC5987 的 UTF-8 中文名）
                val contentDisposition = resp.header("content-disposition")
                val httpName = parseContentDisposition(contentDisposition)
                // 首页按钮地址：仅采纳 http/https
                val httpHomePage = resp.header("profile-web-page-url")?.trim().orEmpty()
                    .let { if (isValidWebUrl(it)) it else "" }
                // 自动更新间隔（小时）
                val httpIntervalHours = resp.header("profile-update-interval")?.trim()
                    ?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                // 账户级流量快照：subscription-userinfo 响应头（仅订阅节点返回；手动节点无此头）
                val httpUserInfo = resp.header("subscription-userinfo")

                StunLogger.d(TAG, "Response header raw: content-disposition=${contentDisposition ?: "-"}")
                StunLogger.d(TAG, "Response header parsed: name=\"$httpName\"")
                StunLogger.d(
                    TAG,
                    "Response header parsed: webPage=${httpHomePage.ifEmpty { "-" }}; " +
                        "updateIntervalHours=${httpIntervalHours.takeIf { it > 0 } ?: "-"}; " +
                        "userInfo=${httpUserInfo ?: "-"}"
                )

                val body = resp.body?.string().orEmpty().trim()
                if (body.isEmpty()) {
                    StunLogger.e(TAG, "Empty subscription body received from [$subUrl]")
                } else {
                    StunLogger.d(
                        TAG,
                        "Body received: ${body.length} chars; head=${preview(body)}"
                    )
                }

                // 文件内自带的 `#头名: 值` 声明（静态托管没法自定义响应头时的兜底）。
                // 优先级：真响应头 > 文件内声明 > 上次已存值（后者由 mergeHeaderMeta 兜）。
                val inline = parseInlineHeaders(body)
                val meta = resolveHeaderMeta(httpName, httpHomePage, httpIntervalHours, inline)
                val name = meta.name
                val homePage = meta.homePage
                val intervalHours = meta.intervalHours
                StunLogger.d(
                    TAG,
                    "Effective meta (response header ⊕ inline): name=\"${name.ifBlank { "-" }}\"; " +
                        "webPage=${homePage.ifEmpty { "-" }}; intervalHours=${intervalHours.takeIf { it > 0 } ?: "-"}"
                )

                val usage = resolveUsage(httpUserInfo, inline)
                if (usage != null) {
                    saveSubscriptionUsage(context, subUrl, usage)
                    checkUsageAlerts(context, subUrl, usage)
                    StunLogger.d(
                        TAG,
                        "Usage snapshot saved: used=${usage.used}B / " +
                            "total=${usage.total.takeIf { it > 0 }?.toString() ?: "unlimited"}; " +
                            "expire=${if (usage.hasExpire) usage.expire.toString() else "-"}"
                    )
                } else {
                    StunLogger.d(TAG, "No usable subscription-userinfo (header or inline); keeping previous snapshot")
                }

                val parsed = parseSubscriptionPayload(inline.body, sub.pin.ifBlank { null })
                StunLogger.d(
                    TAG,
                    "Parse outcome: ${parsed.profiles.size} node(s), " +
                        "pinRequired=${parsed.pinRequired}, pinInvalid=${parsed.pinInvalid}"
                )

                if (parsed.pinRequired) {
                    StunLogger.d(TAG, "Aborting sync [$subUrl]: encrypted payload, no PIN configured")
                    return SyncResultItem(subUrl, false, errorCode = SyncError.PIN_REQUIRED,
                        message = context.getString(R.string.subscription_pin_required))
                }
                if (parsed.pinInvalid) {
                    StunLogger.d(TAG, "Aborting sync [$subUrl]: configured PIN rejected the payload")
                    return SyncResultItem(subUrl, false, errorCode = SyncError.PIN_INVALID,
                        message = context.getString(R.string.subscription_pin_invalid))
                }

                val importedProfiles = parsed.profiles
                if (importedProfiles.isEmpty()) {
                    StunLogger.e(TAG, "No valid nodes found in subscription payload from [$subUrl]")
                    return SyncResultItem(subUrl, false, errorCode = SyncError.NO_VALID_NODES,
                        message = "No valid nodes found in subscription payload")
                }

                // 导入/更新：按 id 全局去重；每个落库的节点都盖上来源订阅 URL。
                var imported = 0
                var updated = 0
                val newIds = HashSet<String>()
                importedProfiles.forEachIndexed { index, newProfile ->
                    val profileToSave = newProfile.copy(
                        id = newProfile.id.ifBlank { UUID.randomUUID().toString() },
                        sourceSubscriptionUrl = subUrl,
                    )
                    newIds.add(profileToSave.id)
                    if (existingById.containsKey(profileToSave.id)) {
                        ProfileManager.updateProfile(context, profileToSave)
                        updated++
                        StunLogger.d(
                            TAG,
                            "  node #${index + 1} UPDATE id=${profileToSave.id.take(8)} name=\"${profileToSave.name}\""
                        )
                    } else {
                        ProfileManager.addProfile(context, profileToSave)
                        existingById[profileToSave.id] = profileToSave
                        imported++
                        StunLogger.d(
                            TAG,
                            "  node #${index + 1} INSERT id=${profileToSave.id.take(8)} " +
                                "name=\"${profileToSave.name}\" addr=${profileToSave.sshAddr.ifBlank { profileToSave.proxyAddr }}"
                        )
                    }
                }

                // 陈旧清理：本次 payload 里没有、且归属本订阅的节点 = 已下架。
                // 收藏与"当前选中"的节点豁免（不能把用户星标/正在连的节点静默删掉）。
                val removed = pruneStaleSubscriptionNodes(context, subUrl, newIds)

                // 持久化订阅链接 + 响应头解析出的元信息（名称/首页/更新间隔）
                persistSubscriptionMeta(context, subUrl, name, homePage, intervalHours)
                StunLogger.d(
                    TAG,
                    "Sync finished [$subUrl]: ${importedProfiles.size} parsed -> +$imported ~$updated -$removed; " +
                        "total ${System.currentTimeMillis() - startedAt} ms"
                )
                StunLogger.i(TAG, "Subscription sync [$subUrl] complete. +$imported ~$updated -$removed. name=$name interval=$intervalHours")
                return SyncResultItem(subUrl, true, imported, updated, removed)
            }
        } catch (e: Exception) {
            StunLogger.e(TAG, "Subscription sync error [$subUrl]", e)
            return SyncResultItem(subUrl, false, errorCode = SyncError.NETWORK, message = e.message ?: "Sync failed")
        }
    }

    /**
     * 删除"归属 [subUrl] 但本次 payload 已不含"的节点。收藏与当前选中节点豁免。
     * 同步只在成功解析到非空 payload 后调用（空 payload 走 no_valid_nodes 早退），
     * 所以不会因一次网络抖动把整订阅节点清空。
     */
    private fun pruneStaleSubscriptionNodes(context: Context, subUrl: String, keepIds: Set<String>): Int {
        val selectedId = SettingsManager.getSelectedProfileId(context)
        val stale = staleSubscriptionNodes(
            ProfileManager.getProfiles(context), subUrl, keepIds, selectedId
        )
        stale.forEach { ProfileManager.deleteProfile(context, it) }
        if (stale.isNotEmpty()) {
            StunLogger.i(TAG, "Pruned ${stale.size} stale nodes from subscription [$subUrl]")
        }
        return stale.size
    }

    /**
     * 陈旧节点判定（纯函数，便于 JVM 测）：来源为 [subUrl]、不在本次 payload 的 [keepIds] 里、
     * 且非收藏、非当前选中 [selectedId] 的节点应被移除。
     */
    internal fun staleSubscriptionNodes(
        profiles: List<Profile>,
        subUrl: String,
        keepIds: Set<String>,
        selectedId: String?
    ): List<Profile> = profiles.filter {
        it.sourceSubscriptionUrl == subUrl && it.id !in keepIds && !it.favorite && it.id != selectedId
    }

    /**
     * 把本次响应头解析出的元信息并入已存条目。
     *
     * `content-disposition` / `profile-web-page-url` / `profile-update-interval` 都是**可选的**，
     * 服务端未必每次都回（同一机场不同端点、不同时机都可能缺）。空值一律沿用已存值——
     * 否则一次少带头的同步就会把订阅名和主页按钮抹掉，而且是不可逆、用户无从感知的。
     * 代价：换到完全不回这些头的机场时，旧值会残留到新响应给出新值为止。
     */
    internal fun mergeHeaderMeta(
        old: SubEntry,
        name: String,
        homePage: String,
        updateIntervalHours: Int
    ): SubEntry = old.copy(
        name = name.ifBlank { old.name },
        homePage = homePage.ifBlank { old.homePage },
        updateIntervalHours = updateIntervalHours.takeIf { it > 0 } ?: old.updateIntervalHours
    )

    /** 一次同步解析出的订阅元信息（响应头 ⊕ 文件内声明 之后的最终值）。 */
    data class HeaderMeta(val name: String, val homePage: String, val intervalHours: Int)

    /**
     * 合并「真响应头」与「文件内自带的声明」（[parseInlineHeaders]）：**逐项**取值，
     * 响应头优先、文件内声明补缺。两项都没有的项留空，交由 [mergeHeaderMeta] 沿用已存值。
     *
     * 为什么响应头优先而不是文件优先：文件内声明的定位是**补充**静态托管缺的那部分
     * （"补充请求响应头"），真响应头来自服务端实时逻辑，比文件里的静态文本更权威。
     * 逐项合并而非整包二选一，是为了让"响应头只给一半、文件补另一半"也能生效。
     */
    internal fun resolveHeaderMeta(
        httpName: String,
        httpHomePage: String,
        httpIntervalHours: Int,
        inline: InlineHeaders
    ): HeaderMeta = HeaderMeta(
        name = httpName.ifBlank { parseContentDisposition(inline.headers["content-disposition"]) },
        homePage = httpHomePage.ifBlank {
            inline.headers["profile-web-page-url"]?.trim().orEmpty()
                .let { if (isValidWebUrl(it)) it else "" }
        },
        intervalHours = httpIntervalHours.takeIf { it > 0 }
            ?: (inline.headers["profile-update-interval"]?.trim()?.toIntOrNull()
                ?.coerceAtLeast(0) ?: 0)
    )

    /**
     * 流量快照取值：真响应头解析成功就用它，否则回落到文件内声明的 `subscription-userinfo`。
     * 两者都没有（或都解不出有效值）返回 null ⇒ 不改动已存快照、UI 显示「暂无用量」。
     */
    internal fun resolveUsage(httpUserInfoHeader: String?, inline: InlineHeaders): SubscriptionUsage? =
        parseUsageHeader(httpUserInfoHeader)
            ?: inline.headers["subscription-userinfo"]?.let { parseUsageHeader(it) }

    /** 保存/更新一条订阅：补齐响应头解析出的名称/首页/更新间隔（按 url 去重）。 */
    private fun persistSubscriptionMeta(
        context: Context,
        url: String,
        name: String,
        homePage: String,
        updateIntervalHours: Int
    ) {
        val subs = getSubscriptions(context).toMutableList()
        val idx = subs.indexOfFirst { it.url == url }
        if (idx >= 0) {
            subs[idx] = mergeHeaderMeta(subs[idx], name, homePage, updateIntervalHours)
        } else {
            subs.add(0, SubEntry(url = url, name = name, homePage = homePage, updateIntervalHours = updateIntervalHours))
        }
        saveSubscriptions(context, subs)
    }

    @Deprecated("Use syncAllSubscriptions", ReplaceWith("syncAllSubscriptions(context)"))
    suspend fun syncSubscriptionLegacy(context: Context, targetUrl: String? = null): Result<Int> = syncSubscription(context, targetUrl)

    /** 敏感字段名（节点密码 / 密钥 / 私钥）；日志预览一律遮蔽其取值。 */
    private val SECRET_FIELD_REGEX = Regex(
        "\"(password|passwd|sshPrivateKey|sshPublicKey|noiseKey|key|psk|secret)\"\\s*:\\s*\"[^\"]*\"",
        RegexOption.IGNORE_CASE
    )

    /**
     * 日志预览：压平换行、遮蔽 [SECRET_FIELD_REGEX] 命中的字段值、超长截断。
     *
     * 订阅 payload / 解密结果里带着节点密码与 SSH 私钥，日志会进本地日志文件并可被分享，
     * 所以这里**只给结构（长度、分支、条数）+ 一小段去敏后的样例**，绝不整段落盘。
     */
    private fun preview(value: String, max: Int = 96): String {
        val flat = value.replace("\\R".toRegex(), "\\n")
        val redacted = SECRET_FIELD_REGEX.replace(flat) { "\"${it.groupValues[1]}\":\"[REDACTED]\"" }
        return if (redacted.length > max) {
            redacted.take(max) + "…(${redacted.length} chars)"
        } else {
            redacted
        }
    }

    fun parseSubscriptionPayload(rawPayload: String, pin: String? = null): ParseResult {
        val totalLines = rawPayload.lineSequence().count()
        StunLogger.d(
            TAG,
            "Parse payload start: ${rawPayload.length} chars / $totalLines lines / " +
                "pin=${if (pin.isNullOrBlank()) "none" else "set"}"
        )
        StunLogger.d(TAG, "Parse payload head: ${preview(rawPayload)}")

        // 0. Multi-line `stun://` nodes: one node per line, every line shares the file-level PIN.
        //    Per agreed design: ONLY `stun://`/`stun:` lines are honored (option 1); each line reuses
        //    the existing single-node format — a ShareCryptoUtils-encrypted Profile JSON (option 2);
        //    the file-level PIN decrypts every line (option 3). Non-stun:// lines are ignored.
        val stunLines = rawPayload.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { it.startsWith("stun://", ignoreCase = true) || it.startsWith("stun:", ignoreCase = true) }
            .toList()
        if (stunLines.isNotEmpty()) {
            StunLogger.d(TAG, "Parse branch: multi-line stun:// (${stunLines.size} line(s))")
            val profiles = mutableListOf<Profile>()
            var needsPin = false
            var pinBad = false
            var lineIndex = 0
            for (line in stunLines) {
                lineIndex++
                val payload = stripStunScheme(line)
                StunLogger.d(TAG, "  stun line #$lineIndex: ${payload.length} chars payload")
                if (pin.isNullOrBlank()) {
                    needsPin = true
                    continue
                }
                val decrypted = ShareCryptoUtils.decrypt(payload, pin)
                if (decrypted == null) {
                    StunLogger.d(TAG, "  stun line #$lineIndex: PIN decrypt FAILED (wrong PIN or not encrypted)")
                    pinBad = true
                    continue
                }
                val nodes = parseDecryptedNodes(decrypted)
                StunLogger.d(
                    TAG,
                    "  stun line #$lineIndex: decrypt OK (${decrypted.length} chars), ${nodes.size} node(s)"
                )
                profiles.addAll(nodes)
            }
            StunLogger.d(TAG, "Parse branch done: ${profiles.size} node(s) from ${stunLines.size} stun:// line(s)")
            if (profiles.isNotEmpty()) return ParseResult(profiles)
            if (needsPin) {
                StunLogger.d(TAG, "Parse failed: payload is PIN-encrypted but no PIN was provided")
                return ParseResult(emptyList(), pinRequired = true)
            }
            if (pinBad) {
                StunLogger.d(TAG, "Parse failed: PIN provided but decryption failed for every stun:// line")
                return ParseResult(emptyList(), pinInvalid = true)
            }
            // Lines present but nothing decodable and no pin issue → treat as no valid nodes.
            StunLogger.d(TAG, "Parse failed: stun:// lines present but none yielded a node")
            return ParseResult(emptyList())
        }

        // 1. Try parsing direct JSON Array of Profiles
        try {
            val listType = object : TypeToken<List<Profile>>() {}.type
            val list: List<Profile>? = gson.fromJson(rawPayload, listType)
            if (!list.isNullOrEmpty() && list.any { it.sshAddr.isNotBlank() || it.proxyAddr.isNotBlank() }) {
                StunLogger.d(TAG, "Parse branch: raw JSON array -> ${list.size} node(s)")
                return ParseResult(list)
            }
            StunLogger.d(
                TAG,
                "Raw JSON array branch: matched ${list?.size ?: 0} entry(ies), " +
                    "but none carries sshAddr/proxyAddr"
            )
        } catch (e: Exception) {
            StunLogger.d(TAG, "Raw JSON array branch: not a JSON array (${e.javaClass.simpleName}: ${e.message})")
        }

        var sawEncrypted = false

        // 尝试用 PIN 解密；无 PIN 或解密失败返回 null
        fun decryptPayload(payload: String): String? {
            sawEncrypted = true
            if (pin.isNullOrBlank()) {
                StunLogger.d(TAG, "Whole-body branch: PIN-encrypted payload but no PIN provided")
                return null
            }
            val plain = ShareCryptoUtils.decrypt(payload, pin)
            StunLogger.d(
                TAG,
                if (plain == null) "Whole-body branch: PIN decrypt FAILED"
                else "Whole-body branch: PIN decrypt OK -> ${plain.length} chars"
            )
            return plain
        }

        // 2. Try a whole-body PIN-encrypted payload.
        val compact = rawPayload.replace("\\s".toRegex(), "")
        if (ShareCryptoUtils.isEncryptedPayload(compact)) {
            StunLogger.d(TAG, "Detected whole-body PIN-encrypted payload (ShareCryptoUtils envelope)")
            val decrypted = decryptPayload(compact)
            if (decrypted != null) {
                val profiles = parseDecryptedNodes(decrypted)
                StunLogger.d(TAG, "Whole-body branch: ${profiles.size} node(s) parsed from decrypted text")
                if (profiles.isNotEmpty()) return ParseResult(profiles)
            }
        } else {
            StunLogger.d(TAG, "Body is not a ShareCryptoUtils encrypted envelope")
        }

        // 3. Try Base64 decoding the payload (base64 of a plain JSON array)
        var decodedText = ""
        try {
            val cleanB64 = rawPayload.replace("\\s".toRegex(), "")
            val bytes = Base64.decode(cleanB64, Base64.DEFAULT)
            decodedText = String(bytes, Charsets.UTF_8).trim()
            StunLogger.d(
                TAG,
                "Base64 decode OK: ${cleanB64.length} chars -> ${decodedText.length} chars"
            )
        } catch (e: Exception) {
            decodedText = rawPayload
            StunLogger.d(
                TAG,
                "Base64 decode FAILED (${e.javaClass.simpleName}: ${e.message}); " +
                    "falling back to raw text"
            )
        }

        try {
            val listType = object : TypeToken<List<Profile>>() {}.type
            val list: List<Profile>? = gson.fromJson(decodedText, listType)
            if (!list.isNullOrEmpty()) {
                StunLogger.d(TAG, "Base64 branch: -> ${list.size} node(s)")
                return ParseResult(list)
            }
            StunLogger.d(
                TAG,
                "Base64 branch: decoded text matched ${list?.size ?: 0} entry(ies), none usable"
            )
        } catch (e: Exception) {
            StunLogger.d(
                TAG,
                "Base64 branch: decoded text is not a JSON array of nodes " +
                    "(${e.javaClass.simpleName}: ${e.message}); head=${preview(decodedText)}"
            )
        }

        StunLogger.d(
            TAG,
            "Parse failed: no branch produced a node " +
                "(encrypted=$sawEncrypted, pin=${if (pin.isNullOrBlank()) "none" else "set"})"
        )
        return ParseResult(
            profiles = emptyList(),
            pinRequired = sawEncrypted && pin.isNullOrBlank(),
            pinInvalid = sawEncrypted && !pin.isNullOrBlank()
        )
    }

    /** 解密结果兼容两种内容：单节点 JSON 或加密备份的节点数组。订阅逐行与整文件加密两处共用。 */
    private fun parseDecryptedNodes(jsonStr: String): List<Profile> {
        val list = try {
            gson.fromJson<List<Profile>>(jsonStr, object : TypeToken<List<Profile>>() {}.type)
        } catch (_: Exception) { null }
        if (!list.isNullOrEmpty()) {
            StunLogger.d(TAG, "Decrypted content -> node array: ${list.size} node(s)")
            return list
        }
        val single = try { gson.fromJson(jsonStr, Profile::class.java) } catch (_: Exception) { null }
        return if (single != null) {
            StunLogger.d(TAG, "Decrypted content -> single node: name=\"${single.name}\"")
            listOf(single)
        } else {
            StunLogger.d(TAG, "Decrypted content -> not an array nor a node; head=${preview(jsonStr)}")
            emptyList()
        }
    }

    /** 去掉 `stun://` / `stun:` 前缀（与 HomeFragment.stripStunScheme 保持一致）。 */
    private fun stripStunScheme(raw: String): String {
        val text = raw.trim()
        return when {
            text.startsWith("stun://", ignoreCase = true) -> text.substring(7).trim()
            text.startsWith("stun:", ignoreCase = true) -> text.substring(5).removePrefix("//").trim()
            else -> text
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 文件内自带的响应头声明（`#头名: 值`）
    // 静态托管（CDN / Gist / raw 文件）没法自定义响应头，只能把订阅名、流量额度这些
    // 写进文件正文让客户端自己认。真响应头优先，缺哪项就用文件里的补哪项。
    // ─────────────────────────────────────────────────────────────────────────

    /** [parseInlineHeaders] 的结果：认出来的头 + 剥掉声明行之后的正文。 */
    data class InlineHeaders(
        /** 已归一小写的头名 → 值。只含 [INLINE_HEADER_KEYS] 里的键。 */
        val headers: Map<String, String>,
        /** 剥净声明行、可直接喂给 [parseSubscriptionPayload] 的正文。 */
        val body: String
    )

    /** 允许在文件里声明的响应头；其余 `#` 行一律当普通注释丢弃。 */
    private val INLINE_HEADER_KEYS = setOf(
        "content-disposition",
        "profile-web-page-url",
        "profile-update-interval",
        "subscription-userinfo"
    )

    /**
     * 从响应体里摘出 `#` 开头的响应头声明，返回 `(headers, 剥净正文)`。
     *
     * 语法：整行（允许前导空白）以 `#` 开头，`#` 之后是 `头名: 值`。`#` 与头名之间
     * 允许有空格（`#content-disposition:` ≡ `# content-disposition:`），头名大小写不敏感。
     * 一条声明都没认到时**正文原样返回**，连重新拼接都不做（保住原始换行与缩进）。
     *
     * 容错三条：
     * 1. 没带冒号、或头名不在 [INLINE_HEADER_KEYS] 里的 `#` 行 → **当注释丢掉、不报错**。
     *    这样既能写 `# 七星机场` 这类说明，也不会因为以后加头而让老版本炸掉。
     * 2. 同一头名出现多次 → **以第一条为准**（自顶向下读符合"文件头"的直觉）。
     * 3. 值为空（`#x:`）→ 视同没声明，交给下一步沿用已存值。
     *
     * 只认"行首（允许前导空白）的 `#`"，所以 JSON 字符串值内部的 `#` 不会被误伤；
     * 而三种外壳（裸 JSON / Base64 / PIN 加密）的字符集都不含 `#`，剥行对它们都安全。
     */
    fun parseInlineHeaders(raw: String): InlineHeaders {
        if (raw.isBlank()) return InlineHeaders(emptyMap(), raw)
        val headers = LinkedHashMap<String, String>()
        val body = StringBuilder(raw.length)
        var sawComment = false
        for (line in raw.lineSequence()) {
            val trimmed = line.trimStart()
            if (!trimmed.startsWith("#")) {
                body.append(line).append('\n')
                continue
            }
            sawComment = true
            val payload = trimmed.removePrefix("#")
            val sep = payload.indexOf(':')
            if (sep <= 0) continue // `#` 后没冒号 / 头名为空 → 普通注释
            val key = payload.substring(0, sep).trim().lowercase(java.util.Locale.ROOT)
            if (key !in INLINE_HEADER_KEYS) continue // 不认识的头名 → 普通注释
            val value = payload.substring(sep + 1).trim()
            if (value.isNotEmpty() && key !in headers) headers[key] = value
        }
        val result = InlineHeaders(headers, if (sawComment) body.toString().trim() else raw)
        StunLogger.d(
            TAG,
            if (result.headers.isEmpty()) {
                "Inline headers: no recognized `#header: value` declaration in body"
            } else {
                "Inline headers recognized: ${result.headers.keys.joinToString(", ")} " +
                    "(body ${raw.length} chars -> ${result.body.length} chars)"
            }
        )
        return result
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 订阅流量（subscription-userinfo 响应头，账户级累计 upload/download/total/expire）
    // 仅“订阅节点”才有；手动节点无此头，相应的 UI 卡片直接隐藏。
    // 按订阅 URL 各存一份（含日/月基线与趋势），多订阅不再互相顶掉快照。
    // ─────────────────────────────────────────────────────────────────────────

    /** 一次订阅流量快照。字节；expire 为 unix 秒（0=无到期）。 */
    data class SubscriptionUsage(
        val upload: Long,
        val download: Long,
        val total: Long,
        val expire: Long,
        val updatedAt: Long
    ) {
        val used: Long get() = upload + download
        val unlimited: Boolean get() = total <= 0
        val hasExpire: Boolean get() = expire > 0
        val ratio: Double
            get() = if (total <= 0) 0.0 else (used.toDouble() / total).coerceIn(0.0, 1.0)
    }

    /** 某订阅的流量视图：快照 + 今日/本月新增 + 趋势点。 */
    data class UsageView(
        val usage: SubscriptionUsage,
        val dayDelta: Long,
        val monthDelta: Long,
        val history: List<Pair<Long, Long>>
    )

    /** 趋势采样点（每次同步拿到头时记一笔 used 值）。 */
    data class UsagePoint(val t: Long, val u: Long)

    /**
     * 云备份用的流量快照（对外稳定结构）。
     *
     * 它就是 [UsageRecord] 的公开投影 —— 内部记录是私有的、字段随实现走的，
     * 直接序列化私有类等于把内部布局写进云端文件格式，以后想动就动不了。
     *
     * 提醒去重标记（overquota/expiring）、last-sync 时间、同步次数这些**没有**进来：
     * 它们是"这台设备已经提示过了"的纯设备态，跨设备恢复只会导致新设备漏提醒。
     */
    data class UsageSnapshot(
        val url: String = "",
        val upload: Long = 0, val download: Long = 0, val total: Long = 0,
        val expire: Long = 0, val updatedAt: Long = 0,
        val dayDate: String = "", val dayBase: Long = 0,
        val monthDate: String = "", val monthBase: Long = 0,
        val history: List<UsagePoint> = emptyList()
    )

    /** 落盘的按订阅流量记录：快照 + 日/月基线 + 趋势。 */
    private data class UsageRecord(
        val upload: Long, val download: Long, val total: Long, val expire: Long, val updatedAt: Long,
        val dayDate: String, val dayBase: Long, val monthDate: String, val monthBase: Long,
        val history: List<UsagePoint>
    )

    private const val KEY_USAGE_BY_URL = "subscription_usage_by_url"
    private const val KEY_NOTIFIED_OVERQUOTA = "subscription_notified_overquota"
    private const val KEY_NOTIFIED_EXPIRING = "subscription_notified_expiring"

    private const val USAGE_HISTORY_MAX = 60
    private const val OVER_QUOTA_RATIO = 0.9
    private const val EXPIRING_WITHIN_MS = 7L * 24 * 60 * 60 * 1000

    private const val USAGE_CHANNEL_ID = "stun_usage_alerts"
    private const val USAGE_NOTIFY_OVERQUOTA_ID = 9001
    private const val USAGE_NOTIFY_EXPIRING_ID = 9002

    /** url → 最新快照，按更新时间升序。仅同步拿到 userinfo 头时更新。 */
    val usageLiveData = MutableLiveData<Map<String, SubscriptionUsage>>(emptyMap())

    /** 解析 `subscription-userinfo` 头：upload=1234; download=2234; total=1024000; expire=2218532293 */
    fun parseUsageHeader(header: String?): SubscriptionUsage? {
        if (header.isNullOrBlank()) return null
        val map = mutableMapOf<String, Long>()
        for (seg in header.split(';')) {
            val kv = seg.split('=', limit = 2)
            if (kv.size != 2) continue
            val v = kv[1].trim().toLongOrNull() ?: continue
            map[kv[0].trim().lowercase(java.util.Locale.ROOT)] = v
        }
        val upload = map["upload"] ?: 0L
        val download = map["download"] ?: 0L
        val total = map["total"] ?: 0L
        val expire = map["expire"] ?: 0L
        if (total <= 0 && upload <= 0 && download <= 0 && expire <= 0) return null
        return SubscriptionUsage(upload, download, total, expire, System.currentTimeMillis())
    }

    private fun readUsageMap(prefs: android.content.SharedPreferences): LinkedHashMap<String, UsageRecord> {
        val json = prefs.getString(KEY_USAGE_BY_URL, null) ?: return LinkedHashMap()
        val type = object : TypeToken<Map<String, UsageRecord>>() {}.type
        val raw = runCatching { gson.fromJson<Map<String, UsageRecord>>(json, type) }.getOrNull()
            ?: return LinkedHashMap()
        return LinkedHashMap(raw)
    }

    /**
     * 持久化某订阅的流量快照：刷新其日/月基线、追加趋势，并推 [usageLiveData]。
     * 跨日/跨月把"当日起始已用"重置为当前 used（≈上一周期结束值），之后 delta = used - 基线。
     */
    fun saveSubscriptionUsage(context: Context, url: String, usage: SubscriptionUsage) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val records = readUsageMap(prefs)
        val prev = records[url]
        val today = dayKey()
        val thisMonth = monthKey()
        val dayBase = if (prev == null || prev.dayDate != today) usage.used else prev.dayBase
        val monthBase = if (prev == null || prev.monthDate != thisMonth) usage.used else prev.monthBase
        val history = (prev?.history ?: emptyList()).toMutableList()
        history.add(UsagePoint(usage.updatedAt, usage.used))
        while (history.size > USAGE_HISTORY_MAX) history.removeAt(0)

        records[url] = UsageRecord(
            usage.upload, usage.download, usage.total, usage.expire, usage.updatedAt,
            today, dayBase, thisMonth, monthBase, history
        )
        // 按 updatedAt 升序重排，LiveData 末位即"最新一次同步的订阅"。
        val sorted = LinkedHashMap<String, SubscriptionUsage>()
        records.entries.sortedBy { it.value.updatedAt }.forEach { (u, r) ->
            sorted[u] = SubscriptionUsage(r.upload, r.download, r.total, r.expire, r.updatedAt)
        }
        prefs.edit { putString(KEY_USAGE_BY_URL, gson.toJson(records)) }
        usageLiveData.postValue(sorted)
    }

    /** 某订阅的完整流量视图（无记录返回 null）。 */
    fun getUsageForUrl(context: Context, url: String): UsageView? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val r = readUsageMap(prefs)[url] ?: return null
        val usage = SubscriptionUsage(r.upload, r.download, r.total, r.expire, r.updatedAt)
        return UsageView(usage, usage.used - r.dayBase, usage.used - r.monthBase, r.history.map { it.t to it.u })
    }

    /** 最近一次同步拿到快照的那条订阅的流量（多订阅时的兜底展示 / 提醒用）。 */
    fun getSubscriptionUsage(context: Context): SubscriptionUsage? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val latest = readUsageMap(prefs).maxByOrNull { it.value.updatedAt }?.value ?: return null
        return SubscriptionUsage(latest.upload, latest.download, latest.total, latest.expire, latest.updatedAt)
    }

    /** 导出全部订阅的流量快照供云备份（无记录返回空列表）。 */
    fun exportUsageSnapshot(context: Context): List<UsageSnapshot> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return readUsageMap(prefs).map { (url, r) ->
            UsageSnapshot(
                url,
                r.upload, r.download, r.total, r.expire, r.updatedAt,
                r.dayDate, r.dayBase, r.monthDate, r.monthBase, r.history
            )
        }
    }

    /**
     * 纯函数：把云备份里的流量快照并进本地记录表（按 url 取并集，不删本地）。
     *
     * 抽成不碰 Context / prefs 的纯函数，是为了能在 core 的**纯 JVM 单测**里直接验
     * （core 单测不挂 Robolectric）。这段的坑全在"什么时候该重置基线"上，
     * 靠手点界面根本试不出来。
     *
     * - 同 url：[UsageSnapshot.updatedAt] 新者胜，本地更新过的不被旧备份冲回去
     * - 基线：只在 dayDate / monthDate 与「今天 / 本月」一致时才沿用备份里的基准值，
     *   否则重置为当前 used —— 等价于「本周期从现在开始算」
     * - 趋势点：只保留最近 [USAGE_HISTORY_MAX] 个
     */
    internal fun mergeUsageSnapshots(
        existing: Map<String, UsageSnapshot>,
        incoming: List<UsageSnapshot>,
        today: String,
        thisMonth: String
    ): LinkedHashMap<String, UsageSnapshot> {
        val merged = LinkedHashMap(existing)
        for (s in incoming) {
            if (s.url.isBlank()) continue
            val prev = merged[s.url]
            if (prev != null && prev.updatedAt > s.updatedAt) continue // 本地更新，保留本地
            val used = s.upload + s.download
            merged[s.url] = s.copy(
                dayBase = if (s.dayDate == today) s.dayBase else used,
                monthBase = if (s.monthDate == thisMonth) s.monthBase else used,
                history = s.history.takeLast(USAGE_HISTORY_MAX)
            )
        }
        return merged
    }

    /**
     * 导入云备份里的流量快照，落盘并刷新 [usageLiveData]。
     *
     * 合并规则见 [mergeUsageSnapshots]（日/月基线要按当前周期重算 —— 基线是跟日期绑定的，
     * 跨天/跨设备恢复时硬套旧基线会让「今日新增」变成两个周期之间的差额，甚至负数）。
     */
    fun importUsageSnapshot(context: Context, snapshots: List<UsageSnapshot>) {
        if (snapshots.isEmpty()) return
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val merged = mergeUsageSnapshots(
            readUsageMap(prefs).mapValues { (url, r) -> r.toSnapshot(url) },
            snapshots, dayKey(), monthKey()
        )
        // 与 saveSubscriptions 的裁剪口径保持一致：URL 是这张表的键，孤儿项不该被恢复带进来。
        // 但订阅表本身为空时不动手 —— 那多半是订阅分区没恢复成功，不能顺手把流量记录也抹了。
        val keep = getSubscriptions(context).map { it.url }.toHashSet()
        val records = LinkedHashMap<String, UsageRecord>()
        merged.forEach { (url, s) -> if (keep.isEmpty() || url in keep) records[url] = s.toRecord() }
        prefs.edit { putString(KEY_USAGE_BY_URL, gson.toJson(records)) }
        seedUsageLiveData(context)
    }

    /** [UsageRecord] → [UsageSnapshot]（键即 url）。两类型字段逐一同名同序，只多一个 url。 */
    private fun UsageRecord.toSnapshot(url: String) = UsageSnapshot(
        url, upload, download, total, expire, updatedAt, dayDate, dayBase, monthDate, monthBase, history
    )

    /** [UsageSnapshot] → [UsageRecord]（丢掉落盘的 url，它由 Map 的键承载）。 */
    private fun UsageSnapshot.toRecord() = UsageRecord(
        upload, download, total, expire, updatedAt, dayDate, dayBase, monthDate, monthBase, history
    )

    /** 进入订阅页时把已存快照灌进 LiveData，避免空白闪烁。 */
    fun seedUsageLiveData(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val sorted = LinkedHashMap<String, SubscriptionUsage>()
        readUsageMap(prefs).entries.sortedBy { it.value.updatedAt }.forEach { (u, r) ->
            sorted[u] = SubscriptionUsage(r.upload, r.download, r.total, r.expire, r.updatedAt)
        }
        usageLiveData.postValue(sorted)
    }

    /** 清除某订阅的流量记录与提醒去重标记（删订阅时调用）。 */
    private fun clearUsageForUrl(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val records = readUsageMap(prefs)
        if (records.remove(url) != null) {
            prefs.edit { putString(KEY_USAGE_BY_URL, gson.toJson(records)) }
        }
        prefs.edit {
            remove("$KEY_NOTIFIED_OVERQUOTA:$url")
            remove("$KEY_NOTIFIED_EXPIRING:$url")
        }
    }

    /**
     * 超额 / 即将到期提醒。按状态跳变去重（标记按 URL 分键），回落到阈值内后重置以便再次触发。
     * 仅在同步拿到有效快照时调用（网络线程）。
     */
    fun checkUsageAlerts(context: Context, url: String, usage: SubscriptionUsage) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val overKey = "$KEY_NOTIFIED_OVERQUOTA:$url"
        val expKey = "$KEY_NOTIFIED_EXPIRING:$url"

        val overQuota = !usage.unlimited && usage.ratio >= OVER_QUOTA_RATIO
        val prevOver = prefs.getBoolean(overKey, false)
        if (overQuota && !prevOver) {
            notifyUsage(
                context, USAGE_NOTIFY_OVERQUOTA_ID,
                context.getString(R.string.subscription_usage_over_quota_title),
                context.getString(
                    R.string.subscription_usage_over_quota_body,
                    (usage.ratio * 100).roundToInt(),
                    AppUtils.formatBytes(usage.used),
                    AppUtils.formatBytes(usage.total)
                )
            )
            prefs.edit { putBoolean(overKey, true) }
        } else if (!overQuota && prevOver) {
            prefs.edit { putBoolean(overKey, false) }
        }

        val expiring = usage.hasExpire && (usage.expire * 1000L - now) in 1..EXPIRING_WITHIN_MS
        val prevExp = prefs.getBoolean(expKey, false)
        if (expiring && !prevExp) {
            val days = ((usage.expire * 1000L - now) / 86400000L).toInt()
            notifyUsage(
                context, USAGE_NOTIFY_EXPIRING_ID,
                context.getString(R.string.subscription_usage_expiring_title),
                context.getString(R.string.subscription_usage_expiring_body, days, AppUtils.formatBytes(usage.total))
            )
            prefs.edit { putBoolean(expKey, true) }
        } else if (!expiring && prevExp) {
            prefs.edit { putBoolean(expKey, false) }
        }
    }

    private fun notifyUsage(context: Context, id: Int, title: String, body: String) {
        val appCtx = context.applicationContext
        val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                USAGE_CHANNEL_ID,
                context.getString(R.string.subscription_usage_title),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = context.getString(R.string.subscription_usage_alerts_channel_desc) }
            nm.createNotificationChannel(ch)
        }
        val n = NotificationCompat.Builder(appCtx, USAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    }

    private fun dayKey(): String {
        val c = java.util.Calendar.getInstance()
        return "%04d-%02d-%02d".format(
            java.util.Locale.ROOT, c.get(java.util.Calendar.YEAR),
            c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    private fun monthKey(): String {
        val c = java.util.Calendar.getInstance()
        return "%04d-%02d".format(java.util.Locale.ROOT, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1)
    }
}
