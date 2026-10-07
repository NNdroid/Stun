package app.fjj.stun.util

import android.content.Context
import android.content.pm.PackageManager

object AppUtils {
    fun getAppVersion(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "v1.0.0"
        } catch (e: Exception) {
            "v1.0.0"
        }
    }

    fun getLibVersion(): String {
        return try {
            myssh.Myssh.getVersion()
        } catch (e: Exception) {
            "Unknown"
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        return String.format(java.util.Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    fun formatSpeed(bytesPerSec: Long): String {
        return "${formatBytes(bytesPerSec)}/s"
    }

    /**
     * 桌面组件用的**紧凑**速率文案（无空格、无 `/s`）：`950`、`1.5K`、`2.3M`。
     *
     * 组件上的可用宽度只有几十 dp，写全 `1.5 MB/s` 会被 launcher 截断，
     * 所以这里换一套更短的格式 —— 但**单位阶梯必须与 [formatSpeed] 一致**（到 TB），
     * 否则会出现"1.0 GB/s 显示成 1024.0 MB/s"这种自己跟自己打架的输出
     * （旧组件副本就是这样只做到 MB 的）。
     *
     * 小于 1 KB 时**不返回小数**：`950` 比 `950.0` 少两个字符，在组件上值这 2dp。
     */
    fun formatSpeedCompact(bytesPerSec: Long): String {
        if (bytesPerSec <= 0) return "0"
        if (bytesPerSec < 1024) return bytesPerSec.toString()
        // 单字母单位：组件宽度紧张，比 "1.5MB" 更稳的收益不大，但字符数差一倍。
        val units = arrayOf("", "K", "M", "G", "T")
        val digitGroups = (Math.log10(bytesPerSec.toDouble()) / Math.log10(1024.0)).toInt()
            .coerceIn(1, units.size - 1)
        val value = bytesPerSec / Math.pow(1024.0, digitGroups.toDouble())
        return String.format(java.util.Locale.US, "%.1f%s", value, units[digitGroups])
    }
}

