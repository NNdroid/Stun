package app.fjj.stun.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import app.fjj.stun.core.R
import app.fjj.stun.core.BuildConfig
import kotlinx.coroutines.*
import hev.htp.TTunnelService
import app.fjj.stun.repo.*
import app.fjj.stun.util.AppBootstrap
import app.fjj.stun.util.ShizukuUtils
import myssh.SysInfoCallback
import myssh.TrafficCallback
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.pow

@SuppressLint("VpnServicePolicy")
class MyVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

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
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var userRequestedStop = false
    @Volatile private var isForegroundStarted = false

    /**
     * 会话循环的**唯一权威存活信号**。`vpnState` 是异步 postValue 的 LiveData，
     * 比循环的实际启停滞后 —— 拿它当"有没有会话在跑"的闸门，会在旧循环收尾期间放进
     * 第二个循环，两个循环共享 vpnInterface、Go proxy 与状态流，产生
     * "UI 显示未连接但 VPN 图标还亮着"的僵尸态。只在主线程读写。
     */
    private var loopJob: Job? = null

    /** 旧会话正在收尾时收到的启动意图，等循环退出后自动补发。只在主线程读写。 */
    private var pendingStart = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentUnderlyingNetwork: Network? = null
    private var reconnectTrigger: CompletableDeferred<Unit>? = null

    companion object {
        const val TAG = "StunVpnService"
        const val ACTION_START = "app.fjj.stun.START"
        const val ACTION_STOP = "app.fjj.stun.STOP"
        const val SOCKS_PORT = 10808
        const val DNS_PORT = 10553
        const val INITIAL_RECONNECT_DELAY = 2000L
        const val MAX_RECONNECT_DELAY = 30000L
        const val CHANNEL_ID = "StunVpnChannel"
        const val NOTIFICATION_ID = 1001
        const val VPN_MTU = 1500

        /** 断开后等待循环自行退出的上限，超时才强制停服务（见 stopVpnService 的看门狗）。 */
        const val STOP_WATCHDOG_MS = 5000L
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(app.fjj.stun.util.LocaleHelper.wrapContext(newBase))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService 启动的服务必须在系统时限内进入前台，否则超时抛
        // ForegroundServiceDidNotStartInTimeException。必须同步调用：会话守卫
        // （loopJob 还活着时跳过/排队启动）和 ACTION_STOP 路径都不能例外。
        startForegroundNow(getString(R.string.main_connecting))
        when (intent?.action) {
            ACTION_STOP -> handleStopRequest()
            else -> handleStartRequest()
        }
        return START_STICKY
    }

    private fun handleStopRequest() {
        userRequestedStop = true
        // 显式断开要作废排队中的重连意图（断开后用户又快速点过连接的情况）
        pendingStart = false
        reconnectTrigger?.complete(Unit)
        serviceScope.launch {
            stopVpnService()
        }
    }

    private fun handleStartRequest() {
        // 闸门只认循环协程的存活，不认滞后的 vpnState：循环还活着（含收尾）时，
        // 若已在停止流程则记下意图、循环退出后自动重连；否则视为重复点击，忽略。
        if (loopJob?.isActive == true) {
            if (userRequestedStop) pendingStart = true
            return
        }
        pendingStart = false
        userRequestedStop = false
        StunRepository.vpnState.postValue(VpnState.CONNECTING)
        acquireLocks()
        registerNetworkCallback()
        loopJob = serviceScope.launch {
            startVpnServiceLoop()
            onSessionLoopExited()
        }
    }

    /**
     * 循环退出后的统一收尾（跑在循环协程自己的尾部，此时旧会话的资源已全部释放）：
     * 有排队意图就原地重开，没有才真正停服务。stopForeground/stopSelf 放在这里而不是
     * ACTION_STOP 路径里，是为了让排队重连不被上一会话的 stopSelf 打断。
     */
    private suspend fun onSessionLoopExited() {
        withContext(Dispatchers.Main) {
            loopJob = null // 腾位置：handleStartRequest 的闸门此刻必须放行
            if (pendingStart) {
                pendingStart = false
                handleStartRequest()
                return@withContext
            }
            isForegroundStarted = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Stun::VpnWakeLock")
                wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24h safety timeout
            }
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Stun::VpnWifiLock")
                wifiLock?.acquire()
            }
            StunLogger.d(TAG, "Acquired WakeLock and WifiLock for background stability")
        } catch (e: Exception) {
            StunLogger.w(TAG, "Failed to acquire locks: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
            wifiLock = null
            StunLogger.d(TAG, "Released WakeLock and WifiLock")
        } catch (e: Exception) {
            StunLogger.w(TAG, "Failed to release locks: ${e.message}")
        }
    }

    private fun registerNetworkCallback() {
        if (connectivityManager == null) {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && defaultNetworkCallback == null) {
            defaultNetworkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    StunLogger.i(TAG, "Default Network Available: $network")
                    currentUnderlyingNetwork = network
                    updateUnderlyingNetworks()
                    // Instantly trigger reconnect if waiting in backoff
                    reconnectTrigger?.complete(Unit)
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    super.onCapabilitiesChanged(network, networkCapabilities)
                    currentUnderlyingNetwork = network
                    updateUnderlyingNetworks()
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    StunLogger.w(TAG, "Default Network Lost: $network")
                    if (currentUnderlyingNetwork == network) {
                        currentUnderlyingNetwork = null
                        updateUnderlyingNetworks()
                    }
                }
            }
            try {
                connectivityManager?.registerDefaultNetworkCallback(defaultNetworkCallback!!)
            } catch (e: Exception) {
                StunLogger.w(TAG, "Failed to register default network callback: ${e.message}")
            }
        }
    }

    private fun updateUnderlyingNetworks() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val networks = currentUnderlyingNetwork?.let { arrayOf(it) }
                setUnderlyingNetworks(networks)
                StunLogger.d(TAG, "Updated underlying networks: ${currentUnderlyingNetwork ?: "null (clear)"}")
            } catch (e: Exception) {
                StunLogger.w(TAG, "Failed to set underlying networks: ${e.message}")
            }
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            defaultNetworkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            StunLogger.w(TAG, "Failed to unregister network callback: ${e.message}")
        }
        defaultNetworkCallback = null
        currentUnderlyingNetwork = null
    }

    private suspend fun startVpnServiceLoop() {
        // 规则库（geosite/geoip）路径要交给 Go 侧，而这几份资产已随启动优化挪到 IO 上部署 ——
        // 进循环前等就绪，别把还不存在的路径下发下去（Go 侧会静默按无规则启动）。
        AppBootstrap.awaitAssets(this)

        var currentBackoff = INITIAL_RECONNECT_DELAY
        while (!userRequestedStop) {
            try {
                val profile = ProfileManager.getSelectedProfile(this)
                currentProfileName = profile.name
                
                updateNotification()
                
                StunRepository.registerEngineCallback()
                val cfgStatus = StunRepository.proxy.loadGlobalConfig(VpnConfigBuilder.buildGlobalConfig(this, profile))
                if (cfgStatus != 0L) throw RuntimeException("Global config load failed: $cfgStatus")

                myssh.Myssh.registerProtector { fd: Int ->
                    this@MyVpnService.protect(fd)
                }

                val sshStatus = StunRepository.proxy.start(VpnConfigBuilder.buildMySshConfig(this, profile, SOCKS_PORT, DNS_PORT))
                if (sshStatus != 0L) {
                    throw RuntimeException("SSH Core start failed")
                }

                vpnInterface = createVpnInterface(profile)
                val fd = vpnInterface?.fd ?: throw RuntimeException("TUN establish failed")

                startHevTunnelEngine(fd)

                ProfileManager.markConnected(this, profile.id)
                StunRepository.vpnState.postValue(VpnState.CONNECTED)
                currentBackoff = INITIAL_RECONNECT_DELAY // Reset backoff on success
                updateUnderlyingNetworks()
                startTrafficMonitor()

                applyShizukuOptimizations()

                StunRepository.proxy.wgWait()

            } catch (e: Exception) {
                StunLogger.e(TAG, "Main Loop Interrupted", e)
            } finally {
                cleanupNativeResources()
                val nextState = if (userRequestedStop) VpnState.DISCONNECTED else VpnState.RECONNECTING
                StunRepository.vpnState.postValue(nextState)
            }

            if (!userRequestedStop) {
                reconnectTrigger = CompletableDeferred()
                withTimeoutOrNull(currentBackoff) {
                    reconnectTrigger?.await()
                }
                currentBackoff = (currentBackoff * 2).coerceAtMost(MAX_RECONNECT_DELAY)
            }
        }
        StunRepository.vpnState.postValue(VpnState.DISCONNECTED)
    }

    private fun createVpnInterface(profile: Profile): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("StunSshTunnel")
            .setMtu(VPN_MTU)
            .addAddress("10.0.0.2", 24)
            .addRoute("0.0.0.0", 0)
            .addAddress("fd00:1::2", 64)
            .addRoute("::", 0)
            .addDnsServer("8.8.8.8")

        applyAppFiltering(builder, profile)
        return builder.establish()
    }

    private fun applyAppFiltering(builder: Builder, profile: Profile) {
        val appFilterOverride = profile.appFilterOverride
        val filterApps = if (appFilterOverride) profile.filterApps else SettingsManager.getFilterApps(this)
        val filterMode = if (appFilterOverride) profile.filterMode else SettingsManager.getFilterMode(this)

        if (filterApps.isNotBlank()) {
            val apps = filterApps.split(",").map { it.trim() }.filter { it.isNotBlank() }
            apps.forEach { app ->
                try {
                    if (filterMode == 1) builder.addAllowedApplication(app)
                    else builder.addDisallowedApplication(app)
                } catch (e: Exception) {
                    StunLogger.w(TAG, "Failed to filter app: $app")
                }
            }
        }
    }

    private fun applyShizukuOptimizations() {
        if (ShizukuUtils.isReady()) {
            ShizukuUtils.addSelfToBatteryWhitelist(packageName)
            ShizukuUtils.setStandbyBucketActive(packageName)
        }
    }

    private fun startHevTunnelEngine(fd: Int) {
        val confFile = File(cacheDir, "tproxy.conf")
        try {
            FileOutputStream(confFile).use { it.write(VpnConfigBuilder.buildHevSocks5TunnelConfig(SOCKS_PORT).toByteArray()) }
            
            serviceScope.launch {
                try {
                    TTunnelService.TTunnelStartService(confFile.absolutePath, fd)
                } catch (e: Exception) {
                    StunLogger.e(TAG, "HEV Crash", e)
                }
            }
        } catch (e: IOException) {
            StunLogger.e(TAG, "Failed to write HEV config")
        }
    }

    private fun cleanupNativeResources() {
        app.fjj.stun.util.LatencyProber.stop()
        StunRepository.clearRateHistory()
        try { myssh.Myssh.registerTrafficCallback(null) } catch (_: Exception) {}
        try { myssh.Myssh.registerSysInfoCallback(null) } catch (_: Exception) {}
        try { myssh.Myssh.registerProtector(null) } catch (_: Exception) {}
        try { TTunnelService.TTunnelStopService() } catch (_: Exception) {}
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (_: Exception) {}

        try { StunRepository.proxy.stop() } catch (_: Exception) {}
    }

    private fun stopVpnService() {
        releaseLocks()
        unregisterNetworkCallback()
        serviceScope.launch {
            saveFinalTrafficStats()
            // cleanupNativeResources 在这里跑一遍是为了 unblock 循环里阻塞的 wgWait；
            // 循环 finally 还会再清一遍，两步都幂等。
            cleanupNativeResources()
        }
        // 收尾统一交给 onSessionLoopExited（stopForeground/stopSelf + 排队重连）。
        // 看门狗兜底：wgWait 万一卡死，循环永远不退出，服务就会赖在前台 ——
        // 这是旧代码"无条件 stopSelf"能掩盖、但改回按循环退出停服后必须补上的安全网。
        serviceScope.launch(Dispatchers.Main) {
            delay(STOP_WATCHDOG_MS)
            if (loopJob?.isActive == true && !pendingStart) {
                StunLogger.w(TAG, "Session loop did not exit after stop request; forcing service stop")
                loopJob?.cancel()
                isForegroundStarted = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun saveFinalTrafficStats() {
        // 只补最后一段尚未落库的增量，避免与 updateStats 的逐次累加重复（否则整段流量会被加两次）。
        // 会话期间 updateStats 已把每段 delta 写入 DB，这里兜底补齐「最后一次回调到会话结束」之间的差值。
        val dTx = if (currentTxTotal >= lastSessionTx) currentTxTotal - lastSessionTx else 0L
        val dRx = if (currentRxTotal >= lastSessionRx) currentRxTotal - lastSessionRx else 0L
        if (dTx > 0 || dRx > 0) {
            SettingsManager.getSelectedProfileId(this)?.let { id ->
                ProfileManager.addTrafficStats(this, id, dTx, dRx)
            }
        }
        currentTxTotal = 0L
        currentRxTotal = 0L
    }

    /** 同步进入前台：在 startForegroundService 的时限窗口内必须被调用，不能经过协程调度。 */
    private fun startForegroundNow(contentText: String) {
        if (isForegroundStarted) return
        try {
            val notification = buildNotification(contentText)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForegroundStarted = true
        } catch (e: Exception) {
            StunLogger.e(TAG, "startForeground failed", e)
        }
    }

    private fun buildNotification(contentText: String): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.service_mode_vpn), NotificationManager.IMPORTANCE_LOW)
        )

        val stopIntent = Intent(this, MyVpnService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = android.app.PendingIntent.getService(
            this, 0, stopIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val mainIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent().setClassName(this, "app.fjj.stun.ui.MainActivity")
        val mainPendingIntent = android.app.PendingIntent.getActivity(
            this, 0, mainIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setSubText(currentProfileName)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(mainPendingIntent)
            .addAction(R.drawable.ic_pause, getString(R.string.disconnect), stopPendingIntent)
            .build()
    }

    private fun updateNotification(contentText: String? = null) {
        val text = contentText ?: getString(R.string.notif_text)
        if (!isForegroundStarted) {
            startForegroundNow(text)
            return
        }
        serviceScope.launch(Dispatchers.Main) {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
        }
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

        app.fjj.stun.util.LatencyProber.start(this)
    }

    private fun updateStats(txRate: Long, rxRate: Long, txTotal: Long, rxTotal: Long, activeConns: Long, totalConns: Long) {
        currentTxRate = txRate
        currentRxRate = rxRate

        // 按 Go 全局累计值的逐帧差值增量落库：避免把整个进程累计值整段重复写入，
        // 也保证节点列表的流量统计能随 DB 变更实时刷新（lastSessionTx 跨重连保留，不在此清零）。
        val deltaTx = if (txTotal >= lastSessionTx) txTotal - lastSessionTx else 0L
        val deltaRx = if (rxTotal >= lastSessionRx) rxTotal - lastSessionRx else 0L
        lastSessionTx = txTotal
        lastSessionRx = rxTotal
        if (deltaTx > 0 || deltaRx > 0) {
            SettingsManager.getSelectedProfileId(this)?.let { id ->
                ProfileManager.addTrafficStats(this, id, deltaTx, deltaRx)
            }
        }

        currentTxTotal = txTotal
        currentRxTotal = rxTotal
        currentActiveConns = activeConns
        currentTotalConns = totalConns

        StunRepository.txRate.postValue(txRate)
        StunRepository.rxRate.postValue(rxRate)
        StunRepository.txTotal.postValue(txTotal)
        StunRepository.rxTotal.postValue(rxTotal)
        StunRepository.recordRateSample(txRate, rxRate)

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
        if (!SettingsManager.getShowNotificationSpeed(this)) {
            return
        }
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastNotificationUpdateTime < 2000L) {
            return
        }
        lastNotificationUpdateTime = currentTime

        val statusText = "↑ ${app.fjj.stun.util.AppUtils.formatSpeed(currentTxRate)} (${app.fjj.stun.util.AppUtils.formatBytes(currentTxTotal)}) " +
                "↓ ${app.fjj.stun.util.AppUtils.formatSpeed(currentRxRate)} (${app.fjj.stun.util.AppUtils.formatBytes(currentRxTotal)}) | " +
                "Conns: $currentActiveConns/$currentTotalConns | " +
                "CPU: ${String.format(Locale.US, "%.1f", currentCpu)}% MEM: ${String.format(Locale.US, "%.1f", currentMem)}MB/${String.format(Locale.US, "%.1f", currentMemSys)}MB G: $currentGoroutines"

        updateNotification(statusText)
    }

    override fun onDestroy() {
        userRequestedStop = true
        releaseLocks()
        unregisterNetworkCallback()
        // 收尾不再让主线程无界等待：统计落库有界等（Room 不允许主线程访问，只能留在 IO），
        // 原生资源释放彻底异步。契约与取值依据见 VpnTeardown。
        val blockedMs = VpnTeardown.run(
            flushStats = { saveFinalTrafficStats() },
            cleanupNative = { cleanupNativeResources() },
            onError = { what, t -> StunLogger.w(TAG, "Teardown $what failed: ${t.message}") },
        )
        StunLogger.i(TAG, "Teardown: main thread blocked ${blockedMs}ms")
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        userRequestedStop = true
        stopVpnService()
        super.onRevoke()
    }
}
