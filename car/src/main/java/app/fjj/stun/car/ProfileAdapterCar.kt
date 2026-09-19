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
            // 成功判定不能只认 "ms"：zh 的 latency_format 是「%1$d 毫秒」。
            // 颜色走 core 的语义色，DayNight 两套主题都正确。
            val context = binding.root.context
            if (delay.contains("ms") || delay.contains("毫秒")) {
                binding.tvCarItemDelay.setTextColor(context.getColor(app.fjj.stun.core.R.color.status_connected))
            } else {
                binding.tvCarItemDelay.setTextColor(context.getColor(app.fjj.stun.core.R.color.status_connecting))
            }

            binding.carItemActiveDot.visibility = if (isSelected) View.VISIBLE else View.GONE

            val primaryColor = com.google.android.material.color.MaterialColors.getColor(
                binding.root, androidx.appcompat.R.attr.colorPrimary
            )

            binding.cardCarItem.strokeColor = if (isSelected) primaryColor else Color.TRANSPARENT
            binding.cardCarItem.strokeWidth = if (isSelected) 6 else 0

            binding.root.setOnClickListener {
                onProfileClick(profile)
            }
        }
    }
}
