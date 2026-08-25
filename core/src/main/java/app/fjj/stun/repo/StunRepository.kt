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
    val appLogs = MutableLiveData<CharSequence>("")
    val tunnelLogs = MutableLiveData("")
    val txRate = MutableLiveData(0L)
    val rxRate = MutableLiveData(0L)
    val txTotal = MutableLiveData(0L)
    val rxTotal = MutableLiveData(0L)
    // Go 引擎对象句柄：gomobile 绑定的唯一公开入口，所有控制调用（load/start/stop/ping/回调）走它
    val proxy: myssh.SshTProxy = myssh.Myssh.newSshTProxy()

    /**
     * 向指定日志源追加内容
     */
    private fun append(builder: SpannableStringBuilder, liveData: MutableLiveData<CharSequence>, text: CharSequence) {
        synchronized(builder) {
            builder.append(text)
            if (builder.length > MAX_LOG_SIZE) {
                // When exceeding limit, remove the first 10% of logs to avoid frequent deletions
                val removeCount = MAX_LOG_SIZE / 10
                val firstLineEnd = builder.indexOf("\n", removeCount)
                if (firstLineEnd != -1) {
                    builder.delete(0, firstLineEnd + 1)
                } else {
                    builder.delete(0, removeCount)
                }
            }
            liveData.postValue(SpannableStringBuilder(builder)) // Send a copy to avoid mutation issues
        }
    }

    fun appendAppLog(text: CharSequence) = append(appLogBuilder, appLogs, text)

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
    }

    fun setupLogBridge() {
        StunLogger.logListener = { line -> appendAppLog(line) }
    }

    /**
     * 注册 Go 引擎事件回调：把连通/重连/停止状态映射到 vpnState，
     * 把致命/连接错误推到 engineError，供主 UI 直接展示，不再只埋在日志页。
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
        })
    }

    fun getAppLogFilePath(ctx: Context): String = File(ctx.cacheDir, "app.log").absolutePath
    fun getTunnelLogFilePath(ctx: Context): String = File(ctx.cacheDir, "go.log").absolutePath
}
