package app.fjj.stun.util

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃历史持久化存储。
 *
 * 背景：CrashHandler 与 Go 引擎此前都只保留「最近一次」崩溃，且 read-once-delete 语义会把它
 * 在下次启动时直接销毁，因此 WebUI 里看不到任何历史。这里补上一个只增不丢的台账。
 *
 * 存储格式：filesDir/crash_history.jsonl —— 每条记录一行 JSON（JSON Lines）。
 * 选 JSONL 而不是 Room，是因为记录动作发生在 [Thread.UncaughtExceptionHandler] 里，
 * 进程紧接着就会死掉；一行 append + 立刻 close 是这里最不容易半路损坏的写法，
 * 单行解析失败也只丢那一行，不会让整个历史读不出来。
 * 不用 cacheDir：Android 存储吃紧时会主动清理 cache 目录，而这里存的是唯一的崩溃证据。
 * 不加 Room 实体：那需要把 AppDatabase 从 schema 24 升到 25 再补 migration 和 schema 导出，
 * 为一个只读诊断台账不值得。
 *
 * 所有公开方法都被异常包裹——记录发生在崩溃现场，任何失败都不能影响原有的崩溃处理流程。
 */
object CrashHistoryStore {

    private const val TAG = "CrashHistoryStore"
    private const val FILE_NAME = "crash_history.jsonl"

    /** 崩溃来源类型。jvm = Kotlin/Java 未捕获异常；go_panic = 核心引擎 Panic/Fatal。 */
    const val TYPE_JVM = "jvm"
    const val TYPE_GO_PANIC = "go_panic"

    /** 最多保留多少条：只裁旧的，保证 WebUI 单次拉取不会太重。 */
    const val MAX_RECORDS = 50

    /** 单条报告截断上限，防止一次巨型堆栈把整个文件顶爆。 */
    private const val MAX_REPORT_CHARS = 60000

    private val LOCK = Any()
    private val gson = Gson()

    data class CrashRecord(
        val id: Long,        // 记录时间 epochMs，同时充当唯一键
        val time: String,    // 人类可读时间戳
        val type: String,    // TYPE_JVM / TYPE_GO_PANIC
        val version: String, // 崩溃时 App 版本
        val device: String,  // 机型
        val android: String, // Android 版本
        val thread: String,  // 崩溃线程
        val exception: String,
        val message: String,
        val report: String   // 完整原始报告，供 WebUI 展开/复制
    )

    private fun historyFile(context: Context): File =
        File(context.filesDir, FILE_NAME)

    private fun nowStr(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    /**
     * 追加一条崩溃记录。在崩溃现场调用，必须自兜底。
     */
    fun record(context: Context, type: String, report: String) {
        try {
            val record = parseRecord(type, report)
            synchronized(LOCK) {
                val file = historyFile(context.applicationContext)
                file.appendText("${gson.toJson(record)}\n")
                trimIfOverLimit(file)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to record crash history: ${t.message}", t)
        }
    }

    fun list(context: Context): List<CrashRecord> = synchronized(LOCK) {
        try {
            val file = historyFile(context.applicationContext)
            if (!file.exists() || file.length() == 0L) return@synchronized emptyList()
            file.readText().lineSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    try { gson.fromJson(line, CrashRecord::class.java) } catch (_: Throwable) { null }
                }
                .toList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun delete(context: Context, id: Long): Boolean = synchronized(LOCK) {
        try {
            val file = historyFile(context.applicationContext)
            if (!file.exists() || file.length() == 0L) return@synchronized false
            val lines = file.readLines()
            val kept = lines.filterNot { line ->
                line.isNotBlank() && runCatching { gson.fromJson(line, CrashRecord::class.java)?.id }
                    .getOrNull() == id
            }
            if (kept.size == lines.size) return@synchronized false
            if (kept.isEmpty()) return@synchronized file.delete()
            file.writeText(kept.joinToString("\n") + "\n")
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun clear(context: Context): Int = synchronized(LOCK) {
        try {
            val file = historyFile(context.applicationContext)
            if (!file.exists()) return@synchronized 0
            val count = runCatching { file.readLines().count { it.isNotBlank() } }.getOrDefault(0)
            file.delete()
            count
        } catch (_: Throwable) {
            0
        }
    }

    /** 记录过多时裁掉最旧的行，保留最新的 [MAX_RECORDS] 条（新记录始终在文件末尾）。 */
    private fun trimIfOverLimit(file: File) {
        try {
            val lines = file.readLines().filter { it.isNotBlank() }
            if (lines.size > MAX_RECORDS) {
                file.writeText(lines.takeLast(MAX_RECORDS).joinToString("\n") + "\n")
            }
        } catch (_: Throwable) {}
    }

    /**
     * 从原始报告文本抽出结构化字段。
     * JVM 报告的表头格式由 CrashHandler 固定输出（Time:/App Version:/.../Exception:/Message:），
     * 逐行按前缀匹配即可，不需要正则；Go Panic 报告没有这些行，只做一次 Panic Recovered 兜底。
     */
    private fun parseRecord(type: String, raw: String): CrashRecord {
        val report = if (raw.length > MAX_REPORT_CHARS) raw.take(MAX_REPORT_CHARS) else raw

        fun field(label: String): String =
            report.lineSequence()
                .firstOrNull { it.startsWith(label) }
                ?.removePrefix(label)?.trim()
                .orEmpty()

        val time = field("Time:").ifBlank { nowStr() }
        val epoch = epochOf(time)
        val exception = field("Exception:")
        var message = field("Message:")

        if (message.isBlank() && type == TYPE_GO_PANIC) {
            // Go 侧格式是「💥 [tag] Panic Recovered: <异常信息>」+ debug.Stack() + Version
            message = Regex("Panic Recovered:\\s*([^\\r\\n]+)").find(report)
                ?.groupValues?.get(1)?.trim().orEmpty()
        }

        return CrashRecord(
            id = epoch,
            time = time,
            type = type,
            version = field("App Version:"),
            device = field("Device:"),
            android = field("Android:"),
            thread = field("Thread:"),
            exception = exception,
            message = message,
            report = report
        )
    }

    private fun epochOf(time: String): Long = try {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).parse(time)?.time
            ?: System.currentTimeMillis()
    } catch (_: Throwable) {
        System.currentTimeMillis()
    }
}
