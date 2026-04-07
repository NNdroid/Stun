package app.fjj.stun.repo

import android.content.Context
import androidx.lifecycle.MutableLiveData
import java.io.File
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString

object GostRepository {
    // 限制最大保留字符数，防止内存无限增长（约 10000 字符）
    private const val MAX_LOG_SIZE = 10000

    // 使用 StringBuilder 提高拼接效率
    private val logBuilder = StringBuilder()

    val vpnStatus = MutableLiveData(false)
    val logData = MutableLiveData("")

    /**
     * 向日志中追加一行内容
     * 自动处理线程同步、字符串裁剪和 UI 更新
     */
    @Synchronized
    fun appendLog(line: String) {
        // 1. 追加新行并带上时间戳（可选，方便调试）
        // val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        logBuilder.append(line).append("\n")

        // 2. 检查长度，如果超出限制则删除头部内容（滚动更新）
        if (logBuilder.length > MAX_LOG_SIZE) {
            val overflow = logBuilder.length - MAX_LOG_SIZE
            val firstLineEnd = logBuilder.indexOf("\n", overflow)
            if (firstLineEnd != -1) {
                logBuilder.delete(0, firstLineEnd + 1)
            }
        }

        // 3. 更新 LiveData
        // 使用 postValue 确保在后台线程（如 Go 线程或 HEV 线程）调用时也能安全更新 UI
        logData.postValue(logBuilder.toString())
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
     * 实时追踪 Go 写入的日志文件，并逐行追加到 logBuilder 中
     */
    fun startLogFileCapture(ctx: Context) {
        kotlin.concurrent.thread(start = true, name = "LogFileReader") {
            var process: Process? = null
            try {
                // 获取你之前定义的日志文件路径
                val logFile = File(getLogFilePath(ctx))

                // 容错处理：如果 Go 还没来得及创建文件，我们先建一个空的，防止 tail 命令报错
                if (!logFile.exists()) {
                    logFile.createNewFile()
                }

                // 使用 Linux 原生的 tail -f 命令：它会输出现有内容，并一直阻塞等待新内容的追加
                // 甚至不需要你去写复杂的 File 流轮询代码
                process = Runtime.getRuntime().exec(arrayOf("tail", "-f", logFile.absolutePath))
                val reader = process.inputStream.bufferedReader()

                var line: String?
                // 只要 Go 侧在写入文件，readLine() 就会立刻拿到新数据；没有写入时，它会在这里静静阻塞等待
                while (reader.readLine().also { line = it } != null) {
                    val currentLine = line ?: continue

                    // 1. 线程安全地追加到 logBuilder
                    synchronized(logBuilder) {
                        logBuilder.append(currentLine).append("\n")
                    }
                }
            } catch (e: Exception) {
                synchronized(logBuilder) {
                    logBuilder.append("日志追踪失败: ${e.message}\n")
                }
            } finally {
                // 当退出界面或手动停止追踪时，销毁 tail 进程，释放资源
                process?.destroy()
            }
        }
    }

    fun getLogFilePath(ctx: Context): String {
        return File(ctx.cacheDir, "global.log").absolutePath
    }
}