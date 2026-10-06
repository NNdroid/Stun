package app.fjj.stun.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.Keep
import app.fjj.stun.repo.Profile
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.StunLogger
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.OkHttp as ClientOkHttp
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@Keep
data class RemoteDeviceInfo(
    val name: String,
    val host: String,
    val port: Int,
    val model: String = "",
    val lastSeen: Long = System.currentTimeMillis()
)

@Keep
data class TvProfileSummary(
    val id: String,
    val name: String,
    val tunnelType: String = ""
)

@Keep
data class TvStatusResponse(
    val vpnState: String,
    val currentProfileName: String?,
    val currentProfileId: String?,
    val currentProfileType: String? = null,
    val currentProfileServer: String? = null,
    val profileCount: Int = 0,
    val deviceName: String = "",
    val publicIp: String? = null,
    val txRate: Long = 0L,
    val rxRate: Long = 0L,
    val txTotal: Long = 0L,
    val rxTotal: Long = 0L,
    val profiles: List<TvProfileSummary>? = null
)

@Keep
data class RemoteControlRequest(
    val action: String, // "start_vpn", "stop_vpn", "restart_vpn", "select_profile"
    val profileId: String? = null
)

enum class PushResult {
    SUCCESS,
    REJECTED,
    TIMEOUT,
    ERROR
}

interface DiscoverySession {
    fun stop()
}

object RemoteSyncManager {
    private const val TAG = "RemoteSync"
    const val SERVICE_TYPE = "_stun_sync._tcp."
    private const val SERVICE_NAME_PREFIX = "StunTV"

    /** 注册失败后最多重投几次。耗尽就明确报错，别让它静默地永远搜不到。 */
    private const val MAX_REGISTRATION_RETRIES = 5
    /** 两次重投之间的等待。太密会和 mDNS 缓存写入时机打架。 */
    private const val REREGISTER_RETRY_DELAY_MS = 3_000L
    /** 刚注册过就不因「Wi-Fi 可用」而重投，见 [observeNetwork]。 */
    private const val FRESH_REGISTRATION_WINDOW_MS = 5_000L
    /** 端口验活的探测次数与间隔（总窗口 ~1.2s，在 IO 线程上跑，不占主线程）。 */
    private const val LISTEN_PROBE_ATTEMPTS = 12
    private const val LISTEN_PROBE_INTERVAL_MS = 100L
    /** 双栈通配地址：`::`。Linux 默认 `ipv6.bindv6only=0`，绑它同时收 IPv4 映射连接。 */
    private const val BIND_HOST_DUAL_STACK = "::"
    /** 纯 IPv4 通配地址，双栈绑不上或退化时的回落目标。 */
    private const val BIND_HOST_IPV4_ONLY = "0.0.0.0"

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val isServerRunning = AtomicBoolean(false)
    private var nsdManager: NsdManager? = null
    private var connectivityManager: ConnectivityManager? = null
    @Volatile private var lastServiceInfo: NsdServiceInfo? = null
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastRegisteredAtMs = 0L
    @Volatile private var registrationRetriesLeft = MAX_REGISTRATION_RETRIES
    /** 当前实际绑定用的地址。验活据此判断要不要重绑，见 [verifyListening]。 */
    @Volatile private var activeBindHost = BIND_HOST_DUAL_STACK
    /**
     * 一旦确认本平台的双栈绑定收不到 IPv4，就永久改走纯 IPv4。
     * 这是**机器属性**而不是偶发故障，所以粘滞、不在 [stopServer] 里清 —— 清了下次又会重绑一遍
     * 再退化一次，白浪费一轮端口重分配和 NSD 重注册。
     */
    @Volatile private var forceIpv4OnlyBind = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Callbacks on TV side
    var onProfilePushRequested: (suspend (senderIp: String, profile: Profile) -> Boolean)? = null
    var onRemoteControlRequested: (suspend (action: String, profileId: String?) -> Boolean)? = null
    var tvStatusProvider: (() -> TvStatusResponse)? = null

    // --- Server Mode (TV) ---

    /**
     * 启动局域网同步监听面。
     *
     * 监听地址走 [startHttpServer]：双栈优先（`::`），绑不上就地回落纯 IPv4（`0.0.0.0`），
     * 实际用了哪个记在 [activeBindHost] 里并打进启动日志。
     *
     * @return 是否真的在监听。失败时**必须**把 [isServerRunning] 回滚 —— 否则标志位永久卡在 true，
     *   之后每次 `RemoteControlHost.startLanSync` 都在 `isRunning()` 短路、日志报 `lan-alive`，
     *   而实际没有任何东西在监听，且这条失败不会留下任何日志。
     */
    fun startServer(context: Context): Boolean {
        if (!isServerRunning.compareAndSet(false, true)) return true
        // 闭包只捕获 applicationContext：`this` 可能是 Activity，钉在常驻 object 的路由闭包里会泄漏。
        val app = context.applicationContext
        val port = findFreePort()
        try {
            server = startHttpServer(port) {
                install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                    gson { setPrettyPrinting() }
                }
                routing {
                    // Push Profile
                    post("/push_profile") {
                        try {
                            val senderIp = call.request.origin.remoteHost
                            // 失败关闭：没有宿主确认回调就**绝不**自动写入。
                            // 回调只在宿主界面活着时注册（弹确认框必须有界面），TV 端 UI 一关掉就是 null。
                            // 先前 `?: true` 是静默接受 —— 完整 Profile（含 pass / privateKey /
                            // icmpCustomPsk）被直接写进设备，全程没有任何确认，而且手机端看到 200 会
                            // 报「推送成功」。手机端对任何非 200 都走 push_failed，文案正好是
                            // 「请确保 TV 端已打开且在同一网络下」，所以不用新增字符串。
                            val pushHandler = onProfilePushRequested
                            if (pushHandler == null) {
                                StunLogger.w(TAG, "Push from $senderIp dropped: no host UI to confirm")
                                call.respond(
                                    HttpStatusCode.ServiceUnavailable,
                                    mapOf("error" to "no_host_ui", "message" to "Open the app to confirm the push")
                                )
                                return@post
                            }
                            val profile = call.receive<Profile>()
                            StunLogger.i(TAG, "Push request from $senderIp: ${profile.name}")

                            val accepted = pushHandler(senderIp, profile)
                            if (accepted) {
                                ProfileManager.addProfile(app, profile)
                                StunLogger.i(TAG, "Profile accepted and saved: ${profile.name}")
                                call.respond(HttpStatusCode.OK, mapOf("status" to "success"))
                            } else {
                                StunLogger.w(TAG, "Profile push rejected by TV user: ${profile.name}")
                                call.respond(
                                    HttpStatusCode.Forbidden,
                                    mapOf("error" to "rejected", "message" to "TV user rejected the push request")
                                )
                            }
                        } catch (e: Exception) {
                            StunLogger.e(TAG, "Failed to process push_profile", e)
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "unknown error")))
                        }
                    }

                    // Get TV Status
                    get("/api/status") {
                        try {
                            // 兜底也走 TvStatusSource：宿主忘了注册 provider 时不该回一个「在线但空」的
                            // 假状态 —— 手机端会把它渲染成「电视端暂无可用节点」，看起来像没节点，
                            // 实际是 provider 缺失。宁可多读一次真实数据。
                            val status = tvStatusProvider?.invoke() ?: TvStatusSource.build(app)
                            call.respond(HttpStatusCode.OK, status)
                        } catch (e: Exception) {
                            StunLogger.e(TAG, "Failed to provide tv status", e)
                            call.respond(HttpStatusCode.OK, TvStatusSource.build(app))
                        }
                    }

                    // Remote Control (Start/Stop/Restart VPN, Switch profile)
                    post("/api/control") {
                        try {
                            val req = call.receive<RemoteControlRequest>()
                            // 区分「宿主没在」与「动作被拒」：回调只在宿主界面活着时注册，null 时回
                            // 503 —— 手机端据此能提示「先打开 TV 端的 App」，而不是笼统的「远程操作
                            // 失败」，后者会被误读成动作本身不支持。
                            val controlHandler = onRemoteControlRequested
                            if (controlHandler == null) {
                                StunLogger.w(TAG, "Control ${req.action} dropped: no host UI")
                                call.respond(
                                    HttpStatusCode.ServiceUnavailable,
                                    mapOf(
                                        "error" to "no_host_ui",
                                        "message" to "Open the app to accept remote control"
                                    )
                                )
                                return@post
                            }
                            val success = controlHandler(req.action, req.profileId)
                            if (success) {
                                call.respond(HttpStatusCode.OK, mapOf("status" to "success"))
                            } else {
                                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "control action failed"))
                            }
                        } catch (e: Exception) {
                            StunLogger.e(TAG, "Remote control error", e)
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "unknown error")))
                        }
                    }

                    get("/ping") {
                        call.respond(HttpStatusCode.OK, "pong")
                    }
                }
            }.start(wait = false)

            registerService(app, port)
            observeNetwork(app)
            StunLogger.i(TAG, "Sync server started on port $port (bind=$activeBindHost)")
            // 异步验活：start(wait = false) 不保证端口真的在监听（见 [verifyListening]）。
            verifyListening(app, port)
            return true
        } catch (e: Exception) {
            stopServer()
            StunLogger.e(TAG, "Sync server start failed on port $port", e)
            return false
        }
    }

    /**
     * 起 HTTP 服务器：双栈优先，绑不上就地回落纯 IPv4。
     *
     * 为什么必须双栈：mDNS resolve 返回的地址顺序不保证 IPv4 在前，[resolveHostCandidates]
     * 只能排序、变不出地址。一旦网段是 IPv6-only 接入，原来只绑 `0.0.0.0` 的服务器
     * 连一个能连的地址都不存在。绑 `::` 之后两种都收。
     *
     * 绑定失败是**同步抛**的（端口冲突实测抛 [JobCancellationException]，仍属 Exception），
     * 所以回落直接在这里做、端口不必重新分配，NSD 广播的还是同一个端口，手机端无感。
     *
     * module 闭包原样透传，不提成 `val` 字段也不拆成具名类：Ktor 3.x 按构造器形态反射实例化
     * module，闭包捕获状态与否会决定它走哪条分支（见 `WebServer` 里那段 R8 说明），
     * 保持与改动前完全一致的形态最稳。
     */
    private fun startHttpServer(
        port: Int,
        module: Application.() -> Unit
    ): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
        if (forceIpv4OnlyBind) {
            activeBindHost = BIND_HOST_IPV4_ONLY
            return embeddedServer(CIO, port = port, host = BIND_HOST_IPV4_ONLY, module = module)
                .start(wait = false)
        }
        return try {
            activeBindHost = BIND_HOST_DUAL_STACK
            embeddedServer(CIO, port = port, host = BIND_HOST_DUAL_STACK, module = module)
                .start(wait = false)
        } catch (e: Exception) {
            // 绑 `::` 失败（平台不支持 IPv6、地址不可用）时回落纯 IPv4，至少保住这条通道。
            // 这里刻意**不**置 forceIpv4OnlyBind：绑定异常分不出「不支持 IPv6」和「端口冲突」，
            // 粘滞降级会把一台完全支持双栈的机器永久降成纯 IPv4。真正的行为判定在
            // [verifyListening] 里按「IPv4 环回是否可达」来做。
            StunLogger.w(TAG, "Dual-stack bind failed (${e.message}); falling back to $BIND_HOST_IPV4_ONLY")
            activeBindHost = BIND_HOST_IPV4_ONLY
            embeddedServer(CIO, port = port, host = BIND_HOST_IPV4_ONLY, module = module)
                .start(wait = false)
        }
    }

    /**
     * 异步验活端口，必要时换回纯 IPv4 重绑。
     *
     * `.start(wait = false)` 不保证端口真的在监听：日志会照打「Sync server started」而实际上
     * 没有任何东西在听，NSD 又照常广播一个死端口 —— 手机端能搜到设备但探测必然超时，就是
     * 「搜得到却连不上」的那一半。探不通就把标志位回滚（[stopServer] 会一并撤掉 NSD 注册），
     * 让下一次 [RemoteControlHost.startLanSync] 如实看到「没起来」，而不是永远报 lan-alive。
     *
     * 先看 **IPv4 环回**：双栈绑定后 IPv4 应当照常可达（Linux `ipv6.bindv6only` 默认 0）。
     * 若 IPv4 不通而 IPv6 通，说明这台机器把双栈退化成了纯 IPv6 监听 —— 这是机器属性而不是
     * 偶发故障，置 [forceIpv4OnlyBind] 后重绑一次，让 IPv4 客户端（绝大多数家庭网络）恢复可达。
     * 重绑失败会再走一遍本方法、此时 `activeBindHost` 已是 IPv4、直接落进回滚分支，不会死循环。
     */
    private fun verifyListening(app: Context, port: Int) {
        scope.launch {
            if (probeHostRepeatedly("127.0.0.1", port, LISTEN_PROBE_ATTEMPTS)) {
                if (!probeHost("::1", port)) {
                    StunLogger.w(TAG, "Server on port $port is reachable on IPv4 only (IPv6 loopback closed)")
                }
                return@launch
            }
            if (activeBindHost == BIND_HOST_DUAL_STACK && probeHost("::1", port)) {
                StunLogger.w(TAG, "Dual-stack bind on port $port served IPv6 only; rebinding IPv4-only")
                forceIpv4OnlyBind = true
                stopServer()
                startServer(app)
                return@launch
            }
            StunLogger.e(TAG, "Sync server port $port not accepting connections; rolling back")
            stopServer()
        }
    }

    /** 单次连接尝试，自带 1.2s 超时，不会无限占住 IO 线程。 */
    private fun probeHost(host: String, port: Int): Boolean =
        runCatching { Socket().use { s -> s.connect(InetSocketAddress(host, port), 1_200) } }.isSuccess

    private suspend fun probeHostRepeatedly(host: String, port: Int, attempts: Int): Boolean {
        repeat(attempts) { i ->
            if (probeHost(host, port)) return true
            if (i < attempts - 1) delay(LISTEN_PROBE_INTERVAL_MS)
        }
        return false
    }

    fun stopServer() {
        if (isServerRunning.compareAndSet(true, false)) {
            server?.stop(1000, 2000)
            server = null
            unregisterService()
            unobserveNetwork()
            StunLogger.i(TAG, "Sync server stopped")
        }
    }

    /** 是否正在监听（[RemoteControlHost] 与宿主界面用来区分「已开启」和「已开启但没起来」）。 */
    fun isRunning(): Boolean = isServerRunning.get()

    private fun registerService(context: Context, port: Int) {
        // 取不到 NSD 管理器不能静默返回：那时 isServerRunning 已为 true 却什么都没广播，
        // 手机端永远搜不到、且不留任何日志。抛出让 [startServer] 走回滚并把状态如实回报上去。
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: throw IllegalStateException("NSD manager unavailable; device would not be discoverable")
        nsdManager = manager
        registrationRetriesLeft = MAX_REGISTRATION_RETRIES
        val cleanModel = Build.MODEL.replace("[^a-zA-Z0-9_-]".toRegex(), "_").take(16)
        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = "$SERVICE_NAME_PREFIX-$cleanModel"
            this.serviceType = SERVICE_TYPE
            setPort(port)
        }
        // 记一份以便断网重连后原样重投（端口随每次启动重新分配，必须连端口一起重投）。
        lastServiceInfo = serviceInfo
        manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        lastRegisteredAtMs = System.currentTimeMillis()
        // 把投递参数打进日志：手机端搜不到时，这是判断「TV 到底有没有在广播」的唯一凭据。
        StunLogger.i(TAG, "NSD registration submitted: ${serviceInfo.serviceName} type=$SERVICE_TYPE port=$port")
    }

    private fun unregisterService() {
        try {
            nsdManager?.unregisterService(registrationListener)
        } catch (_: Exception) {}
    }

    /**
     * 重投 NSD 注册，异步 + 有界退避重试。
     *
     * 两个触发源共用这一条：Wi-Fi 恢复可用（[observeNetwork]）和注册失败
     * （[registrationListener] 的 onRegistrationFailed）。
     *
     * 注册失败过去**只打日志、永不重试** —— TV 冷启动时 Wi-Fi 可能还没就绪，一次失败就永久隐身，
     * 而 [isServerRunning] 仍为 true、没人会再调 [startServer]，手机端就长期搜不到本机
     * （HTTP 端口其实一直活着）。这里给一个有界计数器：[registrationRetriesLeft] 耗尽就明确报错；
     * 成功注册时在 [registrationListener] 里复位，别让一次偶发失败把额度用光。
     *
     * 异步投递是因为 onRegistrationFailed 跑在 binder 线程、`registerService` 本身也可能抛，
     * 两条都不能在回调里同步做重活。
     */
    private fun scheduleReregister(reason: String) {
        val left = registrationRetriesLeft
        if (left <= 0) {
            StunLogger.e(TAG, "NSD registration retries exhausted after '$reason'; giving up")
            return
        }
        registrationRetriesLeft = left - 1
        scope.launch {
            delay(REREGISTER_RETRY_DELAY_MS)
            if (!isServerRunning.get()) return@launch
            performReregister(reason, left)
        }
    }

    /** 同步做一次「反注册 + 重注册」。在 IO 线程上跑。 */
    private fun performReregister(reason: String, attempt: Int): Boolean {
        val manager = nsdManager ?: return false
        val info = lastServiceInfo ?: return false
        try {
            unregisterService()
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            lastRegisteredAtMs = System.currentTimeMillis()
            StunLogger.i(TAG, "NSD service (re-)registered, attempt $attempt after '$reason': ${info.serviceName}")
            return true
        } catch (e: Exception) {
            StunLogger.w(TAG, "NSD re-registration attempt $attempt after '$reason' failed: ${e.message}")
            return false
        }
    }

    /**
     * Wi-Fi 恢复可用性时重投注册。只看 Wi-Fi：局域网同步在蜂窝网上没有意义，重投只是白费。
     */
    private fun observeNetwork(context: Context) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                // 只认 Wi-Fi：局域网同步在蜂窝网上没有意义，重投只是白费。
                // 注意走 ConnectivityManager 查 —— compileSdk 37 的 Network 上已经没有了
                // getCapabilities()，直接 `network.capabilities` 会编译失败。
                val caps = manager.getNetworkCapabilities(network)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return
                // registerNetworkCallback 会对**已连接**的网络立即回调一次；此刻首次注册还在进行中，
                // 紧接着反注册只会把整轮注册打断，而第二次很容易撞上名字竞态返回失败 —— 之后没人
                // 重试，TV 就永久对手机不可见（HTTP 端口其实一直活着）。
                if (System.currentTimeMillis() - lastRegisteredAtMs < FRESH_REGISTRATION_WINDOW_MS) {
                    StunLogger.d(TAG, "NSD registration is fresh; skipping re-register on wifi-available")
                    return
                }
                StunLogger.i(TAG, "Wi-Fi available; re-registering NSD service")
                scheduleReregister("wifi-available")
            }
        }
        try {
            manager.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            connectivityManager = manager
            networkCallback = callback
        } catch (e: Exception) {
            StunLogger.w(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    private fun unobserveNetwork() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        networkCallback = null
    }

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            // 成功一次就复位计数器：注册是事件驱动的，不该因一次偶发失败而把重投额度用光。
            lastRegisteredAtMs = System.currentTimeMillis()
            registrationRetriesLeft = MAX_REGISTRATION_RETRIES
            StunLogger.i(TAG, "NSD Service registered: ${serviceInfo.serviceName}")
        }
        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            // 只打日志就完了等于「一次失败永久隐身」：这里必须续一次重投。
            StunLogger.w(TAG, "NSD registration failed: $errorCode — ${serviceInfo.serviceName}; retrying")
            scheduleReregister("registration-failed($errorCode)")
        }
        override fun onServiceUnregistered(arg0: NsdServiceInfo) {}
        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
    }

    // --- Client Mode (Phone) ---

    /**
     * 拼局域网 HTTP 基地址（不含路径）。
     *
     * IPv6 字面量本身含冒号，直接插进 URI 会被解析器当成多个 `host:port` 分隔符 ——
     * Ktor 3 对 `http://fe80::1:54321/x` 抛的是 `URLParserException`，**不是**连接失败。
     * 而三个客户端方法各自 catch 异常后返回 null / ERROR / false，调用点只能当作
     * 「设备不在线」，于是表现成「搜得到、连不上」。IPv6 必须加方括号。
     *
     * [getTvStatus] / [pushProfileToDevice] / [sendRemoteControl] 统一走这里，
     * 别再各自拼 `http://$host:$port` —— 那串内插就是历史故障点。
     *
     * 这里的 `http://` 不是随手选的：局域网里跑不了 TLS 握手协商。它依赖宿主 APK 的 manifest 把
     * 明文放行（`app` 模块 `android:usesCleartextTraffic="true"`），targetSdk 37 下缺了这个开关
     * 请求会在握手前被平台拦掉，三个客户端方法一样只会得到 null。改协议前先看那行。
     */
    internal fun lanBaseUrl(host: String, port: Int): String {
        val authority = if (host.indexOf(':') >= 0) "[$host]" else host
        return "http://$authority:$port"
    }

    /**
     * 地址字面量的可用性排名，越小越优先。纯函数，便于单测。
     *
     * 服务端已经是双栈监听（见 [startHttpServer]），两种地址都能收，排序的意义变成**可预测性**：
     * IPv4 在家庭网段上几乎总是可达，IPv6 则要看运营商/路由器是否真做完了双栈。先试 IPv4，
     * 探不通时日志里能看到候选数，好判断该换哪一端。
     *
     * 链路本地 IPv6（fe80::/10）垫底但不丢弃：它只在同一链路有效，且 `hostAddress`
     * 丢掉 `%scope` 后本来就路由不了。垫底而不是删掉，是为了不把设备整个藏起来 ——
     * 至少让它以「探不通」呈现，比凭空消失要好排查。
     */
    internal fun addressRank(literal: String): Int = when {
        literal.indexOf(':') < 0 -> 0                     // IPv4 字面量或主机名
        literal.lowercase().startsWith("fe80") -> 2        // 链路本地 IPv6
        else -> 1                                          // 其它（站点/全局）IPv6
    }

    /**
     * 取出可连的地址字面量，按 [addressRank] 排序去重。
     *
     * 无版本分支：`NsdServiceInfo.getHostAddresses()` 自 API 23 起可用（minSdk 28），
     * 旧的写法在 API 34 以下只能取 `getHost()` 返回的**单个**地址，拿到 IPv6 就没了退路。
     */
    private fun resolveHostCandidates(serviceInfo: NsdServiceInfo): List<String> =
        serviceInfo.hostAddresses
            .map { it.hostAddress.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedBy { addressRank(it) }

    // ── 手机端 socket 的 Wi-Fi 网络绑定 ─────────────────────────────
    //
    // 客户端 socket 过去跟着「系统默认网络」走：Wi-Fi 一旦被系统判为无互联网
    // （验证失败、或隧道断开的那一瞬间），默认网络切到蜂窝，往 192.168.x.x 的
    // connect() 在蜂窝路由表里无路可走，直接抛 NoRouteToHostException —— 而 NSD
    // 是系统服务、跨网络照样能发现设备，于是表现为「搜得到、连不上」。
    // 绑定到 Wi-Fi 网络后 socket 从 Wi-Fi 的路由表走，默认网络是谁都无所谓；
    // 本应用自己的 VPN（tun 是默认网络）开着时，这同样把局域网同步流量从隧道里
    // 摘出来（tun 路由含 RFC1918 是刻意设计，见 MyVpnService，不能靠排除网段解决）。
    // 拿不到 Wi-Fi（设备无 Wi-Fi、requestNetwork 抛异常）时回落系统默认网络，
    // 与旧行为一致 —— 这是纯增强，不引入新的依赖前提。
    @Volatile private var wifiNetwork: Network? = null
    @Volatile private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private val wifiNetworkInstallOnce = AtomicBoolean(false)

    /**
     * 跟踪 Wi-Fi 网络，进程级一次性安装。客户端入口（[startDeviceDiscovery]）调用。
     *
     * `requestNetwork` 对已连接的 Wi-Fi 会立即回调 onAvailable，但回调在
     * ConnectivityThread 上、与本协程存在窗口 —— 装好后头一两次轮询仍可能走默认网络，
     * 下个轮询周期（1.5s）自然恢复，不值得为它同步阻塞。
     *
     * 刻意不配对卸载：各监听面没有停止语义（见 [observeNetwork] 同款取舍），
     * 回调随进程消亡由系统回收。
     */
    private fun observeWifiNetwork(context: Context) {
        if (!wifiNetworkInstallOnce.compareAndSet(false, true)) return
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (manager == null) {
            wifiNetworkInstallOnce.set(false)
            return
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifiNetwork = network
                StunLogger.i(TAG, "LAN sync sockets will bind to Wi-Fi network $network")
            }
            override fun onLost(network: Network) {
                if (wifiNetwork === network) wifiNetwork = null
            }
        }
        try {
            manager.requestNetwork(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                callback
            )
            wifiNetworkCallback = callback
        } catch (e: Exception) {
            wifiNetworkInstallOnce.set(false)
            StunLogger.w(TAG, "Wi-Fi network tracking unavailable; LAN sync falls back to default network: ${e.message}")
        }
    }

    /** 当前绑定下已构建的 client 与其绑定的网络实例；网络实例变了就重建（Wi-Fi 重连后旧 socketFactory 指向死网络）。 */
    @Volatile private var boundClient: HttpClient? = null
    @Volatile private var boundClientNetwork: Network? = null
    private val clientLock = Any()

    /**
     * 局域网同步的 HTTP 客户端。每次请求前取一次：绑定的 Wi-Fi 网络实例若已更换
     * （Wi-Fi 掉线重连可能换 Network 实例），重建 client 换上新 socketFactory ——
     * 复用旧 client 的代价是 socket 建在死网络上，之后每次请求都 NoRouteToHost。
     * 没有绑定（无 Wi-Fi）时返回走系统默认网络的 client，行为与历史版本一致。
     */
    private fun lanHttpClient(): HttpClient {
        val network = wifiNetwork
        synchronized(clientLock) {
            val existing = boundClient
            if (existing != null && boundClientNetwork === network) return existing
            runCatching { existing?.close() }
            val client = buildLanHttpClient(network)
            boundClient = client
            boundClientNetwork = network
            return client
        }
    }

    private fun buildLanHttpClient(network: Network?): HttpClient {
        return HttpClient(ClientOkHttp) {
            // 明文 http:// 由宿主 manifest 放行（app 模块 usesCleartextTraffic="true"），见 [lanBaseUrl]。
            // OkHttp 引擎会真正执行平台明文策略（CIO 不执行），那行 manifest 从名义依赖变成硬依赖。
            engine {
                config {
                    if (network != null) socketFactory(network.socketFactory)
                }
            }
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                gson()
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 35000L // 35s timeout to allow TV user confirmation
                connectTimeoutMillis = 6000L
                socketTimeoutMillis = 35000L
            }
        }
    }

    /**
     * Start active discovery of TV devices in the LAN.
     * Returns a DiscoverySession which can be stopped when done.
     */
    fun startDeviceDiscovery(
        context: Context,
        onDevicesUpdated: (List<RemoteDeviceInfo>) -> Unit
    ): DiscoverySession {
        // 客户端路径的第一站：从这里开始跟踪 Wi-Fi 网络，之后所有请求 socket 绑定它（见绑定块 KDoc）。
        observeWifiNetwork(context)
        val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val deviceMap = ConcurrentHashMap<String, RemoteDeviceInfo>()
        val isStopped = AtomicBoolean(false)
        val listenerRef = arrayOfNulls<NsdManager.DiscoveryListener>(1)

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                StunLogger.i(TAG, "NSD discovery started for $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (isStopped.get()) return
                if (service.serviceType.contains("stun_sync") || service.serviceName.contains(SERVICE_NAME_PREFIX)) {
                    val resolveListener = object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            StunLogger.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            if (isStopped.get()) return

                            val candidates = resolveHostCandidates(serviceInfo)
                            if (candidates.isEmpty()) {
                                StunLogger.w(TAG, "Resolve returned no usable address for ${serviceInfo.serviceName}")
                                return
                            }
                            // 必须取排序后的第一个而不是数组第一个：mDNS 不保证把 IPv4 放前面，
                            // 而链路本地 IPv6 丢掉 scope 后根本路由不了，选错地址 →
                            // 设备能搜到却永远判「无法连接」。
                            val hostAddress = candidates.first()
                            val port = serviceInfo.port
                            StunLogger.i(
                                TAG,
                                "Resolved ${serviceInfo.serviceName} -> $hostAddress:$port " +
                                    "(candidateCount=${candidates.size})"
                            )
                            val key = "$hostAddress:$port"
                            val model = serviceInfo.serviceName.removePrefix("$SERVICE_NAME_PREFIX-").replace("_", " ")
                            val device = RemoteDeviceInfo(
                                name = serviceInfo.serviceName,
                                host = hostAddress,
                                port = port,
                                model = model.ifBlank { "Android TV" }
                            )
                            deviceMap[key] = device
                            scope.launch(Dispatchers.Main) {
                                onDevicesUpdated(deviceMap.values.toList())
                            }
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        @Suppress("DEPRECATION")
                        manager.resolveService(service, { it.run() }, resolveListener)
                    } else {
                        @Suppress("DEPRECATION")
                        manager.resolveService(service, resolveListener)
                    }
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                val toRemove = deviceMap.entries.firstOrNull { it.value.name == service.serviceName }?.key
                if (toRemove != null) {
                    deviceMap.remove(toRemove)
                    scope.launch(Dispatchers.Main) {
                        onDevicesUpdated(deviceMap.values.toList())
                    }
                }
            }

            override fun onDiscoveryStopped(regType: String) {
                StunLogger.i(TAG, "NSD discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                StunLogger.e(TAG, "NSD start discovery failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        listenerRef[0] = listener
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            StunLogger.e(TAG, "Failed to start discoverServices", e)
        }

        val autoStopJob = scope.launch {
            delay(25000L) // 25s auto-stop to prevent background battery drain
            if (isStopped.compareAndSet(false, true)) {
                try {
                    listenerRef[0]?.let { manager.stopServiceDiscovery(it) }
                } catch (_: Exception) {}
            }
        }

        return object : DiscoverySession {
            override fun stop() {
                autoStopJob.cancel()
                if (isStopped.compareAndSet(false, true)) {
                    try {
                        listenerRef[0]?.let { manager.stopServiceDiscovery(it) }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * Push profile to a specific TV device with status result.
     */
    suspend fun pushProfileToDevice(host: String, port: Int, profile: Profile): PushResult {
        return try {
            val response: io.ktor.client.statement.HttpResponse =
                lanHttpClient().post("${lanBaseUrl(host, port)}/push_profile") {
                contentType(ContentType.Application.Json)
                setBody(profile)
            }
            when (response.status) {
                HttpStatusCode.OK -> PushResult.SUCCESS
                HttpStatusCode.Forbidden -> PushResult.REJECTED
                else -> PushResult.ERROR
            }
        } catch (e: HttpRequestTimeoutException) {
            PushResult.TIMEOUT
        } catch (e: Exception) {
            StunLogger.e(TAG, "Failed to push profile to $host:$port", e)
            PushResult.ERROR
        }
    }

    /**
     * Query remote TV status (VPN state, active profile, device info).
     *
     * 返回 null 有两种截然不同的含义：TV 没在监听，或者连上了但响应不对。
     * 两者在界面上都渲染成同一个红字「裝置暫時無法連線」，所以失败原因必须进日志 ——
     * 过去只记 `e.message`，`ConnectException: Connection refused` 和 `SocketTimeoutException:
     * failed to connect ... timeout=6000ms` 看起来差不多，而它们的修法完全相反
     * （前者是 TV 端进程没起，后者是路由/防火墙挡住）。
     */
    suspend fun getTvStatus(host: String, port: Int): TvStatusResponse? {
        return try {
            val response: io.ktor.client.statement.HttpResponse =
                lanHttpClient().get("${lanBaseUrl(host, port)}/api/status")
            if (response.status == HttpStatusCode.OK) {
                response.body()
            } else {
                StunLogger.w(TAG, "TV status from $host:$port returned ${response.status}")
                null
            }
        } catch (e: Exception) {
            // 带异常类型而不带堆栈：这里需要的是判别信息（ConnectException / SocketTimeoutException /
            // URLParserException），堆栈对定位没有帮助；而且面板每 1.5s 轮询一次，
            // 真打 ERROR+堆栈就是每分钟几十条刷屏。
            StunLogger.w(TAG, "Failed to fetch TV status from $host:$port: ${e::class.simpleName}: ${e.message}")
            diagnoseLanPort(host, port)
            null
        }
    }

    /**
     * 只在 HTTP 失败之后补的一刀，把「同一个红字」背后的两种故障分开。
     *
     * - **端口拒连**：TV 端的 HTTP 监听面没在跑（进程被杀、保活没顶住），去修 TV 端进程保活。
     * - **端口超时**：路由器/AP 隔离/防火墙挡着，或 TV 上那个随机端口根本没被监听面占用。
     * - **端口开着但 HTTP 没应答**：最有用的一种 —— 说明 TV 端在跑、网络也是通的，
     *   坏在服务端 HTTP 层（请求进不来、handler 卡住），跟「设备不可达」完全无关。
     *
     * HTTP 自己的异常消息分不出这三种：超时和拒连在界面上都是同一句「裝置暫時無法連線」。
     * 不走 [httpClient]：那是带 6s 连接超时的完整 HTTP 栈，失败原因已经在上面的异常里了；
     * 这里要的是最短路径的一次 TCP 三次握手，所以直接开裸 socket。
     */
    private fun diagnoseLanPort(host: String, port: Int) {
        val startNs = System.nanoTime()
        try {
            // 探针必须与 HTTP 客户端走同一条出网路径（绑 Wi-Fi 就从 Wi-Fi 连），
            // 否则会出现「探针通了、HTTP 仍死」的错位，诊断反而误导。
            val socket = wifiNetwork?.socketFactory?.createSocket() ?: Socket()
            socket.use { s -> s.connect(InetSocketAddress(host, port), 1_200) }
            val ms = (System.nanoTime() - startNs) / 1_000_000
            StunLogger.w(
                TAG,
                "LAN port $host:$port is OPEN (${ms}ms) but HTTP /api/status did not answer " +
                    "-> listener is up and reachable, HTTP layer is the problem"
            )
        } catch (e: ConnectException) {
            StunLogger.w(TAG, "LAN port $host:$port REFUSED -> no listener on the TV, process not up")
        } catch (e: SocketTimeoutException) {
            StunLogger.w(
                TAG,
                "LAN port $host:$port TIMED OUT after 1200ms -> route/AP-isolation/firewall between phone and TV"
            )
        } catch (e: Exception) {
            StunLogger.w(TAG, "LAN port probe $host:$port failed: ${e.message}")
        }
    }

    /**
     * Send remote control command to TV.
     */
    suspend fun sendRemoteControl(host: String, port: Int, action: String, profileId: String? = null): Boolean {
        return try {
            val response: io.ktor.client.statement.HttpResponse =
                lanHttpClient().post("${lanBaseUrl(host, port)}/api/control") {
                contentType(ContentType.Application.Json)
                setBody(RemoteControlRequest(action, profileId))
            }
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            StunLogger.e(TAG, "Failed to send control command $action to $host:$port", e)
            false
        }
    }

    private fun findFreePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }
}

