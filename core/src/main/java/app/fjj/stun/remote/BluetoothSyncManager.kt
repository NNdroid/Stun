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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.UUID

object BluetoothSyncManager {
    private const val TAG = "BluetoothSyncManager"
    val STUN_BT_UUID: UUID = UUID.fromString("8ce25a20-4e56-11ee-be56-0242ac120002")

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var serverSocket: BluetoothServerSocket? = null

    @Volatile
    private var isServerRunning = false

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
        if (isServerRunning) return true

        // Check the runtime grant *before* touching the adapter. On API 31+ the framework's own
        // getAddress() call inside listenUsingRfcommWithServiceRecord() throws without it, which
        // used to surface as an unexplained "Bluetooth Sync Server failed" error with a stack trace.
        if (!hasBluetoothConnectPermission(context)) {
            StunLogger.w(
                TAG,
                "BLUETOOTH_CONNECT not granted; Bluetooth sync server not started. " +
                    "Request it from an Activity, then call startServer() again."
            )
            return false
        }

        val adapter = getBluetoothAdapter(context) ?: run {
            StunLogger.w(TAG, "Bluetooth not supported on this device.")
            return false
        }

        try {
            if (!adapter.isEnabled) {
                StunLogger.w(TAG, "Bluetooth is disabled.")
                return false
            }
        } catch (e: SecurityException) {
            StunLogger.w(TAG, "SecurityException checking adapter.isEnabled: ${e.message}")
            return false
        }

        isServerRunning = true
        scope.launch {
            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord("StunCarService", STUN_BT_UUID)
                StunLogger.i(TAG, "Bluetooth Sync Server listening on UUID: $STUN_BT_UUID")

                while (isServerRunning) {
                    val socket = try {
                        serverSocket?.accept()
                    } catch (e: Exception) {
                        if (!isServerRunning) break
                        StunLogger.w(TAG, "Bluetooth accept error: ${e.message}")
                        null
                    }

                    socket?.let { clientSocket ->
                        handleClientConnection(context.applicationContext, clientSocket)
                    }
                }
            } catch (e: Exception) {
                StunLogger.e(TAG, "Bluetooth Sync Server failed", e)
            } finally {
                stopServer()
            }
        }
        return true
    }

    fun stopServer() {
        isServerRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        StunLogger.i(TAG, "Bluetooth Sync Server stopped.")
    }

    /** 服务器是否在跑（Car 端状态徽标用）。 */
    fun isRunning(): Boolean = isServerRunning

    private fun handleClientConnection(context: Context, socket: BluetoothSocket) {
        scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                val writer = PrintWriter(socket.outputStream, true)

                val line = reader.readLine() ?: return@launch
                val responseJson = processRequest(context, line)
                writer.println(responseJson)
            } catch (e: Exception) {
                StunLogger.e(TAG, "Error handling BT client connection", e)
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
                    val ok = if (handler != null) {
                        handler(action, profileId)
                    } else when (action) {
                        // 没有宿主回调（例如宿主没进过 UI）时，只保留启停这条最小回落。
                        "start_vpn" -> {
                            startOrStopService(context, "start", profileId ?: "")
                            true
                        }
                        "stop_vpn" -> {
                            startOrStopService(context, "stop", "")
                            true
                        }
                        else -> false
                    }
                    JSONObject().apply {
                        put("status", if (ok) "success" else "error")
                        if (!ok) put("message", "Action not supported by this device")
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
            socket.connect()

            val writer = PrintWriter(socket.outputStream, true)
            val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))

            writer.println(requestJson)
            val response = reader.readLine() ?: JSONObject().apply {
                put("status", "error")
                put("message", "Empty response from Car Bluetooth")
            }.toString()

            response
        } catch (e: Exception) {
            StunLogger.e(TAG, "BT Send Command Error to ${device.name}", e)
            JSONObject().apply {
                put("status", "error")
                put("message", "Bluetooth error: ${e.message}")
            }.toString()
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Quickly checks if a paired Bluetooth device is reachable by attempting
     * an RFCOMM connection with a 4-second timeout ping.
     * Returns true only if the device accepts the connection (Stun BT server is running).
     */
    @SuppressLint("MissingPermission")
    suspend fun isDeviceReachable(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        var socket: BluetoothSocket? = null
        return@withContext try {
            socket = device.createRfcommSocketToServiceRecord(STUN_BT_UUID)
            // Set a short socket-level timeout via a thread interrupt trick —
            // BluetoothSocket.connect() blocks, so we wrap with withTimeoutOrNull via a thread
            var connected = false
            val connectThread = Thread {
                try {
                    socket.connect()
                    connected = true
                } catch (_: Exception) {}
            }
            connectThread.start()
            connectThread.join(4000L) // wait max 4 seconds
            connectThread.interrupt()
            connected
        } catch (_: Exception) {
            false
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }
}
