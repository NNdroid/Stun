package app.fjj.stun.tv

import android.app.Application
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.util.ExecUtils
import app.fjj.stun.util.KeystoreUtils
import app.fjj.stun.util.LocaleHelper
import com.google.android.material.color.DynamicColors
import myssh.LogReceiver

class TVApp : Application() {
    private var goLogReceiver: LogReceiver? = null

    override fun onCreate() {
        super.onCreate()
        
        // Multi-language support
        LocaleHelper.applyLocale(this)

        initLogger()
        
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
        app.fjj.stun.repo.StunRepository.registerEngineCallback()
        app.fjj.stun.repo.StunRepository.initCrashOutput(this)

        // Deploy assets (geoip.dat, geosite.dat, etc.)
        initAssets()

        // Trigger GeoData auto-update check on startup
        SettingsManager.checkAndUpdateGeoData(this)
    }

    private fun initLogger() {
        try {
            StunLogger.init(this)
            val logPath = StunRepository.getTunnelLogFilePath(this)
            val logLevel = SettingsManager.getLogLevel(this)
            StunLogger.setLogLevel(logLevel)
            
            StunLogger.i("TVApp", "Initializing Go bridge... Path: $logPath, Level: $logLevel")
            
            // Explicitly implement LogReceiver to be safe and ensure strong reference
            goLogReceiver = object : LogReceiver {
                override fun receive(level: Long, tag: String, msg: String) {
                    StunLogger.receiveGoLog(level.toInt(), tag, msg)
                }
            }
            
            myssh.Myssh.setLogReceiver(goLogReceiver)
            val res = myssh.Myssh.initLogger(logPath, logLevel)
            
            if (res == 0L) {
                StunLogger.i("TVApp", "✅ Go logger initialized successfully.")
            } else {
                StunLogger.e("TVApp", "❌ Go logger initialization FAILED with code: $res")
            }
            
            StunLogger.i("TVApp", "Log system bridge established (PID: ${android.os.Process.myPid()})")
        } catch (e: Exception) {
            StunLogger.e("TVApp", "Fatal error during logger init", e)
        }
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

