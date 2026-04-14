package app.fjj.stun.repo

import android.util.Log
import androidx.annotation.Keep
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

@Keep
object StunLogger {
    // 日志时间格式，与 Go 端的 ISO8601 类似
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    // 是否同时输出到系统的 Logcat (在开发阶段可以设为 true，发布阶段设为 false)
    var isLogcatEnabled = true

    // 可选：用于 UI 实时显示的监听器
    var logListener: ((String) -> Unit)? = null

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
    }

    @JvmStatic
    fun receiveGoLog(level: Int, tag: String, msg: String) {
        when (level) {
            0 -> d(tag, msg)
            1 -> i(tag, msg)
            2 -> w(tag, msg)
            3 -> e(tag, msg)
            4 -> e(tag, "🔥 PANIC: $msg") // 对应 Go Panic
            5 -> e(tag, "💀 FATAL: $msg") // 对应 Go Fatal
        }
    }
}