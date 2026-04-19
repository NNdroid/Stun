package app.fjj.stun.repo

import android.content.Context
import android.text.SpannableStringBuilder
import androidx.lifecycle.MutableLiveData
import java.io.File

object StunRepository {
    private const val MAX_LOG_SIZE = 10000000 // 10MB of text

    private val appLogBuilder = SpannableStringBuilder()
    private val tunnelLogBuilder = StringBuilder()

    val vpnState = MutableLiveData(VpnState.DISCONNECTED)
    val appLogs = MutableLiveData<CharSequence>("")
    val tunnelLogs = MutableLiveData("")

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

    fun getAppLogFilePath(ctx: Context): String = File(ctx.cacheDir, "app.log").absolutePath
    fun getTunnelLogFilePath(ctx: Context): String = File(ctx.cacheDir, "go.log").absolutePath
}