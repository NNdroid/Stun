package app.fjj.stun.repo

import android.content.Context
import androidx.lifecycle.MutableLiveData
import java.io.File
import kotlin.concurrent.thread

object StunRepository {
    private const val MAX_LOG_SIZE = 10000

    private val appLogBuilder = StringBuilder()
    private val tunnelLogBuilder = StringBuilder()

    val vpnState = MutableLiveData(VpnState.DISCONNECTED)
    val appLogs = MutableLiveData("")
    val tunnelLogs = MutableLiveData("")

    /**
     * 向指定日志源追加内容
     */
    private fun append(builder: StringBuilder, liveData: MutableLiveData<String>, text: String) {
        synchronized(builder) {
            builder.append(text)
            if (builder.length > MAX_LOG_SIZE) {
                val overflow = builder.length - MAX_LOG_SIZE
                val firstLineEnd = builder.indexOf("\n", overflow)
                if (firstLineEnd != -1) {
                    builder.delete(0, firstLineEnd + 1)
                }
            }
            liveData.postValue(builder.toString())
        }
    }

    fun appendAppLog(text: String) = append(appLogBuilder, appLogs, text)
    fun appendTunnelLog(text: String) = append(tunnelLogBuilder, tunnelLogs, text + "\n")

    /**
     * 兼容旧接口，底层 StunLogger 仍会通过此方法间接触发 appendAppLog
     */
    fun appendLog(line: String) {
        StunLogger.i("StunRepo", line)
    }

    @Synchronized
    fun clearLogs() {
        synchronized(appLogBuilder) {
            appLogBuilder.setLength(0)
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

    fun getAppLogFilePath(ctx: Context) = File(ctx.cacheDir, "stun.log").absolutePath
    fun getTunnelLogFilePath(ctx: Context) = File(ctx.cacheDir, "go.log").absolutePath
}