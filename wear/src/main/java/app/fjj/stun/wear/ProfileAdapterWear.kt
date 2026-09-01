package app.fjj.stun.wear

import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import app.fjj.stun.repo.Profile
import app.fjj.stun.wear.databinding.ItemProfileWearBinding

class ProfileAdapterWear(
    private var selectedProfileId: String?,
    private val onProfileClick: (Profile) -> Unit
) : RecyclerView.Adapter<ProfileAdapterWear.WearViewHolder>() {

    private val profiles = mutableListOf<Profile>()

    fun updateProfiles(newProfiles: List<Profile>, selectedId: String?) {
        profiles.clear()
        profiles.addAll(newProfiles)
        selectedProfileId = selectedId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WearViewHolder {
        val binding = ItemProfileWearBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return WearViewHolder(binding)
    }

    override fun onBindViewHolder(holder: WearViewHolder, position: Int) {
        holder.bind(profiles[position])
    }

    override fun getItemCount(): Int = profiles.size

    inner class WearViewHolder(private val binding: ItemProfileWearBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(profile: Profile) {
            val isSelected = profile.id == selectedProfileId

            binding.tvWearItemName.text = profile.name
            binding.tvWearItemType.text = profile.tunnelType.uppercase()
            binding.wearItemActiveDot.visibility = if (isSelected) View.VISIBLE else View.GONE

            val context = binding.root.context
            val primaryColor = getThemeColor(context, "colorPrimary", Color.GREEN)

            binding.cardWearItem.strokeColor = if (isSelected) primaryColor else Color.TRANSPARENT
            binding.cardWearItem.strokeWidth = if (isSelected) 4 else 0

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
