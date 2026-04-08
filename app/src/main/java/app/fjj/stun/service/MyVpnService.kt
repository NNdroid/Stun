package app.fjj.stun.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.concurrent.thread

@SuppressLint("VpnServicePolicy")
class MyVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val CHANNEL_ID = "StunVpnChannel"

    // 使用 Volatile 确保多线程间状态立即可见
    @Volatile private var userRequestedStop = false

    companion object {
        const val TAG = "MyVpnService"
        const val ACTION_START = "app.fjj.stun.START"
        const val ACTION_STOP = "app.fjj.stun.STOP"
        const val SOCKS_PORT = 10808
        const val DNS_PORT = 10853
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
                if (!StunRepository.vpnStatus.value!!) {
                    userRequestedStop = false
                    StunRepository.appendLog("Starting VPN Service...")
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

                // 1. 预清理可能残留的旧资源
                cleanupNative()

                // 2. 启动前台通知，更新 UI 状态
                updateNotification()
                StunRepository.vpnStatus.postValue(true)

                // 3. 加载日志与全局配置
                loadMySshLogger()
                loadGlobalConfigFromJson()

                // 4. 同步启动 Go SSH 代理核心
                val sshStatus = startSshGoLib()
                if (sshStatus != 0L) {
                    StunRepository.appendLog("❌ Go SSH Core failed to start (Code: $sshStatus). Retrying...")
                    throw RuntimeException("SSH Core start failed")
                }

                // 5. 配置并建立 VPN TUN 网卡
                val builder = Builder()
                    .setSession("StunSshTunnel")
                    .setMtu(1500)
                    .addAddress("10.0.0.2", 24)
                    .addRoute("0.0.0.0", 0)
                    .addAddress("fd00:1::2", 64)
                    .addRoute("::", 0)
                    .addDnsServer("8.8.8.8")
                    .addDisallowedApplication(packageName)

                vpnInterface = builder.establish()

                if (vpnInterface != null) {
                    val fd = vpnInterface!!.fd
                    StunRepository.appendLog("✅ TUN interface ready (FD: $fd)")

                    // 6. 启动 HEV 流量劫持引擎
                    startHevTunnel(fd)

                    StunRepository.appendLog("🚀 All services started. Tunnel is active.")

                    myssh.Myssh.wgWait()

                    StunRepository.appendLog("⚠️ WG Wait released.")
                } else {
                    StunRepository.appendLog("❌ Failed to establish TUN interface.")
                    throw RuntimeException("TUN establish failed")
                }

            } catch (e: Exception) {
                StunLogger.e(TAG, "Main Loop Interrupted", e)
            }

            // 🌟 8. 退出阻塞后的判断逻辑
            if (!userRequestedStop) {
                // 如果不是用户主动停止，说明是网络断开/核心崩溃触发的释放
                StunRepository.appendLog("🔄 Abnormal exit detected. Reconnecting in ${RECONNECT_DELAY / 1000}s...")
                cleanupNative() // 彻底清理这轮失败的资源
                Thread.sleep(RECONNECT_DELAY) // 延时后进入下一个 While 循环重新建立
            }
        }

        // 只有 userRequestedStop == true 才会跳出 while 循环来到这里
        cleanupNative()
        StunRepository.vpnStatus.postValue(false)
        StunRepository.appendLog("🛑 VPN main loop exited safely.")
    }

    private fun loadGlobalConfigFromJson() {
        val config = JSONObject().apply {
            put("remote_dns_server", SettingsManager.getRemoteDnsServer(this@MyVpnService))
            put("local_dns_server", SettingsManager.getLocalDnsServer(this@MyVpnService))
            put("geosite_filepath", SettingsManager.getGeositeCachePath(this@MyVpnService))
            put("geoip_filepath", SettingsManager.getGeoipCachePath(this@MyVpnService))
            put("direct_site_tags", JSONArray(SettingsManager.getGeositeDirectTags(this@MyVpnService)))
            put("direct_ip_tags", JSONArray(SettingsManager.getGeoipDirectTags(this@MyVpnService)))
        }
        myssh.Myssh.loadGlobalConfigFromJson(config.toString())
    }

    private fun loadMySshLogger() {
        val logPath = StunRepository.getLogFilePath(this@MyVpnService)
        val logLevel = SettingsManager.getLogLevel(this@MyVpnService)
        myssh.Myssh.initLogger(logPath, logLevel)
        myssh.Myssh.startWebLogger(10880, logPath)
    }

    /**
     * 同步启动 Go 代理核心
     * 返回 0 代表启动成功，其他代表失败
     */
    private fun startSshGoLib(): Long {
        val selectedProfile = ProfileManager.getSelectedProfile(this@MyVpnService)
        val config = JSONObject().apply {
            put("local_addr", "127.0.0.1:$SOCKS_PORT")
            put("ssh_addr", selectedProfile.sshAddr)
            put("user", selectedProfile.user)
            put("pass", selectedProfile.pass)
            put("tunnel_type", selectedProfile.tunnelType)
            put("proxy_addr", selectedProfile.proxyAddr)
            put("custom_host", selectedProfile.customHost)
            put("http_payload", selectedProfile.httpPayload)
        }

        StunRepository.appendLog("Go lib: Dialing SSH...")
        // 因为你在 Go 中修改的 StartSshTProxy 是非阻塞的，所以这里直接调用并获取结果即可
        return myssh.Myssh.startSshTProxy(config.toString())
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
            } catch (e: Exception) {
                StunLogger.e(TAG, "HEV Crash", e)
            }
        }
    }

    /**
     * 底层资源清理：需严格遵循关闭顺序
     */
    private fun cleanupNative() {
        // 1. 发送 Go 核心停止指令 (这会触发所有网络断开，并使得 wgWait 自动解除阻塞)
        try { myssh.Myssh.stopSshTProxy() } catch (_: Exception) {}

        // 2. 发送底层 HEV 停止指令
        try { hev.htproxy.TProxyService.TProxyStopService() } catch (_: Exception) {}

        // 3. 关闭网卡 FD (放在 HEV 之后关，防止 HEV 读取失效网卡崩溃)
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (_: Exception) {}

        // 4. 关闭日志模块
        try { myssh.Myssh.stopWebLogger() } catch (_: Exception) {}
    }

    private fun stopVpnService() {
        // userRequestedStop = true 已经在 onStartCommand 里设置过了
        // 这里我们只需要调用一次清理，Go 端一停，wgWait() 就会放行，主循环自然退出
        cleanupNative()
        stopForeground(true)
        stopSelf()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "VPN Status", NotificationManager.IMPORTANCE_LOW)
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(app.fjj.stun.R.string.notif_title))
            .setContentText(getString(app.fjj.stun.R.string.notif_text))
            .setSmallIcon(app.fjj.stun.R.drawable.ic_fox_logo)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1001, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1001, notification)
        }
    }

    override fun onDestroy() {
        userRequestedStop = true
        stopVpnService()
        super.onDestroy()
    }
}