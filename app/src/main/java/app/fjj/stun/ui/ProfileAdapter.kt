package app.fjj.stun.ui

import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.fjj.stun.R
import app.fjj.stun.databinding.ItemProfileBinding
import app.fjj.stun.repo.Profile
import androidx.core.graphics.toColorInt

class ProfileAdapter(
    private var selectedProfileId: String?,
    private val onProfileClick: (Profile) -> Unit,
    private val onEditClick: (Profile) -> Unit,
    private val onDeleteClick: (Profile) -> Unit,
    private val onShareClick: (Profile) -> Unit
) : ListAdapter<Profile, ProfileAdapter.ProfileViewHolder>(ProfileDiffCallback()) {

    companion object {
        const val PAYLOAD_TRAFFIC = "payload_traffic"
        const val PAYLOAD_DELAY = "payload_delay"
    }

    private var allProfiles: List<Profile> = emptyList()
    private val delays = mutableMapOf<String, String>()
    private var currentQuery: String = ""

    inner class ProfileViewHolder(val binding: ItemProfileBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
        val binding = ItemProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ProfileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ProfileViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            val profile = getItem(position)
            for (payload in payloads) {
                when (payload) {
                    PAYLOAD_TRAFFIC -> {
                        if (profile.totalTx > 0 || profile.totalRx > 0) {
                            holder.binding.tvStats.visibility = View.VISIBLE
                            holder.binding.tvStats.text = "↑ ${formatBytes(profile.totalTx)}  ↓ ${formatBytes(profile.totalRx)}"
                        } else {
                            holder.binding.tvStats.visibility = View.GONE
                        }
                    }
                    PAYLOAD_DELAY -> {
                        val delay = delays[profile.id] ?: ""
                        holder.binding.tvDelay.text = delay
                        holder.binding.tvDelay.setTextColor(getDelayColor(holder.binding.root.context, delay))
                    }
                }
            }
        }
    }

    override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
        val profile = getItem(position)
        holder.binding.apply {
            val context = root.context
            tvName.text = profile.name
            
            // Avatar letter
            tvAvatarLetter.text = (profile.name.takeIf { it.isNotBlank() }?.firstOrNull()?.toString() ?: "S").uppercase()
            
            // Build the specific proxy chain display
            val chain = if (profile.tunnelType == Profile.TUNNEL_TYPE_BASE) {
                profile.sshAddr
            } else {
                "${profile.proxyAddr} ➔ ${profile.sshAddr}"
            }
            tvAddr.text = chain
            
            // User info display
            tvUserInfo.text = context.getString(R.string.label_user, profile.user)
            tvUserInfo.visibility = if (profile.user.isNotBlank()) View.VISIBLE else View.GONE
            
            // Protocol Type
            tvType.text = profile.tunnelType.uppercase()
            
            // SNI & Host Display Logic (Matches ProfileEditActivity)
            val isServerNameSupported = profile.tunnelType in listOf(
                Profile.TUNNEL_TYPE_TLS, Profile.TUNNEL_TYPE_WSS, Profile.TUNNEL_TYPE_H2, Profile.TUNNEL_TYPE_QUIC,
                Profile.TUNNEL_TYPE_GRPC, Profile.TUNNEL_TYPE_H3, Profile.TUNNEL_TYPE_WT, Profile.TUNNEL_TYPE_MASQUE,
                Profile.TUNNEL_TYPE_XHTTP
            )
            
            val isCustomHostSupported = profile.tunnelType != Profile.TUNNEL_TYPE_BASE && 
                    profile.tunnelType != Profile.TUNNEL_TYPE_TLS && 
                    profile.tunnelType != Profile.TUNNEL_TYPE_QUIC

            if (isServerNameSupported && profile.serverName.isNotBlank()) {
                tvSni.text = context.getString(R.string.label_sni, profile.serverName)
                tvSni.visibility = View.VISIBLE
            } else {
                tvSni.visibility = View.GONE
            }

            if (isCustomHostSupported && profile.customHost.isNotBlank()) {
                tvHost.text = context.getString(R.string.label_host, profile.customHost)
                tvHost.visibility = View.VISIBLE
            } else {
                tvHost.visibility = View.GONE
            }
            
            // DNS Badge
            ivDnsBadge.visibility = if (profile.dnsOverride) View.VISIBLE else View.GONE
            
            val isSelected = profile.id == selectedProfileId
            
            // Safe color resolution using runtime lookup
            val primaryColor = getThemeColor(context, "colorPrimary", Color.BLUE)
            val surfaceLow = getThemeColor(context, "colorSurfaceContainerLow", Color.LTGRAY)
            val surfaceHigh = getThemeColor(context, "colorSurfaceContainerHigh", Color.GRAY)

            cardView.strokeWidth = if (isSelected) 3 else 0
            cardView.strokeColor = primaryColor
            
            if (isSelected) {
                cardView.setCardBackgroundColor(surfaceHigh)
            } else {
                cardView.setCardBackgroundColor(surfaceLow)
            }
            
            val delay = delays[profile.id] ?: ""
            tvDelay.text = delay
            tvDelay.setTextColor(getDelayColor(context, delay))

            if (profile.totalTx > 0 || profile.totalRx > 0) {
                tvStats.visibility = View.VISIBLE
                tvStats.text = "↑ ${formatBytes(profile.totalTx)}  ↓ ${formatBytes(profile.totalRx)}"
            } else {
                tvStats.visibility = View.GONE
            }

            root.setOnClickListener { onProfileClick(profile) }
            btnShare.setOnClickListener { onShareClick(profile) }
            btnEdit.setOnClickListener { onEditClick(profile) }
            btnDelete.setOnClickListener { onDeleteClick(profile) }
        }
    }

    private fun getThemeColor(context: android.content.Context, attrName: String, default: Int): Int {
        val attrId = context.resources.getIdentifier(attrName, "attr", context.packageName).takeIf { it != 0 }
            ?: context.resources.getIdentifier(attrName, "attr", "android").takeIf { it != 0 }
            ?: return default
            
        val typedValue = TypedValue()
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

    private fun getDelayColor(context: android.content.Context, delay: String): Int {
        if (delay.isEmpty() || delay == "...") return Color.GRAY
        if (delay.contains("ms")) {
            val ms = delay.replace(" ms", "").toIntOrNull() ?: return Color.GRAY
            return when {
                ms < 200 -> getThemeColor(context, "colorPrimary", Color.GREEN)
                ms < 500 -> getThemeColor(context, "colorTertiary", Color.YELLOW)
                else -> getThemeColor(context, "colorError", Color.RED)
            }
        }
        return getThemeColor(context, "colorError", Color.RED)
    }

    fun getProfiles() = allProfiles

    fun updateProfiles(newProfiles: List<Profile>, newSelectedId: String?) {
        val selectionChanged = selectedProfileId != newSelectedId
        allProfiles = newProfiles
        selectedProfileId = newSelectedId
        
        applyFilterAndSubmit()
        
        if (selectionChanged) {
            notifyDataSetChanged() 
        }
    }

    fun filter(query: String) {
        currentQuery = query
        applyFilterAndSubmit()
    }

    private fun applyFilterAndSubmit() {
        val filteredList = if (currentQuery.isEmpty()) {
            allProfiles
        } else {
            allProfiles.filter { it.name.contains(currentQuery, ignoreCase = true) }
        }
        submitList(filteredList)
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
        val digitGroups = (kotlin.math.log10(bytes.toDouble()) / kotlin.math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        return String.format(java.util.Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    class ProfileDiffCallback : DiffUtil.ItemCallback<Profile>() {
        override fun areItemsTheSame(oldItem: Profile, newItem: Profile): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Profile, newItem: Profile): Boolean {
            return oldItem == newItem
        }

        override fun getChangePayload(oldItem: Profile, newItem: Profile): Any? {
            return if (oldItem.totalTx != newItem.totalTx || oldItem.totalRx != newItem.totalRx) {
                PAYLOAD_TRAFFIC
            } else {
                super.getChangePayload(oldItem, newItem)
            }
        }
    }
}
