package app.fjj.stun.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.repo.Profile
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import androidx.appcompat.R as AppCompatR

/**
 * 车机 / 头显 / 手表三端节点列表的**唯一实现**。
 *
 * ## 为什么要收口
 * 三个 `ProfileAdapter{Car,XR,Wear}` 原来是逐字复制：数据源、`updateProfiles`、延迟表、
 * 延迟着色、选中描边、点击跳转，六段逻辑在三个文件里各写一遍。收口前实测 60% 非空行完全相同。
 * 危害不是"多写了点代码"，而是**任何修复都会漏掉另外两端** —— 而这已经发生过一次：
 * XR 端多了个 `updateDelays()`（批量回填，避免一轮测速触发 N 次全量重绘），car 和 wear
 * 都只有逐个 `updateDelay()`，于是 car 那边整轮测速会重绘 N 次。
 *
 * ## 为什么不合并布局
 * `item_profile_{car,xr,wear}.xml` 看着像重复，实则是**真实端差异**，不该强行统一：
 * 车机 64dp 高、带地址与延迟两行；手表 48dp、只有名称与协议徽章（没有地址/延迟控件）。
 * 合并要引入一堆 `visibility` 开关 + `?attr/` 条件，比留着三份更难维护。
 * 真正逐字复制的只有 Kotlin 侧 —— 那才是这里收口的对象。
 *
 * ## 扩展方式
 * 端差异只剩两处，都通过构造函数参数表达，不需要子类：
 *  - [showAddress] / [showDelay]：手表传 false（布局里没有对应控件，传 true 会 NPE）；
 *  - [selectedStrokeWidthPx]：手表描边 4px，车机与头显 6px。
 * view 引用通过 [RowViews] 契约注入 —— 各端 ViewBinding 生成的类名不同
 * （`ItemProfileCarBinding` / `ItemProfileXrBinding` / …），基类不能持有具体类型。
 */
abstract class ProfileRowAdapter(
    private val onProfileClick: (Profile) -> Unit,
    /** 选中态描边宽度（px）。手表更细，见 KDoc。 */
    private val selectedStrokeWidthPx: Int,
    /** 布局里是否有地址控件。 */
    private val showAddress: Boolean,
    /** 布局里是否有延迟控件。 */
    private val showDelay: Boolean,
) : RecyclerView.Adapter<ProfileRowAdapter.RowViewHolder>() {

    /**
     * 一行里基类要操作的那些 view。
     *
     * 用接口而不是泛型/基类 holder：各端 ViewBinding 类名不同且由各自的布局生成，
     * 让基类 `RecyclerView.ViewHolder(ItemProfileCarBinding)` 是不可能的。
     */
    interface RowViews {
        val root: View
        val card: MaterialCardView
        val activeDot: View
        val name: android.widget.TextView
        val type: android.widget.TextView
        /** 布局无地址控件时传 null —— 不要传一个隐藏的 TextView，隐藏 ≠ 不存在。 */
        val address: android.widget.TextView?
        /** 同上。 */
        val delay: android.widget.TextView?
    }

    private val profiles = mutableListOf<Profile>()
    private val delayMap = mutableMapOf<String, String>()
    private var selectedProfileId: String? = null

    /** 各端实现：inflate 自己的布局并把 view 交给基类。 */
    protected abstract fun createRowViews(parent: ViewGroup): RowViews

    fun updateProfiles(newProfiles: List<Profile>, selectedId: String?) {
        profiles.clear()
        profiles.addAll(newProfiles)
        selectedProfileId = selectedId
        notifyDataSetChanged()
    }

    /**
     * 批量回填测速结果：整轮测速只刷一次，避免 N 个节点触发 N 次全量重绘。
     *
     * ⚠️ **只提供批量版，不提供单个 `updateDelay`**。曾经只有 XR 有这个批量方法，
     * car / wear 只有逐个版，于是 car 每次 ping 回调都整表 `notifyDataSetChanged()` ——
     * 节点多时在大屏上肉眼可见地卡。逐个版删掉是为了让"逐个刷"这个坑无法被踩第二次。
     *
     * （app 的 `ProfileAdapter` 与 tv 的 `ProfileAdapterTV` 是独立的 `ListAdapter` 实现，
     *  那里的逐项 `notifyItemChanged(index, PAYLOAD_DELAY)` 只刷一行，是正确做法，不在此列。）
     */
    fun updateDelays(results: Map<String, String>) {
        delayMap.putAll(results)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder =
        RowViewHolder(createRowViews(parent))

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        holder.bind(profiles[position])
    }

    override fun getItemCount(): Int = profiles.size

    inner class RowViewHolder(private val views: RowViews) : RecyclerView.ViewHolder(views.root) {

        fun bind(profile: Profile) {
            val isSelected = profile.id == selectedProfileId

            views.name.text = profile.name
            views.type.text = profile.tunnelType.uppercase()

            if (showAddress) {
                views.address?.text =
                    if (profile.proxyAddr.isNotBlank()) profile.proxyAddr else profile.sshAddr
            }

            if (showDelay) {
                val delay = delayMap[profile.id] ?: ""
                views.delay?.apply {
                    text = delay
                    // 成功判定不能只认 "ms"：zh 的 latency_format 是「%1$d 毫秒」。
                    // 颜色走 core 的语义色，DayNight 两套主题都正确。
                    val ok = delay.contains("ms") || delay.contains("毫秒")
                    setTextColor(
                        context.getColor(
                            if (ok) CoreR.color.status_connected else CoreR.color.status_connecting
                        )
                    )
                }
            }

            views.activeDot.visibility = if (isSelected) View.VISIBLE else View.GONE

            val primaryColor = MaterialColors.getColor(views.root, AppCompatR.attr.colorPrimary)
            views.card.strokeColor = if (isSelected) primaryColor else Color.TRANSPARENT
            views.card.strokeWidth = if (isSelected) selectedStrokeWidthPx else 0

            views.root.setOnClickListener { onProfileClick(profile) }
        }
    }
}
