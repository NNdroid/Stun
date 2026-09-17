package app.fjj.stun.remote

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.fjj.stun.repo.LogLevel
import app.fjj.stun.repo.Profile
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.repo.SubscriptionManager
import app.fjj.stun.service.VpnConfigBuilder
import app.fjj.stun.repo.VpnState
import app.fjj.stun.service.MyTransparentProxyService
import app.fjj.stun.service.MyVpnService
import app.fjj.stun.util.AppUtils
import app.fjj.stun.worker.WebDavBackupWorker
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.network.tls.certificates.generateCertificate
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeStringUtf8
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.KeyStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lightweight delegating Socket that redirects inputStream to a peeked PushbackInputStream
 * allowing SSLSocket to parse from the pre-read stream after protocol sniffing.
 */
private class SniffedSocket(
    private val delegate: Socket,
    private val peekedInputStream: InputStream
) : Socket() {
    override fun getInputStream(): InputStream = peekedInputStream
    override fun getOutputStream(): OutputStream = delegate.getOutputStream()
    override fun isConnected(): Boolean = delegate.isConnected
    override fun isClosed(): Boolean = delegate.isClosed
    override fun isBound(): Boolean = delegate.isBound
    override fun getInetAddress() = delegate.inetAddress
    override fun getPort() = delegate.port
    override fun getLocalPort() = delegate.localPort
    override fun getLocalSocketAddress() = delegate.localSocketAddress
    override fun getRemoteSocketAddress() = delegate.remoteSocketAddress
    override fun close() = delegate.close()
    override fun setTcpNoDelay(on: Boolean) { delegate.tcpNoDelay = on }
    override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay
    override fun setSoTimeout(timeout: Int) { delegate.soTimeout = timeout }
    override fun getSoTimeout(): Int = delegate.soTimeout
}

/**
 * Full-Featured MCP server for Stun Android.
 *
 * The primary transport is Streamable HTTP on /mcp. The legacy HTTP+SSE
 * endpoints remain available during the migration period.
 * Exposes complete VPN controls, node management, split-tunneling app filter,
 * GeoData routing updates, system logs, live resource change notifications,
 * and prompt workflows to MCP clients such as Codex and Claude Code.
 */
object StunMcpServer {
    private const val TAG = "StunMcpServer"
    // 与底部栏 LatencyProber 同源的延迟探测参数（真握手延迟，非裸 TCP 直连）。
    private const val PING_URL = "http://cp.cloudflare.com/generate_204"
    private const val PING_TIMEOUT_MS = 8000L
    private const val LATEST_PROTOCOL_VERSION = "2025-11-25"
    private const val LEGACY_PROTOCOL_VERSION = "2024-11-05"
    private val SUPPORTED_PROTOCOL_VERSIONS = setOf(
        LATEST_PROTOCOL_VERSION,
        "2025-06-18",
        "2025-03-26",
        LEGACY_PROTOCOL_VERSION
    )
    private const val MCP_SESSION_ID_HEADER = "Mcp-Session-Id"
    private const val MCP_PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version"
    private const val SERVER_NAME = "stun-android-mcp"
    private const val SERVER_VERSION = "2.0.0"

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val isServerRunning = AtomicBoolean(false)
    private var serverPort: Int = SettingsManager.DEFAULT_MCP_SERVER_PORT
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var stateObserverJob: Job? = null

    // Sniffer Dispatcher for Single-Port HTTP + HTTPS Multiplexing
    private var snifferServerSocket: ServerSocket? = null
    private var snifferJob: Job? = null
    private var sslContext: SSLContext? = null
    private var cachedCertPem: String? = null

    // Legacy HTTP+SSE sessions: sessionId -> event Channel
    private val legacySseSessions = ConcurrentHashMap<String, Channel<String>>()

    private data class StreamableSession(
        val protocolVersion: String,
        val eventChannel: Channel<String> = Channel(Channel.BUFFERED),
        val streamOpen: AtomicBoolean = AtomicBoolean(false),
        @Volatile var lastAccessAt: Long = System.currentTimeMillis()
    )

    // Modern Streamable HTTP sessions created by initialize on POST /mcp.
    private val streamableSessions = ConcurrentHashMap<String, StreamableSession>()
    // Active OAuth 2.0 Tokens: token -> expiry timestamp
    private val activeOAuthTokens = ConcurrentHashMap<String, Long>()
    private val activeAuthCodes = ConcurrentHashMap<String, Long>()
    private val gson = Gson()
    private val prettyGson = GsonBuilder().setPrettyPrinting().create()

    fun isRunning(): Boolean = isServerRunning.get()
    fun getPort(): Int = serverPort

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    fun getMcpUrl(scheme: String = "http", host: String? = null): String {
        val targetHost = host ?: "${getLocalIpAddress()}:$serverPort"
        return "$scheme://$targetHost/mcp"
    }

    /** Legacy HTTP+SSE URL kept for older MCP clients. */
    fun getSseUrl(scheme: String = "http", host: String? = null): String {
        val targetHost = host ?: "${getLocalIpAddress()}:$serverPort"
        return "$scheme://$targetHost/mcp/sse"
    }

    fun getClaudeConfigJson(context: Context? = null, scheme: String = "http", host: String? = null): String {
        val targetHost = host ?: "${getLocalIpAddress()}:$serverPort"
        val authMode = context?.let { SettingsManager.getMcpAuthMode(it) } ?: SettingsManager.MCP_AUTH_MODE_NONE
        val apiKey = context?.let { SettingsManager.getMcpApiKey(it) } ?: ""

        val authorization = when (authMode) {
            SettingsManager.MCP_AUTH_MODE_API_KEY -> apiKey.takeIf { it.isNotBlank() }?.let { "Bearer $it" }
            SettingsManager.MCP_AUTH_MODE_BASIC -> "Basic <BASE64_USER_PASS>"
            SettingsManager.MCP_AUTH_MODE_OAUTH -> "Bearer <OAUTH_ACCESS_TOKEN>"
            else -> null
        }
        val serverConfig = JsonObject().apply {
            addProperty("type", "http")
            addProperty("url", "$scheme://$targetHost/mcp")
            if (authorization != null) {
                add("headers", JsonObject().apply { addProperty("Authorization", authorization) })
            }
        }
        return prettyGson.toJson(
            JsonObject().apply {
                add("mcpServers", JsonObject().apply { add("stun-android", serverConfig) })
            }
        )
    }

    fun getCodexConfigToml(context: Context? = null, scheme: String = "http", host: String? = null): String {
        val targetHost = host ?: "${getLocalIpAddress()}:$serverPort"
        val authMode = context?.let { SettingsManager.getMcpAuthMode(it) } ?: SettingsManager.MCP_AUTH_MODE_NONE
        val apiKey = context?.let { SettingsManager.getMcpApiKey(it) } ?: ""
        fun tomlString(value: String): String = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")

        val authorization = when (authMode) {
            SettingsManager.MCP_AUTH_MODE_API_KEY -> apiKey.takeIf { it.isNotBlank() }?.let { "Bearer $it" }
            SettingsManager.MCP_AUTH_MODE_BASIC -> "Basic <BASE64_USER_PASS>"
            SettingsManager.MCP_AUTH_MODE_OAUTH -> "Bearer <OAUTH_ACCESS_TOKEN>"
            else -> null
        }
        val headersBlock = authorization?.let {
            "\n\n[mcp_servers.stun_android.http_headers]\nAuthorization = \"${tomlString(it)}\""
        }.orEmpty()

        return """
[mcp_servers.stun_android]
url = "$scheme://$targetHost/mcp"$headersBlock
        """.trimIndent()
    }

    private fun findFreePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    private fun initSsl(appContext: Context) {
        try {
            val keyStoreFile = File(appContext.filesDir, "stun_mcp_keystore.jks")
            val keyStorePassword = "stun_ssl_password"
            val keyAlias = "stun_mcp_ssl"

            val keyStore: KeyStore = try {
                if (!keyStoreFile.exists()) {
                    generateCertificate(
                        file = keyStoreFile,
                        keyAlias = keyAlias,
                        keyPassword = keyStorePassword,
                        jksPassword = keyStorePassword
                    )
                } else {
                    KeyStore.getInstance("JKS").apply {
                        keyStoreFile.inputStream().use { load(it, keyStorePassword.toCharArray()) }
                    }
                }
            } catch (e: Exception) {
                StunLogger.w(TAG, "Failed to load keystore, regenerating fresh: ${e.message}")
                if (keyStoreFile.exists()) keyStoreFile.delete()
                generateCertificate(
                    file = keyStoreFile,
                    keyAlias = keyAlias,
                    keyPassword = keyStorePassword,
                    jksPassword = keyStorePassword
                )
            }

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, keyStorePassword.toCharArray())
            val sslCtx = SSLContext.getInstance("TLS")
            sslCtx.init(kmf.keyManagers, null, null)
            sslContext = sslCtx

            // Cache PEM for /mcp/cert download
            val cert = keyStore.getCertificate(keyAlias)
            if (cert != null) {
                val base64Cert = android.util.Base64.encodeToString(cert.encoded, android.util.Base64.DEFAULT)
                cachedCertPem = "-----BEGIN CERTIFICATE-----\n$base64Cert-----END CERTIFICATE-----\n"
            }
        } catch (e: Exception) {
            StunLogger.e(TAG, "Failed to init SSL context for sniffer: ${e.message}")
        }
    }

    private fun startSniffer(publicPort: Int, internalPort: Int) {
        val serverSock = ServerSocket(publicPort)
        snifferServerSocket = serverSock

        snifferJob = scope.launch(Dispatchers.IO) {
            while (isActive && !serverSock.isClosed) {
                try {
                    val clientSock = serverSock.accept()
                    handleSniffedConnection(clientSock, internalPort)
                } catch (e: Exception) {
                    if (serverSock.isClosed) break
                }
            }
        }
    }

    private fun handleSniffedConnection(clientSocket: Socket, backendPort: Int) {
        scope.launch(Dispatchers.IO) {
            try {
                clientSocket.tcpNoDelay = true
                val rawIn = clientSocket.getInputStream()
                val pushbackIn = PushbackInputStream(rawIn, 16)
                val firstByte = pushbackIn.read()
                if (firstByte == -1) {
                    clientSocket.close()
                    return@launch
                }
                pushbackIn.unread(firstByte)

                val isTls = (firstByte == 0x16)
                val backendSocket = Socket("127.0.0.1", backendPort).apply {
                    tcpNoDelay = true
                }

                val currentSslCtx = sslContext
                if (isTls && currentSslCtx != null) {
                    val sniffedSocket = SniffedSocket(clientSocket, pushbackIn)
                    val sslSocket = (currentSslCtx.socketFactory.createSocket(
                        sniffedSocket,
                        clientSocket.inetAddress?.hostAddress ?: "127.0.0.1",
                        clientSocket.port,
                        true
                    ) as SSLSocket).apply {
                        useClientMode = false
                        tcpNoDelay = true
                    }

                    val job1 = launch(Dispatchers.IO) {
                        try {
                            pipeHttpRequestWithHeader(sslSocket.inputStream, backendSocket.getOutputStream(), "https")
                        } finally {
                            try { backendSocket.shutdownOutput() } catch (_: Exception) {}
                        }
                    }
                    val job2 = launch(Dispatchers.IO) {
                        try {
                            pipeStreams(backendSocket.getInputStream(), sslSocket.outputStream)
                        } finally {
                            try { sslSocket.close() } catch (_: Exception) {}
                        }
                    }
                    job1.join()
                    job2.join()
                } else {
                    val job1 = launch(Dispatchers.IO) {
                        try {
                            pipeHttpRequestWithHeader(pushbackIn, backendSocket.getOutputStream(), "http")
                        } finally {
                            try { backendSocket.shutdownOutput() } catch (_: Exception) {}
                        }
                    }
                    val job2 = launch(Dispatchers.IO) {
                        try {
                            pipeStreams(backendSocket.getInputStream(), clientSocket.getOutputStream())
                        } finally {
                            try { clientSocket.close() } catch (_: Exception) {}
                        }
                    }
                    job1.join()
                    job2.join()
                }
            } catch (_: Exception) {
            } finally {
                try { clientSocket.close() } catch (_: Exception) {}
            }
        }
    }

    private suspend fun pipeStreams(input: InputStream, output: OutputStream) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(8192)
        try {
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                output.flush()
            }
        } catch (_: Exception) {}
    }

    /**
     * Reads the first HTTP request line/headers and injects X-Forwarded-Proto: [scheme]
     * so internal Ktor can accurately determine whether the client connected via HTTP or HTTPS.
     */
    private suspend fun pipeHttpRequestWithHeader(
        input: InputStream,
        output: OutputStream,
        scheme: String
    ) = withContext(Dispatchers.IO) {
        try {
            val headerBytes = java.io.ByteArrayOutputStream()
            var prev1 = -1
            var prev2 = -1
            var prev3 = -1
            var b: Int
            var headerEnded = false

            while (input.read().also { b = it } != -1) {
                headerBytes.write(b)
                if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
                    headerEnded = true
                    break
                }
                prev3 = prev2
                prev2 = prev1
                prev1 = b
            }

            if (headerEnded) {
                val headerStr = headerBytes.toString("UTF-8")
                val modifiedHeader = if (!headerStr.contains("X-Forwarded-Proto", ignoreCase = true)) {
                    val crlfIndex = headerStr.indexOf("\r\n")
                    if (crlfIndex != -1) {
                        headerStr.substring(0, crlfIndex + 2) +
                                "X-Forwarded-Proto: $scheme\r\n" +
                                headerStr.substring(crlfIndex + 2)
                    } else {
                        headerStr
                    }
                } else {
                    headerStr
                }
                val modBytes = modifiedHeader.toByteArray(Charsets.UTF_8)
                output.write(modBytes)
                output.flush()
            } else if (headerBytes.size() > 0) {
                output.write(headerBytes.toByteArray())
                output.flush()
            }

            // Pipe the remaining payload (body/SSE stream)
            pipeStreams(input, output)
        } catch (_: Exception) {}
    }

    /**
     * Resolves the request URL scheme (http or https) dynamically from X-Forwarded-Proto header.
     */
    private fun getRequestScheme(call: ApplicationCall): String {
        return call.request.header("X-Forwarded-Proto")
            ?: call.request.header("X-Forwarded-Scheme")
            ?: if (call.request.local.scheme == "https") "https" else "http"
    }

    /**
     * Resolves the base URL for the current request dynamically, e.g. "https://127.0.0.77:37180"
     */
    private fun getBaseUrl(call: ApplicationCall): String {
        val scheme = getRequestScheme(call)
        val host = call.request.header("Host") ?: "${getLocalIpAddress()}:$serverPort"
        return "$scheme://$host"
    }

    @Synchronized
    fun start(context: Context, port: Int = SettingsManager.getMcpServerPort(context)) {
        if (isServerRunning.compareAndSet(false, true)) {
            serverPort = port
            val appContext = context.applicationContext

            scope.launch {
                try {
                    startStateObserver(appContext)

                    // 1. Initialize SSL Context for HTTPS Sniffing
                    initSsl(appContext)

                    // 2. Start internal Ktor on loopback port
                    val internalPort = findFreePort()
                    server = embeddedServer(
                        factory = CIO,
                        configure = {
                            connector {
                                this.host = "127.0.0.1"
                                this.port = internalPort
                            }
                        }
                    ) {
                        routing {
                            // ─── 1. Web Dashboard & Info (Public) ───
                            get("/") {
                                call.respondText(renderDashboardHtml(appContext, call), ContentType.Text.Html)
                            }
                            get("/mcp/cert") {
                                val pem = cachedCertPem
                                if (pem != null) {
                                    call.response.headers.append("Content-Disposition", "attachment; filename=\"stun_ca.crt\"")
                                    call.respondText(pem, ContentType.parse("application/x-x509-ca-cert"))
                                } else {
                                    call.respond(HttpStatusCode.NotFound, "Certificate not found")
                                }
                            }

                            // ─── OAuth 2.0 Authorization Server Endpoints (RFC 6749 / RFC 8414 / RFC 7636 PKCE) ───
                            val respondOauthMeta: suspend (ApplicationCall) -> Unit = { call ->
                                val baseUrl = getBaseUrl(call)
                                val oauthMeta = JsonObject().apply {
                                    addProperty("issuer", baseUrl)
                                    addProperty("authorization_endpoint", "$baseUrl/authorize")
                                    addProperty("token_endpoint", "$baseUrl/token")
                                    val grants = JsonArray().apply {
                                        add("client_credentials")
                                        add("authorization_code")
                                        add("refresh_token")
                                    }
                                    add("grant_types_supported", grants)
                                    val responses = JsonArray().apply {
                                        add("code")
                                        add("token")
                                    }
                                    add("response_types_supported", responses)
                                    val codeChallengeMethods = JsonArray().apply {
                                        add("S256")
                                        add("plain")
                                    }
                                    add("code_challenge_methods_supported", codeChallengeMethods)
                                    val tokenAuthMethods = JsonArray().apply {
                                        add("client_secret_post")
                                        add("client_secret_basic")
                                        add("none")
                                    }
                                    add("token_endpoint_auth_methods_supported", tokenAuthMethods)
                                }
                                call.respondText(gson.toJson(oauthMeta), ContentType.Application.Json)
                            }

                            get("/.well-known/oauth-authorization-server") { respondOauthMeta(call) }
                            get("/.well-known/openid-configuration") { respondOauthMeta(call) }

                            val handleAuthorize: suspend (ApplicationCall) -> Unit = { call ->
                                val clientId = call.request.queryParameters["client_id"] ?: ""
                                val redirectUri = call.request.queryParameters["redirect_uri"] ?: ""
                                val state = call.request.queryParameters["state"] ?: ""
                                val authCode = "stunc_auth_" + UUID.randomUUID().toString().replace("-", "")
                                activeAuthCodes[authCode] = System.currentTimeMillis() + 600000L // 10 mins

                                val redirectUrl = if (redirectUri.isNotBlank()) {
                                    val sep = if (redirectUri.contains("?")) "&" else "?"
                                    "$redirectUri${sep}code=$authCode&state=$state"
                                } else null

                                val html = renderOAuthAuthorizeHtml(clientId, redirectUrl, authCode, call, appContext)
                                call.respondText(html, ContentType.Text.Html)
                            }

                            get("/oauth/authorize") { handleAuthorize(call) }
                            get("/authorize") { handleAuthorize(call) }

                            val handleToken: suspend (ApplicationCall) -> Unit = { call ->
                                val formParams = try { call.receiveParameters() } catch (_: Exception) { null }
                                val grantType = formParams?.get("grant_type") ?: call.request.queryParameters["grant_type"] ?: "client_credentials"
                                val clientId = formParams?.get("client_id") ?: call.request.queryParameters["client_id"] ?: ""
                                val clientSecret = formParams?.get("client_secret") ?: call.request.queryParameters["client_secret"] ?: ""
                                val code = formParams?.get("code") ?: call.request.queryParameters["code"] ?: ""

                                val configuredClientId = SettingsManager.getMcpOAuthClientId(appContext)
                                val configuredSecret = SettingsManager.getMcpOAuthClientSecret(appContext)

                                var authorized = false
                                if (grantType == "client_credentials") {
                                    if (configuredSecret.isBlank() || (clientId == configuredClientId && clientSecret == configuredSecret)) {
                                        authorized = true
                                    }
                                } else if (grantType == "authorization_code") {
                                    val expiry = activeAuthCodes.remove(code)
                                    if (expiry != null && System.currentTimeMillis() <= expiry) {
                                        authorized = true
                                    }
                                } else if (grantType == "refresh_token") {
                                    authorized = true
                                }

                                if (!authorized) {
                                    call.respondText(
                                        "{\"error\": \"invalid_grant\", \"error_description\": \"Invalid client credentials, grant_type or authorization code\"}",
                                        ContentType.Application.Json,
                                        HttpStatusCode.Unauthorized
                                    )
                                } else {
                                    val accessToken = "stuntok_" + UUID.randomUUID().toString().replace("-", "")
                                    activeOAuthTokens[accessToken] = System.currentTimeMillis() + 86400000L // 24 hours

                                    val tokenResponse = JsonObject().apply {
                                        addProperty("access_token", accessToken)
                                        addProperty("token_type", "Bearer")
                                        addProperty("expires_in", 86400)
                                        addProperty("scope", "mcp:all")
                                    }
                                    call.respondText(gson.toJson(tokenResponse), ContentType.Application.Json)
                                }
                            }

                            post("/oauth/token") { handleToken(call) }
                            post("/token") { handleToken(call) }
                            get("/oauth/token") { handleToken(call) }
                            get("/token") { handleToken(call) }

                            // ─── Protected API & MCP Endpoints ───
                            get("/mcp/status") {
                                if (!validateAuth(call, appContext)) {
                                    respondUnauthorized(call, appContext)
                                    return@get
                                }
                                val status = buildStatusJson(appContext)
                                call.respondText(status.toString(), ContentType.Application.Json)
                            }

                            // ─── Gemini Direct API / Function Calling Endpoints ───
                            get("/gemini/declarations") {
                                if (!validateAuth(call, appContext)) {
                                    respondUnauthorized(call, appContext)
                                    return@get
                                }
                                val decls = buildGeminiFunctionDeclarations()
                                call.respondText(gson.toJson(decls), ContentType.Application.Json)
                            }
                            get("/gemini/tools") {
                                if (!validateAuth(call, appContext)) {
                                    respondUnauthorized(call, appContext)
                                    return@get
                                }
                                val decls = buildGeminiFunctionDeclarations()
                                call.respondText(gson.toJson(decls), ContentType.Application.Json)
                            }
                            post("/gemini/call") {
                                if (!validateAuth(call, appContext)) {
                                    respondUnauthorized(call, appContext)
                                    return@post
                                }
                                val rawBody = call.receiveText()
                                val response = handleGeminiCall(appContext, rawBody)
                                call.respondText(response, ContentType.Application.Json)
                            }
                            post("/gemini/execute") {
                                if (!validateAuth(call, appContext)) {
                                    respondUnauthorized(call, appContext)
                                    return@post
                                }
                                val rawBody = call.receiveText()
                                val response = handleGeminiCall(appContext, rawBody)
                                call.respondText(response, ContentType.Application.Json)
                            }

                            // ─── 2. MCP Streamable HTTP (2025-11-25) ───
                            post("/mcp") {
                                handleStreamablePost(call, appContext)
                            }
                            get("/mcp") {
                                handleStreamableGet(call, appContext)
                            }
                            delete("/mcp") {
                                handleStreamableDelete(call, appContext)
                            }

                            // ─── 3. Legacy MCP HTTP+SSE compatibility endpoints ───
                            get("/mcp/sse") {
                                if (!validateOrigin(call)) {
                                    respondMcpHttpError(call, HttpStatusCode.Forbidden, -32000, "Invalid Origin header")
                                    return@get
                                }
                                if (!validateAuth(call, appContext)) {
                                    respondUnauthorized(call, appContext)
                                    return@get
                                }

                                val sessionId = UUID.randomUUID().toString()
                                val eventChannel = Channel<String>(Channel.BUFFERED)
                                legacySseSessions[sessionId] = eventChannel

                                StunLogger.i(TAG, "New legacy MCP SSE connection opened: $sessionId (Total: ${legacySseSessions.size})")

                                try {
                                    call.response.headers.append("Cache-Control", "no-cache")
                                    call.response.headers.append("Connection", "keep-alive")
                                    call.respondBytesWriter(contentType = ContentType.parse("text/event-stream; charset=UTF-8")) {
                                        // Send endpoint discovery event
                                        val endpointEvent = "event: endpoint\ndata: /mcp/messages?sessionId=$sessionId\n\n"
                                        writeStringUtf8(endpointEvent)
                                        flush()

                                        // Stream incoming events from channel
                                        for (msg in eventChannel) {
                                            val sseMsg = "event: message\ndata: $msg\n\n"
                                            writeStringUtf8(sseMsg)
                                            flush()
                                        }
                                    }
                                } finally {
                                    legacySseSessions.remove(sessionId)
                                    eventChannel.close()
                                    StunLogger.i(TAG, "Legacy MCP SSE connection closed: $sessionId")
                                }
                            }

                            post("/mcp/messages") {
                                if (!validateOrigin(call)) {
                                    respondMcpHttpError(call, HttpStatusCode.Forbidden, -32000, "Invalid Origin header")
                                    return@post
                                }
                                if (!validateAuth(call, appContext)) {
                                    respondUnauthorized(call, appContext)
                                    return@post
                                }

                                val sessionId = call.request.queryParameters["sessionId"]
                                val rawBody = call.receiveText()

                                if (sessionId == null || !legacySseSessions.containsKey(sessionId)) {
                                    val response = handleJsonRpcRequest(appContext, rawBody, LEGACY_PROTOCOL_VERSION)
                                    call.respondText(response, ContentType.Application.Json)
                                    return@post
                                }

                                val eventChannel = legacySseSessions[sessionId]
                                val response = handleJsonRpcRequest(appContext, rawBody, LEGACY_PROTOCOL_VERSION)

                                if (response.isNotBlank()) {
                                    eventChannel?.send(response)
                                }
                                call.respond(HttpStatusCode.Accepted, "Accepted")
                            }
                        }
                    }.start(wait = false)

                    // 3. Start Single-Port Protocol Sniffer on public serverPort (HTTP + HTTPS Multiplexing)
                    startSniffer(serverPort, internalPort)

                    StunLogger.i(TAG, "Stun MCP Server successfully started on port $serverPort (HTTP + HTTPS Single-Port Sniffer Active)")
                } catch (e: Exception) {
                    isServerRunning.set(false)
                    StunLogger.e(TAG, "Failed to start MCP Server on port $serverPort", e)
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        if (isServerRunning.compareAndSet(true, false)) {
            stateObserverJob?.cancel()
            stateObserverJob = null
            legacySseSessions.values.forEach { it.close() }
            legacySseSessions.clear()
            streamableSessions.values.forEach { it.eventChannel.close() }
            streamableSessions.clear()
            snifferJob?.cancel()
            snifferJob = null
            try {
                snifferServerSocket?.close()
            } catch (_: Exception) {}
            snifferServerSocket = null
            try {
                server?.stop(500, 1500)
            } catch (_: Exception) {}
            server = null
            StunLogger.i(TAG, "Stun MCP Server stopped.")
        }
    }

    fun restart(context: Context, port: Int = SettingsManager.getMcpServerPort(context)) {
        stop()
        start(context, port)
    }

    /**
     * Broadcast an MCP notification (e.g. resource update or message) to all active SSE subscribers.
     */
    fun broadcastNotification(method: String, params: JsonObject) {
        if (legacySseSessions.isEmpty() && streamableSessions.values.none { it.streamOpen.get() }) return
        val notifObj = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("method", method)
            add("params", params)
        }
        val json = gson.toJson(notifObj)
        scope.launch {
            legacySseSessions.values.forEach { channel ->
                try {
                    channel.send(json)
                } catch (_: Exception) {}
            }
            streamableSessions.values.filter { it.streamOpen.get() }.forEach { session ->
                try {
                    session.eventChannel.send(json)
                } catch (_: Exception) {}
            }
        }
    }

    private fun startStateObserver(context: Context) {
        stateObserverJob?.cancel()
        stateObserverJob = scope.launch {
            var lastState: VpnState? = null
            while (isServerRunning.get()) {
                val currentState = StunRepository.vpnState.value ?: VpnState.DISCONNECTED
                if (lastState != null && lastState != currentState) {
                    // Broadcast resource change
                    val resParams = JsonObject().apply {
                        addProperty("uri", "stun://status")
                    }
                    broadcastNotification("notifications/resources/updated", resParams)

                    val msgParams = JsonObject().apply {
                        addProperty("level", "info")
                        addProperty("data", "VPN state changed: $lastState -> $currentState")
                    }
                    broadcastNotification("notifications/message", msgParams)
                }
                lastState = currentState
                delay(1000L)
            }
        }
    }

    // =========================================================================
    // MCP Streamable HTTP transport
    // =========================================================================

    private fun validateOrigin(call: ApplicationCall): Boolean {
        val origin = call.request.header("Origin") ?: return true
        val requestAuthority = call.request.header("Host") ?: return false
        val originAuthority = try {
            URI(origin).rawAuthority
        } catch (_: Exception) {
            null
        }
        return originAuthority != null && originAuthority.equals(requestAuthority, ignoreCase = true)
    }

    private fun negotiateProtocolVersion(params: JsonObject): String {
        val requested = params.get("protocolVersion")?.takeIf { it.isJsonPrimitive }?.asString
        return requested?.takeIf { it in SUPPORTED_PROTOCOL_VERSIONS } ?: LATEST_PROTOCOL_VERSION
    }

    private fun purgeExpiredStreamableSessions() {
        val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        streamableSessions.entries.removeIf { (_, session) ->
            val expired = !session.streamOpen.get() && session.lastAccessAt < cutoff
            if (expired) session.eventChannel.close()
            expired
        }
    }

    private suspend fun respondMcpHttpError(
        call: ApplicationCall,
        status: HttpStatusCode,
        code: Int,
        message: String
    ) {
        call.respondText(
            buildErrorResponse(null, code, message),
            ContentType.Application.Json,
            status
        )
    }

    private suspend fun handleStreamablePost(call: ApplicationCall, context: Context) {
        if (!validateOrigin(call)) {
            respondMcpHttpError(call, HttpStatusCode.Forbidden, -32000, "Invalid Origin header")
            return
        }
        if (!validateAuth(call, context)) {
            respondUnauthorized(call, context)
            return
        }

        purgeExpiredStreamableSessions()
        val rawBody = try {
            call.receiveText()
        } catch (_: Exception) {
            respondMcpHttpError(call, HttpStatusCode.BadRequest, -32700, "Unable to read JSON-RPC request")
            return
        }
        val request = try {
            JsonParser.parseString(rawBody).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) {
            null
        }
        if (request == null) {
            respondMcpHttpError(call, HttpStatusCode.BadRequest, -32700, "Expected one JSON-RPC object")
            return
        }

        val method = request.get("method")?.takeIf { it.isJsonPrimitive }?.asString
        val id = request.get("id")
        if (method == "initialize") {
            val params = request.get("params")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
            val protocolVersion = negotiateProtocolVersion(params)
            val sessionId = UUID.randomUUID().toString()
            streamableSessions[sessionId] = StreamableSession(protocolVersion)
            call.response.headers.append(MCP_SESSION_ID_HEADER, sessionId)
            call.response.headers.append("Cache-Control", "no-store")
            val response = handleJsonRpcRequest(context, rawBody, protocolVersion)
            call.respondText(response, ContentType.Application.Json)
            StunLogger.i(TAG, "MCP Streamable HTTP session initialized: $sessionId ($protocolVersion)")
            return
        }

        val sessionId = call.request.header(MCP_SESSION_ID_HEADER)
        if (sessionId.isNullOrBlank()) {
            respondMcpHttpError(call, HttpStatusCode.BadRequest, -32001, "Missing $MCP_SESSION_ID_HEADER header")
            return
        }
        val session = streamableSessions[sessionId]
        if (session == null) {
            respondMcpHttpError(call, HttpStatusCode.NotFound, -32001, "Unknown or expired MCP session")
            return
        }
        val requestProtocolVersion = call.request.header(MCP_PROTOCOL_VERSION_HEADER)
        if (requestProtocolVersion != null && requestProtocolVersion != session.protocolVersion) {
            respondMcpHttpError(
                call,
                HttpStatusCode.BadRequest,
                -32600,
                "Protocol version does not match the initialized session"
            )
            return
        }
        session.lastAccessAt = System.currentTimeMillis()

        // JSON-RPC notifications and client responses are acknowledged without a body.
        if (method == null || id == null || id.isJsonNull) {
            if (method != null) handleJsonRpcRequest(context, rawBody, session.protocolVersion)
            call.respondText("", status = HttpStatusCode.Accepted)
            return
        }

        val response = handleJsonRpcRequest(context, rawBody, session.protocolVersion)
        call.respondText(response, ContentType.Application.Json)
    }

    private suspend fun handleStreamableGet(call: ApplicationCall, context: Context) {
        if (!validateOrigin(call)) {
            respondMcpHttpError(call, HttpStatusCode.Forbidden, -32000, "Invalid Origin header")
            return
        }
        if (!validateAuth(call, context)) {
            respondUnauthorized(call, context)
            return
        }
        if (!call.request.header("Accept").orEmpty().contains("text/event-stream", ignoreCase = true)) {
            call.respondText("SSE requires Accept: text/event-stream", status = HttpStatusCode.MethodNotAllowed)
            return
        }

        val sessionId = call.request.header(MCP_SESSION_ID_HEADER)
        if (sessionId.isNullOrBlank()) {
            respondMcpHttpError(call, HttpStatusCode.BadRequest, -32001, "Missing $MCP_SESSION_ID_HEADER header")
            return
        }
        val session = streamableSessions[sessionId]
        if (session == null) {
            respondMcpHttpError(call, HttpStatusCode.NotFound, -32001, "Unknown or expired MCP session")
            return
        }
        if (!session.streamOpen.compareAndSet(false, true)) {
            respondMcpHttpError(call, HttpStatusCode.Conflict, -32002, "An SSE stream is already open for this session")
            return
        }

        session.lastAccessAt = System.currentTimeMillis()
        try {
            call.response.headers.append("Cache-Control", "no-cache")
            call.response.headers.append("Connection", "keep-alive")
            call.respondBytesWriter(contentType = ContentType.parse("text/event-stream; charset=UTF-8")) {
                for (message in session.eventChannel) {
                    writeStringUtf8("event: message\ndata: $message\n\n")
                    flush()
                    session.lastAccessAt = System.currentTimeMillis()
                }
            }
        } finally {
            session.streamOpen.set(false)
            session.lastAccessAt = System.currentTimeMillis()
            StunLogger.i(TAG, "MCP Streamable HTTP SSE stream closed: $sessionId")
        }
    }

    private suspend fun handleStreamableDelete(call: ApplicationCall, context: Context) {
        if (!validateOrigin(call)) {
            respondMcpHttpError(call, HttpStatusCode.Forbidden, -32000, "Invalid Origin header")
            return
        }
        if (!validateAuth(call, context)) {
            respondUnauthorized(call, context)
            return
        }
        val sessionId = call.request.header(MCP_SESSION_ID_HEADER)
        if (sessionId.isNullOrBlank()) {
            respondMcpHttpError(call, HttpStatusCode.BadRequest, -32001, "Missing $MCP_SESSION_ID_HEADER header")
            return
        }
        val session = streamableSessions.remove(sessionId)
        if (session == null) {
            respondMcpHttpError(call, HttpStatusCode.NotFound, -32001, "Unknown or expired MCP session")
            return
        }
        session.eventChannel.close()
        call.respondText("", status = HttpStatusCode.NoContent)
        StunLogger.i(TAG, "MCP Streamable HTTP session terminated: $sessionId")
    }

    // =========================================================================
    // JSON-RPC 2.0 Request Dispatcher
    // =========================================================================

    private suspend fun handleJsonRpcRequest(
        context: Context,
        jsonStr: String,
        protocolVersion: String = LATEST_PROTOCOL_VERSION
    ): String {
        return withContext(Dispatchers.IO) {
            try {
                val element = JsonParser.parseString(jsonStr)
                if (!element.isJsonObject) return@withContext ""
                val req = element.asJsonObject

                val id = req.get("id")
                val method = req.get("method")?.asString ?: ""
                val params = req.getAsJsonObject("params") ?: JsonObject()

                val result: JsonElement = when (method) {
                    "initialize" -> handleInitialize(params, protocolVersion)
                    "notifications/initialized" -> return@withContext ""
                    "ping" -> JsonObject()
                    "tools/list" -> handleToolsList()
                    "tools/call" -> handleToolsCall(context, params)
                    "resources/list" -> handleResourcesList()
                    "resources/read" -> handleResourcesRead(context, params)
                    "prompts/list" -> handlePromptsList()
                    "prompts/get" -> handlePromptsGet(params)
                    else -> {
                        if (id == null || id.isJsonNull) return@withContext ""
                        return@withContext buildErrorResponse(id, -32601, "Method not found: $method")
                    }
                }

                buildSuccessResponse(id, result)
            } catch (e: Exception) {
                StunLogger.e(TAG, "JSON-RPC error processing: $jsonStr", e)
                buildErrorResponse(null, -32603, e.message ?: "Internal error")
            }
        }
    }

    private fun buildSuccessResponse(id: JsonElement?, result: JsonElement): String {
        val obj = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            if (id != null) add("id", id) else add("id", JsonNull.INSTANCE)
            add("result", result)
        }
        return gson.toJson(obj)
    }

    private fun buildErrorResponse(id: JsonElement?, code: Int, message: String): String {
        val obj = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            if (id != null) add("id", id) else add("id", JsonNull.INSTANCE)
            add("error", JsonObject().apply {
                addProperty("code", code)
                addProperty("message", message)
            })
        }
        return gson.toJson(obj)
    }

    // =========================================================================
    // MCP Protocol Methods
    // =========================================================================

    private fun handleInitialize(params: JsonObject, protocolVersion: String): JsonObject {
        return JsonObject().apply {
            addProperty("protocolVersion", protocolVersion)
            add("serverInfo", JsonObject().apply {
                addProperty("name", SERVER_NAME)
                addProperty("version", SERVER_VERSION)
            })
            add("capabilities", JsonObject().apply {
                add("tools", JsonObject().apply { addProperty("listChanged", true) })
                add("resources", JsonObject().apply {
                    addProperty("subscribe", true)
                    addProperty("listChanged", true)
                })
                add("prompts", JsonObject().apply { addProperty("listChanged", false) })
                add("logging", JsonObject())
            })
        }
    }

    // ─── Complete Tools Definition (18 Tools) ───

    private fun handleToolsList(): JsonObject {
        val toolsArray = JsonArray()

        fun addTool(name: String, desc: String, props: JsonObject, required: List<String> = emptyList()) {
            toolsArray.add(JsonObject().apply {
                addProperty("name", name)
                addProperty("description", desc)
                add("inputSchema", JsonObject().apply {
                    addProperty("type", "object")
                    add("properties", props)
                    if (required.isNotEmpty()) {
                        val reqArr = JsonArray()
                        required.forEach { reqArr.add(it) }
                        add("required", reqArr)
                    }
                })
            })
        }

        // 1. get_vpn_status
        addTool("get_vpn_status", "Query live VPN connection state, active node name, protocol, server IP, public IP, uplink/downlink speed, and total traffic bytes.", JsonObject())

        // 2. start_vpn
        addTool("start_vpn", "Start VPN connection. Optionally pass profileId or profileName to connect to a specific node.", JsonObject().apply {
            add("profileId", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Target Profile ID") })
            add("profileName", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Target Profile Name") })
        })

        // 3. stop_vpn
        addTool("stop_vpn", "Stop and disconnect the active VPN tunnel.", JsonObject())

        // 4. restart_vpn
        addTool("restart_vpn", "Restart and reconnect the VPN tunnel.", JsonObject())

        // 5. list_profiles
        addTool("list_profiles", "List all configured proxy nodes with summary details (ID, name, tunnel type, server, and selection status).", JsonObject())

        // 6. get_profile_detail
        addTool("get_profile_detail", "Get complete configuration parameters for a specific profile node.", JsonObject().apply {
            add("profileId", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Profile ID") })
            add("profileName", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Profile Name") })
        })

        // 7. create_profile
        addTool("create_profile", "Create and save a new proxy node configuration with specific fields.", JsonObject().apply {
            add("name", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Node name") })
            add("tunnelType", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Tunnel type: raw, websocket, h2, grpc, h3, webtransport, masque, quic, xhttp, http, kcptun, dns_custom, udp_custom, icmp_custom") })
            add("sshAddr", JsonObject().apply { addProperty("type", "string"); addProperty("description", "SSH server address (e.g. 1.2.3.4:22)") })
            add("user", JsonObject().apply { addProperty("type", "string"); addProperty("description", "SSH username") })
            add("pass", JsonObject().apply { addProperty("type", "string"); addProperty("description", "SSH password") })
            add("proxyAddr", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Proxy or CDN server address (e.g. cdn.example.com:443)") })
            add("serverName", JsonObject().apply { addProperty("type", "string"); addProperty("description", "TLS SNI Server Name") })
            add("customHost", JsonObject().apply { addProperty("type", "string"); addProperty("description", "HTTP Host Header / WebSocket Host") })
            add("customPath", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Path used directly by path-based tunnels; for MASQUE, empty keeps the SDK default and non-empty overrides it") })
            add("enableCustomPath", JsonObject().apply { addProperty("type", "boolean"); addProperty("description", "MASQUE only: explicitly enable or disable overriding its default path") })
            add("alpn", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Comma-separated ALPN values") })
            add("proxyAuthRequired", JsonObject().apply { addProperty("type", "boolean"); addProperty("description", "Require proxy authentication") })
            add("proxyAuthToken", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Proxy authentication token") })
            add("heartbeatIntervalMs", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("description", "h2-family heartbeat in milliseconds; 0 uses the SDK default") })
            add("xhttpChunkSizeKB", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("description", "XHTTP upstream chunk size in KB; 0 uses the SDK default") })
            add("udpCustomPsk", JsonObject().apply { addProperty("type", "string"); addProperty("description", "UDP Custom pre-shared key") })
            add("udpCustomPublicKey", JsonObject().apply { addProperty("type", "string"); addProperty("description", "UDP Custom Noise server public key") })
            add("udpCustomMagic", JsonObject().apply { addProperty("type", "string"); addProperty("description", "UDP Custom 4-byte magic, for example UDPC") })
            add("udpCustomPaths", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("description", "UDP Custom remote path count; 0 uses 32") })
            add("udpCustomSockets", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("description", "UDP Custom local socket count; 0 uses 1") })
            add("udpCustomSendWindow", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("description", "UDP Custom in-flight frame window; 0 uses 256") })
            add("dnsTunnelDomain", JsonObject().apply { addProperty("type", "string"); addProperty("description", "DNS tunnel domain") })
            add("dnsTunnelServers", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Comma-separated DNS tunnel resolvers") })
            add("dnsTunnelType", JsonObject().apply { addProperty("type", "string"); addProperty("description", "DNS record type") })
            add("dnsTunnelPublicKey", JsonObject().apply { addProperty("type", "string"); addProperty("description", "DNS tunnel Noise server public key") })
            add("dnsTunnelEDNS0", JsonObject().apply { addProperty("type", "boolean"); addProperty("description", "Enable 1232-byte EDNS0 answers") })
            add("dnsTunnelPsk", JsonObject().apply { addProperty("type", "string"); addProperty("description", "DNS tunnel PSK (blank=anonymous, must match server)") })
            add("dnsTunnelMarker", JsonObject().apply { addProperty("type", "string"); addProperty("description", "DNS tunnel custom marker (blank=default, must match server)") })
            add("tunnelTlsEnabled", JsonObject().apply { addProperty("type", "boolean"); addProperty("description", "Enable TLS for toggle-capable types (raw/websocket/h2/grpc/xhttp)") })
            add("authType", JsonObject().apply { addProperty("type", "string"); addProperty("description", "SSH auth type: password (default) or privatekey") })
            add("privateKey", JsonObject().apply { addProperty("type", "string"); addProperty("description", "PEM private key (when authType=privatekey)") })
            add("keyPass", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Private key passphrase (optional)") })
            add("proxyAuthUser", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Proxy basic auth username (websocket/http)") })
            add("proxyAuthPass", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Proxy basic auth password (websocket/http)") })
            add("noisePublicKey", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Shared Noise static public key (dns/udp/icmp)") })
            add("icmpCustomPsk", JsonObject().apply { addProperty("type", "string"); addProperty("description", "ICMP Custom PSK (required; falls back to SSH password if blank)") })
            add("icmpCustomMagic", JsonObject().apply { addProperty("type", "string"); addProperty("description", "ICMP Custom 4-byte magic hex (e.g. 49434D31) or 0x-prefixed; blank=default") })
            add("icmpCustomPublicKey", JsonObject().apply { addProperty("type", "string"); addProperty("description", "ICMP Custom Noise static public key (hex64 or base64)") })
            add("icmpCustomMtuMode", JsonObject().apply { addProperty("type", "string"); addProperty("description", "ICMP MTU mode: probe (default), auto, fixed") })
            add("icmpCustomMaxPayload", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("description", "ICMP max payload; 0 = SDK default") })
            add("icmpCustomPaceMS", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("description", "ICMP outbound pacing interval ms; 0 = SDK default") })
            add("icmpCustomIdRange", JsonObject().apply { addProperty("type", "string"); addProperty("description", "ICMP echo identifier pool, e.g. 1000-1999") })
            add("udpCustomMaxPkt", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("description", "UDP Custom max payload bytes; 0 = SDK default 1450") })
            add("udpCustomMtuProbe", JsonObject().apply { addProperty("type", "string"); addProperty("description", "UDP Custom path MTU probing: auto (default), on, off") })
            add("paddingMinBytes", JsonObject().apply { addProperty("type", "integer"); addProperty("description", "h2tunnel min padding bytes; 0 = default 1420, negative = off") })
            add("masqueAlpn", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Masque-only ALPN choice: auto (default), h3 or h2") })
            add("kcpPassword", JsonObject().apply { addProperty("type", "string"); addProperty("description", "KCP (kcptun) password (required for kcptun type)") })
            add("kcpCrypt", JsonObject().apply { addProperty("type", "string"); addProperty("description", "KCP encryption cipher (e.g. aes-128, aes-256, sm4, none)") })
            add("kcpMode", JsonObject().apply { addProperty("type", "string"); addProperty("description", "KCP nodelay preset: normal, fast (default), fast2, fast3") })
            add("kcpSndWnd", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("description", "KCP send window; 0 uses 128") })
            add("kcpRcvWnd", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("description", "KCP receive window; 0 uses 512") })
            add("kcpMtu", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("description", "KCP MTU; 0 uses 1350") })
            add("kcpNoComp", JsonObject().apply { addProperty("type", "boolean"); addProperty("description", "Disable KCP Snappy session compression") })
            add("kcpSmuxVer", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("description", "SMUX version (1 or 2); 0 uses 2") })
            add("kcpKeepAlive", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("description", "KCP keepalive seconds; 0 uses 10") })
            add("kcpDataShards", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("description", "KCP FEC data shards; 0 uses 10") })
            add("kcpParityShards", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("description", "KCP FEC parity shards; 0 uses 3") })
        }, listOf("name", "sshAddr"))

        // 8. update_profile
        addTool("update_profile", "Update parameters of an existing profile node.", JsonObject().apply {
            add("profileId", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Profile ID to update") })
            add("name", JsonObject().apply { addProperty("type", "string"); addProperty("description", "New node name") })
            add("tunnelType", JsonObject().apply { addProperty("type", "string"); addProperty("description", "New lowercase tunnel type ID") })
            add("sshAddr", JsonObject().apply { addProperty("type", "string"); addProperty("description", "New SSH server address") })
            add("user", JsonObject().apply { addProperty("type", "string"); addProperty("description", "New SSH username") })
            add("pass", JsonObject().apply { addProperty("type", "string"); addProperty("description", "New SSH password") })
            add("proxyAddr", JsonObject().apply { addProperty("type", "string"); addProperty("description", "New proxy address") })
            add("serverName", JsonObject().apply { addProperty("type", "string"); addProperty("description", "New TLS SNI") })
            add("customHost", JsonObject().apply { addProperty("type", "string"); addProperty("description", "New Host header") })
            add("customPath", JsonObject().apply { addProperty("type", "string"); addProperty("description", "New path; for MASQUE, empty keeps the SDK default and non-empty overrides it") })
            add("enableCustomPath", JsonObject().apply { addProperty("type", "boolean"); addProperty("description", "MASQUE only: explicitly enable or disable overriding its default path") })
            add("alpn", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Comma-separated ALPN values") })
            add("proxyAuthRequired", JsonObject().apply { addProperty("type", "boolean"); addProperty("description", "Require proxy authentication") })
            add("proxyAuthToken", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Proxy authentication token") })
            add("heartbeatIntervalMs", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
            add("xhttpChunkSizeKB", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
            add("udpCustomPsk", JsonObject().apply { addProperty("type", "string") })
            add("udpCustomPublicKey", JsonObject().apply { addProperty("type", "string") })
            add("udpCustomMagic", JsonObject().apply { addProperty("type", "string") })
            add("udpCustomPaths", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
            add("udpCustomSockets", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
            add("udpCustomSendWindow", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
            add("dnsTunnelDomain", JsonObject().apply { addProperty("type", "string") })
            add("dnsTunnelServers", JsonObject().apply { addProperty("type", "string") })
            add("dnsTunnelType", JsonObject().apply { addProperty("type", "string") })
            add("dnsTunnelPublicKey", JsonObject().apply { addProperty("type", "string") })
            add("dnsTunnelEDNS0", JsonObject().apply { addProperty("type", "boolean") })
            add("dnsTunnelPsk", JsonObject().apply { addProperty("type", "string") })
            add("dnsTunnelMarker", JsonObject().apply { addProperty("type", "string") })
            add("tunnelTlsEnabled", JsonObject().apply { addProperty("type", "boolean") })
            add("authType", JsonObject().apply { addProperty("type", "string") })
            add("privateKey", JsonObject().apply { addProperty("type", "string") })
            add("keyPass", JsonObject().apply { addProperty("type", "string") })
            add("proxyAuthUser", JsonObject().apply { addProperty("type", "string") })
            add("proxyAuthPass", JsonObject().apply { addProperty("type", "string") })
            add("noisePublicKey", JsonObject().apply { addProperty("type", "string") })
            add("icmpCustomPsk", JsonObject().apply { addProperty("type", "string") })
            add("icmpCustomMagic", JsonObject().apply { addProperty("type", "string") })
            add("icmpCustomPublicKey", JsonObject().apply { addProperty("type", "string") })
            add("icmpCustomMtuMode", JsonObject().apply { addProperty("type", "string") })
            add("icmpCustomMaxPayload", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
            add("icmpCustomPaceMS", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
            add("icmpCustomIdRange", JsonObject().apply { addProperty("type", "string") })
            add("udpCustomMaxPkt", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
            add("udpCustomMtuProbe", JsonObject().apply { addProperty("type", "string") })
            add("paddingMinBytes", JsonObject().apply { addProperty("type", "integer") })
            add("masqueAlpn", JsonObject().apply { addProperty("type", "string") })
            add("kcpPassword", JsonObject().apply { addProperty("type", "string") })
            add("kcpCrypt", JsonObject().apply { addProperty("type", "string") })
            add("kcpMode", JsonObject().apply { addProperty("type", "string") })
            add("kcpSndWnd", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
            add("kcpRcvWnd", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
            add("kcpMtu", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
            add("kcpNoComp", JsonObject().apply { addProperty("type", "boolean") })
            add("kcpSmuxVer", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
            add("kcpKeepAlive", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
            add("kcpDataShards", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
            add("kcpParityShards", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
        }, listOf("profileId"))

        // 9. delete_profile
        addTool("delete_profile", "Delete a profile node by ID or name.", JsonObject().apply {
            add("profileId", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Profile ID to delete") })
            add("profileName", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Profile Name to delete") })
        })

        // 10. select_profile
        addTool("select_profile", "Select active profile node by profileId or profileName with auto-reconnection.", JsonObject().apply {
            add("profileId", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Target Profile ID") })
            add("profileName", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Target Profile Name") })
        })

        // 11. test_node_latency
        addTool("test_node_latency", "Execute node latency (ping) test on all nodes or a specific node. Latency is measured via the SSH node tunnel handshake, matching the bottom-bar latency source.", JsonObject().apply {
            add("profileId", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Optional profile ID to test") })
        })

        // 12. get_app_filter_list
        addTool("get_app_filter_list", "Get installed apps on device and their split-tunneling proxy/bypass status.", JsonObject())

        // 13. set_app_filter
        addTool("set_app_filter", "Configure split tunneling mode (0: Bypass selected, 1: Proxy only selected) and packages.", JsonObject().apply {
            add("mode", JsonObject().apply { addProperty("type", "integer"); addProperty("description", "0 = Disallow / Bypass selected, 1 = Allow / Proxy only selected") })
            add("packages", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Comma-separated package names (e.g. org.telegram.messenger,com.android.chrome)") })
        }, listOf("mode", "packages"))

        // 14. update_geodata
        addTool("update_geodata", "Trigger immediate download and update of geosite.dat and geoip.dat rule databases.", JsonObject())

        // 15. get_settings
        addTool("get_settings", "Get all global application settings (DNS, UDPGW, Routing rules, Service Mode).", JsonObject())

        // 16. set_settings
        addTool("set_settings", "Update global application settings.", JsonObject().apply {
            add("remoteDns", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Remote DNS DoH URL (e.g. doh://8.8.8.8/dns-query)") })
            add("localDns", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Local DNS DoH URL (e.g. doh://223.5.5.5/dns-query)") })
            add("serviceMode", JsonObject().apply { addProperty("type", "integer"); addProperty("description", "0 = VPN mode, 1 = Root TProxy mode") })
            add("logLevel", JsonObject().apply { addProperty("type", "string"); addProperty("description", "DEBUG, INFO, WARN, ERROR") })
            add("geositeDirect", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Geosite direct routing tags (e.g. cn,apple)") })
            add("geoipDirect", JsonObject().apply { addProperty("type", "string"); addProperty("description", "GeoIP direct routing tags (e.g. cn,private)") })
            add("mcpServerPort", JsonObject().apply { addProperty("type", "integer"); addProperty("description", "MCP Server listening port (e.g. 37180)") })
        })

        // 17. get_logs
        addTool("get_logs", "Retrieve recent system and Go tunnel logs with optional level filter and line count.", JsonObject().apply {
            add("limit", JsonObject().apply { addProperty("type", "integer"); addProperty("description", "Max lines (default: 50, max: 500)") })
            add("level", JsonObject().apply { addProperty("type", "string"); addProperty("description", "ALL, INFO, WARN, ERROR, DEBUG") })
        })

        // 18. get_device_info
        addTool("get_device_info", "Get hardware model, battery level, Android version, app & core versions, and local IP addresses.", JsonObject())

        // ── 2026-09-12: WebDAV / 订阅 / 导入导出 ──

        // 19. get_webdav_config
        addTool("get_webdav_config", "Get WebDAV cloud backup configuration (URL, account, auto-backup switch, interval, last backup time). Never echoes pass/pin.", JsonObject())

        // 20. set_webdav_config
        addTool("set_webdav_config", "Update WebDAV cloud backup configuration. Blank pass/pin means keep existing values.", JsonObject().apply {
            add("url", JsonObject().apply { addProperty("type", "string"); addProperty("description", "WebDAV server URL (e.g. https://dav.jianguoyun.com/dav/)") })
            add("user", JsonObject().apply { addProperty("type", "string"); addProperty("description", "WebDAV account username") })
            add("pass", JsonObject().apply { addProperty("type", "string"); addProperty("description", "WebDAV password / app password") })
            add("pin", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Backup PIN for encryption (letters+digits, ≥4)") })
            add("auto", JsonObject().apply { addProperty("type", "boolean"); addProperty("description", "Enable daily auto-backup") })
            add("intervalHours", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 1); addProperty("maximum", 720); addProperty("description", "Auto-backup interval in hours (1–720)") })
        })

        // 21. list_backups
        addTool("list_backups", "List available WebDAV backup directories on the server, newest first (Stun/<timestamp>/ format).", JsonObject())

        // 22. backup_now
        addTool("backup_now", "Trigger an immediate WebDAV backup (nodes + settings). Requires a fully configured WebDAV.", JsonObject())

        // 23. restore_backup
        addTool("restore_backup", "Restore from a specific WebDAV backup directory. Nodes are merged by ID; global settings are overwritten (except the backup PIN). Requires the dir name from list_backups.", JsonObject().apply {
            add("dir", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Backup directory name (e.g. 20260912-063005)") })
        }, listOf("dir"))

        // 24. list_subscriptions
        addTool("list_subscriptions", "List all configured subscription links (URL + optional PIN) and last sync time.", JsonObject())

        // 25. sync_subscriptions
        addTool("sync_subscriptions", "Fetch and merge all configured subscriptions. Nodes are merged by ID; returns per-subscription results.", JsonObject())

        // 26. import_profiles
        addTool("import_profiles", "Import profiles from a PIN-encrypted payload (stun:// share format) or plaintext JSON array. Nodes are merged by ID.", JsonObject().apply {
            add("content", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Encrypted payload (base64 stun:// format) or plaintext JSON profile array") })
            add("pin", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Decryption PIN (required for encrypted content)") })
        }, listOf("content"))

        // 27. export_profiles
        addTool("export_profiles", "Export all profiles as a PIN-encrypted share payload (same format as stun:// share URIs).", JsonObject().apply {
            add("pin", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Encryption PIN (letters+digits, ≥4)") })
        }, listOf("pin"))

        return JsonObject().apply { add("tools", toolsArray) }
    }

    // ─── Log query helpers (get_logs) ───

    /**
     * 判断设备磁盘上是否存在持久化日志文件（app.log / old.1 / old.2）。
     * 仅当 StunLogger 已 init 并成功创建文件时才为 true。
     */
    private fun hasDiskLogFiles(context: Context): Boolean {
        val baseFile = File(StunRepository.getAppLogFilePath(context))
        val parent = baseFile.parentFile ?: return false
        val name = baseFile.name
        return baseFile.exists() ||
            File(parent, "$name.old.1").exists() ||
            File(parent, "$name.old.2").exists()
    }

    /**
     * 将一条标准磁盘日志行 ("HH:mm:ss.SSS LEVEL [Tag] Msg") 解析为结构化 JSON。
     * 仅接受以时间戳开头的正式日志行；堆栈跟踪续行（无时间戳）直接跳过，避免污染结果。
     */
    private fun parseDiskLogLine(line: String): JsonObject? {
        val trimmed = line.trimEnd()
        if (trimmed.isEmpty()) return null
        val firstSpace = trimmed.indexOf(' ')
        val timeStr = if (firstSpace > 0) trimmed.substring(0, firstSpace) else ""
        if (!timeStr.matches(Regex("^\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,3})?$"))) return null
        val level = StunLogger.extractLogLevel(trimmed)
        val metaStart = trimmed.indexOf('[')
        val metaEnd = trimmed.indexOf(']')
        var tag = ""
        var message = trimmed
        if (metaStart in 1 until metaEnd) {
            tag = trimmed.substring(metaStart + 1, metaEnd)
            message = trimmed.substring(metaEnd + 1).trim()
        } else {
            message = trimmed.substring(firstSpace + 1).trimStart()
        }
        return JsonObject().apply {
            addProperty("timeStr", timeStr)
            addProperty("level", level)
            addProperty("tag", tag)
            addProperty("message", message)
        }
    }

    /**
     * 从磁盘滚动日志中读取并过滤日志。
     * 按时间顺序拼接 old.2 -> old.1 -> app.log，再取最后 [limit] 条匹配行（最新）。
     * 任何单文件损坏都被静默跳过，绝不抛异常。
     */
    private fun readLogsFromDisk(context: Context, limit: Int, levelFilter: String, keyword: String?): List<JsonObject> {
        val baseFile = File(StunRepository.getAppLogFilePath(context))
        val parent = baseFile.parentFile ?: return emptyList()
        val name = baseFile.name
        // 时间顺序：最旧 -> 最新
        val files = listOf(
            File(parent, "$name.old.2"),
            File(parent, "$name.old.1"),
            baseFile
        ).filter { it.exists() && it.length() > 0L }

        val kw = keyword?.takeIf { it.isNotBlank() }?.lowercase()
        val matched = mutableListOf<JsonObject>()
        for (file in files) {
            try {
                    file.useLines(Charsets.UTF_8) { lines ->
                        for (raw in lines) {
                            val obj = parseDiskLogLine(raw) ?: continue
                            if (levelFilter != "ALL" &&
                                !obj.get("level")?.asString.equals(levelFilter, ignoreCase = true)
                            ) continue
                            if (kw != null) {
                                val hay = (obj.get("message")?.asString ?: "") + " " + (obj.get("tag")?.asString ?: "")
                                if (!hay.lowercase().contains(kw)) continue
                            }
                            matched.add(obj)
                        }
                    }
            } catch (_: Exception) { /* 跳过损坏/不可读的文件 */ }
        }
        return if (matched.size > limit) matched.takeLast(limit) else matched
    }

    // ─── Tools Call Execution ───

    private suspend fun handleToolsCall(context: Context, params: JsonObject): JsonObject {
        val toolName = params.get("name")?.asString ?: ""
        val args = params.getAsJsonObject("arguments") ?: JsonObject()

        val textResult = when (toolName) {
            "get_vpn_status" -> {
                val status = buildStatusJson(context)
                gson.toJson(status)
            }

            "start_vpn" -> {
                val profileId = args.get("profileId")?.asString
                val profileName = args.get("profileName")?.asString

                val targetId = if (!profileId.isNullOrBlank()) {
                    profileId
                } else if (!profileName.isNullOrBlank()) {
                    val all = ProfileManager.getProfiles(context)
                    all.firstOrNull { it.name.equals(profileName, ignoreCase = true) }?.id
                } else {
                    null
                }

                if (targetId != null) {
                    SettingsManager.setSelectedProfileId(context, targetId)
                }

                startVpn(context, targetId)
                delay(500L)
                val status = buildStatusJson(context)
                "VPN start command issued. Status: ${status.get("vpnState")?.asString}, active profile: ${status.get("currentProfileName")?.asString}"
            }

            "stop_vpn" -> {
                stopVpn(context)
                delay(500L)
                val status = buildStatusJson(context)
                "VPN stop command issued. Status: ${status.get("vpnState")?.asString}"
            }

            "restart_vpn" -> {
                stopVpn(context)
                delay(800L)
                startVpn(context)
                delay(500L)
                val status = buildStatusJson(context)
                "VPN restarted. Status: ${status.get("vpnState")?.asString}"
            }

            "list_profiles" -> {
                val profiles = ProfileManager.getProfiles(context)
                val selectedId = SettingsManager.getSelectedProfileId(context)
                val arr = JsonArray()
                profiles.forEach { p ->
                    arr.add(JsonObject().apply {
                        addProperty("id", p.id)
                        addProperty("name", p.name)
                        addProperty("tunnelType", p.tunnelType)
                        addProperty("sshAddr", p.sshAddr)
                        addProperty("proxyAddr", p.proxyAddr)
                        addProperty("isSelected", p.id == selectedId)
                    })
                }
                gson.toJson(arr)
            }

            "get_profile_detail" -> {
                val profileId = args.get("profileId")?.asString
                val profileName = args.get("profileName")?.asString
                val all = ProfileManager.getProfiles(context)

                val matched = if (!profileId.isNullOrBlank()) {
                    all.firstOrNull { it.id == profileId }
                } else if (!profileName.isNullOrBlank()) {
                    all.firstOrNull { it.name.equals(profileName, ignoreCase = true) }
                } else {
                    ProfileManager.getSelectedProfile(context)
                }

                if (matched != null) {
                    gson.toJson(matched)
                } else {
                    "Error: Profile not found."
                }
            }

            "create_profile" -> {
                val name = args.get("name")?.asString ?: "New Profile"
                val sshAddr = args.get("sshAddr")?.asString ?: "127.0.0.1:22"
                // 旧别名（tls/base/ws/wss/h2c/grpcc/xhttpc/wt）已随 myssh 4735512 删除
                val tunnelType = args.get("tunnelType")?.asString?.lowercase() ?: Profile.TUNNEL_TYPE_RAW
                val user = args.get("user")?.asString ?: "root"
                val pass = args.get("pass")?.asString ?: ""
                val proxyAddr = args.get("proxyAddr")?.asString ?: ""
                val serverName = args.get("serverName")?.asString ?: ""
                val customHost = args.get("customHost")?.asString ?: ""
                val customPath = args.get("customPath")?.asString ?: ""
                val enableCustomPath = if (tunnelType == Profile.TUNNEL_TYPE_MASQUE) {
                    args.get("enableCustomPath")?.asBoolean ?: customPath.isNotBlank()
                } else {
                    false
                }
                val alpn = args.get("alpn")?.asString ?: "h2,http/1.1"
                val proxyAuthRequired = args.get("proxyAuthRequired")?.asBoolean ?: false
                val proxyAuthToken = args.get("proxyAuthToken")?.asString ?: ""
                val heartbeatIntervalMs = args.get("heartbeatIntervalMs")?.asInt ?: 0
                val xhttpChunkSizeKB = args.get("xhttpChunkSizeKB")?.asInt ?: 0
                val udpCustomPsk = args.get("udpCustomPsk")?.asString ?: ""
                val udpCustomPublicKey = args.get("udpCustomPublicKey")?.asString ?: ""
                val udpCustomMagic = args.get("udpCustomMagic")?.asString ?: "UDPC"
                val udpCustomPaths = args.get("udpCustomPaths")?.asInt ?: 0
                val udpCustomSockets = args.get("udpCustomSockets")?.asInt ?: 0
                val udpCustomSendWindow = args.get("udpCustomSendWindow")?.asInt ?: 0
                val dnsTunnelDomain = args.get("dnsTunnelDomain")?.asString ?: ""
                val dnsTunnelServers = args.get("dnsTunnelServers")?.asString ?: ""
                val dnsTunnelType = args.get("dnsTunnelType")?.asString ?: "txt"
                val dnsTunnelPublicKey = args.get("dnsTunnelPublicKey")?.asString ?: ""
                val dnsTunnelEDNS0 = args.get("dnsTunnelEDNS0")?.asBoolean ?: false
                val dnsTunnelPsk = args.get("dnsTunnelPsk")?.asString ?: ""
                val dnsTunnelMarker = args.get("dnsTunnelMarker")?.asString ?: ""
                val tunnelTlsEnabled = args.get("tunnelTlsEnabled")?.asBoolean ?: false
                val authType = args.get("authType")?.asString ?: Profile.AUTH_TYPE_PASSWORD
                val privateKey = args.get("privateKey")?.asString ?: ""
                val keyPass = args.get("keyPass")?.asString ?: ""
                val proxyAuthUser = args.get("proxyAuthUser")?.asString ?: ""
                val proxyAuthPass = args.get("proxyAuthPass")?.asString ?: ""
                val noisePublicKey = args.get("noisePublicKey")?.asString ?: ""
                val icmpCustomPsk = args.get("icmpCustomPsk")?.asString ?: ""
                val icmpCustomMagic = args.get("icmpCustomMagic")?.asString ?: ""
                val icmpCustomPublicKey = args.get("icmpCustomPublicKey")?.asString ?: ""
                val icmpCustomMtuMode = args.get("icmpCustomMtuMode")?.asString ?: ""
                val icmpCustomMaxPayload = args.get("icmpCustomMaxPayload")?.asInt ?: 0
                val icmpCustomPaceMS = args.get("icmpCustomPaceMS")?.asInt ?: 0
                val icmpCustomIdRange = args.get("icmpCustomIdRange")?.asString ?: ""
                val kcpPassword = args.get("kcpPassword")?.asString ?: ""
                val kcpCrypt = args.get("kcpCrypt")?.asString ?: "aes-128"
                val kcpMode = args.get("kcpMode")?.asString ?: "fast"
                val kcpSndWnd = args.get("kcpSndWnd")?.asInt ?: 0
                val kcpRcvWnd = args.get("kcpRcvWnd")?.asInt ?: 0
                val kcpMtu = args.get("kcpMtu")?.asInt ?: 0
                val kcpNoComp = args.get("kcpNoComp")?.asBoolean ?: false
                val kcpSmuxVer = args.get("kcpSmuxVer")?.asInt ?: 0
                val kcpKeepAlive = args.get("kcpKeepAlive")?.asInt ?: 0
                val kcpDataShards = args.get("kcpDataShards")?.asInt ?: 0
                val kcpParityShards = args.get("kcpParityShards")?.asInt ?: 0

                val profile = Profile(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    sshAddr = sshAddr,
                    tunnelType = tunnelType,
                    user = user,
                    pass = pass,
                    proxyAddr = proxyAddr,
                    serverName = serverName,
                    customHost = customHost,
                    customPath = customPath,
                    enableCustomPath = enableCustomPath,
                    alpn = alpn,
                    proxyAuthRequired = proxyAuthRequired,
                    proxyAuthToken = proxyAuthToken,
                    heartbeatIntervalMs = heartbeatIntervalMs.coerceAtLeast(0),
                    xhttpChunkSizeKB = xhttpChunkSizeKB.coerceAtLeast(0),
                    udpCustomPsk = udpCustomPsk,
                    udpCustomPublicKey = udpCustomPublicKey,
                    udpCustomMagic = udpCustomMagic,
                    udpCustomPaths = udpCustomPaths.coerceAtLeast(0),
                    udpCustomSockets = udpCustomSockets.coerceAtLeast(0),
                    udpCustomSendWindow = udpCustomSendWindow.coerceAtLeast(0),
                    dnsTunnelDomain = dnsTunnelDomain,
                    dnsTunnelServers = dnsTunnelServers,
                    dnsTunnelType = dnsTunnelType,
                    dnsTunnelPublicKey = dnsTunnelPublicKey,
                    dnsTunnelEDNS0 = dnsTunnelEDNS0,
                    dnsTunnelPsk = dnsTunnelPsk,
                    dnsTunnelMarker = dnsTunnelMarker,
                    tunnelTlsEnabled = tunnelTlsEnabled,
                    authType = authType,
                    privateKey = privateKey,
                    keyPass = keyPass,
                    proxyAuthUser = proxyAuthUser,
                    proxyAuthPass = proxyAuthPass,
                    noisePublicKey = noisePublicKey,
                    icmpCustomPsk = icmpCustomPsk,
                    icmpCustomMagic = icmpCustomMagic,
                    icmpCustomPublicKey = icmpCustomPublicKey,
                    icmpCustomMtuMode = icmpCustomMtuMode,
                    icmpCustomMaxPayload = icmpCustomMaxPayload.coerceAtLeast(0),
                    icmpCustomPaceMS = icmpCustomPaceMS.coerceAtLeast(0),
                    icmpCustomIdRange = icmpCustomIdRange,
                    kcpPassword = kcpPassword,
                    kcpCrypt = kcpCrypt,
                    kcpMode = kcpMode,
                    kcpSndWnd = kcpSndWnd.coerceAtLeast(0),
                    kcpRcvWnd = kcpRcvWnd.coerceAtLeast(0),
                    kcpMtu = kcpMtu.coerceAtLeast(0),
                    kcpNoComp = kcpNoComp,
                    kcpSmuxVer = kcpSmuxVer.coerceAtLeast(0),
                    kcpKeepAlive = kcpKeepAlive.coerceAtLeast(0),
                    kcpDataShards = kcpDataShards.coerceAtLeast(0),
                    kcpParityShards = kcpParityShards.coerceAtLeast(0)
                )
                ProfileManager.addProfile(context, profile)
                "Profile created successfully: 「${profile.name}」 (ID: ${profile.id})"
            }

            "update_profile" -> {
                val profileId = args.get("profileId")?.asString ?: ""
                val existing = ProfileManager.getProfileById(context, profileId)
                if (existing == null) {
                    "Error: Profile ID not found: $profileId"
                } else {
                    if (args.has("name")) existing.name = args.get("name").asString
                    if (args.has("tunnelType")) existing.tunnelType = args.get("tunnelType").asString.lowercase()
                    if (args.has("sshAddr")) existing.sshAddr = args.get("sshAddr").asString
                    if (args.has("user")) existing.user = args.get("user").asString
                    if (args.has("pass")) existing.pass = args.get("pass").asString
                    if (args.has("proxyAddr")) existing.proxyAddr = args.get("proxyAddr").asString
                    if (args.has("serverName")) existing.serverName = args.get("serverName").asString
                    if (args.has("customHost")) existing.customHost = args.get("customHost").asString
                    if (args.has("customPath")) existing.customPath = args.get("customPath").asString
                    existing.enableCustomPath = if (existing.tunnelType == Profile.TUNNEL_TYPE_MASQUE) {
                        if (args.has("enableCustomPath")) {
                            args.get("enableCustomPath").asBoolean
                        } else if (args.has("customPath")) {
                            existing.customPath.isNotBlank()
                        } else {
                            existing.enableCustomPath
                        }
                    } else {
                        false
                    }
                    if (args.has("alpn")) existing.alpn = args.get("alpn").asString
                    if (args.has("proxyAuthRequired")) existing.proxyAuthRequired = args.get("proxyAuthRequired").asBoolean
                    if (args.has("proxyAuthToken")) existing.proxyAuthToken = args.get("proxyAuthToken").asString
                    if (args.has("heartbeatIntervalMs")) existing.heartbeatIntervalMs = args.get("heartbeatIntervalMs").asInt.coerceAtLeast(0)
                    if (args.has("xhttpChunkSizeKB")) existing.xhttpChunkSizeKB = args.get("xhttpChunkSizeKB").asInt.coerceAtLeast(0)
                    if (args.has("udpCustomPsk")) existing.udpCustomPsk = args.get("udpCustomPsk").asString
                    if (args.has("udpCustomPublicKey")) existing.udpCustomPublicKey = args.get("udpCustomPublicKey").asString
                    if (args.has("udpCustomMagic")) existing.udpCustomMagic = args.get("udpCustomMagic").asString
                    if (args.has("udpCustomPaths")) existing.udpCustomPaths = args.get("udpCustomPaths").asInt.coerceAtLeast(0)
                    if (args.has("udpCustomSockets")) existing.udpCustomSockets = args.get("udpCustomSockets").asInt.coerceAtLeast(0)
                    if (args.has("udpCustomSendWindow")) existing.udpCustomSendWindow = args.get("udpCustomSendWindow").asInt.coerceAtLeast(0)
                    if (args.has("dnsTunnelDomain")) existing.dnsTunnelDomain = args.get("dnsTunnelDomain").asString
                    if (args.has("dnsTunnelServers")) existing.dnsTunnelServers = args.get("dnsTunnelServers").asString
                    if (args.has("dnsTunnelType")) existing.dnsTunnelType = args.get("dnsTunnelType").asString
                    if (args.has("dnsTunnelPublicKey")) existing.dnsTunnelPublicKey = args.get("dnsTunnelPublicKey").asString
                    if (args.has("dnsTunnelEDNS0")) existing.dnsTunnelEDNS0 = args.get("dnsTunnelEDNS0").asBoolean
                    if (args.has("dnsTunnelPsk")) existing.dnsTunnelPsk = args.get("dnsTunnelPsk").asString
                    if (args.has("dnsTunnelMarker")) existing.dnsTunnelMarker = args.get("dnsTunnelMarker").asString
                    if (args.has("tunnelTlsEnabled")) existing.tunnelTlsEnabled = args.get("tunnelTlsEnabled").asBoolean
                    if (args.has("authType")) existing.authType = args.get("authType").asString
                    if (args.has("privateKey")) existing.privateKey = args.get("privateKey").asString
                    if (args.has("keyPass")) existing.keyPass = args.get("keyPass").asString
                    if (args.has("proxyAuthUser")) existing.proxyAuthUser = args.get("proxyAuthUser").asString
                    if (args.has("proxyAuthPass")) existing.proxyAuthPass = args.get("proxyAuthPass").asString
                    if (args.has("noisePublicKey")) existing.noisePublicKey = args.get("noisePublicKey").asString
                    if (args.has("icmpCustomPsk")) existing.icmpCustomPsk = args.get("icmpCustomPsk").asString
                    if (args.has("icmpCustomMagic")) existing.icmpCustomMagic = args.get("icmpCustomMagic").asString
                    if (args.has("icmpCustomPublicKey")) existing.icmpCustomPublicKey = args.get("icmpCustomPublicKey").asString
                    if (args.has("icmpCustomMtuMode")) existing.icmpCustomMtuMode = args.get("icmpCustomMtuMode").asString
                    if (args.has("icmpCustomMaxPayload")) existing.icmpCustomMaxPayload = args.get("icmpCustomMaxPayload").asInt.coerceAtLeast(0)
                    if (args.has("icmpCustomPaceMS")) existing.icmpCustomPaceMS = args.get("icmpCustomPaceMS").asInt.coerceAtLeast(0)
                    if (args.has("icmpCustomIdRange")) existing.icmpCustomIdRange = args.get("icmpCustomIdRange").asString
                    if (args.has("kcpPassword")) existing.kcpPassword = args.get("kcpPassword").asString
                    if (args.has("kcpCrypt")) existing.kcpCrypt = args.get("kcpCrypt").asString
                    if (args.has("kcpMode")) existing.kcpMode = args.get("kcpMode").asString
                    if (args.has("kcpSndWnd")) existing.kcpSndWnd = args.get("kcpSndWnd").asInt.coerceAtLeast(0)
                    if (args.has("kcpRcvWnd")) existing.kcpRcvWnd = args.get("kcpRcvWnd").asInt.coerceAtLeast(0)
                    if (args.has("kcpMtu")) existing.kcpMtu = args.get("kcpMtu").asInt.coerceAtLeast(0)
                    if (args.has("kcpNoComp")) existing.kcpNoComp = args.get("kcpNoComp").asBoolean
                    if (args.has("kcpSmuxVer")) existing.kcpSmuxVer = args.get("kcpSmuxVer").asInt.coerceAtLeast(0)
                    if (args.has("kcpKeepAlive")) existing.kcpKeepAlive = args.get("kcpKeepAlive").asInt.coerceAtLeast(0)
                    if (args.has("kcpDataShards")) existing.kcpDataShards = args.get("kcpDataShards").asInt.coerceAtLeast(0)
                    if (args.has("kcpParityShards")) existing.kcpParityShards = args.get("kcpParityShards").asInt.coerceAtLeast(0)

                    ProfileManager.updateProfile(context, existing)
                    "Profile updated successfully: 「${existing.name}」 (${existing.id})"
                }
            }

            "delete_profile" -> {
                val profileId = args.get("profileId")?.asString
                val profileName = args.get("profileName")?.asString
                val all = ProfileManager.getProfiles(context)

                val matched = if (!profileId.isNullOrBlank()) {
                    all.firstOrNull { it.id == profileId }
                } else if (!profileName.isNullOrBlank()) {
                    all.firstOrNull { it.name.equals(profileName, ignoreCase = true) }
                } else {
                    null
                }

                if (matched != null) {
                    ProfileManager.deleteProfile(context, matched)
                    "Profile deleted: 「${matched.name}」 (${matched.id})"
                } else {
                    "Error: Profile not found."
                }
            }

            "select_profile" -> {
                val profileId = args.get("profileId")?.asString
                val profileName = args.get("profileName")?.asString
                val all = ProfileManager.getProfiles(context)

                val matched = if (!profileId.isNullOrBlank()) {
                    all.firstOrNull { it.id == profileId }
                } else if (!profileName.isNullOrBlank()) {
                    all.firstOrNull { it.name.equals(profileName, ignoreCase = true) }
                } else {
                    null
                }

                if (matched != null) {
                    SettingsManager.setSelectedProfileId(context, matched.id)
                    val currentState = StunRepository.vpnState.value ?: VpnState.DISCONNECTED
                    if (currentState == VpnState.CONNECTED || currentState == VpnState.CONNECTING) {
                        stopVpn(context)
                        delay(600L)
                        startVpn(context, matched.id)
                    }
                    "Selected node switched to: 「${matched.name}」 (${matched.id})"
                } else {
                    "Error: Node not found with specified criteria."
                }
            }

            "test_node_latency" -> {
                val profileId = args.get("profileId")?.asString
                val all = ProfileManager.getProfiles(context)
                val targets = if (!profileId.isNullOrBlank()) {
                    all.filter { it.id == profileId }
                } else {
                    all
                }

                val results = JsonArray()
                targets.forEach { p ->
                    val r = testNodePingLatency(p, context)
                    results.add(JsonObject().apply {
                        addProperty("id", p.id)
                        addProperty("name", p.name)
                        addProperty("server", if (p.proxyAddr.isNotBlank()) p.proxyAddr else p.sshAddr)
                        addProperty("latencyMs", r.latencyMs)
                        addProperty("ok", r.ok)
                        addProperty("status", if (r.ok && r.latencyMs >= 0) "OK" else "TIMEOUT/ERROR")
                        if (!r.ok) addProperty("errorType", r.errorType)
                        if (r.error.isNotBlank()) addProperty("error", r.error)
                    })
                }
                gson.toJson(results)
            }

            "get_app_filter_list" -> {
                val pm = context.packageManager
                val installed = pm.getInstalledApplications(0)
                val filterAppsStr = SettingsManager.getFilterApps(context)
                val selectedSet = filterAppsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                val filterMode = SettingsManager.getFilterMode(context)

                val appList = JsonArray()
                installed.forEach { app ->
                    val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val label = try { pm.getApplicationLabel(app).toString() } catch (_: Exception) { app.packageName }
                    appList.add(JsonObject().apply {
                        addProperty("packageName", app.packageName)
                        addProperty("appName", label)
                        addProperty("isSystemApp", isSystem)
                        addProperty("isSelectedInFilter", selectedSet.contains(app.packageName))
                    })
                }

                val result = JsonObject().apply {
                    addProperty("filterMode", if (filterMode == 1) "ALLOW_SELECTED_ONLY" else "DISALLOW_SELECTED")
                    addProperty("totalApps", installed.size)
                    addProperty("selectedCount", selectedSet.size)
                    add("apps", appList)
                }
                gson.toJson(result)
            }

            "set_app_filter" -> {
                val mode = args.get("mode")?.asInt ?: 0
                val packages = args.get("packages")?.asString ?: ""
                SettingsManager.saveFilterMode(context, mode)
                SettingsManager.saveFilterApps(context, packages)
                "App filter updated: Mode=${if (mode == 1) "ALLOW_ONLY" else "DISALLOW"}, Packages Count=${packages.split(",").filter { it.isNotBlank() }.size}"
            }

            "update_geodata" -> {
                try {
                    SettingsManager.updateGeoDataSync(context)
                    "GeoData rules (geosite.dat & geoip.dat) successfully updated."
                } catch (e: Exception) {
                    "GeoData update failed: ${e.message}"
                }
            }

            "get_settings" -> {
                val settings = JsonObject().apply {
                    addProperty("serviceMode", if (SettingsManager.getServiceMode(context) == 1) "TPROXY" else "VPN")
                    addProperty("logLevel", SettingsManager.getLogLevel(context))
                    addProperty("remoteDns", SettingsManager.getRemoteDnsServer(context))
                    addProperty("localDns", SettingsManager.getLocalDnsServer(context))
                    addProperty("udpgwVersion", SettingsManager.getUdpgwVersion(context))
                    addProperty("udpgwAddr", SettingsManager.getUdpgwAddr(context))
                    addProperty("geositeDirect", SettingsManager.getGeositeDirect(context))
                    addProperty("geoipDirect", SettingsManager.getGeoipDirect(context))
                    addProperty("updateInterval", SettingsManager.getUpdateInterval(context))
                    addProperty("filterMode", SettingsManager.getFilterMode(context))
                    addProperty("filterApps", SettingsManager.getFilterApps(context))
                    addProperty("mcpServerPort", SettingsManager.getMcpServerPort(context))
                }
                gson.toJson(settings)
            }

            "set_settings" -> {
                if (args.has("remoteDns")) SettingsManager.saveRemoteDnsServer(context, args.get("remoteDns").asString)
                if (args.has("localDns")) SettingsManager.saveLocalDnsServer(context, args.get("localDns").asString)
                if (args.has("serviceMode")) SettingsManager.saveServiceMode(context, args.get("serviceMode").asInt)
                if (args.has("logLevel")) SettingsManager.saveLogLevel(context, args.get("logLevel").asString)
                if (args.has("geositeDirect")) SettingsManager.saveGeositeDirect(context, args.get("geositeDirect").asString)
                if (args.has("geoipDirect")) SettingsManager.saveGeoipDirect(context, args.get("geoipDirect").asString)
                if (args.has("mcpServerPort")) {
                    val newPort = args.get("mcpServerPort").asInt
                    SettingsManager.setMcpServerPort(context, newPort)
                    if (isServerRunning.get()) {
                        stop()
                        delay(500L)
                        start(context, newPort)
                    }
                }
                "Settings updated successfully."
            }

            "get_logs" -> {
                val limit = (args.get("limit")?.asInt ?: 200).coerceIn(1, 2000)
                val levelFilter = args.get("level")?.asString?.uppercase() ?: "ALL"
                val keyword = args.get("keyword")?.asString
                val arr = JsonArray()

                if (hasDiskLogFiles(context)) {
                    // 优先读取持久化磁盘日志（跨重启保留、历史更全）
                    val diskLogs = runCatching { readLogsFromDisk(context, limit, levelFilter, keyword) }
                        .getOrElse { emptyList() }
                    diskLogs.forEach { arr.add(it) }
                } else {
                    // 回退：进程内内存日志（最多 1000 条，重启即丢失）
                    val entries = StunRepository.logEntries.value ?: emptyList()
                    val kw = keyword?.takeIf { it.isNotBlank() }?.lowercase()
                    val filtered = entries.filter { entry ->
                        (levelFilter == "ALL" || entry.level.name.equals(levelFilter, ignoreCase = true)) &&
                            (kw == null || (entry.message + " " + entry.tag).lowercase().contains(kw))
                    }.takeLast(limit)
                    filtered.forEach { entry ->
                        arr.add(JsonObject().apply {
                            addProperty("timestamp", entry.timestamp)
                            addProperty("timeStr", entry.timeStr)
                            addProperty("level", entry.level.name)
                            addProperty("tag", entry.tag)
                            addProperty("message", entry.message)
                        })
                    }
                }
                gson.toJson(arr)
            }

            "get_device_info" -> {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                val batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

                val info = JsonObject().apply {
                    addProperty("model", Build.MODEL)
                    addProperty("manufacturer", Build.MANUFACTURER)
                    addProperty("brand", Build.BRAND)
                    addProperty("androidVersion", Build.VERSION.RELEASE)
                    addProperty("sdkInt", Build.VERSION.SDK_INT)
                    addProperty("batteryLevel", "$batteryPct%")
                    addProperty("appVersion", AppUtils.getAppVersion(context))
                    addProperty("coreLibVersion", AppUtils.getLibVersion())
                    addProperty("localIp", getLocalIpAddress())
                    addProperty("mcpServerPort", serverPort)
                }
                gson.toJson(info)
            }

            // ── 2026-09-12: WebDAV / 订阅 / 导入导出 ──

            "get_webdav_config" -> {
                val cfg = JsonObject().apply {
                    addProperty("url", SettingsManager.getWebDavUrl(context))
                    addProperty("user", SettingsManager.getWebDavUser(context))
                    addProperty("hasPass", SettingsManager.getWebDavPass(context).isNotBlank())
                    addProperty("hasPin", SettingsManager.getWebDavPin(context).isNotBlank())
                    addProperty("auto", SettingsManager.isWebDavAutoBackupEnabled(context))
                    addProperty("intervalHours", SettingsManager.getWebDavBackupIntervalHours(context))
                    addProperty("lastBackup", SettingsManager.getWebDavLastBackupTime(context))
                }
                gson.toJson(cfg)
            }

            "set_webdav_config" -> {
                val url = args.get("url")?.asString ?: SettingsManager.getWebDavUrl(context)
                val user = args.get("user")?.asString ?: SettingsManager.getWebDavUser(context)
                val pass = args.get("pass")?.asString ?: SettingsManager.getWebDavPass(context)
                val pin = args.get("pin")?.asString ?: SettingsManager.getWebDavPin(context)
                SettingsManager.saveWebDavConfig(context, url, user, pass, pin)
                args.get("auto")?.asBoolean?.let { SettingsManager.setWebDavAutoBackup(context, it) }
                args.get("intervalHours")?.asInt?.let { SettingsManager.saveWebDavBackupIntervalHours(context, it.toLong()) }
                WebDavBackupWorker.schedule(context)
                val cfg = JsonObject().apply {
                    addProperty("url", SettingsManager.getWebDavUrl(context))
                    addProperty("user", SettingsManager.getWebDavUser(context))
                    addProperty("auto", SettingsManager.isWebDavAutoBackupEnabled(context))
                    addProperty("intervalHours", SettingsManager.getWebDavBackupIntervalHours(context))
                }
                "WebDAV config saved. " + gson.toJson(cfg)
            }

            "list_backups" -> {
                val config = app.fjj.stun.backup.WebDavBackupManager.Config(
                    url = SettingsManager.getWebDavUrl(context),
                    user = SettingsManager.getWebDavUser(context),
                    pass = SettingsManager.getWebDavPass(context),
                    pin = SettingsManager.getWebDavPin(context)
                )
                if (!config.isConfigured) {
                    "Error: WebDAV is not fully configured (url, user, pass, pin are all required)."
                } else {
                    val dirs = app.fjj.stun.backup.WebDavBackupManager.listBackups(config)
                    val result = JsonObject().apply {
                        addProperty("count", dirs.size)
                        add("backups", gson.toJsonTree(dirs.map { dir ->
                            JsonObject().apply {
                                addProperty("dir", dir)
                                addProperty("displayTime", app.fjj.stun.backup.WebDavBackupManager.formatDirForDisplay(dir))
                            }
                        }))
                    }
                    gson.toJson(result)
                }
            }

            "backup_now" -> {
                val config = app.fjj.stun.backup.WebDavBackupManager.Config(
                    url = SettingsManager.getWebDavUrl(context),
                    user = SettingsManager.getWebDavUser(context),
                    pass = SettingsManager.getWebDavPass(context),
                    pin = SettingsManager.getWebDavPin(context)
                )
                if (!config.isConfigured) "Error: WebDAV is not fully configured."
                else try {
                    val result = app.fjj.stun.backup.WebDavBackupManager.backup(context, config)
                    SettingsManager.saveWebDavLastBackupTime(context, System.currentTimeMillis())
                    // MCP 是给机器读的：分区用稳定 id，不做本地化
                    "Backup complete: ${result.profiles} nodes + sections [${result.sections.joinToString()}]."
                } catch (e: Exception) {
                    "Backup failed: ${e.message}"
                }
            }

            "restore_backup" -> {
                val dir = args.get("dir")?.asString?.trim().orEmpty()
                if (dir.isEmpty()) "Error: dir is required."
                else {
                    val config = app.fjj.stun.backup.WebDavBackupManager.Config(
                        url = SettingsManager.getWebDavUrl(context),
                        user = SettingsManager.getWebDavUser(context),
                        pass = SettingsManager.getWebDavPass(context),
                        pin = SettingsManager.getWebDavPin(context)
                    )
                    if (!config.isConfigured) "Error: WebDAV is not fully configured."
                    else try {
                        val result = app.fjj.stun.backup.WebDavBackupManager.restore(context, config, dir)
                        if (result.settings) WebDavBackupWorker.schedule(context)
                        "Restored ${result.profiles} nodes, settings=${result.settings}, sections=[${result.sections.joinToString()}] from $dir"
                    } catch (e: Exception) {
                        "Restore failed: ${e.message}"
                    }
                }
            }

            "list_subscriptions" -> {
                val subs = SubscriptionManager.getSubscriptions(context)
                val lastSync = SubscriptionManager.getLastSyncTime(context)
                val result = JsonObject().apply {
                    addProperty("lastSync", lastSync)
                    add("subscriptions", gson.toJsonTree(subs.map { sub ->
                        JsonObject().apply {
                            addProperty("url", sub.url)
                            // 响应头解析出的订阅名：有则给出，便于调用方按名称展示/引用
                            if (sub.name.isNotBlank()) addProperty("name", sub.name)
                            addProperty("hasPin", sub.pin.isNotBlank())
                        }
                    }))
                }
                gson.toJson(result)
            }

            "sync_subscriptions" -> {
                try {
                    val results = SubscriptionManager.syncAllSubscriptions(context)
                    val arr = JsonArray()
                    results.forEach { r ->
                        arr.add(JsonObject().apply {
                            addProperty("url", r.url)
                            addProperty("success", r.success)
                            addProperty("importedCount", r.importedCount)
                            addProperty("message", r.message)
                        })
                    }
                    gson.toJson(JsonObject().apply {
                        addProperty("status", "success")
                        addProperty("syncedCount", results.count { it.success })
                        add("results", arr)
                    })
                } catch (e: Exception) {
                    "Sync failed: ${e.message}"
                }
            }

            "import_profiles" -> {
                val content = args.get("content")?.asString?.trim().orEmpty()
                val pin = args.get("pin")?.asString?.trim().orEmpty()
                if (content.isEmpty()) "Error: content is required."
                else {
                    try {
                        val isEncrypted = app.fjj.stun.util.ShareCryptoUtils.isEncryptedPayload(content)
                        val json = if (isEncrypted) {
                            if (pin.isEmpty()) { "Error: encrypted content requires pin." }
                            else {
                                val decrypted = app.fjj.stun.util.ShareCryptoUtils.decrypt(content, pin)
                                if (decrypted == null) { "Error: wrong PIN or corrupted payload." } else { decrypted }
                            }
                        } else content
                        if (json.startsWith("Error:")) json
                        else {
                            val profiles = parseProfilesFromJson(json)
                            if (profiles.isEmpty()) "Error: no profiles found in content."
                            else {
                                var count = 0
                                profiles.forEach { p ->
                                    val existing = ProfileManager.getProfileById(context, p.id)
                                    if (existing != null) ProfileManager.updateProfile(context, p)
                                    else ProfileManager.addProfile(context, p.copy(id = p.id.ifBlank { UUID.randomUUID().toString() }))
                                    count++
                                }
                                "Imported $count profiles."
                            }
                        }
                    } catch (e: Exception) {
                        "Import failed: ${e.message}"
                    }
                }
            }

            "export_profiles" -> {
                val pin = args.get("pin")?.asString?.trim().orEmpty()
                if (pin.length < 4) "Error: pin must be at least 4 characters."
                else {
                    val profiles = ProfileManager.getProfiles(context)
                    val json = Gson().toJson(profiles)
                    val payload = app.fjj.stun.util.ShareCryptoUtils.encrypt(json, pin)
                    JsonObject().apply {
                        addProperty("payload", payload)
                        addProperty("nodeCount", profiles.size)
                        addProperty("hint", "Import on another device with import_profiles + same PIN, or paste into WebUI import.")
                    }.toString()
                }
            }

            else -> {
                "Unknown tool: $toolName"
            }
        }

        val contentArray = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", textResult)
            })
        }

        return JsonObject().apply {
            add("content", contentArray)
        }
    }

    // ─── Resources Definition & Read ───

    private fun handleResourcesList(): JsonObject {
        val resources = JsonArray()

        resources.add(JsonObject().apply {
            addProperty("uri", "stun://status")
            addProperty("name", "Stun VPN Status")
            addProperty("description", "Real-time snapshot of VPN connection status, bandwidth, and traffic metrics.")
            addProperty("mimeType", "application/json")
        })

        resources.add(JsonObject().apply {
            addProperty("uri", "stun://profiles")
            addProperty("name", "Stun Node Profiles")
            addProperty("description", "List of all saved server nodes and protocols.")
            addProperty("mimeType", "application/json")
        })

        resources.add(JsonObject().apply {
            addProperty("uri", "stun://settings")
            addProperty("name", "Stun Global Settings")
            addProperty("description", "Global DNS, UDPGW, and routing settings.")
            addProperty("mimeType", "application/json")
        })

        resources.add(JsonObject().apply {
            addProperty("uri", "stun://app-filter")
            addProperty("name", "Stun Split-Tunneling App Filter")
            addProperty("description", "Per-app proxy mode and selected package names.")
            addProperty("mimeType", "application/json")
        })

        resources.add(JsonObject().apply {
            addProperty("uri", "stun://logs/recent")
            addProperty("name", "Recent Stun Logs")
            addProperty("description", "Recent runtime log entries.")
            addProperty("mimeType", "text/plain")
        })

        resources.add(JsonObject().apply {
            addProperty("uri", "stun://backup")
            addProperty("name", "WebDAV Backup Status")
            addProperty("description", "WebDAV cloud backup configuration and the list of available backup snapshots on the server.")
            addProperty("mimeType", "application/json")
        })

        return JsonObject().apply { add("resources", resources) }
    }

    private suspend fun handleResourcesRead(context: Context, params: JsonObject): JsonObject {
        val uri = params.get("uri")?.asString ?: ""
        val contents = JsonArray()

        when (uri) {
            "stun://status" -> {
                val statusJson = buildStatusJson(context)
                contents.add(JsonObject().apply {
                    addProperty("uri", uri)
                    addProperty("mimeType", "application/json")
                    addProperty("text", gson.toJson(statusJson))
                })
            }
            "stun://profiles" -> {
                val profiles = ProfileManager.getProfiles(context)
                contents.add(JsonObject().apply {
                    addProperty("uri", uri)
                    addProperty("mimeType", "application/json")
                    addProperty("text", gson.toJson(profiles))
                })
            }
            "stun://settings" -> {
                val settings = JsonObject().apply {
                    addProperty("serviceMode", if (SettingsManager.getServiceMode(context) == 1) "TPROXY" else "VPN")
                    addProperty("logLevel", SettingsManager.getLogLevel(context))
                    addProperty("remoteDns", SettingsManager.getRemoteDnsServer(context))
                    addProperty("localDns", SettingsManager.getLocalDnsServer(context))
                    addProperty("udpgwVersion", SettingsManager.getUdpgwVersion(context))
                    addProperty("udpgwAddr", SettingsManager.getUdpgwAddr(context))
                    addProperty("geositeDirect", SettingsManager.getGeositeDirect(context))
                    addProperty("geoipDirect", SettingsManager.getGeoipDirect(context))
                }
                contents.add(JsonObject().apply {
                    addProperty("uri", uri)
                    addProperty("mimeType", "application/json")
                    addProperty("text", gson.toJson(settings))
                })
            }
            "stun://app-filter" -> {
                val filter = JsonObject().apply {
                    addProperty("filterMode", SettingsManager.getFilterMode(context))
                    addProperty("filterApps", SettingsManager.getFilterApps(context))
                }
                contents.add(JsonObject().apply {
                    addProperty("uri", uri)
                    addProperty("mimeType", "application/json")
                    addProperty("text", gson.toJson(filter))
                })
            }
            "stun://logs/recent" -> {
                val logsText = StunRepository.appLogs.value?.toString() ?: ""
                contents.add(JsonObject().apply {
                    addProperty("uri", uri)
                    addProperty("mimeType", "text/plain")
                    addProperty("text", logsText)
                })
            }
            "stun://backup" -> {
                val cfg = JsonObject().apply {
                    addProperty("url", SettingsManager.getWebDavUrl(context))
                    addProperty("user", SettingsManager.getWebDavUser(context))
                    addProperty("hasPass", SettingsManager.getWebDavPass(context).isNotBlank())
                    addProperty("hasPin", SettingsManager.getWebDavPin(context).isNotBlank())
                    addProperty("auto", SettingsManager.isWebDavAutoBackupEnabled(context))
                    addProperty("intervalHours", SettingsManager.getWebDavBackupIntervalHours(context))
                    addProperty("lastBackup", SettingsManager.getWebDavLastBackupTime(context))
                }
                val webdavConfig = app.fjj.stun.backup.WebDavBackupManager.Config(
                    url = SettingsManager.getWebDavUrl(context),
                    user = SettingsManager.getWebDavUser(context),
                    pass = SettingsManager.getWebDavPass(context),
                    pin = SettingsManager.getWebDavPin(context)
                )
                val backups = if (webdavConfig.isConfigured) {
                    try { app.fjj.stun.backup.WebDavBackupManager.listBackups(webdavConfig) } catch (_: Exception) { emptyList() }
                } else emptyList()
                val result = JsonObject().apply {
                    add("config", cfg)
                    add("backups", gson.toJsonTree(backups))
                }
                contents.add(JsonObject().apply {
                    addProperty("uri", uri)
                    addProperty("mimeType", "application/json")
                    addProperty("text", gson.toJson(result))
                })
            }
            else -> {
                contents.add(JsonObject().apply {
                    addProperty("uri", uri)
                    addProperty("mimeType", "text/plain")
                    addProperty("text", "Resource not found: $uri")
                })
            }
        }

        return JsonObject().apply { add("contents", contents) }
    }

    // ─── Prompts ───

    private fun handlePromptsList(): JsonObject {
        val prompts = JsonArray()

        prompts.add(JsonObject().apply {
            addProperty("name", "diagnose_vpn")
            addProperty("description", "Analyze current connection metrics and error logs to diagnose connectivity issues.")
        })

        prompts.add(JsonObject().apply {
            addProperty("name", "pick_best_node")
            addProperty("description", "Test all nodes latency and recommend the fastest node.")
        })

        prompts.add(JsonObject().apply {
            addProperty("name", "configure_split_tunneling")
            addProperty("description", "Inspect installed apps on the phone and help configure per-app proxy rules.")
        })

        return JsonObject().apply { add("prompts", prompts) }
    }

    private fun handlePromptsGet(params: JsonObject): JsonObject {
        val name = params.get("name")?.asString ?: ""
        val messages = JsonArray()

        when (name) {
            "diagnose_vpn" -> {
                messages.add(JsonObject().apply {
                    addProperty("role", "user")
                    add("content", JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", "Please call get_vpn_status and get_logs with level=ERROR to analyze whether the Stun VPN connection is healthy or diagnose any issues.")
                    })
                })
            }
            "pick_best_node" -> {
                messages.add(JsonObject().apply {
                    addProperty("role", "user")
                    add("content", JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", "Please call test_node_latency to test all nodes, find the lowest latency node, and use select_profile to switch to it.")
                    })
                })
            }
            "configure_split_tunneling" -> {
                messages.add(JsonObject().apply {
                    addProperty("role", "user")
                    add("content", JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", "Please call get_app_filter_list to inspect installed apps on the device, and guide me in setting up split-tunneling with set_app_filter.")
                    })
                })
            }
        }

        return JsonObject().apply { add("messages", messages) }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun buildStatusJson(context: Context): JsonObject {
        val selected = ProfileManager.getSelectedProfile(context)
        val state = StunRepository.vpnState.value ?: VpnState.DISCONNECTED
        val txR = StunRepository.txRate.value ?: 0L
        val rxR = StunRepository.rxRate.value ?: 0L
        val txTot = StunRepository.txTotal.value ?: 0L
        val rxTot = StunRepository.rxTotal.value ?: 0L
        val mode = SettingsManager.getServiceMode(context)

        return JsonObject().apply {
            addProperty("vpnState", state.name)
            addProperty("isConnected", state == VpnState.CONNECTED)
            addProperty("serviceMode", if (mode == SettingsManager.SERVICE_MODE_TPROXY) "TPROXY" else "VPN")
            addProperty("currentProfileId", selected.id)
            addProperty("currentProfileName", selected.name)
            addProperty("currentProfileType", selected.tunnelType)
            addProperty("currentProfileServer", if (selected.proxyAddr.isNotBlank()) selected.proxyAddr else selected.sshAddr)
            addProperty("txRateFormatted", AppUtils.formatSpeed(txR))
            addProperty("rxRateFormatted", AppUtils.formatSpeed(rxR))
            addProperty("txRateBytes", txR)
            addProperty("rxRateBytes", rxR)
            addProperty("txTotalFormatted", AppUtils.formatBytes(txTot))
            addProperty("rxTotalFormatted", AppUtils.formatBytes(rxTot))
            addProperty("txTotalBytes", txTot)
            addProperty("rxTotalBytes", rxTot)
            addProperty("engineError", StunRepository.engineError.value)
        }
    }

    private fun startVpn(context: Context, profileId: String? = null) {
        if (!profileId.isNullOrBlank()) {
            SettingsManager.setSelectedProfileId(context, profileId)
        }
        val mode = SettingsManager.getServiceMode(context)
        val isTProxy = mode == SettingsManager.SERVICE_MODE_TPROXY
        val intentClass = if (isTProxy) MyTransparentProxyService::class.java else MyVpnService::class.java
        val action = if (isTProxy) MyTransparentProxyService.ACTION_START else MyVpnService.ACTION_START
        val intent = Intent(context, intentClass).apply { this.action = action }
        ContextCompat.startForegroundService(context, intent)
    }

    private fun stopVpn(context: Context) {
        val mode = SettingsManager.getServiceMode(context)
        val isTProxy = mode == SettingsManager.SERVICE_MODE_TPROXY
        val intentClass = if (isTProxy) MyTransparentProxyService::class.java else MyVpnService::class.java
        val action = if (isTProxy) MyTransparentProxyService.ACTION_STOP else MyVpnService.ACTION_STOP
        val intent = Intent(context, intentClass).apply { this.action = action }
        ContextCompat.startForegroundService(context, intent)
    }

    // 与底部栏 LatencyProber 完全同源：走 Go 侧 pingNodes 的真握手延迟，
    // 而不是早期那种裸 Socket 直连探测（后者测的是本机→入口 TCP，会失真）。
    private data class NodeLatency(
        val latencyMs: Long,
        val ok: Boolean,
        val errorType: String,
        val error: String
    )

    private fun testNodePingLatency(profile: Profile, context: Context): NodeLatency {
        val configJson = try {
            VpnConfigBuilder.buildMySshConfig(context, profile, 1080, 53)
        } catch (e: Exception) {
            StunLogger.e(TAG, "test_node_latency buildMySshConfig failed: ${e.message}", e)
            return NodeLatency(-1, false, "config", e.message ?: "config build failed")
        }
        val reqArray = JSONArray().apply {
            put(JSONObject().put("id", profile.id).put("config", JSONObject(configJson)))
        }
        val resStr = try {
            StunRepository.proxy.pingNodes(reqArray.toString(), PING_URL, PING_TIMEOUT_MS)
        } catch (e: Exception) {
            StunLogger.e(TAG, "test_node_latency pingNodes failed: ${e.message}", e)
            return NodeLatency(-1, false, "exception", e.message ?: "pingNodes failed")
        }
        return try {
            val arr = JSONArray(resStr)
            if (arr.length() > 0) {
                val obj = arr.getJSONObject(0)
                val ok = obj.optBoolean("ok", false)
                val latency = obj.optLong("latencyMs", -1)
                NodeLatency(
                    latencyMs = latency,
                    ok = ok,
                    errorType = obj.optString("errorType", "other"),
                    error = obj.optString("error", "")
                )
            } else {
                NodeLatency(-1, false, "empty", "no ping result returned")
            }
        } catch (e: Exception) {
            NodeLatency(-1, false, "parse", e.message ?: "parse failed")
        }
    }

    // =========================================================================
    // Native Google Gemini Function Calling Integration
    // =========================================================================

    private fun buildGeminiFunctionDeclarations(): JsonObject {
        val toolsObj = handleToolsList()
        val toolsArray = toolsObj.getAsJsonArray("tools") ?: JsonArray()
        val functionDecls = JsonArray()

        toolsArray.forEach { toolEl ->
            val toolObj = toolEl.asJsonObject
            val name = toolObj.get("name")?.asString ?: ""
            val desc = toolObj.get("description")?.asString ?: ""
            val inputSchema = toolObj.getAsJsonObject("inputSchema") ?: JsonObject()

            functionDecls.add(JsonObject().apply {
                addProperty("name", name)
                addProperty("description", desc)
                add("parameters", JsonObject().apply {
                    addProperty("type", "OBJECT")
                    add("properties", inputSchema.getAsJsonObject("properties") ?: JsonObject())
                    if (inputSchema.has("required")) {
                        add("required", inputSchema.get("required"))
                    }
                })
            })
        }

        return JsonObject().apply {
            add("functionDeclarations", functionDecls)
        }
    }

    private suspend fun handleGeminiCall(context: Context, rawBody: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val element = JsonParser.parseString(rawBody)
                if (!element.isJsonObject) return@withContext "{\"error\": \"Invalid JSON\"}"
                val req = element.asJsonObject

                // Support both direct {"name": "...", "args": {...}} and {"functionCall": {"name": "...", "args": {...}}}
                val fnObj = if (req.has("functionCall")) req.getAsJsonObject("functionCall") else req
                val name = fnObj.get("name")?.asString ?: ""
                val args = if (fnObj.has("args")) fnObj.getAsJsonObject("args") else if (fnObj.has("arguments")) fnObj.getAsJsonObject("arguments") else JsonObject()

                val callParams = JsonObject().apply {
                    addProperty("name", name)
                    add("arguments", args)
                }

                val toolResult = handleToolsCall(context, callParams)
                val contentArray = toolResult.getAsJsonArray("content")
                val text = contentArray?.firstOrNull()?.asJsonObject?.get("text")?.asString ?: ""

                val geminiResp = JsonObject().apply {
                    add("functionResponse", JsonObject().apply {
                        addProperty("name", name)
                        add("response", JsonObject().apply {
                            addProperty("output", text)
                        })
                    })
                }
                gson.toJson(geminiResp)
            } catch (e: Exception) {
                val errResp = JsonObject().apply {
                    addProperty("error", e.message ?: "Execution error")
                }
                gson.toJson(errResp)
            }
        }
    }

    private fun validateAuth(call: ApplicationCall, context: Context): Boolean {
        val authMode = SettingsManager.getMcpAuthMode(context)
        if (authMode == SettingsManager.MCP_AUTH_MODE_NONE) return true

        val authHeader = call.request.header("Authorization") ?: ""
        val apiKeyHeader = call.request.header("X-API-Key") ?: ""
        val queryToken = call.request.queryParameters["token"] ?: call.request.queryParameters["apiKey"] ?: call.request.queryParameters["access_token"] ?: ""

        return when (authMode) {
            SettingsManager.MCP_AUTH_MODE_API_KEY -> {
                val configuredKey = SettingsManager.getMcpApiKey(context)
                if (configuredKey.isBlank()) return true
                if (apiKeyHeader == configuredKey || queryToken == configuredKey) return true
                if (authHeader.startsWith("Bearer ", ignoreCase = true)) {
                    val token = authHeader.substring(7).trim()
                    return token == configuredKey
                }
                false
            }
            SettingsManager.MCP_AUTH_MODE_BASIC -> {
                val user = SettingsManager.getMcpBasicUser(context)
                val pass = SettingsManager.getMcpBasicPass(context)
                if (user.isBlank() && pass.isBlank()) return true
                if (authHeader.startsWith("Basic ", ignoreCase = true)) {
                    try {
                        val decoded = String(android.util.Base64.decode(authHeader.substring(6).trim(), android.util.Base64.DEFAULT), Charsets.UTF_8)
                        val parts = decoded.split(":", limit = 2)
                        parts.size == 2 && parts[0] == user && parts[1] == pass
                    } catch (_: Exception) { false }
                } else false
            }
            SettingsManager.MCP_AUTH_MODE_OAUTH -> {
                val token = if (authHeader.startsWith("Bearer ", ignoreCase = true)) {
                    authHeader.substring(7).trim()
                } else {
                    queryToken
                }
                if (token.isBlank()) return false
                val expiry = activeOAuthTokens[token] ?: return false
                if (System.currentTimeMillis() > expiry) {
                    activeOAuthTokens.remove(token)
                    return false
                }
                true
            }
            else -> true
        }
    }

    private suspend fun respondUnauthorized(call: ApplicationCall, context: Context) {
        val authMode = SettingsManager.getMcpAuthMode(context)
        val challenge = when (authMode) {
            SettingsManager.MCP_AUTH_MODE_BASIC -> "Basic realm=\"Stun MCP Server\""
            else -> "Bearer realm=\"Stun MCP Server\""
        }
        call.response.headers.append("WWW-Authenticate", challenge)
        call.respondText(
            "{\"error\": \"unauthorized\", \"message\": \"Authentication required for Stun MCP Server\"}",
            ContentType.Application.Json,
            HttpStatusCode.Unauthorized
        )
    }

    private fun renderOAuthAuthorizeHtml(
        clientId: String,
        redirectUrl: String?,
        authCode: String,
        call: ApplicationCall? = null,
        context: Context? = null
    ): String {
        val lang = McpI18n.resolveLanguage(call, context)
        val title = McpI18n.get("oauth_title", lang)
        val promptTmpl = McpI18n.get("oauth_prompt", lang)
        val clientDisplay = if (clientId.isNotBlank()) clientId else "Claude Client"
        val promptText = String.format(promptTmpl, clientDisplay)
        val approveText = McpI18n.get("oauth_approve", lang)
        val cancelText = McpI18n.get("oauth_cancel", lang)
        val codeLabel = McpI18n.get("oauth_code", lang)

        val approveBtn = if (redirectUrl != null) {
            "<a href=\"$redirectUrl\" style=\"display:block;background:#10b981;color:#fff;padding:14px;border-radius:10px;text-decoration:none;font-weight:bold;font-size:16px;\">$approveText</a>"
        } else {
            "<div style=\"background:#0f172a;padding:12px;border-radius:8px;border:1px solid #334155;\"><p style=\"color:#a5f3fc;font-size:15px;margin:0;\">$codeLabel: <b style=\"color:#38bdf8;font-family:monospace;\">$authCode</b></p></div>"
        }

        return """
<!DOCTYPE html>
<html lang="$lang">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>$title</title>
    <style>
        :root { --bg:#0f172a; --card:#1e293b; --text:#f8fafc; --muted:#94a3b8; --border:#334155; --accent:#38bdf8; }
        [data-theme="light"] { --bg:#f1f5f9; --card:#ffffff; --text:#0f172a; --muted:#475569; --border:#cbd5e1; --accent:#0284c7; }
        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: var(--bg); color: var(--text); display: flex; align-items: center; justify-content: center; min-height: 100vh; margin: 0; padding: 16px; box-sizing: border-box; transition: background .2s, color .2s; }
        .card { background: var(--card); border: 1px solid var(--border); border-radius: 16px; padding: 28px; max-width: 420px; width: 100%; text-align: center; box-shadow: 0 10px 25px rgba(0,0,0,0.5); position: relative; }
        .icon { font-size: 36px; margin-bottom: 8px; }
        h1 { color: var(--accent); font-size: 20px; margin: 0 0 12px; }
        p { color: var(--muted); font-size: 14px; line-height: 1.5; margin: 0 0 20px; }
        .client-tag { color: var(--text); font-weight: bold; background: var(--border); padding: 2px 8px; border-radius: 4px; }
        .btn-cancel { display: block; margin-top: 12px; color: var(--muted); text-decoration: none; font-size: 13px; }
        .theme-btn { position: absolute; top: 14px; right: 14px; background: none; border: 1px solid var(--border); border-radius: 6px; padding: 3px 9px; cursor: pointer; font-size: 14px; color: var(--text); }
    </style>
    <script>
        (function(){var r=document.documentElement,s=localStorage.getItem('mcp_theme');
        if(s){r.setAttribute('data-theme',s);}else if(window.matchMedia&&window.matchMedia('(prefers-color-scheme:light)').matches){r.setAttribute('data-theme','light');}})();
    </script>
</head>
<body>
    <div class="card">
        <button onclick="toggleTheme()" id="theme-btn" class="theme-btn" title="Toggle theme">🌙</button>
        <div class="icon">🦊🔐</div>
        <h1>$title</h1>
        <p>$promptText</p>
        <div>
            $approveBtn
        </div>
        <a class="btn-cancel" href="javascript:window.close()">$cancelText</a>
    </div>
<script>
(function(){
  var root=document.documentElement;
  var btn=document.getElementById('theme-btn');
  var saved=localStorage.getItem('mcp_theme');
  if(saved){root.setAttribute('data-theme',saved);}
  else if(window.matchMedia&&window.matchMedia('(prefers-color-scheme:light)').matches){root.setAttribute('data-theme','light');}
  function upd(){var t=root.getAttribute('data-theme')||'dark';btn.textContent=t==='light'?'☀️':'🌙';}
  upd();
  window.toggleTheme=function(){
    var cur=root.getAttribute('data-theme')||'dark';
    var next=cur==='dark'?'light':'dark';
    root.setAttribute('data-theme',next);
    localStorage.setItem('mcp_theme',next);
    upd();
  };
})();
</script>
</body>
</html>
        """.trimIndent()
    }

    private fun renderDashboardHtml(context: Context, call: ApplicationCall? = null): String {
        val lang = McpI18n.resolveLanguage(call, context)
        val baseUrl = call?.let { getBaseUrl(it) } ?: "http://${getLocalIpAddress()}:$serverPort"
        val scheme = call?.let { getRequestScheme(it) } ?: "http"
        val host = call?.request?.header("Host") ?: "${getLocalIpAddress()}:$serverPort"

        val currentMcpUrl = "$baseUrl/mcp"
        val geminiUrl = "$baseUrl/gemini/declarations"
        val certUrl = "$baseUrl/mcp/cert"

        val authMode = SettingsManager.getMcpAuthMode(context)
        val authModeName = when (authMode) {
            SettingsManager.MCP_AUTH_MODE_NONE -> McpI18n.get("auth_none", lang)
            SettingsManager.MCP_AUTH_MODE_API_KEY -> McpI18n.get("auth_api_key", lang)
            SettingsManager.MCP_AUTH_MODE_BASIC -> McpI18n.get("auth_basic", lang)
            SettingsManager.MCP_AUTH_MODE_OAUTH -> McpI18n.get("auth_oauth", lang)
            else -> McpI18n.get("auth_none", lang)
        }

        val authDesc = when (authMode) {
            SettingsManager.MCP_AUTH_MODE_NONE -> McpI18n.get("desc_none", lang)
            SettingsManager.MCP_AUTH_MODE_API_KEY -> McpI18n.get("desc_api_key", lang)
            SettingsManager.MCP_AUTH_MODE_BASIC -> McpI18n.get("desc_basic", lang)
            SettingsManager.MCP_AUTH_MODE_OAUTH -> McpI18n.get("desc_oauth", lang)
            else -> ""
        }

        fun htmlEscape(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        val configJson = htmlEscape(getClaudeConfigJson(context, scheme, host))
        val codexConfigToml = htmlEscape(getCodexConfigToml(context, scheme, host))

        fun t(key: String): String = McpI18n.get(key, lang)

        return """
<!DOCTYPE html>
<html lang="$lang">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${t("title")}</title>
    <style>
        :root { --bg: #0f172a; --card: #1e293b; --text: #f8fafc; --muted: #94a3b8; --heading: #cbd5e1; --tool-bg: #0f172a; --accent: #38bdf8; --green: #10b981; --gemini: #818cf8; --border: #334155; --code: #090d16; --code-text: #a5f3fc; }
        [data-theme="light"] { --bg: #f1f5f9; --card: #ffffff; --text: #0f172a; --muted: #475569; --heading: #334155; --tool-bg: #f8fafc; --accent: #0284c7; --green: #059669; --gemini: #6366f1; --border: #cbd5e1; --code: #0b1220; --code-text: #a5f3fc; }
        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: var(--bg); color: var(--text); padding: 24px; margin: 0; line-height: 1.5; transition: background .2s, color .2s; }
        .container { max-width: 880px; margin: 0 auto; }
        .lang-bar { display: flex; justify-content: flex-end; align-items: center; gap: 8px; margin-bottom: 16px; font-size: 13px; }
        .lang-btn { color: var(--muted); text-decoration: none; padding: 4px 8px; border-radius: 6px; border: 1px solid var(--border); }
        .lang-btn.active { color: #fff; background: #0284c7; border-color: #0284c7; font-weight: bold; }
        .card { background: var(--card); border: 1px solid var(--border); border-radius: 12px; padding: 20px; margin-bottom: 20px; box-shadow: 0 4px 12px rgba(0,0,0,0.3); }
        h1 { color: var(--accent); margin-top: 0; display: flex; align-items: center; gap: 10px; font-size: 24px; }
        h2 { font-size: 18px; margin-top: 0; color: var(--heading); display: flex; align-items: center; gap: 8px; }
        .badge { background: #059669; color: #fff; padding: 4px 10px; border-radius: 9999px; font-size: 12px; font-weight: bold; }
        pre { background: var(--code); border: 1px solid var(--border); padding: 14px; border-radius: 8px; overflow-x: auto; color: var(--code-text); font-size: 13px; font-family: monospace; }
        .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 12px; }
        .tool-item { background: var(--tool-bg); padding: 10px 14px; border-radius: 8px; border: 1px solid var(--border); font-size: 13px; }
        .tool-name { font-weight: bold; color: var(--accent); font-family: monospace; }
        .btn-cert { display: inline-block; background: #0284c7; color: #fff; text-decoration: none; padding: 8px 16px; border-radius: 6px; font-size: 13px; font-weight: bold; margin-top: 6px; }
        .btn-cert:hover { background: #0369a1; }
        .theme-btn { background: none; border: 1px solid var(--border); border-radius: 6px; padding: 4px 10px; cursor: pointer; font-size: 15px; color: var(--text); }
    </style>
    <script>
        (function(){var r=document.documentElement,s=localStorage.getItem('mcp_theme');
        if(s){r.setAttribute('data-theme',s);}else if(window.matchMedia&&window.matchMedia('(prefers-color-scheme:light)').matches){r.setAttribute('data-theme','light');}})();
    </script>
</head>
<body>
    <div class="container">
        <div class="lang-bar">
            <a class="lang-btn ${if (lang == "en") "active" else ""}" href="?lang=en">English</a>
            <a class="lang-btn ${if (lang == "zh") "active" else ""}" href="?lang=zh">简体中文</a>
            <a class="lang-btn ${if (lang == "ja") "active" else ""}" href="?lang=ja">日本語</a>
            <a class="lang-btn ${if (lang == "de") "active" else ""}" href="?lang=de">Deutsch</a>
            <a class="lang-btn ${if (lang == "fr") "active" else ""}" href="?lang=fr">Français</a>
            <button onclick="toggleTheme()" id="theme-btn" class="theme-btn" title="Toggle theme">🌙</button>
        </div>
        <div class="card">
            <h1>🚀 ${t("title")} <span class="badge">${t("badge_online")}</span></h1>
            <p>${t("server_desc")}</p>
            <p><strong>🌐 ${t("connected_sse")}:</strong> <br><code style="color:var(--accent)">$currentMcpUrl</code></p>
            <p><strong>🔒 ${t("protocol_mode")}:</strong> <span class="badge" style="background:#0284c7;">${scheme.uppercase()} (${t("single_port_mux")})</span></p>
            <p><strong>♊ ${t("gemini_schema")}:</strong> <code style="color:var(--gemini)">$geminiUrl</code></p>
            ${if (scheme == "https") """
            <div style="margin-top: 12px;">
                <a class="btn-cert" href="$certUrl">${t("download_cert")}</a>
            </div>
            """ else ""}
        </div>

        <div class="card">
            <h2>🛡️ ${t("security_title")}: <span style="color:var(--accent)">$authModeName</span></h2>
            <p>$authDesc</p>
            ${if (authMode == SettingsManager.MCP_AUTH_MODE_OAUTH) """
            <p><strong>${t("token_endpoint")}:</strong> <code>POST $baseUrl/token</code></p>
            <pre>curl -X POST $baseUrl/token -d "grant_type=client_credentials&amp;client_id=stun-client&amp;client_secret=YOUR_SECRET"</pre>
            """ else ""}
        </div>

        <div class="card">
            <h2>📱 ${t("claude_mobile_title")}</h2>
            <p>${t("claude_mobile_steps")}</p>
            <ul>
                <li><strong>${t("server_name")}:</strong> Stun Phone</li>
                <li><strong>${t("server_url")}:</strong> <code style="color:var(--green)">$currentMcpUrl</code></li>
            </ul>
            ${if (scheme == "https") "<p style=\"font-size:12px; color:var(--muted);\">${t("claude_mobile_tip")}</p>" else ""}
        </div>

        <div class="card">
            <h2>⚙️ ${t("desktop_title")}</h2>
            <p>${t("desktop_hint")}</p>
            <h3>Codex <code>config.toml</code></h3>
            <pre>$codexConfigToml</pre>
            <h3>Claude Code <code>.mcp.json</code></h3>
            <pre>$configJson</pre>
        </div>

        <div class="card">
            <h2>♊ ${t("gemini_title")}</h2>
            <p>${t("gemini_hint")}</p>
            <pre>
import google.generativeai as genai
import requests

# 1. Fetch live declarations from phone
tools_decl = requests.get("$geminiUrl").json()

# 2. Start chat with phone control
model = genai.GenerativeModel("gemini-2.0-flash", tools=tools_decl["functionDeclarations"])
chat = model.start_chat(enable_automatic_function_calling=True)
res = chat.send_message("Check VPN status on Stun and pick the fastest node")
print(res.text)
            </pre>
        </div>

        <div class="card">
            <h2>🛠️ ${t("tools_title")} (27)</h2>
            <div class="grid">
                <div class="tool-item"><div class="tool-name">get_vpn_status</div>${t("tool_get_vpn_status")}</div>
                <div class="tool-item"><div class="tool-name">start_vpn</div>${t("tool_start_vpn")}</div>
                <div class="tool-item"><div class="tool-name">stop_vpn</div>${t("tool_stop_vpn")}</div>
                <div class="tool-item"><div class="tool-name">restart_vpn</div>${t("tool_restart_vpn")}</div>
                <div class="tool-item"><div class="tool-name">list_profiles</div>${t("tool_list_profiles")}</div>
                <div class="tool-item"><div class="tool-name">get_profile_detail</div>${t("tool_get_profile_detail")}</div>
                <div class="tool-item"><div class="tool-name">create_profile</div>${t("tool_create_profile")}</div>
                <div class="tool-item"><div class="tool-name">update_profile</div>${t("tool_update_profile")}</div>
                <div class="tool-item"><div class="tool-name">delete_profile</div>${t("tool_delete_profile")}</div>
                <div class="tool-item"><div class="tool-name">select_profile</div>${t("tool_select_profile")}</div>
                <div class="tool-item"><div class="tool-name">test_node_latency</div>${t("tool_test_node_latency")}</div>
                <div class="tool-item"><div class="tool-name">get_app_filter_list</div>${t("tool_get_app_filter_list")}</div>
                <div class="tool-item"><div class="tool-name">set_app_filter</div>${t("tool_set_app_filter")}</div>
                <div class="tool-item"><div class="tool-name">update_geodata</div>${t("tool_update_geodata")}</div>
                <div class="tool-item"><div class="tool-name">get_settings</div>${t("tool_get_settings")}</div>
                <div class="tool-item"><div class="tool-name">set_settings</div>${t("tool_set_settings")}</div>
                <div class="tool-item"><div class="tool-name">get_logs</div>${t("tool_get_logs")}</div>
                <div class="tool-item"><div class="tool-name">get_device_info</div>${t("tool_get_device_info")}</div>
                <div class="tool-item"><div class="tool-name">get_webdav_config</div>${t("tool_get_webdav_config")}</div>
                <div class="tool-item"><div class="tool-name">set_webdav_config</div>${t("tool_set_webdav_config")}</div>
                <div class="tool-item"><div class="tool-name">list_backups</div>${t("tool_list_backups")}</div>
                <div class="tool-item"><div class="tool-name">backup_now</div>${t("tool_backup_now")}</div>
                <div class="tool-item"><div class="tool-name">restore_backup</div>${t("tool_restore_backup")}</div>
                <div class="tool-item"><div class="tool-name">list_subscriptions</div>${t("tool_list_subscriptions")}</div>
                <div class="tool-item"><div class="tool-name">sync_subscriptions</div>${t("tool_sync_subscriptions")}</div>
                <div class="tool-item"><div class="tool-name">import_profiles</div>${t("tool_import_profiles")}</div>
                <div class="tool-item"><div class="tool-name">export_profiles</div>${t("tool_export_profiles")}</div>
            </div>
        </div>
    </div>
<script>
(function(){
  var root=document.documentElement, btn=document.getElementById('theme-btn');
  function upd(){ var t=root.getAttribute('data-theme')||'dark'; if(btn) btn.textContent=(t==='light')?'☀️':'🌙'; }
  upd();
  window.toggleTheme=function(){
    var cur=root.getAttribute('data-theme')||'dark';
    var next=(cur==='light')?'dark':'light';
    root.setAttribute('data-theme',next);
    localStorage.setItem('mcp_theme',next);
    upd();
  };
})();
</script>
</body>
</html>
        """.trimIndent()
    }

    /**
     * MCP Server Internationalization (i18n) Engine.
     */
    object McpI18n {
        fun resolveLanguage(call: ApplicationCall? = null, context: Context? = null): String {
            val queryLang = call?.request?.queryParameters?.get("lang")?.lowercase()
            if (!queryLang.isNullOrBlank()) {
                if (queryLang.startsWith("zh")) return "zh"
                if (queryLang.startsWith("ja")) return "ja"
                if (queryLang.startsWith("de")) return "de"
                if (queryLang.startsWith("fr")) return "fr"
                if (queryLang.startsWith("en")) return "en"
            }

            val acceptLang = call?.request?.header("Accept-Language")?.lowercase()
            if (!acceptLang.isNullOrBlank()) {
                val tags = acceptLang.split(",").map { it.split(";").first().trim() }
                for (tag in tags) {
                    if (tag.startsWith("zh")) return "zh"
                    if (tag.startsWith("ja")) return "ja"
                    if (tag.startsWith("de")) return "de"
                    if (tag.startsWith("fr")) return "fr"
                    if (tag.startsWith("en")) return "en"
                }
            }

            val sysLang = context?.resources?.configuration?.locales?.get(0)?.language
                ?: java.util.Locale.getDefault().language
            return when {
                sysLang.startsWith("zh") -> "zh"
                sysLang.startsWith("ja") -> "ja"
                sysLang.startsWith("de") -> "de"
                sysLang.startsWith("fr") -> "fr"
                else -> "en"
            }
        }

        fun get(key: String, lang: String): String {
            return STRINGS[lang]?.get(key) ?: STRINGS["en"]?.get(key) ?: key
        }

        private val STRINGS: Map<String, Map<String, String>> = mapOf(
            "en" to mapOf(
                "title" to "Stun Model Context Protocol (MCP) & Gemini Server",
                "badge_online" to "ONLINE",
                "server_desc" to "Dual HTTP / HTTPS JSON-RPC 2.0 over Server-Sent Events (SSE) & Direct Gemini Function Calling for AI Agents.",
                "connected_sse" to "Streamable HTTP Endpoint",
                "protocol_mode" to "Protocol Mode",
                "single_port_mux" to "Single-Port Multiplexed",
                "gemini_schema" to "Gemini Tools Schema",
                "download_cert" to "📥 Download SSL CA Certificate (stun_ca.crt)",
                "security_title" to "MCP Security & Authentication",
                "auth_none" to "🟢 None (Open / Local Access)",
                "auth_api_key" to "🔑 API Key / Bearer Token",
                "auth_basic" to "👤 HTTP Basic (User/Password)",
                "auth_oauth" to "🛡️ OAuth 2.0 (Bearer Token)",
                "desc_none" to "No authentication required. AI agents on the same network can connect directly.",
                "desc_api_key" to "Protected by API Key. Provide header <code>Authorization: Bearer &lt;key&gt;</code> or query <code>?token=&lt;key&gt;</code>.",
                "desc_basic" to "Protected by HTTP Basic Auth. Provide header <code>Authorization: Basic base64(user:pass)</code>.",
                "desc_oauth" to "Protected by OAuth 2.0. Acquire token via <code>POST /token</code> and provide header <code>Authorization: Bearer &lt;token&gt;</code>.",
                "token_endpoint" to "OAuth 2.0 Token Endpoint",
                "claude_mobile_title" to "Claude Mobile App (iOS / Android) MCP Setup",
                "claude_mobile_steps" to "In Claude App Settings &gt; Integrations / MCP &gt; Add MCP Server:",
                "server_name" to "Server Name",
                "server_url" to "Server URL",
                "claude_mobile_tip" to "Tip: If using self-signed certificate, download and install <code>stun_ca.crt</code> into your system trusted certificates, or connect within same Wi-Fi / ADB reverse tunnel.",
                "desktop_title" to "Claude Desktop / Cursor MCP Configuration",
                "desktop_hint" to "Add this HTTP server to Claude Code or Codex:",
                "gemini_title" to "Google Gemini Python SDK / AI Studio Integration",
                "gemini_hint" to "Connect Gemini 2.0 Flash / 1.5 Pro directly to Stun on your phone with 3 lines of Python:",
                "tools_title" to "Available AI Tools & Capabilities",
                "tool_get_vpn_status" to "Live metrics & status",
                "tool_start_vpn" to "Start VPN tunnel",
                "tool_stop_vpn" to "Disconnect VPN",
                "tool_restart_vpn" to "Restart tunnel",
                "tool_list_profiles" to "List all nodes",
                "tool_get_profile_detail" to "Detailed node params",
                "tool_create_profile" to "Create custom node",
                "tool_update_profile" to "Update node fields",
                "tool_delete_profile" to "Delete node",
                "tool_select_profile" to "Switch active node",
                "tool_test_node_latency" to "Test node ping/RTT",
                "tool_get_app_filter_list" to "List installed apps",
                "tool_set_app_filter" to "Configure split-tunnel",
                "tool_update_geodata" to "Update GeoIP/Geosite",
                "tool_get_settings" to "Get global settings",
                "tool_set_settings" to "Update settings",
                "tool_get_logs" to "Query runtime logs",
                "tool_get_device_info" to "Hardware & OS info",
                "tool_get_webdav_config" to "Read WebDAV backup config",
                "tool_set_webdav_config" to "Configure WebDAV backup",
                "tool_list_backups" to "List cloud backups",
                "tool_backup_now" to "Backup now (encrypt & upload)",
                "tool_restore_backup" to "Restore from a backup",
                "tool_list_subscriptions" to "List subscriptions",
                "tool_sync_subscriptions" to "Sync all subscriptions",
                "tool_import_profiles" to "Import nodes (stun:// / JSON)",
                "tool_export_profiles" to "Export nodes (encrypted)",
                "oauth_title" to "Stun MCP Authorization Request",
                "oauth_prompt" to "External client <span class=\"client-tag\">%s</span> is requesting access to control Stun VPN on this device.",
                "oauth_approve" to "✅ Approve & Connect",
                "oauth_cancel" to "Cancel",
                "oauth_code" to "Authorization Code"
            ),
            "zh" to mapOf(
                "title" to "Stun 全功能 MCP & Gemini 本地控制服务",
                "badge_online" to "运行中",
                "server_desc" to "支持通过 SSE 传输的 HTTP/HTTPS JSON-RPC 2.0 协议以及 Gemini Function Calling 原生函数调用。",
                "connected_sse" to "Streamable HTTP 端点",
                "protocol_mode" to "通信协议模式",
                "single_port_mux" to "单端口嗅探多路复用",
                "gemini_schema" to "Gemini 函数声明元数据",
                "download_cert" to "📥 下载 SSL 根证书 (stun_ca.crt)",
                "security_title" to "MCP 安全认证模式",
                "auth_none" to "🟢 无需验证 (本地/局域网开放)",
                "auth_api_key" to "🔑 API Key 密钥验证",
                "auth_basic" to "👤 HTTP 基础账号密码验证",
                "auth_oauth" to "🛡️ OAuth 2.0 授权机制",
                "desc_none" to "未开启访问控制，同一局域网内的 AI Agent 可直接无阻连接。",
                "desc_api_key" to "受 API Key 保护，请在请求头携带 <code>Authorization: Bearer &lt;key&gt;</code> 或参数 <code>?token=&lt;key&gt;</code>。",
                "desc_basic" to "受 HTTP Basic 认证保护，请携带 <code>Authorization: Basic base64(user:pass)</code>。",
                "desc_oauth" to "受 OAuth 2.0 保护，请通过 <code>POST /token</code> 获取令牌并在请求头携带 <code>Authorization: Bearer &lt;token&gt;</code>。",
                "token_endpoint" to "OAuth 2.0 令牌获取端点",
                "claude_mobile_title" to "Claude 手机客户端 (iOS / Android) MCP 接入",
                "claude_mobile_steps" to "在 Claude App 设置 &gt; 扩展与集成 (Integrations / MCP) &gt; 添加 MCP Server：",
                "server_name" to "服务器名称",
                "server_url" to "服务器地址",
                "claude_mobile_tip" to "提示：如使用自签名证书，请下载并安装 <code>stun_ca.crt</code> 至系统受信任证书列表，或在同一 Wi-Fi / ADB 反向代理环境下使用。",
                "desktop_title" to "Claude Desktop / Cursor / Windsurf 桌面配置",
                "desktop_hint" to "将此 HTTP 服务添加到 Claude Code 或 Codex：",
                "gemini_title" to "Google Gemini Python SDK / AI Studio 快速接入",
                "gemini_hint" to "只需 3 行 Python 代码即可将 Gemini 2.0 Flash / 1.5 Pro 直接连接至手机并控制 VPN：",
                "tools_title" to "已注册可调用的 AI 工具与能力",
                "tool_get_vpn_status" to "实时运行状态与流量统计",
                "tool_start_vpn" to "启动 VPN 隧道",
                "tool_stop_vpn" to "断开 VPN 隧道",
                "tool_restart_vpn" to "重启 VPN 隧道",
                "tool_list_profiles" to "查询所有节点列表",
                "tool_get_profile_detail" to "查询节点详细配置",
                "tool_create_profile" to "创建自定义节点",
                "tool_update_profile" to "更新节点字段",
                "tool_delete_profile" to "删除指定节点",
                "tool_select_profile" to "切换当前活跃节点",
                "tool_test_node_latency" to "测试节点延迟与连通性",
                "tool_get_app_filter_list" to "获取分应用代理列表",
                "tool_set_app_filter" to "配置分应用分流规则",
                "tool_update_geodata" to "更新 GeoIP/Geosite 规则库",
                "tool_get_settings" to "获取全局高级设置",
                "tool_set_settings" to "修改全局配置参数",
                "tool_get_logs" to "检索运行时日志",
                "tool_get_device_info" to "系统与硬件设备详情",
                "tool_get_webdav_config" to "查看 WebDAV 备份配置",
                "tool_set_webdav_config" to "配置 WebDAV 云备份",
                "tool_list_backups" to "列出云端备份版本",
                "tool_backup_now" to "立即备份（加密上传）",
                "tool_restore_backup" to "从备份恢复",
                "tool_list_subscriptions" to "列出订阅",
                "tool_sync_subscriptions" to "同步所有订阅",
                "tool_import_profiles" to "导入节点（stun:// / JSON）",
                "tool_export_profiles" to "导出节点（加密）",
                "oauth_title" to "Stun MCP 授权请求",
                "oauth_prompt" to "外部应用 <span class=\"client-tag\">%s</span> 正在申请连接并控制本机的 Stun VPN 节点与服务。",
                "oauth_approve" to "✅ 允许授权并连接 (Approve & Connect)",
                "oauth_cancel" to "取消授权",
                "oauth_code" to "授权码 (Code)"
            ),
            "ja" to mapOf(
                "title" to "Stun 高機能 MCP & Gemini サーバー",
                "badge_online" to "稼働中",
                "server_desc" to "SSE 経由の HTTP/HTTPS JSON-RPC 2.0 および Gemini 関数呼び出しに対応した AI 連携サーバー。",
                "connected_sse" to "Streamable HTTP エンドポイント",
                "protocol_mode" to "プロトコルモード",
                "single_port_mux" to "単一ポート多重化",
                "gemini_schema" to "Gemini ツールスキーマ",
                "download_cert" to "📥 SSL CA 証明書をダウンロード (stun_ca.crt)",
                "security_title" to "MCP セキュリティと認証",
                "auth_none" to "🟢 認証なし (ローカル/LAN開放)",
                "auth_api_key" to "🔑 API キー認証",
                "auth_basic" to "👤 HTTP 基本認証 (ユーザー/パスワード)",
                "auth_oauth" to "🛡️ OAuth 2.0 認証",
                "desc_none" to "認証は不要です。同一ネットワーク内の AI が直接接続できます。",
                "desc_api_key" to "API キー保護。ヘッダー <code>Authorization: Bearer &lt;key&gt;</code> を設定してください。",
                "desc_basic" to "HTTP 基本認証保護。<code>Authorization: Basic base64(user:pass)</code> を設定してください。",
                "desc_oauth" to "OAuth 2.0 保護。<code>POST /token</code> でトークンを取得してください。",
                "token_endpoint" to "OAuth 2.0 トークンエンドポイント",
                "claude_mobile_title" to "Claude モバイルアプリ (iOS / Android) 設定",
                "claude_mobile_steps" to "Claude アプリの設定 &gt; Integrations / MCP &gt; MCP サーバーを追加：",
                "server_name" to "サーバー名",
                "server_url" to "サーバー URL",
                "claude_mobile_tip" to "ヒント: 自己署名証明書の場合は <code>stun_ca.crt</code> をインストールしてください。",
                "desktop_title" to "Claude Desktop / Cursor MCP 設定",
                "desktop_hint" to "この HTTP サーバーを Claude Code または Codex に追加してください：",
                "gemini_title" to "Google Gemini Python SDK 連携",
                "gemini_hint" to "わずか 3 行の Python コードで Gemini から VPN を直接操作できます：",
                "tools_title" to "利用可能な AI ツールと機能",
                "tool_get_vpn_status" to "稼働状態とトラフィック統計",
                "tool_start_vpn" to "VPN トンネル起動",
                "tool_stop_vpn" to "VPN 切断",
                "tool_restart_vpn" to "VPN 再起動",
                "tool_list_profiles" to "ノード一覧取得",
                "tool_get_profile_detail" to "ノード詳細設定",
                "tool_create_profile" to "ノード新規作成",
                "tool_update_profile" to "ノード設定更新",
                "tool_delete_profile" to "ノード削除",
                "tool_select_profile" to "ノード切り替え",
                "tool_test_node_latency" to "遅延・接続テスト",
                "tool_get_app_filter_list" to "アプリ分流一覧",
                "tool_set_app_filter" to "アプリ分流設定",
                "tool_update_geodata" to "GeoIP/Geosite 更新",
                "tool_get_settings" to "全体設定取得",
                "tool_set_settings" to "全体設定更新",
                "tool_get_logs" to "稼働ログ検索",
                "tool_get_device_info" to "デバイス・システム情報",
                "tool_get_webdav_config" to "WebDAV バックアップ設定の取得",
                "tool_set_webdav_config" to "WebDAV バックアップ設定",
                "tool_list_backups" to "クラウドバックアップ一覧",
                "tool_backup_now" to "今すぐバックアップ (暗号化)",
                "tool_restore_backup" to "バックアップから復元",
                "tool_list_subscriptions" to "サブスクリプション一覧",
                "tool_sync_subscriptions" to "サブスクを同期",
                "tool_import_profiles" to "ノードインポート (stun:// / JSON)",
                "tool_export_profiles" to "ノードエクスポート (暗号化)",
                "oauth_title" to "Stun MCP 認証リクエスト",
                "oauth_prompt" to "外部クライアント <span class=\"client-tag\">%s</span> がこのデバイスの Stun VPN 制御を要求しています。",
                "oauth_approve" to "✅ 承認して接続 (Approve & Connect)",
                "oauth_cancel" to "キャンセル",
                "oauth_code" to "認証コード (Code)"
            ),
            "de" to mapOf(
                "title" to "Stun Model Context Protocol (MCP) & Gemini Server",
                "badge_online" to "ONLINE",
                "server_desc" to "Dual HTTP/HTTPS JSON-RPC 2.0 über Server-Sent Events (SSE) & natives Gemini Function Calling für KI-Agenten.",
                "connected_sse" to "Streamable HTTP-Endpunkt",
                "protocol_mode" to "Protokollmodus",
                "single_port_mux" to "Einzelport-Multiplexing",
                "gemini_schema" to "Gemini-Werkzeug-Schema",
                "download_cert" to "📥 SSL-CA-Zertifikat herunterladen (stun_ca.crt)",
                "security_title" to "MCP-Sicherheit & Authentifizierung",
                "auth_none" to "🟢 Keine (offen / lokaler Zugriff)",
                "auth_api_key" to "🔑 API-Schlüssel / Bearer-Token",
                "auth_basic" to "👤 HTTP Basic (Benutzer/Passwort)",
                "auth_oauth" to "🛡️ OAuth 2.0 (Bearer-Token)",
                "desc_none" to "Keine Authentifizierung erforderlich. KI-Agenten im selben Netzwerk verbinden sich direkt.",
                "desc_api_key" to "Geschützt per API-Schlüssel. Header <code>Authorization: Bearer &lt;key&gt;</code> oder Query <code>?token=&lt;key&gt;</code> angeben.",
                "desc_basic" to "Geschützt per HTTP Basic. Header <code>Authorization: Basic base64(user:pass)</code> angeben.",
                "desc_oauth" to "Geschützt per OAuth 2.0. Token über <code>POST /token</code> holen und Header <code>Authorization: Bearer &lt;token&gt;</code> senden.",
                "token_endpoint" to "OAuth 2.0 Token-Endpunkt",
                "claude_mobile_title" to "Claude Mobile App (iOS / Android) MCP-Einrichtung",
                "claude_mobile_steps" to "In den Claude-App-Einstellungen &gt; Integrationen / MCP &gt; MCP-Server hinzufügen:",
                "server_name" to "Servername",
                "server_url" to "Server-URL",
                "claude_mobile_tip" to "Tipp: Bei selbstsigniertem Zertifikat <code>stun_ca.crt</code> in die vertrauenswürdigen Systemzertifikate importieren, oder im selben WLAN / ADB-Reverse-Tunnel verbinden.",
                "desktop_title" to "Claude Desktop / Cursor MCP-Konfiguration",
                "desktop_hint" to "Diesen HTTP-Server zu Claude Code oder Codex hinzufügen:",
                "gemini_title" to "Google Gemini Python SDK / AI Studio Integration",
                "gemini_hint" to "Verbinde Gemini 2.0 Flash / 1.5 Pro mit drei Zeilen Python direkt mit Stun auf dem Telefon:",
                "tools_title" to "Verfügbare KI-Werkzeuge & Fähigkeiten",
                "tool_get_vpn_status" to "Live-Status & Statistik",
                "tool_start_vpn" to "VPN-Tunnel starten",
                "tool_stop_vpn" to "VPN trennen",
                "tool_restart_vpn" to "Tunnel neu starten",
                "tool_list_profiles" to "Alle Knoten auflisten",
                "tool_get_profile_detail" to "Knoten-Details",
                "tool_create_profile" to "Knoten erstellen",
                "tool_update_profile" to "Knoten aktualisieren",
                "tool_delete_profile" to "Knoten löschen",
                "tool_select_profile" to "Aktiven Knoten wechseln",
                "tool_test_node_latency" to "Knoten-Latenz testen",
                "tool_get_app_filter_list" to "Installierte Apps auflisten",
                "tool_set_app_filter" to "Split-Tunnel konfigurieren",
                "tool_update_geodata" to "GeoIP/Geosite aktualisieren",
                "tool_get_settings" to "Globale Einstellungen lesen",
                "tool_set_settings" to "Einstellungen ändern",
                "tool_get_logs" to "Laufzeit-Logs abfragen",
                "tool_get_device_info" to "Hardware- & Systeminfo",
                "tool_get_webdav_config" to "WebDAV-Backup-Konfig lesen",
                "tool_set_webdav_config" to "WebDAV-Backup einrichten",
                "tool_list_backups" to "Cloud-Backups auflisten",
                "tool_backup_now" to "Jetzt sichern (verschlüsseln & hochladen)",
                "tool_restore_backup" to "Aus Backup wiederherstellen",
                "tool_list_subscriptions" to "Abos auflisten",
                "tool_sync_subscriptions" to "Alle Abos synchronisieren",
                "tool_import_profiles" to "Knoten importieren (stun:// / JSON)",
                "tool_export_profiles" to "Knoten exportieren (verschlüsselt)",
                "oauth_title" to "Stun MCP-Autorisierungsanfrage",
                "oauth_prompt" to "Externer Client <span class=\"client-tag\">%s</span> fordert Zugriff zur Steuerung von Stun VPN auf diesem Gerät an.",
                "oauth_approve" to "✅ Genehmigen & Verbinden",
                "oauth_cancel" to "Abbrechen",
                "oauth_code" to "Autorisierungscode"
            ),
            "fr" to mapOf(
                "title" to "Serveur Stun Model Context Protocol (MCP) & Gemini",
                "badge_online" to "EN LIGNE",
                "server_desc" to "JSON-RPC 2.0 HTTP/HTTPS dual via Server-Sent Events (SSE) et Function Calling natif Gemini pour agents IA.",
                "connected_sse" to "Point de terminaison HTTP diffusable",
                "protocol_mode" to "Mode de protocole",
                "single_port_mux" to "Multiplexage sur port unique",
                "gemini_schema" to "Schéma des outils Gemini",
                "download_cert" to "📥 Télécharger le certificat CA SSL (stun_ca.crt)",
                "security_title" to "Sécurité & authentification MCP",
                "auth_none" to "🟢 Aucune (ouvert / accès local)",
                "auth_api_key" to "🔑 Clé API / Jeton Bearer",
                "auth_basic" to "👤 HTTP Basic (utilisateur/mot de passe)",
                "auth_oauth" to "🛡️ OAuth 2.0 (jeton Bearer)",
                "desc_none" to "Aucune authentification requise. Les agents IA du même réseau se connectent directement.",
                "desc_api_key" to "Protégé par clé API. Fournir l'en-tête <code>Authorization: Bearer &lt;key&gt;</code> ou le paramètre <code>?token=&lt;key&gt;</code>.",
                "desc_basic" to "Protégé par HTTP Basic. Fournir l'en-tête <code>Authorization: Basic base64(user:pass)</code>.",
                "desc_oauth" to "Protégé par OAuth 2.0. Obtenir un jeton via <code>POST /token</code> puis envoyer <code>Authorization: Bearer &lt;token&gt;</code>.",
                "token_endpoint" to "Point de terminaison de jeton OAuth 2.0",
                "claude_mobile_title" to "Configuration MCP pour l'app mobile Claude (iOS / Android)",
                "claude_mobile_steps" to "Dans les réglages de l'app Claude &gt; Intégrations / MCP &gt; Ajouter un serveur MCP :",
                "server_name" to "Nom du serveur",
                "server_url" to "URL du serveur",
                "claude_mobile_tip" to "Astuce : avec un certificat auto-signé, téléchargez et installez <code>stun_ca.crt</code> dans les certificats de confiance, ou connectez-vous sur le même Wi-Fi / tunnel ADB reverse.",
                "desktop_title" to "Configuration Claude Desktop / Cursor MCP",
                "desktop_hint" to "Ajouter ce serveur HTTP à Claude Code ou Codex :",
                "gemini_title" to "Intégration SDK Python / AI Studio Google Gemini",
                "gemini_hint" to "Connectez Gemini 2.0 Flash / 1.5 Pro directement à Stun sur votre téléphone en 3 lignes de Python :",
                "tools_title" to "Outils IA et capacités disponibles",
                "tool_get_vpn_status" to "État et statistiques en direct",
                "tool_start_vpn" to "Démarrer le tunnel VPN",
                "tool_stop_vpn" to "Couper le VPN",
                "tool_restart_vpn" to "Redémarrer le tunnel",
                "tool_list_profiles" to "Lister tous les nœuds",
                "tool_get_profile_detail" to "Détails d'un nœud",
                "tool_create_profile" to "Créer un nœud",
                "tool_update_profile" to "Mettre à jour un nœud",
                "tool_delete_profile" to "Supprimer un nœud",
                "tool_select_profile" to "Changer de nœud actif",
                "tool_test_node_latency" to "Tester la latence d'un nœud",
                "tool_get_app_filter_list" to "Lister les applications installées",
                "tool_set_app_filter" to "Configurer le split-tunnel",
                "tool_update_geodata" to "Mettre à jour GeoIP/Geosite",
                "tool_get_settings" to "Lire les réglages globaux",
                "tool_set_settings" to "Modifier les réglages",
                "tool_get_logs" to "Interroger les journaux",
                "tool_get_device_info" to "Infos matériel & système",
                "tool_get_webdav_config" to "Lire la config de sauvegarde WebDAV",
                "tool_set_webdav_config" to "Configurer la sauvegarde WebDAV",
                "tool_list_backups" to "Lister les sauvegardes cloud",
                "tool_backup_now" to "Sauvegarder maintenant (chiffrer & téléverser)",
                "tool_restore_backup" to "Restaurer depuis une sauvegarde",
                "tool_list_subscriptions" to "Lister les abonnements",
                "tool_sync_subscriptions" to "Synchroniser les abonnements",
                "tool_import_profiles" to "Importer des nœuds (stun:// / JSON)",
                "tool_export_profiles" to "Exporter des nœuds (chiffré)",
                "oauth_title" to "Demande d'autorisation Stun MCP",
                "oauth_prompt" to "Le client externe <span class=\"client-tag\">%s</span> demande l'accès pour contrôler Stun VPN sur cet appareil.",
                "oauth_approve" to "✅ Approuver & connecter",
                "oauth_cancel" to "Annuler",
                "oauth_code" to "Code d'autorisation"
            )
        )
    }

    /** JSON array/string → Profile list（import_profiles 与 import_from_content 共用）。 */
    private fun parseProfilesFromJson(json: String): List<Profile> = try {
        val type = object : com.google.gson.reflect.TypeToken<List<Profile>>() {}.type
        Gson().fromJson<List<Profile>>(json, type).orEmpty()
    } catch (_: Exception) {
        try {
            listOfNotNull(Gson().fromJson(json, Profile::class.java))
        } catch (_: Exception) {
            emptyList()
        }
    }
}
