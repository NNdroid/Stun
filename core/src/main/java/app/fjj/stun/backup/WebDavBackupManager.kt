package app.fjj.stun.backup

import android.content.Context
import app.fjj.stun.repo.Profile
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.util.WebDavClient
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    /** 备份：写入新的时间戳目录（节点 + 各注册分区），成功后裁剪到最近 [MAX_BACKUPS] 份。 */
    suspend fun backup(context: Context, config: Config): BackupResult = withContext(Dispatchers.IO) {
        if (!config.isConfigured) throw BackupException("config incomplete", ErrorCode.CONFIG_INCOMPLETE)
        val base = WebDavClient.normalizeBaseUrl(config.url)
        val dir = newBackupDirName()
        StunLogger.i("WebDAV", "Backup start → $base/$BACKUP_DIR/$dir")

        val profiles = ProfileManager.getProfiles(context)
        val profilesJson = Gson().toJson(profiles)
        WebDavClient.put(base, dirPath(dir, PROFILES_FILE_NAME), config.user, config.pass, encryptToBytes(profilesJson, config.pin))

        // 可插拔分区：本类不认识任何具体设置项，只遍历注册表。
        val uploaded = mutableListOf<String>()
        BackupSections.all.forEach { section ->
            val json = try {
                section.export(context)
            } catch (e: Exception) {
                StunLogger.w("WebDAV", "Section ${section.id} export failed, skipped: ${e.message}")
                null
            }
            // 无内容（如订阅为空）就不上传，也不动云端已有文件
            if (json == null) return@forEach
            WebDavClient.put(base, dirPath(dir, section.fileName), config.user, config.pass, encryptToBytes(json, config.pin))
            uploaded += section.id
        }

        val pruned = pruneOldBackups(base, config)
        StunLogger.i(
            "WebDAV",
            "Backup OK: ${profiles.size} nodes + ${uploaded.size} sections (${uploaded.joinToString()}) → $dir" +
                (if (pruned > 0) ", pruned $pruned old backup(s)" else "")
        )
        BackupResult(profiles.size, uploaded)
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
