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
 * 列表里每一项都带着**本地 subId**（见 `SubscriptionManager.SubEntry`），导入时原样沿用，
 * 于是"节点 ↔ 订阅"的关联在恢复后仍然成立（节点那侧带的 subId 来自同一份备份）。
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

        // 去重口径没变（同 URL = 同一条订阅，本机已有的优先 —— 本机那批在前），
        // 但**保留了备份里带的 subId**：同机恢复时关联不漂移；跨设备恢复时
        // 节点那侧带的 subId 也来自同一份备份，两边照样对得上。
        // 旧备份没有 subId（Gson 给空串）→ 由 saveSubscriptions 分配新 id，退化成旧的按 URL 连。
        val merged = LinkedHashMap<String, SubscriptionManager.SubEntry>()
        val seenUrls = HashSet<String>()
        for (entry in SubscriptionManager.getSubscriptions(context) + incoming) {
            if (entry.url.isBlank()) continue
            if (!seenUrls.add(entry.url)) continue
            merged[entry.subId.ifBlank { "url:${entry.url}" }] = entry
        }
        SubscriptionManager.saveSubscriptions(context, merged.values.toList())
    }
}
