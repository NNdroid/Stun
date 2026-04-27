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
        stopVpnService()
    }

    private fun handleStartRequest() {
        val currentState = StunRepository.vpnState.value ?: VpnState.DISCONNECTED
        if (currentState == VpnState.DISCONNECTED || currentState == VpnState.ERROR) {
            userRequestedStop = false
            log("Starting VPN Service...")
            StunRepository.vpnState.postValue(VpnState.CONNECTING)
            thread(start = true, name = "VpnMainLoop") {
                startVpnServiceLoop()
            }
        }
    }

    private fun startVpnServiceLoop() {
        while (!userRequestedStop) {
            try {
                log("--- Initializing tunnel environment ---")
                updateNotification()

                val profile = ProfileManager.getSelectedProfile(this)
                
                // 1. Core Config
                myssh.Myssh.loadGlobalConfigFromJson(VpnConfigBuilder.buildGlobalConfig(this, profile))
                
                // 2. Start SSH
                val sshStatus = myssh.Myssh.startSshTProxy2(VpnConfigBuilder.buildMySshConfig(this, profile, SOCKS_PORT, DNS_PORT))
                if (sshStatus != 0L) {
                    log("❌ Go SSH Core failed to start (Code: $sshStatus). Retrying...")
                    throw RuntimeException("SSH Core start failed")
                }

                // 3. Establish TUN
                vpnInterface = createVpnInterface(profile)
                val fd = vpnInterface?.fd ?: throw RuntimeException("TUN establish failed")
                log("✅ TUN interface ready (FD: $fd)")

                // 4. Start HEV Engine
                startHevTunnelEngine(fd)

                log("🚀 All services started. Tunnel is active.")
                StunRepository.vpnState.postValue(VpnState.CONNECTED)
                startTrafficMonitor()

                // 5. Shizuku Optimizations
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
        
        if (filterMode != 1) {
            try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
        }
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
            
            thread(start = true, name = "HevEngineThread") {
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

        try { TTunnelService.TTunnelStopService() } catch (_: Exception) {}
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (_: Exception) {}

        try { myssh.Myssh.stopSshTProxy() } catch (_: Exception) {}
    }

    private fun stopVpnService() {
        cleanupNativeResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification(contentText: String? = null) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(app.fjj.stun.R.string.service_mode_vpn), NotificationManager.IMPORTANCE_LOW)
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(app.fjj.stun.R.string.notif_title))
            .setContentText(contentText ?: getString(app.fjj.stun.R.string.notif_text))
            .setSmallIcon(app.fjj.stun.R.drawable.ic_fox_logo)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startTrafficMonitor() {
        myssh.Myssh.registerTrafficCallback(object : myssh.TrafficCallback {
            override fun onTrafficUpdate(txRate: Long, rxRate: Long, txTotal: Long, rxTotal: Long) {
                serviceScope.launch {
                    updateStats(txRate, rxRate, txTotal, rxTotal)
                }
            }
        })

        myssh.Myssh.registerSysInfoCallback(object : myssh.SysInfoCallback {
            override fun onSysInfoUpdate(cpuPercent: Double, memAllocMB: Double, memSysMB: Double, goroutines: Long) {
                serviceScope.launch {
                    updateSysInfo(cpuPercent, memAllocMB)
                }
            }
        })
    }

    private suspend fun updateStats(txRate: Long, rxRate: Long, txTotal: Long, rxTotal: Long) {
        currentTxRate = txRate
        currentRxRate = rxRate
        currentTxTotal = txTotal
        currentRxTotal = rxTotal

        SettingsManager.getSelectedProfileId(this)?.let { id ->
            ProfileManager.updateTrafficStats(this, id, txTotal, rxTotal)
        }
        refreshNotification()
    }

    private suspend fun updateSysInfo(cpu: Double, mem: Double) {
        currentCpu = cpu
        currentMem = mem
        refreshNotification()
    }

    private suspend fun refreshNotification() {
        val statusText = "↑ ${formatBytes(currentTxRate)}/s (${formatBytes(currentTxTotal)}) " +
                         "↓ ${formatBytes(currentRxRate)}/s (${formatBytes(currentRxTotal)}) | " +
                         "CPU: ${String.format(Locale.US, "%.1f", currentCpu)}% MEM: ${String.format(Locale.US, "%.1f", currentMem)}MB"

        withContext(Dispatchers.Main) {
            updateNotification(statusText)
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        return String.format(Locale.US, "%.1f %s", bytes / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
    }

    private fun log(message: String) = StunRepository.appendLog(message)

    override fun onDestroy() {
        userRequestedStop = true
        stopVpnService()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        userRequestedStop = true
        stopVpnService()
        serviceScope.cancel()
        super.onRevoke()
    }
}
