package app.fjj.stun.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.GostRepository
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

    // 状态位：使用 Volatile 确保多线程可见性
    @Volatile private var isSshRunning = false
    @Volatile private var isHevRunning = false
    @Volatile private var userRequestedStop = false

    companion object {
        const val TAG = "MyVpnService"
        const val ACTION_START = "app.fjj.stun.START"
        const val ACTION_STOP = "app.fjj.stun.STOP"
        const val SOCKS_PORT = 10808
        const val DNS_PORT = 10853
        const val RECONNECT_DELAY = 3000L // 重连间隔 3 秒
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                userRequestedStop = true
                GostRepository.appendLog("用户主动停止服务...")
                stopVpnService()
            }
            else -> {
                userRequestedStop = false
                GostRepository.appendLog("启动 VPN 主循环...")
                thread(start = true, name = "VpnMainLoop") {
                    startVpnServiceLoop()
                }
            }
        }
        return START_STICKY
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

    /**
     * 主服务循环：实现自动重连逻辑
     */
    private fun startVpnServiceLoop() {
        while (!userRequestedStop) {
            try {
                GostRepository.appendLog("--- 正在初始化隧道环境 ---")

                // 1. 预清理旧资源
                cleanupNative()

                // 2. 启动前台通知 (Android 系统要求)
                updateNotification()
                GostRepository.vpnStatus.postValue(true)

                // 2.5 加载全局配置
                loadGlobalConfigFromJson()

                // 3. 启动 Go 语言 SSH 库
                startSshGoLib()

                // 4. 配置 VpnService Builder
                val builder = Builder()
                    .setSession("StunSshTunnel")
                    .setMtu(1500)
                    .addAddress("10.0.0.2", 24)
                    .addRoute("0.0.0.0", 0)
                    .addAddress("fd00:1::2", 64)
                    .addRoute("::", 0)
                    .addDnsServer("8.8.8.8")
                    .addDisallowedApplication(packageName)
                    //.addAllowedApplication("mark.via")

                // 5. 建立网卡
                vpnInterface = builder.establish()

                if (vpnInterface != null) {
                    val fd = vpnInterface!!.fd
                    GostRepository.appendLog("TUN 网卡就绪 (FD: $fd)")

                    // 6. 启动 HEV 引擎
                    startHevTunnel(fd)

                    // 7. 进入监控阻塞状态
                    monitorThreads()
                } else {
                    GostRepository.appendLog("无法建立虚拟网卡，准备重试...")
                }

            } catch (e: Exception) {
                GostRepository.appendLog("主循环发生错误: ${e.message}")
                Log.e(TAG, "Main Loop Error", e)
            }

            // 异常退出或建立失败后的处理
            if (!userRequestedStop) {
                GostRepository.appendLog("检测到异常退出，${RECONNECT_DELAY / 1000}秒后尝试重连...")
                cleanupNative()
                Thread.sleep(RECONNECT_DELAY)
            }
        }
        GostRepository.appendLog("VPN 主循环已安全退出。")
    }

    /**
     * 监控子线程存活状态
     */
    private fun monitorThreads() {
        GostRepository.appendLog("启动健康检查监控...")
        Thread.sleep(6000) // 给启动留出缓冲

        while (!userRequestedStop) {
            if (!isSshRunning || !isHevRunning) {
                val reason = if (!isSshRunning) "Go SSH 库掉线" else "HEV 引擎掉线"
                GostRepository.appendLog("【状态报警】: $reason")
                return // 退出监控，触发上一层循环重连
            }
            Thread.sleep(1500) // 每1.5秒检查一次
        }
    }

    private fun prepareHevConfigPath(): String {
        val confFile = File(cacheDir, "tproxy.conf")
        try {
            if (confFile.exists()) confFile.delete()
            confFile.createNewFile()

            // 详细配置：包含 IPv6 和 MapDNS 拦截
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
            GostRepository.appendLog("HEV 配置文件已更新。")
            return confFile.absolutePath
        } catch (e: IOException) {
            GostRepository.appendLog("配置文件写入失败: ${e.message}")
            return ""
        }
    }

    private fun startHevTunnel(fd: Int) {
        val configPath = prepareHevConfigPath()
        if (configPath.isEmpty()) return

        thread(start = true, name = "HevEngineThread") {
            try {
                isHevRunning = true
                GostRepository.appendLog("HEV 引擎启动中...")
                hev.htproxy.TProxyService.TProxyStartService(configPath, fd)
            } catch (e: Exception) {
                Log.e(TAG, "HEV Crash", e)
                GostRepository.appendLog("HEV 线程崩溃: ${e.message}")
                isHevRunning = false
            } finally {
            }
        }
    }

    private fun startSshGoLib() {
        var selectedProfile = ProfileManager.getSelectedProfile(this@MyVpnService)
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

        thread(start = true, name = "SshGoNativeThread") {
            try {
                isSshRunning = true
                GostRepository.appendLog("Go 库: 正在拨号 SSH...")
                val res = myssh.Myssh.startSshTProxy(config.toString())
                if (res != 0L) {
                    GostRepository.appendLog("Go 库非正常退出，代码: $res")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Go Lib Crash", e)
                GostRepository.appendLog("Go 线程崩溃: ${e.message}")
                isSshRunning = false
            } finally {

            }
        }
    }

    /**
     * 底层资源清理函数
     */
    private fun cleanupNative() {
        try {
            if (isHevRunning) hev.htproxy.TProxyService.TProxyStopService()
            isHevRunning = false
        } catch (e: Exception) {}

        try {
            if (isSshRunning) myssh.Myssh.stopSshTProxy()
            isSshRunning = false
        } catch (e: Exception) {}

        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {}
    }

    private fun stopVpnService() {
        cleanupNative()
        GostRepository.vpnStatus.postValue(false)
        stopForeground(true)
        stopSelf()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "SSH VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SSH 代理运行中")
            .setContentText("全设备流量 (IPv4/IPv6) 加密保护中")
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