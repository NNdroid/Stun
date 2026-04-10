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
    private const val KEY_SELECTED_PROFILE_ID = "selected_profile_id"
    
    private const val KEY_GEOSITE_URL = "geosite_url"
    private const val KEY_GEOIP_URL = "geoip_url"
    private const val KEY_UPDATE_INTERVAL = "update_interval"
    private const val KEY_GEOSITE_DIRECT = "geosite_direct"
    private const val KEY_GEOIP_DIRECT = "geoip_direct"
    private const val KEY_LAST_UPDATE_TIME = "last_update_time"

    const val DEFAULT_LOG_LEVEL = "INFO"
    const val DEFAULT_REMOTE_DNS_SERVER = "doh://8.8.8.8/dns-query"
    const val DEFAULT_LOCAL_DNS_SERVER = "doh://223.5.5.5/dns-query"
    const val DEFAULT_GEOSITE_URL = "https://cdn.jsdelivr.net/gh/Loyalsoldier/v2ray-rules-dat@release/geosite.dat"
    const val DEFAULT_GEOIP_URL = "https://cdn.jsdelivr.net/gh/Loyalsoldier/v2ray-rules-dat@release/geoip.dat"
    const val DEFAULT_UPDATE_INTERVAL = 86400L // 24 hours
    const val DEFAULT_GEOSITE_DIRECT_FLAGS = "cn,apple"
    const val DEFAULT_GEOIP_DIRECT_FLAGS = "cn,private"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getLogLevel(context: Context): String = getPrefs(context).getString(KEY_LOG_LEVEL, DEFAULT_LOG_LEVEL) ?: DEFAULT_LOG_LEVEL
    fun saveLogLevel(context: Context, level: String) = getPrefs(context).edit { putString(KEY_LOG_LEVEL, level) }

    fun getRemoteDnsServer(context: Context): String = getPrefs(context).getString(KEY_REMOTE_DNS_SERVER, DEFAULT_REMOTE_DNS_SERVER) ?: DEFAULT_REMOTE_DNS_SERVER
    fun saveRemoteDnsServer(context: Context, dns: String) = getPrefs(context).edit { putString(KEY_REMOTE_DNS_SERVER, dns) }

    fun getLocalDnsServer(context: Context): String = getPrefs(context).getString(KEY_LOCAL_DNS_SERVER, DEFAULT_LOCAL_DNS_SERVER) ?: DEFAULT_LOCAL_DNS_SERVER
    fun saveLocalDnsServer(context: Context, dns: String) = getPrefs(context).edit { putString(KEY_LOCAL_DNS_SERVER, dns) }

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
        try {
            downloadFile(getGeositeUrl(context), getGeositeCachePath(context))
            downloadFile(getGeoipUrl(context), getGeoipCachePath(context))
            val currentTime = System.currentTimeMillis() / 1000
            saveLastUpdateTime(context, currentTime)
            StunLogger.i("SettingsManager", "GeoData update completed successfully.")
        } catch (e: Exception) {
            StunLogger.e("SettingsManager", "Update GeoData failed", e)
            throw e
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
        try {
            val url = URL(urlStr)
            url.openStream().use { input ->
                File(destPath).outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            StunLogger.e("SettingsManager", "Download file failed: $urlStr", e)
        }
    }
}
