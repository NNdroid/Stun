package app.fjj.stun.wear

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import app.fjj.stun.repo.Profile
import app.fjj.stun.ui.ProfileRowAdapter
import app.fjj.stun.wear.databinding.ItemProfileWearBinding
import com.google.android.material.card.MaterialCardView

/**
 * 手表端节点列表。逻辑全在 [ProfileRowAdapter]（core），这里只负责
 * inflate `item_profile_wear.xml` 并把 view 交给基类。
 *
 * 手表是唯一**没有地址与延迟控件**的一端（48dp 小屏只放得下名称 + 协议徽章），
 * 所以 [ProfileRowAdapter] 构造时传 `showAddress = false, showDelay = false`。
 */
class ProfileAdapterWear(
    onProfileClick: (Profile) -> Unit,
) : ProfileRowAdapter(
    onProfileClick = onProfileClick,
    // 小屏细描边才不至于挤掉文字
    selectedStrokeWidthPx = 4,
    showAddress = false,
    showDelay = false,
) {

    override fun createRowViews(parent: ViewGroup): ProfileRowAdapter.RowViews {
        val binding = ItemProfileWearBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return object : ProfileRowAdapter.RowViews {
            override val root: View = binding.root
            override val card: MaterialCardView = binding.cardWearItem
            override val activeDot: View = binding.wearItemActiveDot
            override val name = binding.tvWearItemName
            override val type = binding.tvWearItemType
            // 布局里没有这两项 —— 传 null 而不是隐藏的 View，隐藏 ≠ 不存在
            override val address = null
            override val delay = null
        }
    }
}
