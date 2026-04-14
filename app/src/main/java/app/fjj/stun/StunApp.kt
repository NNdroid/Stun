package app.fjj.stun

import android.app.Application
import android.content.Context
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.repo.StunRepository
import java.io.File

class StunApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize StunLogger
        initLogger(this@StunApp)
        app.fjj.stun.util.KeystoreUtils.init(this@StunApp)
        
        // Setup bridge to UI LiveData
        app.fjj.stun.repo.StunRepository.setupLogBridge()
        
        StunLogger.i("StunApp", "Application initialized and StunLogger started.")

        // Trigger GeoData update check on startup
        SettingsManager.checkAndUpdateGeoData(this@StunApp)
    }

    override fun onTerminate() {
        super.onTerminate()
    }

    private fun initLogger(context: Context) {
        val logPath = StunRepository.getTunnelLogFilePath(context)
        val logLevel = SettingsManager.getLogLevel(context)
        // 1. 实现接口
        val goLogReceiver = object : myssh.LogReceiver {
            override fun receive(level: Long, tag: String, msg: String) {
                // 直接调用你原来的 StunLogger
                // 注意：Go 的 int 在 Java 中会变成 Long
                StunLogger.receiveGoLog(level.toInt(), tag, msg)
            }
        }
        // 2. 注入并启动
        // 建议在 VpnService.onCreate() 或 Application.onCreate() 中调用
        myssh.Myssh.setLogReceiver(goLogReceiver)
        myssh.Myssh.initLogger(logPath, logLevel)
    }
}
