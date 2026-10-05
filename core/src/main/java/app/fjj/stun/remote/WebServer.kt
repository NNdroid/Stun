package app.fjj.stun.remote

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import app.fjj.stun.backup.WebDavBackupManager
import app.fjj.stun.backup.WebDavSyncMode
import app.fjj.stun.repo.Profile
import app.fjj.stun.repo.ProfileArgReader
import app.fjj.stun.repo.ProfileFields
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.ProfileSecrets
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.repo.SubscriptionManager
import app.fjj.stun.service.MyVpnService
import app.fjj.stun.service.VpnConfigBuilder
import app.fjj.stun.util.CrashHistoryStore
import app.fjj.stun.util.ShareCryptoUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.ktor.http.*
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebServer: 内嵌轻量级 Ktor 现代 Web 管理控制台服务。
 * 提供节点管理、VPN 控制、分流规则配置、连接跟踪、Token 认证、完整系统设置及实时日志流等全功能 WebUI。
 * 前端 HTML/CSS/JS 静态文件独立存放于 assets/web/ 目录中。
 */
object WebServer {

    private const val TAG = "WebServer"
    const val DEFAULT_PORT = 5858

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val isRunning = AtomicBoolean(false)
    private val gson = Gson()

    // ── 已安装应用清单缓存 ──
    // /api/apps 每次调用都要 pm.getInstalledPackages + 逐包取 label，应用多的机器上是几百毫秒级的开销，
    // 而 WebUI 每次切回「系统设置」页签都会打一次。这里缓存「只与安装状态有关、短时间内不变」的元数据；
    // isSelected 依赖用户的分流配置、保存后必须立刻生效，所以不入缓存，每次请求按 Set 现算（开销可忽略）。
    private data class InstalledAppMeta(
        val packageName: String,
        val appName: String,
        val versionName: String,
        val versionCode: Long,
        val isSystem: Boolean
    )

    @Volatile private var appsMetaCache: List<InstalledAppMeta>? = null
    @Volatile private var appsMetaCachedAtMs: Long = 0L
    private const val APPS_META_TTL_MS = 60_000L

    /** 需要时主动失效（例如前端点「刷新」传 refresh=1）。 */
    private fun invalidateAppsMetaCache() {
        appsMetaCache = null
        appsMetaCachedAtMs = 0L
    }

    private fun loadInstalledAppsMeta(context: Context): List<InstalledAppMeta> {
        appsMetaCache?.let { cached ->
            if (SystemClock.elapsedRealtime() - appsMetaCachedAtMs < APPS_META_TTL_MS) return cached
        }
        val pm = context.packageManager
        val list = pm.getInstalledPackages(0)
            .filter { it.packageName != context.packageName }
            .mapNotNull { pkg ->
                try {
                    val appInfo = pkg.applicationInfo ?: return@mapNotNull null
                    InstalledAppMeta(
                        packageName = pkg.packageName,
                        appName = pm.getApplicationLabel(appInfo).toString(),
                        versionName = pkg.versionName ?: "",
                        versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            pkg.longVersionCode
                        } else {
                            @Suppress("DEPRECATION")
                            pkg.versionCode.toLong()
                        },
                        isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                } catch (_: Exception) {
                    null
                }
            }
            .sortedWith(compareBy({ it.isSystem }, { it.appName.lowercase() }))
        appsMetaCache = list
        appsMetaCachedAtMs = SystemClock.elapsedRealtime()
        return list
    }

    var token: String = ""; private set
    var actualPort: Int = DEFAULT_PORT; private set

    var onVpnControlRequested: (suspend (action: String, profileId: String?) -> Boolean)? = null
    var onProfileSelected: ((profileId: String) -> Unit)? = null
    var onProfileDeleted: ((profileId: String) -> Unit)? = null
    var onProfileAdded: ((profile: Profile) -> Unit)? = null
    var onAuthConfigChanged: ((newUrl: String) -> Unit)? = null

    private fun generateRandomToken(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..8).map { chars.random() }.joinToString("")
    }

    fun getEffectiveToken(context: Context): String {
        return when (SettingsManager.getWebAuthMode(context)) {
            SettingsManager.WEB_AUTH_MODE_DISABLED -> ""
            SettingsManager.WEB_AUTH_MODE_PERMANENT -> SettingsManager.getWebPermanentToken(context)
            SettingsManager.WEB_AUTH_MODE_CUSTOM -> {
                val custom = SettingsManager.getWebCustomToken(context)
                if (custom.isNotBlank()) custom else SettingsManager.getWebPermanentToken(context)
            }
            else -> {
                if (token.isBlank()) token = generateRandomToken()
                token
            }
        }
    }

    fun getEffectiveUrl(context: Context, port: Int = actualPort): String {
        val ip = getLocalIp(context)
        val t = getEffectiveToken(context)
        return if (t.isBlank()) {
            "http://$ip:$port/"
        } else {
            "http://$ip:$port/?token=$t"
        }
    }

    private fun readWebDavConfig(context: Context): WebDavBackupManager.Config {
        return WebDavBackupManager.Config(
            url = SettingsManager.getWebDavUrl(context),
            user = SettingsManager.getWebDavUser(context),
            pass = SettingsManager.getWebDavPass(context),
            pin = SettingsManager.getWebDavPin(context)
        )
    }

    private fun parseProfilesFromJson(rawText: String): List<Profile> {
        val trimmed = rawText.trim().removePrefix("\uFEFF")
        if (trimmed.isEmpty()) return emptyList()

        return try {
            if (trimmed.startsWith("[")) {
                val type = object : TypeToken<List<Profile>>() {}.type
                gson.fromJson<List<Profile>>(trimmed, type) ?: emptyList()
            } else if (trimmed.startsWith("{")) {
                val jsonObj = JSONObject(trimmed)
                when {
                    jsonObj.has("profiles") -> {
                        val type = object : TypeToken<List<Profile>>() {}.type
                        gson.fromJson<List<Profile>>(jsonObj.getJSONArray("profiles").toString(), type) ?: emptyList()
                    }
                    jsonObj.has("nodes") -> {
                        val type = object : TypeToken<List<Profile>>() {}.type
                        gson.fromJson<List<Profile>>(jsonObj.getJSONArray("nodes").toString(), type) ?: emptyList()
                    }
                    jsonObj.has("data") -> {
                        val dataObj = jsonObj.get("data")
                        if (dataObj is org.json.JSONArray) {
                            val type = object : TypeToken<List<Profile>>() {}.type
                            gson.fromJson<List<Profile>>(dataObj.toString(), type) ?: emptyList()
                        } else {
                            val p = gson.fromJson(dataObj.toString(), Profile::class.java)
                            if (p != null) listOf(p) else emptyList()
                        }
                    }
                    else -> {
                        val p = gson.fromJson(trimmed, Profile::class.java)
                        if (p != null && (p.sshAddr.isNotBlank() || p.tunnelType.isNotBlank() || p.name.isNotBlank())) {
                            listOf(p)
                        } else emptyList()
                    }
                }
            } else {
                val clean = trimmed.replace("\r", "").replace("\n", "").replace(" ", "")
                val decoded = String(Base64.decode(clean, Base64.DEFAULT), Charsets.UTF_8).trim().removePrefix("\uFEFF")
                if (decoded.startsWith("[") || decoded.startsWith("{")) {
                    parseProfilesFromJson(decoded)
                } else emptyList()
            }
        } catch (e: Exception) {
            StunLogger.w(TAG, "parseProfilesFromJson failed: ${e.message}")
            emptyList()
        }
    }

    fun start(context: Context, port: Int = DEFAULT_PORT): Int {
        if (isRunning.compareAndSet(false, true)) {
            val appContext = context.applicationContext
            token = getEffectiveToken(appContext)

            actualPort = try {
                ServerSocket(port).use { it.localPort }
            } catch (_: Exception) {
                ServerSocket(0).use { it.localPort }
            }

            server = embeddedServer(CIO, port = actualPort) {
                install(ContentNegotiation) {
                    gson {
                        setPrettyPrinting()
                        // update 响应会把刚保存的 Profile 回吐给前端；不注册脱敏就会把
                        // 明文凭据又送回去（等于 GET 打码白做）。
                        ProfileSecrets.registerRedaction(this)
                    }
                }

                routing {
                    // ── 静态 Web 资源路由 ──
                    get("/") {
                        if (!call.checkToken(appContext)) return@get
                        try {
                            val html = appContext.assets.open("web/index.html").bufferedReader().use { it.readText() }
                            call.respondText(html, ContentType.Text.Html)
                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, "Error loading WebUI: ${e.message}")
                        }
                    }

                    get("/style.css") {
                        try {
                            val css = appContext.assets.open("web/style.css").bufferedReader().use { it.readText() }
                            call.respondText(css, ContentType.Text.CSS)
                        } catch (_: Exception) {
                            call.respond(HttpStatusCode.NotFound)
                        }
                    }

                    get("/app.js") {
                        try {
                            val js = appContext.assets.open("web/app.js").bufferedReader().use { it.readText() }
                            call.respondText(js, ContentType.parse("application/javascript"))
                        } catch (_: Exception) {
                            call.respond(HttpStatusCode.NotFound)
                        }
                    }

                    // ── 状态 API ──
                    get("/api/status") {
                        if (!call.checkToken(appContext)) return@get
                        val selected = ProfileManager.getSelectedProfile(appContext)
                        val filterAppsStr = SettingsManager.getFilterApps(appContext)
                        val filterCount = if (filterAppsStr.isBlank()) 0 else filterAppsStr.split(",").filter { it.isNotBlank() }.size
                        val trafficStats = try { myssh.Myssh.getTrafficStats() } catch (_: Exception) { null }

                        // SSH 服务器标识 + 认证阶段 banner：取自引擎真实握手缓存，不额外发网络请求。
                        // 必须核对来源地址——引擎在地址无记录时会回退到「最近一次握手」，不核对就会串节点。
                        val handshake = StunRepository.getSshHandshakeInfo(selected.sshAddr)
                            ?.takeIf { it.address.equals(selected.sshAddr, ignoreCase = true) }

                        val statusMap = mapOf(
                            "vpnState" to (StunRepository.vpnState.value?.name ?: "DISCONNECTED"),
                            "selectedProfileId" to selected.id,
                            "selectedProfileName" to selected.name,
                            "selectedProfileType" to selected.tunnelType,
                            "profileCount" to ProfileManager.getProfiles(appContext).size,
                            "deviceName" to Build.MODEL,
                            "txRate" to (StunRepository.txRate.value ?: 0L),
                            "rxRate" to (StunRepository.rxRate.value ?: 0L),
                            "txTotal" to (StunRepository.txTotal.value ?: 0L),
                            "rxTotal" to (StunRepository.rxTotal.value ?: 0L),
                            "activeConns" to (trafficStats?.activeConns ?: 0L),
                            "totalConns" to (trafficStats?.totalConns ?: 0L),
                            "filterMode" to SettingsManager.getFilterMode(appContext),
                            "filterAppsCount" to filterCount,
                            "sshServerVersion" to (handshake?.serverVersion ?: ""),
                            "sshBanner" to (handshake?.banner ?: "")
                        )
                        call.respond(HttpStatusCode.OK, statusMap)
                    }

                    // ── 节点列表 API ──
                    get("/api/profiles") {
                        if (!call.checkToken(appContext)) return@get
                        val selectedId = SettingsManager.getSelectedProfileId(appContext)
                        // 凭据一律掩码。WebUI 编辑页把 ***** 挪进 input.dataset.secret、框里只留
                        // 「已保存 · 留空则不修改」的 hint（用户不再看到一坨星号），提交前再还原回框里
                        // 原样发回，由 update 侧识别成"保持原值" —— 读的明文没有必要存在。
                        val redacting = ProfileSecrets.redactingGson()
                        // 来源订阅名（`profiles.subId` → 订阅行）在服务端解析好再下发：
                        // 前端拿不到订阅表，而把 subId 直接当徽标提示既不可读、也没必要暴露。
                        // 与原生节点卡片同一口径：名字为空就不显示徽标（不回落到 URL 域名）。
                        val profiles = ProfileManager.getProfiles(appContext).map { p ->
                            val jsonMap = redacting.fromJson<MutableMap<String, Any?>>(
                                redacting.toJson(p),
                                object : TypeToken<MutableMap<String, Any?>>() {}.type
                            )
                            jsonMap["isSelected"] = (p.id == selectedId)
                            jsonMap["subName"] = SubscriptionManager.subscriptionNameFor(appContext, p.subId)
                            jsonMap
                        }
                        call.respond(HttpStatusCode.OK, profiles)
                    }

                    post("/api/profiles/update") {
                        if (!call.checkToken(appContext)) return@post
                        try {
                            val body = call.receive<Map<String, Any?>>().toMutableMap()
                            // 掩码哨兵：前端把 GET 拿到的 ***** 原样提交回来 = "保持原值"。
                            // 置 null 后，下游 `(body["pass"] as? String) ?: existing.pass` 自然回落到库里的值；
                            // 用户真想清空时提交的是空串（不是掩码），仍会照常写库。
                            ProfileSecrets.dropMaskedSecrets(body)
                            val id = (body["id"] as? String)?.trim()
                            if (id.isNullOrBlank()) {
                                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))
                            }
                            val existing = ProfileManager.getProfileById(appContext, id)
                                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Profile not found"))

// 字段规则已收口到 [ProfileFields]（与 MCP 的 create/update 共用唯一事实来源）。
                            // 原来这里是 107 行 copy(...) 平铺，与 MCP 那份各写一遍同样的
                            // trim/clamp/枚举规则，已因此出过两次静默丢参事故。
                            // ⚠️ 掩码剔除必须先做：dropMaskedSecrets 把 ***** 置 null，
                            // ProfileArgReader 把 null 视为"键不存在" ⇒ 自然退化成"保持原值"。
                            ProfileFields.applyTo(ProfileArgReader.of(body), existing)
                            val updated = existing

                            ProfileManager.updateProfile(appContext, updated)
                            StunLogger.i(TAG, "Web console updated profile: ${updated.name} ($id)")
                            call.respond(HttpStatusCode.OK, mapOf("status" to "success", "profile" to updated))
                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Update failed")))
                        }
                    }

                    post("/api/profiles/select") {
                        if (!call.checkToken(appContext)) return@post
                        val body = call.receive<Map<String, String>>()
                        val id = body["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))
                        SettingsManager.setSelectedProfileId(appContext, id)
                        onProfileSelected?.invoke(id)
                        call.respond(HttpStatusCode.OK, mapOf("status" to "success", "selectedId" to id))
                    }

                    post("/api/profiles/delete") {
                        if (!call.checkToken(appContext)) return@post
                        val body = call.receive<Map<String, String>>()
                        val id = body["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))
                        val profile = ProfileManager.getProfileById(appContext, id)
                        if (profile != null) {
                            ProfileManager.deleteProfile(appContext, profile)
                            onProfileDeleted?.invoke(id)
                            call.respond(HttpStatusCode.OK, mapOf("status" to "success", "deletedId" to id))
                        } else {
                            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Profile not found"))
                        }
                    }

                    // ── 诊断与指纹获取 API ──
                    post("/api/diagnostics/ssh-fingerprint") {
                        if (!call.checkToken(appContext)) return@post
                        try {
                            val body = call.receive<Map<String, String>>()
                            val sshAddr = body["sshAddr"]?.trim() ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing sshAddr"))
                            val fp = withContext(Dispatchers.IO) { myssh.Myssh.getSSHFingerprint(sshAddr) }
                            val detailsJson = withContext(Dispatchers.IO) { myssh.Myssh.getSSHServerDetailsJSON(sshAddr) }
                            call.respond(HttpStatusCode.OK, mapOf("status" to "success", "fingerprint" to fp, "detailsJson" to detailsJson))
                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Failed to fetch SSH fingerprint")))
                        }
                    }

                    post("/api/diagnostics/tls-fingerprint") {
                        if (!call.checkToken(appContext)) return@post
                        try {
                            val body = call.receive<Map<String, String>>()
                            val target = body["target"]?.trim() ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing target"))
                            val serverName = body["serverName"]?.trim() ?: ""
                            val fp = withContext(Dispatchers.IO) { myssh.Myssh.getTLSCertFingerprint(target, serverName) }
                            val detailsJson = withContext(Dispatchers.IO) { myssh.Myssh.getTLSCertDetailsJSON(target, serverName) }
                            call.respond(HttpStatusCode.OK, mapOf("status" to "success", "fingerprint" to fp, "detailsJson" to detailsJson))
                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Failed to fetch TLS certificate fingerprint")))
                        }
                    }

                    // ── 节点延迟测速 API (支持 POST 与 GET，容错空 Body 与 Query 参数) ──
                    suspend fun handleProfilePing(call: io.ktor.server.application.ApplicationCall) {
                        if (!call.checkToken(appContext)) return
                        try {
                            val body = try { call.receiveNullable<Map<String, Any?>>() ?: emptyMap() } catch (_: Exception) { emptyMap() }
                            val targetId = (body["id"] as? String)?.trim() ?: call.request.queryParameters["id"]?.trim()
                            val pingUrl = (body["targetUrl"] as? String)?.trim()?.ifBlank { "http://cp.cloudflare.com/generate_204" }
                                ?: call.request.queryParameters["targetUrl"]?.trim()?.ifBlank { "http://cp.cloudflare.com/generate_204" }
                                ?: "http://cp.cloudflare.com/generate_204"
                            val timeoutMs = (body["timeoutMs"] as? Number)?.toLong()
                                ?: call.request.queryParameters["timeoutMs"]?.toLongOrNull()
                                ?: 8000L

                            val profiles = if (!targetId.isNullOrBlank()) {
                                val p = ProfileManager.getProfileById(appContext, targetId)
                                if (p != null) listOf(p) else emptyList()
                            } else {
                                ProfileManager.getProfiles(appContext)
                            }

                            if (profiles.isEmpty()) {
                                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No profiles found to ping"))
                                return
                            }

                            val resultsMap = withContext(Dispatchers.IO) {
                                val reqArray = org.json.JSONArray()
                                profiles.forEach { p ->
                                    val configJson = VpnConfigBuilder.buildMySshConfig(appContext, p)
                                    reqArray.put(org.json.JSONObject().put("id", p.id).put("config", org.json.JSONObject(configJson)))
                                }

                                val jsonResStr = try {
                                    StunRepository.proxy.pingNodes(
                                        reqArray.toString(),
                                        pingUrl,
                                        timeoutMs
                                    )
                                } catch (e: Exception) {
                                    StunLogger.e(TAG, "pingNodes exception: ${e.message}", e)
                                    "[]"
                                }

                                val res = mutableMapOf<String, Map<String, Any?>>()
                                try {
                                    val arr = org.json.JSONArray(jsonResStr)
                                    for (i in 0 until arr.length()) {
                                        val obj = arr.getJSONObject(i)
                                        val id = obj.optString("id", "")
                                        if (id.isEmpty()) continue
                                        val ok = obj.optBoolean("ok", false)
                                        val latencyMs = obj.optLong("latencyMs", -1)
                                        val errType = obj.optString("errorType", "other")
                                        val errMsg = obj.optString("error", "")

                                        // ok 但没给出正延迟时不能显示 "0 ms" ——
                                        // 那读起来像"节点极快"。当作未测得处理。
                                        val display = if (ok && latencyMs > 0) {
                                            "$latencyMs ms"
                                        } else {
                                            when (errType) {
                                                "timeout" -> "Timeout"
                                                "connrefused" -> "Refused"
                                                "auth" -> "Auth Error"
                                                "hostkey" -> "Key Mismatch"
                                                "tcpforward" -> "No Forward"
                                                "tls" -> "SSL Error"
                                                "dns" -> "DNS Error"
                                                "http" -> "HTTP $errMsg"
                                                else -> "Failed"
                                            }
                                        }

                                        res[id] = mapOf(
                                            "ok" to ok,
                                            "latencyMs" to latencyMs,
                                            "errorType" to errType,
                                            "error" to errMsg,
                                            "display" to display
                                        )
                                    }
                                } catch (_: Exception) {}
                                res
                            }

                            call.respond(HttpStatusCode.OK, mapOf(
                                "status" to "success",
                                "results" to resultsMap
                            ))
                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Ping failed")))
                        }
                    }

                    post("/api/profiles/ping") { handleProfilePing(call) }
                    get("/api/profiles/ping") { handleProfilePing(call) }

                    // ── 加密 / 明文 导入 API ──
                    post("/api/profiles/import") {
                        if (!call.checkToken(appContext)) return@post
                        try {
                            val body = call.receive<Map<String, String>>()
                            val content = body["content"]?.trim()
                                ?: return@post call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "empty_content", "message" to "导入内容不能为空")
                            )
                            val pin = body["pin"]?.trim() ?: ""

                            val isEncrypted = ShareCryptoUtils.isEncryptedPayload(content)
                            val jsonToParse = if (isEncrypted) {
                                if (pin.isBlank()) {
                                    return@post call.respond(
                                        HttpStatusCode.BadRequest,
                                        mapOf("error" to "pin_required", "message" to "检测到加密分享码/备份，请输入 PIN")
                                    )
                                }
                                val decrypted = ShareCryptoUtils.decrypt(content, pin)
                                    ?: return@post call.respond(
                                        HttpStatusCode.BadRequest,
                                        mapOf("error" to "invalid_pin", "message" to "PIN 码错误或解密失败")
                                    )
                                decrypted
                            } else {
                                if (pin.isNotBlank()) {
                                    ShareCryptoUtils.decrypt(content, pin) ?: content
                                } else {
                                    content
                                }
                            }

                            val profiles = parseProfilesFromJson(jsonToParse)
                            if (profiles.isEmpty()) {
                                return@post call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to "invalid_format", "message" to "未能识别有效的节点配置格式")
                                )
                            }

                            var importedCount = 0
                            val existing = ProfileManager.getProfiles(appContext)
                            profiles.forEach { p ->
                                val finalProfile = p.copy(
                                    id = if (p.id.isBlank() || existing.any { it.id == p.id }) UUID.randomUUID().toString() else p.id,
                                    name = p.name.ifBlank { "Imported Node" }
                                )
                                ProfileManager.addProfile(appContext, finalProfile)
                                onProfileAdded?.invoke(finalProfile)
                                importedCount++
                            }

                            StunLogger.i(TAG, "WebUI successfully imported $importedCount profile(s)")
                            call.respond(HttpStatusCode.OK, mapOf("status" to "success", "importedCount" to importedCount))
                        } catch (e: Exception) {
                            StunLogger.e(TAG, "Profile import error", e)
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "import_failed", "message" to (e.message ?: "导入失败")))
                        }
                    }

                    // ── 加密导出 API ──
                    post("/api/profiles/export") {
                        if (!call.checkToken(appContext)) return@post
                        try {
                            val body = call.receive<Map<String, String>>()
                            val pin = body["pin"]?.trim() ?: ShareCryptoUtils.generateRandomPIN()
                            if (pin.length < 4) {
                                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "PIN 码至少需要 4 位"))
                            }

                            val profiles = ProfileManager.getProfiles(appContext)
                            val json = gson.toJson(profiles)
                            val encryptedPayload = ShareCryptoUtils.encrypt(json, pin)

                            call.respond(HttpStatusCode.OK, mapOf(
                                "status" to "success",
                                "payload" to encryptedPayload,
                                "pin" to pin,
                                "count" to profiles.size
                            ))
                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "导出失败")))
                        }
                    }

                    // ── 连接跟踪 API ──
                    get("/api/conntrack") {
                        if (!call.checkToken(appContext)) return@get
                        val connsJsonStr = try {
                            myssh.Myssh.getActiveConnectionsJSON()
                        } catch (_: Exception) { "[]" }

                        val domainsJsonStr = try {
                            myssh.Myssh.getDomainActivityJSON()
                        } catch (_: Exception) { "[]" }

                        val trafficStats = try {
                            myssh.Myssh.getTrafficStats()
                        } catch (_: Exception) { null }

                        val routerStats = try {
                            myssh.Myssh.getRouterStats()
                        } catch (_: Exception) { null }

                        val resMap = mapOf(
                            "activeConns" to (trafficStats?.activeConns ?: 0L),
                            "totalConns" to (trafficStats?.totalConns ?: 0L),
                            "connections" to try { gson.fromJson(connsJsonStr, Any::class.java) } catch (_: Exception) { emptyList<Any>() },
                            "domains" to try { gson.fromJson(domainsJsonStr, Any::class.java) } catch (_: Exception) { emptyList<Any>() },
                            "routeQueryCount" to (routerStats?.queryCount ?: 0L),
                            "routeCacheHitCount" to (routerStats?.cacheHitCount ?: 0L),
                            "routeHitRate" to (routerStats?.hitRate ?: 0.0)
                        )
                        call.respond(HttpStatusCode.OK, resMap)
                    }

                    post("/api/vpn/toggle") {
                        if (!call.checkToken(appContext)) return@post
                        val body = call.receive<Map<String, String>>()
                        val action = body["action"] ?: "start"
                        val profileId = body["profileId"]

                        val handled = onVpnControlRequested?.invoke(if (action == "start") "start_vpn" else "stop_vpn", profileId) ?: false
                        if (!handled) {
                            if (action == "start") {
                                if (profileId != null) SettingsManager.setSelectedProfileId(appContext, profileId)
                                val intent = Intent(appContext, MyVpnService::class.java).setAction(MyVpnService.ACTION_START)
                                ContextCompat.startForegroundService(appContext, intent)
                            } else {
                                val intent = Intent(appContext, MyVpnService::class.java).setAction(MyVpnService.ACTION_STOP)
                                ContextCompat.startForegroundService(appContext, intent)
                            }
                        }
                        call.respond(HttpStatusCode.OK, mapOf("status" to "success", "action" to action))
                    }

                    // 应用图标：打开「系统设置」会为每个应用各发一次图标请求，而取图链路
                    // （PackageManager → Drawable → Bitmap → PNG 压缩）本身并不便宜。
                    // 这里给足客户端缓存：ETag = 包名 + 安装包 lastUpdateTime，应用升级换图标后 ETag 自然失效。
                    // 这样重复打开页面时这些请求基本不再落到服务端。
                    get("/api/app-icon") {
                        if (!call.checkToken(appContext)) return@get
                        val pkg = call.parameters["pkg"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                        try {
                            val pm = appContext.packageManager
                            val stamp = try {
                                pm.getPackageInfo(pkg, 0).lastUpdateTime
                            } catch (_: Exception) {
                                0L
                            }
                            val etag = "\"appicon-$stamp-$pkg\""
                            call.response.header("Cache-Control", "private, max-age=86400")
                            call.response.header("ETag", etag)
                            if (call.request.headers["If-None-Match"] == etag) {
                                call.respond(HttpStatusCode.NotModified)
                                return@get
                            }
                            val iconDrawable = pm.getApplicationIcon(pkg)
                            val bitmap = drawableToBitmap(iconDrawable)
                            val stream = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
                            call.respondBytes(stream.toByteArray(), ContentType.Image.PNG)
                        } catch (_: Exception) {
                            call.respond(HttpStatusCode.NotFound)
                        }
                    }

                    get("/api/apps") {
                        if (!call.checkToken(appContext)) return@get
                        // refresh=1：前端点「刷新」时强制重建清单缓存（例如刚装完新应用）
                        if (call.request.queryParameters["refresh"] == "1") invalidateAppsMetaCache()
                        val filterApps = SettingsManager.getFilterApps(appContext).split(",").filter { it.isNotBlank() }.toSet()
                        // 清单走内存缓存（TTL 60s，可被 refresh=1 立即失效）；isSelected 按最新配置现算，保存后立刻可见。
                        val appsList = loadInstalledAppsMeta(appContext).map { meta ->
                            mapOf(
                                "packageName" to meta.packageName,
                                "appName" to meta.appName,
                                "versionName" to meta.versionName,
                                "versionCode" to meta.versionCode,
                                "isSystem" to meta.isSystem,
                                "isSelected" to filterApps.contains(meta.packageName)
                            )
                        }
                        call.respond(HttpStatusCode.OK, appsList)
                    }

                    post("/api/apps/save") {
                        if (!call.checkToken(appContext)) return@post
                        val body = call.receive<Map<String, Any>>()
                        val mode = (body["filterMode"] as? Number)?.toInt() ?: 0
                        val apps = (body["filterApps"] as? String) ?: ""

                        SettingsManager.saveFilterMode(appContext, mode)
                        SettingsManager.saveFilterApps(appContext, apps)
                        StunLogger.i(TAG, "Web console saved app filter settings: mode=$mode, count=${apps.split(",").filter { it.isNotBlank() }.size}")
                        call.respond(HttpStatusCode.OK, mapOf("status" to "success"))
                    }

                    // ── 订阅管理 API (Subscription Management, 多订阅) ──
                    get("/api/subscription") {
                        if (!call.checkToken(appContext)) return@get
                        call.respond(
                            HttpStatusCode.OK,
                            mapOf(
                                "subscriptions" to SubscriptionManager.getSubscriptions(appContext).map {
                                    val meta = SubscriptionManager.getSyncMetaForSub(appContext, it.subId)
                                    mapOf(
                                        // 订阅的本地 id：前端必须原样回传（save / sync），
                                        // 否则"在控制台里改订阅链接"会被当成新建一条 —— 用量历史、
                                        // 同步计时、节点归属全部断开，正是本次重构要消灭的漂移。
                                        "subId" to it.subId,
                                        "url" to it.url,
                                        "pin" to it.pin,
                                        // 响应头解析出的元信息必须回吐：缺了它前端只能显示裸链接，
                                        // 而且 save 回写时会把这些字段抹掉（不可逆丢数据）。
                                        "name" to it.name,
                                        "homePage" to it.homePage,
                                        "updateIntervalHours" to it.updateIntervalHours,
                                        // per-sub 同步元信息：旧版只有全局 lastSync，多订阅时无法逐行展示。
                                        "lastSync" to (meta?.time ?: 0L),
                                        "nodeCount" to (meta?.count ?: -1)
                                    )
                                },
                                "lastSync" to SubscriptionManager.getLastSyncTime(appContext)
                            )
                        )
                    }

                    // 覆盖保存整个订阅列表；body: {subscriptions:[{subId,url,pin,name?,homePage?,updateIntervalHours?}]}
                    // subId 缺省（旧前端）时按同 URL 认领已存行；name/homePage/updateIntervalHours
                    // 缺省时沿用同 URL 的已存值，避免旧前端把元信息清空。
                    post("/api/subscription/save") {
                        if (!call.checkToken(appContext)) return@post
                        try {
                            val body = call.receive<Map<String, Any?>>()
                            val previous = SubscriptionManager.getSubscriptions(appContext)
                                .associateBy { it.url }
                            val subs = (body["subscriptions"] as? List<*>)?.mapNotNull { item ->
                                (item as? Map<*, *>)?.let { m ->
                                    val u = (m["url"] as? String)?.trim().orEmpty()
                                    if (u.isBlank()) {
                                        null
                                    } else {
                                        val prev = previous[u]
                                        val name = (m["name"] as? String)?.trim().orEmpty()
                                        val homePage = (m["homePage"] as? String)?.trim().orEmpty()
                                        val interval = (m["updateIntervalHours"] as? Number)
                                            ?.toInt()?.coerceAtLeast(0) ?: 0
                                        SubscriptionManager.SubEntry(
                                            subId = (m["subId"] as? String)?.trim().orEmpty(),
                                            url = u,
                                            pin = (m["pin"] as? String)?.trim().orEmpty(),
                                            name = name.ifBlank { prev?.name.orEmpty() },
                                            homePage = homePage.ifBlank { prev?.homePage.orEmpty() },
                                            updateIntervalHours = interval.takeIf { it > 0 }
                                                ?: prev?.updateIntervalHours ?: 0
                                        )
                                    }
                                }
                            } ?: emptyList()
                            if (subs.isEmpty()) {
                                return@post call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to "empty_url", "message" to "订阅链接不能为空")
                                )
                            }
                            SubscriptionManager.saveSubscriptions(appContext, subs)
                            StunLogger.i(TAG, "Web console saved ${subs.size} subscription(s)")
                            call.respond(HttpStatusCode.OK, mapOf("status" to "success"))
                        } catch (e: Exception) {
                            StunLogger.e(TAG, "Subscription save error", e)
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "save_failed", "message" to (e.message ?: "保存失败")))
                        }
                    }

                    // 同步订阅。body 可带 {subscriptions:[...]}：带上就先按 save 的口径落库再同步
                    // （修复"控制台改了不保存"——旧实现直接忽略 body，编辑被静默丢弃）。
                    // 不带则同步已保存列表。返回每条订阅的同步结果。
                    post("/api/subscription/sync") {
                        if (!call.checkToken(appContext)) return@post
                        try {
                            val body = runCatching { call.receive<Map<String, Any?>>() }.getOrNull()
                            val incoming = body?.let { b ->
                                (b["subscriptions"] as? List<*>)?.mapNotNull { item ->
                                    (item as? Map<*, *>)?.let { m ->
                                        val u = (m["url"] as? String)?.trim().orEmpty()
                                        if (u.isBlank()) null else SubscriptionManager.SubEntry(
                                            subId = (m["subId"] as? String)?.trim().orEmpty(),
                                            url = u,
                                            pin = (m["pin"] as? String)?.trim().orEmpty(),
                                            name = (m["name"] as? String)?.trim().orEmpty(),
                                            homePage = (m["homePage"] as? String)?.trim().orEmpty(),
                                            updateIntervalHours = (m["updateIntervalHours"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0
                                        )
                                    }
                                }
                            }
                            val toSync = if (!incoming.isNullOrEmpty()) {
                                // 用落库后的返回值（subId 已补齐）去同步：直接拿入参会带着空 subId
                                // 进同步，节点盖戳/用量记账/结果回贴全都会失去锚点。
                                SubscriptionManager.saveSubscriptions(appContext, incoming)
                            } else {
                                SubscriptionManager.getSubscriptions(appContext)
                            }
                            val items = SubscriptionManager.syncAllSubscriptions(appContext, toSync)
                            val okCount = items.count { it.success }
                            val imported = items.sumOf { it.importedCount }
                            val updated = items.sumOf { it.updatedCount }
                            StunLogger.i(TAG, "Web console subscription sync: $okCount/${items.size} ok, +$imported ~$updated")
                            call.respond(
                                HttpStatusCode.OK,
                                mapOf(
                                    "status" to "success",
                                    "importedCount" to imported,
                                    "updatedCount" to updated,
                                    "okCount" to okCount,
                                    "failedCount" to (items.size - okCount),
                                    "lastSync" to SubscriptionManager.getLastSyncTime(appContext),
                                    "results" to items.map {
                                        mapOf(
                                            "url" to it.url,
                                            "ok" to it.success,
                                            "imported" to it.importedCount,
                                            "updated" to it.updatedCount,
                                            "removed" to it.removedCount,
                                            // 上屏用 code（前端本地化），message 仅日志/兜底。
                                            "errorCode" to it.errorCode,
                                            "message" to it.message
                                        )
                                    }
                                )
                            )
                        } catch (e: Exception) {
                            StunLogger.e(TAG, "Subscription sync error", e)
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "sync_failed", "message" to (e.message ?: "同步失败")))
                        }
                    }

                    // ── WebDAV 云备份 / 同步 API (WebDAV Cloud Backup & Sync) ──
                    get("/api/webdav") {
                        if (!call.checkToken(appContext)) return@get
                        call.respond(
                            HttpStatusCode.OK,
                            mapOf(
                                "url" to SettingsManager.getWebDavUrl(appContext),
                                "user" to SettingsManager.getWebDavUser(appContext),
                                "hasPass" to SettingsManager.getWebDavPass(appContext).isNotBlank(),
                                "hasPin" to SettingsManager.getWebDavPin(appContext).isNotBlank(),
                                "auto" to SettingsManager.isWebDavAutoBackupEnabled(appContext),
                                "intervalHours" to SettingsManager.getWebDavBackupIntervalHours(appContext),
                                "lastBackup" to SettingsManager.getWebDavLastBackupTime(appContext),
                                // 同步模式：前端按 id 自己映射文案，别让它去比本地化的标签串
                                "syncMode" to SettingsManager.getWebDavSyncMode(appContext).id,
                                "lastSync" to SettingsManager.getWebDavLastSyncTime(appContext)
                            )
                        )
                    }

                    // 保存配置：pass/pin 留空 = 保持现有值不变
                    post("/api/webdav/config") {
                        if (!call.checkToken(appContext)) return@post
                        try {
                            val body = call.receive<Map<String, Any?>>()
                            val url = (body["url"] as? String)?.trim() ?: SettingsManager.getWebDavUrl(appContext)
                            val user = (body["user"] as? String)?.trim() ?: SettingsManager.getWebDavUser(appContext)
                            val pass = (body["pass"] as? String) ?: SettingsManager.getWebDavPass(appContext)
                            val pin = (body["pin"] as? String)?.trim() ?: SettingsManager.getWebDavPin(appContext)
                            SettingsManager.saveWebDavConfig(appContext, url, user, pass, pin)
                            (body["auto"] as? Boolean)?.let { SettingsManager.setWebDavAutoBackup(appContext, it) }
                            (body["intervalHours"] as? Number)?.toInt()?.let {
                                SettingsManager.saveWebDavBackupIntervalHours(appContext, it.toLong())
                            }
                            // 换模式＝换方向，作废"已推过兜底快照"标记：新模式的第一次仍要先留后路
                            (body["syncMode"] as? String)?.let { raw ->
                                val mode = WebDavSyncMode.fromId(raw)
                                if (mode != SettingsManager.getWebDavSyncMode(appContext)) {
                                    SettingsManager.saveWebDavSyncMode(appContext, mode)
                                    SettingsManager.setWebDavSyncBootstrapped(appContext, false)
                                }
                            }
                            // 间隔/开关/模式可能变化，重排 WorkManager 周期任务（未开启则取消）
                            app.fjj.stun.worker.WebDavBackupWorker.schedule(appContext)
                            call.respond(
                                HttpStatusCode.OK,
                                mapOf(
                                    "status" to "success",
                                    "syncMode" to SettingsManager.getWebDavSyncMode(appContext).id
                                )
                            )
                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "save failed")))
                        }
                    }

                    // 按当前同步模式跑一次（body 可带 mode 临时覆盖；不带就用设置里的）
                    post("/api/webdav/sync") {
                        if (!call.checkToken(appContext)) return@post
                        try {
                            val body = runCatching { call.receive<Map<String, Any?>>() }.getOrDefault(emptyMap())
                            val mode = (body["mode"] as? String)
                                ?.let { WebDavSyncMode.fromId(it) }
                                ?: SettingsManager.getWebDavSyncMode(appContext)
                            val result = WebDavBackupManager.sync(appContext, readWebDavConfig(appContext), mode)
                            if (result.pushed) {
                                SettingsManager.saveWebDavLastBackupTime(appContext, System.currentTimeMillis())
                            }
                            StunLogger.i(
                                TAG,
                                "WebUI WebDAV sync(${mode.id}) OK: pulled=${result.pulled}, pushed=${result.pushed}"
                            )
                            call.respond(
                                HttpStatusCode.OK,
                                mapOf(
                                    "status" to "success",
                                    "mode" to mode.id,
                                    "pulled" to result.pulled,
                                    // 已本地化的分区名，前端直接拼进提示语（不重复维护一份翻译）
                                    "pulledText" to WebDavBackupManager.sectionSummary(appContext, result.pulled),
                                    "pushed" to result.pushed,
                                    "count" to result.profiles,
                                    "bootstrapped" to result.bootstrapped
                                )
                            )
                        } catch (e: Exception) {
                            StunLogger.w(TAG, "WebUI WebDAV sync failed: ${e.message}")
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "sync failed")))
                        }
                    }

                    // 服务器上的备份目录列表（由新到旧，UTC 时间戳名）
                    get("/api/webdav/backups") {
                        if (!call.checkToken(appContext)) return@get
                        try {
                            val backups = WebDavBackupManager.listBackups(readWebDavConfig(appContext))
                            call.respond(HttpStatusCode.OK, mapOf("backups" to backups))
                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "list failed")))
                        }
                    }

                    // 立即备份：使用已保存配置（webui 先调 config 端点保存再触发）
                    post("/api/webdav/backup") {
                        if (!call.checkToken(appContext)) return@post
                        try {
                            val result = WebDavBackupManager.backup(appContext, readWebDavConfig(appContext))
                            SettingsManager.saveWebDavLastBackupTime(appContext, System.currentTimeMillis())
                            StunLogger.i(
                                TAG,
                                "WebUI WebDAV backup OK: ${result.profiles} node(s), sections=${result.sections}"
                            )
                            call.respond(
                                HttpStatusCode.OK,
                                mapOf(
                                    "status" to "success",
                                    "count" to result.profiles,
                                    "sections" to result.sections,
                                    // 已本地化的分区名，WebUI 直接拼进提示语（前端不重复维护一份翻译）
                                    "sectionsText" to WebDavBackupManager.sectionSummary(appContext, result.sections)
                                )
                            )
                        } catch (e: Exception) {
                            StunLogger.w(TAG, "WebUI WebDAV backup failed: ${e.message}")
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "backup failed")))
                        }
                    }

                    // 从云端恢复（body: dir = 备份目录名；节点按 id 合并 + 设置写回）
                    post("/api/webdav/restore") {
                        if (!call.checkToken(appContext)) return@post
                        try {
                            val body = call.receive<Map<String, Any?>>()
                            val dir = (body["dir"] as? String)?.trim().orEmpty()
                            if (dir.isEmpty()) {
                                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "dir required"))
                                return@post
                            }
                            val result = WebDavBackupManager.restore(appContext, readWebDavConfig(appContext), dir)
                            StunLogger.i(TAG, "WebUI WebDAV restore OK: ${result.profiles} node(s), sections=${result.sections}")
                            if (result.settings) {
                                // 快照可能带回新的 auto/interval 配置，重排周期备份
                                app.fjj.stun.worker.WebDavBackupWorker.schedule(appContext)
                            }
                            call.respond(
                                HttpStatusCode.OK,
                                mapOf(
                                    "status" to "success",
                                    "count" to result.profiles,
                                    "settings" to result.settings,
                                    "sections" to result.sections,
                                    "sectionsText" to WebDavBackupManager.sectionSummary(appContext, result.sections)
                                )
                            )
                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "restore failed")))
                        }
                    }

                    // ── 综合系统设置 API (Core Settings) ──
                    get("/api/settings") {
                        if (!call.checkToken(appContext)) return@get
                        val mode = SettingsManager.getWebAuthMode(appContext)
                        val effective = getEffectiveToken(appContext)
                        val custom = SettingsManager.getWebCustomToken(appContext)
                        val permanent = SettingsManager.getWebPermanentToken(appContext)
                        val fullUrl = getEffectiveUrl(appContext, actualPort)

                        val settingsMap = mapOf(
                            "serviceMode" to SettingsManager.getServiceMode(appContext),
                            "logLevel" to SettingsManager.getLogLevel(appContext),
                            "remoteDns" to SettingsManager.getRemoteDnsServer(appContext),
                            "localDns" to SettingsManager.getLocalDnsServer(appContext),
                            "udpgwVersion" to SettingsManager.getUdpgwVersion(appContext),
                            "udpgwAddr" to SettingsManager.getUdpgwAddr(appContext),
                            "geositeUrl" to SettingsManager.getGeositeUrl(appContext),
                            "geoipUrl" to SettingsManager.getGeoipUrl(appContext),
                            "updateInterval" to SettingsManager.getUpdateInterval(appContext),
                            "geositeDirect" to SettingsManager.getGeositeDirect(appContext),
                            "geoipDirect" to SettingsManager.getGeoipDirect(appContext),
                            "lastUpdateTime" to SettingsManager.getLastUpdateTime(appContext),
                            "showNotificationSpeed" to SettingsManager.getShowNotificationSpeed(appContext),
                            "authMode" to mode,
                            "effectiveToken" to effective,
                            "randomToken" to (if (token.isNotBlank()) token else SettingsManager.getWebPermanentToken(appContext)),
                            "customToken" to custom,
                            "permanentToken" to permanent,
                            "effectiveUrl" to fullUrl,
                            "mcpServerEnabled" to SettingsManager.isMcpServerEnabled(appContext),
                            "mcpServerPort" to SettingsManager.getMcpServerPort(appContext),
                            "mcpAuthMode" to SettingsManager.getMcpAuthMode(appContext),
                            "mcpAuthSecret" to SettingsManager.getMcpApiKey(appContext),
                            "mcpIsRunning" to StunMcpServer.isRunning()
                        )
                        call.respond(HttpStatusCode.OK, settingsMap)
                    }

                    post("/api/settings/save") {
                        if (!call.checkToken(appContext)) return@post
                        try {
                            val body = call.receive<Map<String, Any>>()
                            
                            (body["serviceMode"] as? Number)?.toInt()?.let { SettingsManager.saveServiceMode(appContext, it) }
                            (body["logLevel"] as? String)?.let { SettingsManager.saveLogLevel(appContext, it) }
                            (body["remoteDns"] as? String)?.let { SettingsManager.saveRemoteDnsServer(appContext, it) }
                            (body["localDns"] as? String)?.let { SettingsManager.saveLocalDnsServer(appContext, it) }
                            (body["udpgwVersion"] as? String)?.let { SettingsManager.saveUdpgwVersion(appContext, it) }
                            (body["udpgwAddr"] as? String)?.let { SettingsManager.saveUdpgwAddr(appContext, it) }
                            (body["geositeUrl"] as? String)?.let { SettingsManager.saveGeositeUrl(appContext, it) }
                            (body["geoipUrl"] as? String)?.let { SettingsManager.saveGeoipUrl(appContext, it) }
                            (body["updateInterval"] as? Number)?.toLong()?.let { SettingsManager.saveUpdateInterval(appContext, it) }
                            (body["geositeDirect"] as? String)?.let { SettingsManager.saveGeositeDirect(appContext, it) }
                            (body["geoipDirect"] as? String)?.let { SettingsManager.saveGeoipDirect(appContext, it) }
                            (body["showNotificationSpeed"] as? Boolean)?.let { SettingsManager.saveShowNotificationSpeed(appContext, it) }

                            var restartMcp = false
                            val mcpEnabled = body["mcpServerEnabled"] as? Boolean
                            if (mcpEnabled != null && mcpEnabled != SettingsManager.isMcpServerEnabled(appContext)) {
                                SettingsManager.setMcpServerEnabled(appContext, mcpEnabled)
                                restartMcp = true
                            }
                            val mcpPort = (body["mcpServerPort"] as? Number)?.toInt()
                            if (mcpPort != null && mcpPort != SettingsManager.getMcpServerPort(appContext)) {
                                SettingsManager.setMcpServerPort(appContext, mcpPort)
                                restartMcp = true
                            }
                            val mcpAuthMode = (body["mcpAuthMode"] as? Number)?.toInt()
                            if (mcpAuthMode != null && mcpAuthMode != SettingsManager.getMcpAuthMode(appContext)) {
                                SettingsManager.setMcpAuthMode(appContext, mcpAuthMode)
                                restartMcp = true
                            }
                            val mcpAuthSecret = body["mcpAuthSecret"] as? String
                            if (mcpAuthSecret != null && mcpAuthSecret != SettingsManager.getMcpApiKey(appContext)) {
                                SettingsManager.setMcpApiKey(appContext, mcpAuthSecret)
                                restartMcp = true
                            }
                            if (restartMcp) {
                                if (SettingsManager.isMcpServerEnabled(appContext)) {
                                    StunMcpServer.restart(appContext, SettingsManager.getMcpServerPort(appContext))
                                } else {
                                    StunMcpServer.stop()
                                }
                            }

                            val authMode = (body["authMode"] as? Number)?.toInt()
                            if (authMode != null) {
                                SettingsManager.saveWebAuthMode(appContext, authMode)
                                (body["customToken"] as? String)?.trim()?.let {
                                    if (it.isNotBlank()) SettingsManager.saveWebCustomToken(appContext, it)
                                }
                                token = getEffectiveToken(appContext)
                                val newUrl = getEffectiveUrl(appContext, actualPort)
                                onAuthConfigChanged?.invoke(newUrl)
                            }

                            val newUrl = getEffectiveUrl(appContext, actualPort)
                            StunLogger.i(TAG, "Web console saved all system settings successfully")
                            call.respond(HttpStatusCode.OK, mapOf(
                                "status" to "success",
                                "effectiveToken" to token,
                                "randomToken" to (if (token.isNotBlank()) token else SettingsManager.getWebPermanentToken(appContext)),
                                "customToken" to SettingsManager.getWebCustomToken(appContext),
                                "permanentToken" to SettingsManager.getWebPermanentToken(appContext),
                                "effectiveUrl" to newUrl,
                                "mcpIsRunning" to StunMcpServer.isRunning()
                            ))
                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Save failed")))
                        }
                    }

                    // ── 保活与后台（Magisk service.d / Shizuku）──
                    //
                    // 刻意**不**并进 /api/settings/save，有两个理由：
                    // 1. 这两个开关一保存就要动设备（root 往 /data/adb 写脚本、可能要弹 Shizuku
                    //    授权），同步做完可能超过 libsu 的 90s 超时，把整次"批量保存"一起拖挂；
                    // 2. 开关需要在失败时**立刻回滚到未开启**，塞进批量保存里做不到那种反馈粒度。
                    get("/api/keepalive/status") {
                        if (!call.checkToken(appContext)) return@get
                        val bundle = withContext(Dispatchers.IO) { KeepAliveManager.statusBundle(appContext) }
                        call.respond(HttpStatusCode.OK, bundle)
                    }

                    post("/api/keepalive/magisk") {
                        if (!call.checkToken(appContext)) return@post
                        val enabled = (runCatching { call.receive<Map<String, Any>>() }.getOrNull()
                            ?.get("enabled") as? Boolean) ?: false
                        val outcome = withContext(Dispatchers.IO) {
                            if (enabled) KeepAliveManager.installServiceD(appContext)
                            else KeepAliveManager.removeServiceD()
                        }
                        // 只有动作真的成功才落库 —— 开关必须与设备上的真实状态一致，绝不撒谎。
                        if (outcome is KeepAliveManager.Outcome.Ok) {
                            SettingsManager.saveMagiskServiceDEnabled(appContext, enabled)
                        } else {
                            StunLogger.w(TAG, "keepalive/magisk enabled=$enabled failed: ${(outcome as KeepAliveManager.Outcome.Failed).code}")
                        }
                        call.respond(
                            if (outcome is KeepAliveManager.Outcome.Ok) HttpStatusCode.OK else HttpStatusCode.BadRequest,
                            keepAlivePayload(outcome, appContext)
                        )
                    }

                    post("/api/keepalive/shizuku") {
                        if (!call.checkToken(appContext)) return@post
                        val body = runCatching { call.receive<Map<String, Any>>() }.getOrNull()
                        val enabled = body?.get("enabled") as? Boolean ?: false
                        // 远端 WebUI：Shizuku 授权弹窗是显示在**设备屏幕**上的，用户多半不在跟前，
                        // 所以默认不主动弹窗，只回报状态让 UI 提示去设备上处理。
                        val requestPermission = body?.get("requestPermission") as? Boolean ?: false
                        val outcome = withContext(Dispatchers.IO) {
                            if (enabled) KeepAliveManager.enableShizukuKeepAlive(appContext, requestPermission)
                            else KeepAliveManager.disableShizukuKeepAlive(appContext)
                        }
                        if (outcome is KeepAliveManager.Outcome.Ok) {
                            SettingsManager.saveShizukuKeepAliveEnabled(appContext, enabled)
                        } else {
                            StunLogger.w(TAG, "keepalive/shizuku enabled=$enabled failed: ${(outcome as KeepAliveManager.Outcome.Failed).code}")
                        }
                        call.respond(
                            if (outcome is KeepAliveManager.Outcome.Ok) HttpStatusCode.OK else HttpStatusCode.BadRequest,
                            keepAlivePayload(outcome, appContext)
                        )
                    }

                    post("/api/settings/update-geodata") {
                        if (!call.checkToken(appContext)) return@post
                        withContext(Dispatchers.IO) {
                            try {
                                SettingsManager.updateGeoDataSync(appContext)
                                call.respond(HttpStatusCode.OK, mapOf(
                                    "status" to "success",
                                    "lastUpdateTime" to SettingsManager.getLastUpdateTime(appContext)
                                ))
                            } catch (e: Exception) {
                                call.respond(HttpStatusCode.InternalServerError, mapOf(
                                    "error" to "update_failed",
                                    "message" to (e.message ?: "Failed to update GeoData")
                                ))
                            }
                        }
                    }

                    get("/logs/stream") {
                        if (!call.checkToken(appContext)) return@get
                        call.response.header(HttpHeaders.CacheControl, "no-cache")
                        call.response.header(HttpHeaders.Connection, "keep-alive")
                        call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
                        call.respondBytesWriter(ContentType.parse("text/event-stream")) {
                            writeFully(": connected\n\n".toByteArray())
                            flush()
                            try {
                                StunLogger.logFlow.collect { line ->
                                    val sseData = buildString {
                                        line.trimEnd().split("\n").forEach { append("data: $it\n") }
                                        append("\n")
                                    }
                                    writeFully(sseData.toByteArray())
                                    flush()
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    get("/logs/clear") {
                        if (!call.checkToken(appContext)) return@get
                        StunLogger.i(TAG, "--- Log cleared by web console ---")
                        call.respond(HttpStatusCode.OK, "ok")
                    }

                    // ── 崩溃历史 API ──
                    // 数据源 filesDir/crash_history.jsonl（CrashHistoryStore 维护）：JVM 未捕获异常
                    // 由 CrashHandler 写入，Go 引擎 Panic 由 StunRepository.onCrash 写入。
                    // 读操作一律走 Dispatchers.IO——历史可能累积到几十 KB，别压 Ktor 线程。
                    get("/api/crashes") {
                        if (!call.checkToken(appContext)) return@get
                        val crashes = withContext(Dispatchers.IO) { CrashHistoryStore.list(appContext) }
                        call.respond(HttpStatusCode.OK, mapOf(
                            "status" to "success",
                            "total" to crashes.size,
                            "limit" to CrashHistoryStore.MAX_RECORDS,
                            "crashes" to crashes.map { c -> mapOf(
                                "id" to c.id,
                                "time" to c.time,
                                "type" to c.type,
                                "version" to c.version,
                                "device" to c.device,
                                "android" to c.android,
                                "thread" to c.thread,
                                "exception" to c.exception,
                                "message" to c.message,
                                "report" to c.report
                            ) }
                        ))
                    }

                    post("/api/crashes/delete") {
                        if (!call.checkToken(appContext)) return@post
                        val body = try { call.receive<Map<String, Any?>>() } catch (_: Exception) { emptyMap() }
                        val id = when (val raw = body["id"]) {
                            is Number -> raw.toLong()
                            else -> raw?.toString()?.trim()?.toLongOrNull()
                        }
                        if (id == null) return@post call.respond(
                            HttpStatusCode.BadRequest, mapOf("error" to "Missing id")
                        )
                        val deleted = withContext(Dispatchers.IO) { CrashHistoryStore.delete(appContext, id) }
                        call.respond(
                            if (deleted) HttpStatusCode.OK else HttpStatusCode.NotFound,
                            mapOf("status" to "success", "deleted" to deleted, "id" to id)
                        )
                    }

                    post("/api/crashes/clear") {
                        if (!call.checkToken(appContext)) return@post
                        val removed = withContext(Dispatchers.IO) { CrashHistoryStore.clear(appContext) }
                        call.respond(HttpStatusCode.OK, mapOf("status" to "success", "deleted" to removed))
                    }
                }
            }.start(wait = false)

            val fullUrl = getEffectiveUrl(context, actualPort)
            // 粘滞标记：保活 worker 靠它判断"这台设备本来就跑控制台"，从而不必在
            // 从没起过控制台的设备上（如手机端）凭空开一个监听端口。只置位不清除。
            SettingsManager.markWebConsoleEverStarted(appContext)
            StunLogger.i(TAG, "WebServer started → $fullUrl")
            return actualPort
        }
        return -1
    }

    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            server?.stop(500, 1000)
            server = null
            token = ""
            StunLogger.i(TAG, "WebServer stopped")
        }
    }

    fun isRunning() = isRunning.get()

    /**
     * 保活开关接口的统一响应体。
     *
     * `code` 是给前端拿去**本地化**的失败码（服务端不拼文案），`status` 把动作之后的真实状态
     * 一起回传 —— 前端据此把开关回滚到与设备一致的位置，而不是盲目相信用户刚才那一下点击。
     */
    private fun keepAlivePayload(outcome: KeepAliveManager.Outcome, ctx: Context): Map<String, Any?> = mapOf(
        "ok" to (outcome is KeepAliveManager.Outcome.Ok),
        "code" to (outcome as? KeepAliveManager.Outcome.Failed)?.code,
        "status" to KeepAliveManager.statusBundle(ctx)
    )

    fun getLocalIp(context: Context): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            val candidateIps = mutableListOf<String>()
            
            while (interfaces != null && interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                
                // 优先选择以太网 (eth0) 或 Wi-Fi (wlan0)
                val name = iface.name.lowercase()
                val isLanInterface = name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("en")
                
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        
                        // 彻底排除 VPN 常见的虚拟网段
                        if (host.startsWith("10.0.0.") || host.startsWith("172.18.") || 
                            host.startsWith("172.19.") || host.startsWith("172.20.")) continue

                        if (isLanInterface) return host // 找到 LAN 接口，直接返回
                        candidateIps.add(host)
                    }
                }
            }
            return candidateIps.firstOrNull() ?: "localhost"
        } catch (e: Exception) {
            StunLogger.w(TAG, "getLocalIp failed: ${e.message}")
        }
        return "localhost"
    }

    private suspend fun ApplicationCall.checkToken(appContext: Context): Boolean {
        val mode = SettingsManager.getWebAuthMode(appContext)
        if (mode == SettingsManager.WEB_AUTH_MODE_DISABLED) return true

        val expectedToken = getEffectiveToken(appContext)
        if (expectedToken.isBlank()) return true // Security hole fallback, but better than lockout

        val reqToken = request.queryParameters["token"] ?: request.headers["X-Auth-Token"]
        if (reqToken == expectedToken) return true
        respond(HttpStatusCode.Unauthorized, "401 — Unauthorized. Access token required.")
        return false
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth.coerceAtMost(96) else 96
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight.coerceAtMost(96) else 96
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
