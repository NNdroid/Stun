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

        // Initialize StunLogger
        initLogger(this@StunApp)
        app.fjj.stun.util.KeystoreUtils.init(this@StunApp)
        // Setup bridge to UI LiveData
        app.fjj.stun.repo.StunRepository.setupLogBridge()
        initAssets(this@StunApp)
        // Trigger GeoData update check on startup
        SettingsManager.checkAndUpdateGeoData(this@StunApp)
    }

    private fun initLogger(context: Context) {
        StunLogger.init(context)
        val logPath = StunRepository.getTunnelLogFilePath(context)
        val logLevel = SettingsManager.getLogLevel(context)
        // 实现接口
        val goLogReceiver = LogReceiver { level, tag, msg ->
            // 注意：Go 的 int 在 Java 中会变成 Long
            StunLogger.receiveGoLog(level.toInt(), tag, msg)
        }
        // 注入并启动
        myssh.Myssh.setLogReceiver(goLogReceiver)
        myssh.Myssh.initLogger(logPath, logLevel)
    }

    private fun initAssets(context: Context) {
        ExecUtils.binaryDeploy(context, "hev-socks5-tproxy")
        ExecUtils.scriptDeploy(context, "tproxy.sh")
        ExecUtils.scriptDeploy(context, "watchdog.sh")
    }
}
