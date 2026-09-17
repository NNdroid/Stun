package app.fjj.stun.util

import android.content.Context
import androidx.core.content.edit
import app.fjj.stun.repo.SettingsManager

/**
 * 当前出口（Exit）信息的落盘缓存：出口 IP + 位置（含国旗 emoji）+ 探测时间。
 *
 * 存在的理由：出口 IP 是 [ExitIpProbe] 在"连接已建立"时探测出来的，而桌面小组件是
 * 独立进程生命周期（AppWidgetProvider 可能在没有 Activity 的情况下被刷新），
 * 只放内存（HomeFragment 的字段）的话进程一死小组件就再也拿不到，只能显示占位符。
 *
 * 存放位置：`SettingsManager.deviceState` —— 设备态库，**永不参与云备份**。
 * 这条信息换机后毫无意义，且出口 IP 属于隐私，不该跟着备份跑到别的设备上。
 *
 * 写入方：HomeFragment 在探测成功/断开时调用。
 * 读取方：StunAppWidgetProvider（渲染）、HomeFragment（进程重启后回填）。
 */
object ExitInfoStore {

    private const val KEY_IP = "exit_info_ip"
    private const val KEY_LOCATION = "exit_info_location"
    private const val KEY_UPDATED_AT = "exit_info_updated_at"

    /** 出口信息。[displayText] 与旧版内存字段 `"$ip · $location"` 保持同一形态。 */
    data class Info(val ip: String, val location: String, val updatedAt: Long) {

        /** 一行展示：`203.0.113.8 · 🇸🇬 Singapore`；无位置时退化为纯 IP。 */
        val displayText: String get() = if (location.isBlank()) ip else "$ip · $location"

        val isBlank: Boolean get() = ip.isBlank() && location.isBlank()
    }

    /**
     * 写入出口信息。返回 true 表示内容确实变了——调用方据此决定要不要刷新小组件，
     * 避免每次状态机抖动都广播一轮 APPWIDGET_UPDATE。
     */
    fun save(context: Context, ip: String, location: String): Boolean {
        val trimmedIp = ip.trim()
        val trimmedLocation = location.trim()
        if (trimmedIp.isEmpty() && trimmedLocation.isEmpty()) return clear(context)
        val current = read(context)
        if (current != null && current.ip == trimmedIp && current.location == trimmedLocation) return false
        SettingsManager.deviceState(context).edit {
            putString(KEY_IP, trimmedIp)
            putString(KEY_LOCATION, trimmedLocation)
            putLong(KEY_UPDATED_AT, System.currentTimeMillis())
        }
        return true
    }

    /** 读取当前出口信息；从未探测过时返回 null。 */
    fun read(context: Context): Info? {
        val prefs = SettingsManager.deviceState(context)
        val info = Info(
            ip = prefs.getString(KEY_IP, null).orEmpty(),
            location = prefs.getString(KEY_LOCATION, null).orEmpty(),
            updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L),
        )
        return info.takeUnless { it.isBlank }
    }

    /**
     * 断开连接时清空。返回 true 表示之前确实有值——同样用于抑制多余的组件刷新。
     * 出口 IP 属于"这次会话"的事实，断开后留着会误导用户。
     */
    fun clear(context: Context): Boolean {
        val prefs = SettingsManager.deviceState(context)
        if (!prefs.contains(KEY_IP) && !prefs.contains(KEY_LOCATION)) return false
        prefs.edit {
            remove(KEY_IP)
            remove(KEY_LOCATION)
            remove(KEY_UPDATED_AT)
        }
        return true
    }

    /** 便捷读取：只要用于展示的一行文本，没有则返回 null。 */
    fun label(context: Context): String? = read(context)?.displayText
}
