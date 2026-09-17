package app.fjj.stun.ui

import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    private var searchJob: Job? = null
    
    private var filterOnlySelected = false
    data class AppInfo(
        val name: String,
        val packageName: String,
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

        updateCountDisplay()
        loadApps(adapter)

        binding.etSearch.doAfterTextChanged { text ->
            searchJob?.cancel()
            val query = text?.toString().orEmpty()
            searchJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(150L)
                applyFilters(query, adapter)
            }
        }

        binding.chipSelectAll.setOnClickListener {
            if (selectedPackages.size == allApps.size) {
                selectedPackages.clear()
            } else {
                allApps.forEach { selectedPackages.add(it.packageName) }
            }
            updateCountDisplay()
            // 列表本身没变（只改了勾选集合），DiffUtil 算不出差异 → 定点重绘可见项即可
            adapter.notifyItemRangeChanged(0, adapter.itemCount)
        }

        binding.chipFilterSelected.setOnCheckedChangeListener { _, isChecked ->
            filterOnlySelected = isChecked
            applyFilters(binding.etSearch.text?.toString() ?: "", adapter)
        }

        binding.btnDone.setOnClickListener {
            parentFragmentManager.setFragmentResult(
                REQUEST_KEY,
                Bundle().apply {
                    putString(RESULT_PACKAGES, selectedPackages.sorted().joinToString(","))
                }
            )
            dismiss()
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
            val apps = withContext(Dispatchers.IO) {
                val pm = requireContext().packageManager
                pm.getInstalledApplications(0)
                    .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM == 0) || 
                             (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0) || 
                             it.packageName == requireContext().packageName }
                    .map {
                        AppInfo(
                            name = it.loadLabel(pm).toString(),
                            packageName = it.packageName
                        )
                    }.sortedBy { it.name.lowercase() }
            }

            cachedApps = apps.map { it.copy(icon = null) }
            cachedAtElapsedMs = SystemClock.elapsedRealtime()

            allApps.clear()
            allApps.addAll(apps.map { it.copy(icon = iconCache.get(it.packageName)) })
            applyFilters(binding.etSearch.text?.toString() ?: "", adapter)
            binding.loadingProgress.visibility = View.GONE
            
            // Re-sync Chip state if all apps are selected
            updateChipLabels()
        }
    }

    private fun applyFilters(query: String, adapter: AppAdapter) {
        val lowerQuery = query.lowercase()
        val newList = allApps.filter {
            val matchesSearch = it.name.lowercase().contains(lowerQuery) || 
                              it.packageName.lowercase().contains(lowerQuery)
            val matchesSelectionFilter = if (filterOnlySelected) selectedPackages.contains(it.packageName) else true
            matchesSearch && matchesSelectionFilter
        }
        
        adapter.submitList(newList)
        updateChipLabels()
    }

    private fun updateCountDisplay() {
        binding.tvCount.text = getString(CoreR.string.selected_count, selectedPackages.size)
    }
    
    private fun updateChipLabels() {
        binding.chipSelectAll.text = if (selectedPackages.size == allApps.size && allApps.isNotEmpty()) {
            getString(CoreR.string.deselect_all)
        } else {
            getString(CoreR.string.select_all)
        }
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
        override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo) = oldItem.name == newItem.name
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
                cbSelected.isChecked = selectedPackages.contains(app.packageName)
                
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
                    cbSelected.isChecked = !cbSelected.isChecked
                    updateCountDisplay()
                    updateChipLabels()
                }
            }
        }

    }
}
