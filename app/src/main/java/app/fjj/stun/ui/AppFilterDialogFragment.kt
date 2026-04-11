package app.fjj.stun.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.fjj.stun.databinding.FragmentAppFilterBinding
import app.fjj.stun.databinding.ItemAppBinding
import kotlin.concurrent.thread

class AppFilterDialogFragment : DialogFragment() {

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

    data class AppInfo(
        val name: String,
        val packageName: String,
        val icon: Drawable?
    )

    companion object {
        fun newInstance(selectedPackages: String): AppFilterDialogFragment {
            val fragment = AppFilterDialogFragment()
            fragment.initialSelectedPackages = selectedPackages
            return fragment
        }
    }

    fun setOnAppFilterSelectedListener(listener: OnAppFilterSelectedListener) {
        this.listener = listener
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAppFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        val adapter = AppAdapter()
        binding.recyclerView.adapter = adapter

        selectedPackages.addAll(initialSelectedPackages.split(",").map { it.trim() }.filter { it.isNotBlank() })

        loadApps(adapter)

        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                filterApps(newText, adapter)
                return true
            }
        })

        binding.btnOk.setOnClickListener {
            listener?.onAppFilterSelected(selectedPackages.joinToString(","))
            dismiss()
        }

        binding.btnCancel.setOnClickListener {
            dismiss()
        }
    }

    private fun loadApps(adapter: AppAdapter) {
        thread {
            val pm = requireContext().packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                val apps = packages
                    .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM == 0) || (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0) || it.packageName == requireContext().packageName }
                    .map {
                    AppInfo(
                        name = it.loadLabel(pm).toString(),
                        packageName = it.packageName,
                        icon = it.loadIcon(pm)
                    )
                }.sortedBy { it.name.lowercase() }

            activity?.runOnUiThread {
                allApps.clear()
                allApps.addAll(apps)
                filteredApps.clear()
                filteredApps.addAll(apps)
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun filterApps(query: String?, adapter: AppAdapter) {
        filteredApps.clear()
        if (query.isNullOrBlank()) {
            filteredApps.addAll(allApps)
        } else {
            val lowerQuery = query.lowercase()
            allApps.filter { it.name.lowercase().contains(lowerQuery) || it.packageName.lowercase().contains(lowerQuery) }
                .forEach { filteredApps.add(it) }
        }
        adapter.notifyDataSetChanged()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    inner class AppAdapter : RecyclerView.Adapter<AppAdapter.ViewHolder>() {
        inner class ViewHolder(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = filteredApps[position]
            holder.binding.tvAppName.text = app.name
            holder.binding.tvPackageName.text = app.packageName
            holder.binding.ivAppIcon.setImageDrawable(app.icon)
            holder.binding.cbSelected.isChecked = selectedPackages.contains(app.packageName)

            holder.itemView.setOnClickListener {
                if (selectedPackages.contains(app.packageName)) {
                    selectedPackages.remove(app.packageName)
                } else {
                    selectedPackages.add(app.packageName)
                }
                notifyItemChanged(position)
            }
        }

        override fun getItemCount(): Int = filteredApps.size
    }
}
