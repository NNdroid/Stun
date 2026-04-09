package app.fjj.stun.repo

import android.content.Context
import androidx.lifecycle.MutableLiveData
import java.io.File

object StunRepository {
    // 限制最大保留字符数，防止内存无限增长（约 10000 字符）
    private const val MAX_LOG_SIZE = 10000

    // 使用 StringBuilder 提高拼接效率
    private val logBuilder = StringBuilder()

    val vpnState = MutableLiveData<VpnState>(VpnState.DISCONNECTED)
    val logData = MutableLiveData("")

    /**
     * 向日志中追加一行内容
     * 自动处理线程同步、字符串裁剪和 UI 更新
     */
    @Synchronized
    fun appendLog(line: String) {
        StunLogger.i("StunRepo", line)
    }

    /**
     * 清空所有日志
     */
    @Synchronized
    fun clearLogs() {
        logBuilder.setLength(0)
        logData.postValue("")
    }

    /**
     * 初始化日志监听，将 StunLogger 的输出同步到 LiveData
     */
    fun setupLogBridge() {
        StunLogger.logListener = { line ->
            synchronized(logBuilder) {
                logBuilder.append(line)
                if (logBuilder.length > MAX_LOG_SIZE) {
                    val overflow = logBuilder.length - MAX_LOG_SIZE
                    val firstLineEnd = logBuilder.indexOf("\n", overflow)
                    if (firstLineEnd != -1) {
                        logBuilder.delete(0, firstLineEnd + 1)
                    }
                }
                logData.postValue(logBuilder.toString())
            }
        }
    }

    fun getLogFilePath(ctx: Context): String {
        return File(ctx.cacheDir, "go.log").absolutePath
    }
}