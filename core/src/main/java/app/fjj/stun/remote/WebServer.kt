package app.fjj.stun.remote

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Base64
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import app.fjj.stun.repo.Profile
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.service.MyVpnService
import app.fjj.stun.service.VpnConfigBuilder
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
                    gson { setPrettyPrinting() }
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
                            "filterAppsCount" to filterCount
                        )
                        call.respond(HttpStatusCode.OK, statusMap)
                    }

                    // ── 节点列表 API ──
                    get("/api/profiles") {
                        if (!call.checkToken(appContext)) return@get
                        val selectedId = SettingsManager.getSelectedProfileId(appContext)
                        val profiles = ProfileManager.getProfiles(appContext).map { p ->
                            val jsonMap = gson.fromJson<MutableMap<String, Any?>>(gson.toJson(p), object : TypeToken<MutableMap<String, Any?>>() {}.type)
                            jsonMap["isSelected"] = (p.id == selectedId)
                            jsonMap
                        }
                        call.respond(HttpStatusCode.OK, profiles)
                    }

                    post("/api/profiles/update") {
                        if (!call.checkToken(appContext)) return@post
                        try {
                            val body = call.receive<Map<String, Any?>>()
                            val id = (body["id"] as? String)?.trim()
                            if (id.isNullOrBlank()) {
                                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))
                            }
                            val existing = ProfileManager.getProfileById(appContext, id)
                                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Profile not found"))

                            val updated = existing.copy(
                                name = (body["name"] as? String)?.trim()?.ifBlank { existing.name } ?: existing.name,
                                sshAddr = (body["sshAddr"] as? String)?.trim()?.ifBlank { existing.sshAddr } ?: existing.sshAddr,
                                user = (body["user"] as? String)?.trim() ?: existing.user,
                                pass = (body["pass"] as? String) ?: existing.pass,
                                authType = (body["authType"] as? String) ?: existing.authType,
                                privateKey = (body["privateKey"] as? String) ?: existing.privateKey,
                                keyPass = (body["keyPass"] as? String) ?: existing.keyPass,
                                tunnelType = (body["tunnelType"] as? String) ?: existing.tunnelType,
                                proxyAddr = (body["proxyAddr"] as? String)?.trim() ?: existing.proxyAddr,
                                customHost = (body["customHost"] as? String)?.trim() ?: existing.customHost,
                                serverName = (body["serverName"] as? String)?.trim() ?: existing.serverName,
                                customPath = (body["customPath"] as? String)?.trim() ?: existing.customPath,
                                enableCustomPath = (body["enableCustomPath"] as? Boolean) ?: existing.enableCustomPath,
                                httpPayload = (body["httpPayload"] as? String)?.trim() ?: existing.httpPayload,
                                disableStatusCheck = (body["disableStatusCheck"] as? Boolean) ?: existing.disableStatusCheck,
                                alpn = (body["alpn"] as? String)?.trim() ?: existing.alpn,
                                proxyAuthRequired = (body["proxyAuthRequired"] as? Boolean) ?: existing.proxyAuthRequired,
                                proxyAuthToken = (body["proxyAuthToken"] as? String)?.trim() ?: existing.proxyAuthToken,
                                proxyAuthUser = (body["proxyAuthUser"] as? String)?.trim() ?: existing.proxyAuthUser,
                                proxyAuthPass = (body["proxyAuthPass"] as? String) ?: existing.proxyAuthPass,
                                verifyCertFingerprint = (body["verifyCertFingerprint"] as? Boolean) ?: existing.verifyCertFingerprint,
                                serverCertFingerprint = (body["serverCertFingerprint"] as? String)?.trim() ?: existing.serverCertFingerprint,
                                dnsTunnelDomain = (body["dnsTunnelDomain"] as? String)?.trim() ?: existing.dnsTunnelDomain,
                                dnsTunnelServers = (body["dnsTunnelServers"] as? String)?.trim() ?: existing.dnsTunnelServers,
                                dnsTunnelType = (body["dnsTunnelType"] as? String)?.trim() ?: existing.dnsTunnelType,
                                kcpPassword = (body["kcpPassword"] as? String) ?: existing.kcpPassword,
                                kcpCrypt = (body["kcpCrypt"] as? String)?.trim() ?: existing.kcpCrypt,
                                kcpNoDelay = (body["kcpNoDelay"] as? Boolean) ?: existing.kcpNoDelay,
                                kcpDataShards = (body["kcpDataShards"] as? Number)?.toInt() ?: existing.kcpDataShards,
                                kcpParityShards = (body["kcpParityShards"] as? Number)?.toInt() ?: existing.kcpParityShards,
                                udpCustomPsk = (body["udpCustomPsk"] as? String) ?: existing.udpCustomPsk,
                                udpCustomMagic = (body["udpCustomMagic"] as? String)?.trim() ?: existing.udpCustomMagic,
                                dnsOverride = (body["dnsOverride"] as? Boolean) ?: existing.dnsOverride,
                                remoteDns = (body["remoteDns"] as? String)?.trim() ?: existing.remoteDns,
                                localDns = (body["localDns"] as? String)?.trim() ?: existing.localDns,
                                udpgwVersion = (body["udpgwVersion"] as? String) ?: existing.udpgwVersion,
                                udpgwAddr = (body["udpgwAddr"] as? String)?.trim() ?: existing.udpgwAddr,
                                geositeDirect = (body["geositeDirect"] as? String)?.trim() ?: existing.geositeDirect,
                                geoipDirect = (body["geoipDirect"] as? String)?.trim() ?: existing.geoipDirect,
                                appFilterOverride = (body["appFilterOverride"] as? Boolean) ?: existing.appFilterOverride,
                                filterMode = (body["filterMode"] as? Number)?.toInt() ?: existing.filterMode,
                                filterApps = (body["filterApps"] as? String)?.trim() ?: existing.filterApps
                            )

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

                    // ── 节点延迟测速 API ──
                    post("/api/profiles/ping") {
                        if (!call.checkToken(appContext)) return@post
                        try {
                            val body = try { call.receive<Map<String, String>>() } catch (_: Exception) { emptyMap() }
                            val targetId = body["id"]?.trim()

                            val profiles = if (!targetId.isNullOrBlank()) {
                                val p = ProfileManager.getProfileById(appContext, targetId)
                                if (p != null) listOf(p) else emptyList()
                            } else {
                                ProfileManager.getProfiles(appContext)
                            }

                            if (profiles.isEmpty()) {
                                return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No profiles found to ping"))
                            }

                            val resultsMap = withContext(Dispatchers.IO) {
                                val reqArray = org.json.JSONArray()
                                profiles.forEach { p ->
                                    val configJson = VpnConfigBuilder.buildMySshConfig(appContext, p, 1080, 53)
                                    reqArray.put(org.json.JSONObject().put("id", p.id).put("config", org.json.JSONObject(configJson)))
                                }

                                val jsonResStr = try {
                                    StunRepository.proxy.pingNodes(
                                        reqArray.toString(),
                                        "http://cp.cloudflare.com/generate_204",
                                        8000L
                                    )
                                } catch (e: Exception) {
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

                                        val display = if (ok) {
                                            "$latencyMs ms"
                                        } else {
                                            when (errType) {
                                                "timeout" -> "Timeout"
                                                "connrefused" -> "Refused"
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

                    // ── 加密 / 明文 导入 API ──
                    post("/api/profiles/import") {
                        if (!call.checkToken(appContext)) return@post
                        try {
                            val body = call.receive<Map<String, String>>()
                            val content = body["content"]?.trim() ?: return@post call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "empty_content", "message" to "导入内容不能为空")
                            )
                            val pin = body["pin"]?.trim() ?: ""

                            val isEncrypted = ShareCryptoUtils.isEncryptedPayload(content)
                            val jsonToParse = if (isEncrypted) {
                                if (pin.isBlank()) {
                                    return@post call.respond(
                                        HttpStatusCode.BadRequest,
                                        mapOf("error" to "pin_required", "message" to "检测到加密分享码/备份，请输入6位PIN码")
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

                    get("/api/app-icon") {
                        if (!call.checkToken(appContext)) return@get
                        val pkg = call.parameters["pkg"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                        try {
                            val pm = appContext.packageManager
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
                        val pm = appContext.packageManager
                        val filterApps = SettingsManager.getFilterApps(appContext).split(",").filter { it.isNotBlank() }.toSet()
                        val installedPackages = pm.getInstalledPackages(0)

                        val appsList = installedPackages
                            .filter { it.packageName != appContext.packageName }
                            .mapNotNull { pkg ->
                                try {
                                    val appInfo = pkg.applicationInfo ?: return@mapNotNull null
                                    val appName = pm.getApplicationLabel(appInfo).toString()
                                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                                    val versionName = pkg.versionName ?: ""
                                    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                        pkg.longVersionCode
                                    } else {
                                        @Suppress("DEPRECATION")
                                        pkg.versionCode.toLong()
                                    }

                                    mapOf(
                                        "packageName" to pkg.packageName,
                                        "appName" to appName,
                                        "versionName" to versionName,
                                        "versionCode" to versionCode,
                                        "isSystem" to isSystem,
                                        "isSelected" to filterApps.contains(pkg.packageName)
                                    )
                                } catch (_: Exception) {
                                    null
                                }
                            }.sortedWith(compareBy({ it["isSystem"] as Boolean }, { (it["appName"] as String).lowercase() }))

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
                            "effectiveUrl" to fullUrl
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
                                "effectiveUrl" to newUrl
                            ))
                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Save failed")))
                        }
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
                }
            }.start(wait = false)

            val fullUrl = getEffectiveUrl(context, actualPort)
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
