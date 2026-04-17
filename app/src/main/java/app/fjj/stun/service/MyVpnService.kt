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

    private var lastTxBytes = 0L
    private var lastRxBytes = 0L
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null

    @Volatile private var userRequestedStop = false

    companion object {
        const val TAG = "StunVpnService"
        const val ACTION_START = "app.fjj.stun.START"
        const val ACTION_STOP = "app.fjj.stun.STOP"
        const val SOCKS_PORT = 10808
        const val RECONNECT_DELAY = 3000L
        const val CHANNEL_ID = "StunVpnChannel"
        const val NOTIFICATION_ID = 1001
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
                val sshStatus = myssh.Myssh.startSshTProxy2(VpnConfigBuilder.buildSshConfig(this, profile, SOCKS_PORT))
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

    private fun startHevTunnelEngine(fd: Int) {
        val confFile = File(cacheDir, "tproxy.conf")
        try {
            FileOutputStream(confFile).use { it.write(VpnConfigBuilder.buildHevConfig(SOCKS_PORT).toByteArray()) }
            
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
        monitorJob?.cancel()
        try { myssh.Myssh.stopSshTProxy() } catch (_: Exception) {}
        try { TTunnelService.TTunnelStopService() } catch (_: Exception) {}
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (_: Exception) {}
    }

    private fun stopVpnService() {
        cleanupNativeResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification(contentText: String? = null) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "VPN Status", NotificationManager.IMPORTANCE_LOW)
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
        monitorJob?.cancel()
        lastTxBytes = 0L
        lastRxBytes = 0L
        monitorJob = serviceScope.launch {
            while (isActive) {
                val stats = try { TTunnelService.TTunnelGetStats() } catch (_: Exception) { null }
                if (stats != null && stats.size >= 4) {
                    updateStats(stats[1], stats[3])
                }
                delay(1000)
            }
        }
    }

    private suspend fun updateStats(currentTxBytes: Long, currentRxBytes: Long) {
        val txSpeed = if (lastTxBytes > 0) currentTxBytes - lastTxBytes else 0L
        val rxSpeed = if (lastRxBytes > 0) currentRxBytes - lastRxBytes else 0L

        lastTxBytes = currentTxBytes
        lastRxBytes = currentRxBytes

        SettingsManager.getSelectedProfileId(this)?.let { id ->
            ProfileManager.updateTrafficStats(this, id, currentTxBytes, currentRxBytes)
        }

        val statusText = "↑ ${formatBytes(txSpeed)}/s (${formatBytes(currentTxBytes)}) " +
                         "↓ ${formatBytes(rxSpeed)}/s (${formatBytes(currentRxBytes)})"

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
}
