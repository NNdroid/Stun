package app.fjj.stun.backup

import android.content.Context
import app.fjj.stun.repo.SettingsManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 全局设置分区。
 *
 * 内容来自 [SettingsManager.webDavSettingsSnapshot]，其本身已做到「全量枚举 + 自动分类」，
 * 因此**新增设置字段无需改动本文件**。
 *
 * 文件名刻意沿用历史名 `settings.json.enc`（而非 `section_settings.json.enc`），
 * 这样改造前产生的云备份能被新版直接恢复，新版备份也能被旧版读取 —— 双向兼容。
 */
object SettingsBackupSection : BackupSection {

    override val id: String = "settings"

    override val labelRes: Int = app.fjj.stun.core.R.string.webdav_section_settings

    override val fileName: String = WebDavBackupManager.SETTINGS_FILE_NAME

    override fun export(context: Context): String? =
        Gson().toJson(SettingsManager.webDavSettingsSnapshot(context))

    override fun import(context: Context, json: String) {
        // 注意：必须声明为不可变 Map —— MutableMap 在 V 上不变，不能赋给 Map<String, Map<..>>
        val type = object : TypeToken<Map<String, Map<String, Any?>>>() {}.type
        val snapshot: Map<String, Map<String, Any?>> =
            Gson().fromJson<Map<String, Map<String, Any?>>>(json, type)
                ?: throw IllegalArgumentException("settings snapshot is not a JSON object")
        SettingsManager.applyWebDavSettingsSnapshot(context, snapshot)
    }
}
