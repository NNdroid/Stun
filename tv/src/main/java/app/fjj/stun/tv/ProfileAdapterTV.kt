package app.fjj.stun.tv

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.fjj.stun.repo.Profile
import com.google.android.material.card.MaterialCardView

class ProfileAdapterTV(
    private var selectedId: String?,
    private val onProfileClick: (Profile) -> Unit
) : ListAdapter<Profile, ProfileAdapterTV.ViewHolder>(ProfileDiffCallback()) {

    companion object {
        private const val PAYLOAD_DELAY = "payload_delay"
    }

    // 测速结果（内存态，不落库），与手机端 adapter.delays 语义一致
    private val delays = mutableMapOf<String, String>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.cardView)
        val tvName: TextView = view.findViewById(R.id.tvName)
        val tvAddr: TextView = view.findViewById(R.id.tvAddr)
        val tvAvatar: TextView = view.findViewById(R.id.tvAvatar)
        val tvType: TextView = view.findViewById(R.id.tvType)
        val tvTraffic: TextView = view.findViewById(R.id.tvTraffic)
        val tvDelay: TextView = view.findViewById(R.id.tvDelay)
        val ivSelected: ImageView = view.findViewById(R.id.ivSelected)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_profile_tv, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        bind(holder, getItem(position))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_DELAY)) {
            // 仅刷新延迟文字，不影响焦点/选中态
            holder.tvDelay.text = delays[getItem(position).id] ?: "—"
        } else {
            bind(holder, getItem(position))
        }
    }

    private fun bind(holder: ViewHolder, profile: Profile) {
        holder.tvName.text = profile.name
        holder.tvAddr.text = profile.sshAddr
        holder.tvAvatar.text = profile.name.take(1).uppercase()
        holder.tvType.text = profile.tunnelType.uppercase()
        // 节点累计流量（来自 Room，随引擎 addTrafficStats 实时刷新）
        holder.tvTraffic.text = holder.itemView.context.getString(
            app.fjj.stun.core.R.string.tv_traffic_total_format,
            formatBytes(profile.totalTx),
            formatBytes(profile.totalRx)
        )
        // 节点测速延迟（内存态）
        holder.tvDelay.text = delays[profile.id] ?: "—"

        val isSelected = profile.id == selectedId
        holder.ivSelected.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE

        val primaryColor = holder.itemView.context.getColor(app.fjj.stun.core.R.color.md_theme_light_primary)
        val focusBorderColor = Color.parseColor("#FFB77B")

        holder.card.strokeWidth = if (isSelected) 4 else 0
        holder.card.strokeColor = primaryColor

        holder.itemView.setOnClickListener {
            selectedId = profile.id
            notifyDataSetChanged()
            onProfileClick(profile)
        }
        
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            val bgNormal = Color.parseColor("#222222")
            val bgFocused = Color.parseColor("#2C323D")
            val bgSelectedFocused = Color.parseColor("#384252")

            if (isSelected) {
                holder.card.setCardBackgroundColor(if (hasFocus) bgSelectedFocused else bgNormal)
                holder.card.strokeWidth = if (hasFocus) 5 else 4
                holder.card.strokeColor = if (hasFocus) focusBorderColor else primaryColor
            } else {
                holder.card.setCardBackgroundColor(if (hasFocus) bgFocused else bgNormal)
                holder.card.strokeWidth = if (hasFocus) 3 else 0
                holder.card.strokeColor = if (hasFocus) focusBorderColor else Color.TRANSPARENT
            }
            
            val scale = if (hasFocus) 1.04f else 1.0f
            val elevation = if (hasFocus) 12f else 4f
            holder.card.cardElevation = elevation
            holder.card.animate()
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(150)
                .start()
        }
    }

    fun updateSelectedId(id: String?) {
        selectedId = id
        notifyDataSetChanged()
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

    class ProfileDiffCallback : DiffUtil.ItemCallback<Profile>() {
        override fun areItemsTheSame(oldItem: Profile, newItem: Profile) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Profile, newItem: Profile) = oldItem == newItem
    }
}
