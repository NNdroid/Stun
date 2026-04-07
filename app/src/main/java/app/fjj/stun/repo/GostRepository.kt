package app.fjj.stun.repo

import androidx.lifecycle.MutableLiveData

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
     * 读取系统 Logcat 并追加到日志中
     */
    fun startLogcatCapture() {
        kotlin.concurrent.thread(start = true, name = "LogcatReader") {
            try {
                // 读取当前进程日志 (VERBOSE 级别)
                val process = Runtime.getRuntime().exec("logcat -v time -s GoLog")
                val reader = process.inputStream.bufferedReader()
                
                reader.useLines { lines ->
                    lines.forEach { line ->
                        // 简单过滤掉过于冗余的 UI 刷新日志
                        if (!line.contains("ViewRootImpl") && !line.contains("Choreographer")) {
                            appendLog(line)
                        }
                    }
                }
            } catch (e: Exception) {
                appendLog("Logcat 捕获失败: ${e.message}")
            }
        }
    }
}