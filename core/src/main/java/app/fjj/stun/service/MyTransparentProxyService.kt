package app.fjj.stun.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.fjj.stun.core.R
import app.fjj.stun.core.BuildConfig
import app.fjj.stun.repo.*
import app.fjj.stun.util.AppBootstrap
import app.fjj.stun.util.BackgroundExemptions
import app.fjj.stun.util.RootShell
import kotlinx.coroutines.*
import myssh.SysInfoCallback
import myssh.TrafficCallback
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * a root-based transparent proxy.
 * Uses iptables (via tproxy.sh) and hev-socks5-tproxy core.
 */
class MyTransparentProxyService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 收尾专用作用域。
     *
     * 刻意**不挂在** [serviceScope] 下：`onDestroy` 紧接着就 `serviceScope.cancel()`，
     * 收尾任务挂上去等于每次都在自己把自己掐掉。也不用 `GlobalScope` —— 那是全局 Job，
     * 没有归属、异常无人接、也无法在测试里等待；这里给它一个明确的 `SupervisorJob`，
     * 收尾链里任何一步抛异常都不会连累后续步骤（`stopWatchdog` / `applyRules` 都调 root 命令，
     * 失败是常态而非例外）。
     *
     * 它活到进程结束为止：Service 被销毁重建时旧作用域已无挂起任务，随 GC 回收。
     */
    private val teardownScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var mainJob: Job? = null
    private var coreJob: Job? = null
    private val isRunning = AtomicBoolean(false)
    @Volatile private var isForegroundStarted = false

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

    private val statsSink = TrafficStatsSink(this)

    private var currentProfileName: String = ""
    private val TAG: String
        get() = "TProxyService-[${Thread.currentThread().name}]"

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    /**
     * SO_MARK 通路是否真的可用（由 [registerSocketMarkHelper] 的探测决定）。
     *
     * 决定 [applyRules] 生成哪种旁路规则，**false 时必须回落到 uid 放行**：
     * 隧道 socket 若既没 mark 又不在 `BYPASS_APPS_LIST` 里，会被 TPROXY 抓回
     * 本地 socks5 造成死循环。@Volatile 是因为写入在服务主协程、读取在 applyRules 侧。
     */
    @Volatile private var socketMarkAvailable: Boolean = false

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
        // 端口取自 [TProxyPorts]（单一事实来源）。tproxy 的 TPROXY 端口与 SOCKS 分离，
        // 三个值都与 tproxy.sh 的 DEFAULT_PROXY_TCP_PORT / DEFAULT_DNS_PORT 成对。
        private const val SOCKS_PORT = TProxyPorts.TProxy.SOCKS
        private const val TPROXY_PORT = TProxyPorts.TProxy.TPROXY
        private const val DNS_HIJACK_PORT = TProxyPorts.TProxy.DNS

        private const val BIN_HEV_SOCKS5_TPROXY = "hev-socks5-tproxy"
        private const val BIN_SOCKMARK = "sockmark"
        private const val FILE_HEV_SOCKS5_TPROXY_CONF = "hev-socks5-tproxy.conf"
        private const val FILE_HEV_SOCKS5_TPROXY_LOG = "tproxy.log"

        private const val SCRIPT_TPROXY = "tproxy.sh"

        /**
         * 规则文件名。⚠️ 必须与 `tproxy.sh` 里 `source "$CONFIG_DIR/<此名>"` 保持一致 ——
         * 两边对不上时脚本 source 不到文件，而 sh 默认**不报错**，于是所有 iptables 规则
         * 静默不生效：服务显示已连接，实际全部直连。改这里必须同步改脚本。
         */
        private const val FILE_TPROXY_RULES = "tproxy_rules.conf"

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
        // startForegroundService 启动的服务必须在系统时限内进入前台，否则超时抛
        // ForegroundServiceDidNotStartInTimeException。isRunning 守卫和 ACTION_STOP
        // 路径都不能跳过这一步，故在分派前无条件同步调用。
        startForegroundNow(getString(R.string.main_connecting))
        when (intent?.action) {
            ACTION_STOP -> stopTProxy()
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

        StunRepository.registerEngineCallback(this)
        StunRepository.vpnState.postValue(VpnState.CONNECTING)
        updateNotification(getString(R.string.main_connecting))
        acquireLocks()

        mainJob = serviceScope.launch {
            if (!withContext(Dispatchers.IO) { RootShell.isRoot() }) {
                StunLogger.e(TAG, "Root permission required but not granted. Stopping service.")
                isRunning.set(false)
                StunRepository.vpnState.postValue(VpnState.DISCONNECTED)
                withContext(Dispatchers.Main) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return@launch
            }

            // TProxy 三件套（二进制 + tproxy.sh / watchdog.sh）与 geoip/geosite 都由
            // AppBootstrap 在 IO 上部署，而紧接着的 applyRules 就要执行 cacheDir 里的
            // tproxy.sh（还有下面 buildGlobalConfig 要交给 Go 侧的规则库路径）——
            // 必须等它落地，否则这一步会静默失败、留一堆没人清的旧 iptables 规则。
            AppBootstrap.awaitAssets(context)

            try {
                StunLogger.i(TAG, "--- Start Sequence Initiated ---")

                applyRules(enabled = false)

                val profile = ProfileManager.getSelectedProfile(context)
                currentProfileName = profile.name

                // 不在此清零流量基准：Go 的 TxTotal/RxTotal 为进程级全局单调计数器，
                // 清零会导致每次会话开始把整个累计值重新写库，跨重连重复计数。基准跨重连保留。
                val cfgStatus = StunRepository.proxy.loadGlobalConfig(VpnConfigBuilder.buildGlobalConfig(context, profile))
                if (cfgStatus != 0L) throw RuntimeException("Global config load failed: $cfgStatus")
                // 必须在 start 之前注册：start 内部立刻拨 SSH，socket 一旦建出来首个 SYN
                // 就发出去，此时若还没有 mark 通路，SSH 连接会被自己的 TPROXY 抓回本地 socks5。
                // 探测结果决定 applyRules 走 mark 还是 uid 旁路。
                socketMarkAvailable = registerSocketMarkHelper()
                val sshStatus = StunRepository.proxy.start(VpnConfigBuilder.buildMySshConfig(context, profile, SOCKS_PORT, DNS_HIJACK_PORT))
                if (sshStatus != 0L) throw RuntimeException("SSH Core failed to start with status: $sshStatus")
                StunLogger.i(TAG, "SSH Core started successfully.")

                val yamlConfig = TransparentProxyConfigBuilder.buildCoreYaml(SOCKS_PORT, TPROXY_PORT)
                File(cacheDir, FILE_HEV_SOCKS5_TPROXY_CONF).writeText(yamlConfig)

                startCoreEngine()

                delay(1000)

                applyRules(enabled = true)

                ProfileManager.markConnected(context, profile.id)
                StunRepository.vpnState.postValue(VpnState.CONNECTED)
                updateNotification(getString(R.string.notif_text))

                optimizeSystemForBackground()
                BackgroundExemptions.applyViaShizuku(packageName)

                startWatchdog()
                startTrafficMonitor()

                StunRepository.proxy.wgWait()
                StunLogger.i(TAG, "wgWait() returned gracefully.")

            } catch (e: Exception) {
                StunLogger.e(TAG, "Critical Failure during start sequence: ${e.message}", e)
                stopTProxy()
            }
        }
    }

    /**
     * 把 `sockmark` 二进制的位置与 mark 值交给 Go 侧，然后**真跑一次探测**。
     *
     * 由 Go 自己 fork 这个 helper（见 `core/jni/myssh/socket_mark_android.go`），而不是这里
     * 另起一个进程：请求-响应必须与 socket 创建同生命周期，跨进程另起 helper 会让
     * 「拿到 fd」与「helper 还活着」之间出现竞态窗口。Go 侧经 `su` 拿到 root 身份。
     *
     * @return mark 通路是否真的可用。**false 时 [applyRules] 必须回落到 uid 放行** ——
     *   隧道 socket 若既没 mark 又不在旁路列表，会被 TPROXY 抓回本地 socks5 造成死循环。
     */
    private fun registerSocketMarkHelper(): Boolean {
        val helper = File(cacheDir, BIN_SOCKMARK)
        if (!helper.exists()) {
            // 部署没成功（assets 缺失 / 旧包没重新安装）——不注册，规则侧走 uid 降级。
            StunLogger.w(TAG, "sockmark helper missing at ${helper.absolutePath}; falling back to uid bypass")
            StunRepository.proxy.registerSocketMarkHelper("", 0L, 0L)
            return false
        }
        // appPID 传 0：Go 侧用 os.Getpid() 自取，比在 Kotlin 这边再算一遍可靠。
        // ⚠️ gomobile 把 Go 的 `int` 映射成 Java `long`，所以 mark 与 pid 都要传 Long。
        StunRepository.proxy.registerSocketMarkHelper(helper.absolutePath, 0L, TProxyPorts.SOCKET_MARK.toLong())
        StunLogger.i(TAG, "sockmark helper registered: ${helper.absolutePath} mark=0x${Integer.toHexString(TProxyPorts.SOCKET_MARK)}")

        // 探测：**必须真的设一次 mark 成功**才敢让规则层走 mark 模式。
        // helper 虽是 App 自己 fork 的，但提权（su）、借 fd（pidfd_getfd，Linux 5.6+）、
        // 以及某些 ROM 的 ptrace 策略都可能失败；而这些失败在拨号阶段只会表现为
        // 「SSH 连不上」，根因极难定位。这里提前把失败暴露成一次明确的日志。
        val ok = try {
            StunRepository.proxy.probeSocketMark() == 1L
        } catch (e: UnsatisfiedLinkError) {
            // 旧 AAR 里没有这个方法：说明装的 APK 与当前 libs 不匹配，当作不可用。
            StunLogger.w(TAG, "probeSocketMark unavailable (stale AAR?): ${e.message}")
            false
        }
        if (ok) {
            StunLogger.i(TAG, "SO_MARK probe succeeded; tunnel socket will bypass tproxy by mark")
        } else {
            StunLogger.w(TAG, "SO_MARK probe failed; falling back to uid bypass (App traffic will not be proxied)")
        }
        return ok
    }

    private fun startCoreEngine() {        coreJob = serviceScope.launch {
            val coreFile = File(cacheDir, BIN_HEV_SOCKS5_TPROXY)
            val configFile = File(cacheDir, FILE_HEV_SOCKS5_TPROXY_CONF)
            val logFile = File(cacheDir, FILE_HEV_SOCKS5_TPROXY_LOG)

            val cmd = "nohup ${coreFile.absolutePath} ${configFile.absolutePath} > ${logFile.absolutePath} 2>&1 &"
            StunLogger.i(TAG, "Executing Start hev-socks5-tproxy Cmd: $cmd")
            RootShell.exec(cmd)
        }
    }

    private fun stopCoreEngine() {
        StunLogger.i(TAG, "Killing TProxy binary processes...")
        RootShell.exec("killall -9 $BIN_HEV_SOCKS5_TPROXY || true")
    }

    private fun applyRules(enabled: Boolean) {
        val cachePath = cacheDir.absolutePath
        val scriptFile = File(cacheDir, SCRIPT_TPROXY)
        // 路径一律加引号：cacheDir 在多用户/工作资料场景可能带空格，未加引号时脚本会被拆成
        // 两个参数，source 找不到配置文件 —— 而 sh 对 source 失败不报错，最终表现为"规则静默失效"。
        val quotedCache = "\"$cachePath\""
        val verbose = if (BuildConfig.DEBUG) listOf("--verbose") else emptyList()
        val verb = if (enabled) "start" else "stop"

        if (enabled) {
            StunLogger.i(TAG, "Enabling TProxy firewall rules...")
            // 规则文件每次重连都重写：分应用代理是运行期可改的，缓存里的旧文件不能当权威。
            val shellConfig = TransparentProxyConfigBuilder.buildShellRules(
                selfPackage = packageName,
                tproxyPort = TPROXY_PORT,
                dnsPort = DNS_HIJACK_PORT,
                appFilter = resolveAppFilter(),
                // ⚠️ 探测失败必须传 0：让 Builder 把 App 放回 BYPASS_APPS_LIST。
                // 若此处仍传 SOCKET_MARK，隧道 socket 既没 mark 又不在旁路列表，
                // 会被 TPROXY 抓回本地 socks5 —— 死循环，SSH 完全连不上。
                socketMark = if (socketMarkAvailable) TProxyPorts.SOCKET_MARK else 0,
            )
            File(cacheDir, FILE_TPROXY_RULES).writeText(shellConfig)
        } else {
            StunLogger.i(TAG, "Disabling TProxy firewall rules...")
        }

        RootShell.exec(listOf(scriptFile.absolutePath, "-d", quotedCache).plus(verbose).plus(verb).joinToString(" "))
    }

    /**
     * 当前生效的分应用代理配置。
     *
     * 取值与 VPN 模式走同一个 [AppFilterResolver]（override 语义只在那里定义一次）。
     * 这里多一步"读当前 profile"：规则是整段 shell 变量、要在 root 侧被 `source`，
     * 没有 `Builder.addAllowedApplication` 那种逐个应用的挂载点，只能在生成配置时整份算出来。
     */
    private fun resolveAppFilter(): AppFilter {
        val profile = ProfileManager.getSelectedProfile(this) ?: return AppFilter.EMPTY
        return AppFilterResolver.resolve(
            overrideEnabled = profile.appFilterOverride,
            profileFilterApps = profile.filterApps,
            profileFilterMode = profile.filterMode,
            globalFilterApps = SettingsManager.getFilterApps(this),
            globalFilterMode = SettingsManager.getFilterMode(this),
        )
    }

    private fun stopTProxy() {
        if (!isRunning.compareAndSet(true, false)) {
            StunLogger.w(TAG, "Service is not running, ignoring stop request.")
            return
        }

        mainJob?.cancel()
        coreJob?.cancel()
        releaseLocks()

        teardownScope.launch {
            try {
                stopTrafficMonitor()
                stopWatchdog()
                applyRules(enabled = false)
                stopCoreEngine()
                try {
                    StunRepository.proxy.stop()
                } catch (_: Exception) {}
            } finally {
                // 撤销后台豁免：deviceidle 白名单是**跨重启留存**的，不撤的话用户断开一次之后
                // 本 App 就永久留在系统白名单里（卸载都不一定清得掉），既耗电也是隐私。
                revertBackgroundOptimizations()
                StunRepository.vpnState.postValue(VpnState.DISCONNECTED)
                withContext(Dispatchers.Main) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun optimizeSystemForBackground() {
        BackgroundExemptions.applyViaRoot(packageName)
    }

    /** [optimizeSystemForBackground] 的逆操作。与 Shizuku 那个开关一样按"启用才撤销"处理。 */
    private fun revertBackgroundOptimizations() {
        BackgroundExemptions.revertViaRoot(packageName)
    }

    private fun startWatchdog() {
        val pid = android.os.Process.myPid()
        val cachePath = cacheDir.absolutePath
        val scriptPath = File(cacheDir, SCRIPT_WATCHDOG).absolutePath
        val watchdogLogFile = File(cacheDir, FILE_WATCHDOG_LOG).absolutePath
        val cmd = "nohup sh \"$scriptPath\" $pid \"$cachePath\" $packageName > \"$watchdogLogFile\" 2>&1 &"
        RootShell.exec(cmd)
    }

    private fun stopWatchdog() {
        val killCmd = "pkill -f $SCRIPT_WATCHDOG || kill -9 \$(ps -A | grep $SCRIPT_WATCHDOG | grep -v grep | awk '{print \$2}') || true"
        RootShell.exec(killCmd)
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

    private fun stopTrafficMonitor() {
        app.fjj.stun.util.LatencyProber.stop()
        StunRepository.clearRateHistory()
        try { myssh.Myssh.registerTrafficCallback(null) } catch (_: Exception) {}
        try { myssh.Myssh.registerSysInfoCallback(null) } catch (_: Exception) {}
    }

    private fun updateStats(txRate: Long, rxRate: Long, txTotal: Long, rxTotal: Long, activeConns: Long, totalConns: Long) {
        currentTxRate = txRate
        currentRxRate = rxRate
        currentTxTotal = txTotal
        currentRxTotal = rxTotal
        currentActiveConns = activeConns
        currentTotalConns = totalConns

        // 增量落库 / LiveData 刷新全在 sink 里，两种服务模式共用一份（口径漂移过一次，见 TrafficStatsSink）。
        statsSink.ingest(txRate, rxRate, txTotal, rxTotal)

        refreshNotification()
    }

    private fun updateSysInfo(cpu: Double, mem: Double, memSys: Double, goroutines: Long) {
        currentCpu = cpu
        currentMem = mem
        currentMemSys = memSys
        currentGoroutines = goroutines
        refreshNotification()
    }

    private val speedThrottle = SpeedRefreshThrottle()

    /**
     * 速度刷新型通知的刷新。
     *
     * 两个守卫缺一不可：
     *  - 用户开关：关掉之后连"第一次"都不该刷，否则用户关掉它却看到速度在跳。
     *  - 节流：统计回调是亚秒级的，不节流就是每秒十几次 `notify()`，而通知栏每次更新都要
     *    跨进程 binder + 重绘，实测在低端机上足以和服务本身抢 CPU。
     *
     * 旧实现用 `if (now - last < 1000) return` 自己判时序，时钟回拨（NTP 校正、用户改时间）
     * 会让 `now - last` 变成负数而永久卡死刷新 —— 交给 [SpeedRefreshThrottle] 用
     * `delta in 0 until interval` 判定，回拨时 delta 为负、不在区间内，于是照常刷新。
     */
    private fun refreshNotification() {
        if (!SettingsManager.getShowNotificationSpeed(this)) return
        if (!speedThrottle.shouldRefresh()) return

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

    /** 同步进入前台：在 startForegroundService 的时限窗口内必须被调用，不能经过协程调度。 */
    private fun startForegroundNow(content: String) {
        if (isForegroundStarted) return
        try {
            val notification = buildNotification(content)
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

    private fun buildNotification(content: String): android.app.Notification {
        val stopIntent = Intent(this, MyTransparentProxyService::class.java).apply { action = ACTION_STOP }
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
    }

    private fun updateNotification(content: String) {
        if (!isForegroundStarted) {
            startForegroundNow(content)
            return
        }
        serviceScope.launch(Dispatchers.Main) {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(content))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 补齐「最后一次统计回调 → 停止」之间的那一小段流量，否则这几十 KB 永远不落库。
        // VPN 侧本来就有这一步（saveFinalTrafficStats），tproxy 之前漏了 —— 同一份逻辑两份实现，
        // 漏的那份只有切到透明代理模式才暴露。基准与回绕规则都在 sink 里，这里只传总量。
        // Room 不能在主线程查；teardownScope 不随 serviceScope 一起被 cancel（见其注释）。
        //
        // 取数与清零放在协程**外**同步做完：统计回调跑在别的线程，若在协程里再读一次，
        // 这两个字段可能已经被新的一帧改写，导致落库的是「旧基准 + 新总量」而重复计数。
        val tx = currentTxTotal
        val rx = currentRxTotal
        currentTxTotal = 0L
        currentRxTotal = 0L
        teardownScope.launch {
            statsSink.flushPending(tx, rx)
        }

        // 先停核心、再停 tproxy：反过来的话停机过程自身产生的收尾流量（FIN/RST 等）
        // 不会有下一帧回调，那一段就落在刚补齐的区间之外。
        stopTProxy()
        serviceScope.cancel()
        super.onDestroy()
    }
}
