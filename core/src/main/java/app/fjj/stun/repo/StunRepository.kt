package app.fjj.stun.repo

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import androidx.lifecycle.MutableLiveData
import myssh.TunnelEventCallback
import app.fjj.stun.util.QualityCalculator
import app.fjj.stun.util.QualityScore
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object StunRepository {
    private const val MAX_LOG_SIZE = 100000
    private const val RATE_HISTORY_MAX = 24

    private val appLogBuilder = SpannableStringBuilder()
    private val tunnelLogBuilder = StringBuilder()

    // 当前会话起点（epochMs）。进入 CONNECTED 时记时，离开链路（断开/连接中/错误）时清零。
    // 单独拎出来是因为小组件「运行时长」需要一个“本次连接持续了多久”的起点，
    // 而 vpnState 是零散 postValue 的，靠调用方各自维护极易漏记。
    @Volatile
    private var sessionStartMs: Long = 0L

    /** 本次会话起点；0 表示当前无活动会话。供小组件「运行时长」使用。 */
    fun sessionStartedAt(): Long = sessionStartMs

    private fun trackSession(state: VpnState) {
        when (state) {
            // 首次进入已连接才记时；重连期间的 CONNECTED 不会重置起点，保证时长连续
            VpnState.CONNECTED -> if (sessionStartMs == 0L) sessionStartMs = System.currentTimeMillis()
            // 重连是从已连接滑出去的中间态，会话并未结束，保留起点
            VpnState.RECONNECTING -> Unit
            // 其余状态都意味着会话终结：显式断开、初次连接中、致命错误
            VpnState.DISCONNECTED, VpnState.CONNECTING, VpnState.ERROR -> sessionStartMs = 0L
        }
    }

    // 覆写写入点而非逐个改调用方：vpnState 在全工程有多处 postValue，
    // 集中在这里记账可以保证任何一处状态推进都被覆盖，不会漏。
    val vpnState = object : MutableLiveData<VpnState>(VpnState.DISCONNECTED) {
        override fun setValue(value: VpnState) {
            trackSession(value)
            super.setValue(value)
        }

        override fun postValue(value: VpnState) {
            trackSession(value)
            super.postValue(value)
        }
    }
    // 引擎上报的致命/连接错误，供主 UI 直接提示（解决“报错不知道”）
    val engineError = MutableLiveData<String?>(null)
    // 🌟 核心引擎崩溃/Panic 拦截事件（存放完整崩溃堆栈供 UI 弹窗展示）
    val crashEvent = MutableLiveData<String?>(null)
    val appLogs = MutableLiveData<CharSequence>("")
    val tunnelLogs = MutableLiveData("")
    val txRate = MutableLiveData(0L)
    val rxRate = MutableLiveData(0L)
    val txTotal = MutableLiveData(0L)
    val rxTotal = MutableLiveData(0L)
    // 连接期 TCP 握手 RTT（ms），-1 = 未测得。由 LatencyProber 周期刷新，供小组件显示。
    val latencyMs = MutableLiveData(-1L)
    // 最近 N 个速率采样点，供小组件迷你柱状图绘制（仅内存，不落库）。
    private val rateHistory = ArrayDeque<Pair<Long, Long>>()
    private val rateHistoryLock = Any()

    fun recordRateSample(tx: Long, rx: Long) {
        synchronized(rateHistoryLock) {
            rateHistory.addLast(tx to rx)
            while (rateHistory.size > RATE_HISTORY_MAX) rateHistory.removeFirst()
        }
    }

    fun rateHistorySnapshot(): List<Pair<Long, Long>> =
        synchronized(rateHistoryLock) { rateHistory.toList() }

    fun clearRateHistory() {
        synchronized(rateHistoryLock) { rateHistory.clear() }
    }

    // ── 连接质量综合评分（②功能）──
    // 最近 N 个延迟样本（环形）+ 本会话出口变化次数，供 QualityCalculator 算 jitter / 出口稳定性。
    private val qualityLock = Any()
    private val latencyHistory = ArrayDeque<Long>()
    private const val LATENCY_HISTORY_MAX = 12
    private var exitChangeCount = 0
    val connectionQuality = MutableLiveData(QualityScore.unknown)

    fun recordLatencySample(ms: Long) {
        if (ms < 0) return
        synchronized(qualityLock) {
            latencyHistory.addLast(ms)
            while (latencyHistory.size > LATENCY_HISTORY_MAX) latencyHistory.removeFirst()
        }
    }

    /** 出口 IP 发生变化时记一次（由 HomeFragment 在 ExitInfoStore.save 返回 true 处调用）。 */
    fun noteExitChanged() {
        synchronized(qualityLock) { exitChangeCount++ }
    }

    fun clearConnectionQuality() {
        synchronized(qualityLock) {
            latencyHistory.clear()
            exitChangeCount = 0
        }
        connectionQuality.postValue(QualityScore.unknown)
    }

    /** 用当前延迟 + 延迟历史(jitter) + 出口变化次数回算综合评分并广播。 */
    fun recomputeConnectionQuality() {
        val lat = latencyMs.value ?: -1L
        if (lat < 0) {
            connectionQuality.postValue(QualityScore.unknown)
            return
        }
        val (samples, changes) = synchronized(qualityLock) {
            latencyHistory.toList() to exitChangeCount
        }
        val jitter = QualityCalculator.jitter(samples)
        connectionQuality.postValue(QualityCalculator.compute(lat, jitter, changes))
    }
    // Go 引擎对象句柄：gomobile 绑定的唯一公开入口，所有控制调用（load/start/stop/ping/回调）走它
    val proxy: myssh.SshTProxy = myssh.Myssh.newSshTProxy()

    // ── SSH 握手信息（服务器标识，供连接详情面板展示）──────────────────────────
    // 由 Go 引擎在真实握手时记录（proxy.go 的 recordSSHHandshakeVersion / recordSSHHandshakeBanner），
    // 这里只做 JSON → 模型的薄封装。数据驻留在引擎进程内存中，未握手过该地址时为空。
    data class SshHandshakeInfo(
        /** 该条信息的来源 SSH 地址；调用方应核对它与当前展示节点的地址一致，避免串节点 */
        val address: String = "",
        /** RFC 4253 版本标识行，如 SSH-2.0-OpenSSH_9.6；认证通过后才可达 */
        @SerializedName("server_version") val serverVersion: String = "",
        /** 认证阶段服务端提示文本（MOTD / 登录告知），服务端未配置时为空 */
        val banner: String = ""
    )

    private val handshakeGson = Gson()

    /**
     * 读取 [sshAddr] 最近一次真实 SSH 握手信息；从未握手过该地址返回 null。
     * Go 侧在地址无记录时会回退到最近一次握手，故节点切换后依然能取到当前连接的信息。
     */
    fun getSshHandshakeInfo(sshAddr: String): SshHandshakeInfo? {
        if (sshAddr.isBlank()) return null
        val json = runCatching { proxy.getSSHHandshakeInfo(sshAddr) }.getOrNull()
        if (json.isNullOrBlank()) return null
        return runCatching { handshakeGson.fromJson(json, SshHandshakeInfo::class.java) }.getOrNull()
    }

    // 🌟 结构化日志队列（有界环形，零 GC 抖动，供列表 UI 直接消费）
    private const val MAX_LOG_ENTRIES = 1000
    private const val LOG_PUBLISH_DELAY_MS = 100L
    private val logEntriesList = ArrayList<LogEntry>(MAX_LOG_ENTRIES)
    val logEntries = MutableLiveData<List<LogEntry>>(emptyList())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val logPublishPending = AtomicBoolean(false)
    private var logEntriesVersion = 0L

    /**
     * 追加结构化日志
     */
    fun appendLogEntry(entry: LogEntry) {
        synchronized(logEntriesList) {
            if (logEntriesList.size >= MAX_LOG_ENTRIES) {
                val trimCount = MAX_LOG_ENTRIES / 10
                logEntriesList.subList(0, trimCount).clear()
            }
            logEntriesList.add(entry)
            logEntriesVersion++
        }
        scheduleLogEntriesPublish()
        appendAppLog(entry.fullText + "\n")
    }

    private fun scheduleLogEntriesPublish() {
        if (!logPublishPending.compareAndSet(false, true)) return
        mainHandler.postDelayed({
            val snapshot: List<LogEntry>
            val publishedVersion: Long
            synchronized(logEntriesList) {
                snapshot = ArrayList(logEntriesList)
                publishedVersion = logEntriesVersion
            }
            logEntries.value = snapshot
            logPublishPending.set(false)
            val changedWhilePublishing = synchronized(logEntriesList) {
                logEntriesVersion != publishedVersion
            }
            if (changedWhilePublishing) scheduleLogEntriesPublish()
        }, LOG_PUBLISH_DELAY_MS)
    }

    /**
     * 向 App 日志源追加内容
     */
    fun appendAppLog(text: CharSequence) {
        synchronized(appLogBuilder) {
            appLogBuilder.append(text)
            if (appLogBuilder.length > MAX_LOG_SIZE) {
                val removeCount = MAX_LOG_SIZE / 10
                val firstLineEnd = appLogBuilder.indexOf("\n", removeCount)
                if (firstLineEnd != -1) {
                    appLogBuilder.delete(0, firstLineEnd + 1)
                } else {
                    appLogBuilder.delete(0, removeCount)
                }
            }
            appLogs.postValue(SpannableStringBuilder(appLogBuilder))
        }
    }

    @Synchronized
    fun clearLogs() {
        synchronized(appLogBuilder) {
            appLogBuilder.clear()
            appLogs.postValue("")
        }
        synchronized(tunnelLogBuilder) {
            tunnelLogBuilder.setLength(0)
            tunnelLogs.postValue("")
        }
        synchronized(logEntriesList) {
            logEntriesList.clear()
            logEntriesVersion++
            logEntries.postValue(emptyList())
        }
    }

    fun setupLogBridge() {
        StunLogger.logEntryListener = { entry -> appendLogEntry(entry) }
    }

    /**
     * 注册 Go 引擎事件回调：把连通/重连/停止状态映射到 vpnState，
     * 把致命/连接错误推到 engineError，把崩溃 Panic 推到 crashEvent 供主 UI 弹窗。
     */
    fun registerEngineCallback() {
        proxy.setEngineCallback(object : myssh.EngineCallback {
            override fun onState(state: String?, detail: String?) {
                when (state) {
                    "connected" -> {
                        vpnState.postValue(VpnState.CONNECTED)
                        engineError.postValue(null) // 连通后清空历史错误提示
                    }
                    "reconnecting" -> vpnState.postValue(VpnState.RECONNECTING)
                    "stopped" -> vpnState.postValue(VpnState.DISCONNECTED)
                }
            }

            override fun onNodeEvent(nodeId: String?, event: String?, detail: String?) {
                // 节点级事件暂仅记日志，便于排查单节点连通问题
                if (event == "failed") {
                    StunLogger.e("Engine", "Node $nodeId failed: $detail")
                }
            }

            override fun onError(code: Long, msg: String?) {
                val text = msg ?: "unknown error (code=$code)"
                StunLogger.e("Engine", "Error($code): $text")
                engineError.postValue(text)
            }

            override fun onCrash(crashReport: String?) {
                val report = crashReport ?: "Unknown core panic"
                StunLogger.e("GoCrash", report)
                crashEvent.postValue(report)
            }
        })

        // 隧道生命周期事件（myssh eafc4b2+）：established/reconnecting/died 等。
        // 仅记日志分级展示；vpnState 仍由 EngineCallback 独家管理，避免双重状态迁移。
        myssh.Myssh.registerTunnelEventCallback(TunnelEventCallback { tunnel, kind, session, detail ->
            val text = buildString {
                append(tunnel ?: "tunnel")
                if (!session.isNullOrBlank()) append(" [").append(session).append(']')
                append(' ').append(kind ?: "unknown")
                if (!detail.isNullOrBlank()) append(' ').append(detail)
            }
            when (kind) {
                "tunnel_established" -> StunLogger.i("Tunnel", text)
                "tunnel_reconnecting", "tunnel_handshake_retrying" -> StunLogger.w("Tunnel", text)
                "tunnel_died", "tunnel_target_denied" -> StunLogger.e("Tunnel", text)
                else -> StunLogger.i("Tunnel", text)
            }
        })
    }

    /**
     * 初始化崩溃输出重定向文件（Go 1.23+ debug.SetCrashOutput 兜底）
     */
    fun initCrashOutput(ctx: Context) {
        try {
            val crashFile = File(ctx.cacheDir, "crash.log")
            proxy.initCrashOutput(crashFile.absolutePath)
        } catch (e: Throwable) {
            StunLogger.e("GoCrash", "Failed to init crash output: ${e.message}")
        }
    }

    /**
     * 检查上次运行是否有遗留的致命崩溃日志，若有则读取返回并备份归档
     */
    fun checkPreviousCrash(ctx: Context): String? {
        val crashFile = File(ctx.cacheDir, "crash.log")
        if (crashFile.exists() && crashFile.length() > 0) {
            return try {
                val content = crashFile.readText()
                val backupFile = File(ctx.cacheDir, "crash_prev.log")
                if (backupFile.exists()) backupFile.delete()
                crashFile.renameTo(backupFile)
                content
            } catch (e: Throwable) {
                null
            }
        }
        return null
    }

    fun getAppLogFilePath(ctx: Context): String = File(ctx.cacheDir, "app.log").absolutePath
    fun getTunnelLogFilePath(ctx: Context): String = File(ctx.cacheDir, "go.log").absolutePath
}
