package app.fjj.stun.backup

import android.content.Context

/**
 * 一个「可插拔的备份分区」。
 *
 * 备份链路只做两件事：遍历 [BackupSections.all]，把每个分区返回的明文 JSON
 * 各自加密后写入云端一个文件（`<fileName>`）。新增一类需要云备份的设置，
 * 只需实现本接口并登记到 [BackupSections]，**不需要改 WebDavBackupManager**
 * ——这就是「可插拔」的全部含义。
 *
 * 约定（实现方必须遵守）：
 * - [export] 返回 null 表示本次无内容（例如订阅列表为空），该分区文件不上传，
 *   也不会因此把已有云端文件删掉。
 * - [import] 必须容忍重复执行，且**不得覆盖本地已有数据**造成丢失
 *   （订阅是「按 url 取并集」，不是直接替换）。
 * - 分区文件整体已受备份 PIN 加密保护，因此分区内部**不需要**再加密；
 *   但设备态（本机路径、选中项、时间戳）不得进入分区，
 *   Keystore 密文也不得原样进入分区（应交给 SettingsBackupCodec 处理）。
 */
interface BackupSection {
    /** 稳定标识，用于日志与默认文件名。 */
    val id: String

    /**
     * 分区名的本地化资源（`app.fjj.stun.core.R.string.*`）；0 = 不在 UI 里点名。
     *
     * 放在接口上而不是让调用方按 id 硬编码，是为了「新增分区只改这一处」：
     * 只说一句「备份了 N 个分区」，用户根本不知道这个数字包不包括他的订阅；
     * 而按 id 在 UI 层翻译，漏一个新增分区就会在弹窗里露出 `section_xxx` 这种内部串。
     */
    val labelRes: Int get() = 0

    /**
     * 云端文件名。默认 `section_<id>.json.enc`。
     * 「settings」分区保留历史文件名 `settings.json.enc`，以便已经存在的云备份仍可恢复。
     */
    val fileName: String get() = "section_$id.json.enc"

    /** 导出明文 JSON；返回 null 表示本次无可导出内容。 */
    fun export(context: Context): String?

    /** 导入明文 JSON。导入失败应抛异常，由调用方捕获并跳过该分区。 */
    fun import(context: Context, json: String)
}

/**
 * 备份分区注册表 —— 新增分区只在这里加一行。
 *
 * **顺序有意义**：导入按列表顺序执行，后面的分区可以依赖前面已落盘的数据
 * （如 subscription_usage 需要先有订阅列表才能裁掉孤儿流量记录）。
 *
 * 说明：节点（profiles）**不**是分区，它是必需载荷，有自己的按 id 合并语义，
 * 由 [WebDavBackupManager] 直接处理，不参与「失败可跳过」的分区循环。
 */
object BackupSections {

    private val sections: List<BackupSection> = listOf(
        SettingsBackupSection,
        SubscriptionBackupSection,
        SubscriptionUsageBackupSection,
    )

    val all: List<BackupSection> get() = sections

    fun byId(id: String): BackupSection? = sections.firstOrNull { it.id == id }
}
