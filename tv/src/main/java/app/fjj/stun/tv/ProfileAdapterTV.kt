package app.fjj.stun.tv

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.repo.Profile
import app.fjj.stun.util.AppUtils
import com.google.android.material.card.MaterialCardView
import java.util.Locale

class ProfileAdapterTV(
    private var selectedId: String?,
    private val onProfileClick: (Profile) -> Unit,
    private val onProfileLongClick: ((Profile) -> Unit)? = null
) : ListAdapter<Profile, ProfileAdapterTV.ViewHolder>(ProfileDiffCallback()) {

    companion object {
        private const val PAYLOAD_DELAY = "payload_delay"
        private const val PAYLOAD_TRAFFIC = "payload_traffic"
        private const val PAYLOAD_SELECTED = "payload_selected"
        private const val DELAY_PLACEHOLDER = "—"
    }

    /** 节点名里直接写了旗子 emoji 就认它，别去查地区词。 */
    private val FLAG_REGEX = Regex("[\\uD83C][\\uDDE6-\\uDDFF][\\uD83C][\\uDDE6-\\uDDFF]")

    private class FlagRule(val emoji: String, val keywords: List<String>)

    // 顺序敏感（先命中的赢），与旧版逐行 when 的判定顺序一致
    private val FLAG_RULES = listOf(
        FlagRule("🇭🇰", listOf("香港", "hk", "hongkong")),
        FlagRule("🇯🇵", listOf("日本", "jp", "japan", "东京", "大阪")),
        FlagRule("🇺🇸", listOf("美国", "us", "usa", "洛杉矶", "硅谷")),
        FlagRule("🇸🇬", listOf("新加坡", "sg", "singapore", "狮城")),
        FlagRule("🇹🇼", listOf("台湾", "tw", "taiwan", "台北")),
        FlagRule("🇩🇪", listOf("德国", "de", "germany", "法兰克福")),
        FlagRule("🇬🇧", listOf("英国", "uk", "london", "伦敦")),
        FlagRule("🇰🇷", listOf("韩国", "kr", "korea", "首尔")),
        FlagRule("🇫🇷", listOf("法国", "fr", "france", "巴黎")),
        FlagRule("🇨🇦", listOf("加拿大", "ca", "canada")),
        FlagRule("🇦🇺", listOf("澳大利亚", "au", "australia", "悉尼")),
        FlagRule("🇨🇳", listOf("中国", "cn", "china")),
    )

    // 测速结果（内存态，不落库），与手机端 adapter.delays 语义一致
    private val delays = mutableMapOf<String, String>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.cardView)
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvAddr: TextView = view.findViewById(R.id.tvAddr)
        val tvAvatar: TextView = view.findViewById(R.id.tvAvatar)
        val tvType: TextView = view.findViewById(R.id.tvType)
        val tvSubBadge: TextView = view.findViewById(R.id.tvSubBadge)
        val tvTraffic: TextView = view.findViewById(R.id.tvTraffic)
        val tvDelay: TextView = view.findViewById(R.id.tvDelay)
        val tvSelectedBadge: TextView = view.findViewById(R.id.tvSelectedBadge)
        val tvFocusHint: TextView = view.findViewById(R.id.tvFocusHint)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_profile_tv, parent, false)
        return ViewHolder(view)
    }

    /** Cancel stale animations and reset transform when a ViewHolder is recycled. */
    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.card.animate().cancel()
        holder.card.scaleX = 1.0f
        holder.card.scaleY = 1.0f
        holder.card.alpha = 1.0f
        holder.card.cardElevation = 3f
        holder.tvFocusHint.visibility = View.GONE
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        bind(holder, getItem(position))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            bind(holder, getItem(position))
            return
        }
        val profile = getItem(position)
        if (payloads.contains(PAYLOAD_TRAFFIC)) {
            holder.tvTraffic.text = trafficText(holder.itemView.context, profile)
        }
        if (payloads.contains(PAYLOAD_DELAY)) {
            renderDelay(holder, profile)
        }
        if (payloads.contains(PAYLOAD_SELECTED)) {
            updateSelectionVisuals(holder, profile)
        }
    }

    private fun bind(holder: ViewHolder, profile: Profile) {
        val context = holder.itemView.context

        holder.tvName.text = profile.name
        holder.tvAddr.text = profile.sshAddr
        holder.tvAvatar.text = getFlagOrMonogram(profile.name)
        holder.tvType.text = profileTypeLabel(profile)

        // 子特征徽标：UDP 的 magic/PSK、DNS/KCP 的加密方式、其余是认证方式
        val subFeature = buildSubFeatureText(context, profile)
        if (subFeature.isNotBlank()) {
            holder.tvSubBadge.text = subFeature
            holder.tvSubBadge.visibility = View.VISIBLE
        } else {
            holder.tvSubBadge.visibility = View.GONE
        }

        // 节点累计流量（来自 Room，随引擎 addTrafficStats 实时刷新）
        holder.tvTraffic.text = trafficText(context, profile)

        // 节点测速延迟（内存态）与颜色
        renderDelay(holder, profile)

        updateSelectionVisuals(holder, profile)

        holder.itemView.setOnClickListener {
            val oldSelectedId = selectedId
            selectedId = profile.id
            // 精确刷新：只刷选中新旧两项，不全量重绘（遥控器上切节点会闪一下）
            notifySelectionChange(oldSelectedId, profile.id)
            onProfileClick(profile)
        }
        holder.itemView.setOnLongClickListener {
            onProfileLongClick?.invoke(profile)
            true
        }
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            updateSelectionVisuals(holder, profile)
            applyFocusVisuals(holder, hasFocus)
        }
    }

    /**
     * 选中态 × 焦点态的样式矩阵。bind / 焦点变化 / 局部刷新三条路径都过这里，
     * 首帧不至于「全员高亮」、焦点一动才集体变暗。
     */
    private fun updateSelectionVisuals(holder: ViewHolder, profile: Profile) {
        val context = holder.itemView.context
        val selected = profile.id == selectedId
        val focused = holder.itemView.hasFocus()

        holder.tvSelectedBadge.visibility = if (selected) View.VISIBLE else View.INVISIBLE
        holder.card.strokeColor = when {
            selected && focused -> context.getColor(R.color.tv_focus_border)
            selected -> primaryColor(context)
            focused -> context.getColor(R.color.tv_focus_border)
            else -> context.getColor(R.color.tv_card_stroke)
        }
        holder.card.strokeWidth = when {
            selected && focused -> 5
            selected -> 3
            focused -> 4
            else -> 1
        }
        holder.card.setCardBackgroundColor(when {
            selected && focused -> context.getColor(R.color.tv_card_selected_focused_bg)
            selected -> context.getColor(R.color.tv_card_bg)
            focused -> context.getColor(R.color.tv_card_focused_bg)
            else -> context.getColor(R.color.tv_card_bg)
        })
        // 未聚焦压暗在这里统一驱动
        holder.itemView.alpha = if (focused) 1f else 0.88f
    }

    /** 焦点三件套：焦点音 + 提示条 + 放大/抬升（描边由 updateSelectionVisuals 驱动）。 */
    private fun applyFocusVisuals(holder: ViewHolder, hasFocus: Boolean) {
        if (hasFocus) holder.itemView.playSoundEffect(SoundEffectConstants.CLICK)
        holder.tvFocusHint.visibility = if (hasFocus) View.VISIBLE else View.GONE
        holder.card.cardElevation = if (hasFocus) 12f else 3f

        val scale = if (hasFocus) 1.06f else 1.0f
        holder.card.animate().cancel()
        holder.card.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
    }

    private fun renderDelay(holder: ViewHolder, profile: Profile) {
        val delayStr = delays[profile.id] ?: DELAY_PLACEHOLDER
        holder.tvDelay.text = delayStr
        applyDelayColor(holder.tvDelay, delayStr)
    }

    private fun trafficText(context: Context, profile: Profile): String =
        context.getString(
            CoreR.string.tv_traffic_total_format,
            AppUtils.formatBytes(profile.totalTx),
            AppUtils.formatBytes(profile.totalRx)
        )

    private fun profileTypeLabel(profile: Profile): String = when (profile.tunnelType) {
        Profile.TUNNEL_TYPE_UDP_CUSTOM -> "UDP CUSTOM"
        Profile.TUNNEL_TYPE_DNS -> "DNS"
        Profile.TUNNEL_TYPE_KCP -> "KCP"
        else -> profile.tunnelType.uppercase()
    }

    private fun buildSubFeatureText(context: Context, profile: Profile): String = buildString {
        when (profile.tunnelType) {
            Profile.TUNNEL_TYPE_UDP_CUSTOM -> {
                append(profile.udpCustomMagic.ifBlank { "UDPC" })
                if (profile.udpCustomPsk.isNotBlank()) append(" · 🔒 PSK")
            }
            Profile.TUNNEL_TYPE_DNS -> append(profile.dnsTunnelType.uppercase())
            Profile.TUNNEL_TYPE_KCP -> append(profile.kcpCrypt.uppercase())
            else -> append(authBadge(context, profile))
        }
    }

    private fun authBadge(context: Context, profile: Profile): String =
        if (profile.authType == Profile.AUTH_TYPE_PRIVATEKEY)
            "🗝️ " + context.getString(CoreR.string.auth_private_key_badge)
        else
            "🔑 " + context.getString(CoreR.string.auth_password_badge)

    private fun getFlagOrMonogram(name: String): String =
        FLAG_REGEX.find(name)?.value
            ?: FLAG_RULES.firstOrNull { rule -> rule.keywords.any { name.lowercase().contains(it) } }?.emoji
            ?: name.take(1).uppercase().ifBlank { "🌐" }

    private fun applyDelayColor(tv: TextView, delay: String) {
        val ms = delay.filter(Char::isDigit).toIntOrNull()
        val colorRes = when {
            delay == DELAY_PLACEHOLDER -> CoreR.color.md_theme_light_onSurfaceVariant
            // 只有带 "ms" 的文案才算测得值：「HTTP 200」这类错误串里有数字但不能当延迟上色
            delay.contains("ms", ignoreCase = true) -> when {
                ms == null -> CoreR.color.status_disconnected
                ms < 150 -> CoreR.color.status_connected
                ms < 350 -> CoreR.color.status_connecting
                else -> CoreR.color.status_disconnected
            }
            else -> CoreR.color.status_disconnected
        }
        tv.setTextColor(tv.context.getColor(colorRes))
    }

    fun updateSelectedId(id: String?) {
        val oldId = selectedId
        selectedId = id
        notifySelectionChange(oldId, id)
    }

    private fun notifySelectionChange(oldId: String?, newId: String?) {
        val oldIndex = currentList.indexOfFirst { it.id == oldId }
        val newIndex = currentList.indexOfFirst { it.id == newId }
        if (oldIndex != -1) notifyItemChanged(oldIndex, PAYLOAD_SELECTED)
        if (newIndex != -1 && newIndex != oldIndex) notifyItemChanged(newIndex, PAYLOAD_SELECTED)
    }

    fun updateDelay(profileId: String, delay: String) {
        delays[profileId] = delay
        val index = currentList.indexOfFirst { it.id == profileId }
        if (index != -1) {
            notifyItemChanged(index, PAYLOAD_DELAY)
        }
    }

    private fun primaryColor(context: Context): Int =
        getThemeColor(context, "colorPrimary", context.getColor(CoreR.color.md_theme_light_primary))

    private fun getThemeColor(context: Context, attrName: String, default: Int): Int {
        val attrId = context.resources.getIdentifier(attrName, "attr", context.packageName).takeIf { it != 0 }
            ?: context.resources.getIdentifier(attrName, "attr", "android").takeIf { it != 0 }
            ?: return default

        val typedValue = TypedValue()
        return if (context.theme.resolveAttribute(attrId, typedValue, true)) {
            if (typedValue.resourceId != 0) {
                ContextCompat.getColor(context, typedValue.resourceId)
            } else {
                typedValue.data
            }
        } else {
            default
        }
    }

    class ProfileDiffCallback : DiffUtil.ItemCallback<Profile>() {
        override fun areItemsTheSame(oldItem: Profile, newItem: Profile) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Profile, newItem: Profile) = oldItem == newItem

        override fun getChangePayload(oldItem: Profile, newItem: Profile): Any? {
            // When only totalTx/totalRx changes (1Hz background traffic tick), send payload to avoid full re-render & blinking
            if (oldItem.copy(totalTx = newItem.totalTx, totalRx = newItem.totalRx) == newItem) {
                return PAYLOAD_TRAFFIC
            }
            return super.getChangePayload(oldItem, newItem)
        }
    }
}
