package app.fjj.stun.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import app.fjj.stun.databinding.ItemProfileBinding
import app.fjj.stun.repo.ConfigManager
import app.fjj.stun.repo.Profile

class ProfileAdapter(
    private var profiles: List<Profile>,
    private var selectedProfileId: String?,
    private val onProfileClick: (Profile) -> Unit,
    private val onEditClick: (Profile) -> Unit,
    private val onDeleteClick: (Profile) -> Unit,
    private val onShareClick: (Profile) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.ProfileViewHolder>() {

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
            tvAddr.text = "${profile.sshAddr}"
            tvType.text = profile.type
            
            val isSelected = profile.id == selectedProfileId
            selectionIndicator.visibility = if (isSelected) View.VISIBLE else View.GONE
            cardView.strokeWidth = if (isSelected) 4 else 0
            
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
        profiles = newProfiles
        selectedProfileId = newSelectedId
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
