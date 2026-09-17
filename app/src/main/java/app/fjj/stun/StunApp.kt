package app.fjj.stun

import android.app.Application
import android.content.Context
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.repo.StunRepository
import kotlinx.coroutines.launch
import myssh.LogReceiver

class StunApp : Application() {
    private companion object {
        const val TAG = "StunApp"
    }

    override fun attachBaseContext(base: Context) {
        // We rely on AppCompatDelegate.setApplicationLocales for Activities.
        // For non-UI strings in Application context, we can still use wrapContext,
        // but it's often safer to avoid it if setApplicationLocales is used to prevent conflicts.
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()

        // 每一步都独立、且**失败也要继续**：某个可选能力起不来不该拖垮整个 App。
        // 但从前这里是 14 个 `catch (_: Throwable) {}`，出了问题一点痕迹都没有 ——
        // 最典型的就是 Keystore：init 失败后 aead 恒为 null，之后 22 处加解密全部抛
        // IllegalStateException，而日志里一个字都看不到。改成带步骤名记日志。
        startupStep("崩溃处理器") {
            // Initialize global crash handler to capture unhandled exceptions
            app.fjj.stun.util.CrashHandler.init(this@StunApp)
        }

        startupStep("应用语言") {
            // Modern way to set app-wide locale (AppCompat 1.6.0+)
            app.fjj.stun.util.LocaleHelper.applyLocale(this)
        }

        startupStep("Material 3 动态取色") {
            // Enable Material 3 Dynamic Colors globally
            com.google.android.material.color.DynamicColors.applyToActivitiesIfAvailable(this)
        }

        startupStep("日志系统") {
            initLogger(this@StunApp)
        }

        startupStep("Keystore 预热（IO）") {
            // Keystore 初始化 + 备份 PIN 兜底都挪到 IO 预热。前者要跟 AndroidKeyStore 打交道
            // （首次生成主密钥可达百 ms 级），后者只有首启会真的写一次 prefs。
            // 都不需要就绪门：KeystoreUtils.encrypt/decrypt 自带 ensureInitialized 自愈。
            app.fjj.stun.util.AppBootstrap.warmUpKeystore(this@StunApp)
        }

        startupStep("日志与崩溃回调桥") {
            // Setup bridge to UI LiveData
            app.fjj.stun.repo.StunRepository.setupLogBridge()
            app.fjj.stun.repo.StunRepository.registerEngineCallback()
            app.fjj.stun.repo.StunRepository.initCrashOutput(this@StunApp)
        }

        startupStep("资产部署（IO）") {
            // 资产部署（解压 ~12.9MB 规则库 + TProxy 三件套）也挪到 IO。
            // VPN 服务在碰 cacheDir 里这些文件之前会 AppBootstrap.awaitAssets()。
            // 规则库更新检查也归它管 —— 那个检查的前提是「文件已就位」，天然必须排在部署之后，
            // 拆成两步会踩到「部署还没跑完就被告知文件缺失 ⇒ 每次冷启动白排一个 13MB 下载」。
            app.fjj.stun.util.AppBootstrap.start(this@StunApp)
        }

        startupStep("全局配置下发") {
            // 向 myssh 灌入全局配置（geosite/geoip 缓存路径等）。tag 查询等
            // 非连接期的 Go 侧功能依赖 globalConfig 中的路径，若只在 VPN 启动
            // 时填充，进程刚启动时这些功能会回落到无效的相对路径。
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    // geosite/geoip 的路径要交给 Go 侧，所以这里对资产就绪有硬要求。
                    app.fjj.stun.util.AppBootstrap.awaitAssets(this@StunApp)
                    val profile = app.fjj.stun.repo.ProfileManager.getSelectedProfile(this@StunApp)
                    app.fjj.stun.repo.StunRepository.proxy.loadGlobalConfig(
                        app.fjj.stun.service.VpnConfigBuilder.buildGlobalConfig(this@StunApp, profile)
                    )
                } catch (t: Throwable) {
                    StunLogger.e(TAG, "Failed to push global config", t)
                }
            }
        }

        startupStep("MCP 服务") {
            // Start MCP Server if enabled in settings
            if (SettingsManager.isMcpServerEnabled(this@StunApp)) {
                app.fjj.stun.remote.StunMcpServer.start(this@StunApp)
            }
        }

        startupStep("数据库 WebUI 服务") {
            // Start the Database WebUI service (:dbwebui) if enabled in settings
            if (SettingsManager.isDbWebEnabled(this@StunApp)) {
                app.fjj.stun.dbwebui.DbWebServer.start(this@StunApp)
            }
        }

        startupStep("桌面快捷方式与小组件") {
            // Initialize Dynamic Launcher Shortcuts & Desktop App Widget
            app.fjj.stun.util.DynamicShortcutManager.updateShortcuts(this@StunApp)
            app.fjj.stun.widget.StunWidgets.refreshAll(this@StunApp)
            app.fjj.stun.repo.StunRepository.vpnState.observeForever {
                try {
                    app.fjj.stun.util.DynamicShortcutManager.updateShortcuts(this@StunApp)
                    app.fjj.stun.widget.StunWidgets.refreshAll(this@StunApp)
                } catch (t: Throwable) {
                    StunLogger.e(TAG, "Failed to refresh shortcuts/widgets on VPN state change", t)
                }
            }
            // 连接期间速率回调约每秒一次，节流到 2s 驱动小组件的速度/柱状图/延迟刷新
            var lastWidgetTick = 0L
            app.fjj.stun.repo.StunRepository.txRate.observeForever {
                try {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastWidgetTick >= 2000L) {
                        lastWidgetTick = now
                        app.fjj.stun.widget.StunWidgets.refreshAll(this@StunApp)
                    }
                } catch (t: Throwable) {
                    StunLogger.e(TAG, "Failed to refresh widget from speed callback", t)
                }
            }
        }

        startupStep("周期订阅同步调度") {
            // 周期订阅同步：按 profile-update-interval 自动刷新（最小 15min 间隔，首次对齐到边界）
            app.fjj.stun.worker.SubscriptionSyncWorker.schedule(this@StunApp)
        }
    }

    /**
     * 启动步骤统一包一层：失败**记日志但不中断**，取代从前那一排 `catch (_: Throwable) {}`。
     *
     * 之所以不中断：这些都是「App 主体能用」之外的可选能力（动态取色、MCP、小组件刷新…），
     * 一个起不来不该让用户连界面都进不去。但静默是另一回事 —— 症状会在几十行之外以
     * 完全无关的样子冒出来（见 onCreate 顶部 Keystore 那条注释）。
     */
    private fun startupStep(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            StunLogger.e(TAG, "Startup step failed: $name", t)
        }
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
}
