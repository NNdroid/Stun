package app.fjj.stun.repo

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object StunLogger {
    private var fileWriter: FileWriter? = null

    // 使用单线程池：既保证不在主线程写文件，又保证了日志按顺序逐条写入，彻底解决并发冲突
    private val executor = Executors.newSingleThreadExecutor()

    // 日志时间格式，与 Go 端的 ISO8601 类似
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    // 是否同时输出到系统的 Logcat (在开发阶段可以设为 true，发布阶段设为 false)
    var isLogcatEnabled = true

    // 可选：用于 UI 实时显示的监听器
    var logListener: ((String) -> Unit)? = null

    /**
     * 初始化日志引擎
     * @param logFile 日志文件路径
     * @param append true 为追加模式，false 为每次启动清空旧日志 (对应 Go 端的 O_TRUNC)
     */
    fun init(logFile: File, append: Boolean = false) {
        executor.execute {
            try {
                if (!logFile.exists()) {
                    logFile.parentFile?.mkdirs()
                    logFile.createNewFile()
                }
                // 初始化 FileWriter
                fileWriter = FileWriter(logFile, append)
                i("FileLogger", "================ Android 日志引擎初始化成功 ================")
            } catch (e: Exception) {
                Log.e("FileLogger", "初始化文件日志失败", e)
            }
        }
    }

    // ==========================================
    // 对外暴露的便捷打印方法
    // ==========================================
    fun d(tag: String, msg: String) = log("DEBUG", tag, msg)
    fun i(tag: String, msg: String) = log("INFO", tag, msg)
    fun w(tag: String, msg: String) = log("WARN", tag, msg)
    fun e(tag: String, msg: String, tr: Throwable? = null) = log("ERROR", tag, msg, tr)

    /**
     * 核心日志处理逻辑
     */
    private fun log(level: String, tag: String, msg: String, tr: Throwable? = null) {
        // 1. 如果开启了 Logcat，同时往控制台打一份
        if (isLogcatEnabled) {
            when (level) {
                "DEBUG" -> Log.d(tag, msg)
                "INFO" -> Log.i(tag, msg)
                "WARN" -> Log.w(tag, msg)
                "ERROR" -> if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
            }
        }

        // 2. 拼接日志字符串
        val time = dateFormat.format(Date())
        val builder = java.lang.StringBuilder()
        builder.append(time).append("\t").append(level).append("\t[").append(tag).append("]\t").append(msg).append("\n")

        // 3. 如果有异常堆栈，将异常信息打印出来
        if (tr != null) {
            builder.append(Log.getStackTraceString(tr)).append("\n")
        }

        val logStr = builder.toString()

        // 5. 通知监听器 (UI 更新)
        logListener?.invoke(logStr)

        // 4. 扔到单线程池中异步写入文件
        executor.execute {
            try {
                fileWriter?.append(logStr)
                fileWriter?.flush() // 立即刷新到磁盘，防止 App 突然崩溃导致日志丢失
            } catch (e: Exception) {
                // 写文件失败时静默处理或回退到 logcat
            }
        }
    }

    /**
     * 退出 App 或关闭 VPN 服务时调用，释放句柄
     */
    fun release() {
        executor.execute {
            try {
                fileWriter?.close()
                fileWriter = null
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}