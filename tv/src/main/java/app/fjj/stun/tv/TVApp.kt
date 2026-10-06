package app.fjj.stun.tv

import android.app.Application
import android.os.Process
import app.fjj.stun.remote.BluetoothSyncManager
import app.fjj.stun.remote.RemoteControlHost
import app.fjj.stun.remote.RemoteSyncManager
import app.fjj.stun.remote.TvStatusSource
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.util.AppBootstrap
import app.fjj.stun.util.CrashHandler
import app.fjj.stun.util.KeystoreUtils
import app.fjj.stun.util.LocaleHelper
import com.google.android.material.color.DynamicColors
import myssh.LogReceiver

class TVApp : Application() {
    private var goLogReceiver: LogReceiver? = null

    override fun onCreate() {
        super.onCreate()

        LocaleHelper.applyLocale(this)

        initLogger()

        CrashHandler.init(this)

        KeystoreUtils.init(this)

        // Setup bridge to UI LiveData
        StunRepository.setupLogBridge()
        StunRepository.registerEngineCallback(this)
        StunRepository.initCrashOutput(this)

        // Deploy assets (geoip.dat, geosite.dat, etc.)
        // 从前这里是 TV 独有的**主线程同步**实现：铺 12.9MB + 因为判定口径的 bug 每次冷启动都重铺，
        // 是 5 个变体里唯一真会卡住启动的一个。现统一交给 AppBootstrap（IO + 内含就绪门，
        // 服务侧会 awaitAssets），实现只此一处。
        AppBootstrap.start(this)

        // 远程控制监听面（蓝牙 / 局域网同步 / MCP）统一交给 RemoteControlHost 在进程级启动：
        // 进程被系统回收后 Application.onCreate 一定重跑 ⇒ 监听面随之回来。
        // 之前局域网同步只在 MainActivity.onCreate 里起，而 WorkManager / Service 唤活进程
        // **不会**重建 Activity ⇒ 进程死过一次，手机远控就再也没回来过（"关掉 App 就没有了"）。
        // MCP 是否启动由 WebUI「启用 MCP 智能体服务」开关控制（与手机端 StunApp 同一语义）。
        RemoteControlHost.enableLanSync()

        // 远端查状态的**状态源**同样必须在进程级注册：监听面已经是进程级基础设施了，但状态源
        // 先前挂的是 `MainActivity::buildTvStatus`（一个捕获了 Activity 的 bound method reference），
        // 界面一销毁就被 `clearRemoteCallbacks()` 置 null。于是手机能连上、也能拿到 status:"ok"，
        // 内容却是兜底的 profileCount=0 / profiles 缺省 —— 表现成「只有打开了 App 才看得到节点」，
        // 附带把整个已销毁的 Activity 连同 ViewBinding 钉在进程级静态字段上（真泄漏）。
        val tvStatusProvider: () -> app.fjj.stun.remote.TvStatusResponse = { TvStatusSource.build(this) }
        RemoteSyncManager.tvStatusProvider = tvStatusProvider
        BluetoothSyncManager.tvStatusProvider = tvStatusProvider

        RemoteControlHost.startAll(this)

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

            StunLogger.i("TVApp", "Log system bridge established (PID: ${Process.myPid()})")
        } catch (e: Exception) {
            StunLogger.e("TVApp", "Fatal error during logger init", e)
        }
    }
}
