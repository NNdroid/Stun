package app.fjj.stun.xr

import android.app.Application
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.util.AppBootstrap
import app.fjj.stun.util.KeystoreUtils
import app.fjj.stun.util.LocaleHelper
import myssh.LogReceiver

class XRApp : Application() {
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
        // Keystore 首次生成可达百 ms 级，挪出主线程（与手机端 StunApp 同理）
        Thread { runCatching { KeystoreUtils.init(this) } }.apply { isDaemon = true; start() }
        runCatching {
            StunRepository.setupLogBridge()
            StunRepository.registerEngineCallback()
            StunRepository.initCrashOutput(this)
        }

        // Multi-MB geo asset copies must not run on the main thread (ANR on XR hardware).
        // 交给 AppBootstrap：IO + 判定只此一处（规则库不再看 last_update_time，见 needsDeploy），
        // 规则库更新检查也由它在部署完成后做 —— 部署还在跑时去问「文件在不在」只会白排下载。
        AppBootstrap.start(this)
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
                StunLogger.i("XRApp", "✅ Go logger initialized successfully.")
            } else {
                StunLogger.e("XRApp", "❌ Go logger initialization FAILED with code: $res")
            }
        } catch (e: Exception) {
            StunLogger.e("XRApp", "Error initializing XR logger", e)
        }
    }
}
