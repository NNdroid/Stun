package app.fjj.stun.wear

import android.app.Application
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.util.ExecUtils
import app.fjj.stun.util.KeystoreUtils
import app.fjj.stun.util.LocaleHelper
import myssh.LogReceiver

class WearApp : Application() {
    private var goLogReceiver: LogReceiver? = null

    override fun onCreate() {
        super.onCreate()

        LocaleHelper.applyLocale(this)

        initLogger()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            StunLogger.e("FATAL", "Uncaught Exception in Wear App [Thread: ${thread.name}]", throwable)
            Thread.sleep(500)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        KeystoreUtils.init(this)

        StunRepository.setupLogBridge()
        StunRepository.registerEngineCallback()
        StunRepository.initCrashOutput(this)

        initAssets()

        SettingsManager.checkAndUpdateGeoData(this)
    }

    private fun initLogger() {
        try {
            StunLogger.init(this)
            val logPath = StunRepository.getTunnelLogFilePath(this)
            val logLevel = SettingsManager.getLogLevel(this)
            StunLogger.setLogLevel(logLevel)

            goLogReceiver = object : LogReceiver {
                override fun receive(level: Long, tag: String, msg: String) {
                    StunLogger.receiveGoLog(level.toInt(), tag, msg)
                }
            }

            myssh.Myssh.setLogReceiver(goLogReceiver)
            val res = myssh.Myssh.initLogger(logPath, logLevel)

            if (res == 0L) {
                StunLogger.i("WearApp", "✅ Go logger initialized successfully.")
            } else {
                StunLogger.e("WearApp", "❌ Go logger initialization FAILED with code: $res")
            }
        } catch (e: Exception) {
            StunLogger.e("WearApp", "Error initializing Wear logger", e)
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
            StunLogger.i("WearApp", "Deploying geo assets for Wear...")
            ExecUtils.copyAssetToCache(this, "rules-dat/geoip.dat", "geoip.dat")
            ExecUtils.copyAssetToCache(this, "rules-dat/geosite.dat", "geosite.dat")

            if (lastUpdate > 0) {
                SettingsManager.saveLastUpdateTime(this, apkUpdateTime)
            }
        }
    }
}
