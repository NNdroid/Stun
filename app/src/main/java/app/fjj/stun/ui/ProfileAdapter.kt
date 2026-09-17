package app.fjj.stun.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.ViewCompat
import app.fjj.stun.R
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.databinding.ItemProfileBinding
import app.fjj.stun.repo.Profile
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.SubscriptionManager
import app.fjj.stun.util.ThemeColors
import androidx.appcompat.widget.PopupMenu
import kotlin.math.pow

class ProfileAdapter(
    private var selectedProfileId: String?,
    private val onProfileClick: (Profile) -> Unit,
    private val onEditClick: (Profile) -> Unit,
    private val onDeleteClick: (Profile) -> Unit,
    private val onShareClick: (Profile) -> Unit,
    private val onPushToTvClick: (Profile) -> Unit,
    private val onFavoriteToggle: (Profile) -> Unit,
    private val onOrderChanged: (List<Profile>) -> Unit
) : ListAdapter<Profile, ProfileAdapter.ProfileViewHolder>(ProfileDiffCallback()) {

    companion object {
        const val PAYLOAD_TRAFFIC = "payload_traffic"
        const val PAYLOAD_DELAY = "payload_delay"

        /** 列表筛选 tab（与 fragment_home 的 chip_group_node_tabs 一一对应）。 */
        const val FILTER_ALL = 0
        const val FILTER_FAVORITES = 1
        const val FILTER_RECENT = 2
    }

    private var allProfiles: List<Profile> = emptyList()
    private val delays = mutableMapOf<String, String>()
    private var currentQuery: String = ""
    private var filterMode: Int = FILTER_ALL

    class ProfileViewHolder(val binding: ItemProfileBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
        val binding = ItemProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ProfileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ProfileViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            val profile = getItem(position)
            for (payload in payloads) {
                when (payload) {
                    PAYLOAD_TRAFFIC -> {
                        bindFavorite(holder.binding, profile)
                        if (profile.totalTx > 0 || profile.totalRx > 0) {
                            holder.binding.tvStats.visibility = View.VISIBLE
                            holder.binding.tvStats.text = holder.binding.root.context.getString(
                                CoreR.string.tv_traffic_total_format,
                                formatBytes(profile.totalTx),
                                formatBytes(profile.totalRx)
                            )
                        } else {
                            holder.binding.tvStats.visibility = View.GONE
                        }
                    }
                    PAYLOAD_DELAY -> {
                        val delay = delays[profile.id] ?: ""
                        holder.binding.tvDelay.text = delay
                        holder.binding.tvDelay.setTextColor(getDelayColor(holder.binding.root.context, delay))
                    }
                }
            }
        }
    }

    override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
        val profile = getItem(position)
        holder.binding.apply {
            val context = root.context
            tvName.text = profile.name
            
            // Avatar letter
            tvAvatarLetter.text = (profile.name.takeIf { it.isNotBlank() }?.firstOrNull()?.toString() ?: "S").uppercase()
            
            // Build the specific proxy chain display
            val chain = when (profile.tunnelType) {
                Profile.TUNNEL_TYPE_RAW -> if (profile.tunnelTlsEnabled) "${profile.proxyAddr} ➔ ${profile.sshAddr}" else profile.sshAddr
                Profile.TUNNEL_TYPE_DNS -> {
                    val servers = profile.dnsTunnelServers.ifBlank { profile.proxyAddr }
                    "DNS ($servers) ➔ ${profile.sshAddr}"
                }
                else -> "${profile.proxyAddr} ➔ ${profile.sshAddr}"
            }
            tvAddr.text = chain
            
            // User info display
            tvUserInfo.text = context.getString(CoreR.string.label_user, profile.user)
            tvUserInfo.visibility = if (profile.user.isNotBlank()) View.VISIBLE else View.GONE
            
            // Protocol Type
            tvType.text = when (profile.tunnelType) {
                Profile.TUNNEL_TYPE_DNS ->
                    "DNS (${profile.dnsTunnelType.uppercase()})"
                // myssh 注册名是 kcptun，对用户仍显示通用的 KCP
                Profile.TUNNEL_TYPE_KCP -> "KCP"
                else -> profile.tunnelType.uppercase()
            }
            
            // SNI & Host Display Logic (myssh 4735512 类型语义)
            val isTlsFixed = profile.tunnelType in listOf(
                Profile.TUNNEL_TYPE_QUIC, Profile.TUNNEL_TYPE_H3, Profile.TUNNEL_TYPE_MASQUE,
                Profile.TUNNEL_TYPE_WEBTRANSPORT
            )
            val isTlsCapable = profile.tunnelType in listOf(
                Profile.TUNNEL_TYPE_RAW, Profile.TUNNEL_TYPE_WEBSOCKET, Profile.TUNNEL_TYPE_H2,
                Profile.TUNNEL_TYPE_GRPC, Profile.TUNNEL_TYPE_XHTTP
            )
            val isTlsActive = isTlsFixed || (isTlsCapable && profile.tunnelTlsEnabled)

            // 私钥徽标显示
            val isSSHAuthPrivateKey = profile.authType == Profile.AUTH_TYPE_PRIVATEKEY
            ivSshAuthKey.visibility = if (isSSHAuthPrivateKey) View.VISIBLE else View.GONE

            // 密码徽标显示
            val isSSHAuthPassword = profile.authType == Profile.AUTH_TYPE_PASSWORD
            ivSshAuthPassword.visibility = if (isSSHAuthPassword) View.VISIBLE else View.GONE

            // ICMP_CUSTOM 不发 custom_host/server_name（见 VpnConfigBuilder），该类协议不支持自定义 Host
            val isCustomHostSupported = when (profile.tunnelType) {
                Profile.TUNNEL_TYPE_RAW, Profile.TUNNEL_TYPE_QUIC, Profile.TUNNEL_TYPE_DNS,
                Profile.TUNNEL_TYPE_KCP, Profile.TUNNEL_TYPE_UDP_CUSTOM,
                Profile.TUNNEL_TYPE_ICMP_CUSTOM -> false
                Profile.TUNNEL_TYPE_WEBSOCKET, Profile.TUNNEL_TYPE_H2 -> !profile.tunnelTlsEnabled
                else -> true
            }

            if (isTlsActive && profile.serverName.isNotBlank()) {
                tvSni.text = context.getString(CoreR.string.label_sni, profile.serverName)
                tvSni.visibility = View.VISIBLE
            } else {
                tvSni.visibility = View.GONE
            }

            if (profile.tunnelType == Profile.TUNNEL_TYPE_DNS) {
                val domain = profile.dnsTunnelDomain.ifBlank { profile.customHost }
                if (domain.isNotBlank()) {
                    tvHost.text = context.getString(CoreR.string.label_domain, domain)
                    tvHost.visibility = View.VISIBLE
                } else {
                    tvHost.visibility = View.GONE
                }
            } else if (profile.tunnelType == Profile.TUNNEL_TYPE_UDP_CUSTOM) {
                tvHost.text = context.getString(CoreR.string.label_magic, profile.udpCustomMagic.ifBlank { "UDPC" })
                tvHost.visibility = View.VISIBLE
            } else if (isCustomHostSupported && profile.customHost.isNotBlank()) {
                tvHost.text = context.getString(CoreR.string.label_host, profile.customHost)
                tvHost.visibility = View.VISIBLE
            } else {
                tvHost.visibility = View.GONE
            }

            // 来源订阅徽标：sourceSubscriptionUrl 非空（订阅导入的节点）时显示**订阅名**。
            // 名字走 SubscriptionManager 的内存快照（订阅表在 saveSubscriptions 里就地重建
            // 快照），所以这里是查表、不是读盘，不会拖慢滚动热路径。
            // 名字为空就整个收起 —— 不回落到 URL 域名：那正是用户明确要求不要出现的东西，
            // 而且未命名说明订阅头里本来就没给名字，露出个域名只会更像"没做完"。
            val subUrl = profile.sourceSubscriptionUrl.trim()
            val subLabel = if (subUrl.isBlank()) "" else SubscriptionManager.subscriptionNameFor(context, subUrl)
            tvSubBadge.visibility = if (subLabel.isNotBlank()) View.VISIBLE else View.GONE
            tvSubBadge.text = subLabel
            tvSubBadge.contentDescription =
                context.getString(CoreR.string.subscription_title) + ": " + subLabel
            
            // Noise encryption indicator: only DNS-tunnel / UDP-Custom use a Noise
            // public key (protocol-specific field, falling back to the legacy shared one).
            val hasNoiseKey = when (profile.tunnelType) {
                Profile.TUNNEL_TYPE_DNS -> (profile.dnsTunnelPublicKey.ifBlank { profile.noisePublicKey }).isNotBlank()
                Profile.TUNNEL_TYPE_UDP_CUSTOM -> (profile.udpCustomPublicKey.ifBlank { profile.noisePublicKey }).isNotBlank()
                Profile.TUNNEL_TYPE_ICMP_CUSTOM -> (profile.icmpCustomPublicKey.ifBlank { profile.noisePublicKey }).isNotBlank()
                else -> false
            }
            ivDnsBadge.visibility = if (hasNoiseKey) View.VISIBLE else View.GONE

            // SSH fingerprint Check
            val hasSSHFingerprintCheck = (profile.verifyFingerprint)
            ivSshFingerprintAuth.visibility = if (hasSSHFingerprintCheck) View.VISIBLE else View.GONE

            // TLS fingerprint Check
            val hasTLSFingerprintCheck = (profile.verifyCertFingerprint)
            ivTlsFingerprintAuth.visibility = if (hasTLSFingerprintCheck) View.VISIBLE else View.GONE

            // WebDAV 云备份状态点（全库级状态，各卡片一致）：
            // 未开启自动备份=灰；间隔内同步过=绿；超期未同步=红
            run {
                val autoOn = SettingsManager.isWebDavAutoBackupEnabled(context)
                val intervalMs = SettingsManager.getWebDavBackupIntervalHours(context)
                    .coerceIn(1, 720) * 3_600_000L
                val last = SettingsManager.getWebDavLastBackupTime(context)
                val dotColor = when {
                    !autoOn -> 0xFF9E9E9E
                    last > 0 && System.currentTimeMillis() - last <= intervalMs -> 0xFF4CAF50
                    else -> 0xFFE53935
                }.toInt()
                dotBackupStatus.visibility = View.VISIBLE
                dotBackupStatus.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(dotColor)
            }

            // TLS启用徽标
            ivKeyAuth.visibility = if (isTlsActive) View.VISIBLE else View.GONE

            // Last successful connect (relative time, e.g. "3 天前"); hidden when never connected
            if (profile.lastConnectedAt > 0) {
                tvLastConnected.visibility = View.VISIBLE
                tvLastConnected.text = context.getString(
                    CoreR.string.last_connected_fmt,
                    android.text.format.DateUtils.getRelativeTimeSpanString(profile.lastConnectedAt).toString()
                )
            } else {
                tvLastConnected.visibility = View.GONE
            }

            val isSelected = profile.id == selectedProfileId
            root.isActivated = isSelected
            root.isSelected = isSelected
            bindFavorite(this, profile)
            ViewCompat.setStateDescription(
                root,
                if (isSelected) context.getString(CoreR.string.tv_profile_selected_badge) else null
            )
            root.contentDescription = listOfNotNull(
                profile.name.takeIf { it.isNotBlank() },
                chain.takeIf { it.isNotBlank() },
                tvType.text?.toString()?.takeIf { it.isNotBlank() }
            ).joinToString(", ")
            
            // Safe color resolution using runtime lookup
            val primaryColor = getThemeColor(context, "colorPrimary", Color.BLUE)
            val surfaceLow = getThemeColor(context, "colorSurfaceContainerLow", Color.LTGRAY)
            val surfaceHigh = getThemeColor(context, "colorSurfaceContainerHigh", Color.GRAY)

            cardView.strokeWidth = if (isSelected) 3 else 0
            cardView.strokeColor = primaryColor
            
            if (isSelected) {
                cardView.setCardBackgroundColor(surfaceHigh)
            } else {
                cardView.setCardBackgroundColor(surfaceLow)
            }
            
            val delay = delays[profile.id] ?: ""
            tvDelay.text = delay
            tvDelay.setTextColor(getDelayColor(context, delay))

            if (profile.totalTx > 0 || profile.totalRx > 0) {
                tvStats.visibility = View.VISIBLE
                tvStats.text = context.getString(
                    CoreR.string.tv_traffic_total_format,
                    formatBytes(profile.totalTx),
                    formatBytes(profile.totalRx)
                )
            } else {
                tvStats.visibility = View.GONE
            }

            root.setOnClickListener { onProfileClick(profile) }
            btnMore.setOnClickListener { anchor ->
                PopupMenu(context, anchor).apply {
                    inflate(R.menu.profile_context_menu)
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            R.id.action_share -> onShareClick(profile)
                            R.id.action_push_to_tv -> onPushToTvClick(profile)
                            R.id.action_edit -> onEditClick(profile)
                            R.id.action_delete -> onDeleteClick(profile)
                            else -> return@setOnMenuItemClickListener false
                        }
                        true
                    }
                }.show()
            }
        }
    }

    /** 星标：实心暖色=已收藏，空心中性色=未收藏；点击原地切换（落库由接线方负责）。 */
    private fun bindFavorite(binding: ItemProfileBinding, profile: Profile) {
        val context = binding.root.context
        binding.btnFavorite.setImageResource(
            if (profile.favorite) R.drawable.ic_star else R.drawable.ic_star_border
        )
        binding.btnFavorite.imageTintList = android.content.res.ColorStateList.valueOf(
            androidx.core.content.ContextCompat.getColor(
                context,
                if (profile.favorite) R.color.connection_favorite_active else R.color.connection_favorite_inactive
            )
        )
        binding.btnFavorite.contentDescription = context.getString(
            if (profile.favorite) R.string.connection_favorite_remove else R.string.connection_favorite_add
        )
        binding.btnFavorite.setOnClickListener { onFavoriteToggle(profile) }
    }

    /**
     * 取主题属性颜色。实现在 [ThemeColors]：属性 id 只按名字解析一次，解析出的颜色按
     * Theme 实例缓存 —— 从前这里每调一次都要跨 JNI 按名字翻一遍资源表，而绑定一行
     * 就要取 3~4 个颜色（主色 + 两级容器色 + 延迟色）。
     */
    private fun getThemeColor(context: android.content.Context, attrName: String, default: Int): Int =
        ThemeColors.color(context, attrName, default)

    private fun getDelayColor(context: android.content.Context, delay: String): Int {
        if (delay.isEmpty() || delay == "...") return Color.GRAY
        if (delay.contains("ms")) {
            val ms = delay.replace(" ms", "").toIntOrNull() ?: return Color.GRAY
            return when {
                ms < 200 -> getThemeColor(context, "colorPrimary", Color.GREEN)
                ms < 500 -> getThemeColor(context, "colorTertiary", Color.YELLOW)
                else -> getThemeColor(context, "colorError", Color.RED)
            }
        }
        return getThemeColor(context, "colorError", Color.RED)
    }

    fun getProfiles() = allProfiles

    fun updateProfiles(newProfiles: List<Profile>, newSelectedId: String?) {
        val oldSelectedId = selectedProfileId
        allProfiles = newProfiles
        selectedProfileId = newSelectedId
        applyFilterAndSubmit(oldSelectedId)
    }

    fun filter(query: String) {
        currentQuery = query
        applyFilterAndSubmit(selectedProfileId)
    }

    /** 切换筛选 tab（[FILTER_ALL] / [FILTER_FAVORITES] / [FILTER_RECENT]）。 */
    fun setFilterMode(mode: Int) {
        if (filterMode == mode) return
        filterMode = mode
        applyFilterAndSubmit(selectedProfileId)
    }

    fun onItemMove(fromPosition: Int, toPosition: Int) {
        // 搜索态与非「全部」tab 下顺序都不是用户维护的那份（recent 还按时间重排过），拖动会写错 sortIndex。
        if (currentQuery.isNotEmpty() || filterMode != FILTER_ALL) return

        val list = currentList.toMutableList()
        java.util.Collections.swap(list, fromPosition, toPosition)
        submitList(list)
    }

    fun onDragFinished() {
        if (currentQuery.isEmpty() && filterMode == FILTER_ALL) {
            onOrderChanged(currentList)
        }
    }

    /**
     * 选中态（[selectedProfileId]）是适配器级状态，不在 [Profile] 的相等性里，因此
     * "列表内容不变、只有选中项变了"时 DiffUtil 算不出差异，受影响行的高亮不会重绘。
     * 提交后在 commitCallback 中只定点重绘旧/新选中这两行，取代原来的全表 notifyDataSetChanged；
     * 用 commitCallback 而非就地调用，是因为列表提交是异步的，必须等新列表真正落地再取下标。
     */
    private fun applyFilterAndSubmit(oldSelectedId: String?) {
        val byMode = when (filterMode) {
            FILTER_FAVORITES -> allProfiles.filter { it.favorite }
            // 「最近」= 连过的节点，按最近一次成功连接时间倒序；从未连过的不配进这个 tab。
            FILTER_RECENT -> allProfiles.filter { it.lastConnectedAt > 0 }
                .sortedByDescending { it.lastConnectedAt }
            else -> allProfiles
        }
        val filteredList = if (currentQuery.isEmpty()) {
            byMode
        } else {
            byMode.filter { it.name.contains(currentQuery, ignoreCase = true) }
        }
        submitList(filteredList) {
            if (oldSelectedId != selectedProfileId) {
                currentList.indexOfFirst { it.id == oldSelectedId }
                    .takeIf { it >= 0 }?.let { notifyItemChanged(it) }
                currentList.indexOfFirst { it.id == selectedProfileId }
                    .takeIf { it >= 0 }?.let { notifyItemChanged(it) }
            }
        }
    }

    fun updateDelay(profileId: String, delay: String) {
        delays[profileId] = delay
        val index = currentList.indexOfFirst { it.id == profileId }
        if (index != -1) {
            notifyItemChanged(index, PAYLOAD_DELAY)
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (kotlin.math.log10(bytes.toDouble()) / kotlin.math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        return String.format(java.util.Locale.US, "%.1f %s", bytes / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
    }

    class ProfileDiffCallback : DiffUtil.ItemCallback<Profile>() {
        override fun areItemsTheSame(oldItem: Profile, newItem: Profile): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Profile, newItem: Profile): Boolean {
            return oldItem == newItem
        }

        override fun getChangePayload(oldItem: Profile, newItem: Profile): Any? {
            return if (oldItem.totalTx != newItem.totalTx || oldItem.totalRx != newItem.totalRx) {
                PAYLOAD_TRAFFIC
            } else {
                super.getChangePayload(oldItem, newItem)
            }
        }
    }
}
