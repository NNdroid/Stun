package app.fjj.stun.ui

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.fjj.stun.R
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.databinding.FragmentAppFilterBinding
import app.fjj.stun.databinding.ItemAppBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppFilterDialogFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentAppFilterBinding? = null
    private val binding get() = _binding!!
    private var initialSelectedPackages: String = ""

    private val allApps = mutableListOf<AppInfo>()
    private val selectedPackages = mutableSetOf<String>()
    private val frequentPackages = mutableSetOf<String>()
    private var searchJob: Job? = null

    private var filterCategory = Category.ALL
    private var sortMode = SortMode.NAME

    enum class Category { ALL, SELECTED, FREQUENTLY_USED, SYSTEM, THIRD_PARTY }
    enum class SortMode { NAME, INSTALL_TIME }

    data class AppInfo(
        val name: String,
        val packageName: String,
        val isSystem: Boolean = false,
        val installTime: Long = 0L,
        val isFrequentlyUsed: Boolean = false,
        @Volatile var icon: Drawable? = null
    )

    companion object {
        const val REQUEST_KEY = "app_filter_result"
        const val RESULT_PACKAGES = "selected_packages"
        private const val ARG_SELECTED_PACKAGES = "initial_selected_packages"
        private const val STATE_SELECTED_PACKAGES = "current_selected_packages"
        private const val CACHE_VALID_MS = 5 * 60 * 1000L
        @Volatile private var cachedApps: List<AppInfo> = emptyList()
        @Volatile private var cachedAtElapsedMs: Long = 0L
        private val iconCache = LruCache<String, Drawable>(64)

        fun newInstance(selectedPackages: String): AppFilterDialogFragment {
            return AppFilterDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SELECTED_PACKAGES, selectedPackages)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 不调用 setStyle：继承宿主 Activity 的主题（含动态取色），
        // 与 GeoTags 选择器等其余 bottom sheet 保持同一套配色。
        initialSelectedPackages = savedInstanceState
            ?.getStringArrayList(STATE_SELECTED_PACKAGES)
            ?.joinToString(",")
            ?: arguments?.getString(ARG_SELECTED_PACKAGES).orEmpty()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAppFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        val sheetDialog = dialog as? BottomSheetDialog ?: return
        val bottomSheet = sheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val metrics = resources.displayMetrics
        val configuredHeight = resources.configuration.screenHeightDp
            .takeIf { it > 0 }
            ?.let { (it * metrics.density).toInt() }
            ?: metrics.heightPixels
        val maxHeight = (configuredHeight * 0.90f).toInt()
        bottomSheet.layoutParams = bottomSheet.layoutParams.apply { height = maxHeight }
        BottomSheetBehavior.from(bottomSheet).apply {
            maxWidth = resources.getDimensionPixelSize(R.dimen.content_max_width)
            this.maxHeight = maxHeight
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = AppAdapter()
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
            setHasFixedSize(true)
        }

        selectedPackages.clear()
        selectedPackages.addAll(initialSelectedPackages.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() })

        // 顶部只有拖拽把手（BottomSheetDragHandleView），没有返回按钮；确定/取消在底栏
        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnOk.setOnClickListener {
            parentFragmentManager.setFragmentResult(
                REQUEST_KEY,
                Bundle().apply {
                    putString(RESULT_PACKAGES, selectedPackages.sorted().joinToString(","))
                }
            )
            dismiss()
        }

        updateCountDisplay()
        updateChipCounts()
        loadApps(adapter)

        binding.etSearch.doAfterTextChanged { text ->
            searchJob?.cancel()
            val query = text?.toString().orEmpty()
            searchJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(150L)
                applyFilters(query, adapter)
            }
        }

        // 分类筛选：单选、必选 —— 「全部」是兜底选中项， checkedIds 只会有一个元素
        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            filterCategory = when (checkedIds.firstOrNull()) {
                R.id.chip_filter_selected -> Category.SELECTED
                R.id.chip_filter_frequently_used -> Category.FREQUENTLY_USED
                R.id.chip_filter_system -> Category.SYSTEM
                R.id.chip_filter_third_party -> Category.THIRD_PARTY
                else -> Category.ALL
            }
            applyFilters(binding.etSearch.text?.toString() ?: "", adapter)
        }

        // 效果图版式：排序行只留排序；全选/反选/清空收进标题右侧的筛选图标菜单。
        // 只改勾选集合时 DiffUtil 算不出差异 → 定点重绘可见项
        binding.btnHeaderAction.setOnClickListener { anchor ->
            val menu = PopupMenu(requireContext(), anchor)
            menu.menu.add(0, 0, 0, CoreR.string.select_all)
            menu.menu.add(0, 1, 1, CoreR.string.geo_tag_invert)
            menu.menu.add(0, 2, 2, CoreR.string.deselect_all)
            menu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    0 -> allApps.forEach { selectedPackages.add(it.packageName) }
                    1 -> allApps.forEach {
                        if (selectedPackages.contains(it.packageName)) selectedPackages.remove(it.packageName)
                        else selectedPackages.add(it.packageName)
                    }
                    else -> selectedPackages.clear()
                }
                updateCountDisplay()
                updateChipCounts()
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
                true
            }
            menu.show()
        }

        binding.btnSort.setOnClickListener { anchor ->
            val menu = PopupMenu(requireContext(), anchor)
            menu.menu.add(0, 0, 0, CoreR.string.sort_by_name)
            menu.menu.add(0, 1, 1, CoreR.string.sort_by_install_time)
            menu.menu.setGroupCheckable(0, true, true)
            menu.menu.findItem(if (sortMode == SortMode.NAME) 0 else 1)?.isChecked = true
            menu.setOnMenuItemClickListener { item ->
                sortMode = if (item.itemId == 0) SortMode.NAME else SortMode.INSTALL_TIME
                updateSortLabel()
                applyFilters(binding.etSearch.text?.toString() ?: "", adapter)
                true
            }
            menu.show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(STATE_SELECTED_PACKAGES, ArrayList(selectedPackages))
        super.onSaveInstanceState(outState)
    }

    private fun loadApps(adapter: AppAdapter) {
        val cached = cachedApps
        if (cached.isNotEmpty()) {
            allApps.clear()
            allApps.addAll(cached.map { it.copy(icon = iconCache.get(it.packageName)) })
            applyFilters(binding.etSearch.text?.toString().orEmpty(), adapter)
            binding.loadingProgress.visibility = View.GONE
            if (SystemClock.elapsedRealtime() - cachedAtElapsedMs < CACHE_VALID_MS) return
        } else {
            binding.loadingProgress.visibility = View.VISIBLE
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val (apps, frequent) = withContext(Dispatchers.IO) {
                val pm = requireContext().packageManager
                val frequentSet = loadFrequentPackages()
                val apps = pm.getInstalledApplications(0)
                    .map {
                        AppInfo(
                            name = it.loadLabel(pm).toString(),
                            packageName = it.packageName,
                            // 「系统」= 纯系统应用；被用户更新过的系统应用按第三方算（与旧口径一致）
                            isSystem = (it.flags and ApplicationInfo.FLAG_SYSTEM != 0) &&
                                (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0),
                            installTime = try {
                                pm.getPackageInfo(it.packageName, 0).firstInstallTime
                            } catch (_: PackageManager.NameNotFoundException) {
                                0L
                            },
                            isFrequentlyUsed = frequentSet.contains(it.packageName)
                        )
                    }.sortedBy { it.name.lowercase() }
                apps to frequentSet
            }

            frequentPackages.clear()
            frequentPackages.addAll(frequent)
            cachedApps = apps.map { it.copy(icon = null) }
            cachedAtElapsedMs = SystemClock.elapsedRealtime()

            allApps.clear()
            allApps.addAll(apps.map { it.copy(icon = iconCache.get(it.packageName)) })
            applyFilters(binding.etSearch.text?.toString() ?: "", adapter)
            binding.loadingProgress.visibility = View.GONE
            updateChipCounts()
        }
    }

    /** 读取最近 7 天有使用记录的应用包名集合；无「使用情况访问」权限时返回空集。 */
    private fun loadFrequentPackages(): Set<String> {
        val ctx = requireContext()
        if (!hasUsageStatsPermission()) return emptySet()
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptySet()
        val now = System.currentTimeMillis()
        val begin = now - 7L * 24 * 60 * 60 * 1000
        return try {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, now)
                ?.filter { it.totalTimeInForeground > 0 || it.lastTimeUsed > 0 }
                ?.map { it.packageName }
                ?.toSet()
                ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val ctx = requireContext()
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                ctx.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                ctx.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun applyFilters(query: String, adapter: AppAdapter) {
        val lowerQuery = query.lowercase()
        val filtered = allApps.filter {
            val matchesSearch = it.name.lowercase().contains(lowerQuery) ||
                              it.packageName.lowercase().contains(lowerQuery)
            val matchesCategory = when (filterCategory) {
                Category.ALL -> true
                Category.SELECTED -> selectedPackages.contains(it.packageName)
                Category.FREQUENTLY_USED -> it.isFrequentlyUsed
                Category.SYSTEM -> it.isSystem
                Category.THIRD_PARTY -> !it.isSystem
            }
            matchesSearch && matchesCategory
        }
        // 载入时已按名称排过一次；按安装时间排序在过滤后现排（数据量级 ~几百，主线程可接受）
        val sorted = if (sortMode == SortMode.INSTALL_TIME) {
            filtered.sortedByDescending { it.installTime }
        } else {
            filtered
        }

        adapter.submitList(sorted)
        updateChipCounts()
    }

    private fun updateCountDisplay() {
        val text = getString(CoreR.string.selected_count, selectedPackages.size)
        // 效果图：计数出现在两处 —— 标题下方的副标题 + 底栏
        binding.tvSubtitle.text = text
        binding.tvBottomCount.text = text
    }

    /** 分类 chip 文案带上计数（"全部  268"，计数用次要色），勾选集合或列表变化时都要刷 */
    private fun updateChipCounts() {
        val systemCount = allApps.count { it.isSystem }
        binding.chipFilterAll.text = chipLabel(CoreR.string.filter_all, allApps.size)
        binding.chipFilterSelected.text = chipLabel(CoreR.string.filter_selected, selectedPackages.size)
        binding.chipFilterFrequentlyUsed.text =
            chipLabel(CoreR.string.filter_frequently_used, allApps.count { it.isFrequentlyUsed })
        binding.chipFilterSystem.text = chipLabel(CoreR.string.filter_system, systemCount)
        binding.chipFilterThirdParty.text = chipLabel(CoreR.string.filter_third_party, allApps.size - systemCount)
    }

    private fun chipLabel(labelRes: Int, count: Int): CharSequence {
        val label = getString(labelRes)
        val full = "$label  $count"
        val span = SpannableString(full)
        val countColor = MaterialColors.getColor(
            binding.chipFilterAll, com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        span.setSpan(
            ForegroundColorSpan(countColor),
            label.length + 1, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return span
    }

    private fun updateSortLabel() {
        binding.btnSort.text = getString(
            if (sortMode == SortMode.NAME) CoreR.string.sort_by_name else CoreR.string.sort_by_install_time
        )
    }

    override fun onDestroyView() {
        searchJob?.cancel()
        searchJob = null
        super.onDestroyView()
        _binding = null
    }

    private class AppDiffCallback : DiffUtil.ItemCallback<AppInfo>() {
        override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo) = oldItem.packageName == newItem.packageName
        // icon 走 iconCache + 直连 setImageDrawable，不进 diff，避免图标就绪后触发无谓重绑
        override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo) =
            oldItem.name == newItem.name && oldItem.isSystem == newItem.isSystem
    }

    inner class AppAdapter : ListAdapter<AppInfo, AppAdapter.ViewHolder>(AppDiffCallback()) {
        inner class ViewHolder(val itemBinding: ItemAppBinding) : RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val ib = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(ib)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = getItem(position)
            holder.itemBinding.apply {
                tvAppName.text = app.name
                tvPackageName.text = app.packageName
                tvAppBadge.text = if (app.isSystem) {
                    getString(CoreR.string.filter_system)
                } else {
                    getString(CoreR.string.badge_installed)
                }
                val checked = selectedPackages.contains(app.packageName)
                cbSelected.isChecked = checked
                // 选中态的底色/描边是卡片 checked state 的颜色选择器，这里只驱动状态
                cardApp.isChecked = checked

                // Lazy load icon
                if (app.icon == null) {
                    app.icon = iconCache.get(app.packageName)
                }
                if (app.icon == null) {
                    ivAppIcon.setImageDrawable(null)
                    viewLifecycleOwner.lifecycleScope.launch {
                        val icon = withContext(Dispatchers.IO) {
                            try {
                                holder.itemView.context.packageManager.getApplicationIcon(app.packageName)
                            } catch (e: Exception) { null }
                        }
                        if (icon != null && currentList.getOrNull(holder.bindingAdapterPosition)?.packageName == app.packageName) {
                            app.icon = icon
                            iconCache.put(app.packageName, icon)
                            ivAppIcon.setImageDrawable(icon)
                        }
                    }
                } else {
                    ivAppIcon.setImageDrawable(app.icon)
                }

                root.setOnClickListener {
                    if (selectedPackages.contains(app.packageName)) {
                        selectedPackages.remove(app.packageName)
                    } else {
                        selectedPackages.add(app.packageName)
                    }
                    val nowChecked = selectedPackages.contains(app.packageName)
                    cbSelected.isChecked = nowChecked
                    cardApp.isChecked = nowChecked
                    updateCountDisplay()
                    updateChipCounts()
                }
            }
        }

    }
}
