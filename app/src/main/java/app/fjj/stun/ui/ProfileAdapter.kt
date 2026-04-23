package app.fjj.stun.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.fjj.stun.databinding.ItemProfileBinding
import app.fjj.stun.repo.Profile

class ProfileAdapter(
    private var selectedProfileId: String?,
    private val onProfileClick: (Profile) -> Unit,
    private val onEditClick: (Profile) -> Unit,
    private val onDeleteClick: (Profile) -> Unit,
    private val onShareClick: (Profile) -> Unit
) : ListAdapter<Profile, ProfileAdapter.ProfileViewHolder>(ProfileDiffCallback()) {

    private var allProfiles: List<Profile> = emptyList()
    private val delays = mutableMapOf<String, String>()
    private var currentQuery: String = ""

    inner class ProfileViewHolder(val binding: ItemProfileBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
        val binding = ItemProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ProfileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
        val profile = getItem(position)
        holder.binding.apply {
            tvName.text = profile.name
            
            // Build the specific proxy chain display
            val chain = if (profile.tunnelType == Profile.TUNNEL_TYPE_BASE) {
                profile.sshAddr
            } else {
                "${profile.proxyAddr} ➔ ${profile.sshAddr}"
            }
            
            tvAddr.text = chain
            tvType.text = profile.tunnelType.uppercase()
            
            val isSelected = profile.id == selectedProfileId
            selectionIndicator.visibility = if (isSelected) View.VISIBLE else View.GONE
            cardView.strokeWidth = if (isSelected) 2 else 0
            cardView.strokeColor = holder.binding.root.context.getColor(app.fjj.stun.R.color.primary)
            
            tvDelay.text = delays[profile.id] ?: ""

            if (profile.totalTx > 0 || profile.totalRx > 0) {
                tvStats.visibility = View.VISIBLE
                tvStats.text = "↑ ${formatBytes(profile.totalTx)}  ↓ ${formatBytes(profile.totalRx)}"
            } else {
                tvStats.visibility = View.GONE
            }

            root.setOnClickListener { onProfileClick(profile) }
            btnEdit.setOnClickListener { onEditClick(profile) }
            btnDelete.setOnClickListener { onDeleteClick(profile) }
            btnShare.setOnClickListener { onShareClick(profile) }
        }
    }

    fun getProfiles() = allProfiles

    fun updateProfiles(newProfiles: List<Profile>, newSelectedId: String?) {
        val selectionChanged = selectedProfileId != newSelectedId
        allProfiles = newProfiles
        selectedProfileId = newSelectedId
        
        applyFilterAndSubmit()
        
        // If only the selection changed but the list content is the same, 
        // ListAdapter might not re-bind. We might need to manually refresh visible items 
        // or include isSelected in the DiffUtil (which requires a wrapper).
        // For simplicity, if selection changed, we refresh the list.
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
            notifyItemChanged(index)
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
    }
}
