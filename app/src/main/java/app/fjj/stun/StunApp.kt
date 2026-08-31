package app.fjj.stun

import android.app.Application
import android.content.Context
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.util.ExecUtils
import myssh.LogReceiver

class StunApp : Application() {
    override fun attachBaseContext(base: Context) {
        // We rely on AppCompatDelegate.setApplicationLocales for Activities.
        // For non-UI strings in Application context, we can still use wrapContext,
        // but it's often safer to avoid it if setApplicationLocales is used to prevent conflicts.
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        
        // Modern way to set app-wide locale (AppCompat 1.6.0+)
        app.fjj.stun.util.LocaleHelper.applyLocale(this)

        // Enable Material 3 Dynamic Colors globally
        com.google.android.material.color.DynamicColors.applyToActivitiesIfAvailable(this)

        // Initialize StunLogger
        initLogger(this@StunApp)
        app.fjj.stun.util.KeystoreUtils.init(this@StunApp)
        // Setup bridge to UI LiveData
        app.fjj.stun.repo.StunRepository.setupLogBridge()
        app.fjj.stun.repo.StunRepository.registerEngineCallback()
        app.fjj.stun.repo.StunRepository.initCrashOutput(this@StunApp)
        initAssets(this@StunApp)
        // Trigger GeoData update check on startup
        SettingsManager.checkAndUpdateGeoData(this@StunApp)
    }

    private fun initLogger(context: Context) {
        StunLogger.init(context)
        val logPath = StunRepository.getTunnelLogFilePath(context)
        val logLevel = SettingsManager.getLogLevel(context)
        StunLogger.setLogLevel(logLevel)
        val goLogReceiver = LogReceiver { level, tag, msg ->
            // 注意：Go 的 int 在 Java 中会变成 Long
            StunLogger.receiveGoLog(level.toInt(), tag, msg)
        }
        myssh.Myssh.setLogReceiver(goLogReceiver)
        myssh.Myssh.initLogger(logPath, logLevel)
    }

    private fun initAssets(context: Context) {
        ExecUtils.binaryDeploy(context, "hev-socks5-tproxy")
        ExecUtils.scriptDeploy(context, "tproxy.sh")
        ExecUtils.scriptDeploy(context, "watchdog.sh")

        val lastUpdate = SettingsManager.getLastUpdateTime(context)
        val apkUpdateTime = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.lastUpdateTime / 1000
        } catch (e: Exception) {
            0L
        }

        // Deploy assets if it's the first run or if the APK has been updated since the last deployment.
        if (lastUpdate <= 0 || apkUpdateTime > lastUpdate) {
            app.fjj.stun.repo.StunLogger.i("StunApp", "App updated or first run, redeploying assets...")
            ExecUtils.copyAssetToCache(context, "rules-dat/geoip.dat", "geoip.dat")
            ExecUtils.copyAssetToCache(context, "rules-dat/geosite.dat", "geosite.dat")
            
            // Update last update time only if it's an overwrite from APK update
            if (lastUpdate > 0) {
                SettingsManager.saveLastUpdateTime(context, apkUpdateTime)
            }
        }
    }
}
