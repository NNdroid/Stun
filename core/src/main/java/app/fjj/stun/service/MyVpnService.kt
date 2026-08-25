package app.fjj.stun.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import app.fjj.stun.core.R
import app.fjj.stun.core.BuildConfig
import kotlinx.coroutines.*
import hev.htp.TTunnelService
import app.fjj.stun.repo.*
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

    companion object {
        const val TAG = "StunVpnService"
        const val ACTION_START = "app.fjj.stun.START"
        const val ACTION_STOP = "app.fjj.stun.STOP"
        const val SOCKS_PORT = 10808
        const val DNS_PORT = 10553
        const val RECONNECT_DELAY = 3000L
        const val CHANNEL_ID = "StunVpnChannel"
        const val NOTIFICATION_ID = 1001
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(app.fjj.stun.util.LocaleHelper.wrapContext(newBase))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> handleStopRequest()
            else -> handleStartRequest()
        }
        return START_STICKY
    }

    private fun handleStopRequest() {
        userRequestedStop = true
        serviceScope.launch {
            stopVpnService()
        }
    }

    private fun handleStartRequest() {
        val currentState = StunRepository.vpnState.value ?: VpnState.DISCONNECTED
        if (currentState == VpnState.DISCONNECTED || currentState == VpnState.ERROR) {
            userRequestedStop = false
            updateNotification(getString(R.string.main_connecting))
            StunRepository.vpnState.postValue(VpnState.CONNECTING)
            serviceScope.launch {
                startVpnServiceLoop()
            }
        }
    }

    private fun startVpnServiceLoop() {
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

                // 注意：不再在此把 lastSessionTx/Rx 清零。
                // Go 的 TxTotal/RxTotal 是进程级全局单调计数器，若每次会话开始清零，
                // 首帧回调会把整个进程累计值重新写进 DB，导致跨重连重复计数。
                // 基准值跨重连保留，updateStats 里的 delta 即为本次真实增量。
                val sshStatus = StunRepository.proxy.start(VpnConfigBuilder.buildMySshConfig(this, profile, SOCKS_PORT, DNS_PORT))
                if (sshStatus != 0L) {
                    throw RuntimeException("SSH Core start failed")
                }

                vpnInterface = createVpnInterface(profile)
                val fd = vpnInterface?.fd ?: throw RuntimeException("TUN establish failed")

                startHevTunnelEngine(fd)

                StunRepository.vpnState.postValue(VpnState.CONNECTED)
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
                try {
                    Thread.sleep(RECONNECT_DELAY)
                } catch (_: InterruptedException) {}
            }
        }
        StunRepository.vpnState.postValue(VpnState.DISCONNECTED)
    }

    private fun createVpnInterface(profile: Profile): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("StunSshTunnel")
            .setMtu(1500)
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
        isForegroundStarted = false
        serviceScope.launch {
            saveFinalTrafficStats()
            cleanupNativeResources()
            withContext(Dispatchers.Main) {
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

    private fun updateNotification(contentText: String? = null) {
        serviceScope.launch(Dispatchers.Main) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.service_mode_vpn), NotificationManager.IMPORTANCE_LOW)
            )

            val stopIntent = Intent(this@MyVpnService, MyVpnService::class.java).apply { action = ACTION_STOP }
            val stopPendingIntent = android.app.PendingIntent.getService(
                this@MyVpnService, 0, stopIntent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )

            val mainIntent = Intent().setClassName(this@MyVpnService, "app.fjj.stun.ui.MainActivity")
            val mainPendingIntent = android.app.PendingIntent.getActivity(
                this@MyVpnService, 0, mainIntent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notificationBuilder = NotificationCompat.Builder(this@MyVpnService, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(contentText ?: getString(R.string.notif_text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(contentText ?: getString(R.string.notif_text)))
                .setSubText(currentProfileName)
                .setSmallIcon(R.drawable.ic_notification)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(mainPendingIntent)
                .addAction(R.drawable.ic_pause, getString(R.string.disconnect), stopPendingIntent)

            val notification = notificationBuilder.build()

            if (!isForegroundStarted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                isForegroundStarted = true
            } else {
                nm?.notify(NOTIFICATION_ID, notification)
            }
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

        val statusText = "↑ ${app.fjj.stun.util.AppUtils.formatBytes(currentTxRate)}/s (${app.fjj.stun.util.AppUtils.formatBytes(currentTxTotal)}) " +
                "↓ ${app.fjj.stun.util.AppUtils.formatBytes(currentRxRate)}/s (${app.fjj.stun.util.AppUtils.formatBytes(currentRxTotal)}) | " +
                "Conns: $currentActiveConns/$currentTotalConns | " +
                "CPU: ${String.format(Locale.US, "%.1f", currentCpu)}% MEM: ${String.format(Locale.US, "%.1f", currentMem)}MB/${String.format(Locale.US, "%.1f", currentMemSys)}MB G: $currentGoroutines"

        updateNotification(statusText)
    }

    override fun onDestroy() {
        userRequestedStop = true
        runBlocking {
            withContext(Dispatchers.IO) {
                saveFinalTrafficStats()
                cleanupNativeResources()
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        userRequestedStop = true
        stopVpnService()
        super.onRevoke()
    }
}
