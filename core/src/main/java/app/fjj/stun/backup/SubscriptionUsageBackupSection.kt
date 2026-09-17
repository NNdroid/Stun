package app.fjj.stun.backup

import android.content.Context
import app.fjj.stun.repo.SubscriptionManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 订阅流量分区：把「已用流量 / 总量 / 到期时间 / 60 点趋势」纳入云备份。
 *
 * 为什么和 [SubscriptionBackupSection] 分成两个文件？两者同一份 PREF_NAME
 * （`stun_subscription_prefs`），按「一个存储一个分区」的惯例本该合并。但合并就得把
 * `section_subscription.json.enc` 从「SubEntry 数组」改成信封对象，而**旧版本 App
 * 读新备份时**会拿数组反序列化这个对象直接抛异常，结果是订阅列表整块恢复失败
 * （节点却恢复成功，用户看着"恢复完成"却少了订阅）。拆成独立文件后，旧版本只是
 * 不认识这个多出来的文件、忽略它，订阅列表照常恢复 —— 单向降级不炸。
 *
 * 注册顺序有约束：必须排在 [SubscriptionBackupSection] 之后。流量表以订阅 URL 为键，
 * 导入时要按"当前订阅列表"裁掉孤儿项，得先让订阅列表落盘。
 */
object SubscriptionUsageBackupSection : BackupSection {

    override val id: String = "subscription_usage"

    override val labelRes: Int = app.fjj.stun.core.R.string.webdav_section_subscription_usage

    override fun export(context: Context): String? {
        val usage = SubscriptionManager.exportUsageSnapshot(context)
        return if (usage.isEmpty()) null else Gson().toJson(usage)
    }

    override fun import(context: Context, json: String) {
        val type = object : TypeToken<List<SubscriptionManager.UsageSnapshot>>() {}.type
        val incoming: List<SubscriptionManager.UsageSnapshot> =
            Gson().fromJson<List<SubscriptionManager.UsageSnapshot>>(json, type) ?: emptyList()
        SubscriptionManager.importUsageSnapshot(context, incoming)
    }
}
