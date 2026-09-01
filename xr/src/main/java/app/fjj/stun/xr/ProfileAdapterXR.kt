package app.fjj.stun.xr

import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import app.fjj.stun.repo.Profile
import app.fjj.stun.xr.databinding.ItemProfileXrBinding

class ProfileAdapterXR(
    private var selectedProfileId: String?,
    private val onProfileClick: (Profile) -> Unit
) : RecyclerView.Adapter<ProfileAdapterXR.XRViewHolder>() {

    private val profiles = mutableListOf<Profile>()
    private val delayMap = mutableMapOf<String, String>()

    fun updateProfiles(newProfiles: List<Profile>, selectedId: String?) {
        profiles.clear()
        profiles.addAll(newProfiles)
        selectedProfileId = selectedId
        notifyDataSetChanged()
    }

    fun updateDelay(profileId: String, delayStr: String) {
        delayMap[profileId] = delayStr
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): XRViewHolder {
        val binding = ItemProfileXrBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return XRViewHolder(binding)
    }

    override fun onBindViewHolder(holder: XRViewHolder, position: Int) {
        holder.bind(profiles[position])
    }

    override fun getItemCount(): Int = profiles.size

    inner class XRViewHolder(private val binding: ItemProfileXrBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(profile: Profile) {
            val isSelected = profile.id == selectedProfileId

            binding.tvXrItemName.text = profile.name
            binding.tvXrItemAddr.text = if (profile.proxyAddr.isNotBlank()) profile.proxyAddr else profile.sshAddr
            binding.tvXrItemType.text = profile.tunnelType.uppercase()

            val delay = delayMap[profile.id] ?: ""
            binding.tvXrItemDelay.text = delay
            if (delay.contains("ms")) {
                binding.tvXrItemDelay.setTextColor(Color.parseColor("#4CAF50"))
            } else {
                binding.tvXrItemDelay.setTextColor(Color.parseColor("#FF9800"))
            }

            binding.xrItemActiveDot.visibility = if (isSelected) View.VISIBLE else View.GONE

            val context = binding.root.context
            val primaryColor = getThemeColor(context, "colorPrimary", Color.GREEN)

            binding.cardXrItem.strokeColor = if (isSelected) primaryColor else Color.TRANSPARENT
            binding.cardXrItem.strokeWidth = if (isSelected) 6 else 0

            binding.root.setOnClickListener {
                onProfileClick(profile)
            }
        }

        private fun getThemeColor(context: android.content.Context, attrName: String, defaultColor: Int): Int {
            val attrId = context.resources.getIdentifier(attrName, "attr", context.packageName).takeIf { it != 0 }
                ?: context.resources.getIdentifier(attrName, "attr", "android").takeIf { it != 0 }
                ?: return defaultColor
            val typedValue = TypedValue()
            return if (context.theme.resolveAttribute(attrId, typedValue, true)) {
                if (typedValue.resourceId != 0) {
                    androidx.core.content.ContextCompat.getColor(context, typedValue.resourceId)
                } else {
                    typedValue.data
                }
            } else defaultColor
        }
    }
}
