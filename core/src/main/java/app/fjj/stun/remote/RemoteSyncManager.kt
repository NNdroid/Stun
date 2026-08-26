package app.fjj.stun.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.Keep
import app.fjj.stun.repo.Profile
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.repo.StunRepository
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.CIO as ClientCIO
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
import java.net.ServerSocket
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
data class TvStatusResponse(
    val vpnState: String,
    val currentProfileName: String?,
    val currentProfileId: String?,
    val profileCount: Int,
    val deviceName: String
)

@Keep
data class RemoteControlRequest(
    val action: String, // "start_vpn", "stop_vpn", "select_profile"
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

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val isServerRunning = AtomicBoolean(false)
    private var nsdManager: NsdManager? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Callbacks on TV side
    var onProfilePushRequested: (suspend (senderIp: String, profile: Profile) -> Boolean)? = null
    var onRemoteControlRequested: (suspend (action: String, profileId: String?) -> Boolean)? = null
    var tvStatusProvider: (() -> TvStatusResponse)? = null

    // --- Server Mode (TV) ---

    fun startServer(context: Context) {
        if (isServerRunning.compareAndSet(false, true)) {
            val port = findFreePort()
            server = embeddedServer(CIO, port = port) {
                install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                    gson { setPrettyPrinting() }
                }
                routing {
                    // Push Profile
                    post("/push_profile") {
                        try {
                            val profile = call.receive<Profile>()
                            val senderIp = call.request.origin.remoteHost
                            StunLogger.i(TAG, "Push request from $senderIp: ${profile.name}")

                            val accepted = onProfilePushRequested?.invoke(senderIp, profile) ?: true
                            if (accepted) {
                                ProfileManager.addProfile(context, profile)
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
                        val status = tvStatusProvider?.invoke() ?: TvStatusResponse(
                            vpnState = StunRepository.vpnState.value?.name ?: "DISCONNECTED",
                            currentProfileName = null,
                            currentProfileId = SettingsManager.getSelectedProfileId(context),
                            profileCount = 0,
                            deviceName = Build.MODEL
                        )
                        call.respond(HttpStatusCode.OK, status)
                    }

                    // Remote Control (Start/Stop VPN, Switch profile)
                    post("/api/control") {
                        try {
                            val req = call.receive<RemoteControlRequest>()
                            val success = onRemoteControlRequested?.invoke(req.action, req.profileId) ?: false
                            if (success) {
                                call.respond(HttpStatusCode.OK, mapOf("status" to "success"))
                            } else {
                                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "control action failed"))
                            }
                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "unknown error")))
                        }
                    }

                    get("/ping") {
                        call.respond(HttpStatusCode.OK, "pong")
                    }
                }
            }.start(wait = false)

            registerService(context, port)
            StunLogger.i(TAG, "Sync server started on port $port")
        }
    }

    fun stopServer() {
        if (isServerRunning.compareAndSet(true, false)) {
            server?.stop(1000, 2000)
            server = null
            unregisterService()
            StunLogger.i(TAG, "Sync server stopped")
        }
    }

    private fun registerService(context: Context, port: Int) {
        nsdManager = (context.getSystemService(Context.NSD_SERVICE) as NsdManager)
        val cleanModel = Build.MODEL.replace("[^a-zA-Z0-9_-]".toRegex(), "_").take(16)
        val serviceName = "$SERVICE_NAME_PREFIX-$cleanModel"
        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = SERVICE_TYPE
            setPort(port)
        }
        nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    private fun unregisterService() {
        try {
            nsdManager?.unregisterService(registrationListener)
        } catch (_: Exception) {}
    }

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            StunLogger.i(TAG, "NSD Service registered: ${serviceInfo.serviceName}")
        }
        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            StunLogger.e(TAG, "NSD Registration failed: $errorCode")
        }
        override fun onServiceUnregistered(arg0: NsdServiceInfo) {}
        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
    }

    // --- Client Mode (Phone) ---

    private val httpClient by lazy {
        HttpClient(ClientCIO) {
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
                            
                            val hostAddress = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                serviceInfo.hostAddresses.firstOrNull { it is java.net.Inet4Address }?.hostAddress
                                    ?: serviceInfo.hostAddresses.firstOrNull()?.hostAddress
                            } else {
                                @Suppress("DEPRECATION")
                                serviceInfo.host?.hostAddress
                            } ?: return

                            val port = serviceInfo.port
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
            val response: io.ktor.client.statement.HttpResponse = httpClient.post("http://$host:$port/push_profile") {
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
     */
    suspend fun getTvStatus(host: String, port: Int): TvStatusResponse? {
        return try {
            val response: io.ktor.client.statement.HttpResponse = httpClient.get("http://$host:$port/api/status")
            if (response.status == HttpStatusCode.OK) {
                response.body()
            } else {
                null
            }
        } catch (e: Exception) {
            StunLogger.w(TAG, "Failed to fetch TV status from $host:$port: ${e.message}")
            null
        }
    }

    /**
     * Send remote control command to TV.
     */
    suspend fun sendRemoteControl(host: String, port: Int, action: String, profileId: String? = null): Boolean {
        return try {
            val response: io.ktor.client.statement.HttpResponse = httpClient.post("http://$host:$port/api/control") {
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

