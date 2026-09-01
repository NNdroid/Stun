package app.fjj.stun.car

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import app.fjj.stun.car.databinding.ItemProfileCarBinding
import app.fjj.stun.repo.Profile

class ProfileAdapterCar(
    private var selectedProfileId: String?,
    private val onProfileClick: (Profile) -> Unit
) : RecyclerView.Adapter<ProfileAdapterCar.CarViewHolder>() {

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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CarViewHolder {
        val binding = ItemProfileCarBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CarViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CarViewHolder, position: Int) {
        val profile = profiles[position]
        holder.bind(profile)
    }

    override fun getItemCount(): Int = profiles.size

    inner class CarViewHolder(private val binding: ItemProfileCarBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(profile: Profile) {
            val isSelected = profile.id == selectedProfileId
            
            binding.tvCarItemName.text = profile.name
            binding.tvCarItemAddr.text = if (profile.proxyAddr.isNotBlank()) profile.proxyAddr else profile.sshAddr
            binding.tvCarItemType.text = profile.tunnelType.uppercase()

            val delay = delayMap[profile.id] ?: ""
            binding.tvCarItemDelay.text = delay
            if (delay.contains("ms")) {
                binding.tvCarItemDelay.setTextColor(Color.parseColor("#4CAF50"))
            } else {
                binding.tvCarItemDelay.setTextColor(Color.parseColor("#FF9800"))
            }

            binding.carItemActiveDot.visibility = if (isSelected) View.VISIBLE else View.GONE
            
            val context = binding.root.context
            val primaryColor = getThemeColor(context, "colorPrimary", Color.GREEN)

            binding.cardCarItem.strokeColor = if (isSelected) primaryColor else Color.TRANSPARENT
            binding.cardCarItem.strokeWidth = if (isSelected) 6 else 0

            binding.root.setOnClickListener {
                onProfileClick(profile)
            }
        }

        private fun getThemeColor(context: android.content.Context, attrName: String, defaultColor: Int): Int {
            val attrId = context.resources.getIdentifier(attrName, "attr", context.packageName).takeIf { it != 0 }
                ?: context.resources.getIdentifier(attrName, "attr", "android").takeIf { it != 0 }
                ?: return defaultColor
            val typedValue = android.util.TypedValue()
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
