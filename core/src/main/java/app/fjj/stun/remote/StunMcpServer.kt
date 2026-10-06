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
import app.fjj.stun.repo.ProfileSecrets
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
    /**
     * 只读出口用的 Gson：凡是把 `Profile` 序列化出去（`get_profile_detail` 工具、
     * `stun://profiles` 资源）都会把凭据字段换成 [ProfileSecrets.MASK]，不再把
     * SSH 密码 / PEM 私钥 / 代理 token / 隧道 PSK 明文送进 AI 客户端的会话上下文。
     *
     * 需要真值的 `export_profiles` 用的是独立 `Gson()` 实例（加密后再给出去），不受影响。
     */
    private val gson = ProfileSecrets.redactingGson()
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

    /**
     * 配置片段里的 `Authorization` 头。[maskSecrets] 决定 API Key 给真值还是 `*****`。
     *
     * BASIC / OAUTH 两种模式本来就没法写死静态凭据（前者要现场 base64 用户名密码、
     * 后者要运行时换取 access token），一直用的占位符 —— 只有 API_KEY 模式会把
     * 真实密钥印进配置文本，所以只有它需要掩码分支。
     */
    private fun buildAuthorizationHeader(authMode: Int, apiKey: String, maskSecrets: Boolean): String? =
        when (authMode) {
            SettingsManager.MCP_AUTH_MODE_API_KEY ->
                apiKey.takeIf { it.isNotBlank() }
                    ?.let { "Bearer ${if (maskSecrets) ProfileSecrets.MASK else it}" }
            SettingsManager.MCP_AUTH_MODE_BASIC -> "Basic <BASE64_USER_PASS>"
            SettingsManager.MCP_AUTH_MODE_OAUTH -> "Bearer <OAUTH_ACCESS_TOKEN>"
            else -> null
        }

    /**
     * 生成 Claude Code 的 `.mcp.json` 片段。
     *
     * [maskSecrets] 为 true 时把 API Key 换成 `*****`：**页面渲染一律走这条**
     * （控制台页面在局域网可达，明文 key 会随截屏/围观外流）；
     * App 内"分享配置"要的是能直接粘贴使用的真值，传 false。
     */
    fun getClaudeConfigJson(
        context: Context? = null,
        scheme: String = "http",
        host: String? = null,
        maskSecrets: Boolean = false
    ): String {
        val targetHost = host ?: "${getLocalIpAddress()}:$serverPort"
        val authMode = context?.let { SettingsManager.getMcpAuthMode(it) } ?: SettingsManager.MCP_AUTH_MODE_NONE
        val apiKey = context?.let { SettingsManager.getMcpApiKey(it) } ?: ""

        val authorization = buildAuthorizationHeader(authMode, apiKey, maskSecrets)
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

    /** Codex `config.toml` 片段。掩码语义同 [getClaudeConfigJson]。 */
    fun getCodexConfigToml(
        context: Context? = null,
        scheme: String = "http",
        host: String? = null,
        maskSecrets: Boolean = false
    ): String {
        val targetHost = host ?: "${getLocalIpAddress()}:$serverPort"
        val authMode = context?.let { SettingsManager.getMcpAuthMode(it) } ?: SettingsManager.MCP_AUTH_MODE_NONE
        val apiKey = context?.let { SettingsManager.getMcpApiKey(it) } ?: ""
        fun tomlString(value: String): String = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")

        val authorization = buildAuthorizationHeader(authMode, apiKey, maskSecrets)
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

    // ─── Complete Tools Definition (27 Tools) ───

    /** 工具清单已抽到 [McpTools]（纯 Gson 依赖，可单独读与测）。这里只做委托。 */
    private fun handleToolsList(): JsonObject = McpTools.toolsList()
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
                // 字段映射已抽到 [McpProfileArgs]（纯函数，不碰 Context/ProfileManager）。
                // 掩码剔除在 fromArgs 内部完成，这里只负责落库与回报。
                val profile = McpProfileArgs.fromArgs(args)
                ProfileManager.addProfile(context, profile)
                "Profile created successfully: 「${profile.name}」 (ID: ${profile.id})"
            }

            "update_profile" -> {
                // 同上；字段映射抽到 [McpProfileArgs.mergeInto]，落库仍留在 server 侧。
                val profileId = args.get("profileId")?.asString ?: ""
                val existing = ProfileManager.getProfileById(context, profileId)
                if (existing == null) {
                    "Error: Profile ID not found: $profileId"
                } else {
                    McpProfileArgs.mergeInto(args, existing)
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
                        // 分段耗时（Go 侧 v? 起提供）：握手含 TCP+KEX+认证，
                        // 与隧道内 HTTP RTT 分开，便于判断"慢在握手还是慢在链路"。
                        // 老版本 Go 侧返回 -1，用 hasBreakdown 语义标注有效性。
                        addProperty("handshakeMs", r.handshakeMs)
                        addProperty("httpMs", r.httpMs)
                        // 必须是 > 0：ok 但延迟为 0/缺省时标 OK 会误导调用方以为
                        // 节点可达且极快，实际是"没测到"。
                        addProperty("status", if (r.ok && r.latencyMs > 0) "OK" else "TIMEOUT/ERROR")
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
                    addProperty("udpMaxSessions", SettingsManager.getUdpMaxSessions(context))
                    addProperty("udpIdleTimeoutSec", SettingsManager.getUdpIdleTimeoutSec(context))
                    addProperty("geositeUrl", SettingsManager.getGeositeUrl(context))
                    addProperty("geoipUrl", SettingsManager.getGeoipUrl(context))
                    addProperty("geositeDirect", SettingsManager.getGeositeDirect(context))
                    addProperty("geoipDirect", SettingsManager.getGeoipDirect(context))
                    addProperty("updateInterval", SettingsManager.getUpdateInterval(context))
                    addProperty("showNotificationSpeed", SettingsManager.getShowNotificationSpeed(context))
                    addProperty("filterMode", SettingsManager.getFilterMode(context))
                    addProperty("filterApps", SettingsManager.getFilterApps(context))
                    addProperty("mcpServerPort", SettingsManager.getMcpServerPort(context))
                }
                gson.toJson(settings)
            }

            "set_settings" -> {
                // 字段面与 WebUI 设置页的全局隧道设置一致（DNS / UDPGW / 会话限制 / Geo /
                // 日志 / 通知）。**刻意不含**认证与控制台字段（web authMode/customToken、
                // mcp auth）—— 那是访问控制，交给 WebUI 的 token 轮换流程，不能被智能体改掉
                // （改 mcpAuthSecret 等于把当前会话自己锁在外面）。过滤分流走专用的
                // set_app_filter，这里不重复开写入口。
                if (args.has("remoteDns")) SettingsManager.saveRemoteDnsServer(context, args.get("remoteDns").asString)
                if (args.has("localDns")) SettingsManager.saveLocalDnsServer(context, args.get("localDns").asString)
                if (args.has("serviceMode")) SettingsManager.saveServiceMode(context, args.get("serviceMode").asInt)
                if (args.has("logLevel")) SettingsManager.saveLogLevel(context, args.get("logLevel").asString)
                if (args.has("udpgwVersion")) SettingsManager.saveUdpgwVersion(context, args.get("udpgwVersion").asString)
                if (args.has("udpgwAddr")) SettingsManager.saveUdpgwAddr(context, args.get("udpgwAddr").asString)
                // 0 = 引擎默认（1024 会话 / 60 秒）；saveXxx 内部已按范围钳位
                if (args.has("udpMaxSessions")) SettingsManager.saveUdpMaxSessions(context, args.get("udpMaxSessions").asInt)
                if (args.has("udpIdleTimeoutSec")) SettingsManager.saveUdpIdleTimeoutSec(context, args.get("udpIdleTimeoutSec").asInt)
                if (args.has("geositeUrl")) SettingsManager.saveGeositeUrl(context, args.get("geositeUrl").asString)
                if (args.has("geoipUrl")) SettingsManager.saveGeoipUrl(context, args.get("geoipUrl").asString)
                if (args.has("geositeDirect")) SettingsManager.saveGeositeDirect(context, args.get("geositeDirect").asString)
                if (args.has("geoipDirect")) SettingsManager.saveGeoipDirect(context, args.get("geoipDirect").asString)
                if (args.has("updateInterval")) SettingsManager.saveUpdateInterval(context, args.get("updateInterval").asLong)
                if (args.has("showNotificationSpeed")) SettingsManager.saveShowNotificationSpeed(context, args.get("showNotificationSpeed").asBoolean)
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
                    // 同步模式：给机器读的一律用稳定 id（upload/download/both），不做本地化
                    addProperty("syncMode", SettingsManager.getWebDavSyncMode(context).id)
                    addProperty("lastSync", SettingsManager.getWebDavLastSyncTime(context))
                    // 前缀 / 同步范围：同样给稳定 id，供机器判断"哪些分区在云端"
                    addProperty("prefix", SettingsManager.getWebDavPrefix(context))
                    add("sections", gson.toJsonTree(SettingsManager.getWebDavSyncSections(context).toList()))
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
                // 换模式＝换方向，作废"已推过兜底快照"标记：新模式的第一次仍要先留后路
                args.get("syncMode")?.asString?.let { raw ->
                    val mode = app.fjj.stun.backup.WebDavSyncMode.fromId(raw)
                    if (mode != SettingsManager.getWebDavSyncMode(context)) {
                        SettingsManager.saveWebDavSyncMode(context, mode)
                        SettingsManager.setWebDavSyncBootstrapped(context, false)
                    }
                }
                // 前缀："" 是有效的"清空"，不能像 pass/pin 那样用 ?: 回落到旧值
                args.get("prefix")?.asString?.let { SettingsManager.saveWebDavPrefix(context, it) }
                // 同步范围：缺字段＝保持现状；给了数组（含空数组）＝按勾选落库
                args.get("sections")?.asJsonArray?.let { arr ->
                    val ids = buildSet { for (e in arr) (e.asJsonPrimitive?.asString)?.let(::add) }
                    SettingsManager.saveWebDavSyncSections(context, ids)
                }
                WebDavBackupWorker.schedule(context)
                val cfg = JsonObject().apply {
                    addProperty("url", SettingsManager.getWebDavUrl(context))
                    addProperty("user", SettingsManager.getWebDavUser(context))
                    addProperty("auto", SettingsManager.isWebDavAutoBackupEnabled(context))
                    addProperty("intervalHours", SettingsManager.getWebDavBackupIntervalHours(context))
                    addProperty("syncMode", SettingsManager.getWebDavSyncMode(context).id)
                    addProperty("prefix", SettingsManager.getWebDavPrefix(context))
                    add("sections", gson.toJsonTree(SettingsManager.getWebDavSyncSections(context).toList()))
                }
                "WebDAV config saved. " + gson.toJson(cfg)
            }

            "list_backups" -> {
                val config = app.fjj.stun.backup.WebDavBackupManager.Config(
                    url = SettingsManager.getWebDavUrl(context),
                    user = SettingsManager.getWebDavUser(context),
                    pass = SettingsManager.getWebDavPass(context),
                    pin = SettingsManager.getWebDavPin(context),
                    prefix = SettingsManager.getWebDavPrefix(context),
                    sections = SettingsManager.getWebDavSyncSections(context)
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
                    pin = SettingsManager.getWebDavPin(context),
                    prefix = SettingsManager.getWebDavPrefix(context),
                    sections = SettingsManager.getWebDavSyncSections(context)
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

            "sync_now" -> {
                val config = app.fjj.stun.backup.WebDavBackupManager.Config(
                    url = SettingsManager.getWebDavUrl(context),
                    user = SettingsManager.getWebDavUser(context),
                    pass = SettingsManager.getWebDavPass(context),
                    pin = SettingsManager.getWebDavPin(context),
                    prefix = SettingsManager.getWebDavPrefix(context),
                    sections = SettingsManager.getWebDavSyncSections(context)
                )
                if (!config.isConfigured) "Error: WebDAV is not fully configured."
                else try {
                    val mode = args.get("mode")?.asString
                        ?.let { app.fjj.stun.backup.WebDavSyncMode.fromId(it) }
                        ?: SettingsManager.getWebDavSyncMode(context)
                    val result = app.fjj.stun.backup.WebDavBackupManager.sync(context, config, mode)
                    if (result.pushed) SettingsManager.saveWebDavLastBackupTime(context, System.currentTimeMillis())
                    // 同 backup_now：分区用稳定 id，不做本地化
                    "Sync(${mode.id}) complete: pulled=[${result.pulled.joinToString()}], " +
                        "pushed=${result.pushed}, nodes=${result.profiles}, bootstrapped=${result.bootstrapped}."
                } catch (e: Exception) {
                    "Sync failed: ${e.message}"
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
                        pin = SettingsManager.getWebDavPin(context),
                        prefix = SettingsManager.getWebDavPrefix(context),
                        sections = SettingsManager.getWebDavSyncSections(context)
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
                            // 本地订阅 id：调用方按它引用某一条订阅（URL 是可变的）。
                            addProperty("subId", sub.subId)
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
            addProperty("description", "List of all saved server nodes and protocols. Credentials are masked as \"*****\".")
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
                    pin = SettingsManager.getWebDavPin(context),
                    prefix = SettingsManager.getWebDavPrefix(context),
                    sections = SettingsManager.getWebDavSyncSections(context)
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
        val error: String,
        /** 握手耗时（TCP+KEX+认证）；-1 表示 Go 侧未提供分段数据。 */
        val handshakeMs: Long = -1L,
        /** 隧道内 HTTP 往返；-1 表示 Go 侧未提供分段数据。 */
        val httpMs: Long = -1L,
    )

    private fun testNodePingLatency(profile: Profile, context: Context): NodeLatency {
        val configJson = try {
            VpnConfigBuilder.buildMySshConfig(context, profile)
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
                    error = obj.optString("error", ""),
                    handshakeMs = obj.optLong("handshakeMs", -1L),
                    httpMs = obj.optLong("httpMs", -1L),
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

    /** HTML 模板已抽到 [McpDashboardHtml]（纯模板，零 Context 依赖）。这里只做取数与转义。 */
    private fun renderDashboardHtml(context: Context, call: ApplicationCall? = null): String {
        val lang = McpI18n.resolveLanguage(call, context)
        val baseUrl = call?.let { getBaseUrl(it) } ?: "http://${getLocalIpAddress()}:$serverPort"
        val scheme = call?.let { getRequestScheme(it) } ?: "http"
        val host = call?.request?.header("Host") ?: "${getLocalIpAddress()}:$serverPort"

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

        // 控制台页面走掩码版本：这是浏览器打开的页面，明文 API Key 落在 DOM 里
        // 就会随截屏 / 投屏 / 局域网访问外流。要可直接粘贴使用的完整配置，
        // 走 App 设置页的"分享配置"（本地、用户主动触发）。
        fun htmlEscape(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        return McpDashboardHtml.render(
            t = { key -> McpI18n.get(key, lang) },
            lang = lang,
            scheme = scheme,
            baseUrl = baseUrl,
            currentMcpUrl = "$baseUrl/mcp",
            geminiUrl = "$baseUrl/gemini/declarations",
            certUrl = "$baseUrl/mcp/cert",
            authModeName = authModeName,
            authDesc = authDesc,
            isOauth = authMode == SettingsManager.MCP_AUTH_MODE_OAUTH,
            configJson = htmlEscape(getClaudeConfigJson(context, scheme, host, maskSecrets = true)),
            codexConfigToml = htmlEscape(getCodexConfigToml(context, scheme, host, maskSecrets = true)),
            serverPort = serverPort,
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

