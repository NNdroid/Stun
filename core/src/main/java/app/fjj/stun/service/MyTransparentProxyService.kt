package app.fjj.stun.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.fjj.stun.core.R
import app.fjj.stun.core.BuildConfig
import app.fjj.stun.repo.*
import app.fjj.stun.util.ExecUtils
import app.fjj.stun.util.ShizukuUtils
import kotlinx.coroutines.*
import myssh.SysInfoCallback
import myssh.TrafficCallback
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.pow

/**
 * a root-based transparent proxy.
 * Uses iptables (via tproxy.sh) and hev-socks5-tproxy core.
 */
class MyTransparentProxyService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mainJob: Job? = null
    private var coreJob: Job? = null
    private val isRunning = AtomicBoolean(false)

    private var currentTxRate = 0L
    private var currentRxRate = 0L
    private var currentTxTotal = 0L
    private var currentRxTotal = 0L
    private var currentCpu = 0.0
    private var currentMem = 0.0
    private var currentActiveConns = 0L
    private var currentTotalConns = 0L
    private var currentMemSys = 0.0
    private var currentGoroutines = 0L
    
    private var lastSessionTx = 0L
    private var lastSessionRx = 0L
    
    private var currentProfileName: String = ""
    private val TAG: String
        get() = "TProxyService-[${Thread.currentThread().name}]"

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                wakeLock = pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Stun::TProxyWakeLock")
                wakeLock?.acquire(24 * 60 * 60 * 1000L)
            }
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Stun::TProxyWifiLock")
                wifiLock?.acquire()
            }
        } catch (e: Exception) {
            StunLogger.w(TAG, "Failed to acquire locks: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            wakeLock = null
            if (wifiLock?.isHeld == true) wifiLock?.release()
            wifiLock = null
        } catch (e: Exception) {
            StunLogger.w(TAG, "Failed to release locks: ${e.message}")
        }
    }

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

        if (!isRunning.compareAndSet(false, true)) {
            StunLogger.w(TAG, "Service is already running, ignoring start request.")
            return
        }

        StunRepository.registerEngineCallback()
        StunRepository.vpnState.postValue(VpnState.CONNECTING)
        updateNotification(getString(R.string.main_connecting))
        acquireLocks()

        mainJob = serviceScope.launch {
            if (!withContext(Dispatchers.IO) { ExecUtils.checkIsRootPermission() }) {
                StunLogger.e(TAG, "Root permission required but not granted. Stopping service.")
                isRunning.set(false)
                StunRepository.vpnState.postValue(VpnState.DISCONNECTED)
                withContext(Dispatchers.Main) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return@launch
            }

            try {
                StunLogger.i(TAG, "--- Start Sequence Initiated ---")

                applyRules(context, false)

                val profile = ProfileManager.getSelectedProfile(context)
                currentProfileName = profile.name

                // 不在此清零 lastSessionTx/Rx：Go 的 TxTotal/RxTotal 为进程级全局单调计数器，
                // 清零会导致每次会话开始把整个累计值重新写库，跨重连重复计数。基准跨重连保留。
                val cfgStatus = StunRepository.proxy.loadGlobalConfig(VpnConfigBuilder.buildGlobalConfig(context, profile))
                if (cfgStatus != 0L) throw RuntimeException("Global config load failed: $cfgStatus")
                val sshStatus = StunRepository.proxy.start(VpnConfigBuilder.buildMySshConfig(context, profile, SOCKS_PORT, DNS_HIJACK_PORT))
                if (sshStatus != 0L) throw RuntimeException("SSH Core failed to start with status: $sshStatus")
                StunLogger.i(TAG, "SSH Core started successfully.")

                val yamlConfig = TransparentProxyConfigBuilder.buildHevSocks5TProxyConfig(
                    context, profile, SOCKS_PORT, TPROXY_PORT, DNS_HIJACK_PORT
                )
                File(cacheDir, FILE_HEV_SOCKS5_TPROXY_CONF).writeText(yamlConfig)

                startCoreEngine()

                delay(1000)

                applyRules(context, true)

                StunRepository.vpnState.postValue(VpnState.CONNECTED)
                updateNotification(getString(R.string.notif_text))

                optimizeSystemForBackground()
                applyShizukuOptimizations()

                startWatchdog()
                startTrafficMonitor()

                StunRepository.proxy.wgWait()
                StunLogger.i(TAG, "wgWait() returned gracefully.")

            } catch (e: Exception) {
                StunLogger.e(TAG, "Critical Failure during start sequence: ${e.message}", e)
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
        val isDebug = BuildConfig.DEBUG
        if (enabled) {
            StunLogger.i(TAG, "Enabling TProxy firewall rules...")
            val shellConfig = TransparentProxyConfigBuilder.buildHevSocks5TProxyConfig(
                this, TPROXY_PORT, TPROXY_PORT, DNS_HIJACK_PORT
            )
            File(cacheDir, FILE_TPROXY_CONF).writeText(shellConfig)

            ExecUtils.executeRootCommand("${scriptFile.absolutePath} -d $cachePath ${(if (isDebug) "--verbose" else "")} start")
        } else {
            StunLogger.i(TAG, "Disabling TProxy firewall rules...")
            ExecUtils.executeRootCommand("${scriptFile.absolutePath} -d $cachePath ${(if (isDebug) "--verbose" else "")} stop")
        }
    }

    private fun stopTProxy(context: Context) {
        if (!isRunning.compareAndSet(true, false)) {
            StunLogger.w(TAG, "Service is not running, ignoring stop request.")
            return
        }

        mainJob?.cancel()
        coreJob?.cancel()
        releaseLocks()

        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch(Dispatchers.IO + NonCancellable) {
            try {
                stopTrafficMonitor()
                stopWatchdog()
                applyRules(context, false)
                stopCoreEngine()
                try {
                    StunRepository.proxy.stop()
                } catch (_: Exception) {}
            } finally {
                StunRepository.vpnState.postValue(VpnState.DISCONNECTED)
                withContext(Dispatchers.Main) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun optimizeSystemForBackground() {
        ExecUtils.executeRootCommand("dumpsys deviceidle whitelist +$packageName")
        ExecUtils.executeRootCommand("appops set $packageName RUN_IN_BACKGROUND allow")
        ExecUtils.executeRootCommand("appops set $packageName WAKE_LOCK allow")
    }

    private fun applyShizukuOptimizations() {
        if (ShizukuUtils.isReady()) {
            ShizukuUtils.addSelfToBatteryWhitelist(packageName)
            ShizukuUtils.setStandbyBucketActive(packageName)
        }
    }

    private fun startWatchdog() {
        val pid = android.os.Process.myPid()
        val cachePath = cacheDir.absolutePath
        val scriptPath = File(cacheDir, SCRIPT_WATCHDOG).absolutePath
        val watchdogLogFile = File(cacheDir, FILE_WATCHDOG_LOG).absolutePath
        val cmd = "nohup sh $scriptPath $pid $cachePath $packageName > $watchdogLogFile 2>&1 &"
        ExecUtils.executeRootCommand(cmd)
    }

    private fun stopWatchdog() {
        val scriptName = "watchdog.sh"
        val killCmd = "pkill -f $scriptName || kill -9 $(ps -A | grep $scriptName | grep -v grep | awk '{print $2}') || true"
        ExecUtils.executeRootCommand(killCmd)
    }

    private fun startTrafficMonitor() {
        myssh.Myssh.registerTrafficCallback(TrafficCallback { txRate: Long, rxRate: Long, txTotal: Long, rxTotal: Long, activeConns: Long, totalConns: Long ->
            serviceScope.launch {
                updateStats(txRate, rxRate, txTotal, rxTotal, activeConns, totalConns)
            }
        })

        myssh.Myssh.registerSysInfoCallback(SysInfoCallback { cpuPercent: Double, memAllocMB: Double, memSysMB: Double, goroutines: Long ->
            serviceScope.launch {
                updateSysInfo(cpuPercent, memAllocMB, memSysMB, goroutines)
            }
        })
    }

    private fun stopTrafficMonitor() {
        try { myssh.Myssh.registerTrafficCallback(null) } catch (_: Exception) {}
        try { myssh.Myssh.registerSysInfoCallback(null) } catch (_: Exception) {}
    }

    private fun updateStats(txRate: Long, rxRate: Long, txTotal: Long, rxTotal: Long, activeConns: Long, totalConns: Long) {
        currentTxRate = txRate
        currentRxRate = rxRate
        
        val deltaTx = if (txTotal >= lastSessionTx) txTotal - lastSessionTx else txTotal
        val deltaRx = if (rxTotal >= lastSessionRx) rxTotal - lastSessionRx else rxTotal
        
        currentTxTotal = txTotal
        currentRxTotal = rxTotal
        currentActiveConns = activeConns
        currentTotalConns = totalConns

        lastSessionTx = txTotal
        lastSessionRx = rxTotal

        StunRepository.txRate.postValue(txRate)
        StunRepository.rxRate.postValue(rxRate)

        SettingsManager.getSelectedProfileId(this)?.let { id ->
            ProfileManager.addTrafficStats(this, id, deltaTx, deltaRx)
        }
        refreshNotification()
    }

    private fun updateSysInfo(cpu: Double, mem: Double, memSys: Double, goroutines: Long) {
        currentCpu = cpu
        currentMem = mem
        currentMemSys = memSys
        currentGoroutines = goroutines
        refreshNotification()
    }

    private var lastNotificationUpdateTime = 0L
    private fun refreshNotification() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastNotificationUpdateTime < 1000L) return
        lastNotificationUpdateTime = currentTime

        val statusText = "↑ ${app.fjj.stun.util.AppUtils.formatSpeed(currentTxRate)} (${app.fjj.stun.util.AppUtils.formatBytes(currentTxTotal)}) " +
                         "↓ ${app.fjj.stun.util.AppUtils.formatSpeed(currentRxRate)} (${app.fjj.stun.util.AppUtils.formatBytes(currentRxTotal)}) | " +
                         "Conns: $currentActiveConns/$currentTotalConns | " +
                         "CPU: ${String.format(Locale.US, "%.1f", currentCpu)}% MEM: ${String.format(Locale.US, "%.1f", currentMem)}MB/${String.format(Locale.US, "%.1f", currentMemSys)}MB G: $currentGoroutines"

        updateNotification(statusText)
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.service_mode_tproxy), NotificationManager.IMPORTANCE_LOW)
        nm?.createNotificationChannel(channel)
    }

    private fun updateNotification(content: String) {
        serviceScope.launch(Dispatchers.Main) {
            val stopIntent = Intent(this@MyTransparentProxyService, MyTransparentProxyService::class.java).apply { action = ACTION_STOP }
            val stopPendingIntent = android.app.PendingIntent.getService(
                this@MyTransparentProxyService, 0, stopIntent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )

            val mainIntent = Intent().setClassName(this@MyTransparentProxyService, "app.fjj.stun.ui.MainActivity")
            val mainPendingIntent = android.app.PendingIntent.getActivity(
                this@MyTransparentProxyService, 0, mainIntent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification = NotificationCompat.Builder(this@MyTransparentProxyService, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .setSubText(currentProfileName)
                .setSmallIcon(R.drawable.ic_notification)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(mainPendingIntent)
                .addAction(R.drawable.ic_pause, getString(R.string.disconnect), stopPendingIntent)
                .build()
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopTProxy(this@MyTransparentProxyService)
        serviceScope.cancel()
        super.onDestroy()
    }
}
