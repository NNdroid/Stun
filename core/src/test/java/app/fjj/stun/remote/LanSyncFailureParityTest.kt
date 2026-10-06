package app.fjj.stun.remote

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 局域网同步的**失败模式**护栏。
 *
 * 这块代码横跨 TV 广播面与手机发现面，横跨两个 App，而且**两端都可能在设备上完全静默**：
 * NSD 注册失败不给 App 抛异常、发现失败不回调、端口绑定失败也不让 `start(wait = false)` 报错。
 * 于是任何一个「只打日志就完事」的分支都会表现成「手机端永远搜不到 TV」，而 logcat 里什么都
 * 看不出异常 —— 这类回归编译不报错、测试也过，只会安静地让整条通道消失。
 *
 * 所以这里只做结构断言：钉住「每一种失败都必须被看见、并且要么重试要么回滚」这条不变量。
 */
class LanSyncFailureParityTest {

    private val remoteSync: String =
        File("src/main/java/app/fjj/stun/remote/RemoteSyncManager.kt").readText()

    private val remoteControlHost: String =
        File("src/main/java/app/fjj/stun/remote/RemoteControlHost.kt").readText()

    private val bluetooth: String =
        File("src/main/java/app/fjj/stun/remote/BluetoothSyncManager.kt").readText()

    @Test
    fun `注册失败必须续一次重投，不能只打日志`() {
        // onRegistrationFailed 若只 log，TV 冷启动时 Wi-Fi 未就绪的一次失败就永久隐身。
        assertTrue(
            "onRegistrationFailed 必须触发重投",
            remoteSync.contains("scheduleReregister(\"registration-failed")
        )
    }

    @Test
    fun `重投次数必须有上限并且成功时复位`() {
        // 无上限重试会在网络真坏时紧贴自旋；不复位则一次偶发失败就把额度用光、之后再不广播。
        assertTrue("重投要有上限", remoteSync.contains("MAX_REGISTRATION_RETRIES"))
        assertTrue("耗尽要明确报错，不能无声消失", remoteSync.contains("retries exhausted"))
        assertTrue(
            "注册成功后必须复位计数器",
            remoteSync.contains("registrationRetriesLeft = MAX_REGISTRATION_RETRIES")
        )
    }

    @Test
    fun `首次注册不能被立即的wifi可用回调打断`() {
        // registerNetworkCallback 对**已连接**的网络会立即回调一次 onAvailable；若此刻立刻
        // unregister+register，首次注册被打断、第二次又很容易撞名字竞态，之后无人重试。
        assertTrue("onAvailable 必须看新鲜度窗口", remoteSync.contains("FRESH_REGISTRATION_WINDOW_MS"))
    }

    @Test
    fun `端口探不通必须回滚运行标志`() {
        // 绑定失败本身是**同步抛**的（见 LanDualStackBindTest 实测），但「bind 成功却没在监听」
        // 仍然可能发生（findFreePort 与 bind 之间的抢端口竞态、连接器绑到了非预期地址）。
        // 不回滚则 isServerRunning 永久卡在 true，RemoteControlHost 一直报 lan-alive，
        // 而实际没有任何东西在监听 —— 手机端搜得到却连不上。
        assertTrue("启动后必须验活端口", remoteSync.contains("verifyListening(app, port)"))
        assertTrue("探活必须按地址族分别判定", remoteSync.contains("probeHostRepeatedly(\"127.0.0.1\", port"))
        assertTrue("IPv6 环回也要探", remoteSync.contains("probeHost(\"::1\", port)"))
        assertTrue("探不通必须回滚", remoteSync.contains("not accepting connections"))
        assertTrue("回滚走统一清理", remoteSync.contains("stopServer()"))
    }

    @Test
    fun `startServer失败时不能留下卡死的运行标志`() {
        // 旧写法在这里手写了三行清理（set(false) + stop + server = null），漏掉 unregisterService
        // 与 unobserveNetwork 就留下悬挂的 NSD 注册与网络回调。统一走 stopServer。
        assertFalse(
            "catch 分支应走 stopServer 而非手写清理",
            remoteSync.contains("isServerRunning.set(false)\n            try { server?.stop")
        )
    }

    @Test
    fun `NSD管理器缺失必须抛出让上层回滚`() {
        // 静默 return 时 isServerRunning 已为 true 却什么都没广播，手机端永远搜不到且无日志。
        assertTrue("取不到 NSD manager 必须抛异常", remoteSync.contains("NSD manager unavailable"))
    }

    @Test
    fun `startLanSync必须如实回报启动结果`() {
        // 上层若只看 isRunning()，端口绑定失败的静默状态会被当成「已启动」。
        assertTrue(
            "startLanSync 必须区分 started / failed",
            remoteControlHost.contains("\"lan-started\" else \"lan-start-failed\"")
        )
    }

    @Test
    fun `被控端监听面活着必须持有WiFi锁`() {
        // 很多电视盒子息屏进深睡会把 Wi-Fi 整个拽掉：手机端 mDNS 缓存还在、ARP 打不通，
        // connect() 立即 NoRouteToHostException —— 表现成「设备在列表里，就是连不上」。
        // 前台保活服务只保进程不保无线链路；不持锁的话 TV 一息屏，lan_sync 整条通道静默死掉。
        assertTrue(
            "监听面活着必须 acquire WiFiLock（HIGH_PERF 模式，对齐 tproxy 服务）",
            remoteControlHost.contains("WIFI_MODE_FULL_HIGH_PERF")
        )
        assertTrue(
            "持锁必须以「有监听面在跑」为门（WebServer/Bluetooth/RemoteSync/Mcp 任一 running）",
            remoteControlHost.contains("RemoteSyncManager.isRunning() || StunMcpServer.isRunning()")
        )
        val lockBody = remoteControlHost.substringAfter("private fun ensureWifiLock")
        assertTrue(
            "锁持有必须幂等（isHeld 短路）且只在 try 内 acquire",
            lockBody.contains("wifiLock?.isHeld == true") && lockBody.contains("wifiLock?.acquire()")
        )
    }

    @Test
    fun `局域网客户端socket必须绑定Wi-Fi网络而不是跟随默认网络`() {
        // Wi-Fi 被系统判为无互联网（验证失败/隧道断开瞬间）时默认网络切到蜂窝：
        // NSD 是系统服务、跨网络照样能发现设备，但应用 socket 往 192.168.x 连在蜂窝
        // 路由表里无路可走 → NoRouteToHostException，表现成「搜得到、连不上」。
        // 绑定 Wi-Fi 网络后 socket 从 Wi-Fi 路由表走，默认网络是谁都无所谓。
        assertTrue(
            "必须以 TRANSPORT_WIFI 跟踪 Wi-Fi 网络",
            remoteSync.contains("addTransportType(NetworkCapabilities.TRANSPORT_WIFI)")
        )
        assertTrue(
            "HTTP 引擎必须注入 Wi-Fi 网络的 socketFactory（CIO 不支持，必须留在 OkHttp 引擎上）",
            remoteSync.contains("socketFactory(network.socketFactory)")
        )
        assertTrue(
            "端口探针必须与 HTTP 同一条出网路径，否则诊断会误导",
            remoteSync.contains("wifiNetwork?.socketFactory?.createSocket() ?: Socket()")
        )
        assertTrue(
            "Wi-Fi 网络实例更换后必须重建 client（旧 socketFactory 指向死网络）",
            remoteSync.contains("boundClientNetwork === network")
        )
    }

    @Test
    fun `蓝牙探测连接不能当错误刷屏`() {
        // 蓝牙协议是「一连接 = 一个请求 = 服务端回一句就挂」，所以「对端关掉连接」是正常收尾。
        // 可达性探测（isDeviceReachable）就是连上立刻挂断，过去一律 ERROR + 完整堆栈：
        // 一次扫描 N 台设备 = N 条 ERROR，而且真故障被淹没后，
        // 「日志里有 ERROR 就一定是故障」这个前提也不成立，两边都坏。
        // 判定按 socket 是否还连着，而不是去匹配异常消息文本。
        assertTrue(
            "对端断开必须按 socket 连接状态判定",
            bluetooth.contains("if (!socket.isConnected)")
        )
        assertTrue(
            "对端断开必须是 debug 级、不带堆栈",
            bluetooth.contains("BT client disconnected before a request")
        )
        assertTrue("真故障仍要留 ERROR", bluetooth.contains("Error handling BT client connection"))
        // 干净 EOF 与「读一半被关断」语义相同，不能只处理其中一种
        assertTrue(
            "干净 EOF 也要按断开处理",
            bluetooth.contains("closed without a request")
        )
        // ERROR 日志出现之前必须先做连接状态判定 —— 即它是 else 分支，不是 catch 的无条件分支。
        val handler = bluetooth
            .substringAfter("handleClientConnection")
            .substringBefore("\"Error handling BT client connection\"")
        assertTrue(
            "ERROR 必须是条件分支，不得 catch 到就升级",
            handler.contains("!socket.isConnected")
        )
    }
}
