package app.fjj.stun.backup

import android.content.Context
import app.fjj.stun.repo.Profile
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.util.WebDavClient
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * WebDAV 云备份：每次备份写入 WebDAV 基址下按 UTC 时间戳命名的 Stun 目录，
 * 目录内为 PIN 加密文件（ShareCryptoUtils，PBKDF2+GCM）：
 *   <base>/Stun/<yyyyMMdd-HHmmss>/profiles.json.enc   — 全部节点（必需载荷）
 *   <base>/Stun/<yyyyMMdd-HHmmss>/<分区文件名>          — 可插拔分区（见 [BackupSection]）
 * 其中 settings 分区沿用历史文件名 `settings.json.enc`（双向兼容），
 * 其余分区用 `section_<id>.json.enc`。滚动保留最近 [MAX_BACKUPS] 份（上传成功后清理更旧的）。
 *
 * **可插拔**：本类不再认识任何具体设置项，只遍历 [BackupSections.all]。
 * 新增一类需要云备份的设置 = 新增一个 [BackupSection] 实现并登记，**本文件不用改**。
 *
 * 上传前客户端会自动 MKCOL 缺失目录（坚果云/Nextcloud/自建通用）。
 * 开发者与网盘服务商均无法读取内容。恢复必须由用户从备份列表中选择（二次确认，
 * 因为节点按 id 合并 + 设置直接覆盖）。
 *
 * 与 Android Keystore 的关系：Keystore 主钥不可迁移，数据库的 Keystore
 * 加密封文不能跨设备恢复；因此备份内容是「解密后以备份 PIN 再加密」的
 * 可迁移格式，恢复时以 PIN 解密后按 id 合并进本地数据库 / 写回设置。
 * 设备态数据（选中节点、时间戳、备份 PIN 本身）存放在独立的
 * `stun_device_state` 库，从结构上就不会进入备份。
 */
object WebDavBackupManager {

    const val BACKUP_DIR = "Stun"
    const val PROFILES_FILE_NAME = "profiles.json.enc"

    /** settings 分区的历史文件名；[SettingsBackupSection] 复用它，保证旧备份可恢复。 */
    const val SETTINGS_FILE_NAME = "settings.json.enc"

    /**
     * 同步元数据：每个分区「内容修改时间」的表（`{分区 id: epochMillis}`），加密后随快照一起上传。
     *
     * **刻意单独一个文件**，而不是塞进任何一个载荷的信封里 —— 理由和 subscription_usage 独立成文件
     * 完全一样：旧版本读新备份时只会**忽略未知文件**，而信封一旦从数组变对象，旧版本会整块
     * 反序列化失败，把那个分区的数据一起赔进去。
     *
     * ⚠️ 旧备份没有这个文件。那种目录**不参与"谁更新"的判断**，见 [sync]。
     */
    const val SYNC_META_FILE_NAME = "sync_meta.json.enc"

    /** 节点（profiles）在同步元数据里的 id。它不是 [BackupSection]，但同样参与同步。 */
    const val PROFILES_SYNC_ID = "profiles"

    const val MAX_BACKUPS = 5

    /** 备份目录名：UTC 时间戳，字典序即时间序。 */
    private val DIR_NAME_REGEX = Regex("\\d{8}-\\d{6}")

    /**
     * 备份/恢复失败原因码。UI 按码取本地化文案 —— 直接上屏 `e.message` 会把
     * "wrong pin or no backup file" 这种英文内部串丢给用户，而且这两件事
     * 用户能做的补救完全不同（一个是记错 PIN，一个是备份没了）。
     */
    object ErrorCode {
        /** 配置不完整（地址/账号/密码/PIN 缺项）。 */
        const val CONFIG_INCOMPLETE = 1

        /** 备份目录名不合法（防路径穿越）。 */
        const val INVALID_DIR = 2

        /** 备份里没有必需载荷，或整个目录已不存在。 */
        const val PAYLOAD_MISSING = 3

        /** 载荷在，但 PIN 解不开（PIN 记错或文件损坏）。 */
        const val PIN_MISMATCH = 4
    }

    class BackupException(message: String, val code: Int = -1) : Exception(message)

    data class Config(val url: String, val user: String, val pass: String, val pin: String) {
        val isConfigured: Boolean get() = url.isNotBlank() && user.isNotBlank() && pass.isNotBlank() && pin.isNotBlank()
    }

    /** [backup] 的结果：节点数 + 本次**真的上传了**的分区 id（订阅为空时不含该分区）。 */
    data class BackupResult(val profiles: Int, val sections: List<String>)

    data class RestoreResult(
        val profiles: Int,
        /** settings 分区是否恢复成功（保留原字段，兼容既有调用方）。 */
        val settings: Boolean,
        /** 本次成功恢复的分区 id（含 settings）。 */
        val sections: List<String> = emptyList(),
    )

    /**
     * 把分区 id 拼成一段可展示文案（本地化名 + 本地化分隔符）。
     *
     * 不给 UI 层留"按 id 自己翻译"的活：漏一个新增分区就会在弹窗里露出
     * `subscription_usage` 这种内部串。未登记的 id 直接丢掉。
     */
    fun sectionSummary(context: Context, ids: List<String>): String {
        val joiner = context.getString(app.fjj.stun.core.R.string.webdav_section_joiner)
        return ids.mapNotNull { id ->
            BackupSections.byId(id)?.labelRes?.takeIf { it != 0 }?.let { context.getString(it) }
        }.joinToString(joiner)
    }

    /** 备份：写入新的时间戳目录（节点 + 各注册分区 + 同步元数据），成功后裁剪到最近 [MAX_BACKUPS] 份。 */
    suspend fun backup(context: Context, config: Config): BackupResult = withContext(Dispatchers.IO) {
        if (!config.isConfigured) throw BackupException("config incomplete", ErrorCode.CONFIG_INCOMPLETE)
        val base = WebDavClient.normalizeBaseUrl(config.url)
        val parts = exportParts(context, emptyMap())
        val mtimes = anchorMtimes(parts)
        val result = pushSnapshot(base, config, parts, mtimes)
        persistStamps(context, parts, mtimes)
        result
    }

    /** [sync] 的结果。 */
    data class SyncResult(
        /** 本次从云端拉回并应用的分区 id（节点也会以 [PROFILES_SYNC_ID] 出现在这里）。 */
        val pulled: List<String>,
        /** 本次是否上传了新快照。 */
        val pushed: Boolean,
        /** 上传快照包含的节点数；未上传为 0。 */
        val profiles: Int,
        /** 拉取之前是否推了一份本机兜底快照。 */
        val bootstrapped: Boolean,
    )

    /**
     * 按 [mode] 跑一次同步：
     * - **仅上传**：等价于 [backup]（引入本功能前唯一存在的行为，因此默认档零改动）；
     * - **仅下载**：只把云端较新的分区拉回本机；
     * - **双向**：先拉后推，把合并结果写成一份新快照。
     *
     * ## 「按内容时间戳决胜负」是怎么判的
     * 每个分区在设备态库里有 `(mtime, hash)` 指纹（见 [SettingsManager.SyncStamp]）：
     * - `hash` 是**本机这份内容**的指纹，与上次记录比对来**推断**"本机被改过" ——
     *   刻意不在 80+ 个设置写入点埋钩子，漏一个就是静默不同步；
     * - `mtime` 是内容修改时间。本机改动时抬到 `max(now, 远端 + 1)`，抬到"比见过的远端大 1"
     *   是**防两台设备时钟偏差**：否则慢钟那台改完依然"比云端旧"，永远推不上去。
     *
     * ## 远端时间戳从哪来
     * 各备份目录里的 [SYNC_META_FILE_NAME]，**同一分区取所有保留目录里的最大值**（见
     * [collectRemoteStamps]），不是只看最新目录。
     *
     * ## 首次进入拉取模式先留后路
     * 用户明确要求：第一次跑「仅下载 / 双向」时，先把本机现状推一份再拉。否则云端那份若本身不对，
     * 本机就只剩被覆盖后的样子、没有可退的副本。
     */
    suspend fun sync(context: Context, config: Config, mode: WebDavSyncMode): SyncResult = withContext(Dispatchers.IO) {
        if (!config.isConfigured) throw BackupException("config incomplete", ErrorCode.CONFIG_INCOMPLETE)
        val base = WebDavClient.normalizeBaseUrl(config.url)
        val dirs = listBackups(config)
        val remote = collectRemoteStamps(base, dirs, config)
        val parts = exportParts(context, remote)

        // 兜底推送。⚠️ 这一步**不写本机指纹**，meta 用的是本机当前的（多半还是 0 的）时间戳：
        // 若在这里把 mtime 锚到 now，下面判"谁更新"时云端会因为"比本机的 now 旧"而**永远拉不下来**。
        var bootstrapped = false
        if (mode.pulls && dirs.isNotEmpty() && !SettingsManager.isWebDavSyncBootstrapped(context)) {
            StunLogger.i("WebDAV", "Sync bootstrap: pushing a local snapshot before the first pull")
            pushSnapshot(base, config, parts, parts.associate { it.id to it.mtime })
            SettingsManager.setWebDavSyncBootstrapped(context, true)
            bootstrapped = true
        }

        // ── 拉取：远端内容修改时间 > 本机 ⇒ 远端赢 ──
        val pulled = mutableListOf<String>()
        parts.forEach { part ->
            val ref = remote[part.id] ?: return@forEach
            val localBefore = part.mtime
            if (!shouldPull(ref.mtime, localBefore)) return@forEach
            val json = (fetchDecrypted(base, dirPath(ref.dir, part.fileName), config) as? Fetched.Ok)?.json
            if (json == null) {
                StunLogger.d("WebDAV", "Sync: ${part.id} is newer on cloud but unreadable in ${ref.dir}, skipped")
                return@forEach
            }
            try {
                applyJson(context, part, json)
            } catch (e: Exception) {
                StunLogger.w("WebDAV", "Sync: apply ${part.id} failed: ${e.message}")
                return@forEach
            }
            val hash = fingerprint(json)
            SettingsManager.saveWebDavSyncStamp(context, part.id, ref.mtime, hash)
            part.mtime = ref.mtime
            part.hash = hash
            pulled += part.id
            StunLogger.i("WebDAV", "Sync: pulled ${part.id} (cloud ${ref.mtime} > local $localBefore)")
        }

        // ── 推送 ──
        var pushed = false
        var profiles = 0
        if (mode.pushes) {
            // 仅上传必须"每次都推" —— 引入本功能之前就是如此，默认档不能有行为变化；
            // 双向则只在真有变化时推，免得 5 个保留位全被无变化的快照吃掉。
            val needPush = mode == WebDavSyncMode.UPLOAD || bootstrapped || dirs.isEmpty() ||
                pulled.isNotEmpty() || parts.any { it.changed || it.neverObserved }
            if (needPush) {
                val mtimes = anchorMtimes(parts)
                profiles = pushSnapshot(base, config, parts, mtimes).profiles
                persistStamps(context, parts, mtimes)
                pushed = true
            }
        }

        SettingsManager.saveWebDavLastSyncTime(context, System.currentTimeMillis())
        StunLogger.i(
            "WebDAV",
            "Sync(${mode.id}) OK: pulled=[${pulled.joinToString()}] pushed=$pushed" +
                (if (bootstrapped) " (bootstrapped)" else "")
        )
        SyncResult(pulled, pushed, profiles, bootstrapped)
    }

    /** 远端某分区的内容修改时间，以及它出现在哪个备份目录（拉取时要去那个目录取文件）。 */
    private class RemoteRef(val mtime: Long, val dir: String)

    /**
     * 参与同步的一份内容。节点（[PROFILES_SYNC_ID]）也走同一条路，只是它的 [section] 为 null、
     * [fileName] 是 [PROFILES_FILE_NAME]、应用时要走"按 id 合并"而不是直接覆盖。
     */
    private class SyncPart(
        val id: String,
        val fileName: String,
        /** 本机明文 JSON；null = 本机没有这份内容（如订阅为空），既不推也不参与判断。 */
        val json: String?,
        val section: BackupSection?,
        /** 节点数，只用于回执文案。 */
        val itemCount: Int,
        /** 本机内容指纹；null 表示本机没有这份内容。 */
        var hash: String?,
        /** 是否从未观测过本机内容（指纹库为空）。 */
        val neverObserved: Boolean,
        /** 与上次记录相比，本机内容是否被改过。 */
        val changed: Boolean,
        /** 判断"该拉该推"用的内容修改时间；0 = 从未观测过，**不挡远端拉取**。 */
        var mtime: Long,
    )

    /**
     * 采集本机各分区的内容与同步指纹。
     *
     * ⚠️ 本函数**有写副作用**：把本次观测到的 `(mtime, hash)` 记回设备态库。放在这里是因为
     * "本机是否被改过"只能在同步时判定（不在 80+ 个设置写入点埋钩子 —— 漏一个就是静默不同步），
     * 观测一次就该落一次盘，否则下次又变回"从未观测"。
     */
    private fun exportParts(context: Context, remote: Map<String, RemoteRef>): List<SyncPart> {
        val now = System.currentTimeMillis()
        val parts = ArrayList<SyncPart>(BackupSections.all.size + 1)

        fun define(id: String, fileName: String, json: String?, section: BackupSection?, itemCount: Int) {
            val stored = SettingsManager.getWebDavSyncStamp(context, id)
            val hash = json?.let { fingerprint(it) }
            val neverObserved = stored.hash.isBlank()
            val changed = hash != null && !neverObserved && stored.hash != hash
            val mtime = localMtime(
                storedMtime = stored.mtime,
                storedHash = stored.hash,
                hash = hash,
                remoteMtime = remote[id]?.mtime ?: 0L,
                now = now,
            )
            parts += SyncPart(id, fileName, json, section, itemCount, hash, neverObserved, changed, mtime)
            if (hash != null) SettingsManager.saveWebDavSyncStamp(context, id, mtime, hash)
        }

        val profiles = ProfileManager.getProfiles(context)
        define(PROFILES_SYNC_ID, PROFILES_FILE_NAME, Gson().toJson(profiles), null, profiles.size)
        BackupSections.all.forEach { section ->
            val json = try {
                section.export(context)
            } catch (e: Exception) {
                StunLogger.w("WebDAV", "Section ${section.id} export failed, skipped: ${e.message}")
                null
            }
            define(section.id, section.fileName, json, section, 0)
        }
        return parts
    }

    /**
     * 「本机这份内容该记成什么内容修改时间」—— 同步冲突判定的全部智慧都在这里，因此抽成纯函数
     * （`internal`）以便回归测试，不再埋在 [exportParts] 的闭包里。
     *
     * 分支含义：
     * - `hash == null`：本机根本没有这份内容（如订阅为空），沿用已存记录，不参与判断；
     * - `storedHash` 为空：**首次观测**，返回 0 —— 本机版本未知，不能凭"刚看见"就压住云端，
     *   否则一台新设备装完就永远拉不到任何数据；
     * - 指纹未变：沿用已存时间，本机没动过，不去抢；
     * - 指纹变了：抬到 `max(now, 远端 + 1)`。抬到"比见过的远端大 1"是**防两台设备时钟偏差**：
     *   慢钟那台改完若仍"比云端旧"，就永远推不上去，双设备会各自看到自己的版本。
     */
    internal fun localMtime(
        storedMtime: Long,
        storedHash: String,
        hash: String?,
        remoteMtime: Long,
        now: Long,
    ): Long = when {
        hash == null -> storedMtime
        storedHash.isBlank() -> 0L
        storedHash != hash -> maxOf(now, remoteMtime + 1L)
        else -> storedMtime
    }

    /** 拉取判定：远端内容修改时间**严格大于**本机才拉（相等视为同一版本，避免无谓往返）。 */
    internal fun shouldPull(remoteMtime: Long, localMtime: Long): Boolean = remoteMtime > localMtime

    /**
     * 落盘 / 写进 meta 用的最终时间戳：从未观测过的分区（[localMtime] 给了 0）锚到**现在**。
     *
     * 为什么和 [SyncPart.mtime] 分成两层：判"该拉该推"时 0 表示"本机版本未知"，不能让本机赢；
     * 但一旦要把本机内容写进 meta，就必须给一个**能与其他设备比较**的真实时间 ——
     * 写 0 会让别的设备永远拉不到这份数据。
     */
    internal fun anchorMtime(mtime: Long, now: Long): Long = mtime.takeIf { it > 0L } ?: now

    private fun anchorMtimes(parts: List<SyncPart>): Map<String, Long> {
        val now = System.currentTimeMillis()
        return parts.filter { it.json != null }.associate { it.id to anchorMtime(it.mtime, now) }
    }

    private fun persistStamps(context: Context, parts: List<SyncPart>, mtimes: Map<String, Long>) {
        parts.forEach { part ->
            val mtime = mtimes[part.id] ?: return@forEach
            val hash = part.hash ?: return@forEach
            SettingsManager.saveWebDavSyncStamp(context, part.id, mtime, hash)
        }
    }

    /**
     * 把一个快照写进新的时间戳目录：节点 + 各分区 + 同步元数据，最后裁剪旧目录。
     *
     * [backup] 只是"算指纹 → 调它 → 落指纹"的一层壳；[sync] 的兜底推送直接调它、不落指纹。
     */
    private fun pushSnapshot(base: String, config: Config, parts: List<SyncPart>, mtimes: Map<String, Long>): BackupResult {
        val dir = newBackupDirName()
        StunLogger.i("WebDAV", "Backup start → $base/$BACKUP_DIR/$dir")

        val profilesPart = parts.first { it.id == PROFILES_SYNC_ID }
        val profilesJson = profilesPart.json ?: "[]"
        WebDavClient.put(base, dirPath(dir, PROFILES_FILE_NAME), config.user, config.pass, encryptToBytes(profilesJson, config.pin))

        // 可插拔分区：本类不认识任何具体设置项，只遍历注册表。
        val uploaded = mutableListOf<String>()
        parts.forEach { part ->
            val section = part.section ?: return@forEach
            // 无内容（如订阅为空）就不上传，也不动云端已有文件
            val json = part.json ?: return@forEach
            WebDavClient.put(base, dirPath(dir, section.fileName), config.user, config.pass, encryptToBytes(json, config.pin))
            uploaded += section.id
        }

        // 元数据只记录**本次真的上传了**的分区，不给不存在的内容编时间戳
        val meta = mtimes.filterKeys { it == PROFILES_SYNC_ID || it in uploaded }
        WebDavClient.put(base, dirPath(dir, SYNC_META_FILE_NAME), config.user, config.pass, encryptToBytes(Gson().toJson(meta), config.pin))

        val pruned = pruneOldBackups(base, config)
        StunLogger.i(
            "WebDAV",
            "Backup OK: ${profilesPart.itemCount} nodes + ${uploaded.size} sections (${uploaded.joinToString()}) → $dir" +
                (if (pruned > 0) ", pruned $pruned old backup(s)" else "")
        )
        return BackupResult(profilesPart.itemCount, uploaded)
    }

    /**
     * 汇总各备份目录的同步元数据，**同一分区取最大值**。
     *
     * 为什么不是"只看最新目录"：分区是按需上传的（订阅为空时不上传），而且不同设备的目录会交替
     * 插到最前面 —— 只看最新目录，会让"仅上传"设备插进来的那份旧数据把另一台设备较新的版本挡住。
     *
     * ⚠️ **没有 meta 的目录（本功能之前产生的备份）直接跳过**，而不是拿目录名当初次上传时间兜底：
     * 老用户升级后本地设置是"真身"、云端那份只是历史副本，拿上传时间冒充内容时间会让云端**反盖本地**。
     */
    private suspend fun collectRemoteStamps(base: String, dirs: List<String>, config: Config): Map<String, RemoteRef> {
        val out = HashMap<String, RemoteRef>()
        dirs.forEach { dir ->
            val meta = try {
                when (val fetched = fetchDecrypted(base, dirPath(dir, SYNC_META_FILE_NAME), config)) {
                    is Fetched.Ok -> parseMeta(fetched.json)
                    Fetched.Missing -> null
                    Fetched.Undecryptable -> {
                        StunLogger.w("WebDAV", "$dir/$SYNC_META_FILE_NAME cannot be decrypted, dir ignored")
                        null
                    }
                }
            } catch (e: Exception) {
                // 单个目录取不动（网络抖动 / 被别的工具删了）不该让整次同步失败
                StunLogger.w("WebDAV", "Reading meta of $dir failed: ${e.message}")
                null
            }
            meta?.forEach { (id, mtime) ->
                val current = out[id]
                if (current == null || mtime > current.mtime) out[id] = RemoteRef(mtime, dir)
            }
        }
        return out
    }

    /** 解析 [SYNC_META_FILE_NAME]；格式不合法返回 null（该目录整份作废，不当成空表）。 */
    internal fun parseMeta(json: String): Map<String, Long>? = try {
        val type = object : TypeToken<Map<String, Long>>() {}.type
        Gson().fromJson<Map<String, Long>>(json, type)
    } catch (e: Exception) {
        StunLogger.w("WebDAV", "Malformed $SYNC_META_FILE_NAME: ${e.message}")
        null
    }

    /** 把一份云端明文 JSON 应用到本机：节点走"按 id 合并"，分区走各自的 import。 */
    private fun applyJson(context: Context, part: SyncPart, json: String) {
        val section = part.section
        if (section == null) applyProfilesJson(context, json) else section.import(context, json)
    }

    /** 节点载荷的应用：按 id 合并（同 id 保本机设备态字段，新节点清掉本机没有的网卡绑定）。 */
    private fun applyProfilesJson(context: Context, json: String): Int {
        val profiles = parseProfiles(json)
        // 网卡名只在枚举得出来的时候才用来做判断（见 dropForeignBindInterface）
        val localInterfaces = localInterfaceNames()
        var merged = 0
        profiles.forEach { p ->
            val existing = ProfileManager.getProfileById(context, p.id)
            if (existing != null) {
                // 同 id 合并：留本机的"运行期/设备态"字段，其余按备份覆盖。
                // 恢复走的是 Room 的整行 REPLACE，不管的话备份里那一刻的累计流量、
                // 排序位次、上次连接时间会把本机这几天的新值直接冲回去。
                p.keepLocalDeviceState(existing)
                ProfileManager.updateProfile(context, p)
            } else {
                ProfileManager.addProfile(
                    context,
                    p.copy(id = p.id.ifBlank { java.util.UUID.randomUUID().toString() })
                        .dropForeignBindInterface(localInterfaces)
                )
            }
            merged++
        }
        return merged
    }

    /**
     * 内容指纹：先把 JSON **规范化**（对象键递归排序）再取 SHA-256。
     *
     * 排序是必须的：设置快照是从 `SharedPreferences.all` 枚举出来的，键序不该成为"内容变了"的依据 ——
     * 不排序的话一次无关的键增删就会让指纹翻转、被判成"本机改过"，从而无谓地推一份新快照，
     * 更糟的是把本机时间戳抬到最高、永远压住别的设备。
     *
     * `internal` 而非 `private`：同步的"谁更新"判定完全建立在这个指纹上，必须有回归测试钉住
     * 「键序无关 / 内容一变就翻转」这两条（见 `WebDavSyncDecisionTest`）。
     */
    internal fun fingerprint(json: String): String {
        val canonical = try {
            Gson().toJson(sortKeys(JsonParser.parseString(json)))
        } catch (_: Exception) {
            json // 不是合法 JSON 就按原样算：宁可指纹不稳，也不要在这里抛
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** 递归排序对象键（数组顺序保留 —— 数组本身是有序语义）。 */
    internal fun sortKeys(element: JsonElement): JsonElement = when {
        element.isJsonObject -> JsonObject().apply {
            element.asJsonObject.entrySet().sortedBy { it.key }.forEach { (key, value) -> add(key, sortKeys(value)) }
        }
        element.isJsonArray -> JsonArray().apply { element.asJsonArray.forEach { add(sortKeys(it)) } }
        else -> element
    }

    /** 列出服务器上的备份目录名（由新到旧）。 */
    suspend fun listBackups(config: Config): List<String> = withContext(Dispatchers.IO) {
        if (!config.isConfigured) return@withContext emptyList()
        val result = runCatching {
            WebDavClient.listChildren(
                WebDavClient.normalizeBaseUrl(config.url), BACKUP_DIR, config.user, config.pass
            )
        }.getOrDefault(emptyList())
            .filter { it.endsWith("/") }
            .map { it.trim('/') }
            .filter { DIR_NAME_REGEX.matches(it) }
            .sortedDescending()
        StunLogger.d("WebDAV", "listBackups -> ${result.size} backup(s): ${result.joinToString(", ")}")
        result
    }

    /** 从指定备份目录恢复：PIN 解密 → 节点按 id 合并，设置写回。 */
    suspend fun restore(context: Context, config: Config, dir: String): RestoreResult = withContext(Dispatchers.IO) {
        if (!config.isConfigured) throw BackupException("config incomplete", ErrorCode.CONFIG_INCOMPLETE)
        if (!DIR_NAME_REGEX.matches(dir)) throw BackupException("invalid backup id", ErrorCode.INVALID_DIR)
        val base = WebDavClient.normalizeBaseUrl(config.url)
        StunLogger.i("WebDAV", "Restore start ← $base/$BACKUP_DIR/$dir")

        // 节点是必需载荷，但「文件不在」和「PIN 解不开」是两件事：前者可能只是这个目录
        // 上次上传到一半，设置分区照样值得恢复；后者说明 PIN 记错了 —— 继续跑下去每个分区
        // 都会解不开，不如立刻带着明确原因失败（UI 才能提示"PIN 不正确"而不是"备份失败"）。
        val required = fetchDecrypted(base, dirPath(dir, PROFILES_FILE_NAME), config)
        val profiles = when (required) {
            is Fetched.Ok -> parseProfiles(required.json)
            Fetched.Undecryptable -> throw BackupException(
                "cannot decrypt $PROFILES_FILE_NAME (wrong pin?)", ErrorCode.PIN_MISMATCH
            )
            Fetched.Missing -> {
                StunLogger.w("WebDAV", "$dir/$PROFILES_FILE_NAME missing, falling back to section-only restore")
                emptyList()
            }
        }

        // 节点合并走与同步链路**同一份**实现（见 applyProfilesJson）：两处各写一遍迟早漂移
        val merged = applyProfilesJson(context, Gson().toJson(profiles))

        // 各分区可选：单个分区损坏/缺失只跳过它，不影响已完成的节点合并
        val restoredIds = mutableListOf<String>()
        var settingsRestored = false
        BackupSections.all.forEach { section ->
            try {
                val json = (fetchDecrypted(base, dirPath(dir, section.fileName), config) as? Fetched.Ok)?.json
                if (json == null) {
                    StunLogger.d("WebDAV", "$dir/${section.fileName} missing or decrypt failed, skipping section ${section.id}")
                    return@forEach
                }
                section.import(context, json)
                restoredIds += section.id
                if (section.id == SettingsBackupSection.id) settingsRestored = true
            } catch (e: Exception) {
                StunLogger.w("WebDAV", "Section ${section.id} restore failed: ${e.message}")
            }
        }

        // 节点文件不在 + 一个分区都没恢复成 = 这个目录根本没东西可恢复（多半已被清理）。
        // 这时返回 0 会让 UI 报"✓ 已恢复 0 个节点"，比直接失败更让人困惑。
        if (required === Fetched.Missing && restoredIds.isEmpty()) {
            throw BackupException("no readable payload in $dir", ErrorCode.PAYLOAD_MISSING)
        }

        StunLogger.i(
            "WebDAV",
            "Restore OK: $merged nodes, settings=$settingsRestored, sections=${restoredIds.joinToString()}"
        )
        RestoreResult(merged, settingsRestored, restoredIds)
    }

    /** 远端是否已有备份。 */
    suspend fun exists(config: Config): Boolean = listBackups(config).isNotEmpty()

    /** 供 UI 展示：把 UTC 目录名格式化成本机时区的可读时间。 */
    fun formatDirForDisplay(name: String): String = try {
        val utc = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.parse(name) ?: return name
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(utc)
    } catch (_: Exception) {
        name
    }

    private fun newBackupDirName(): String =
        java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())

    private fun dirPath(dir: String, file: String): String = "$BACKUP_DIR/$dir/$file"

    /** 超出保留份数时删除最旧的目录；尽力而为，失败忽略（下次备份再清）。返回删除数量。 */
    private fun pruneOldBackups(base: String, config: Config): Int {
        return try {
            val dirs = WebDavClient.listChildren(base, BACKUP_DIR, config.user, config.pass)
                .filter { it.endsWith("/") }
                .map { it.trim('/') }
                .filter { DIR_NAME_REGEX.matches(it) }
                .sortedDescending()
            val doomed = dirs.drop(MAX_BACKUPS)
            doomed.forEach { old ->
                WebDavClient.delete(base, BACKUP_DIR + "/" + old, config.user, config.pass)
            }
            doomed.size
        } catch (e: Exception) {
            StunLogger.d("WebDAV", "prune skipped: ${e.message}")
            0
        }
    }

    /**
     * 同 id 合并时，把**本机设备态**的字段从本地侧搬回来。
     *
     * 就地改而不是 `copy(...)`：Profile 有 80 多个字段，抄一份出来写等于给自己埋雷 ——
     * 以后谁加一个字段忘了在这里带上，恢复一次就悄悄把它抹成默认值。
     * 只列"要保住的"，其余照备份走，责任边界最清楚。
     */
    private fun Profile.keepLocalDeviceState(local: Profile) {
        totalTx = local.totalTx
        totalRx = local.totalRx
        sortIndex = local.sortIndex
        lastConnectedAt = local.lastConnectedAt
        bindInterface = local.bindInterface
    }

    /**
     * 跨设备恢复时清掉指向"本机不存在网卡"的绑定。
     *
     * `bindInterface` 存的是 wlan0 / rmnet0 这类**本机网卡名**，从别的机型带过来在本机
     * 根本不存在，拨号时按名字 bind 会失败，而失败信息对用户毫无指向性。
     *
     * [available] 为 null = 网卡枚举失败：这时**不清理**。宁可留一个可能失效的绑定
     * （用户能在编辑页看到并改掉），也不能在查不到的时候把有效绑定误删。
     */
    private fun Profile.dropForeignBindInterface(available: Set<String>?): Profile = apply {
        if (available != null && bindInterface.isNotBlank() && bindInterface !in available) {
            StunLogger.w("WebDAV", "Node \"$name\" interface binding '$bindInterface' not present on this device, cleared")
            bindInterface = ""
        }
    }

    /** 本机网卡名集合；枚举失败返回 null（调用方据此放弃任何清理动作）。 */
    private fun localInterfaceNames(): Set<String>? = try {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList()?.mapNotNull { it.name }?.toSet()
    } catch (e: Exception) {
        StunLogger.w("WebDAV", "Failed to enumerate network interfaces, skipping binding validation: ${e.message}")
        null
    }

    /** 备份载荷一律用加码后的 PBKDF2 迭代数（`ShareCryptoUtils.ITERATIONS_BACKUP`）：
     * 这些密文会长期躺在第三方网盘上，而 PIN 是用户自选的短串。
     * 读取侧不用配套改动 —— 迭代数写在信封的 `it` 字段里，解密函数自己认。
     */
    private fun encryptToBytes(json: String, pin: String): ByteArray =
        app.fjj.stun.util.ShareCryptoUtils.encrypt(
            json, pin, app.fjj.stun.util.ShareCryptoUtils.ITERATIONS_BACKUP
        ).toByteArray(Charsets.UTF_8)

    /**
     * 取回并解密一个载荷的结果。
     *
     * 分成三态而不是「成功给字符串、失败给 null」：调用方要能区分**文件不在**和
     * **PIN 解不开** —— 前者可以绕过继续恢复其它分区，后者必须立刻停并以明确原因报错。
     * 合成一个 null 就把这两种情况混死了（旧版正是如此，用户只会看到一句
     * "wrong pin or no backup file"）。
     */
    private sealed interface Fetched {
        data class Ok(val json: String) : Fetched

        /** 文件不存在（404）或服务端回了空 body。 */
        data object Missing : Fetched

        /** 文件在，但 PIN 解不开 / 内容损坏。 */
        data object Undecryptable : Fetched
    }

    private suspend fun fetchDecrypted(
        base: String,
        path: String,
        config: Config
    ): Fetched = try {
        val data = WebDavClient.get(base, path, config.user, config.pass)
        val text = String(data, Charsets.UTF_8)
        // 部分 WebDAV 服务端对不存在的文件回 200 + 空 body，空文本一律当"没有"
        if (text.isBlank()) {
            Fetched.Missing
        } else {
            val json = app.fjj.stun.util.ShareCryptoUtils.decrypt(text, config.pin)
            if (json == null) Fetched.Undecryptable else Fetched.Ok(json)
        }
    } catch (e: WebDavClient.WebDavException) {
        // 只有 404 才是"这个文件不存在"；401/403/5xx 是别的问题，不该伪装成"备份没了"
        if (e.statusCode == 404) Fetched.Missing else throw e
    }

    private fun parseProfiles(json: String): List<Profile> = try {
        val type = object : TypeToken<List<Profile>>() {}.type
        Gson().fromJson<List<Profile>>(json, type).orEmpty()
    } catch (_: Exception) {
        try {
            listOfNotNull(Gson().fromJson(json, Profile::class.java))
        } catch (_: Exception) {
            emptyList()
        }
    }
}
