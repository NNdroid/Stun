package app.fjj.stun.repo

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.io.File
import java.net.URL
import kotlin.concurrent.thread

object SettingsManager {
    private const val PREF_NAME = "stun_settings"
    private const val KEY_LOG_LEVEL = "log_level"
    private const val KEY_REMOTE_DNS_SERVER = "remote_dns_server"
    private const val KEY_LOCAL_DNS_SERVER = "local_dns_server"
    private const val KEY_UDPGW_VERSION = "udpgw_version"
    private const val KEY_UDPGW_ADDR = "udpgw_addr"
    private const val KEY_SELECTED_PROFILE_ID = "selected_profile_id"
    
    private const val KEY_GEOSITE_URL = "geosite_url"
    private const val KEY_GEOIP_URL = "geoip_url"
    private const val KEY_UPDATE_INTERVAL = "update_interval"
    private const val KEY_GEOSITE_DIRECT = "geosite_direct"
    private const val KEY_GEOIP_DIRECT = "geoip_direct"
    private const val KEY_LAST_UPDATE_TIME = "last_update_time"
    private const val KEY_FILTER_APPS = "filter_apps"
    private const val KEY_FILTER_MODE = "filter_mode"
    private const val KEY_SERVICE_MODE = "service_mode"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_SHOW_NOTIFICATION_SPEED = "show_notification_speed"

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

    const val DEFAULT_LOG_LEVEL = "INFO"
    const val DEFAULT_REMOTE_DNS_SERVER = "doh://8.8.8.8/dns-query"
    const val DEFAULT_LOCAL_DNS_SERVER = "doh://223.5.5.5/dns-query"
    const val DEFAULT_UDPGW_VERSION = "tun2proxy"
    const val DEFAULT_UDPGW_ADDR = "127.0.0.1:7300"
    const val DEFAULT_GEOSITE_URL = "https://cdn.jsdelivr.net/gh/Loyalsoldier/v2ray-rules-dat@release/geosite.dat"
    const val DEFAULT_GEOIP_URL = "https://cdn.jsdelivr.net/gh/Loyalsoldier/v2ray-rules-dat@release/geoip.dat"
    const val DEFAULT_UPDATE_INTERVAL = 86400L // 24 hours
    const val DEFAULT_GEOSITE_DIRECT_FLAGS = "cn,apple"
    const val DEFAULT_GEOIP_DIRECT_FLAGS = "cn,private"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
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

    fun getSelectedProfileId(context: Context): String? = getPrefs(context).getString(KEY_SELECTED_PROFILE_ID, null)
    fun setSelectedProfileId(context: Context, id: String) = getPrefs(context).edit { putString(KEY_SELECTED_PROFILE_ID, id) }

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

    fun getLastUpdateTime(context: Context): Long = getPrefs(context).getLong(KEY_LAST_UPDATE_TIME, 0L)
    fun saveLastUpdateTime(context: Context, time: Long) = getPrefs(context).edit { putLong(KEY_LAST_UPDATE_TIME, time) }

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

    fun updateGeoData(context: Context, onComplete: (() -> Unit)? = null) {
        thread {
            try {
                updateGeoDataSync(context)
                onComplete?.invoke()
            } catch (e: Exception) {
                // Already logged in updateGeoDataSync
            }
        }
    }

    private fun downloadFile(urlStr: String, destPath: String) {
        if (urlStr.isBlank()) return
        val url = URL(urlStr)
        url.openStream().use { input ->
            File(destPath).outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }
}
