package app.fjj.stun.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import app.fjj.stun.databinding.ItemProfileBinding
import app.fjj.stun.repo.Profile

class ProfileAdapter(
    private var profiles: List<Profile>,
    private var selectedProfileId: String?,
    private val onProfileClick: (Profile) -> Unit,
    private val onEditClick: (Profile) -> Unit,
    private val onDeleteClick: (Profile) -> Unit,
    private val onShareClick: (Profile) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.ProfileViewHolder>() {

    private var allProfiles: List<Profile> = emptyList()
    private val delays = mutableMapOf<String, String>()

    inner class ProfileViewHolder(val binding: ItemProfileBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
        val binding = ItemProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ProfileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
        val profile = profiles[position]
        holder.binding.apply {
            tvName.text = profile.name
            
            // Build the specific proxy chain display
            val chain = if (profile.tunnelType == Profile.TUNNEL_TYPE_BASE) {
                profile.sshAddr
            } else {
                val sb = StringBuilder()
                sb.append(profile.proxyAddr)
                sb.append(" (")
                sb.append(profile.tunnelType.uppercase())
                
                // Show custom host if present
                if (profile.customHost.isNotBlank()) {
                    sb.append(" @").append(profile.customHost)
                }
                
                // Show custom path for appropriate types
                val needsPath = profile.tunnelType in listOf(
                    Profile.TUNNEL_TYPE_WS, Profile.TUNNEL_TYPE_WSS,
                    Profile.TUNNEL_TYPE_H2, Profile.TUNNEL_TYPE_H2C
                )
                if (needsPath && profile.customPath.isNotBlank()) {
                    sb.append(profile.customPath)
                }
                
                sb.append(") ➔ ")
                sb.append(profile.sshAddr)
                sb.toString()
            }
            
            tvAddr.text = chain
            tvType.text = profile.tunnelType.uppercase()
            
            val isSelected = profile.id == selectedProfileId
            selectionIndicator.visibility = if (isSelected) View.VISIBLE else View.GONE
            cardView.strokeWidth = if (isSelected) 2 else 0
            cardView.strokeColor = holder.binding.root.context.getColor(app.fjj.stun.R.color.primary)
            
            tvDelay.text = delays[profile.id] ?: ""

            root.setOnClickListener { onProfileClick(profile) }
            btnEdit.setOnClickListener { onEditClick(profile) }
            btnDelete.setOnClickListener { onDeleteClick(profile) }
            btnShare.setOnClickListener { onShareClick(profile) }
        }
    }

    override fun getItemCount() = profiles.size

    fun getProfiles() = profiles

    fun updateProfiles(newProfiles: List<Profile>, newSelectedId: String?) {
        allProfiles = newProfiles
        selectedProfileId = newSelectedId
        // Maintain current filter if possible, or just reset for now
        profiles = newProfiles
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        profiles = if (query.isEmpty()) {
            allProfiles
        } else {
            allProfiles.filter { it.name.contains(query, ignoreCase = true) }
        }
        notifyDataSetChanged()
    }

    fun updateDelay(profileId: String, delay: String) {
        delays[profileId] = delay
        val index = profiles.indexOfFirst { it.id == profileId }
        if (index != -1) {
            notifyItemChanged(index)
        }
    }
}
