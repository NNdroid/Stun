package app.fjj.stun.repo

import android.content.Context
import android.text.SpannableStringBuilder
import androidx.lifecycle.MutableLiveData
import java.io.File

object StunRepository {
    private const val MAX_LOG_SIZE = 100000

    private val appLogBuilder = SpannableStringBuilder()
    private val tunnelLogBuilder = StringBuilder()

    val vpnState = MutableLiveData(VpnState.DISCONNECTED)
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
    // Go 引擎对象句柄：gomobile 绑定的唯一公开入口，所有控制调用（load/start/stop/ping/回调）走它
    val proxy: myssh.SshTProxy = myssh.Myssh.newSshTProxy()

    // 🌟 结构化日志队列（有界环形，零 GC 抖动，供列表 UI 直接消费）
    private const val MAX_LOG_ENTRIES = 1000
    private val logEntriesList = ArrayList<LogEntry>(MAX_LOG_ENTRIES)
    val logEntries = MutableLiveData<List<LogEntry>>(emptyList())

    /**
     * 追加结构化日志
     */
    fun appendLogEntry(entry: LogEntry) {
        val snapshot: List<LogEntry>
        synchronized(logEntriesList) {
            if (logEntriesList.size >= MAX_LOG_ENTRIES) {
                val trimCount = MAX_LOG_ENTRIES / 10
                logEntriesList.subList(0, trimCount).clear()
            }
            logEntriesList.add(entry)
            snapshot = ArrayList(logEntriesList)
        }
        logEntries.postValue(snapshot)
        appendAppLog(entry.fullText + "\n")
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

    /**
     * 兼容旧接口，底层 StunLogger 仍会通过此方法间接触发 appendAppLog
     */
    fun appendLog(line: String) {
        StunLogger.i("StunRepo", line)
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
