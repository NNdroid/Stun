package app.fjj.stun.tv

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.fjj.stun.repo.Profile
import com.google.android.material.card.MaterialCardView

class ProfileAdapterTV(
    private var selectedId: String?,
    private val onProfileClick: (Profile) -> Unit,
    private val onProfileLongClick: ((Profile) -> Unit)? = null
) : ListAdapter<Profile, ProfileAdapterTV.ViewHolder>(ProfileDiffCallback()) {

    companion object {
        private const val PAYLOAD_DELAY = "payload_delay"
        private const val PAYLOAD_TRAFFIC = "payload_traffic"
        private const val PAYLOAD_SELECTED = "payload_selected"
    }

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
            holder.tvTraffic.text = holder.itemView.context.getString(
                app.fjj.stun.core.R.string.tv_traffic_total_format,
                formatBytes(profile.totalTx),
                formatBytes(profile.totalRx)
            )
        }
        if (payloads.contains(PAYLOAD_DELAY)) {
            val delayStr = delays[profile.id] ?: "—"
            holder.tvDelay.text = delayStr
            applyDelayColor(holder.tvDelay, delayStr)
        }
        if (payloads.contains(PAYLOAD_SELECTED)) {
            updateSelectionVisuals(holder, profile)
        }
    }

    private fun updateSelectionVisuals(holder: ViewHolder, profile: Profile) {
        val isSelected = profile.id == selectedId
        holder.tvSelectedBadge.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE

        val context = holder.itemView.context
        val bgNormal = context.getColor(R.color.tv_card_bg)
        val strokeNormal = context.getColor(R.color.tv_card_stroke)
        val primaryColor = getThemeColor(context, "colorPrimary", context.getColor(app.fjj.stun.core.R.color.md_theme_light_primary))
        val focusBorderColor = context.getColor(R.color.tv_focus_border)

        val hasFocus = holder.itemView.hasFocus()
        val bgFocused = context.getColor(R.color.tv_card_focused_bg)
        val bgSelectedFocused = context.getColor(R.color.tv_card_selected_focused_bg)

        if (isSelected) {
            holder.card.setCardBackgroundColor(if (hasFocus) bgSelectedFocused else bgNormal)
            holder.card.strokeWidth = if (hasFocus) 4 else 3
            holder.card.strokeColor = if (hasFocus) focusBorderColor else primaryColor
        } else {
            holder.card.setCardBackgroundColor(if (hasFocus) bgFocused else bgNormal)
            holder.card.strokeWidth = if (hasFocus) 3 else 1
            holder.card.strokeColor = if (hasFocus) focusBorderColor else strokeNormal
        }
    }

    private fun bind(holder: ViewHolder, profile: Profile) {
        holder.tvName.text = profile.name
        holder.tvAddr.text = profile.sshAddr
        holder.tvAvatar.text = getFlagOrMonogram(profile.name)
        
        holder.tvType.text = when (profile.tunnelType) {
            Profile.TUNNEL_TYPE_UDP_CUSTOM -> "UDP CUSTOM"
            Profile.TUNNEL_TYPE_DNS -> "DNS"
            Profile.TUNNEL_TYPE_KCP -> "KCP"
            else -> profile.tunnelType.uppercase()
        }

        // Sub Feature Badge: Magic Header, PSK, Crypt, Auth Type
        val subFeature = buildString {
            when (profile.tunnelType) {
                Profile.TUNNEL_TYPE_UDP_CUSTOM -> {
                    append(profile.udpCustomMagic.ifBlank { "UDPC" })
                    if (profile.udpCustomPsk.isNotBlank()) append(" · 🔒 PSK")
                }
                Profile.TUNNEL_TYPE_DNS -> {
                    append(profile.dnsTunnelType.uppercase())
                }
                Profile.TUNNEL_TYPE_KCP -> {
                    append(profile.kcpCrypt.uppercase())
                }
                else -> {
                    val authName = if (profile.authType == Profile.AUTH_TYPE_PRIVATEKEY) {
                        "🗝️ " + holder.itemView.context.getString(app.fjj.stun.core.R.string.auth_private_key_badge)
                    } else {
                        "🔑 " + holder.itemView.context.getString(app.fjj.stun.core.R.string.auth_password_badge)
                    }
                    append(authName)
                }
            }
        }
        if (subFeature.isNotBlank()) {
            holder.tvSubBadge.text = subFeature
            holder.tvSubBadge.visibility = View.VISIBLE
        } else {
            holder.tvSubBadge.visibility = View.GONE
        }

        // 节点累计流量（来自 Room，随引擎 addTrafficStats 实时刷新）
        holder.tvTraffic.text = holder.itemView.context.getString(
            app.fjj.stun.core.R.string.tv_traffic_total_format,
            formatBytes(profile.totalTx),
            formatBytes(profile.totalRx)
        )
        
        // 节点测速延迟（内存态）与颜色
        val delayStr = delays[profile.id] ?: "—"
        holder.tvDelay.text = delayStr
        applyDelayColor(holder.tvDelay, delayStr)

        updateSelectionVisuals(holder, profile)

        holder.itemView.setOnClickListener {
            val oldSelectedId = selectedId
            selectedId = profile.id
            // Precise refresh: only update old and new selected items, no full redraw flash
            val oldIndex = currentList.indexOfFirst { it.id == oldSelectedId }
            val newIndex = currentList.indexOfFirst { it.id == profile.id }
            if (oldIndex != -1) notifyItemChanged(oldIndex, PAYLOAD_SELECTED)
            if (newIndex != -1 && newIndex != oldIndex) notifyItemChanged(newIndex, PAYLOAD_SELECTED)
            onProfileClick(profile)
        }

        holder.itemView.setOnLongClickListener {
            onProfileLongClick?.invoke(profile)
            true
        }
        
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            holder.tvFocusHint.visibility = if (hasFocus) View.VISIBLE else View.GONE
            updateSelectionVisuals(holder, profile)
            
            val scale = if (hasFocus) 1.03f else 1.0f
            val elevation = if (hasFocus) 10f else 3f
            holder.card.cardElevation = elevation
            holder.card.animate().cancel()
            holder.card.animate()
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(150)
                .start()
        }
    }

    private fun getFlagOrMonogram(name: String): String {
        // Direct Flag Emoji in name
        val flagRegex = Regex("[\\uD83C][\\uDDE6-\\uDDFF][\\uD83C][\\uDDE6-\\uDDFF]")
        val flagMatch = flagRegex.find(name)
        if (flagMatch != null) return flagMatch.value

        val lower = name.lowercase()
        return when {
            lower.contains("香港") || lower.contains("hk") || lower.contains("hongkong") -> "🇭🇰"
            lower.contains("日本") || lower.contains("jp") || lower.contains("japan") || lower.contains("东京") || lower.contains("大阪") -> "🇯🇵"
            lower.contains("美国") || lower.contains("us") || lower.contains("usa") || lower.contains("洛杉矶") || lower.contains("硅谷") -> "🇺🇸"
            lower.contains("新加坡") || lower.contains("sg") || lower.contains("singapore") || lower.contains("狮城") -> "🇸🇬"
            lower.contains("台湾") || lower.contains("tw") || lower.contains("taiwan") || lower.contains("台北") -> "🇹🇼"
            lower.contains("德国") || lower.contains("de") || lower.contains("germany") || lower.contains("法兰克福") -> "🇩🇪"
            lower.contains("英国") || lower.contains("uk") || lower.contains("london") || lower.contains("伦敦") -> "🇬🇧"
            lower.contains("韩国") || lower.contains("kr") || lower.contains("korea") || lower.contains("首尔") -> "🇰🇷"
            lower.contains("法国") || lower.contains("fr") || lower.contains("france") || lower.contains("巴黎") -> "🇫🇷"
            lower.contains("加拿大") || lower.contains("ca") || lower.contains("canada") -> "🇨🇦"
            lower.contains("澳大利亚") || lower.contains("au") || lower.contains("australia") || lower.contains("悉尼") -> "🇦🇺"
            lower.contains("中国") || lower.contains("cn") || lower.contains("china") -> "🇨🇳"
            else -> name.take(1).uppercase().ifBlank { "🌐" }
        }
    }

    private fun applyDelayColor(tv: TextView, delay: String) {
        val context = tv.context
        val color = when {
            delay == "—" -> context.getColor(app.fjj.stun.core.R.color.md_theme_light_onSurfaceVariant)
            delay.contains("ms", ignoreCase = true) -> {
                val ms = delay.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 999
                when {
                    ms < 150 -> context.getColor(app.fjj.stun.core.R.color.status_connected)
                    ms < 350 -> context.getColor(app.fjj.stun.core.R.color.status_connecting)
                    else     -> context.getColor(app.fjj.stun.core.R.color.status_disconnected)
                }
            }
            else -> context.getColor(app.fjj.stun.core.R.color.status_disconnected)
        }
        tv.setTextColor(color)
    }

    fun updateSelectedId(id: String?) {
        val oldId = selectedId
        selectedId = id
        val oldIndex = currentList.indexOfFirst { it.id == oldId }
        val newIndex = currentList.indexOfFirst { it.id == id }
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

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (kotlin.math.log10(bytes.toDouble()) / kotlin.math.log10(1024.0)).toInt()
            .coerceIn(0, units.size - 1)
        return String.format(java.util.Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    private fun getThemeColor(context: android.content.Context, attrName: String, default: Int): Int {
        val attrId = context.resources.getIdentifier(attrName, "attr", context.packageName).takeIf { it != 0 }
            ?: context.resources.getIdentifier(attrName, "attr", "android").takeIf { it != 0 }
            ?: return default

        val typedValue = android.util.TypedValue()
        return if (context.theme.resolveAttribute(attrId, typedValue, true)) {
            if (typedValue.resourceId != 0) {
                androidx.core.content.ContextCompat.getColor(context, typedValue.resourceId)
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
