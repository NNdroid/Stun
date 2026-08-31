package app.fjj.stun.repo

import android.graphics.Color

/**
 * 结构化日志等级枚举
 */
enum class LogLevel(
    val priority: Int,
    val shortCode: String,
    val color: Int,
    val rowBgColor: Int = Color.TRANSPARENT
) {
    DEBUG(0, "D", Color.parseColor("#9E9E9E")),
    INFO(1, "I", Color.parseColor("#2196F3")),
    WARN(2, "W", Color.parseColor("#FF9800")),
    ERROR(3, "E", Color.parseColor("#F44336"), Color.argb(30, 244, 67, 54));

    companion object {
        fun fromInt(value: Int): LogLevel = when (value) {
            0 -> DEBUG
            1 -> INFO
            2 -> WARN
            else -> ERROR
        }

        fun fromString(str: String): LogLevel = when (str.uppercase().trim()) {
            "DEBUG", "D" -> DEBUG
            "INFO", "I" -> INFO
            "WARN", "WARNING", "W" -> WARN
            "ERROR", "FATAL", "PANIC", "E" -> ERROR
            else -> INFO
        }
    }
}

/**
 * 结构化日志实体类
 * 彻底消灭文本解析与切片，保障 100% 准确性与零 GC 抖动
 */
data class LogEntry(
    val id: Long,
    val timestamp: Long,
    val timeStr: String,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val metaInfo: String = "",
    val stackTrace: String = "",
    val formattedLine: String = ""
) {
    /**
     * 单条完整的文本呈现（针对多行异常堆栈，聚合为一个整体）
     */
    val fullText: String by lazy {
        if (formattedLine.isNotEmpty()) formattedLine
        else buildString {
            append(timeStr)
            append(" ")
            append(level.name.padEnd(5))
            if (metaInfo.isNotEmpty()) {
                append(" ")
                append(metaInfo)
            }
            append(" ")
            append(message)
            if (stackTrace.isNotEmpty()) {
                append("\n")
                append(stackTrace)
            }
        }
    }
}
