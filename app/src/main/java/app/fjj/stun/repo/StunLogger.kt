package app.fjj.stun.repo

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

@Keep
object StunLogger {
    private const val TAG = "StunLogger"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val executor = Executors.newSingleThreadExecutor()

    // ADDED @Volatile to ensure background threads see the initialization immediately
    @Volatile
    private var logFile: File? = null

    var isLogcatEnabled = true
    var logListener: ((String) -> Unit)? = null

    fun init(context: Context) {
        val path = StunRepository.getAppLogFilePath(context)
        Log.w(TAG, "🔧 [Self-check] Preparing to initialize log path: $path")

        try {
            val file = File(path)
            val parentDir = file.parentFile

            // 1. Test directory creation
            if (parentDir != null && !parentDir.exists()) {
                val isCreated = parentDir.mkdirs()
                Log.w(TAG, "🔧 [Self-check] Parent directory does not exist, attempting to create... Result: $isCreated")
                if (!isCreated && !parentDir.exists()) {
                    Log.e(TAG, "❌ [Fatal Error] Unable to create parent directory, possibly due to insufficient permissions or invalid path!")
                    return // Intercept directly, no need to go further
                }
            }

            // 2. Test file creation and write permission
            if (!file.exists()) {
                val isFileCreated = file.createNewFile()
                Log.w(TAG, "🔧 [Self-check] Log file does not exist, attempting to create... Result: $isFileCreated")
            }

            if (!file.canWrite()) {
                Log.e(TAG, "❌ [Fatal Error] File exists, but system denied write permission! Check Scoped Storage.")
                return
            }

            // Clean up large logs (> 15MB)
            if (file.exists() && file.length() > 15 * 1024 * 1024) {
                file.delete()
                file.createNewFile()
            }

            logFile = file
            i(TAG, "✅ [Self-check passed] Logger initialized successfully, file locked.")

        } catch (e: Exception) {
            Log.e(TAG, "❌ [Fatal Error] Crash during initialization: ${e.message}", e)
        }
    }

    fun d(tag: String, msg: String) = log("DEBUG", tag, msg)
    fun i(tag: String, msg: String) = log("INFO", tag, msg)
    fun w(tag: String, msg: String) = log("WARN", tag, msg)
    fun e(tag: String, msg: String, tr: Throwable? = null) = log("ERROR", tag, msg, tr)

    /**
     * Core logging logic (Optimized: async writing, file persistence)
     */
    private fun log(level: String, tag: String, msg: String, tr: Throwable? = null) {
        val currentTime = Date()
        executor.execute {
            // 1. Output to Logcat if enabled
            if (isLogcatEnabled) {
                when (level) {
                    "DEBUG" -> Log.d(tag, msg)
                    "INFO" -> Log.i(tag, msg)
                    "WARN" -> Log.w(tag, msg)
                    "ERROR" -> if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
                }
            }

            // 2. Format log string
            val timeStr = dateFormat.format(currentTime)
            val logStr = buildString {
                append(timeStr).append("\t").append(level).append("\t[").append(tag).append("]\t").append(msg).append("\n")
                if (tr != null) {
                    append(Log.getStackTraceString(tr)).append("\n")
                }
            }

            // 3. Write to app.log
            logFile?.let { file ->
                try {
                    // FileOutputStream will now succeed because parent directories exist
                    FileOutputStream(file, true).use { fos ->
                        fos.write(logStr.toByteArray())
                        fos.flush()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write log to file: ${e.message}", e)
                }
            }

            // 4. Notify listener (UI update)
            logListener?.invoke(logStr)
        }
    }

    @JvmStatic
    fun receiveGoLog(level: Int, tag: String, msg: String) {
        when (level) {
            0 -> d(tag, msg)
            1 -> i(tag, msg)
            2 -> w(tag, msg)
            3 -> e(tag, msg)
            4 -> e(tag, "🔥 PANIC: $msg")
            5 -> e(tag, "💀 FATAL: $msg")
        }
    }
}