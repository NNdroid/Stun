package app.fjj.stun.ui

import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.fjj.stun.R
import app.fjj.stun.databinding.FragmentAppFilterBinding
import app.fjj.stun.databinding.ItemAppBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.*

class AppFilterDialogFragment : BottomSheetDialogFragment() {

    interface OnAppFilterSelectedListener {
        fun onAppFilterSelected(selectedPackages: String)
    }

    private var _binding: FragmentAppFilterBinding? = null
    private val binding get() = _binding!!
    private var listener: OnAppFilterSelectedListener? = null
    private var initialSelectedPackages: String = ""
    
    private val allApps = mutableListOf<AppInfo>()
    private var filteredApps = mutableListOf<AppInfo>()
    private val selectedPackages = mutableSetOf<String>()
    
    private var filterOnlySelected = false
    private val job = SupervisorJob()
    private val uiScope = CoroutineScope(Dispatchers.Main + job)

    data class AppInfo(
        val name: String,
        val packageName: String,
        @Volatile var icon: Drawable? = null
    )

    companion object {
        fun newInstance(selectedPackages: String): AppFilterDialogFragment {
            return AppFilterDialogFragment().apply {
                initialSelectedPackages = selectedPackages
            }
        }
    }

    fun setOnAppFilterSelectedListener(listener: OnAppFilterSelectedListener) {
        this.listener = listener
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Use custom style for transparent container and dynamic colors
        setStyle(STYLE_NORMAL, R.style.Theme_App_BottomSheetDialog)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAppFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = AppAdapter()
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
            setHasFixedSize(true)
        }

        selectedPackages.addAll(initialSelectedPackages.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() })

        updateCountDisplay()
        loadApps(adapter)

        binding.etSearch.doAfterTextChanged { text ->
            applyFilters(text?.toString() ?: "", adapter)
        }

        binding.chipSelectAll.setOnClickListener {
            if (selectedPackages.size == allApps.size) {
                selectedPackages.clear()
            } else {
                allApps.forEach { selectedPackages.add(it.packageName) }
            }
            updateCountDisplay()
            adapter.notifyDataSetChanged()
        }

        binding.chipFilterSelected.setOnCheckedChangeListener { _, isChecked ->
            filterOnlySelected = isChecked
            applyFilters(binding.etSearch.text?.toString() ?: "", adapter)
        }

        binding.btnDone.setOnClickListener {
            listener?.onAppFilterSelected(selectedPackages.joinToString(","))
            dismiss()
        }
    }

    private fun loadApps(adapter: AppAdapter) {
        binding.loadingProgress.visibility = View.VISIBLE
        uiScope.launch {
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

            allApps.clear()
            allApps.addAll(apps)
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
        
        filteredApps.clear()
        filteredApps.addAll(newList)
        adapter.notifyDataSetChanged()
        updateChipLabels()
    }

    private fun updateCountDisplay() {
        binding.tvCount.text = getString(R.string.selected_count, selectedPackages.size)
    }
    
    private fun updateChipLabels() {
        binding.chipSelectAll.text = if (selectedPackages.size == allApps.size && allApps.isNotEmpty()) {
            getString(R.string.deselect_all)
        } else {
            getString(R.string.select_all)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        job.cancel()
        _binding = null
    }

    inner class AppAdapter : RecyclerView.Adapter<AppAdapter.ViewHolder>() {
        inner class ViewHolder(val itemBinding: ItemAppBinding) : RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val ib = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(ib)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = filteredApps[position]
            holder.itemBinding.apply {
                tvAppName.text = app.name
                tvPackageName.text = app.packageName
                cbSelected.isChecked = selectedPackages.contains(app.packageName)
                
                // Lazy load icon
                if (app.icon == null) {
                    ivAppIcon.setImageDrawable(null)
                    uiScope.launch {
                        val icon = withContext(Dispatchers.IO) {
                            try {
                                holder.itemView.context.packageManager.getApplicationIcon(app.packageName)
                            } catch (e: Exception) { null }
                        }
                        if (icon != null && filteredApps.getOrNull(holder.bindingAdapterPosition)?.packageName == app.packageName) {
                            app.icon = icon
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

        override fun getItemCount(): Int = filteredApps.size
    }
}
