package app.fjj.stun.remote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
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

    @Volatile
    private var serverSocket: BluetoothServerSocket? = null

    @Volatile
    private var isServerRunning = false

    // =========================================================================
    // Server Side (Car Head Unit / TV)
    // =========================================================================

    @SuppressLint("MissingPermission")
    fun startServer(context: Context) {
        if (isServerRunning) return
        val adapter = try {
            BluetoothAdapter.getDefaultAdapter()
        } catch (e: SecurityException) {
            StunLogger.w(TAG, "SecurityException obtaining BluetoothAdapter: ${e.message}")
            return
        } ?: run {
            StunLogger.w(TAG, "Bluetooth not supported on this device.")
            return
        }

        try {
            if (!adapter.isEnabled) {
                StunLogger.w(TAG, "Bluetooth is disabled.")
                return
            }
        } catch (e: SecurityException) {
            StunLogger.w(TAG, "SecurityException checking adapter.isEnabled: ${e.message}")
            return
        }

        isServerRunning = true
        GlobalScope.launch(Dispatchers.IO) {
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
    }

    fun stopServer() {
        isServerRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        StunLogger.i(TAG, "Bluetooth Sync Server stopped.")
    }

    private fun handleClientConnection(context: Context, socket: BluetoothSocket) {
        GlobalScope.launch(Dispatchers.IO) {
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

    private suspend fun processRequest(context: Context, requestJson: String): String {
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
                        profile.id = UUID.randomUUID().toString()
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
                    val serviceCmd = req.optString("serviceMode", "start")
                    val profileId = req.optString("profileId", "")

                    if (profileId.isNotEmpty()) {
                        SettingsManager.setSelectedProfileId(context, profileId)
                    }

                    val mode = SettingsManager.getServiceMode(context)
                    val intentClass = if (mode == SettingsManager.SERVICE_MODE_TPROXY) {
                        MyTransparentProxyService::class.java
                    } else {
                        MyVpnService::class.java
                    }

                    val intentAction = if (serviceCmd == "start") "START" else "STOP"
                    val serviceIntent = Intent(context, intentClass).apply { 
                        this.action = intentAction 
                    }
                    ContextCompat.startForegroundService(context, serviceIntent)

                    JSONObject().apply {
                        put("status", "success")
                        put("vpnState", StunRepository.vpnState.value?.name ?: "UNKNOWN")
                    }.toString()
                }

                "get_status" -> {
                    val selected = ProfileManager.getSelectedProfile(context)
                    JSONObject().apply {
                        put("status", "ok")
                        put("vpnState", StunRepository.vpnState.value?.name ?: "DISCONNECTED")
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

    // =========================================================================
    // Client Side (Phone App)
    // =========================================================================

    @SuppressLint("MissingPermission")
    fun getPairedBluetoothDevices(): List<BluetoothDevice> {
        val adapter = try {
            BluetoothAdapter.getDefaultAdapter()
        } catch (e: SecurityException) {
            StunLogger.w(TAG, "SecurityException obtaining BluetoothAdapter: ${e.message}")
            return emptyList()
        } ?: return emptyList()

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
}
