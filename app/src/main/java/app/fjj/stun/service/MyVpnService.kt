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
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.repo.VpnState
import app.fjj.stun.util.KeystoreUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.pow

@SuppressLint("VpnServicePolicy")
class MyVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val CHANNEL_ID = "StunVpnChannel"

    private var lastTxBytes = 0L
    private var lastRxBytes = 0L
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null

    // 使用 Volatile 确保多线程间状态立即可见
    @Volatile private var userRequestedStop = false

    companion object {
        const val TAG = "MyVpnService"
        const val ACTION_START = "app.fjj.stun.START"
        const val ACTION_STOP = "app.fjj.stun.STOP"
        const val SOCKS_PORT = 10808
        const val RECONNECT_DELAY = 3000L // 异常断开重连间隔
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                StunRepository.appendLog("User requested to stop service...")
                userRequestedStop = true
                stopVpnService()
            }
            else -> {
                // 安全获取当前状态，默认为断开
                val currentState = StunRepository.vpnState.value ?: VpnState.DISCONNECTED

                // 仅在断开或错误状态下允许发起新连接，防止用户连点导致多开
                if (currentState == VpnState.DISCONNECTED || currentState == VpnState.ERROR) {
                    userRequestedStop = false
                    StunRepository.appendLog("Starting VPN Service...")

                    // 立即通知 UI 进入“连接中”状态
                    StunRepository.vpnState.postValue(VpnState.CONNECTING)

                    // 开启一个后台主线程来控制生命周期
                    thread(start = true, name = "VpnMainLoop") {
                        startVpnServiceLoop()
                    }
                }
            }
        }
        return START_STICKY
    }

    /**
     * 主服务循环：利用 wgWait 阻塞，实现优雅的生命周期与自动重连
     */
    private fun startVpnServiceLoop() {
        while (!userRequestedStop) {
            try {
                StunRepository.appendLog("--- Initializing tunnel environment ---")

                // 1. 启动前台通知
                updateNotification()

                // 2. 加载全局配置
                loadGlobalConfigFromJson()

                // 3. 同步启动 Go SSH 代理核心
                val sshStatus = startSshGoLib()
                if (sshStatus != 0L) {
                    StunRepository.appendLog("❌ Go SSH Core failed to start (Code: $sshStatus). Retrying...")
                    throw RuntimeException("SSH Core start failed")
                }

                // 4. 配置并建立 VPN TUN 网卡
                val builder = Builder()
                    .setSession("StunSshTunnel")
                    .setMtu(1500)
                    .addAddress("10.0.0.2", 24)
                    .addRoute("0.0.0.0", 0)
                    .addAddress("fd00:1::2", 64)
                    .addRoute("::", 0)
                    .addDnsServer("8.8.8.8")

                // Configure App Filtering
                val selectedProfile = ProfileManager.getSelectedProfile(this@MyVpnService)
                val appFilterOverride = selectedProfile.appFilterOverride
                val filterApps = if (appFilterOverride) selectedProfile.filterApps else SettingsManager.getFilterApps(this@MyVpnService)
                val filterMode = if (appFilterOverride) selectedProfile.filterMode else SettingsManager.getFilterMode(this@MyVpnService)

                if (filterApps.isNotBlank()) {
                    val apps = filterApps.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    if (filterMode == 1) { // Allow
                        for (app in apps) {
                            try {
                                builder.addAllowedApplication(app)
                            } catch (e: Exception) {
                                StunRepository.appendLog("⚠️ Failed to add app to allowed filter: $app")
                            }
                        }
                    } else { // Disallow
                        for (app in apps) {
                            try {
                                builder.addDisallowedApplication(app)
                            } catch (e: Exception) {
                                StunRepository.appendLog("⚠️ Failed to add app to disallowed filter: $app")
                            }
                        }
                        builder.addDisallowedApplication(packageName)
                    }
                } else {
                    builder.addDisallowedApplication(packageName)
                }

                vpnInterface = builder.establish()

                if (vpnInterface != null) {
                    val fd = vpnInterface!!.fd
                    StunRepository.appendLog("✅ TUN interface ready (FD: $fd)")

                    // 5. 启动 HEV 流量劫持引擎
                    startHevTunnel(fd)

                    StunRepository.appendLog("🚀 All services started. Tunnel is active.")

                    // 🌟 核心就绪，正式通知 UI “已连接”
                    StunRepository.vpnState.postValue(VpnState.CONNECTED)
                    startTrafficMonitor()

                    // 阻塞当前线程，等待 Go 核心释放
                    myssh.Myssh.wgWait()

                    StunRepository.appendLog("⚠️ WG Wait released.")
                } else {
                    StunRepository.appendLog("❌ Failed to establish TUN interface.")
                    throw RuntimeException("TUN establish failed")
                }

            } catch (e: Exception) {
                StunLogger.e(TAG, "Main Loop Interrupted", e)
            } finally {
                // 🌟 核心优化：不论如何退出的，只要离开核心逻辑区，立刻清理资源并更新状态
                cleanupNative()

                if (userRequestedStop) {
                    StunRepository.vpnState.postValue(VpnState.DISCONNECTED)
                } else {
                    StunRepository.vpnState.postValue(VpnState.RECONNECTING)
                }
            }

            // 🌟 判断退出阻塞后的动作
            if (!userRequestedStop) {
                // 如果不是用户主动停止，说明是网络断开/核心崩溃触发的释放
                StunRepository.appendLog("🔄 Abnormal exit detected. Reconnecting in ${RECONNECT_DELAY / 1000}s...")
                Thread.sleep(RECONNECT_DELAY) // 延时后进入下一个 While 循环重新建立
            }
        }

        // 彻底跳出 while 循环（用户主动要求停止）
        StunRepository.vpnState.postValue(VpnState.DISCONNECTED)
        StunRepository.appendLog("🛑 VPN main loop exited safely.")
    }

    private fun loadGlobalConfigFromJson() {
        val selectedProfile = ProfileManager.getSelectedProfile(this@MyVpnService)
        
        val remoteDns = if (selectedProfile.dnsOverride) selectedProfile.remoteDns else SettingsManager.getRemoteDnsServer(this@MyVpnService)
        val localDns = if (selectedProfile.dnsOverride) selectedProfile.localDns else SettingsManager.getLocalDnsServer(this@MyVpnService)
        val geositeDirect = if (selectedProfile.dnsOverride) selectedProfile.geositeDirect.split(",").filter { it.isNotBlank() } else SettingsManager.getGeositeDirectTags(this@MyVpnService)
        val geoipDirect = if (selectedProfile.dnsOverride) selectedProfile.geoipDirect.split(",").filter { it.isNotBlank() } else SettingsManager.getGeoipDirectTags(this@MyVpnService)

        val config = JSONObject().apply {
            put("remote_dns_server", remoteDns)
            put("local_dns_server", localDns)
            put("geosite_filepath", SettingsManager.getGeositeCachePath(this@MyVpnService))
            put("geoip_filepath", SettingsManager.getGeoipCachePath(this@MyVpnService))
            put("direct_site_tags", JSONArray(geositeDirect))
            put("direct_ip_tags", JSONArray(geoipDirect))
        }
        myssh.Myssh.loadGlobalConfigFromJson(config.toString())
    }

    /**
     * 同步启动 Go 代理核心
     * 返回 0 代表启动成功，其他代表失败
     */
    private fun startSshGoLib(): Long {
        val selectedProfile = ProfileManager.getSelectedProfile(this@MyVpnService)
        
        val udpgwAddr = if (selectedProfile.dnsOverride) selectedProfile.udpgwAddr else SettingsManager.getUdpgwAddr(this@MyVpnService)

        val config = JSONObject().apply {
            put("local_addr", "127.0.0.1:$SOCKS_PORT")
            put("ssh_addr", selectedProfile.sshAddr)
            put("user", selectedProfile.user)
            put("auth_type", selectedProfile.authType)
            put("pass", selectedProfile.pass)
            put("private_key", selectedProfile.privateKey)
            put("private_key_passphrase", KeystoreUtils.decrypt(selectedProfile.keyPass))
            put("tunnel_type", selectedProfile.tunnelType)
            put("proxy_addr", selectedProfile.proxyAddr)
            put("proxy_auth_required", selectedProfile.proxyAuthRequired)
            put("proxy_auth_user", selectedProfile.proxyAuthUser)
            put("proxy_auth_pass", selectedProfile.proxyAuthPass)
            put("proxy_auth_token", selectedProfile.proxyAuthToken)
            put("custom_host", selectedProfile.customHost)
            put("server_name", selectedProfile.serverName)
            put("custom_path", if (!selectedProfile.enableCustomPath) "" else selectedProfile.customPath)
            put("http_payload", selectedProfile.httpPayload)
            put("udpgw_addr", udpgwAddr)
            put("disable_status_check", selectedProfile.disableStatusCheck)
        }

        StunRepository.appendLog("Go lib: Dialing SSH with UdpGW: $udpgwAddr...")
        return myssh.Myssh.startSshTProxy2(config.toString())
    }

    private fun prepareHevConfigPath(): String {
        val confFile = File(cacheDir, "tproxy.conf")
        try {
            if (confFile.exists()) confFile.delete()
            confFile.createNewFile()

            val tproxyConf = """
                misc:
                  task-stack-size: 8192
                  log-level: warn
                tunnel:
                  mtu: 1500
                  ipv4: true
                  ipv6: true
                socks5:
                  port: $SOCKS_PORT
                  address: 127.0.0.1
                  udp: udp
            """.trimIndent()

            FileOutputStream(confFile).use { it.write(tproxyConf.toByteArray()) }
            return confFile.absolutePath
        } catch (e: IOException) {
            StunRepository.appendLog("Failed to write HEV config: ${e.message}")
            return ""
        }
    }

    private fun startHevTunnel(fd: Int) {
        val configPath = prepareHevConfigPath()
        if (configPath.isEmpty()) return

        thread(start = true, name = "HevEngineThread") {
            try {
                StunRepository.appendLog("HEV engine starting...")
                hev.htproxy.TProxyService.TProxyStartService(configPath, fd)
                StunRepository.appendLog("HEV engine started.")
            } catch (e: Exception) {
                StunLogger.e(TAG, "HEV Crash", e)
            }
        }
    }

    /**
     * 底层资源清理：需严格遵循关闭顺序，且多次调用绝对安全
     */
    private fun cleanupNative() {
        monitorJob?.cancel()

        // 1. 发送 Go 核心停止指令 (这会触发所有网络断开，并使得 wgWait 自动解除阻塞)
        try { myssh.Myssh.stopSshTProxy() } catch (_: Exception) {}

        // 2. 发送底层 HEV 停止指令
        try { hev.htproxy.TProxyService.TProxyStopService() } catch (_: Exception) {}

        // 3. 关闭网卡 FD (放在 HEV 之后关，防止 HEV 读取失效网卡崩溃)
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (_: Exception) {}
    }

    private fun stopVpnService() {
        // userRequestedStop = true 已经在 onStartCommand 里设置过了
        // 调用 cleanupNative 后，Go 端一停，wgWait() 就会放行，主循环自然退出。
        // 多次调用 cleanupNative 是安全的，try-catch 做了防御。
        cleanupNative()
        stopForeground(true)
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
            startForeground(1001, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1001, notification)
        }
    }

    private fun startTrafficMonitor() {
        monitorJob?.cancel()
        lastTxBytes = 0L
        lastRxBytes = 0L
        monitorJob = scope.launch {
            while (isActive) {
                val stats = try { hev.htproxy.TProxyService.TProxyGetStats() } catch (e: Exception) { null }
                if (stats != null && stats.size >= 4) {
                    val currentTxBytes = stats[1]
                    val currentRxBytes = stats[3]

                    val txSpeedBytes = if (lastTxBytes > 0) currentTxBytes - lastTxBytes else 0L
                    val rxSpeedBytes = if (lastRxBytes > 0) currentRxBytes - lastRxBytes else 0L

                    lastTxBytes = currentTxBytes
                    lastRxBytes = currentRxBytes

                    // Update profile stats in database
                    val selectedProfileId = SettingsManager.getSelectedProfileId(this@MyVpnService)
                    if (selectedProfileId != null) {
                        ProfileManager.updateTrafficStats(this@MyVpnService, selectedProfileId, currentTxBytes, currentRxBytes)
                    }

                    val statusText = "↑ ${formatBytes(txSpeedBytes)}/s (${formatBytes(currentTxBytes)}) " +
                                   "↓ ${formatBytes(rxSpeedBytes)}/s (${formatBytes(currentRxBytes)})"

                    withContext(Dispatchers.Main) {
                        updateNotification(statusText)
                    }
                }
                delay(1000)
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        return String.format(java.util.Locale.US, "%.1f %s", bytes / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
    }

    override fun onDestroy() {
        userRequestedStop = true
        stopVpnService()
        scope.cancel()
        super.onDestroy()
    }
}