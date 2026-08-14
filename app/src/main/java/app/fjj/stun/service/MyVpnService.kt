package app.fjj.stun.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
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
        log("User requested to stop service...")
        userRequestedStop = true
        // Switch to IO dispatcher for cleanup to avoid UI freeze
        serviceScope.launch {
            stopVpnService()
        }
    }

    private fun handleStartRequest() {
        val currentState = StunRepository.vpnState.value ?: VpnState.DISCONNECTED
        if (currentState == VpnState.DISCONNECTED || currentState == VpnState.ERROR) {
            userRequestedStop = false
            log("Starting VPN Service...")
            updateNotification(getString(app.fjj.stun.R.string.main_connecting))
            StunRepository.vpnState.postValue(VpnState.CONNECTING)
            serviceScope.launch {
                startVpnServiceLoop()
            }
        }
    }

    private fun startVpnServiceLoop() {
        while (!userRequestedStop) {
            try {
                log("--- Initializing tunnel environment ---")
                val profile = ProfileManager.getSelectedProfile(this)
                currentProfileName = profile.name
                
                updateNotification()
                
                // Core Config
                myssh.Myssh.loadGlobalConfigFromJson(VpnConfigBuilder.buildGlobalConfig(this, profile))

                myssh.Myssh.registerProtector { fd ->
                    // 注意：Go 的 int32 在 Kotlin 中对应的是 Int，bool 对应 Boolean
                    // 调用 VpnService 的 protect 方法
                    val isProtected = this@MyVpnService.protect(fd)
                    // log("already protect socket: $fd")
                    isProtected
                }

                // Start SSH
                lastSessionTx = 0L
                lastSessionRx = 0L
                val sshStatus = myssh.Myssh.startSshTProxy2(VpnConfigBuilder.buildMySshConfig(this, profile, SOCKS_PORT, DNS_PORT))
                if (sshStatus != 0L) {
                    log("❌ Go SSH Core failed to start (Code: $sshStatus). Retrying...")
                    throw RuntimeException("SSH Core start failed")
                }

                // Establish TUN
                vpnInterface = createVpnInterface(profile)
                val fd = vpnInterface?.fd ?: throw RuntimeException("TUN establish failed")
                log("✅ TUN interface ready (FD: $fd)")

                // Start HEV Engine
                startHevTunnelEngine(fd)

                log("🚀 All services started. Tunnel is active.")
                StunRepository.vpnState.postValue(VpnState.CONNECTED)
                startTrafficMonitor()

                // Shizuku Optimizations
                applyShizukuOptimizations()

                // Block and wait for core
                myssh.Myssh.wgWait()
                log("⚠️ WG Wait released.")

            } catch (e: Exception) {
                StunLogger.e(TAG, "Main Loop Interrupted", e)
            } finally {
                cleanupNativeResources()
                val nextState = if (userRequestedStop) VpnState.DISCONNECTED else VpnState.RECONNECTING
                StunRepository.vpnState.postValue(nextState)
            }

            if (!userRequestedStop) {
                log("🔄 Reconnecting in ${RECONNECT_DELAY / 1000}s...")
                Thread.sleep(RECONNECT_DELAY)
            }
        }
        StunRepository.vpnState.postValue(VpnState.DISCONNECTED)
        log("🛑 VPN main loop exited safely.")
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
                    log("⚠️ Failed to filter app: $app, error: ${e.message}")
                }
            }
        }
        
//        if (filterMode != 1) {
//            try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
//        }
    }

    private fun applyShizukuOptimizations() {
        if (ShizukuUtils.isReady()) {
            log("Applying Shizuku background optimizations...")
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
                    log("HEV engine starting...")
                    TTunnelService.TTunnelStartService(confFile.absolutePath, fd)
                    log("HEV engine started.")
                } catch (e: Exception) {
                    StunLogger.e(TAG, "HEV Crash", e)
                }
            }
        } catch (e: IOException) {
            log("Failed to write HEV config: ${e.message}")
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

        try { myssh.Myssh.stopSshTProxy() } catch (_: Exception) {}
    }

    private fun stopVpnService() {
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
        SettingsManager.getSelectedProfileId(this)?.let { id ->
            ProfileManager.addTrafficStats(this, id, currentTxTotal, currentRxTotal)
        }
        currentTxTotal = 0L
        currentRxTotal = 0L
    }

    private fun updateNotification(contentText: String? = null) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(app.fjj.stun.R.string.service_mode_vpn), NotificationManager.IMPORTANCE_LOW)
        )

        val stopIntent = Intent(this, MyVpnService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = android.app.PendingIntent.getService(
            this, 0, stopIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val mainIntent = Intent(this, app.fjj.stun.ui.MainActivity::class.java)
        val mainPendingIntent = android.app.PendingIntent.getActivity(
            this, 0, mainIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(app.fjj.stun.R.string.notif_title))
            .setContentText(contentText ?: getString(app.fjj.stun.R.string.notif_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText ?: getString(app.fjj.stun.R.string.notif_text)))
            .setSubText(currentProfileName)
            .setSmallIcon(app.fjj.stun.R.drawable.ic_notification)
            .setColor(getThemeColor("colorPrimary", android.graphics.Color.BLUE))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(mainPendingIntent)
            .addAction(app.fjj.stun.R.drawable.ic_pause, getString(app.fjj.stun.R.string.disconnect), stopPendingIntent)

        val notification = notificationBuilder.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun getThemeColor(attrName: String, default: Int): Int {
        val attrId = resources.getIdentifier(attrName, "attr", packageName).takeIf { it != 0 }
            ?: resources.getIdentifier(attrName, "attr", "android").takeIf { it != 0 }
            ?: return default
            
        val typedValue = android.util.TypedValue()
        return if (theme.resolveAttribute(attrId, typedValue, true)) {
            if (typedValue.resourceId != 0) {
                androidx.core.content.ContextCompat.getColor(this, typedValue.resourceId)
            } else {
                typedValue.data
            }
        } else {
            default
        }
    }

    private fun startTrafficMonitor() {
        myssh.Myssh.registerTrafficCallback(TrafficCallback { txRate, rxRate, txTotal, rxTotal, activeConns, totalConns ->
            serviceScope.launch {
                updateStats(txRate, rxRate, txTotal, rxTotal, activeConns, totalConns)
            }
        })

        myssh.Myssh.registerSysInfoCallback(SysInfoCallback { cpuPercent, memAllocMB, memSysMB, goroutines ->
            serviceScope.launch {
                updateSysInfo(cpuPercent, memAllocMB, memSysMB, goroutines)
            }
        })
    }

    private suspend fun updateStats(txRate: Long, rxRate: Long, txTotal: Long, rxTotal: Long, activeConns: Long, totalConns: Long) {
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
        StunRepository.txTotal.postValue(txTotal)
        StunRepository.rxTotal.postValue(rxTotal)

        refreshNotification()
    }

    private suspend fun updateSysInfo(cpu: Double, mem: Double, memSys: Double, goroutines: Long) {
        currentCpu = cpu
        currentMem = mem
        currentMemSys = memSys
        currentGoroutines = goroutines
        refreshNotification()
    }

    private var lastNotificationUpdateTime = 0L
    private suspend fun refreshNotification() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastNotificationUpdateTime < 1000L) {
            return
        }
        lastNotificationUpdateTime = currentTime

        val statusText = "↑ ${app.fjj.stun.util.AppUtils.formatBytes(currentTxRate)}/s (${app.fjj.stun.util.AppUtils.formatBytes(currentTxTotal)}) " +
                "↓ ${app.fjj.stun.util.AppUtils.formatBytes(currentRxRate)}/s (${app.fjj.stun.util.AppUtils.formatBytes(currentRxTotal)}) | " +
                "Conns: $currentActiveConns/$currentTotalConns | " +
                "CPU: ${String.format(Locale.US, "%.1f", currentCpu)}% MEM: ${String.format(Locale.US, "%.1f", currentMem)}MB/${String.format(Locale.US, "%.1f", currentMemSys)}MB G: $currentGoroutines"

        withContext(Dispatchers.Main) {
            updateNotification(statusText)
        }
    }

    private fun log(message: String) = StunRepository.appendLog(message)

    override fun onDestroy() {
        userRequestedStop = true

        // Block to ensure critical cleanup completes before the process can be killed
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
