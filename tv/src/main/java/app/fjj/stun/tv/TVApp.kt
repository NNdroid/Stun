package app.fjj.stun.tv

import android.app.Application
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.util.ExecUtils
import app.fjj.stun.util.KeystoreUtils
import app.fjj.stun.util.LocaleHelper
import com.google.android.material.color.DynamicColors

class TVApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Multi-language support
        LocaleHelper.applyLocale(this)

        StunLogger.init(this)
        
        // --- Fatal Crash Logger ---
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            StunLogger.e("FATAL", "Uncaught Exception in TV App [Thread: ${thread.name}]", throwable)
            // Give the logger thread a moment to write to disk
            Thread.sleep(500)
            defaultHandler?.uncaughtException(thread, throwable)
        }
        
        KeystoreUtils.init(this)
        
        // Setup bridge to UI LiveData
        app.fjj.stun.repo.StunRepository.setupLogBridge()

        // Deploy assets (geoip.dat, geosite.dat, etc.)
        initAssets()

        // Trigger GeoData auto-update check on startup
        SettingsManager.checkAndUpdateGeoData(this)
    }

    private fun initAssets() {
        val lastUpdate = SettingsManager.getLastUpdateTime(this)
        val apkUpdateTime = try {
            val info = packageManager.getPackageInfo(packageName, 0)
            info.lastUpdateTime / 1000
        } catch (_: Exception) {
            0L
        }

        if (lastUpdate <= 0 || apkUpdateTime > lastUpdate) {
            StunLogger.i("TVApp", "App updated or first run, deploying geo assets...")
            ExecUtils.copyAssetToCache(this, "rules-dat/geoip.dat", "geoip.dat")
            ExecUtils.copyAssetToCache(this, "rules-dat/geosite.dat", "geosite.dat")
            
            if (lastUpdate > 0) {
                SettingsManager.saveLastUpdateTime(this, apkUpdateTime)
            }
        }
    }
}

