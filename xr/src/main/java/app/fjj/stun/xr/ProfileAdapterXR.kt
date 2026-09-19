package app.fjj.stun.xr

import android.graphics.Color
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

    /** 批量回填测速结果：整轮测速只刷一次，避免 N 个节点触发 N 次全量重绘。 */
    fun updateDelays(results: Map<String, String>) {
        delayMap.putAll(results)
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
            // 成功判定不能只认 "ms"：zh 的 latency_format 是「%1$d 毫秒」。
            // 颜色走 core 的语义色，DayNight 两套主题都正确。
            val context = binding.root.context
            if (delay.contains("ms") || delay.contains("毫秒")) {
                binding.tvXrItemDelay.setTextColor(context.getColor(app.fjj.stun.core.R.color.status_connected))
            } else {
                binding.tvXrItemDelay.setTextColor(context.getColor(app.fjj.stun.core.R.color.status_connecting))
            }

            binding.xrItemActiveDot.visibility = if (isSelected) View.VISIBLE else View.GONE

            val primaryColor = com.google.android.material.color.MaterialColors.getColor(
                binding.root, androidx.appcompat.R.attr.colorPrimary
            )

            binding.cardXrItem.strokeColor = if (isSelected) primaryColor else Color.TRANSPARENT
            binding.cardXrItem.strokeWidth = if (isSelected) 6 else 0

            binding.root.setOnClickListener {
                onProfileClick(profile)
            }
        }
    }
}
