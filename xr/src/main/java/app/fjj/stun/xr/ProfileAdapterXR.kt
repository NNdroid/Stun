package app.fjj.stun.xr

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import app.fjj.stun.repo.Profile
import app.fjj.stun.ui.ProfileRowAdapter
import app.fjj.stun.xr.databinding.ItemProfileXrBinding
import com.google.android.material.card.MaterialCardView

/**
 * 头显端节点列表。逻辑全在 [ProfileRowAdapter]（core），这里只负责
 * inflate `item_profile_xr.xml` 并把 view 交给基类。
 *
 * ⚠️ 批量回填 `updateDelays()` 原本**只有这一端有**（整轮测速只刷一次），
 * car / wear 只有逐个 `updateDelay()`，节点多时 car 每次 ping 回调都全量重绘。
 * 现在三端共用基类里的同一个实现。
 */
class ProfileAdapterXR(
    onProfileClick: (Profile) -> Unit,
) : ProfileRowAdapter(
    onProfileClick = onProfileClick,
    selectedStrokeWidthPx = 6,
    showAddress = true,
    showDelay = true,
) {

    override fun createRowViews(parent: ViewGroup): ProfileRowAdapter.RowViews {
        val binding = ItemProfileXrBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return object : ProfileRowAdapter.RowViews {
            override val root: View = binding.root
            override val card: MaterialCardView = binding.cardXrItem
            override val activeDot: View = binding.xrItemActiveDot
            override val name = binding.tvXrItemName
            override val type = binding.tvXrItemType
            override val address = binding.tvXrItemAddr
            override val delay = binding.tvXrItemDelay
        }
    }
}
