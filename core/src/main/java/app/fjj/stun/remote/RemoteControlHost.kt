package app.fjj.stun.remote

import android.content.Context
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.service.WebConsoleKeepAliveService

/**
 * 远程控制监听面的统一入口。
 *
 * ## 为什么有它
 * 「远程控制」在这个进程里是四个互不相识的 object：[WebServer]（Web 控制台）、
 * [BluetoothSyncManager]（蓝牙 RFCOMM）、[RemoteSyncManager]（局域网 HTTP + NSD 广播）、
 * [StunMcpServer]（MCP）。过去每个各写各的启动点 —— 蓝牙在 `Application.onCreate`、
 * Web 控制台在 Activity、局域网同步**只在 Activity**、MCP 又回到 Application。
 *
 * 结果就是「进程被回收后谁会被拉回来」取决于当初谁写了那行代码，而不是任何统一意图：
 * 蓝牙能回来（`Application.onCreate` 一定重跑）、Web 控制台能回来（前台保活服务 + 粘滞标记）、
 * 局域网同步**永远回不来** —— WorkManager 与 Service 唤活进程都不会重建 Activity，
 * 于是进程死过一次，手机远控就再也没回来过。
 *
 * 本类把「本机有哪些远程控制面、哪些该开着、怎么拉起来」收成一处：
 * 进程冷启动调 [startAll]，保活任务唤回时也调（[restoreAll]），两个入口语义一致、全部幂等。
 *
 * ## 谁拥有「局域网同步」这一面
 * 代码在 core，但语义上只有**被控端**（TV / Car）该开：它会对全网段做 NSD 广播并接受
 * `push_profile`，手机端一开就等于凭空多暴露一个未认证的写入口。
 * 所以由 TV / Car 的 `Application.onCreate` 显式调 [enableLanSync] 认领，手机端不调。
 * 认领结果缓存在进程内，保活唤回时（`Application.onCreate` 一定先于任何 Service/Worker
 * 执行）读到的就是宿主的声明。
 */
object RemoteControlHost {

    private const val TAG = "RemoteControlHost"

    /** 宿主是否认领了局域网同步监听面；进程内声明一次，之后所有 [startAll] / [restoreAll] 生效。 */
    @Volatile private var lanSyncClaimed = false

    /** 被控端监听面活着期间持有的 Wi-Fi 锁；进程不退出不释放，见 [ensureWifiLock]。 */
    @Volatile private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    /** TV / Car 的 `Application.onCreate` 调用：本机是被控端，需要局域网同步监听面。 */
    fun enableLanSync() {
        lanSyncClaimed = true
    }

    /**
     * 本机该挂前台保活服务吗？
     *
     * 前台服务把进程顶到 foreground 优先级、脱离 cached 裁剪，这是「关掉 App 之后远程控制还在」
     * 的地基。门从单纯的 `isWebConsoleEverStarted` 拓宽成「Web 控制台起过」**或**「用户要远程控制保活」：
     * Car 端从来不起 Web 控制台、只跑蓝牙远控，旧门恒为 false ⇒ 没有前台服务 ⇒
     * 进程被裁后蓝牙通道彻底消失 —— 恰恰是最该保的场景保不到。
     */
    fun needsProcessKeepAlive(context: Context): Boolean =
        SettingsManager.isWebConsoleEverStarted(context) ||
            SettingsManager.isRemoteControlKeepAlive(context)

    /** 尽力挂前台保活服务；后台启动限制（Android 12+）等场景内部已降级为仅日志。 */
    fun attachProcessKeepAlive(context: Context) {
        WebConsoleKeepAliveService.start(context)
    }

    /**
     * 进程冷启动时调：把本机所有「应开着」的远程控制面拉起来，并按需挂前台保活服务。
     *
     * 幂等：每个监听面内部都有 `isRunning` 门（CAS 或布尔），重复调用是 no-op。
     * 返回一行便于看日志的描述。
     */
    fun startAll(context: Context): String {
        val app = context.applicationContext
        val notes = mutableListOf<String>()
        notes += restoreListeners(app)
        if (needsProcessKeepAlive(app)) {
            attachProcessKeepAlive(app)
            notes += "keepalive-fgs-attach"
        }
        StunLogger.i(TAG, "Remote control listeners started → ${notes.joinToString(",")}")
        return notes.joinToString(",")
    }

    /**
     * 保活任务（前台保活服务的 [android.app.Service.onStartCommand]、Shizuku 周期任务）
     * 唤回进程后调：把掉了的监听面拉回来。
     *
     * 与 [startAll] 的唯一差别在 Web 控制台：**它由前台保活服务自己补，不在此处启动**。
     * Web 控制台要把 URL + 二维码挂在屏上，起点必须在 TV 的 `MainActivity.onCreate`；
     * 若在这里抢先启动，`WebServer.start` 的 CAS 会让 Activity 拿到 -1、屏幕上看不到地址。
     * 掉了的控制台由 `WebConsoleKeepAliveService.onStartCommand` 调本方法时补上（幂等）。
     */
    fun restoreAll(context: Context): String {
        val app = context.applicationContext
        val notes = mutableListOf<String>()
        notes += restoreWebConsole(app)
        notes += restoreListeners(app)
        StunLogger.i(TAG, "Remote control listeners restored → ${notes.joinToString(",")}")
        return notes.joinToString(",")
    }

    // ── 单个监听面 ────────────────────────────────────────────────

    private fun restoreWebConsole(app: Context): List<String> {
        if (WebServer.isRunning()) return listOf("webui-alive")
        if (!SettingsManager.isWebConsoleEverStarted(app)) return listOf("webui-never-started")
        // 粘滞标记保证只在这台设备**本来就**跑过控制台时才动手（手机端从没有过 ⇒
        // 不凭空开一个 5858 监听端口）。
        val port = WebServer.start(app)
        return listOf(if (port > 0) "webui-restarted:$port" else "webui-start-failed")
    }

    private fun restoreListeners(app: Context): List<String> {
        val notes = listOf(
            startBluetooth(app),
            startLanSync(app),
            startMcp(app),
        )
        // 有任一监听面真的起来了才持锁：手机端不会起任何监听面，永远不会持锁。
        if (WebServer.isRunning() || BluetoothSyncManager.isRunning() ||
            RemoteSyncManager.isRunning() || StunMcpServer.isRunning()
        ) {
            ensureWifiLock(app)
        }
        return notes
    }

    /**
     * 被控端（TV / Car）多为息屏服役：很多电视盒子息屏进深睡会把 Wi-Fi 整个拽掉，
     * 手机端这边 mDNS 缓存还在、ARP 却打不通，connect() 立即 NoRouteToHostException ——
     * 表现成「设备还在列表里，就是连不上」。前台保活服务只保进程不保无线链路，
     * 保链路是 [android.net.wifi.WifiManager.WifiLock] 的职责（对齐 MyTransparentProxyService 的用法）。
     *
     * 刻意不做配对释放：监听面没有停止路径（见 [startAll] 的幂等常驻语义），
     * 锁随进程消亡由系统回收；配对释放只会留下「锁没了但监听面还在跑」的错位。
     */
    private fun ensureWifiLock(app: Context) {
        if (wifiLock?.isHeld == true) return
        try {
            val wm = app.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                ?: return
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(
                android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "Stun::RemoteControlWifiLock"
            )
            wifiLock?.acquire()
            StunLogger.i(TAG, "WiFiLock acquired for remote control listeners")
        } catch (e: Exception) {
            StunLogger.w(TAG, "Failed to acquire WiFiLock: ${e.message}")
        }
    }

    private fun startBluetooth(app: Context): String {
        if (!SettingsManager.isRemoteSyncEnabled(app)) return "bt-sync-disabled"
        return when {
            BluetoothSyncManager.isRunning() -> "bt-alive"
            BluetoothSyncManager.startServer(app) -> "bt-started"
            else -> "bt-start-failed"   // 典型：API 31+ 缺 BLUETOOTH_CONNECT 或蓝牙适配器已关
        }
    }

    private fun startLanSync(app: Context): String {
        if (!lanSyncClaimed) return "lan-not-claimed"
        if (!SettingsManager.isRemoteSyncEnabled(app)) return "lan-sync-disabled"
        if (RemoteSyncManager.isRunning()) return "lan-alive"
        // startServer 失败会回滚 isRunning()，所以这里必须如实回报 —— 否则上层看到的是
        // 「已启动」而实际没有任何东西在监听，局域网同步的失败过去就是这样被静默吞掉的。
        return if (RemoteSyncManager.startServer(app)) "lan-started" else "lan-start-failed"
    }

    private fun startMcp(app: Context): String {
        if (!SettingsManager.isMcpServerEnabled(app)) return "mcp-disabled"
        if (StunMcpServer.isRunning()) return "mcp-alive"
        return if (runCatching { StunMcpServer.start(app) }.isSuccess) "mcp-started" else "mcp-start-failed"
    }

    /**
     * 各监听面的「意图 / 现状」，供 `/api/keepalive/status` 上报。
     *
     * 刻意把 enabled 与 running 分开报：开关是开的但通道实际没起来（蓝牙权限被拒、
     * 适配器关了、端口冲突）必须能被看见，否则界面上只会显示"已开启却连不上"。
     */
    fun listenerStatus(context: Context): List<Map<String, Any?>> {
        val app = context.applicationContext
        val syncOn = SettingsManager.isRemoteSyncEnabled(app)
        val btBlocked = if (syncOn && !BluetoothSyncManager.hasBluetoothConnectPermission(app))
            "no_bluetooth_connect" else null
        return listOf(
            mapOf(
                "name" to "webui",
                "enabled" to SettingsManager.isWebConsoleEverStarted(app),
                "running" to WebServer.isRunning(),
            ),
            mapOf(
                "name" to "bluetooth",
                "enabled" to syncOn,
                "running" to BluetoothSyncManager.isRunning(),
                "blocked" to btBlocked,
            ),
            mapOf(
                "name" to "lan_sync",
                "enabled" to (lanSyncClaimed && syncOn),
                "running" to RemoteSyncManager.isRunning(),
            ),
            mapOf(
                "name" to "mcp",
                "enabled" to SettingsManager.isMcpServerEnabled(app),
                "running" to StunMcpServer.isRunning(),
            ),
        )
    }
}
