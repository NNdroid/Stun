package app.fjj.stun.repo

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName
import java.util.UUID

/**
 * 订阅（Subscription）—— 一条订阅链接的**本地身份**与其全部附属状态。
 *
 * ## 为什么要有这张表（2026-09-22 重构）
 * 重构前订阅只存在于 SharedPreferences（`stun_subscription_prefs` 的 `subscription_list`），
 * 而 URL 同时充当了**身份键**：`last_sync_map` / `sync_count_map` / `usage_by_url` /
 * `notified_*:$url` 四组旁挂数据都以 URL 字符串为键，`Profile.sourceSubscriptionUrl` 也用 URL
 * 指回订阅。后果是**改一次订阅 URL（换域名 / 服务商迁移），整条关联就漂移**：
 * 用量历史、上次同步时间、导入计数全部变成永远回收不了的孤儿，
 * 而节点上的来源标记还指着旧 URL —— 面板里"这条订阅有多少节点 / 删订阅时清理节点"全部算错。
 * 旧代码只能在 `saveSubscriptions()` 里按当前 URL 集合**裁一遍**这几张表（防无界增长），
 * 等于用"丢历史"来兜住漂移。
 *
 * ## 现在的关系
 * - [subId] 是**本地生成的 UUID，创建后永不改变**，是表的主键，也是所有附属数据与
 *   [Profile.subId] 的关联键。
 * - [url] 降级成一个**普通可变字段**：换域名只是 `UPDATE subscriptions SET url=?`，
 *   用量历史、同步计时、节点归属一个都不动。
 *
 * ## 字段来源（都是原 SharedPreferences 的键搬过来的）
 * | 本表列 | 原 SP 键 |
 * |---|---|
 * | [url]/[pin]/[name]/[homePage]/[updateIntervalHours] | `subscription_list`（`SubEntry` JSON 元素） |
 * | [lastSyncTime] | `subscription_last_sync_map`（url→Long） |
 * | [syncCount] | `subscription_sync_count_map`（url→Int） |
 * | [usageJson] | `subscription_usage_by_url`（url→UsageRecord，**原样 JSON 字符串**搬过来） |
 * | [notifiedOverquota]/[notifiedExpiring] | `subscription_notified_overquota:$url` / `..._expiring:$url`（Boolean） |
 *
 * ⚠️ [usageJson] 刻意存**原始 JSON 字符串**而不是拆成列：`UsageRecord` 的内部形状
 * （日/月基线 + 最多 60 个趋势点）还会演进，整串搬过来可以让搬迁**零解析**，
 * 搬迁失败的爆炸半径也就小得多。
 */
@Keep
@Entity(tableName = "subscriptions")
data class Subscription(
    @PrimaryKey
    @SerializedName("subId")
    var subId: String = UUID.randomUUID().toString(),

    /** 订阅链接。**可变字段**：换域名不再断开关联。 */
    @SerializedName("url")
    var url: String = "",

    /** PIN（加密订阅用；空串=未加密）。 */
    @SerializedName("pin")
    var pin: String = "",

    /** content-disposition 解析出的订阅名（无则为空串）。 */
    @SerializedName("name")
    var name: String = "",

    /** profile-web-page-url 解析出的首页地址（仅 http/https，无则为空串）。 */
    @SerializedName("homePage")
    var homePage: String = "",

    /** profile-update-interval 解析出的自动更新间隔（小时；0=未指定）。 */
    @SerializedName("updateIntervalHours")
    var updateIntervalHours: Int = 0,

    /** 上次成功同步的 epoch ms（0=从未）。原 `subscription_last_sync_map`。 */
    @SerializedName("lastSyncTime")
    var lastSyncTime: Long = 0,

    /** 上次同步导入的节点数（-1=旧数据未记录）。原 `subscription_sync_count_map`。 */
    @SerializedName("syncCount")
    var syncCount: Int = -1,

    /** 该订阅的流量记录（`UsageRecord` 的原始 JSON；空串=无记录）。原 `subscription_usage_by_url`。 */
    @SerializedName("usageJson")
    var usageJson: String = "",

    /** 超额提醒已触发标记（状态跳变去重）。原 `subscription_notified_overquota:$url`。 */
    @SerializedName("notifiedOverquota")
    var notifiedOverquota: Boolean = false,

    /** 即将到期提醒已触发标记。原 `subscription_notified_expiring:$url`。 */
    @SerializedName("notifiedExpiring")
    var notifiedExpiring: Boolean = false,

    /** 订阅面板的展示顺序（越小越靠前）。 */
    @SerializedName("sortIndex")
    var sortIndex: Int = 0
)
