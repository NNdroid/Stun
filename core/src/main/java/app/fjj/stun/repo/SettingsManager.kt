package app.fjj.stun.repo

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.io.File
import java.net.URL
import app.fjj.stun.backup.SettingsBackupCodec
import app.fjj.stun.util.KeystoreUtils
import kotlin.concurrent.thread

object SettingsManager {
    private const val TAG = "SettingsManager"
    private const val PREF_NAME = "stun_settings"

    /**
     * 设备态库：**永不参与云备份**。
     * 把「只属于本机」的状态物理隔离在这里，备份链路就再也不用维护排除清单 ——
     * 以后新增设备态字段直接写这个库即可，零改动。
     */
    private const val DEVICE_PREF_NAME = "stun_device_state"
    private const val KEY_DEVICE_STATE_MIGRATED = "device_state_migrated_v1"

    private const val KEY_LOG_LEVEL = "log_level"
    private const val KEY_REMOTE_DNS_SERVER = "remote_dns_server"
    private const val KEY_LOCAL_DNS_SERVER = "local_dns_server"
    private const val KEY_UDPGW_VERSION = "udpgw_version"
    private const val KEY_UDPGW_ADDR = "udpgw_addr"
    private const val KEY_SELECTED_PROFILE_ID = "selected_profile_id"
    
    private const val KEY_GEOSITE_URL = "geosite_url"
    private const val KEY_GEOIP_URL = "geoip_url"

    // 地球（连接详情 3D 视图）用的 City 级地理库。
    // 与上面的 geoip.dat **不是一回事**：那份只给 Go 侧做分流（国家 + CIDR，无坐标），
    // 这份要带经纬度给 Kotlin 侧画点。两份文件、两套 URL，别互相顶掉。
    private const val KEY_GEO_CITY_IPV4_URL = "geo_city_ipv4_url"
    private const val KEY_GEO_CITY_IPV6_URL = "geo_city_ipv6_url"
    private const val KEY_UPDATE_INTERVAL = "update_interval"
    private const val KEY_GEOSITE_DIRECT = "geosite_direct"
    private const val KEY_GEOIP_DIRECT = "geoip_direct"
    private const val KEY_LAST_UPDATE_TIME = "last_update_time"
    private const val KEY_FILTER_APPS = "filter_apps"
    private const val KEY_FILTER_MODE = "filter_mode"
    private const val KEY_SERVICE_MODE = "service_mode"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_SHOW_NOTIFICATION_SPEED = "show_notification_speed"

    // 带宽测速（下行/上行）配置项：默认走 Cloudflare speed 端点，可在设置中覆盖
    private const val KEY_SPEED_TEST_DOWN_URL = "speed_test_down_url"
    private const val KEY_SPEED_TEST_UP_URL = "speed_test_up_url"
    private const val KEY_SPEED_TEST_DOWN_BYTES = "speed_test_down_bytes"
    private const val KEY_SPEED_TEST_UP_BYTES = "speed_test_up_bytes"
    private const val KEY_SPEED_TEST_TIMEOUT_MS = "speed_test_timeout_ms"

    // Web Console Authentication Modes
    const val WEB_AUTH_MODE_RANDOM = 0     // 每次启动随机生成 (Random on Start)
    const val WEB_AUTH_MODE_PERMANENT = 1  // 永久固定生成一次 (Permanent Token)
    const val WEB_AUTH_MODE_CUSTOM = 2     // 自定义密码 (Custom Token)
    const val WEB_AUTH_MODE_DISABLED = 3   // 关闭认证 (局域网免Token访问)

    private const val KEY_WEB_AUTH_MODE = "web_auth_mode"
    private const val KEY_WEB_PERMANENT_TOKEN = "web_permanent_token"
    private const val KEY_WEB_CUSTOM_TOKEN = "web_custom_token"

    const val SERVICE_MODE_VPN = 0
    const val SERVICE_MODE_TPROXY = 1

    private const val KEY_MCP_SERVER_ENABLED = "mcp_server_enabled"
    private const val KEY_MCP_SERVER_PORT = "mcp_server_port"
    const val DEFAULT_MCP_SERVER_PORT = 37180

    const val DEFAULT_LOG_LEVEL = "INFO"
    const val DEFAULT_REMOTE_DNS_SERVER = "doh://8.8.8.8/dns-query"
    const val DEFAULT_LOCAL_DNS_SERVER = "doh://223.5.5.5/dns-query"
    const val DEFAULT_UDPGW_VERSION = "tun2proxy"
    const val DEFAULT_UDPGW_ADDR = "127.0.0.1:7300"
    const val DEFAULT_GEOSITE_URL = "https://cdn.jsdelivr.net/gh/Loyalsoldier/v2ray-rules-dat@release/geosite.dat"
    const val DEFAULT_GEOIP_URL = "https://cdn.jsdelivr.net/gh/Loyalsoldier/v2ray-rules-dat@release/geoip.dat"

    // 地球离线地理库（sapics 的 City 级 mmdb，派生自 GeoLite2 → **CC BY-SA 4.0，展示处需署名**）。
    // 双栈各一份且互不通用（实测 IPv4 库查不了 IPv6 地址、反之也查不到），所以两份都要。
    // 默认走 GitHub Release：国内不通时在设置里换镜像即可（URL 可配就是为这个留的）。
    const val GEO_CITY_IPV4_FILE = "geoip-city-ipv4.mmdb"
    const val GEO_CITY_IPV6_FILE = "geoip-city-ipv6.mmdb"
    const val DEFAULT_GEO_CITY_IPV4_URL =
        "https://github.com/sapics/ip-location-db/releases/download/latest/geolite2-city-ipv4.mmdb"
    const val DEFAULT_GEO_CITY_IPV6_URL =
        "https://github.com/sapics/ip-location-db/releases/download/latest/geolite2-city-ipv6.mmdb"

    /** 地理库下载：几十兆的东西，连接卡住不能把界面永远挂在"下载中"。 */
    private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 15_000
    private const val DOWNLOAD_READ_TIMEOUT_MS = 30_000

    /** 进度回调最小步长，避免每读 64KB 就发一次 UI 更新。 */
    private const val DOWNLOAD_PROGRESS_STEP_BYTES = 256L * 1024L

    const val DEFAULT_UPDATE_INTERVAL = 86400L // 24 hours
    const val DEFAULT_GEOSITE_DIRECT_FLAGS = "cn,apple"
    const val DEFAULT_GEOIP_DIRECT_FLAGS = "cn,private"

    const val DEFAULT_SPEED_TEST_DOWN_URL = "https://speed.cloudflare.com/__down?bytes=10485760"
    const val DEFAULT_SPEED_TEST_UP_URL = "https://speed.cloudflare.com/__up"
    const val DEFAULT_SPEED_TEST_DOWN_BYTES = 10485760L // 10 MiB 下行样本，低速节点也能在阶段超时内完成
    const val DEFAULT_SPEED_TEST_UP_BYTES = 10485760L    // 10 MiB 上行样本
    const val DEFAULT_SPEED_TEST_TIMEOUT_MS = 30000L

    /** 可迁移设置库（全量参与云备份）。 */
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /** 设备态库原始句柄（不触发迁移，避免自递归）。 */
    private fun getDevicePrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(DEVICE_PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 设备态读写的统一入口：保证一次性迁移已执行。
     * 迁移后这里只是「一次内存标记读」，几乎零成本；谁先碰设备态谁负责触发，
     * 因此不依赖 Application 在什么时机初始化。
     */
    private fun devicePrefs(context: Context): SharedPreferences {
        ensureDeviceStateMigrated(context)
        return getDevicePrefs(context)
    }

    /**
     * 设备态库的对外入口，供「只属于本机、绝不参与云备份」的状态使用
     * （例如出口 IP 探测结果：换机后毫无意义，且属于隐私数据）。
     *
     * 有意开放而不是让调用方各自 new 一个 prefs 文件：设备态写进这个库，
     * 备份链路从结构上就覆盖不到，永远不需要维护排除清单。
     */
    fun deviceState(context: Context): SharedPreferences = devicePrefs(context)

    /**
     * 一次性迁移清单：这几个键历史上存在 `stun_settings` 里，现已归属设备态库。
     *
     * 这是**冻结的迁移常量**，不是需要长期维护的分类清单 —— 迁移跑完后它不再变化。
     * 以后新增设备态字段请直接写 [getDevicePrefs]，**不要**往这里加。
     */
    private val LEGACY_DEVICE_LOCAL_KEYS = listOf(
        KEY_SELECTED_PROFILE_ID, KEY_LAST_UPDATE_TIME, KEY_WEBDAV_LAST, KEY_WEBDAV_PIN,
    )

    /**
     * 把历史遗留的设备态键从 `stun_settings` 搬到 `stun_device_state`。
     *
     * 幂等：首次之后只是一次内存读。顺序上先 `commit()` 新库、成功后再从旧库删除，
     * 保证任何时刻都不会出现「两处都没有」。若只需删除旧值而新库已有值，则跳过不覆盖。
     */
    private fun ensureDeviceStateMigrated(context: Context) {
        val device = getDevicePrefs(context)
        if (device.getBoolean(KEY_DEVICE_STATE_MIGRATED, false)) return

        val legacy = getPrefs(context)
        val legacyAll = legacy.all
        val editor = device.edit()
        LEGACY_DEVICE_LOCAL_KEYS.forEach { key ->
            if (device.contains(key)) return@forEach
            when (val value = legacyAll[key]) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                else -> Unit
            }
        }
        editor.putBoolean(KEY_DEVICE_STATE_MIGRATED, true)
        if (editor.commit()) {
            legacy.edit { LEGACY_DEVICE_LOCAL_KEYS.forEach { remove(it) } }
            StunLogger.i(TAG, "Device-scoped settings migrated to $DEVICE_PREF_NAME (removed from backup snapshot)")
        }
    }

    fun getLogLevel(context: Context): String = getPrefs(context).getString(KEY_LOG_LEVEL, DEFAULT_LOG_LEVEL) ?: DEFAULT_LOG_LEVEL
    fun saveLogLevel(context: Context, level: String) {
        getPrefs(context).edit { putString(KEY_LOG_LEVEL, level) }
        StunLogger.setLogLevel(level)
    }

    fun getRemoteDnsServer(context: Context): String = getPrefs(context).getString(KEY_REMOTE_DNS_SERVER, DEFAULT_REMOTE_DNS_SERVER) ?: DEFAULT_REMOTE_DNS_SERVER
    fun saveRemoteDnsServer(context: Context, dns: String) = getPrefs(context).edit { putString(KEY_REMOTE_DNS_SERVER, dns) }

    fun getLocalDnsServer(context: Context): String = getPrefs(context).getString(KEY_LOCAL_DNS_SERVER, DEFAULT_LOCAL_DNS_SERVER) ?: DEFAULT_LOCAL_DNS_SERVER
    fun saveLocalDnsServer(context: Context, dns: String) = getPrefs(context).edit { putString(KEY_LOCAL_DNS_SERVER, dns) }

    fun getUdpgwVersion(context: Context): String = getPrefs(context).getString(KEY_UDPGW_VERSION, DEFAULT_UDPGW_VERSION) ?: DEFAULT_UDPGW_VERSION
    fun saveUdpgwVersion(context: Context, version: String) = getPrefs(context).edit { putString(KEY_UDPGW_VERSION, version) }

    fun getUdpgwAddr(context: Context): String = getPrefs(context).getString(KEY_UDPGW_ADDR, DEFAULT_UDPGW_ADDR) ?: DEFAULT_UDPGW_ADDR
    fun saveUdpgwAddr(context: Context, addr: String) = getPrefs(context).edit { putString(KEY_UDPGW_ADDR, addr) }

    // 设备态：本机当前选中的节点，不参与云备份
    fun getSelectedProfileId(context: Context): String? = devicePrefs(context).getString(KEY_SELECTED_PROFILE_ID, null)
    fun setSelectedProfileId(context: Context, id: String) = devicePrefs(context).edit { putString(KEY_SELECTED_PROFILE_ID, id) }

    fun isMcpServerEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_MCP_SERVER_ENABLED, false)
    fun setMcpServerEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit { putBoolean(KEY_MCP_SERVER_ENABLED, enabled) }

    fun getMcpServerPort(context: Context): Int = getPrefs(context).getInt(KEY_MCP_SERVER_PORT, DEFAULT_MCP_SERVER_PORT)
    fun setMcpServerPort(context: Context, port: Int) = getPrefs(context).edit { putInt(KEY_MCP_SERVER_PORT, port) }

    // MCP Server Authentication Modes
    const val MCP_AUTH_MODE_NONE = 0       // 免认证 (Open / No Auth)
    const val MCP_AUTH_MODE_API_KEY = 1    // API Key / Bearer Token 静态密钥
    const val MCP_AUTH_MODE_BASIC = 2      // HTTP Basic 账号密码
    const val MCP_AUTH_MODE_OAUTH = 3      // OAuth 2.0 (Client Credentials / Authorization Code)

    private const val KEY_MCP_AUTH_MODE = "mcp_auth_mode"
    private const val KEY_MCP_API_KEY = "mcp_api_key"
    private const val KEY_MCP_BASIC_USER = "mcp_basic_user"
    private const val KEY_MCP_BASIC_PASS = "mcp_basic_pass"
    private const val KEY_MCP_OAUTH_CLIENT_ID = "mcp_oauth_client_id"
    private const val KEY_MCP_OAUTH_CLIENT_SECRET = "mcp_oauth_client_secret"

    fun getMcpAuthMode(context: Context): Int = getPrefs(context).getInt(KEY_MCP_AUTH_MODE, MCP_AUTH_MODE_NONE)
    fun setMcpAuthMode(context: Context, mode: Int) = getPrefs(context).edit { putInt(KEY_MCP_AUTH_MODE, mode) }

    fun getMcpApiKey(context: Context): String {
        val prefs = getPrefs(context)
        var key = prefs.getString(KEY_MCP_API_KEY, null)
        if (key.isNullOrBlank()) {
            key = "stun_key_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            prefs.edit { putString(KEY_MCP_API_KEY, key) }
        }
        return key
    }
    fun setMcpApiKey(context: Context, key: String) = getPrefs(context).edit { putString(KEY_MCP_API_KEY, key.trim()) }

    fun getMcpBasicUser(context: Context): String = getPrefs(context).getString(KEY_MCP_BASIC_USER, "admin") ?: "admin"
    fun setMcpBasicUser(context: Context, user: String) = getPrefs(context).edit { putString(KEY_MCP_BASIC_USER, user.trim()) }

    fun getMcpBasicPass(context: Context): String {
        val prefs = getPrefs(context)
        var pass = prefs.getString(KEY_MCP_BASIC_PASS, null)
        if (pass.isNullOrBlank()) {
            pass = "stun_" + java.util.UUID.randomUUID().toString().replace("-", "").take(8)
            prefs.edit { putString(KEY_MCP_BASIC_PASS, pass) }
        }
        return pass
    }
    fun setMcpBasicPass(context: Context, pass: String) = getPrefs(context).edit { putString(KEY_MCP_BASIC_PASS, pass.trim()) }

    fun getMcpOAuthClientId(context: Context): String = getPrefs(context).getString(KEY_MCP_OAUTH_CLIENT_ID, "stun-client") ?: "stun-client"
    fun setMcpOAuthClientId(context: Context, id: String) = getPrefs(context).edit { putString(KEY_MCP_OAUTH_CLIENT_ID, id.trim()) }

    fun getMcpOAuthClientSecret(context: Context): String {
        val prefs = getPrefs(context)
        var secret = prefs.getString(KEY_MCP_OAUTH_CLIENT_SECRET, null)
        if (secret.isNullOrBlank()) {
            secret = "stun_sec_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            prefs.edit { putString(KEY_MCP_OAUTH_CLIENT_SECRET, secret) }
        }
        return secret
    }
    fun setMcpOAuthClientSecret(context: Context, secret: String) = getPrefs(context).edit { putString(KEY_MCP_OAUTH_CLIENT_SECRET, secret.trim()) }

    // ── Database WebUI (:dbwebui module, standalone admin service on its own port) ──
    const val DEFAULT_DB_WEB_PORT = 38180
    private const val KEY_DB_WEB_ENABLED = "db_web_enabled"
    private const val KEY_DB_WEB_PORT = "db_web_port"
    private const val KEY_DB_WEB_USER = "db_web_user"
    private const val KEY_DB_WEB_PASS = "db_web_pass"   // Keystore-encrypted at rest

    fun isDbWebEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_DB_WEB_ENABLED, false)
    fun setDbWebEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit { putBoolean(KEY_DB_WEB_ENABLED, enabled) }

    // ── 订阅后台自动同步开关（SubscriptionSyncWorker 每 15 分钟按此门控，默认开） ──
    private const val KEY_SUB_AUTO_SYNC = "subscription_auto_sync_enabled"
    fun isSubscriptionAutoSyncEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_SUB_AUTO_SYNC, true)
    fun setSubscriptionAutoSyncEnabled(context: Context, enabled: Boolean) =
        getPrefs(context).edit { putBoolean(KEY_SUB_AUTO_SYNC, enabled) }

    // ── 连接拓扑"星空模式"（默认关 = 现有扁平配色） ──
    private const val KEY_GLOBE_STARRY = "globe_starry_mode"
    fun isGlobeStarryMode(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_GLOBE_STARRY, false)
    fun setGlobeStarryMode(context: Context, enabled: Boolean) =
        getPrefs(context).edit { putBoolean(KEY_GLOBE_STARRY, enabled) }

    fun getDbWebPort(context: Context): Int = getPrefs(context).getInt(KEY_DB_WEB_PORT, DEFAULT_DB_WEB_PORT)
    fun setDbWebPort(context: Context, port: Int) = getPrefs(context).edit { putInt(KEY_DB_WEB_PORT, port) }

    fun getDbWebUser(context: Context): String = getPrefs(context).getString(KEY_DB_WEB_USER, "admin") ?: "admin"
    fun setDbWebUser(context: Context, user: String) = getPrefs(context).edit { putString(KEY_DB_WEB_USER, user.trim()) }

    // Login password is encrypted via Keystore (like the WebDAV pass). A random default
    // is generated on first read so the service is never exposed with an empty password.
    fun getDbWebPass(context: Context): String {
        val prefs = getPrefs(context)
        val stored = prefs.getString(KEY_DB_WEB_PASS, "") ?: ""
        if (stored.isNotEmpty()) {
            val decrypted = KeystoreUtils.decrypt(stored)
            if (decrypted.isNotEmpty()) return decrypted
        }
        val generated = "stun_" + java.util.UUID.randomUUID().toString().replace("-", "").take(10)
        prefs.edit { putString(KEY_DB_WEB_PASS, KeystoreUtils.encrypt(generated)) }
        return generated
    }
    fun setDbWebPass(context: Context, pass: String) =
        getPrefs(context).edit { putString(KEY_DB_WEB_PASS, KeystoreUtils.encrypt(pass.trim())) }

    // GeoData Settings
    fun getGeositeUrl(context: Context): String = getPrefs(context).getString(KEY_GEOSITE_URL, DEFAULT_GEOSITE_URL) ?: DEFAULT_GEOSITE_URL
    fun saveGeositeUrl(context: Context, url: String) = getPrefs(context).edit { putString(KEY_GEOSITE_URL, url) }

    fun getGeoipUrl(context: Context): String = getPrefs(context).getString(KEY_GEOIP_URL, DEFAULT_GEOIP_URL) ?: DEFAULT_GEOIP_URL
    fun saveGeoipUrl(context: Context, url: String) = getPrefs(context).edit { putString(KEY_GEOIP_URL, url) }

    fun getUpdateInterval(context: Context): Long = getPrefs(context).getLong(KEY_UPDATE_INTERVAL, DEFAULT_UPDATE_INTERVAL)
    fun saveUpdateInterval(context: Context, interval: Long) = getPrefs(context).edit { putLong(KEY_UPDATE_INTERVAL, interval) }

    fun getGeositeDirect(context: Context): String = getPrefs(context).getString(KEY_GEOSITE_DIRECT, DEFAULT_GEOSITE_DIRECT_FLAGS) ?: DEFAULT_GEOSITE_DIRECT_FLAGS
    fun saveGeositeDirect(context: Context, flags: String) = getPrefs(context).edit { putString(KEY_GEOSITE_DIRECT, flags) }

    fun getGeoipDirect(context: Context): String = getPrefs(context).getString(KEY_GEOIP_DIRECT, DEFAULT_GEOIP_DIRECT_FLAGS) ?: DEFAULT_GEOIP_DIRECT_FLAGS
    fun saveGeoipDirect(context: Context, flags: String) = getPrefs(context).edit { putString(KEY_GEOIP_DIRECT, flags) }

    // 设备态：本机上次 GeoData 更新时间，不参与云备份
    fun getLastUpdateTime(context: Context): Long = devicePrefs(context).getLong(KEY_LAST_UPDATE_TIME, 0L)
    fun saveLastUpdateTime(context: Context, time: Long) = devicePrefs(context).edit { putLong(KEY_LAST_UPDATE_TIME, time) }

    fun getFilterApps(context: Context): String = getPrefs(context).getString(KEY_FILTER_APPS, "") ?: ""
    fun saveFilterApps(context: Context, apps: String) = getPrefs(context).edit { putString(KEY_FILTER_APPS, apps) }

    fun getFilterMode(context: Context): Int = getPrefs(context).getInt(KEY_FILTER_MODE, 0)
    fun saveFilterMode(context: Context, mode: Int) = getPrefs(context).edit { putInt(KEY_FILTER_MODE, mode) }

    fun getServiceMode(context: Context): Int = getPrefs(context).getInt(KEY_SERVICE_MODE, SERVICE_MODE_VPN)
    fun saveServiceMode(context: Context, mode: Int) = getPrefs(context).edit { putInt(KEY_SERVICE_MODE, mode) }

    fun getLanguage(context: Context): String = getPrefs(context).getString(KEY_LANGUAGE, "auto") ?: "auto"
    fun saveLanguage(context: Context, lang: String) = getPrefs(context).edit { putString(KEY_LANGUAGE, lang) }

    fun getShowNotificationSpeed(context: Context): Boolean = getPrefs(context).getBoolean(KEY_SHOW_NOTIFICATION_SPEED, true)
    fun saveShowNotificationSpeed(context: Context, enabled: Boolean) = getPrefs(context).edit { putBoolean(KEY_SHOW_NOTIFICATION_SPEED, enabled) }

    // ── 带宽测速（下行/上行，经节点隧道真实吞吐）配置 ──
    fun getSpeedTestDownUrl(context: Context): String =
        getPrefs(context).getString(KEY_SPEED_TEST_DOWN_URL, DEFAULT_SPEED_TEST_DOWN_URL) ?: DEFAULT_SPEED_TEST_DOWN_URL
    fun saveSpeedTestDownUrl(context: Context, url: String) = getPrefs(context).edit { putString(KEY_SPEED_TEST_DOWN_URL, url) }

    fun getSpeedTestUpUrl(context: Context): String =
        getPrefs(context).getString(KEY_SPEED_TEST_UP_URL, DEFAULT_SPEED_TEST_UP_URL) ?: DEFAULT_SPEED_TEST_UP_URL
    fun saveSpeedTestUpUrl(context: Context, url: String) = getPrefs(context).edit { putString(KEY_SPEED_TEST_UP_URL, url) }

    fun getSpeedTestDownBytes(context: Context): Long =
        getPrefs(context).getLong(KEY_SPEED_TEST_DOWN_BYTES, DEFAULT_SPEED_TEST_DOWN_BYTES)
    fun saveSpeedTestDownBytes(context: Context, bytes: Long) = getPrefs(context).edit { putLong(KEY_SPEED_TEST_DOWN_BYTES, bytes) }

    fun getSpeedTestUpBytes(context: Context): Long =
        getPrefs(context).getLong(KEY_SPEED_TEST_UP_BYTES, DEFAULT_SPEED_TEST_UP_BYTES)
    fun saveSpeedTestUpBytes(context: Context, bytes: Long) = getPrefs(context).edit { putLong(KEY_SPEED_TEST_UP_BYTES, bytes) }

    fun getSpeedTestTimeoutMs(context: Context): Long =
        getPrefs(context).getLong(KEY_SPEED_TEST_TIMEOUT_MS, DEFAULT_SPEED_TEST_TIMEOUT_MS)
    fun saveSpeedTestTimeoutMs(context: Context, ms: Long) = getPrefs(context).edit { putLong(KEY_SPEED_TEST_TIMEOUT_MS, ms) }

    fun getWebAuthMode(context: Context): Int = getPrefs(context).getInt(KEY_WEB_AUTH_MODE, WEB_AUTH_MODE_RANDOM)
    fun saveWebAuthMode(context: Context, mode: Int) = getPrefs(context).edit { putInt(KEY_WEB_AUTH_MODE, mode) }

    fun getWebPermanentToken(context: Context): String {
        var token = getPrefs(context).getString(KEY_WEB_PERMANENT_TOKEN, "") ?: ""
        if (token.isBlank()) {
            val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            token = (1..8).map { chars.random() }.joinToString("")
            getPrefs(context).edit { putString(KEY_WEB_PERMANENT_TOKEN, token) }
        }
        return token
    }

    fun getWebCustomToken(context: Context): String = getPrefs(context).getString(KEY_WEB_CUSTOM_TOKEN, "") ?: ""
    fun saveWebCustomToken(context: Context, token: String) = getPrefs(context).edit { putString(KEY_WEB_CUSTOM_TOKEN, token) }

    fun getGeositeCachePath(context: Context): String = File(context.cacheDir, "geosite.dat").absolutePath
    fun getGeoipCachePath(context: Context): String = File(context.cacheDir, "geoip.dat").absolutePath

    // ---- 地球离线地理库（mmdb）----
    //
    // 与上面那对 .dat 分开维护：那份是给 Go 侧分流的（只有国家 + CIDR），这份带经纬度、只给地球画点。
    // 两份都放 cacheDir：它们是**可重新下载**的数据，不该占 filesDir / 更不该进云备份。
    // 代价是系统清缓存会把它们清掉 —— 那时 [geoCityAllReady] 变 false，界面回到"未下载"状态，重新下即可。

    /** 地球需要的那两份库。 */
    enum class GeoCityDb(val fileName: String) {
        IPV4(GEO_CITY_IPV4_FILE),
        IPV6(GEO_CITY_IPV6_FILE),
    }

    fun geoCityFile(context: Context, db: GeoCityDb): File = File(context.cacheDir, db.fileName)

    fun getGeoCityUrl(context: Context, db: GeoCityDb): String {
        val (key, default) = when (db) {
            GeoCityDb.IPV4 -> KEY_GEO_CITY_IPV4_URL to DEFAULT_GEO_CITY_IPV4_URL
            GeoCityDb.IPV6 -> KEY_GEO_CITY_IPV6_URL to DEFAULT_GEO_CITY_IPV6_URL
        }
        return getPrefs(context).getString(key, default) ?: default
    }

    fun saveGeoCityUrl(context: Context, db: GeoCityDb, url: String) {
        val key = when (db) {
            GeoCityDb.IPV4 -> KEY_GEO_CITY_IPV4_URL
            GeoCityDb.IPV6 -> KEY_GEO_CITY_IPV6_URL
        }
        getPrefs(context).edit { putString(key, url) }
    }

    /** 这份库在本机是否可用（存在且非空）。 */
    fun geoCityReady(context: Context, db: GeoCityDb): Boolean =
        geoCityFile(context, db).let { it.isFile && it.length() > 0L }

    /** 两份都在。false ⇒ 地球点不出位置，界面该显示"下载地理库"。 */
    fun geoCityAllReady(context: Context): Boolean = GeoCityDb.entries.all { geoCityReady(context, it) }

    /** 还缺哪几份。 */
    fun missingGeoCityDbs(context: Context): List<GeoCityDb> =
        GeoCityDb.entries.filterNot { geoCityReady(context, it) }

    /**
     * 把 [dbs] 下到 cacheDir 并原子替换；默认只补**缺**的那几份（已下过 IPv4 就不会因为补 IPv6 而重下 26MB）。
     *
     * 每份都先落 `.tmp`、确认非空后再 `renameTo` —— 复用 geoip.dat 那套形状：
     * 下载中途断网时，旧文件必须原样可用，绝不能被写坏一半。
     *
     * ⚠️ **部分成功是允许的**：先下成功的几份已经落盘可用，后面某份失败才抛异常。
     * 调用方拿到异常时应该重新查 [missingGeoCityDbs]，而不是假定"全都没下成"。
     *
     * @param onProgress `(哪份库, 已读字节, 总字节)`；服务端没给 Content-Length 时总字节为 -1。
     *   回调在**调用线程**上执行 —— 会在界面里的话请自己切主线程。
     * @return 实际写入的库。
     */
    fun downloadGeoCitySync(
        context: Context,
        dbs: List<GeoCityDb> = missingGeoCityDbs(context),
        onProgress: ((GeoCityDb, Long, Long) -> Unit)? = null,
    ): List<GeoCityDb> {
        val written = mutableListOf<GeoCityDb>()
        for (db in dbs) {
            val target = geoCityFile(context, db)
            val temp = File(context.cacheDir, "${db.fileName}.tmp")
            try {
                downloadFile(getGeoCityUrl(context, db), temp.absolutePath) { read, total ->
                    onProgress?.invoke(db, read, total)
                }
                if (!temp.isFile || temp.length() <= 0L) {
                    throw IllegalStateException("downloaded ${db.fileName} is empty")
                }
                // renameTo 在目标已存在时行为依平台而异，先删掉再改名
                target.delete()
                if (!temp.renameTo(target)) {
                    throw IllegalStateException("cannot replace ${target.absolutePath}")
                }
                written += db
                StunLogger.i(TAG, "geo city db ready: ${db.fileName} (${target.length()} bytes)")
            } catch (e: Exception) {
                StunLogger.e(TAG, "download geo city db failed: ${db.fileName}", e)
                throw e
            } finally {
                temp.delete()
            }
        }
        return written
    }

    /** [downloadGeoCitySync] 的后台版本；[onComplete] 也在**后台线程**回调，入参为 null 表示成功。 */
    fun downloadGeoCity(
        context: Context,
        dbs: List<GeoCityDb> = missingGeoCityDbs(context),
        onProgress: ((GeoCityDb, Long, Long) -> Unit)? = null,
        onComplete: ((Throwable?) -> Unit)? = null,
    ) {
        thread {
            val error = runCatching { downloadGeoCitySync(context, dbs, onProgress) }.exceptionOrNull()
            onComplete?.invoke(error)
        }
    }

    fun getGeositeDirectTags(context: Context) : List<String> {
        return getGeositeDirect(context).split(",")
    }

    fun getGeoipDirectTags(context: Context) : List<String> {
        return getGeoipDirect(context).split(",")
    }

    fun checkAndUpdateGeoData(context: Context) {
        val geositeFile = File(getGeositeCachePath(context))
        val geoipFile = File(getGeoipCachePath(context))

        // If files don't exist, run update immediately once
        if (!geositeFile.exists() || !geoipFile.exists()) {
            app.fjj.stun.worker.GeoDataWorker.runOnceNow(context)
        }

        // Schedule periodic updates
        app.fjj.stun.worker.GeoDataWorker.schedule(context)
    }

    fun updateGeoDataSync(context: Context) {
        val tempGeositePath = "${getGeositeCachePath(context)}.tmp"
        val tempGeoipPath = "${getGeoipCachePath(context)}.tmp"

        try {
            // Download to temporary files first
            downloadFile(getGeositeUrl(context), tempGeositePath)
            downloadFile(getGeoipUrl(context), tempGeoipPath)

            val tempGeositeFile = File(tempGeositePath)
            val tempGeoipFile = File(tempGeoipPath)

            // Validate: Both files must exist and have content
            if (tempGeositeFile.exists() && tempGeositeFile.length() > 0 &&
                tempGeoipFile.exists() && tempGeoipFile.length() > 0) {
                
                // Atomically (well, as close as possible) replace the old files
                tempGeositeFile.renameTo(File(getGeositeCachePath(context)))
                tempGeoipFile.renameTo(File(getGeoipCachePath(context)))

                val currentTime = System.currentTimeMillis() / 1000
                saveLastUpdateTime(context, currentTime)
                StunLogger.i("SettingsManager", "GeoData update completed and replaced successfully.")
            } else {
                throw RuntimeException("Downloaded GeoData files are empty or missing")
            }
        } catch (e: Exception) {
            StunLogger.e("SettingsManager", "Update GeoData failed, keeping original files", e)
            throw e
        } finally {
            // Clean up temporary files if they still exist
            File(tempGeositePath).delete()
            File(tempGeoipPath).delete()
        }
    }

    fun updateGeoData(context: Context, onComplete: ((Throwable?) -> Unit)? = null) {
        thread {
            try {
                updateGeoDataSync(context)
                onComplete?.invoke(null)
            } catch (e: Exception) {
                // Already logged in updateGeoDataSync
                onComplete?.invoke(e)
            }
        }
    }

    /**
     * 下载到 [destPath]，返回写入的字节数。
     *
     * [onProgress] 以 `(已读字节, 总字节)` 回调，服务端没给 Content-Length 时总字节为 -1。
     * 带连接 / 读取超时：地理库动辄几十兆，卡住的连接不能把界面永远挂在"下载中"。
     * URL 为空时返回 0 —— 保持历史行为，让调用方按"空文件"报错，而不是在这里抛。
     */
    private fun downloadFile(
        urlStr: String,
        destPath: String,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Long {
        if (urlStr.isBlank()) return 0L
        val connection = URL(urlStr).openConnection()
        connection.connectTimeout = DOWNLOAD_CONNECT_TIMEOUT_MS
        connection.readTimeout = DOWNLOAD_READ_TIMEOUT_MS
        connection.useCaches = false
        // GitHub Release 对没有 UA 的请求不太友好；顺手带上，对 jsDelivr 也无害
        connection.setRequestProperty("User-Agent", "Stun-Android")

        var read = 0L
        connection.getInputStream().use { input ->
            File(destPath).outputStream().use { output ->
                val total = connection.contentLengthLong
                val buffer = ByteArray(64 * 1024)
                var lastReported = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    output.write(buffer, 0, count)
                    read += count
                    if (onProgress != null && read - lastReported >= DOWNLOAD_PROGRESS_STEP_BYTES) {
                        lastReported = read
                        onProgress(read, total)
                    }
                }
                // 收尾补一次，让进度条能真正走到 100%
                if (onProgress != null && read > lastReported) onProgress(read, total)
            }
        }
        return read
    }

    // ── WebDAV 云备份配置（敏感值 Keystore 加密存储） ──
    private const val KEY_WEBDAV_URL = "webdav_url"
    private const val KEY_WEBDAV_USER = "webdav_user"
    private const val KEY_WEBDAV_PASS = "webdav_pass"
    private const val KEY_WEBDAV_PIN = "webdav_pin"
    private const val KEY_WEBDAV_AUTO = "webdav_auto"
    private const val KEY_WEBDAV_LAST = "webdav_last_backup"
    private const val KEY_WEBDAV_INTERVAL_H = "webdav_interval_hours"

    fun getWebDavUrl(context: Context): String = getPrefs(context).getString(KEY_WEBDAV_URL, "") ?: ""
    fun getWebDavUser(context: Context): String = getPrefs(context).getString(KEY_WEBDAV_USER, "") ?: ""
    /** Keystore 加密存储，读取时解密；密钥不可用（如异地恢复）返回空 */
    fun getWebDavPass(context: Context): String = KeystoreUtils.decrypt(getPrefs(context).getString(KEY_WEBDAV_PASS, "") ?: "")
    fun getWebDavPin(context: Context): String = KeystoreUtils.decrypt(devicePrefs(context).getString(KEY_WEBDAV_PIN, "") ?: "")
    fun isWebDavAutoBackupEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_WEBDAV_AUTO, false)
    // 设备态：上次备份时间，不参与云备份
    fun getWebDavLastBackupTime(context: Context): Long = devicePrefs(context).getLong(KEY_WEBDAV_LAST, 0L)

    /**
     * 服务器凭据进可迁移库（pass 密文经快照自动解密导出）；
     * 备份 PIN 进设备态库 —— 它是「备份文件的钥匙」，从结构上就进不了自己的备份。
     */
    fun saveWebDavConfig(context: Context, url: String, user: String, pass: String, pin: String) {
        getPrefs(context).edit {
            putString(KEY_WEBDAV_URL, url.trim())
            putString(KEY_WEBDAV_USER, user.trim())
            putString(KEY_WEBDAV_PASS, KeystoreUtils.encrypt(pass))
        }
        devicePrefs(context).edit { putString(KEY_WEBDAV_PIN, KeystoreUtils.encrypt(pin)) }
    }

    /**
     * 首启默认备份 PIN：从未配置过时生成随机 6 位「字母+数字」（大写、剔除
     * I/O/0/1 易混淆字符——它要在新设备上手工重输），Keystore 加密落盘 →
     * WebDAV 自动备份开箱即配（只差服务器凭据）。
     * 只认「存储值是否为空」：密文存在但解密失败（如 Keystore 重置）时**不**静默
     * 换新——云上的旧备份仍按旧 PIN 加密，覆盖生成会让用户对不上号；
     * 那种场景让用户手动重输旧 PIN 或显式改 PIN。
     * Keystore 不可用时 encrypt 返回 ""，此时不写入（避免存了个空 PIN 还以为成功）。
     */
    fun ensureWebDavPin(context: Context): String? {
        val prefs = devicePrefs(context)
        if (!prefs.getString(KEY_WEBDAV_PIN, "").isNullOrBlank()) return null
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // 无 I O 0 1
        val random = java.security.SecureRandom()
        val generated = (1..6).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        val encrypted = KeystoreUtils.encrypt(generated)
        if (encrypted.isBlank()) return null
        prefs.edit { putString(KEY_WEBDAV_PIN, encrypted) }
        return generated
    }

    fun setWebDavAutoBackup(context: Context, enabled: Boolean) {
        getPrefs(context).edit { putBoolean(KEY_WEBDAV_AUTO, enabled) }
    }

    // 自动备份间隔（小时）。WorkManager 调度与列表状态点（绿/红）都以它为基准。
    fun getWebDavBackupIntervalHours(context: Context): Long =
        getPrefs(context).getLong(KEY_WEBDAV_INTERVAL_H, 24L)

    fun saveWebDavBackupIntervalHours(context: Context, hours: Long) {
        if (hours in 1..720) getPrefs(context).edit { putLong(KEY_WEBDAV_INTERVAL_H, hours) }
    }

    // 设备态：上次备份时间，写路径同样落设备态库
    fun saveWebDavLastBackupTime(context: Context, time: Long) {
        devicePrefs(context).edit { putLong(KEY_WEBDAV_LAST, time) }
    }

    // ── WebDAV 设置备份 ──

    /**
     * `stun_settings` 全量快照。
     *
     * **不需要任何「哪些键要备份」的清单**，这是本次改造的核心：
     * - 遍历全部键 ⇒ 新增普通设置字段**自动纳入**，零代码改动；
     * - 类型标签由 [SettingsBackupCodec] 从运行时值推导（防 Gson 把数字统一变 Double）；
     * - 密钥字段由 `ENC:` 前缀**自动识别**：解出明文导出，恢复时本机重新加密，
     *   因此新增密钥字段也不再需要特判；
     * - 设备态根本不在这个库里（见 [DEVICE_PREF_NAME]），从结构上就无法泄漏。
     *
     * 唯一无法分类的值会打告警，杜绝「将来某个字段静默漏备份」。
     */
    fun webDavSettingsSnapshot(context: Context): Map<String, Map<String, Any?>> {
        // 迁移未跑完前，设备态键还留在本库，必须先搬走再导出
        ensureDeviceStateMigrated(context)
        val out = LinkedHashMap<String, Map<String, Any?>>()
        getPrefs(context).all.forEach { (key, value) ->
            val entry = SettingsBackupCodec.encode(value) { KeystoreUtils.decrypt(it) }
            if (entry == null) {
                StunLogger.w(TAG, "Backup snapshot: unsupported value type for key=$key, skipped (check the new field's type)")
                return@forEach
            }
            if (SettingsBackupCodec.isKeystoreCipher(value as? String) &&
                !SettingsBackupCodec.isEncrypted(entry)
            ) {
                StunLogger.w(TAG, "Backup snapshot: key=$key is a secret field but cannot be decrypted on this device, exported as-is (may not restore on another device)")
            }
            out[key] = entry
        }
        return out
    }

    /**
     * 应用云端设置快照。
     *
     * 条目带 `enc` 标记时用本机 Keystore 重新加密后落盘；
     * 条目未标记、但本机该键**原本就是密文**时同样重新加密（兼容历史备份里
     * `webdav_pass` 以明文形态导出的旧格式）。加密失败宁可跳过，也不落明文。
     */
    fun applyWebDavSettingsSnapshot(context: Context, snapshot: Map<String, Map<String, Any?>>) {
        val prefs = getPrefs(context)
        val existing = prefs.all
        prefs.edit {
            snapshot.forEach { (key, entry) ->
                // 历史备份可能残留设备态键（旧版未排除 last_update_time 等），
                // 一律不回流到可迁移库；新增设备态字段本就不会出现在快照里，无需扩表。
                if (key in LEGACY_DEVICE_LOCAL_KEYS) return@forEach
                val decoded = SettingsBackupCodec.decode(
                    entry = entry,
                    encryptSecret = { KeystoreUtils.encrypt(it) },
                    forceEncrypt = SettingsBackupCodec.isKeystoreCipher(existing[key] as? String),
                )
                if (decoded == null) {
                    StunLogger.w(TAG, "Restore settings: entry for key=$key corrupt/unknown type/encryption failed, skipped")
                    return@forEach
                }
                when (decoded.type) {
                    SettingsBackupCodec.STRING -> putString(key, decoded.value as String)
                    SettingsBackupCodec.BOOL -> putBoolean(key, decoded.value as Boolean)
                    SettingsBackupCodec.INT -> putInt(key, (decoded.value as Number).toInt())
                    SettingsBackupCodec.LONG -> putLong(key, (decoded.value as Number).toLong())
                    SettingsBackupCodec.FLOAT -> putFloat(key, (decoded.value as Number).toFloat())
                    SettingsBackupCodec.STRING_SET -> putStringSet(
                        key,
                        (decoded.value as? Set<*>)?.filterIsInstance<String>()?.toSet().orEmpty(),
                    )
                }
            }
        }
    }
}
