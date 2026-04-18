package app.fjj.stun.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.fjj.stun.R
import app.fjj.stun.repo.*
import app.fjj.stun.util.ExecUtils
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * a root-based transparent proxy.
 * Uses iptables (via tproxy.sh) and hev-socks5-tproxy core.
 */
class MyTransparentProxyService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mainJob: Job? = null
    private var coreJob: Job? = null
    private val isRunning = AtomicBoolean(false)
    private val TAG: String
        get() = "TProxyService-[${Thread.currentThread().name}]"

    companion object {
        // Actions
        const val ACTION_START = "app.fjj.stun.ROOT_START"
        const val ACTION_STOP = "app.fjj.stun.ROOT_STOP"
        // Constants
        private const val CHANNEL_ID = "StunTransparentProxyChannel"
        private const val NOTIFICATION_ID = 3004
        private const val SOCKS_PORT = 10808
        private const val TPROXY_PORT = 10812
        private const val DNS_HIJACK_PORT = 10553

        private const val BIN_HEV_SOCKS5_TPROXY = "hev-socks5-tproxy"
        private const val FILE_HEV_SOCKS5_TPROXY_CONF = "hev-socks5-tproxy.conf"
        private const val FILE_HEV_SOCKS5_TPROXY_LOG = "tproxy.log"

        private const val SCRIPT_TPROXY = "tproxy.sh"
        private const val FILE_TPROXY_CONF = "tproxy.conf"

        private const val SCRIPT_WATCHDOG = "watchdog.sh"
        private const val FILE_WATCHDOG_LOG = "watchdog.log"
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(app.fjj.stun.util.LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        StunLogger.i(TAG, "Service onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        StunLogger.i(TAG, "Received intent action: ${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> stopTProxy(this@MyTransparentProxyService)
            else -> startTProxy(this@MyTransparentProxyService)
        }
        return START_STICKY
    }

    private fun startTProxy(context: Context) {
        StunLogger.i(TAG, "Attempting to start Transparent Proxy...")

        // 如果没有 Root 权限，必须调用 stopSelf() 结束服务，否则会引发系统 ANR / 崩溃
        if (!ExecUtils.checkIsRootPermission()) {
            StunLogger.e(TAG, "Root permission required but not granted. Stopping service.")
            stopSelf()
            return
        }

        // 使用原子操作检查并设置运行状态：如果已经是 true，直接返回
        if (!isRunning.compareAndSet(false, true)) {
            StunLogger.w(TAG, "Service is already running, ignoring start request.")
            return
        }

        StunRepository.vpnState.postValue(VpnState.CONNECTING)
        updateNotification(getString(R.string.main_connecting))

        mainJob = serviceScope.launch {
            try {
                StunLogger.i(TAG, "--- Start Sequence Initiated ---")

                // 0. 清理可能残留的旧防火墙规则
                StunLogger.i(TAG, "Step 0: Clearing legacy firewall rules...")
                applyRules(context, false)

                val profile = ProfileManager.getSelectedProfile(context)
                    ?: throw IllegalStateException("No profile selected")

                // 1. Start SSH Core (SOCKS5 Backend)
                StunLogger.i(TAG, "Step 1: Starting SSH Core backend...")
                myssh.Myssh.loadGlobalConfigFromJson(VpnConfigBuilder.buildGlobalConfig(context, profile))
                val sshStatus = myssh.Myssh.startSshTProxy2(VpnConfigBuilder.buildMySshConfig(context, profile, SOCKS_PORT))
                if (sshStatus != 0L) throw RuntimeException("SSH Core failed to start with status: $sshStatus")
                myssh.Myssh.startLocalDNSServer(DNS_HIJACK_PORT.toLong())
                StunLogger.i(TAG, "SSH Core started successfully.")

                // 2. Generate hev-socks5-tproxy config
                StunLogger.i(TAG, "Step 2: Generating hev-socks5-tproxy config...")
                val yamlConfig = TransparentProxyConfigBuilder.buildHevSocks5TProxyConfig(
                    context, profile, SOCKS_PORT, TPROXY_PORT, DNS_HIJACK_PORT
                )
                File(cacheDir, FILE_HEV_SOCKS5_TPROXY_CONF).writeText(yamlConfig)

                // 3. Start Core Engine
                StunLogger.i(TAG, "Step 3: Launching TProxy core engine...")
                startCoreEngine()

                // 【修复】：挂起协程 1000 毫秒，给底层 C/Go 核心程序分配端口和监听的时间，避免 iptables 导流过早导致断网
                StunLogger.i(TAG, "Waiting for core engine to bind ports...")
                delay(1000)

                // 4. Apply Firewall Rules
                StunLogger.i(TAG, "Step 4: Applying Iptables firewall rules...")
                applyRules(context, true)

                // 5. Update State & Notifications
                StunLogger.i(TAG, "Start Sequence Completed Successfully.")
                StunRepository.vpnState.postValue(VpnState.CONNECTED)
                updateNotification(getString(R.string.notif_text))

                // 6. System Optimizations
                optimizeSystemForBackground()
                applyShizukuOptimizations()

                startWatchdog()

                // Block and wait for core (监听 JNI 底层状态，阻塞当前协程)
                StunLogger.i(TAG, "Entering wgWait() block...")
                myssh.Myssh.wgWait()
                StunLogger.i(TAG, "wgWait() returned gracefully.")

            } catch (e: Exception) {
                StunLogger.e(TAG, "Critical Failure during start sequence: ${e.message}", e)
                // 启动失败时的自动回滚与清理
                stopTProxy(context)
            }
        }
    }

    private fun startCoreEngine() {
        coreJob = serviceScope.launch {
            val coreFile = File(cacheDir, BIN_HEV_SOCKS5_TPROXY)
            val configFile = File(cacheDir, FILE_HEV_SOCKS5_TPROXY_CONF)
            val logFile = File(cacheDir, FILE_HEV_SOCKS5_TPROXY_LOG)

            val cmd = "nohup ${coreFile.absolutePath} ${configFile.absolutePath} > ${logFile.absolutePath} 2>&1 &"
            StunLogger.i(TAG, "Executing Start hev-socks5-tproxy Cmd: $cmd")
            ExecUtils.executeRootCommand(cmd)
        }
    }

    private fun stopCoreEngine() {
        StunLogger.i(TAG, "Killing TProxy binary processes...")
        ExecUtils.executeRootCommand("killall -9 $BIN_HEV_SOCKS5_TPROXY || true")
    }

    private fun applyRules(context: Context, enabled: Boolean) {
        val cachePath = cacheDir.absolutePath
        val scriptFile = File(cacheDir, SCRIPT_TPROXY)
        if (enabled) {
            StunLogger.i(TAG, "Enabling TProxy firewall rules...")
            val shellConfig = TransparentProxyConfigBuilder.buildHevSocks5TProxyConfig(
                this, TPROXY_PORT, TPROXY_PORT, DNS_HIJACK_PORT
            )
            File(cacheDir, FILE_TPROXY_CONF).writeText(shellConfig)

            ExecUtils.executeRootCommand("${scriptFile.absolutePath} -d $cachePath ${(if (app.fjj.stun.BuildConfig.DEBUG) "--verbose" else "")} start")
        } else {
            StunLogger.i(TAG, "Disabling TProxy firewall rules...")
            ExecUtils.executeRootCommand("${scriptFile.absolutePath} -d $cachePath ${(if (app.fjj.stun.BuildConfig.DEBUG) "--verbose" else "")} stop")
        }
    }

    private fun stopTProxy(context: Context) {
        StunLogger.i(TAG, "Attempting to stop Transparent Proxy...")

        // 如果当前不是运行状态 (isRunning=false)，就提前返回。必须加 !
        if (!isRunning.compareAndSet(true, false)) {
            StunLogger.w(TAG, "Service is not running, ignoring stop request.")
            return
        }

        // 取消相关协程，停止产生新的指令
        mainJob?.cancel()
        coreJob?.cancel()
        StunLogger.i(TAG, "Jobs cancelled.")

        // 启动不可取消的清理协程，确保即使上层协程被取消，清理工作也能执行完毕
        serviceScope.launch(NonCancellable) {
            try {
                StunLogger.i(TAG, "--- Stop Sequence Initiated ---")

                stopWatchdog()

                // 1. 清除防火墙规则 (恢复系统网络)
                applyRules(context, false)

                // 2. 强杀底层的二进制进程
                stopCoreEngine()

                // 3. 停止 SSH 后端
                try {
                    StunLogger.i(TAG, "Stopping Local DNS Server...")
                    myssh.Myssh.stopLocalDNSServer()
                } catch (e: Exception) {
                    StunLogger.w(TAG, "Exception while stopping Local DNS Server: ${e.message}")
                }
                try {
                    StunLogger.i(TAG, "Stopping SSH Core...")
                    myssh.Myssh.stopSshTProxy()
                } catch (e: Exception) {
                    StunLogger.w(TAG, "Exception while stopping SSH Core: ${e.message}")
                }
            } finally {
                // 4. 重置状态并销毁 Service
                StunLogger.i(TAG, "Stop Sequence Completed. Tearing down service.")
                StunRepository.vpnState.postValue(VpnState.DISCONNECTED)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun optimizeSystemForBackground() {
        StunLogger.i(TAG, "Applying background execution optimizations...")
        ExecUtils.executeRootCommand("dumpsys deviceidle whitelist +$packageName")
        ExecUtils.executeRootCommand("appops set $packageName RUN_IN_BACKGROUND allow")
        ExecUtils.executeRootCommand("appops set $packageName WAKE_LOCK allow")
    }

    private fun applyShizukuOptimizations() {
        if (app.fjj.stun.util.ShizukuUtils.isReady) {
            StunLogger.i(TAG, "Applying Shizuku background optimizations...")
            app.fjj.stun.util.ShizukuUtils.addSelfToBatteryWhitelist(packageName)
            app.fjj.stun.util.ShizukuUtils.setStandbyBucketActive(packageName)
        }
    }

    private fun startWatchdog() {
        val pid = android.os.Process.myPid()
        val currentPackageName = packageName
        // 获取当前动态的 cache 目录的绝对路径
        val cachePath = cacheDir.absolutePath
        val scriptPath = File(cacheDir, SCRIPT_WATCHDOG).absolutePath
        val watchdogLogFile = File(cacheDir, FILE_WATCHDOG_LOG).absolutePath
        // 传入 3 个参数：PID ($1), Cache路径 ($2), 包名 ($3)
        val cmd = "nohup sh $scriptPath $pid $cachePath $currentPackageName > $watchdogLogFile 2>&1 &"
        StunLogger.i(TAG, "Starting Watchdog for PID: $pid, Package: $currentPackageName")
        ExecUtils.executeRootCommand(cmd)
    }

    private fun stopWatchdog() {
        StunLogger.i(TAG, "Stopping Native Watchdog...")
        // 脚本的名称，与 startWatchdog 中保持一致
        val scriptName = "watchdog.sh"
        // 构建精准击杀命令：
        // 1. 优先尝试 pkill -f，它会匹配整个命令行字符串，是最优雅的方式。
        // 2. 如果系统不支持 pkill (某些精简版 ROM)，使用 ps + grep + kill 作为兼容方案。
        // 3. 最后加上 || true 防止因为找不到进程而导致命令执行抛出异常。
        val killCmd = """
        pkill -f $scriptName || \
        kill -9 $(ps -A | grep $scriptName | grep -v grep | awk '{print $2}') || \
        true
    """.trimIndent()
        ExecUtils.executeRootCommand(killCmd)
        // 可选：清理残留在缓存中的脚本文件
        val scriptFile = File(cacheDir, scriptName)
        if (scriptFile.exists()) {
            scriptFile.delete()
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.service_mode_tproxy), NotificationManager.IMPORTANCE_LOW)
        nm?.createNotificationChannel(channel)
    }

    private fun updateNotification(content: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_fox_logo)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        StunLogger.i(TAG, "Service onDestroy called.")
        stopTProxy(this@MyTransparentProxyService)
        serviceScope.cancel() // 彻底销毁作用域
        super.onDestroy()
    }
}