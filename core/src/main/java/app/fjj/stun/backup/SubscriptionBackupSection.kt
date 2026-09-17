package app.fjj.stun.backup

import android.content.Context
import app.fjj.stun.repo.SubscriptionManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 订阅分区：把订阅链接（及加密订阅的 PIN）纳入云备份。
 *
 * 改造前这块数据存在独立的 `stun_subscription_prefs` 里，**完全没有参与备份** ——
 * 这正是「每加一类设置就得再写一段备份代码」的典型案例。
 *
 * 导入采用「按 url 取并集」而不是直接替换，避免恢复旧备份时把本机新增的订阅冲掉。
 * 订阅列表为空时不导出（[export] 返回 null），也就不会在云端留下空文件。
 */
object SubscriptionBackupSection : BackupSection {

    override val id: String = "subscription"

    override val labelRes: Int = app.fjj.stun.core.R.string.webdav_section_subscription

    override fun export(context: Context): String? {
        val subs = SubscriptionManager.getSubscriptions(context)
        return if (subs.isEmpty()) null else Gson().toJson(subs)
    }

    override fun import(context: Context, json: String) {
        // 显式给出类型实参：否则 `orEmpty()` 会命中 CharSequence 重载导致推断成 String
        val type = object : TypeToken<List<SubscriptionManager.SubEntry>>() {}.type
        val incoming: List<SubscriptionManager.SubEntry> =
            Gson().fromJson<List<SubscriptionManager.SubEntry>>(json, type) ?: emptyList()
        if (incoming.isEmpty()) return

        val existing = SubscriptionManager.getSubscriptions(context)
        val merged = (existing + incoming)
            .filter { it.url.isNotBlank() }
            .distinctBy { it.url }
        SubscriptionManager.saveSubscriptions(context, merged)
    }
}
