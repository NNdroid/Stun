package app.fjj.stun.remote

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.fjj.stun.repo.Profile
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.service.MyTransparentProxyService
import app.fjj.stun.service.MyVpnService
import app.fjj.stun.util.ShareCryptoUtils
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object BluetoothSyncManager {
    private const val TAG = "BluetoothSyncManager"
    val STUN_BT_UUID: UUID = UUID.fromString("8ce25a20-4e56-11ee-be56-0242ac120002")

    /** 服务端 RFCOMM 服务名，与手机端 `createRfcommSocketToServiceRecord` 无关（按 UUID 连接），仅出现在扫描列表里。 */
    private const val RFCOMM_SERVICE_NAME = "StunCarService"

    /** 客户端单次会话（connect + 发一行 + 收一行）的硬超时。 */
    private const val BT_SESSION_TIMEOUT_MS = 7000L

    /** 可达性探测的硬超时，沿用原先的 4 秒。 */
    private const val DEVICE_REACHABLE_TIMEOUT_MS = 4000L

    /** accept 连败多少轮后丢弃当前 server socket、重新 listenUsingRfcommWithServiceRecord。 */
    private const val ACCEPT_REBIND_AFTER_FAILURES = 3

    /** accept 失败退避的基础步长（按失败轮数线性递增）。 */
    private const val ACCEPT_BACKOFF_STEP_MS = 1000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var serverSocket: BluetoothServerSocket? = null

    /**
     * CAS 而不是 read-then-set：`TVApp.onCreate` 与 `MainActivity.onCreate` 会竞争同一个入口，
     * 无锁时两边都能通过检查，把两个 RFCOMM server socket 叠在一起（其中一个接到的客户端会丢），
     * 而 [stopServer] 只关掉最后一个。
     */
    private val isServerRunning = AtomicBoolean(false)

    /**
     * 宿主（TV/Car 的 Activity）注册的状态来源，与 [RemoteSyncManager.tvStatusProvider] 同语义。
     * 注册后 BT 通道的 `get_tv_status` 才能给出与 HTTP `/api/status` **同构**的完整状态
     * （节点列表 / 流量 / 隧道类型 / 出口 IP），手机端才能复用同一个远程控制面板。
     * 未注册时回落到 core 默认的最小状态（只有 VPN 状态与选中节点 id）。
     */
    @Volatile
    var tvStatusProvider: (() -> TvStatusResponse)? = null

    /**
     * 宿主注册的控制回调，与 [RemoteSyncManager.onRemoteControlRequested] 同语义
     * （action：`start_vpn` / `stop_vpn` / `restart_vpn` / `select_profile`）。注册后 BT 与
     * HTTP 两条通道共享同一套启停逻辑（含宿主自己的 UI 刷新与 VPN 授权弹窗）。
     * 未注册时 `start_vpn` / `stop_vpn` 回落到旧的直启服务实现，`restart_vpn` / `select_profile`
     * 返回不支持 —— 老手机 APK 发的 `toggle_vpn` 始终走旧路径，不受影响。
     */
    @Volatile
    var onRemoteControlRequested: (suspend (action: String, profileId: String?) -> Boolean)? = null

    /**
     * VPN 状态名的读取口，默认读 [StunRepository]。
     *
     * 抽出来有两个原因：① 与上面两个回调一致，宿主有机会替换状态来源；
     * ② `StunRepository` 的类初始化会碰到 `myssh` 的 JNI 类，**单元测试环境里起不来** ——
     * 动作分发层不硬依赖它，测试才能只覆盖 JSON 协议本身。
     */
    @Volatile
    var vpnStateNameProvider: () -> String? = { StunRepository.vpnState.value?.name }

    private fun currentVpnStateName(): String = vpnStateNameProvider.invoke() ?: "UNKNOWN"

    private fun getBluetoothAdapter(context: Context): BluetoothAdapter? {
        return try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bm?.adapter ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()
        } catch (e: SecurityException) {
            StunLogger.w(TAG, "SecurityException obtaining BluetoothAdapter: ${e.message}")
            null
        }
    }

    /**
     * Whether BLUETOOTH_CONNECT is actually held at runtime.
     *
     * API 31 (Android 12) turned BLUETOOTH_CONNECT into a **runtime** permission. Merely declaring
     * it in the manifest is not enough: the framework checks the grant inside
     * [BluetoothAdapter.listenUsingRfcommWithServiceRecord], which builds a BluetoothSocket that
     * reads the local adapter address. Without the grant the socket constructor throws
     * `SecurityException: Need android.permission.BLUETOOTH_CONNECT ... getAddress` and the
     * RFCOMM server never binds, so phone-to-TV/Car sync silently stops working.
     *
     * Callers that own an Activity should request the permission and call [startServer] again
     * once it is granted — see [startServer].
     */
    fun hasBluetoothConnectPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

    // =========================================================================
    // Server Side (Car Head Unit / TV)
    // =========================================================================

    /**
     * Starts the RFCOMM server so a paired phone can push nodes to / remote-control this device.
     *
     * @return true when the server is running (freshly started or already up); false when it could
     *   not start — no adapter, Bluetooth off, or BLUETOOTH_CONNECT not granted.
     */
    @SuppressLint("MissingPermission")
    fun startServer(context: Context): Boolean {
        if (!isServerRunning.compareAndSet(false, true)) return true
        // 只捕获 applicationContext：`context` 可能是 Activity，钉在常驻 object 的闭包里会泄漏。
        val app = context.applicationContext

        // Check the runtime grant *before* touching the adapter. On API 31+ the framework's own
        // getAddress() call inside listenUsingRfcommWithServiceRecord() throws without it, which
        // used to surface as an unexplained "Bluetooth Sync Server failed" error with a stack trace.
        if (!hasBluetoothConnectPermission(app)) {
            StunLogger.w(
                TAG,
                "BLUETOOTH_CONNECT not granted; Bluetooth sync server not started. " +
                    "Request it from an Activity, then call startServer() again."
            )
            return abortStart("no BLUETOOTH_CONNECT")
        }

        val adapter = getBluetoothAdapter(app) ?: run {
            StunLogger.w(TAG, "Bluetooth not supported on this device.")
            return abortStart("no adapter")
        }

        try {
            if (!adapter.isEnabled) {
                StunLogger.w(TAG, "Bluetooth is disabled.")
                return abortStart("bluetooth disabled")
            }
        } catch (e: SecurityException) {
            StunLogger.w(TAG, "SecurityException checking adapter.isEnabled: ${e.message}")
            return abortStart("SecurityException: ${e.message}")
        }

        // 在**调用线程**上绑定，而不是丢进协程里：
        //  ① 失败时抛出的 SecurityException / IllegalStateException 能直接记成一条清晰的日志，
        //     而不是变成异步的 "Bluetooth Sync Server failed"；
        //  ② 失败一定走 [abortStart] 回滚标志位 —— 否则标志永久卡在 true，之后每次
        //     RemoteControlHost.startBluetooth 都在 isRunning() 短路、报 bt-alive，实际什么都没在听。
        try {
            serverSocket = adapter.listenUsingRfcommWithServiceRecord(RFCOMM_SERVICE_NAME, STUN_BT_UUID)
        } catch (e: Exception) {
            return abortStart(e.message ?: "listenUsingRfcommWithServiceRecord threw")
        }
        StunLogger.i(TAG, "Bluetooth Sync Server listening on UUID: $STUN_BT_UUID")

        scope.launch { acceptLoop(app) }
        return true
    }

    /** 启动失败时统一回滚，避免 isRunning() 卡死在 true（见 [startServer]）。 */
    private fun abortStart(reason: String): Boolean {
        isServerRunning.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        StunLogger.e(TAG, "Bluetooth Sync Server start aborted: $reason")
        return false
    }

    /**
     * accept 循环。原先异常分支里把 socket 置 null 后直接进下一轮 `accept()` —— 持续报错时就是
     * 紧密自旋打日志，而且永远卡在同一个（可能已经坏了的）server socket 上，永不重建。
     * 这里改为有界退避，连败到阈值就重新 listenUsingRfcommWithServiceRecord 换一个 socket。
     */
    @SuppressLint("MissingPermission")
    private suspend fun acceptLoop(context: Context) {
        try {
            var failures = 0
            while (isServerRunning.get()) {
                val clientSocket = try {
                    serverSocket?.accept()
                } catch (e: Exception) {
                    if (!isServerRunning.get()) break
                    failures++
                    StunLogger.w(TAG, "Bluetooth accept error ($failures): ${e.message}")
                    if (failures >= ACCEPT_REBIND_AFTER_FAILURES) {
                        if (!rebindServerSocket(context)) {
                            StunLogger.e(TAG, "Bluetooth accept loop ended: could not rebind")
                            break
                        }
                        failures = 0
                        continue
                    }
                    delay(ACCEPT_BACKOFF_STEP_MS * failures)
                    continue
                }

                failures = 0
                clientSocket?.let { handleClientConnection(context, it) }
            }
        } catch (e: Exception) {
            StunLogger.e(TAG, "Bluetooth Sync Server failed", e)
        } finally {
            stopServer()
        }
    }

    /** 反复 accept 失败后重建 server socket；权限被撤销或蓝牙被关时返回 false。 */
    @SuppressLint("MissingPermission")
    private fun rebindServerSocket(context: Context): Boolean {
        val old = serverSocket
        try { old?.close() } catch (_: Exception) {}
        serverSocket = null
        val adapter = getBluetoothAdapter(context) ?: return false
        try {
            if (!adapter.isEnabled) return false
        } catch (_: SecurityException) {
            return false
        }
        return try {
            serverSocket = adapter.listenUsingRfcommWithServiceRecord(RFCOMM_SERVICE_NAME, STUN_BT_UUID)
            StunLogger.i(TAG, "Bluetooth Sync Server rebound on UUID: $STUN_BT_UUID")
            true
        } catch (e: Exception) {
            StunLogger.e(TAG, "Bluetooth Sync Server rebind failed", e)
            false
        }
    }

    fun stopServer() {
        // CAS：接受循环的 finally 与宿主显式调用会撞上，只让第一个真正关 socket 并打日志。
        if (!isServerRunning.compareAndSet(true, false)) return
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        StunLogger.i(TAG, "Bluetooth Sync Server stopped.")
    }

    /** 服务器是否在跑（Car 端状态徽标用）。 */
    fun isRunning(): Boolean = isServerRunning.get()

    private fun handleClientConnection(context: Context, socket: BluetoothSocket) {
        scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                val writer = PrintWriter(socket.outputStream, true)

            // readLine 有两种「对端走了」：干净的 EOF 返回 null，被中途关断则抛
            // IOException("bt socket closed, read return: -1")。两者语义相同，都按断开处理。
            val line = reader.readLine() ?: run {
                StunLogger.d(TAG, "BT client closed without a request (reachability probe)")
                return@launch
            }
            val responseJson = processRequest(context, line)
            writer.println(responseJson)
        } catch (e: Exception) {
            // 这条通道的协议是「一连接 = 一个请求 = 服务端回一句就挂」，
            // 所以「对端关掉连接」是**正常收尾**而不是错误：可达性探测
            // （见 [isDeviceReachable]）就是连上立刻挂断，每次探测都会走到这里。
            // 过去一律 `StunLogger.e` + 完整堆栈，一次扫描 N 台设备就是 N 条 ERROR，
            // 真故障反而被淹没。按 socket 是否还连着判定：连断了就没东西可处理，降级为 debug。
            if (!socket.isConnected) {
                StunLogger.d(TAG, "BT client disconnected before a request: ${e.message}")
            } else {
                StunLogger.e(TAG, "Error handling BT client connection", e)
            }
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
        }
    }

    internal suspend fun processRequest(context: Context, requestJson: String): String {
        return try {
            val req = JSONObject(requestJson)
            val action = req.optString("action", "")

            when (action) {
                "ping" -> {
                    JSONObject().apply {
                        put("status", "ok")
                        put("device", android.os.Build.MODEL)
                    }.toString()
                }

                "get_tv_status" -> {
                    // 与 RemoteSyncManager 的 GET /api/status 同构：手机端远程控制面板
                    // 用同一份 TvStatusResponse 解析，HTTP 与 BT 两条通道可以共用一套 UI。
                    val status = tvStatusProvider?.invoke() ?: defaultTvStatus(context)
                    JSONObject()
                        .put("status", "ok")
                        .put("tvStatus", JSONObject(Gson().toJson(status)))
                        .toString()
                }

                // 新版手机端的控制动作（与 HTTP /api/control 同名同义），委托给宿主回调，
                // 让 BT 与 HTTP 共享同一套启停/切换逻辑（含宿主自己的 UI 刷新与授权弹窗）。
                "start_vpn", "stop_vpn", "restart_vpn", "select_profile" -> {
                    val handler = onRemoteControlRequested
                    val profileId = req.optString("profileId", "").ifBlank { null }
                    var ok = true
                    var message: String? = null

                    when {
                        handler != null -> ok = handler(action, profileId)
                        // 宿主回调只在宿主界面活着时注册。没它时只保留启停这条最小回落 ——
                        // 真正起停服务不需要界面，但 restart_vpn / select_profile 依赖宿主的
                        // 过渡态防抖和 UI 刷新，不能假装成功。回一个独立 message，让日志（以及
                        // 将来的界面）能区分「动作被拒」与「宿主界面没开」，后者过去会笼统报
                        // "Action not supported by this device"，被误读成设备不支持该动作。
                        action == "start_vpn" -> startOrStopService(context, "start", profileId ?: "")
                        action == "stop_vpn" -> startOrStopService(context, "stop", "")
                        else -> {
                            ok = false
                            message = "Open the app to accept remote control"
                        }
                    }

                    JSONObject().apply {
                        put("status", if (ok) "success" else "error")
                        if (message != null) put("message", message)
                        put("vpnState", currentVpnStateName())
                    }.toString()
                }

                "push_profile" -> {
                    val payload = req.optString("payload", "")
                    val pin = req.optString("pin", "")

                    val jsonStr = if (pin.isNotEmpty()) {
                        ShareCryptoUtils.decrypt(payload, pin)
                    } else if (payload.startsWith("{")) {
                        payload
                    } else {
                        null
                    }

                    if (jsonStr != null) {
                        val profile = Gson().fromJson(jsonStr, Profile::class.java)
                        // 与 WebServer /api/push 同语义（见 resolvePushedProfileId）：保留 id 才能被
                        // 手机端「推送并启动」后续的 start_vpn 命中，也避免重复推送堆积重复条目。
                        profile.id = resolvePushedProfileId(
                            profile.id,
                            ProfileManager.getProfiles(context).map { it.id }.toSet()
                        )
                        ProfileManager.addProfile(context, profile)
                        JSONObject().apply {
                            put("status", "success")
                            put("message", "Profile imported: ${profile.name}")
                        }.toString()
                    } else {
                        JSONObject().apply {
                            put("status", "error")
                            put("message", "Failed to decrypt/parse profile payload")
                        }.toString()
                    }
                }

                "toggle_vpn" -> {
                    // 旧动作，兼容老手机 APK：语义是「选中（可选）并启动/停止」。
                    startOrStopService(context, req.optString("serviceMode", "start"), req.optString("profileId", ""))
                    JSONObject().apply {
                        put("status", "success")
                        put("vpnState", currentVpnStateName())
                    }.toString()
                }

                "get_status" -> {
                    val selected = ProfileManager.getSelectedProfile(context)
                    JSONObject().apply {
                        put("status", "ok")
                        put("vpnState", currentVpnStateName())
                        put("selectedProfileName", selected.name)
                        put("selectedProfileId", selected.id)
                    }.toString()
                }

                else -> {
                    JSONObject().apply {
                        put("status", "error")
                        put("message", "Unknown action: $action")
                    }.toString()
                }
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "error")
                put("message", e.message ?: "Processing error")
            }.toString()
        }
    }

    /**
     * 推送节点的 id 归一（与 `WebServer /api/push` 同语义）：id 为空**或与接收端现存条目冲突**
     * 才重新生成，否则原样保留。
     *
     * **保留 id 是「推送并启动」成立的前提**：手机端推完会紧接着用同一个 id 发 `start_vpn`
     * （或 `select_profile`），无条件重生成会让接收端把选中节点设成一个不存在的 id ——
     * 要么启动到别的节点、要么启动失败。附带收益：同一节点重复推送不再每次都新建一行。
     */
    internal fun resolvePushedProfileId(incomingId: String, existingIds: Set<String>): String =
        if (incomingId.isBlank() || incomingId in existingIds) UUID.randomUUID().toString() else incomingId

    /**
     * 旧 `toggle_vpn` 的直启实现：不经宿主回调、不做任何 UI 联动，按当前服务模式直接起/停前台服务。
     * 保留给未注册 [onRemoteControlRequested] 的宿主（以及老手机 APK 的 `toggle_vpn`）。
     */
    private fun startOrStopService(context: Context, serviceCmd: String, profileId: String) {
        if (profileId.isNotEmpty()) {
            SettingsManager.setSelectedProfileId(context, profileId)
        }

        val isTProxy = SettingsManager.getServiceMode(context) == SettingsManager.SERVICE_MODE_TPROXY
        val intentClass = if (isTProxy) MyTransparentProxyService::class.java else MyVpnService::class.java
        val intentAction = if (serviceCmd == "start") {
            if (isTProxy) MyTransparentProxyService.ACTION_START else MyVpnService.ACTION_START
        } else {
            if (isTProxy) MyTransparentProxyService.ACTION_STOP else MyVpnService.ACTION_STOP
        }
        ContextCompat.startForegroundService(
            context,
            Intent(context, intentClass).apply { action = intentAction }
        )
    }

    /** 没有宿主 [tvStatusProvider] 时的最小状态（与 RemoteSyncManager 的 /api/status 回落一致）。 */
    private fun defaultTvStatus(context: Context): TvStatusResponse = TvStatusResponse(
        vpnState = currentVpnStateName(),
        currentProfileName = null,
        currentProfileId = SettingsManager.getSelectedProfileId(context),
        profileCount = 0,
        deviceName = Build.MODEL
    )

    // =========================================================================
    // Client Side (Phone App)
    // =========================================================================

    @SuppressLint("MissingPermission")
    fun getPairedBluetoothDevices(context: Context): List<BluetoothDevice> {
        val adapter = getBluetoothAdapter(context) ?: return emptyList()

        try {
            if (!adapter.isEnabled) return emptyList()
            return adapter.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            StunLogger.w(TAG, "SecurityException getting bonded devices: ${e.message}")
            return emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun sendBluetoothCommand(
        device: BluetoothDevice,
        requestJson: String
    ): String = withContext(Dispatchers.IO) {
        var socket: BluetoothSocket? = null
        try {
            socket = device.createRfcommSocketToServiceRecord(STUN_BT_UUID)
            val response = runBtSession(socket, BT_SESSION_TIMEOUT_MS) {
                socket.connect()
                val writer = PrintWriter(socket.outputStream, true)
                val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                writer.println(requestJson)
                reader.readLine()
            }

            response ?: JSONObject().apply {
                put("status", "error")
                put("message", "Bluetooth timeout or empty response")
            }.toString()
        } catch (e: Exception) {
            StunLogger.e(TAG, "BT Send Command Error", e)
            JSONObject().apply {
                put("status", "error")
                put("message", "Bluetooth error: ${e.message}")
            }.toString()
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * 可达性探测：设备必须**接受** RFCOMM 连接才算在线（即对端的 Stun 蓝牙服务在跑）。
     * 沿用原先的 4 秒上限。
     */
    @SuppressLint("MissingPermission")
    suspend fun isDeviceReachable(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        var socket: BluetoothSocket? = null
        try {
            socket = device.createRfcommSocketToServiceRecord(STUN_BT_UUID)
            runBtSession(socket, DEVICE_REACHABLE_TIMEOUT_MS) { socket.connect() } != null
        } catch (_: Exception) {
            false
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * 在 [deadlineMs] 内等 [block] 返回，超时就关掉 socket 强制解除阻塞。
     *
     * RFCOMM 的 [BluetoothSocket.connect] 和 `inputStream.readLine()` 都**无限期阻塞**，而且
     * 都不响应协程取消 —— 调用方 `withTimeoutOrNull(8_000L)` 取消的是外层协程，底下的 IO 线程
     * 照样卡在系统调用里，设备不在旁边时每次尝试都泄漏一个线程（累积起来会拖垮整个应用）。
     * 从另一个线程 `socket.close()` 是打断它们的唯一可靠手段：框架会让阻塞的 connect / read 抛
     * IOException 返回。
     *
     * @return [block] 的返回值；超时或中途抛异常时返回 null（异常已记日志，不吞掉细节）。
     */
    private fun <T> runBtSession(socket: BluetoothSocket, deadlineMs: Long, block: () -> T): T? {
        val result = AtomicReference<T?>(null)
        val finished = AtomicBoolean(false)
        val worker = Thread({
            try {
                result.set(block())
            } catch (t: Exception) {
                StunLogger.w(TAG, "Bluetooth session failed: ${t.message}")
            } finally {
                finished.set(true)
            }
        }, "bt-session-${System.identityHashCode(socket)}")
        worker.isDaemon = true
        worker.start()
        worker.join(deadlineMs)
        if (finished.get()) return result.get()
        // 到点了但底层还没返回：关掉 socket 打断它，再给一小段收尾时间。
        try { socket.close() } catch (_: Exception) {}
        worker.join(1500L)
        if (!finished.get()) worker.interrupt()
        StunLogger.w(TAG, "Bluetooth session timed out after ${deadlineMs}ms")
        return null
    }
}
