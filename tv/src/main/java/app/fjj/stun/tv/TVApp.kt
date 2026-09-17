package app.fjj.stun.tv

import android.app.Application
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.util.AppBootstrap
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
        
        // --- Fatal Crash Logger & Recorder ---
        app.fjj.stun.util.CrashHandler.init(this)
        
        KeystoreUtils.init(this)
        
        // Setup bridge to UI LiveData
        app.fjj.stun.repo.StunRepository.setupLogBridge()
        app.fjj.stun.repo.StunRepository.registerEngineCallback()
        app.fjj.stun.repo.StunRepository.initCrashOutput(this)

        // Deploy assets (geoip.dat, geosite.dat, etc.)
        // 从前这里是 TV 独有的**主线程同步**实现：铺 12.9MB + 因为判定口径的 bug 每次冷启动都重铺，
        // 是 5 个变体里唯一真会卡住启动的一个。现统一交给 AppBootstrap（IO + 内含就绪门，
        // 服务侧会 awaitAssets），实现只此一处。
        AppBootstrap.start(this)

        // Start Bluetooth Sync Server for phone-to-tv remote control & node push
        app.fjj.stun.remote.BluetoothSyncManager.startServer(this)

        // Start MCP Server for TV AI agent remote control (Claude / Cursor / Gemini)
        try {
            app.fjj.stun.remote.StunMcpServer.start(this)
        } catch (e: Throwable) {
            StunLogger.e("TVApp", "Failed to start StunMcpServer", e)
        }

        // GeoData auto-update check 由 AppBootstrap 在部署完成后负责（它的前提是文件已就位）。
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
}

