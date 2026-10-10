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

    // ── 旧存储（SharedPreferences）─────────────────────────────────────────────
    // v25 起订阅升格为 Room 的 `subscriptions` 表（见 [Subscription] / MIGRATION_24_25），
    // 这几组 key 从此**只读不写**：唯一用途是 [migrateLegacyPrefsIfNeeded] 的一次性搬运。
    // 刻意保留不删 —— 旧 APK 回滚后仍读得到原数据（见该函数的注释）。
    private const val PREF_NAME = "stun_subscription_prefs"
    /** 订阅清单（`SubEntry` JSON 数组）→ `subscriptions` 表本体。 */
    private const val KEY_SUBS = "subscription_list"
    /** `KEY_SUBS` 出现之前的单订阅 URL。 */
    private const val KEY_URL = "subscription_url"
    /** 全局「上次成功同步时间」→ 改由 `subscriptions.lastSyncTime` 的最大值推导，见 [getLastSyncTime]。 */
    private const val KEY_LAST_SYNC = "subscription_last_sync"
    /** 每条订阅各自的上次成功同步时间（url → epoch ms）→ `subscriptions.lastSyncTime`。 */
    private const val KEY_LAST_SYNC_MAP = "subscription_last_sync_map"
    /** 每条订阅上次导入的节点数（url → Int）→ `subscriptions.syncCount`。 */
    private const val KEY_SYNC_COUNT_MAP = "subscription_sync_count_map"
    /** 一次性搬运完成标记（SharedPreferences → `subscriptions` 表）。 */
    private const val KEY_MIGRATED_TO_ROOM = "subscription_migrated_to_room_v25"

    private val gson = Gson()

    // ── Room 存储 ─────────────────────────────────────────────────────────────

    /**
     * 订阅表访问器。
     *
     * ⚠️ 所有对订阅表的读写都**必须**经由本函数：它是 [ensureMigrated] 的唯一咽喉点，
     * 保证「SharedPreferences 里的旧订阅已经搬进 Room」先于任何一次读写完成。
     */
    private fun dao(context: Context): SubscriptionDao {
        ensureMigrated(context)
        return AppDatabase.getDatabase(context).subscriptionDao()
    }

    /** code: pin_required / pin_invalid，供 WebUI 等调用方做本地化映射 */
    class SubscriptionException(val code: String, message: String) : Exception(message)

    data class ParseResult(
        val profiles: List<Profile>,
        val pinRequired: Boolean = false,
        val pinInvalid: Boolean = false
    )

    /**
     * 一条订阅：**稳定的本地 id** + 链接 + 可选 PIN（加密订阅用）+ 响应头解析出的元信息。
     *
     * [subId] 就是 `subscriptions` 表的主键（本地 UUID，创建后永不改变），并且是
     * [Profile.subId] 的关联键 —— 所以**改 URL 不再断开关联**。这正是本类型从
     * 「URL 即身份」改成「subId 即身份」的原因（见 [Subscription] 的类注释）。
     *
     * 空 [subId] = 「这条还没落库」（面板上刚加的行 / 调用方临时构造的条目）：
     * [saveSubscriptions] 会给它补一个；[syncAllSubscriptions] 会在同步前补齐
     * （找不到就建一行，与旧实现 `persistSubscriptionMeta` 的「没找到就 add」同义）。
     *
     * 该字段随 Gson 一起进云备份 JSON，所以跨设备恢复后节点与订阅的关联仍在。
     * 也正因为多了一个字段，**新备份给旧版本 App 读**时旧版本会忽略它（Gson 容错），
     * 只是退化成旧的「按 URL 连」行为 —— 单向降级不炸。
     */
    data class SubEntry(
        val subId: String = "",
        val url: String = "",
        val pin: String = "",
        /** content-disposition 解析出的订阅名称（无则为空） */
        val name: String = "",
        /** profile-web-page-url 解析出的首页地址（仅 http/https，无则为空） */
        val homePage: String = "",
        /** profile-update-interval 解析出的自动更新间隔（小时；0=未指定） */
        val updateIntervalHours: Int = 0
    )

    /** [Subscription]（表行）→ [SubEntry]（对外模型）。附属数据（用量/计时/提醒标记）不对外。 */
    private fun Subscription.toEntry() = SubEntry(
        subId = subId, url = url, pin = pin,
        name = name, homePage = homePage, updateIntervalHours = updateIntervalHours
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
        val message: String? = null,
        /**
         * 来源订阅的本地 id。放在**最后**且带默认值，这样既不影响既有调用点的位置参数，
         * 又能让"面板按行匹配"用上它 —— 同步是异步的，用 url 匹配会在用户同时编辑 URL 时串行。
         */
        val subId: String = ""
    )

    /** 单条订阅在实时同步流里的状态，供订阅面板逐行显示进度/结果。 */
    enum class SubSyncStatus { SYNCING, SUCCESS, FAILED }

    /**
     * [subId] 是行匹配键（面板按它把状态贴回对应的行）；[url] 只用于日志/展示 ——
     * 同步过程中用户仍可能在编辑 URL，用 URL 匹配会串行。
     */
    data class SubSyncState(
        val subId: String,
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

    /**
     * 把订阅 URL 变成可以安全写日志的形态：去掉 fragment、query，并把 userinfo 换成 ***。
     *
     * 订阅 URL 通常本身就是凭据（鉴权参数或账号密码直接编在 URL 里），而日志会明文落盘、
     * 在日志界面可见、还会跟着备份走。[Uri] 不直接暴露 userinfo，所以这里按 '@' 手工切分。
     */
    private fun redactedUrl(raw: String): String {
        val noFragment = raw.substringBefore('#')
        val noQuery = noFragment.substringBefore('?')
        val at = noQuery.lastIndexOf('@')
        if (at < 0) return noQuery
        val schemeEnd = noQuery.indexOf("://")
        val authorityStart = (schemeEnd + 3).coerceAtLeast(0)
        if (at <= authorityStart) return noQuery
        return noQuery.substring(0, authorityStart) + "***@" + noQuery.substring(at + 1)
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

    /** 订阅列表（按面板展示顺序，即 `sortIndex`）。 */
    fun getSubscriptions(context: Context): List<SubEntry> =
        dao(context).getAll().map { it.toEntry() }

    /**
     * 覆盖式保存整个订阅列表：**入参顺序即展示顺序**；返回真正落库的列表（含补齐的 [SubEntry.subId]）。
     *
     * 行身份按 `subId` 认领；`subId` 为空时按 URL 认领已存行。于是：
     * - 只改了 URL → 同一行 `UPDATE`，用量历史 / 同步计时 / 节点归属一个都不动（本重构的全部意义）；
     * - 不带 subId 的老调用方（WebUI 旧前端、旧备份、临时条目）→ 按 URL 找回原行，行为与重构前一致；
     * - 真正的新行 → 分配一个新的 subId。
     *
     * 不在本次集合里的行一律删除（整体覆盖语义）。用量记录与提醒标记是**行上的列**，
     * 所以旧实现那套「按 URL 集合裁三张 JSON map」的孤儿回收不再需要 —— 行没了，附属数据跟着没。
     *
     * ⚠️ 空集合必须走 [SubscriptionDao.deleteAll]：`NOT IN ()` 是 SQLite 语法错。
     */
    fun saveSubscriptions(context: Context, subs: List<SubEntry>): List<SubEntry> {
        val d = dao(context)
        val existing = d.getAll()
        val byId = existing.associateBy { it.subId }
        val byUrl = existing.groupBy { it.url }.mapValues { it.value.first() }

        val kept = LinkedHashMap<String, Subscription>()
        subs.forEach { entry ->
            if (entry.url.isBlank()) return@forEach
            val id = entry.subId.takeIf { it.isNotBlank() }
                ?: byUrl[entry.url]?.subId
                ?: UUID.randomUUID().toString()
            val base = byId[id]
            // 同一 subId 出现两次时后者覆盖前者 —— 与"入参即真相"的覆盖语义一致。
            kept[id] = base?.copy(
                url = entry.url,
                pin = entry.pin,
                name = entry.name,
                homePage = entry.homePage,
                updateIntervalHours = entry.updateIntervalHours,
                sortIndex = kept.size
            ) ?: Subscription(
                subId = id,
                url = entry.url,
                pin = entry.pin,
                name = entry.name,
                homePage = entry.homePage,
                updateIntervalHours = entry.updateIntervalHours,
                sortIndex = kept.size
            )
        }

        val rows = kept.values.toList()
        if (rows.isEmpty()) {
            d.deleteAll()
        } else {
            d.upsertAll(rows)
            d.deleteNotIn(kept.keys.toList())
        }
        // 名称缓存就地重建成最新快照，而不是置空等懒加载：增/删/改名都从这里过，
        // 置空的话下一次节点列表 bind（主线程）就得查一次库 —— 正是要避开的那个卡顿。
        subNameCache = rows.associate { it.subId to it.name.trim() }
        return rows.map { it.toEntry() }
    }

    /**
     * subId → 订阅名 的内存快照。
     *
     * 节点列表要给每个订阅导入的节点打"来源订阅"徽标，而 `bind()` 是滚动热路径 ——
     * 那里查一次库太贵（而且 Room 的同步查询在主线程会直接抛），所以缓一层。
     * 缓存会在三处**就地把整个快照换成最新的**（不是置空）：[saveSubscriptions]（增/删/改名）、
     * [syncAllSubscriptions] 收尾（同步会刷新订阅名）、以及 AppBootstrap 上的一次性搬运。
     * 另有 [warmSubscriptionNameCache] 供 UI 在 IO 上预热。
     */
    @Volatile
    private var subNameCache: Map<String, String>? = null

    /**
     * 某条订阅的展示名（按 subId 精确匹配，未命名返回空串）。
     *
     * ⚠️ 本函数会被 `ProfileAdapter.bind()` 在**主线程**调用，所以缓存没热时**只返回空串、
     * 绝不查库**（Room 的同步查询在主线程会抛 `IllegalStateException`）。缓存由
     * `AppBootstrap` 的一次性搬运 / `MainViewModel.init` 在 IO 上焐热，正常流程下永远是热的；
     * 万一没热到，代价只是这一帧少一个徽标，而不是崩。
     */
    fun subscriptionNameFor(context: Context, subId: String): String {
        if (subId.isBlank()) return ""
        val cache = subNameCache ?: return ""
        return cache[subId.trim()].orEmpty()
    }

    /** 预热上面的缓存。**必须在 IO 线程调**（会查一次库）。 */
    fun warmSubscriptionNameCache(context: Context) {
        if (subNameCache == null) {
            subNameCache = dao(context).getAll().associate { it.subId to it.name.trim() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 一次性搬运：SharedPreferences → `subscriptions` 表
    // ─────────────────────────────────────────────────────────────────────────

    private val migrateLock = Any()

    /** 内存快路径：搬过之后就不必再读一次 prefs 标记位。 */
    @Volatile
    private var migrated = false

    private fun ensureMigrated(context: Context) {
        if (migrated) return
        migrateLegacyPrefsIfNeeded(context)
    }

    /**
     * 把 SharedPreferences 里的订阅清单 / 同步计时 / 用量记录搬进 `subscriptions` 表。
     *
     * ## 为什么必须有这一步
     * Room 的 `MIGRATION_24_25` 只建表 —— 迁移函数拿不到 `Context`，读不了 SharedPreferences，
     * 而订阅数据本身全在 prefs 里。不搬的话升级用户会看到"订阅全没了"。
     *
     * ## 幂等 / 并发
     * 由 [KEY_MIGRATED_TO_ROOM] 标记位 + 内存 [migrated] 双重把关，整段跑在 [migrateLock] 里。
     * 更关键的是 [dao] 把 [ensureMigrated] 放在了**所有订阅表读写之前**，所以不存在
     * "先读到空表 → 保存空列表 → 老数据被顺带删掉"这种窗口。
     *
     * ## 与「搬运前就已经产生的新数据」冲突时怎么办
     * 搬运用**并集**而不是覆盖：Room 里已经存在的 URL 一律不动（那是用户已操作过的新数据，更权威），
     * 只补进 prefs 里有而表里没有的。反过来若表里是空的，则 prefs 全量搬入。
     *
     * ## 旧 key 一律不删
     * 搬完只写一个标记位，其余 key 原样留着 —— 回滚到旧 APK 时数据还在。代价是那些 key
     * 会永远停在"搬运那一刻"的快照上（之后新数据只进 Room）；这是刻意的取舍：
     * 宁可回滚后看到一份稍旧的列表，也不要回滚后看到空列表。
     *
     * 由 `AppBootstrap.start` 在 IO 上预热调用；即便那条路失败，[dao] 也会在第一次
     * 真正读写订阅表之前补跑（那时读的是同一份数据，只是晚了）。
     */
    fun migrateLegacyPrefsIfNeeded(context: Context) {
        val app = context.applicationContext
        synchronized(migrateLock) {
            if (migrated) return
            val prefs = app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_MIGRATED_TO_ROOM, false)) {
                migrated = true
                return
            }
            try {
                doMigrateLegacyPrefs(app, prefs)
            } catch (t: Throwable) {
                // 不写标记位 ⇒ 下次启动 / 下次访问会重试。搬运是纯增量（只补缺失 URL），重试安全。
                StunLogger.e(TAG, "Legacy subscription migration failed (will retry on next access)", t)
                return
            }
            prefs.edit { putBoolean(KEY_MIGRATED_TO_ROOM, true) }
            migrated = true
        }
    }

    private fun doMigrateLegacyPrefs(app: Context, prefs: android.content.SharedPreferences) {
        // 1) 订阅清单：`subscription_list`（JSON 数组）优先，回落到更早的单 URL 存储。
        val legacyEntries: List<SubEntry> = prefs.getString(KEY_SUBS, null)?.let { json ->
            runCatching { gson.fromJson(json, Array<SubEntry>::class.java)?.toList() }.getOrNull()
        } ?: prefs.getString(KEY_URL, "")?.trim()?.takeIf { it.isNotBlank() }
            ?.let { listOf(SubEntry(url = it)) }
        ?: emptyList()
        val cleaned = legacyEntries.filter { it.url.isNotBlank() }

        val lastSyncMap = readLongMap(prefs, KEY_LAST_SYNC_MAP)
        val countMap = readIntMap(prefs, KEY_SYNC_COUNT_MAP)
        val usageJsonMap = readUsageJsonMap(prefs)
        val globalLastSync = prefs.getLong(KEY_LAST_SYNC, 0L)

        val d = AppDatabase.getDatabase(app).subscriptionDao()
        val existing = d.getAll()
        val seenUrls = existing.map { it.url }.toHashSet()

        var index = existing.size
        val added = mutableListOf<Subscription>()
        cleaned.forEach { entry ->
            // 表里已有同 URL 就不再搬 —— 那是搬运前用户已经操作过的数据（见函数注释的"并集"）。
            if (!seenUrls.add(entry.url)) return@forEach
            added += Subscription(
                url = entry.url,
                pin = entry.pin,
                name = entry.name,
                homePage = entry.homePage,
                updateIntervalHours = entry.updateIntervalHours,
                // 更老的数据只有"全局上次同步时间"（逐条 map 是后加的）：逐条没记时回落到它，
                // 否则单订阅的老用户升级后会显示"从未同步"。
                lastSyncTime = lastSyncMap[entry.url] ?: globalLastSync,
                syncCount = countMap[entry.url] ?: -1,
                usageJson = usageJsonMap[entry.url].orEmpty(),
                notifiedOverquota = prefs.getBoolean("$KEY_NOTIFIED_OVERQUOTA:${entry.url}", false),
                notifiedExpiring = prefs.getBoolean("$KEY_NOTIFIED_EXPIRING:${entry.url}", false),
                sortIndex = index++
            )
        }
        if (added.isNotEmpty()) {
            d.upsertAll(added)
            StunLogger.i(TAG, "Migrated ${added.size} subscription(s) from SharedPreferences to Room")
        }
        // 缓存就地重建：搬运是启动期最早碰订阅表的地方，顺手把缓存焐热，
        // 免得节点列表第一帧在主线程补一次查库（那条路已经被 [subscriptionNameFor] 关掉了）。
        subNameCache = d.getAll().associate { it.subId to it.name.trim() }
    }

    /** 读一个 url → Long 的旧 JSON map（解析失败一律当空，绝不因为历史脏数据卡住启动）。 */
    private fun readLongMap(prefs: android.content.SharedPreferences, key: String): Map<String, Long> =
        prefs.getString(key, null)?.let { json ->
            runCatching {
                gson.fromJson<Map<String, Long>>(json, object : TypeToken<Map<String, Long>>() {}.type)
            }.getOrNull()
        } ?: emptyMap()

    private fun readIntMap(prefs: android.content.SharedPreferences, key: String): Map<String, Int> =
        prefs.getString(key, null)?.let { json ->
            runCatching {
                gson.fromJson<Map<String, Int>>(json, object : TypeToken<Map<String, Int>>() {}.type)
            }.getOrNull()
        } ?: emptyMap()

    /**
     * 旧 `subscription_usage_by_url`：url → `UsageRecord` 的 JSON。
     *
     * 刻意按 `JsonElement` 收、再 `toString()` 回原样字符串：`UsageRecord` 的内部形状
     * 还会演进，用 Element 中转就能**零解析**地整串搬进 `subscriptions.usageJson`，
     * 不必在这里同步维护第二份解析代码。
     */
    private fun readUsageJsonMap(prefs: android.content.SharedPreferences): Map<String, String> {
        val json = prefs.getString(KEY_USAGE_BY_URL, null) ?: return emptyMap()
        val raw = runCatching {
            gson.fromJson<Map<String, com.google.gson.JsonElement>>(
                json, object : TypeToken<Map<String, com.google.gson.JsonElement>>() {}.type
            )
        }.getOrNull() ?: return emptyMap()
        return raw.mapValues { it.value.toString() }
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
            publishStates(subs.map { SubSyncState(it.subId, it.url, SubSyncStatus.SYNCING) })
            val results = syncAllSubscriptions(appContext, subs)
            publishStates(results.map {
                SubSyncState(it.subId, it.url, if (it.success) SubSyncStatus.SUCCESS else SubSyncStatus.FAILED, it)
            })
            onFinished?.let { cb -> withContext(Dispatchers.Main) { cb(results) } }
        }
    }

    /** 触发"只同步一条订阅"（行内单独同步按钮）。同样走 [syncStateLiveData] 报告该条订阅的状态。 */
    fun startSyncOne(context: Context, entry: SubEntry, onFinished: ((SyncResultItem) -> Unit)? = null) {
        if (isSyncing) return
        val appContext = context.applicationContext
        syncJob = syncScope.launch {
            publishStates(listOf(SubSyncState(entry.subId, entry.url, SubSyncStatus.SYNCING)))
            val results = syncAllSubscriptions(appContext, listOf(entry))
            val item = results.firstOrNull()
                ?: SyncResultItem(entry.url, false, subId = entry.subId, errorCode = SyncError.SYNC_FAILED)
            publishStates(
                listOf(
                    SubSyncState(
                        item.subId.ifBlank { entry.subId },
                        entry.url,
                        if (item.success) SubSyncStatus.SUCCESS else SubSyncStatus.FAILED,
                        item
                    )
                )
            )
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

    /** 追加一条订阅（已存在同 URL 时不动）。新订阅排在最前，与旧实现的 `subs.add(0, …)` 一致。 */
    fun saveSubscriptionUrl(context: Context, url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        val subs = getSubscriptions(context)
        if (subs.any { it.url == trimmed }) return
        saveSubscriptions(context, listOf(SubEntry(url = trimmed)) + subs)
    }

    /**
     * 归属某订阅的节点数（按 `profiles.subId` 精确匹配）。删订阅前用它问"要一起删 N 个节点吗"。
     *
     * suspend + IO：这是一次 Room 的 COUNT 查询。订阅面板是在点击回调（主线程）里问它的，
     * 不切线程就是"主线程查库"——节点上千时肉眼可见地卡一下，确认框才弹出来。
     */
    suspend fun countSubscriptionNodes(context: Context, subId: String): Int =
        withContext(Dispatchers.IO) {
            // 空 subId 是"手动节点"的标记：`subId = ''` 会匹配**全部**手动节点，
            // 必须在这里显式挡掉，否则"删订阅时顺带删它的节点"会变成清库。
            if (subId.isBlank()) 0
            else AppDatabase.getDatabase(context).profileDao().countBySubId(subId)
        }

    /**
     * 删除归属某订阅的全部节点（收藏与当前选中豁免，避免误删正在用的）。返回删除数。
     *
     * suspend + IO 同上：一次全表查询 + N 次单条删除，全塞主线程就是 O(N) 次卡顿。
     * 注意块内没有挂起点，所以一旦开始跑就不会被取消到一半（不会删一半留一半）。
     */
    suspend fun deleteSubscriptionNodes(context: Context, subId: String): Int =
        withContext(Dispatchers.IO) {
            if (subId.isBlank()) return@withContext 0
            val selectedId = SettingsManager.getSelectedProfileId(context)
            val doomed = ProfileManager.getProfiles(context).filter {
                it.subId == subId && !it.favorite && it.id != selectedId
            }
            doomed.forEach { ProfileManager.deleteProfile(context, it) }
            doomed.size
        }

    /**
     * 移除一条订阅（只删这一行，不动它的节点）。
     *
     * 该行的用量记录与提醒标记是行上的列，随行一起消失 —— 旧实现还要额外
     * `clearUsageForUrl` 去 SharedPreferences 里清两张表，现在不需要了。
     */
    fun removeSubscription(context: Context, subId: String) {
        if (subId.isBlank()) return
        saveSubscriptions(context, getSubscriptions(context).filter { it.subId != subId })
    }

    /**
     * 删订阅 +（可选）连它的节点，整段丢到 [persistScope] 上跑完。
     *
     * 调用方（订阅面板）在主线程上，点完确认就可以关面板；这里会先切 IO 再查库/删库，
     * 且不受面板生命周期影响（见 [persistScope] 的说明）。
     */
    fun removeSubscriptionAsync(context: Context, subId: String, deleteNodes: Boolean) {
        if (subId.isBlank()) return
        val appContext = context.applicationContext
        persistScope.launch {
            if (deleteNodes) deleteSubscriptionNodes(appContext, subId)
            removeSubscription(appContext, subId)
        }
    }

    /**
     * 全局"上次成功同步时间" = 所有订阅 `lastSyncTime` 的**最大值**（0=从未成功）。
     *
     * 旧实现单独存一个 prefs 标量，于是"删掉最后一条订阅后仍显示上次同步于 X"。
     * 改成从行推导后，订阅没了这个值自然归零 —— 单一事实来源，也少一处要与订阅表同步落盘的状态。
     */
    fun getLastSyncTime(context: Context): Long =
        dao(context).getAll().maxOfOrNull { it.lastSyncTime } ?: 0L

    /**
     * 每条订阅的上次成功同步时间（subId → epoch ms，0=从未）。
     *
     * 周期同步要按各自的 `profile-update-interval` 逐条门控 —— 一次批量取回，
     * 别在循环里一条一条查（订阅数虽小，但那是 N 次跨进程查询）。
     *
     * 刻意**不**把 `lastSyncTime` 放进 [SubEntry]：它是纯设备态的进度记录，
     * 进了 [SubEntry] 就会顺着云备份 JSON 跨设备传播（同 `UsageSnapshot` 里排除提醒标记的理由）。
     */
    fun getLastSyncTimes(context: Context): Map<String, Long> =
        dao(context).getAll().associate { it.subId to it.lastSyncTime }

    /** 单条订阅的同步元信息：上次成功同步时间 + 导入节点数（count=-1 表示旧数据未记录）。 */
    data class SyncMeta(val time: Long, val count: Int)

    /**
     * 单条订阅的同步元信息（无记录 / 从未成功返回 null）。
     *
     * 挂在 subId 上而不是 URL 上：换域名不会让面板上的"N 节点 · 上次同步"变回空白。
     */
    fun getSyncMetaForSub(context: Context, subId: String): SyncMeta? {
        if (subId.isBlank()) return null
        val row = dao(context).getById(subId) ?: return null
        if (row.lastSyncTime <= 0L) return null
        return SyncMeta(row.lastSyncTime, row.syncCount)
    }

    /**
     * 同步全部已保存订阅（也可传入临时订阅列表）。逐条拉取，节点按 id 全局去重合并。
     * 返回每条订阅的结果；单条失败不影响其他订阅。
     *
     * 入参里 `subId` 为空的条目会先 [ensureSubscription] 补齐 —— 调用方可能是
     * WebUI / MCP 的一次性同步（拿着只有 URL 的临时条目），或订阅面板上还没落库的新行。
     */
    suspend fun syncAllSubscriptions(
        context: Context,
        entries: List<SubEntry>? = null
    ): List<SyncResultItem> = withContext(Dispatchers.IO) {
        val subs = (entries ?: getSubscriptions(context)).map { ensureSubscription(context, it) }
        if (subs.isEmpty()) {
            return@withContext listOf(SyncResultItem("", false, errorCode = SyncError.URL_EMPTY))
        }

        val existingById = ProfileManager.getProfiles(context).associateBy { it.id }.toMutableMap()
        val results = mutableListOf<SyncResultItem>()
        StunLogger.d(
            TAG,
            "Batch sync start: ${subs.size} subscription(s), ${existingById.size} profile(s) already in DB"
        )

        val d = dao(context)
        for (sub in subs) {
            // subId 在这里统一补上：syncSingle 内部有多条返回路径（URL 非法 / HTTP 错 / PIN 错 /
            // 无有效节点 …），逐个加参数最容易漏，而"结果能贴回哪一行"对面板是刚需。
            val item = syncSingle(context, sub, existingById).copy(subId = sub.subId)
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
                // 全局"上次同步时间"不再单独存：它就是这里的 lastSyncTime 的最大值。
                d.updateSyncMeta(
                    sub.subId,
                    System.currentTimeMillis(),
                    item.importedCount + item.updatedCount
                )
            }
        }

        val okCount = results.count { it.success }
        // 名称缓存就地重建：同步会刷新订阅名（persistSubscriptionMeta），
        // 而这里已经在 IO 上、又刚好要收尾，比让下一次 bind 自己去查库划算。
        if (okCount > 0) {
            subNameCache = d.getAll().associate { it.subId to it.name.trim() }
        }
        StunLogger.d(TAG, "Batch sync done: $okCount/${results.size} subscription(s) succeeded")
        results
    }

    /**
     * 把一条只有 URL 的条目接到表里的某一**行**上，返回带 subId 的条目。
     *
     * 这是"URL 只是可变的附属字段"之后必须补的一步：节点盖戳、用量/提醒记账都要 subId，
     * 而调用方可能拿不出它。三档：
     * 1. 已带 subId → 直接用；
     * 2. 表里有同 URL 的行 → 认领它的 subId（老前端 / 旧备份 / 面板上刚改过 URL 的行）；
     * 3. 都没有 → 建一行并排在列表最前（与旧实现 `persistSubscriptionMeta` 里
     *    "没找到就 `subs.add(0, …)`" 同义：同步一个不在列表里的链接就等于把它加进列表）。
     */
    private fun ensureSubscription(context: Context, entry: SubEntry): SubEntry {
        if (entry.subId.isNotBlank()) return entry
        val d = dao(context)
        d.getByUrl(entry.url)?.let { return entry.copy(subId = it.subId) }
        val front = (d.getAll().minOfOrNull { it.sortIndex } ?: 0) - 1
        val row = Subscription(
            url = entry.url,
            pin = entry.pin,
            name = entry.name,
            homePage = entry.homePage,
            updateIntervalHours = entry.updateIntervalHours,
            sortIndex = front
        )
        d.upsert(row)
        return entry.copy(subId = row.subId)
    }

    /**
     * 兼容旧接口：单订阅同步。url 为空时用已保存的第一条；带 pin 时优先用 pin。
     */
    suspend fun syncSubscription(context: Context, targetUrl: String? = null, pin: String? = null): Result<Int> = withContext(Dispatchers.IO) {
        val subUrl = targetUrl?.trim() ?: getSubscriptionUrl(context)
        if (subUrl.isBlank()) {
            return@withContext Result.failure(SubscriptionException(SyncError.URL_EMPTY, "Subscription URL is empty"))
        }
        // 只有 URL（没有 subId）：syncAllSubscriptions 会按 URL 认领已存行、或为它建一行，
        // 所以这里的节点归属、用量记账都不会丢。
        val entry = SubEntry(url = subUrl, pin = pin.orEmpty())
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
            // 订阅 URL 本身就是凭据（多数订阅服务把鉴权直接编在 URL 里），而 StunLogger
            // 的日志是明文落盘、还会被日志界面与备份带上走的。INFO 级别会进更多采集面，
            // 所以这里只打 host，把 query/userinfo 部分去掉。
            StunLogger.i(TAG, "Fetching subscription from: ${redactedUrl(subUrl)}")
            StunLogger.d(
                TAG,
                "Sync start [${redactedUrl(subUrl)}]: pin=${if (sub.pin.isBlank()) "none" else "set"}, " +
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
                    saveSubscriptionUsage(context, sub.subId, usage)
                    checkUsageAlerts(context, sub.subId, usage)
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

                // 导入/更新：按 id 全局去重；每个落库的节点都盖上来源订阅的**本地 subId**。
                var imported = 0
                var updated = 0
                val newIds = HashSet<String>()
                importedProfiles.forEachIndexed { index, newProfile ->
                    val profileToSave = newProfile.copy(
                        id = newProfile.id.ifBlank { UUID.randomUUID().toString() },
                        subId = sub.subId,
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
                val removed = pruneStaleSubscriptionNodes(context, sub.subId, newIds)

                // 持久化订阅链接 + 响应头解析出的元信息（名称/首页/更新间隔）
                persistSubscriptionMeta(context, sub, name, homePage, intervalHours)
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
     * 删除"归属 [subId] 但本次 payload 已不含"的节点。收藏与当前选中节点豁免。
     * 同步只在成功解析到非空 payload 后调用（空 payload 走 no_valid_nodes 早退），
     * 所以不会因一次网络抖动把整订阅节点清空。
     */
    private fun pruneStaleSubscriptionNodes(context: Context, subId: String, keepIds: Set<String>): Int {
        val selectedId = SettingsManager.getSelectedProfileId(context)
        val stale = staleSubscriptionNodes(
            ProfileManager.getProfiles(context), subId, keepIds, selectedId
        )
        stale.forEach { ProfileManager.deleteProfile(context, it) }
        if (stale.isNotEmpty()) {
            StunLogger.i(TAG, "Pruned ${stale.size} stale nodes from subscription [$subId]")
        }
        return stale.size
    }

    /**
     * 陈旧节点判定（纯函数，便于 JVM 测）：来源为 [subId]、不在本次 payload 的 [keepIds] 里、
     * 且非收藏、非当前选中 [selectedId] 的节点应被移除。
     *
     * ⚠️ 空 [subId] 一律返回空列表：空串是"手动节点"的标记，
     * `subId == ""` 会把全部手动节点判成"这条订阅的陈旧节点"而清掉。
     */
    internal fun staleSubscriptionNodes(
        profiles: List<Profile>,
        subId: String,
        keepIds: Set<String>,
        selectedId: String?
    ): List<Profile> {
        if (subId.isBlank()) return emptyList()
        return profiles.filter {
            it.subId == subId && it.id !in keepIds && !it.favorite && it.id != selectedId
        }
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

    /**
     * 把本次响应头解析出的元信息写回订阅**行**（名称/首页/更新间隔）。
     *
     * 按 [SubEntry.subId] 精确定位那一行 —— 这正是本重构的关键收益之一：改过 URL 的订阅
     * **仍然收得到元信息更新**。旧实现按 URL 找行，所以"改了域名 → 第一次同步"会另起一条新订阅，
     * 旧行的用量与计时就此变成孤儿。
     *
     * 只更新这三列（链接与 PIN 交由 [saveSubscriptions] / [ensureSubscription] 维护），
     * 因此不会把用量、计时、提醒标记这些列连带重写一遍。
     *
     * 行不存在时**什么都不做**：`ensureSubscription` 在同步开始前已保证行存在，
     * 走到这里还不存在只可能是"同步途中用户把这条订阅删了" —— 那时把行补回去才是错的
     * （删除会"删不掉"）。
     */
    private fun persistSubscriptionMeta(
        context: Context,
        sub: SubEntry,
        name: String,
        homePage: String,
        updateIntervalHours: Int
    ) {
        val d = dao(context)
        val existing = d.getById(sub.subId) ?: return
        val merged = mergeHeaderMeta(existing.toEntry(), name, homePage, updateIntervalHours)
        d.updateHeaderMeta(sub.subId, merged.name, merged.homePage, merged.updateIntervalHours)
        // 名称可能变了 → 给内存缓存打一个补丁。缓存没热时留空即可（[syncAllSubscriptions] 收尾
        // 会整体重建）——**绝不能**在这里把整个缓存重建成"只有这一条"，那会让其余订阅的徽标全丢。
        subNameCache?.let { cache ->
            subNameCache = cache.toMutableMap().apply { this[sub.subId] = merged.name.trim() }
        }
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
    // 按**订阅行**（`subscriptions.usageJson`）各存一份（含日/月基线与趋势），
    // 多订阅不再互相顶掉快照，改订阅 URL 也不会丢掉历史。
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
     * [subId] 是**新格式的身份键**（老备份里没有，只有 [url]）。[url] 仍然带着，
     * 一是给旧备份做降级认领（见 [importUsageSnapshot]），二是排障时人眼可读。
     *
     * 提醒去重标记（overquota/expiring）、last-sync 时间、同步次数这些**没有**进来：
     * 它们是"这台设备已经提示过了"的纯设备态，跨设备恢复只会导致新设备漏提醒。
     */
    data class UsageSnapshot(
        val subId: String = "",
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

    // 旧存储的 key：只被 [migrateLegacyPrefsIfNeeded] 读，不再写（见文件头部的说明）。
    /** url → `UsageRecord` JSON → 现为 `subscriptions.usageJson`。 */
    private const val KEY_USAGE_BY_URL = "subscription_usage_by_url"
    /** `boolean`，键名带 `:$url` 后缀 → 现为 `subscriptions.notifiedOverquota`。 */
    private const val KEY_NOTIFIED_OVERQUOTA = "subscription_notified_overquota"
    /** `boolean`，键名带 `:$url` 后缀 → 现为 `subscriptions.notifiedExpiring`。 */
    private const val KEY_NOTIFIED_EXPIRING = "subscription_notified_expiring"

    private const val USAGE_HISTORY_MAX = 60
    private const val OVER_QUOTA_RATIO = 0.9
    private const val EXPIRING_WITHIN_MS = 7L * 24 * 60 * 60 * 1000

    private const val USAGE_CHANNEL_ID = "stun_usage_alerts"
    private const val USAGE_NOTIFY_OVERQUOTA_ID = 9001
    private const val USAGE_NOTIFY_EXPIRING_ID = 9002

    /**
     * subId → 该订阅的完整流量视图（含今日/本月新增与趋势），按 `updatedAt` 升序 ——
     * 末位即"最近一次同步拿到快照的那条订阅"。仅同步拿到 userinfo 头时更新。
     *
     * 直接推 [UsageView]（而不是裸快照）是为了让订阅面板一次取全：它的渲染跑在观察者
     * （**主线程**）里，若还要再查一次库补 day/month delta，那就是主线程查库。
     */
    val usageLiveData = MutableLiveData<Map<String, UsageView>>(emptyMap())

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

    /** 解析某行 `usageJson` 列（空串 / 脏 JSON 一律当"无记录"，绝不因为一条坏数据让整个面板挂掉）。 */
    private fun parseUsageRecord(json: String?): UsageRecord? {
        if (json.isNullOrBlank()) return null
        return runCatching { gson.fromJson(json, UsageRecord::class.java) }.getOrNull()
    }

    /** 读某行的用量记录。 */
    private fun readUsageRecord(context: Context, subId: String): UsageRecord? {
        if (subId.isBlank()) return null
        return parseUsageRecord(dao(context).getById(subId)?.usageJson)
    }

    /** 把用量记录写回某行（null = 清空该列）。 */
    private fun writeUsageRecord(context: Context, subId: String, record: UsageRecord?) {
        if (subId.isBlank()) return
        dao(context).updateUsageJson(subId, record?.let { gson.toJson(it) }.orEmpty())
    }

    /** [UsageRecord] → [UsageView]（快照 + 今日/本月新增 + 趋势点）。 */
    private fun UsageRecord.toView(): UsageView {
        val usage = SubscriptionUsage(upload, download, total, expire, updatedAt)
        return UsageView(usage, usage.used - dayBase, usage.used - monthBase, history.map { it.t to it.u })
    }

    /** 全部订阅的流量视图（subId → 视图）。 */
    private fun readAllUsageViews(context: Context): Map<String, UsageView> =
        dao(context).getAll().mapNotNull { row ->
            parseUsageRecord(row.usageJson)?.let { row.subId to it.toView() }
        }.toMap()

    /**
     * 持久化某条订阅的流量快照：刷新其日/月基线、追加趋势，并推 [usageLiveData]。
     * 跨日/跨月把"当日起始已用"重置为当前 used（≈上一周期结束值），之后 delta = used - 基线。
     */
    fun saveSubscriptionUsage(context: Context, subId: String, usage: SubscriptionUsage) {
        if (subId.isBlank()) return
        val prev = readUsageRecord(context, subId)
        val today = dayKey()
        val thisMonth = monthKey()
        val dayBase = if (prev == null || prev.dayDate != today) usage.used else prev.dayBase
        val monthBase = if (prev == null || prev.monthDate != thisMonth) usage.used else prev.monthBase
        val history = (prev?.history ?: emptyList()).toMutableList()
        history.add(UsagePoint(usage.updatedAt, usage.used))
        while (history.size > USAGE_HISTORY_MAX) history.removeAt(0)

        writeUsageRecord(
            context, subId,
            UsageRecord(
                usage.upload, usage.download, usage.total, usage.expire, usage.updatedAt,
                today, dayBase, thisMonth, monthBase, history
            )
        )
        seedUsageLiveData(context)
    }

    /** 某条订阅的完整流量视图（无记录返回 null）。 */
    fun getUsageForSub(context: Context, subId: String): UsageView? =
        readUsageRecord(context, subId)?.toView()

    /** 最近一次同步拿到快照的那条订阅的流量（多订阅时的兜底展示 / 提醒用）。 */
    fun getSubscriptionUsage(context: Context): SubscriptionUsage? =
        readAllUsageViews(context).values.maxByOrNull { it.usage.updatedAt }?.usage

    /** 导出全部订阅的流量快照供云备份（无记录返回空列表）。 */
    fun exportUsageSnapshot(context: Context): List<UsageSnapshot> =
        dao(context).getAll().mapNotNull { row ->
            parseUsageRecord(row.usageJson)?.toSnapshot(row.subId, row.url)
        }

    /**
     * 纯函数：把云备份里的流量快照并进本地记录表（按 subId 取并集，不删本地）。
     *
     * 抽成不碰 Context / 库的纯函数，是为了能在 core 的**纯 JVM 单测**里直接验。
     * 这段的坑全在"什么时候该重置基线"上，靠手点界面根本试不出来。
     *
     * - 同 subId：[UsageSnapshot.updatedAt] 新者胜，本地更新过的不被旧备份冲回去
     * - 基线：只在 dayDate / monthDate 与「今天 / 本月」一致时才沿用备份里的基准值，
     *   否则重置为当前 used —— 等价于「本周期从现在开始算」
     * - 趋势点：只保留最近 [USAGE_HISTORY_MAX] 个
     *
     * ⚠️ `subId` 为空的快照**直接丢弃**：它跟任何一行都对不上。旧备份（只有 url）要先经
     * [importUsageSnapshot] 按 URL 认领出 subId 才进得来。
     */
    internal fun mergeUsageSnapshots(
        existing: Map<String, UsageSnapshot>,
        incoming: List<UsageSnapshot>,
        today: String,
        thisMonth: String
    ): LinkedHashMap<String, UsageSnapshot> {
        val merged = LinkedHashMap(existing)
        for (s in incoming) {
            if (s.subId.isBlank()) continue
            val prev = merged[s.subId]
            if (prev != null && prev.updatedAt > s.updatedAt) continue // 本地更新，保留本地
            val used = s.upload + s.download
            merged[s.subId] = s.copy(
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
     *
     * 订阅表为空时**直接不动手**：那多半是订阅分区没恢复成功，不能顺手把本地流量记录也抹了
     * （旧实现同此口径，只是那时判的是"prefs 里的订阅清单为空"）。
     */
    fun importUsageSnapshot(context: Context, snapshots: List<UsageSnapshot>) {
        if (snapshots.isEmpty()) return
        val d = dao(context)
        if (d.count() == 0) return
        // 旧备份只有 url → 先按 URL 认领本地行的 subId；认领不上的丢掉：
        // 它没有任何一行可以落，硬塞只会造出永远不会被显示的孤儿记录。
        val resolved = snapshots.mapNotNull { s ->
            if (s.subId.isNotBlank()) s else d.getByUrl(s.url)?.let { s.copy(subId = it.subId) }
        }
        if (resolved.isEmpty()) return

        val existing = d.getAll().mapNotNull { row ->
            parseUsageRecord(row.usageJson)?.toSnapshot(row.subId, row.url)
        }.associateBy { it.subId }
        val merged = mergeUsageSnapshots(existing, resolved, dayKey(), monthKey())
        merged.forEach { (subId, s) ->
            if (d.getById(subId) != null) writeUsageRecord(context, subId, s.toRecord())
        }
        seedUsageLiveData(context)
    }

    /** [UsageRecord] → [UsageSnapshot]（带 subId 与 url：前者是身份，后者留给旧版本认领与排障）。 */
    private fun UsageRecord.toSnapshot(subId: String, url: String) = UsageSnapshot(
        subId = subId, url = url,
        upload = upload, download = download, total = total, expire = expire, updatedAt = updatedAt,
        dayDate = dayDate, dayBase = dayBase, monthDate = monthDate, monthBase = monthBase, history = history
    )

    /** [UsageSnapshot] → [UsageRecord]（丢掉落盘的 subId/url，它们由"写在哪一行"承载）。 */
    private fun UsageSnapshot.toRecord() = UsageRecord(
        upload, download, total, expire, updatedAt, dayDate, dayBase, monthDate, monthBase, history
    )

    /**
     * 重建 [usageLiveData]（读全部行的用量记录，按 `updatedAt` 升序推出去）。
     * 进入订阅页时先调它，避免空白闪烁。
     */
    fun seedUsageLiveData(context: Context) {
        val sorted = LinkedHashMap<String, UsageView>()
        readAllUsageViews(context).entries.sortedBy { it.value.usage.updatedAt }
            .forEach { (subId, view) -> sorted[subId] = view }
        usageLiveData.postValue(sorted)
    }

    /**
     * 超额 / 即将到期提醒。按状态跳变去重（标记是订阅行上的两列），回落到阈值内后重置以便再次触发。
     * 仅在同步拿到有效快照时调用（网络线程）。
     */
    fun checkUsageAlerts(context: Context, subId: String, usage: SubscriptionUsage) {
        if (subId.isBlank()) return
        val d = dao(context)
        val row = d.getById(subId) ?: return
        val now = System.currentTimeMillis()
        var overQuotaNotified = row.notifiedOverquota
        var expiringNotified = row.notifiedExpiring

        val overQuota = !usage.unlimited && usage.ratio >= OVER_QUOTA_RATIO
        if (overQuota && !overQuotaNotified) {
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
            overQuotaNotified = true
        } else if (!overQuota && overQuotaNotified) {
            overQuotaNotified = false
        }

        val expiring = usage.hasExpire && (usage.expire * 1000L - now) in 1..EXPIRING_WITHIN_MS
        if (expiring && !expiringNotified) {
            val days = ((usage.expire * 1000L - now) / 86400000L).toInt()
            notifyUsage(
                context, USAGE_NOTIFY_EXPIRING_ID,
                context.getString(R.string.subscription_usage_expiring_title),
                context.getString(R.string.subscription_usage_expiring_body, days, AppUtils.formatBytes(usage.total))
            )
            expiringNotified = true
        } else if (!expiring && expiringNotified) {
            expiringNotified = false
        }

        // 只在标记真的变了才落库（旧实现对每个分支持续写 prefs，白掉一次 I/O）。
        if (overQuotaNotified != row.notifiedOverquota || expiringNotified != row.notifiedExpiring) {
            d.updateNotifiedFlags(subId, overQuotaNotified, expiringNotified)
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
