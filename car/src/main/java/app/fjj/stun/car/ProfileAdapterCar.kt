package app.fjj.stun.car

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import app.fjj.stun.car.databinding.ItemProfileCarBinding
import app.fjj.stun.repo.Profile
import app.fjj.stun.ui.ProfileRowAdapter
import com.google.android.material.card.MaterialCardView

/**
 * 车机端节点列表。逻辑全在 [ProfileRowAdapter]（core），这里只负责
 * inflate `item_profile_car.xml` 并把 view 交给基类。
 *
 * 三个端（car / xr / wear）的适配器曾经各写一遍完整逻辑，60% 非空行逐字相同。
 * 详见 [ProfileRowAdapter] 的 KDoc。
 */
class ProfileAdapterCar(
    onProfileClick: (Profile) -> Unit,
) : ProfileRowAdapter(
    onProfileClick = onProfileClick,
    // 车机是大屏远观，描边比手表粗才看得见选中态
    selectedStrokeWidthPx = 6,
    showAddress = true,
    showDelay = true,
) {

    override fun createRowViews(parent: ViewGroup): ProfileRowAdapter.RowViews {
        val binding = ItemProfileCarBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return object : ProfileRowAdapter.RowViews {
            override val root: View = binding.root
            override val card: MaterialCardView = binding.cardCarItem
            override val activeDot: View = binding.carItemActiveDot
            override val name = binding.tvCarItemName
            override val type = binding.tvCarItemType
            override val address = binding.tvCarItemAddr
            override val delay = binding.tvCarItemDelay
        }
    }
}
