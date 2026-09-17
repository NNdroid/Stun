package app.fjj.stun.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.fjj.stun.R
import app.fjj.stun.core.R as CoreR
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * geosite/geoip tag 多选器：搜索 + 全选/反选/已选过滤 + 复选列表 + 确认回传。
 * 标签来自 myssh 对当前规则文件的扫描（getGeoSiteTagsJSON/getGeoIPTagsJSON，
 * 带文件级缓存）。全部 tag 统一小写展示；show 时传入已选集合，确认经
 * onTagsConfirmed 回传。
 *
 * 状态恢复：kind/selected 写入 arguments（旋转或进程恢复后由 FragmentManager
 * 重建）；listener 通过 parentFragment / hosting activity 解析，不进 Bundle。
 */
class GeoTagsPickerBottomSheet : BottomSheetDialogFragment() {

    interface OnTagsConfirmedListener {
        fun onTagsConfirmed(kind: TagKind, selected: List<String>)
    }

    enum class TagKind { SITE, IP }

    private var selected = mutableSetOf<String>()
    private var allTags: List<String> = emptyList()
    private var loadFailed = false

    private lateinit var kind: TagKind
    private lateinit var adapter: TagAdapter
    private lateinit var rvTags: RecyclerView
    private lateinit var tvStatus: TextView
    private lateinit var tvSelectedCount: TextView
    private lateinit var etSearch: TextInputEditText
    private lateinit var btnFilterSelected: MaterialButton
    private lateinit var btnSelectAll: MaterialButton
    private lateinit var btnInvert: MaterialButton

    private fun resolveListener(): OnTagsConfirmedListener? =
        parentFragment as? OnTagsConfirmedListener ?: activity as? OnTagsConfirmedListener

    companion object {
        private const val ARG_KIND = "kind"
        private const val ARG_SELECTED = "selected"

        fun newInstance(kind: TagKind, currentSelection: List<String>): GeoTagsPickerBottomSheet {
            return GeoTagsPickerBottomSheet().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_KIND, kind)
                    putStringArrayList(ARG_SELECTED, ArrayList(currentSelection))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = requireArguments()
        kind = args.getSerializable(ARG_KIND) as? TagKind ?: TagKind.SITE
        // 来源数据里 geoip 国家码可能是大写（CN）；统一小写保证与列表勾选匹配、
        // 确认输出一致（过滤端同样按小写比较）
        selected = (args.getStringArrayList(ARG_SELECTED) ?: arrayListOf())
            .map { it.lowercase() }.toMutableSet()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.bottom_sheet_geo_tags, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        rvTags = view.findViewById(R.id.rv_tags)
        tvStatus = view.findViewById(R.id.tv_tag_status)
        tvSelectedCount = view.findViewById(R.id.tv_selected_count)
        etSearch = view.findViewById(R.id.et_tag_search)
        btnFilterSelected = view.findViewById(R.id.btn_tag_filter_selected)
        btnSelectAll = view.findViewById(R.id.btn_tag_select_all)
        btnInvert = view.findViewById(R.id.btn_tag_invert)

        adapter = TagAdapter()
        rvTags.layoutManager = LinearLayoutManager(requireContext())
        rvTags.adapter = adapter

        refreshSelectedCount()

        etSearch.setOnTextChanged { adapter.refilter() }

        btnFilterSelected.setOnClickListener {
            // checkable 按钮点击时已自动翻转 isChecked，这里只需重算过滤
            adapter.refilter()
        }

        // 全选/反选作用于当前可见集（受搜索词与"仅已选"过滤影响），符合直觉
        btnSelectAll.setOnClickListener {
            selected.addAll(adapter.visibleSnapshot())
            adapter.refreshChecks()
            refreshSelectedCount()
        }
        btnInvert.setOnClickListener {
            adapter.visibleSnapshot().forEach { if (!selected.remove(it)) selected.add(it) }
            adapter.refreshChecks()
            refreshSelectedCount()
        }

        view.findViewById<MaterialButton>(R.id.btn_tag_clear).setOnClickListener {
            selected.clear()
            if (!etSearch.text.isNullOrBlank()) etSearch.setText("")
            btnFilterSelected.isChecked = false
            adapter.refilter()
            refreshSelectedCount()
        }

        view.findViewById<MaterialButton>(R.id.btn_tag_confirm).setOnClickListener {
            resolveListener()?.onTagsConfirmed(kind, selected.toList().sorted())
            dismiss()
        }

        loadTags()
    }

    private fun setStatus(text: String) {
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = text
        rvTags.visibility = View.GONE
    }

    private fun showList() {
        tvStatus.visibility = View.GONE
        rvTags.visibility = View.VISIBLE
    }

    private fun loadTags() {
        setStatus(getString(CoreR.string.geo_tag_loading))
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            // myssh 首次调用会扫描整个规则文件（几十 ms），缓存后 O(1)。
            // globalConfig（含规则文件路径）由 StunApp 启动时灌入；若进程被杀后
            // 直接深链进弹窗而 App 未完成注入，这里失败会显示在状态区，不静默。
            // 错误必须浮出：静默吞掉会退化成"空列表"，无法排查。
            val result = runCatching {
                val json = if (kind == TagKind.SITE) myssh.Myssh.getGeoSiteTagsJSON() else myssh.Myssh.getGeoIPTagsJSON()
                parseTags(json)
            }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                result.fold(
                    onSuccess = { tags ->
                        loadFailed = false
                        if (tags.isEmpty()) {
                            loadFailed = true
                            setStatus(getString(CoreR.string.geo_tag_empty))
                        } else {
                            showList()
                            allTags = tags
                            adapter.submit(tags, etSearch.text?.toString().orEmpty())
                        }
                    },
                    onFailure = { e ->
                        loadFailed = true
                        setStatus(getString(CoreR.string.geo_tag_load_failed, e.message ?: e.javaClass.simpleName))
                    }
                )
            }
        }
    }

    private fun parseTags(json: String): List<String> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { arr.getString(it).lowercase() }.distinct().sorted()
    }

    private fun refreshSelectedCount() {
        if (!isAdded) return
        tvSelectedCount.text = getString(CoreR.string.geo_tag_selected_count, selected.size)
    }

    private class StringDiffCallback : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String) = oldItem == newItem
        override fun areContentsTheSame(oldItem: String, newItem: String) = true
    }

    private inner class TagAdapter : ListAdapter<String, TagAdapter.Holder>(StringDiffCallback()) {

        fun submit(tags: List<String>, initialQuery: String) {
            etSearch.setText(initialQuery)
            refilter()
        }

        fun refilter() {
            val q = etSearch.text?.toString()?.trim()?.lowercase().orEmpty()
            var source = allTags
            if (btnFilterSelected.isChecked) source = source.filter { it in selected }
            if (q.isNotEmpty()) source = source.filter { it.contains(q) }
            submitList(source)
            // 空结果给出可见反馈；但不要覆盖"加载失败"这种更有价值的错误状态
            if (source.isEmpty()) {
                if (!loadFailed) setStatus(getString(CoreR.string.geo_tag_no_match))
            } else if (tvStatus.visibility == View.VISIBLE) {
                showList()
            }
        }

        fun visibleSnapshot(): List<String> = currentList

        // 全选/反选只改了 selected（外部状态），列表未变 → 定点重绘可见项即可
        fun refreshChecks() = notifyItemRangeChanged(0, itemCount)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_geo_tag, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val switch: MaterialSwitch = view.findViewById(R.id.switch_tag)

            fun bind(tag: String) {
                switch.setOnCheckedChangeListener(null)
                switch.text = tag
                switch.isChecked = selected.contains(tag)
                // MaterialSwitch 自带点击切换；同时支持整行点击
                switch.setOnClickListener {
                    if (switch.isChecked) selected.add(tag) else selected.remove(tag)
                    refreshSelectedCount()
                }
            }
        }
    }
}

private fun TextInputEditText.setOnTextChanged(block: (CharSequence?) -> Unit) {
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) = block(s)
    })
}
