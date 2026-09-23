package app.fjj.stun.backup

/**
 * WebDAV 同步模式。
 *
 * 三个档位的数据流见 [WebDavBackupManager.sync]：
 * - [UPLOAD]（默认）只把本机快照推上云 —— 这正是本功能引入之前**唯一**存在的行为，
 *   所以默认值定在它，老用户升级后行为零变化。
 * - [DOWNLOAD] 只把云端较新的内容拉回本机，不产生新快照。
 * - [BOTH] 先按内容修改时间把云端较新的分区拉回，再把合并后的结果推一份新快照。
 *
 * ⚠️ 本值存**设备态**库（`stun_device_state`），不参与云备份。
 *
 * 理由：它决定「本机是否参与同步」，是本机策略而非共享设置。若它能被云端恢复覆盖，
 * 就会出现自指 —— 设备 A 是「仅上传」、设备 B 是「仅下载」，B 恢复一次设置后模式被
 * A 的值改掉，下一轮的同步行为就跟着突变。与备份 PIN（`webdav_pin`）同属
 * 「钥匙类」设备态，从结构上就进不了自己的备份。
 */
enum class WebDavSyncMode(val id: String) {
    UPLOAD("upload"),
    DOWNLOAD("download"),
    BOTH("both");

    /** 是否要把云端内容拉回本机。 */
    val pulls: Boolean get() = this != UPLOAD

    /** 是否要把本机内容推上云。 */
    val pushes: Boolean get() = this != DOWNLOAD

    companion object {
        val DEFAULT = UPLOAD

        private val byId: Map<String, WebDavSyncMode> = entries.associateBy { it.id }

        /**
         * 未知 / 空 / 大小写混杂一律回落默认档。
         *
         * 必须容错而不是抛：这个键在旧版本里根本不存在，升级后第一次读到的是 null；
         * 而从 WebUI / MCP 传进来的值又是外部输入（可能被手工改坏）。
         */
        fun fromId(raw: String?): WebDavSyncMode =
            raw?.trim()?.lowercase()?.let { byId[it] } ?: DEFAULT
    }
}
