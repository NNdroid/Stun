package app.fjj.stun.repo

import android.content.Context
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.Log
import androidx.annotation.Keep
import app.fjj.stun.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import androidx.core.graphics.toColorInt

@Keep
object StunLogger {
    private const val TAG = "StunLogger"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    // --- 核心优化 1：有界队列 ---
    // 限制最大缓存 5000 条，防止在极端网络拥塞/疯狂刷日志时耗尽应用内存导致 OOM
    private val logQueue = LinkedBlockingQueue<String>(5000)

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var currentFileSize = 0L
    private const val MAX_FILE_SIZE = 5 * 1024 * 1024L // 5MB

    var isLogcatEnabled = true
    var logListener: ((CharSequence) -> Unit)? = null
    private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())
    private val FALLBACK_DATE_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // 定义日志颜色 (Material Design 配色)
    private val COLOR_DEBUG = "#9E9E9E".toColorInt() // 灰色
    private val COLOR_INFO  = "#4CAF50".toColorInt() // 绿色
    private val COLOR_WARN  = "#FFC107".toColorInt() // 琥珀色
    private val COLOR_ERROR = "#F44336".toColorInt() // 红色

    init {
        // --- 核心优化 2：单一后台守护线程专门负责写盘 ---
        thread(name = "StunLogger-Writer", isDaemon = true) {
            val buffer = mutableListOf<String>()
            while (true) {
                try {
                    // take() 会阻塞休眠，直到队列里至少有一条日志，完全不占 CPU
                    val firstLog = logQueue.take()
                    buffer.add(firstLog)

                    // drainTo 会瞬间把队列里目前积压的所有日志全部“收割”到 buffer 里
                    logQueue.drainTo(buffer)

                    // 批量写入磁盘
                    writeBatchToFile(buffer)
                    buffer.clear()
                } catch (e: InterruptedException) {
                    break // 线程被系统中断时退出
                } catch (e: Exception) {
                    Log.e(TAG, "Log writer thread error", e)
                }
            }
        }
    }

    fun init(context: Context) {
        // 注意：如果你这边的路径获取方式不同，请替换回你的方法
        val path = StunRepository.getAppLogFilePath(context)
        Log.w(TAG, "🔧 [Self-check] Preparing to initialize log path: $path")

        try {
            val file = File(path)
            val parentDir = file.parentFile

            // 1. 测试并创建父目录
            if (parentDir != null && !parentDir.exists()) {
                val isCreated = parentDir.mkdirs()
                if (!isCreated && !parentDir.exists()) {
                    Log.e(TAG, "❌ [Fatal Error] Unable to create parent directory!")
                    return
                }
            }

            // 2. 测试并创建文件
            if (!file.exists()) {
                file.createNewFile()
            }

            // 3. 校验读写权限
            if (!file.canWrite()) {
                Log.e(TAG, "❌ [Fatal Error] File exists, but system denied write permission!")
                return
            }

            // 4. 初始化状态
            logFile = file
            currentFileSize = file.length()

            // 5. 启动时如果发现文件已经超大，立即触发一次滚动
            if (currentFileSize > MAX_FILE_SIZE) {
                rotateLogFiles(file)
            }

            i(TAG, "✅ [Self-check passed] Logger initialized successfully. Size: ${currentFileSize / 1024} KB")

        } catch (e: Exception) {
            Log.e(TAG, "❌ [Fatal Error] Crash during initialization: ${e.message}", e)
        }
    }

    var errorListener: ((String, String, Throwable?) -> Unit)? = null

    fun d(tag: String, msg: String) = log("DEBUG", tag, msg)
    fun i(tag: String, msg: String) = log("INFO", tag, msg)
    fun w(tag: String, msg: String) = log("WARN", tag, msg)
    fun e(tag: String, msg: String, tr: Throwable? = null) = log("ERROR", tag, msg, tr)

    /**
     * 核心日志生产逻辑 (完全非阻塞，只做字符串拼接和入队)
     */
    private fun log(inLevel: String, tag: String, rawMsg: String, tr: Throwable? = null) {
        var finalLevel = inLevel.uppercase()
        var timeStr = ""
        var msgContent = rawMsg
        var metaInfo = ""

        // 1. 解析日志内容
        if (rawMsg.startsWith("{")) {
            try {
                val json = JSONObject(rawMsg)
                msgContent = json.optString("msg", json.optString("message", rawMsg))
                finalLevel = json.optString("level", json.optString("severity", finalLevel)).uppercase()
                val caller = json.optString("caller")
                val pid = json.optInt("pid")
                val uid = json.optInt("uid")
                val version = json.optString("v", json.optString("version"))
                val ts = json.optString("ts", json.optString("timestamp"))

                timeStr = if (ts.isNotEmpty()) {
                    TIME_FORMATTER.format(java.time.Instant.parse(ts))
                } else {
                    FALLBACK_DATE_FORMAT.format(Date())
                }

                // 组装元数据 (根据 Debug/Release 模式动态精简)
                metaInfo = buildString {
                    append("[$tag")
                    if (BuildConfig.DEBUG) {
                        append(" v:$version U:$uid P:$pid")
                    }
                    append("]")
                    if (caller.isNotEmpty()) append(" ($caller)")
                }

            } catch (e: Exception) {
                // 解析失败时优雅降级
                timeStr = FALLBACK_DATE_FORMAT.format(Date())
                metaInfo = "[$tag]"
            }
        } else {
            timeStr = FALLBACK_DATE_FORMAT.format(Date())
            metaInfo = "[$tag]"
        }

        val stackTrace = if (tr != null) "\n" + Log.getStackTraceString(tr) else ""

        // 2. 组装纯文本日志 (用于 Logcat 和写盘，保证文件干净)
        // 使用 %-5s 保证级别占 5 个字符，完美对齐
        val plainLogStr = String.format("%s %-5s %s %s%s\n", timeStr, finalLevel, metaInfo, msgContent, stackTrace)

        // 3. 投递给系统 Logcat
        if (isLogcatEnabled) {
            when (finalLevel) {
                "DEBUG" -> Log.d(tag, msgContent)
                "INFO"  -> Log.i(tag, msgContent)
                "WARN"  -> Log.w(tag, msgContent)
                "ERROR" -> Log.e(tag, msgContent, tr)
                else    -> Log.v(tag, msgContent)
            }
        }

        // 4. 写盘防 OOM 保护 (使用纯文本)
        if (!logQueue.offer(plainLogStr)) {
            Log.w("LogSystem", "Log queue is full! Dropping log: $msgContent")
        }

        // 5. 组装彩色文本并投递给 UI
        // 注意：请将 logListener 的类型定义修改为 (CharSequence) -> Unit
        logListener?.let { listener ->
            val color = when (finalLevel) {
                "DEBUG" -> COLOR_DEBUG
                "WARN", "WARNING" -> COLOR_WARN
                "ERROR", "FATAL"  -> COLOR_ERROR
                else -> COLOR_INFO // INFO 默认为绿色
            }

            val spannableBuilder = SpannableStringBuilder(plainLogStr)
            // 给整行文字上色
            spannableBuilder.setSpan(
                ForegroundColorSpan(color),
                0,
                spannableBuilder.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            listener.invoke(spannableBuilder)
        }

        if (finalLevel == "ERROR" || finalLevel == "FATAL") errorListener?.invoke(tag, msgContent, tr)
    }

    /**
     * 批量写盘逻辑 (仅由后台守护线程调用)
     */
    private fun writeBatchToFile(logs: List<String>) {
        val file = logFile ?: return
        try {
            // 将百上千条日志合并为一个 String/ByteArray
            val batchString = buildString {
                for (log in logs) append(log)
            }
            val bytes = batchString.toByteArray()

            // 检查加上这批日志后是否会超出 5MB 限制
            if (currentFileSize + bytes.size > MAX_FILE_SIZE) {
                rotateLogFiles(file)
            }

            // --- 核心优化 3：批量 I/O ---
            // 哪怕有 1000 条日志，这里也只执行一次打开、写入、关闭。大大降低 CPU 和闪存负担。
            FileOutputStream(file, true).use { fos ->
                fos.write(bytes)
                fos.flush()
            }
            currentFileSize += bytes.size

        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log batch", e)
        }
    }

    /**
     * --- 核心优化 4：多级文件滚动 ---
     * 维持 app.log -> old.1 -> old.2 的流转
     */
    private fun rotateLogFiles(currentFile: File) {
        try {
            val parent = currentFile.parentFile ?: return
            val fileName = currentFile.name // 比如 "app.log"

            val old1 = File(parent, "$fileName.old.1")
            val old2 = File(parent, "$fileName.old.2")

            // 1. 删除最旧的 .old.2
            if (old2.exists()) {
                old2.delete()
            }

            // 2. 将 .old.1 重命名为 .old.2
            if (old1.exists()) {
                old1.renameTo(old2)
            }

            // 3. 将当前 app.log 重命名为 .old.1
            currentFile.renameTo(old1)

            // 4. 创建新的空白 app.log
            currentFile.createNewFile()

            // 5. 重置内存中的文件大小计数器
            currentFileSize = 0L

            Log.i(TAG, "Log rotation completed: app.log -> old.1 -> old.2")
        } catch (e: Exception) {
            Log.e(TAG, "Log rotation failed", e)
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