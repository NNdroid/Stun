package app.fjj.stun.car

import android.app.Application
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.util.AppBootstrap
import app.fjj.stun.util.KeystoreUtils
import app.fjj.stun.util.LocaleHelper
import myssh.LogReceiver

class CarApp : Application() {
    private var goLogReceiver: LogReceiver? = null

    override fun onCreate() {
        super.onCreate()

        // Guard every step like the phone app (StunApp) does: KeystoreUtils.init throws
        // RuntimeException on keystore failures and native lib load throws UnsatisfiedLinkError
        // (an Error, not an Exception) — unguarded, either one crashes the app before the
        // first activity is ever shown.
        runCatching { app.fjj.stun.util.CrashHandler.init(this) }
        runCatching { LocaleHelper.applyLocale(this) }
        runCatching { initLogger() }
        runCatching { KeystoreUtils.init(this) }
        runCatching {
            StunRepository.setupLogBridge()
            StunRepository.registerEngineCallback(this)
            StunRepository.initCrashOutput(this)
        }

        // Multi-MB geo asset copies must not run on the main thread (ANR on slow Car hardware).
        // 交给 AppBootstrap：IO + 判定只此一处（规则库不再看 last_update_time，见 needsDeploy），
        // 规则库更新检查也由它在部署完成后做 —— 部署还在跑时去问「文件在不在」只会白排下载。
        AppBootstrap.start(this)

        // 远程控制监听面（蓝牙 / 局域网同步）统一入口：进程被回收后随 Application 重建回来。
        // 之前蓝牙只在这一行起，进程被系统回收后没有前台保活服务把进程顶在 foreground 优先级，
        // 通道随进程一起消失，WorkManager / Service 唤活又不会重建 Activity ⇒ 车机远控彻底没了。
        runCatching {
            app.fjj.stun.remote.RemoteControlHost.enableLanSync()
            // 状态源必须进程级注册：原先挂在 CarMainActivity 上，界面一销毁就被置 null，
            // 手机只看到一份 profileCount=0 的空状态（重新打开界面又恢复，看着像「必须开着
            // App 才有数据」）。BT 是车机主用的通道，但 LAN 也一并注册 —— enableLanSync 就在
            // 上面，两个通道共享同一个数据源，不会出现一条有数据、另一条没有。
            val tvStatusProvider: () -> app.fjj.stun.remote.TvStatusResponse =
                { app.fjj.stun.remote.TvStatusSource.build(this) }
            app.fjj.stun.remote.BluetoothSyncManager.tvStatusProvider = tvStatusProvider
            app.fjj.stun.remote.RemoteSyncManager.tvStatusProvider = tvStatusProvider
            app.fjj.stun.remote.RemoteControlHost.startAll(this)
        }
    }

    private fun initLogger() {
        try {
            StunLogger.init(this)
            val logPath = StunRepository.getTunnelLogFilePath(this)
            val logLevel = SettingsManager.getLogLevel(this)
            StunLogger.setLogLevel(logLevel)

            StunLogger.i("CarApp", "Initializing Go bridge for Car... Path: $logPath, Level: $logLevel")

            goLogReceiver = object : LogReceiver {
                override fun receive(level: Long, tag: String, msg: String) {
                    StunLogger.receiveGoLog(level.toInt(), tag, msg)
                }
            }

            myssh.Myssh.setLogReceiver(goLogReceiver)
            val res = myssh.Myssh.initLogger(logPath, logLevel)

            if (res == 0L) {
                StunLogger.i("CarApp", "✅ Go logger initialized successfully.")
            } else {
                StunLogger.e("CarApp", "❌ Go logger initialization FAILED with code: $res")
            }
        } catch (e: Exception) {
            StunLogger.e("CarApp", "Error initializing Car logger", e)
        }
    }
}
