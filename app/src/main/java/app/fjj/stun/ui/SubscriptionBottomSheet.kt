package app.fjj.stun.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import app.fjj.stun.R
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.SubscriptionManager
import app.fjj.stun.repo.SubscriptionManager.SubEntry
import app.fjj.stun.repo.SubscriptionManager.SubSyncStatus
import app.fjj.stun.ui.view.TrafficBarChartView
import app.fjj.stun.util.AppUtils
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** 列表首项（头部区）占掉的位置偏移：行的适配器位置 = 行下标 + 1。 */
private const val SHEET_HEADER_OFFSET = 1

/** 头部与操作区的稳定 id（订阅行的 id 从 1 递增，不会撞上这两个负值）。 */
private const val HEADER_STABLE_ID = -1L
private const val ACTIONS_STABLE_ID = -2L

/**
 * 订阅管理面板：整页单滚动（除顶部拖拽条外全部随列表滚走），
 * 头部（标题/开关/流量卡）+ N 个订阅行 + 底部操作区合成同一个 RecyclerView。

 * 与上一版的关键差异：面板原先只有中间一段订阅列表能滚，标题/流量卡/底部按钮都是固定的，
 * 键盘一弹可视区就被压成一条缝，展开的编辑器比滚动视口还高。现在整页只有一个滚动系统，
 * 编辑中的订阅能占据键盘上方几乎全部空间。
 *
 * 其它行为保持：同步跑在进程级 scope（旋转/退后台不中断、失败不自动关面板）；
 * 结构变更即时落库，不再只在"立即同步"时顺带保存；展开/校验态并入行模型交给 DiffUtil 定点重绘。
 */
class SubscriptionBottomSheet : BottomSheetDialogFragment() {

    private var onSyncedCallback: (() -> Unit)? = null
    private val rows = mutableListOf<SubscriptionRow>()

    /**
     * 未保存的编辑草稿，按行 id 存。
     *
     * 整页单滚动之后，展开的编辑器很容易被滚出视口、被 RecyclerView 回收；回收再绑定时
     * 若直接从行模型回填输入框，用户没来得及点保存的输入会被悄悄擦掉。
     * 草稿只用于还原显示，不参与落库 —— 收起编辑（点头部/展开别的行）或保存成功即清除，
     * 语义仍是"不保存不生效"。
     */
    private val drafts = mutableMapOf<Long, Draft>()

    private lateinit var adapter: SheetAdapter
    private var recyclerView: RecyclerView? = null

    private var autoSyncEnabled = true
    private var usageUi: UsageUi? = null

    /**
     * 用量趋势图默认收起 —— 面板高度本来就紧张，图一展开就是 64dp。
     * 展开态存在这里而不是 ViewHolder 里：Header 行会被 RecyclerView 回收重建，
     * 状态得随列表数据一起回来（见 SheetItem.Header.trendExpanded）。
     */
    private var usageTrendExpanded = false

    fun setOnSyncedListener(callback: () -> Unit) {
        this.onSyncedCallback = callback
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_subscription, container, false)

    override fun onStart() {
        super.onStart()
        val sheetDialog = dialog as? BottomSheetDialog ?: return
        // 不让窗口随输入法 resize：面板是固定 90% 高，resize 会在键盘弹出的瞬间重布局、
        // 抢走 EditText 焦点，表现为"输入法闪一下就关"。改由 onViewCreated 的 insets
        // 监听把底部内边距加到滚动列表上（配合 clipToPadding=false），让内容滚到键盘之上。
        sheetDialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        val bottomSheet = sheetDialog.findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet
        ) ?: return
        val metrics = resources.displayMetrics
        val configuredHeight = resources.configuration.screenHeightDp
            .takeIf { it > 0 }?.let { (it * metrics.density).toInt() } ?: metrics.heightPixels
        val maxHeight = (configuredHeight * 0.90f).toInt()
        bottomSheet.layoutParams = bottomSheet.layoutParams.apply { height = maxHeight }
        BottomSheetBehavior.from(bottomSheet).apply {
            maxWidth = resources.getDimensionPixelSize(R.dimen.modal_max_width)
            this.maxHeight = maxHeight
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val list = view.findViewById<RecyclerView>(R.id.rv_subscription_list)
        recyclerView = list
        adapter = SheetAdapter(
            onAutoSyncChanged = ::onAutoSyncChanged,
            onToggleExpand = ::toggleExpand,
            onSyncOne = ::syncOne,
            onMore = ::showRowMenu,
            onSave = ::saveRow,
            onRemove = ::confirmRemove,
            onDraftChanged = ::onDraftChanged,
            draftProvider = { id -> drafts[id] },
            onAdd = { addRow(expanded = true) },
            onPaste = ::pasteFromClipboard,
            onSyncAll = ::syncAll,
            onToggleTrend = ::toggleUsageTrend,
            onShowHelp = ::openSubscriptionHelp
        )
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        (list.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        autoSyncEnabled = SettingsManager.isSubscriptionAutoSyncEnabled(requireContext())

        // 键盘避让：窗口不 resize（见 onStart），所以把 ime/navigationBar 的底部内边距加在
        // 滚动列表自身上，而不是根布局 —— 配合 clipToPadding=false，内容能滚到键盘之上，
        // 而不是把可视区压成一条缝。
        val listPaddingBottom = list.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val bottom = listPaddingBottom + maxOf(nav, ime)
            if (list.paddingBottom != bottom) {
                list.updatePadding(bottom = bottom)
                // 键盘高度是异步到位的：输入框拿到焦点那一刻请求可见，只能按旧高度滚，
                // 所以 insets 变化后再补一次，否则输入框仍会被键盘压住一截。
                list.post { revealFocusedInput(list) }
            }
            insets
        }

        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                SubscriptionManager.getSubscriptions(requireContext()).map { entry ->
                    val meta = SubscriptionManager.getSyncMetaForUrl(requireContext(), entry.url)
                    SubscriptionRow(
                        id = ROW_IDS.incrementAndGet(),
                        entry = entry,
                        lastSync = meta?.time ?: 0L,
                        nodeCount = meta?.count ?: -1
                    )
                }
            }
            rows.clear()
            drafts.clear()
            rows += saved
            submit()
        }

        SubscriptionManager.syncStateLiveData.observe(viewLifecycleOwner) { applySyncStates(it) }
        SubscriptionManager.seedUsageLiveData(requireContext())
        SubscriptionManager.usageLiveData.observe(viewLifecycleOwner) { map ->
            usageUi = buildUsageUi(map)
            submit()
        }
    }

    override fun onDestroyView() {
        recyclerView?.adapter = null
        recyclerView = null
        super.onDestroyView()
    }

    // ─────────────────────────────────────────────────────────── 行操作

    private fun addRow(expanded: Boolean, entry: SubEntry = SubEntry()) {
        collapseAll()
        val row = SubscriptionRow(id = ROW_IDS.incrementAndGet(), entry = entry, expanded = expanded)
        rows.add(row)
        submit {
            val position = rows.lastIndex + SHEET_HEADER_OFFSET
            recyclerView?.scrollToPosition(position)
            if (expanded) recyclerView?.post {
                (recyclerView?.findViewHolderForAdapterPosition(position) as? SheetAdapter.VH)
                    ?.focusUrl()
            }
        }
    }

    private fun pasteFromClipboard() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = clipboard?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(requireContext())?.toString().orEmpty()
        // 剪贴板集合可能多行：每行一个订阅，而不是只取第一行塞成一个坏 URL。
        val urls = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (urls.isEmpty()) {
            toast(CoreR.string.clipboard_empty)
            return
        }
        urls.forEach { url -> rows.add(SubscriptionRow(ROW_IDS.incrementAndGet(), SubEntry(url = url))) }
        persistRows()
        submit()
    }

    private fun toggleExpand(id: Long) {
        val target = rows.indexOfFirst { it.id == id }
        if (target < 0) return
        val wasExpanded = rows[target].expanded
        collapseAll()
        if (!wasExpanded) rows[target] = rows[target].copy(expanded = true)
        submit {
            if (wasExpanded) return@submit
            // 展开的卡片比视口还高，光靠用户自己滑容易只看到半张 —— 直接把它滚进视口。
            val position = target + SHEET_HEADER_OFFSET
            recyclerView?.post {
                (recyclerView?.findViewHolderForAdapterPosition(position) as? SheetAdapter.VH)
                    ?.itemView?.let { card ->
                        if (card.height > 0) {
                            card.requestRectangleOnScreen(Rect(0, 0, card.width, card.height), true)
                        }
                    }
            }
        }
    }

    private fun collapseAll() {
        for (i in rows.indices) if (rows[i].expanded) {
            drafts.remove(rows[i].id)
            rows[i] = rows[i].copy(expanded = false)
        }
    }

    /**
     * 编辑中的输入回写到草稿。只改内存、不 submit —— submit 会触发重绑，
     * 打断正在进行的输入（光标跳动、拼音组合被打断）。
     */
    private fun onDraftChanged(id: Long, url: String, pin: String, name: String) {
        if (rows.none { it.id == id }) return
        drafts[id] = Draft(url, pin, name)
    }

    /** 保存单行编辑：URL 变更作废旧同步元信息（换源了），落库并收起。 */
    private fun saveRow(id: Long, url: String, pin: String, name: String) {
        val index = rows.indexOfFirst { it.id == id }
        if (index < 0) return
        val old = rows[index]
        val trimmedUrl = url.trim()
        if (trimmedUrl.isNotBlank() && !SubscriptionManager.isValidSubscriptionScheme(trimmedUrl)) {
            rows[index] = old.copy(urlInvalid = true)
            submit()
            toast(CoreR.string.error_invalid_subscription_url)
            return
        }
        val urlChanged = trimmedUrl != old.entry.url.trim()
        val newEntry = old.entry.copy(
            url = trimmedUrl,
            pin = pin.trim(),
            name = name.trim().ifBlank { if (urlChanged) "" else old.entry.name }
        )
        val meta = if (newEntry.url.isNotBlank()) {
            SubscriptionManager.getSyncMetaForUrl(requireContext(), newEntry.url)
        } else null
        rows[index] = old.copy(
            entry = newEntry,
            expanded = false,
            urlInvalid = false,
            lastSync = if (urlChanged) 0L else old.lastSync,
            nodeCount = if (urlChanged) (meta?.count ?: -1) else old.nodeCount,
            status = null,
            errorCode = null
        )
        drafts.remove(id)
        persistRows()
        submit()
    }

    /**
     * 删除前置询问。
     *
     * "要一起删 N 个节点吗"里的 N 来自 Room 全表查询 —— 先在 IO 取数、拿到结果再弹框。
     * 直接在点击回调（主线程）里查库，节点多时点"删除"会先卡一下才出确认框。
     */
    private fun confirmRemove(id: Long) {
        val row = rows.firstOrNull { it.id == id } ?: return
        val url = row.entry.url
        val ctx = requireContext()
        lifecycleScope.launch {
            val nodeCount = if (url.isNotBlank()) {
                SubscriptionManager.countSubscriptionNodes(ctx, url)
            } else 0

            val builder = MaterialAlertDialogBuilder(ctx)
                .setTitle(CoreR.string.subscription_delete_title)
            val alsoDelete = booleanArrayOf(false)
            if (nodeCount > 0) {
                builder.setMultiChoiceItems(
                    arrayOf(getString(CoreR.string.subscription_delete_also_nodes, nodeCount)),
                    alsoDelete
                ) { _, _, checked -> alsoDelete[0] = checked }
            }
            builder.setNegativeButton(CoreR.string.cancel, null)
                .setPositiveButton(CoreR.string.delete) { _, _ -> removeRow(id, url, alsoDelete[0]) }
                .show()
        }
    }

    /**
     * 确认后的删除：列表先按"已删除"更新（乐观，用户点完立刻看到行消失），
     * 落库交给 [SubscriptionManager.removeSubscriptionAsync]（进程级 scope，查库/删库都在 IO）。
     */
    private fun removeRow(id: Long, url: String, alsoDeleteNodes: Boolean) {
        rows.removeAll { it.id == id }
        drafts.remove(id)
        submit()
        SubscriptionManager.removeSubscriptionAsync(requireContext(), url, alsoDeleteNodes)
    }

    private fun showRowMenu(id: Long, anchor: View) {
        val row = rows.firstOrNull { it.id == id } ?: return
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, MENU_EDIT, 0, CoreR.string.subscription_action_edit)
            if (row.entry.url.isNotBlank()) menu.add(0, MENU_COPY, 1, CoreR.string.subscription_action_copy_url)
            menu.add(0, MENU_DELETE, 2, CoreR.string.delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_EDIT -> toggleExpand(id)
                    MENU_COPY -> {
                        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("url", row.entry.url))
                        toast(CoreR.string.copy_success)
                    }
                    MENU_DELETE -> confirmRemove(id)
                }
                true
            }
            show()
        }
    }

    // ─────────────────────────────────────────────────────────── 同步

    private fun syncAll() {
        val entries = rows.map { it.entry.copy(url = it.entry.url.trim()) }.filter { it.url.isNotBlank() }
        if (entries.isEmpty()) {
            toast(CoreR.string.subscription_url_hint)
            return
        }
        // 全量校验，不再只标第一条。
        var anyInvalid = false
        for (i in rows.indices) {
            val invalid = rows[i].entry.url.isNotBlank() &&
                !SubscriptionManager.isValidSubscriptionScheme(rows[i].entry.url.trim())
            if (invalid) anyInvalid = true
            rows[i] = rows[i].copy(urlInvalid = invalid)
        }
        if (anyInvalid) {
            submit()
            toast(CoreR.string.error_invalid_subscription_url)
            return
        }
        persistRows()
        // 同步跑在进程级 scope；进度经 syncStateLiveData 回流。
        SubscriptionManager.startSyncAll(requireContext()) { results ->
            if (results.any { it.success }) onSyncedCallback?.invoke()
        }
    }

    private fun syncOne(id: Long) {
        val row = rows.firstOrNull { it.id == id } ?: return
        val url = row.entry.url.trim()
        if (url.isBlank() || !SubscriptionManager.isValidSubscriptionScheme(url)) {
            toast(CoreR.string.error_invalid_subscription_url)
            return
        }
        SubscriptionManager.startSyncOne(requireContext(), row.entry.copy(url = url)) { item ->
            if (item.success) onSyncedCallback?.invoke()
        }
    }

    /** 实时同步状态映射回各行（syncing/success/failed + 本地化错误），并刷新节点数/时间。 */
    private fun applySyncStates(states: List<SubscriptionManager.SubSyncState>) {
        val byUrl = states.associateBy { it.url }
        for (i in rows.indices) {
            val row = rows[i]
            val state = byUrl[row.entry.url.trim()]
            val result = state?.result
            var next = row.copy(
                status = state?.status,
                errorCode = state?.result?.let { if (it.success) null else localizeError(it.errorCode) }
            )
            if (result?.success == true) {
                next = next.copy(
                    lastSync = System.currentTimeMillis(),
                    nodeCount = result.importedCount + result.updatedCount
                )
            }
            rows[i] = next
        }
        submit()
    }

    private fun localizeError(code: String?): String = when (code) {
        SubscriptionManager.SyncError.INVALID_URL -> getString(CoreR.string.error_invalid_subscription_url)
        SubscriptionManager.SyncError.HTTP -> getString(CoreR.string.subscription_err_http)
        SubscriptionManager.SyncError.NETWORK -> getString(CoreR.string.subscription_err_network)
        SubscriptionManager.SyncError.NO_VALID_NODES -> getString(CoreR.string.subscription_err_no_valid_nodes)
        SubscriptionManager.SyncError.PIN_REQUIRED -> getString(CoreR.string.subscription_pin_required)
        SubscriptionManager.SyncError.PIN_INVALID -> getString(CoreR.string.subscription_pin_invalid)
        else -> getString(CoreR.string.subscription_sync_error, getString(CoreR.string.error_unknown))
    }

    /** 结构变更后即时落库（增/删/保存都调用），不再依赖"立即同步"的副作用。 */
    private fun persistRows() {
        val entries = rows.map { it.entry.copy(url = it.entry.url.trim()) }.filter { it.url.isNotBlank() }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { SubscriptionManager.saveSubscriptions(requireContext(), entries) }
        }
    }

    /** 键盘 inset 变化后，把当前获得焦点的输入框重新滚进可视区。 */
    private fun revealFocusedInput(list: RecyclerView) {
        val focused = list.findFocus() ?: return
        focused.requestRectangleOnScreen(Rect(0, 0, focused.width, focused.height), true)
    }

    private fun onAutoSyncChanged(checked: Boolean) {
        autoSyncEnabled = checked
        SettingsManager.setSubscriptionAutoSyncEnabled(requireContext(), checked)
        // 不 submit：开关状态自己维护就够了，重绑只会白刷一次头部。
    }

    /** 展开/收起用量趋势图。要 submit —— 展开态是 Header 行数据的一部分，得经 DiffUtil 重绑。 */
    private fun toggleUsageTrend() {
        usageTrendExpanded = !usageTrendExpanded
        submit()
    }

    /**
     * 打开订阅文件格式说明。
     *
     * 刻意**不关闭面板**：说明页是叠加在面板之上的独立 Activity，用户看完按返回
     * 直接回到这里继续加订阅，中间不用重新打开面板、也不丢未保存的编辑草稿。
     */
    private fun openSubscriptionHelp() {
        startActivity(SubscriptionHelpActivity.intent(requireContext()))
    }

    /** 组装整页列表：头部 + 每行 + 操作区。空态提示跟着头部一起滚（不再做居中浮层）。 */
    private fun submit(onCommitted: (() -> Unit)? = null) {
        val items = buildList<SheetItem> {
            add(
                SheetItem.Header(
                    autoSync = autoSyncEnabled,
                    usage = usageUi,
                    showEmpty = rows.isEmpty(),
                    trendExpanded = usageTrendExpanded
                )
            )
            rows.forEach { add(SheetItem.Row(it)) }
            add(SheetItem.Actions)
        }
        if (onCommitted == null) adapter.submitList(items)
        else adapter.submitList(items, Runnable { onCommitted() })
    }

    // ─────────────────────────────────────────────────────────── 流量卡片

    /**
     * 把"最近一次同步到流量快照的那条订阅"整理成可绑定的展示数据。
     *
     * 所有取数/本地化集中在这里：头部会随列表回收重建，绑定阶段只能做赋值
     * （趋势图也一样，必须在 bind 里重放，否则滚回来图就没了）。
     */
    private fun buildUsageUi(
        map: Map<String, SubscriptionManager.SubscriptionUsage>
    ): UsageUi? {
        val latest = map.maxByOrNull { it.value.updatedAt } ?: return null
        val usage = latest.value
        val name = rows.firstOrNull { it.entry.url.trim() == latest.key }?.entry?.name.orEmpty()
        val sourceText = name.ifBlank { runCatching { Uri.parse(latest.key).host }.getOrNull().orEmpty() }
        val pct = Math.round(usage.ratio * 100).toInt()
        val detail = SubscriptionManager.getUsageForUrl(requireContext(), latest.key)

        val expireText = if (usage.hasExpire) {
            val expireMs = usage.expire * 1000L
            val daysLeft = ((expireMs - System.currentTimeMillis()) / 86400000L).toInt()
            val dateStr = DateUtils.formatDateTime(
                requireContext(), expireMs,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_NUMERIC_DATE
            )
            if (daysLeft > 0) {
                getString(CoreR.string.subscription_usage_expire, dateStr, daysLeft)
            } else {
                getString(CoreR.string.subscription_usage_expired, dateStr)
            }
        } else {
            getString(CoreR.string.subscription_usage_expire_none)
        }

        val usedText = AppUtils.formatBytes(usage.used)
        val totalText = if (usage.unlimited) {
            getString(CoreR.string.subscription_usage_unlimited)
        } else {
            AppUtils.formatBytes(usage.total)
        }
        val todayText = getString(
            CoreR.string.subscription_usage_today,
            AppUtils.formatBytes(maxOf(0L, detail?.dayDelta ?: 0L))
        )
        val monthText = getString(
            CoreR.string.subscription_usage_month,
            AppUtils.formatBytes(maxOf(0L, detail?.monthDelta ?: 0L))
        )
        val amountText = getString(CoreR.string.subscription_usage_used_of, usedText, totalText)
        val percentText = getString(CoreR.string.subscription_usage_percent, pct)

        return UsageUi(
            source = sourceText,
            amount = amountText,
            percent = pct,
            percentText = percentText,
            today = todayText,
            month = monthText,
            expire = expireText,
            // 用量色走主题状态色：绑定时按 attr 名解析（非传递 R 类下最稳）。
            colorAttr = when {
                pct >= 90 -> "colorError"
                pct >= 70 -> "colorTertiary"
                else -> "colorPrimary"
            },
            samples = detail?.history?.map { it.second }.orEmpty(),
            // 复制用的纯文本快照：去掉图标与进度条，粘到聊天窗/工单里直接能读。
            copyPayload = buildList {
                if (sourceText.isNotBlank()) add(sourceText)
                add("$amountText ($percentText)")
                add(listOf(todayText, monthText).joinToString(" · "))
                add(expireText)
            }.joinToString("\n")
        )
    }

    private fun toast(stringRes: Int) {
        Toast.makeText(requireContext(), getString(stringRes), Toast.LENGTH_SHORT).show()
    }

    // ─────────────────────────────────────────────────────────── 数据模型

    /** 列表项：头部（标题/开关/流量卡/空态）→ 每个订阅一行 → 底部操作区。 */
    private sealed interface SheetItem {
        /** RecyclerView 稳定 id：头部与操作区固定，行用行 id。 */
        val stableId: Long

        data class Header(
            val autoSync: Boolean,
            val usage: UsageUi?,
            val showEmpty: Boolean,
            /** 用量趋势图是否展开（默认收起，见 SubscriptionBottomSheet.usageTrendExpanded）。 */
            val trendExpanded: Boolean
        ) : SheetItem {
            override val stableId: Long get() = HEADER_STABLE_ID
        }

        data class Row(val row: SubscriptionRow) : SheetItem {
            override val stableId: Long get() = row.id
        }

        /** 操作区没有可绑状态，监听在创建 ViewHolder 时挂一次即可。 */
        object Actions : SheetItem {
            override val stableId: Long get() = ACTIONS_STABLE_ID
        }
    }

    private data class SubscriptionRow(
        val id: Long,
        val entry: SubEntry,
        val lastSync: Long = 0L,
        val nodeCount: Int = -1,
        val status: SubSyncStatus? = null,
        val errorCode: String? = null,
        val expanded: Boolean = false,
        val urlInvalid: Boolean = false
    )

    /** 未保存的编辑内容（不参与落库，只用于回收后还原输入框）。 */
    private data class Draft(val url: String, val pin: String, val name: String)

    /** 流量卡的可绑定数据：文本与配色已在构建时本地化，绑定只做赋值。 */
    private data class UsageUi(
        val source: String,
        /** 一行读完的"已用 X / Y"（不限量时回退成"已用 X / 不限量套餐"），不再拆成两行。 */
        val amount: String,
        val percent: Int,
        val percentText: String,
        val today: String,
        val month: String,
        val expire: String,
        val colorAttr: String,
        val samples: List<Long>,
        /** 点"复制用量"时写进剪贴板的纯文本快照。 */
        val copyPayload: String
    )

    private class SheetAdapter(
        private val onAutoSyncChanged: (Boolean) -> Unit,
        private val onToggleExpand: (Long) -> Unit,
        private val onSyncOne: (Long) -> Unit,
        private val onMore: (Long, View) -> Unit,
        private val onSave: (Long, String, String, String) -> Unit,
        private val onRemove: (Long) -> Unit,
        private val onDraftChanged: (Long, String, String, String) -> Unit,
        private val draftProvider: (Long) -> Draft?,
        private val onAdd: () -> Unit,
        private val onPaste: () -> Unit,
        private val onSyncAll: () -> Unit,
        private val onToggleTrend: () -> Unit,
        private val onShowHelp: () -> Unit
    ) : ListAdapter<SheetItem, RecyclerView.ViewHolder>(DIFF) {

        init {
            setHasStableIds(true)
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }

        override fun getItemId(position: Int): Long = getItem(position).stableId

        override fun getItemViewType(position: Int): Int = when (getItem(position)) {
            is SheetItem.Header -> TYPE_HEADER
            is SheetItem.Row -> TYPE_ROW
            is SheetItem.Actions -> TYPE_ACTIONS
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_HEADER -> HeaderVH(inflater.inflate(R.layout.item_subscription_header, parent, false))
                TYPE_ACTIONS -> ActionsVH(inflater.inflate(R.layout.item_subscription_actions, parent, false))
                else -> VH(inflater.inflate(R.layout.item_subscription_row, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = getItem(position)) {
                is SheetItem.Header -> (holder as HeaderVH).bind(item)
                is SheetItem.Row -> (holder as VH).bind(item.row)
                is SheetItem.Actions -> Unit
            }
        }

        /** 头部：标题下的开关 + 流量卡 + 空态提示。全部按数据重放，回收后不留空。 */
        inner class HeaderVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val autoSync = itemView.findViewById<MaterialSwitch>(R.id.sw_auto_sync)
            private val usageCard = itemView.findViewById<View>(R.id.card_subscription_usage)
            private val usageDetail = itemView.findViewById<View>(R.id.ll_usage_detail)
            private val usageEmpty = itemView.findViewById<View>(R.id.tv_usage_empty)
            private val emptyHint = itemView.findViewById<View>(R.id.tv_subscription_empty)
            private val source = itemView.findViewById<TextView>(R.id.tv_usage_source)
            private val amount = itemView.findViewById<TextView>(R.id.tv_usage_amount)
            private val percent = itemView.findViewById<TextView>(R.id.tv_usage_percent)
            private val progress = itemView.findViewById<LinearProgressIndicator>(R.id.progress_usage)
            private val today = itemView.findViewById<TextView>(R.id.tv_usage_today)
            private val month = itemView.findViewById<TextView>(R.id.tv_usage_month)
            private val expire = itemView.findViewById<TextView>(R.id.tv_usage_expire)
            private val chart = itemView.findViewById<TrafficBarChartView>(R.id.iv_usage_trend)
            private val trendButton = itemView.findViewById<TextView>(R.id.btn_usage_trend)
            private val copyButton = itemView.findViewById<ImageButton>(R.id.btn_usage_copy)
            private val helpButton = itemView.findViewById<ImageButton>(R.id.btn_subscription_help)

            fun bind(item: SheetItem.Header) {
                // 先摘监听再赋值：否则绑定期间的 setChecked 会被当成用户操作回灌。
                autoSync.setOnCheckedChangeListener(null)
                autoSync.isChecked = item.autoSync
                autoSync.setOnCheckedChangeListener { _, checked -> onAutoSyncChanged(checked) }

                emptyHint.visibility = if (item.showEmpty) View.VISIBLE else View.GONE

                trendButton.setOnClickListener { onToggleTrend() }
                // 放在用量早退分支之前：说明入口跟有没有流量数据无关，不能跟着卡片一起消失。
                helpButton.setOnClickListener { onShowHelp() }

                val ui = item.usage
                if (ui == null) {
                    usageCard.visibility = View.VISIBLE
                    usageDetail.visibility = View.GONE
                    usageEmpty.visibility = View.VISIBLE
                    // 没数据时两个动作一起收起，别在卡片右上角留一排按了没反应的死按钮。
                    trendButton.visibility = View.GONE
                    copyButton.visibility = View.GONE
                    chart.visibility = View.GONE
                    return
                }
                usageCard.visibility = View.VISIBLE
                usageDetail.visibility = View.VISIBLE
                usageEmpty.visibility = View.GONE

                source.text = ui.source
                source.visibility = if (ui.source.isBlank()) View.GONE else View.VISIBLE
                amount.text = ui.amount
                percent.text = ui.percentText
                progress.progress = ui.percent
                today.text = ui.today
                month.text = ui.month
                expire.text = ui.expire

                val fallback = resolveThemeColor(progress, "colorPrimary", DEFAULT_USAGE_COLOR)
                val color = resolveThemeColor(progress, ui.colorAttr, fallback)
                progress.setIndicatorColor(color)

                // 两个采样点才画得出一条趋势；只有一个点时把入口也收掉，别让用户点了没反应。
                val hasTrend = ui.samples.size >= 2
                trendButton.visibility = if (hasTrend) View.VISIBLE else View.GONE
                copyButton.visibility = View.VISIBLE
                copyButton.setOnClickListener { copyUsage(ui.copyPayload) }

                if (hasTrend && item.trendExpanded) {
                    chart.slots = ui.samples.size
                    chart.submitSamples(ui.samples, color)
                    chart.visibility = View.VISIBLE
                } else {
                    chart.visibility = View.GONE
                }
            }

            /** 复制用量快照：粘到聊天窗/工单里直接能读，比截图省事。 */
            private fun copyUsage(payload: String) {
                val ctx = itemView.context
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("subscription-usage", payload))
                Toast.makeText(ctx, ctx.getString(CoreR.string.copy_success), Toast.LENGTH_SHORT).show()
            }
        }

        /** 操作区：添加 / 粘贴 / 立即同步。 */
        inner class ActionsVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            init {
                itemView.findViewById<MaterialButton>(R.id.btn_add_sub).setOnClickListener { onAdd() }
                itemView.findViewById<MaterialButton>(R.id.btn_paste).setOnClickListener { onPaste() }
                itemView.findViewById<MaterialButton>(R.id.btn_sync).setOnClickListener { onSyncAll() }
            }
        }

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val title = itemView.findViewById<TextView>(R.id.tv_row_title)
            private val header = itemView.findViewById<View>(R.id.ll_header)
            private val syncing = itemView.findViewById<CircularProgressIndicator>(R.id.pi_row_syncing)
            private val infoLine = itemView.findViewById<View>(R.id.row_info_line)
            private val info = itemView.findViewById<TextView>(R.id.tv_row_info)
            private val home = itemView.findViewById<TextView>(R.id.tv_row_home)
            private val error = itemView.findViewById<TextView>(R.id.tv_row_error)
            private val editor = itemView.findViewById<View>(R.id.ll_editor)
            private val urlInput = itemView.findViewById<TextInputEditText>(R.id.et_row_url)
            private val urlLayout = itemView.findViewById<TextInputLayout>(R.id.til_row_url)
            private val pinInput = itemView.findViewById<TextInputEditText>(R.id.et_row_pin)
            private val nameInput = itemView.findViewById<TextInputEditText>(R.id.et_row_name)
            private val interval = itemView.findViewById<MaterialAutoCompleteTextView>(R.id.act_row_interval)

            /** 当前绑定的行 id（草稿按键存放），未绑定时为 -1。 */
            private var rowId = -1L

            /** 绑定期间回填文本不算用户输入，别把回填值当草稿存下来。 */
            private var suppressDraft = false

            init {
                val items = INTERVAL_HOURS.map {
                    itemView.context.getString(CoreR.string.subscription_auto_interval, it)
                }
                interval.setAdapter(ArrayAdapter(interval.context, android.R.layout.simple_dropdown_item_1line, items))

                val draftWatcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        if (suppressDraft || rowId < 0) return
                        onDraftChanged(
                            rowId,
                            urlInput.text?.toString().orEmpty(),
                            pinInput.text?.toString().orEmpty(),
                            nameInput.text?.toString().orEmpty()
                        )
                    }
                }
                urlInput.addTextChangedListener(draftWatcher)
                pinInput.addTextChangedListener(draftWatcher)
                nameInput.addTextChangedListener(draftWatcher)

                // 输入框被键盘挡住时把它滚进可视区。用 requestRectangleOnScreen 而不是
                // scrollToPosition：后者会把整张卡粗暴地对齐到顶端。
                val reveal = View.OnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) itemView.post {
                        v.requestRectangleOnScreen(Rect(0, 0, v.width, v.height), true)
                    }
                }
                urlInput.onFocusChangeListener = reveal
                pinInput.onFocusChangeListener = reveal
                nameInput.onFocusChangeListener = reveal
            }

            fun bind(row: SubscriptionRow) {
                val ctx = itemView.context
                val entry = row.entry
                rowId = row.id

                title.text = entry.name.ifBlank {
                    runCatching { Uri.parse(entry.url).host }.getOrNull().orEmpty()
                }.ifBlank { ctx.getString(CoreR.string.subscription_url_hint) }

                syncing.visibility = if (row.status == SubSyncStatus.SYNCING) View.VISIBLE else View.GONE

                val infoParts = buildList {
                    if (row.nodeCount >= 0) add(ctx.getString(CoreR.string.subscription_nodes_count, row.nodeCount))
                    if (entry.updateIntervalHours > 0) add(ctx.getString(CoreR.string.subscription_auto_interval, entry.updateIntervalHours))
                    if (row.lastSync > 0) add(
                        ctx.getString(
                            CoreR.string.subscription_synced_at,
                            DateUtils.getRelativeTimeSpanString(
                                row.lastSync, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                            )
                        )
                    )
                }
                info.text = infoParts.joinToString(" · ")
                info.visibility = if (infoParts.isEmpty()) View.GONE else View.VISIBLE

                // 订阅主页入口：只出链接图标，不显域名。域名对用户没信息量（订阅主页
                // 多半也不是节点域名），图标已经表达了"这里能点开外链"。
                val hasHome = entry.homePage.isNotBlank()
                home.visibility = if (hasHome) View.VISIBLE else View.GONE
                home.contentDescription = ctx.getString(CoreR.string.subscription_home_page)
                home.setOnClickListener {
                    if (entry.homePage.isNotBlank()) {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.homePage))) }
                    }
                }
                infoLine.visibility = if (infoParts.isEmpty() && !hasHome) View.GONE else View.VISIBLE

                error.visibility = if (row.errorCode == null) View.GONE else View.VISIBLE
                error.text = row.errorCode

                editor.visibility = if (row.expanded) View.VISIBLE else View.GONE
                if (row.expanded) {
                    // 有草稿优先用草稿：行可能刚从回收池回来，用它还原用户没保存的输入。
                    val draft = draftProvider(row.id)
                    val urlText = draft?.url ?: entry.url
                    val pinText = draft?.pin ?: entry.pin
                    val nameText = draft?.name ?: entry.name
                    suppressDraft = true
                    if (urlInput.text?.toString() != urlText) urlInput.setText(urlText)
                    if (pinInput.text?.toString() != pinText) pinInput.setText(pinText)
                    if (nameInput.text?.toString() != nameText) nameInput.setText(nameText)
                    suppressDraft = false
                    val idx = INTERVAL_HOURS.indexOf(entry.updateIntervalHours)
                    interval.setText(if (idx >= 0) interval.adapter.getItem(idx).toString() else "", false)
                }
                urlLayout.error = if (row.urlInvalid) ctx.getString(CoreR.string.error_invalid_subscription_url) else null

                header.setOnClickListener { onToggleExpand(row.id) }
                itemView.findViewById<ImageButton>(R.id.btn_row_sync).setOnClickListener { onSyncOne(row.id) }
                itemView.findViewById<ImageButton>(R.id.btn_row_more).setOnClickListener { onMore(row.id, it) }
                itemView.findViewById<MaterialButton>(R.id.btn_row_save).setOnClickListener {
                    onSave(row.id, urlInput.text?.toString().orEmpty(), pinInput.text?.toString().orEmpty(), nameInput.text?.toString().orEmpty())
                }
                itemView.findViewById<MaterialButton>(R.id.btn_row_remove).setOnClickListener { onRemove(row.id) }
            }

            fun focusUrl() {
                urlInput.requestFocus()
                urlInput.setSelection(urlInput.text?.length ?: 0)
            }
        }

        companion object {
            private const val TYPE_HEADER = 0
            private const val TYPE_ROW = 1
            private const val TYPE_ACTIONS = 2

            /** 用量色解析失败时的兜底（主题里取不到 colorPrimary 才会用到）。 */
            private val DEFAULT_USAGE_COLOR = 0xFF6750A4.toInt()

            val DIFF = object : DiffUtil.ItemCallback<SheetItem>() {
                override fun areItemsTheSame(a: SheetItem, b: SheetItem) = a.stableId == b.stableId
                override fun areContentsTheSame(a: SheetItem, b: SheetItem) = a == b
            }
            val INTERVAL_HOURS = listOf(1, 6, 12, 24, 168)
        }
    }

    companion object {
        private const val MENU_EDIT = 1
        private const val MENU_COPY = 2
        private const val MENU_DELETE = 3
        private val ROW_IDS = AtomicLong(0)
    }
}

/** 按 attr 名解析主题色（非传递 R 类下最稳），取不到返回 [fallback]。 */
private fun resolveThemeColor(anchor: View, attrName: String, fallback: Int): Int {
    val attrId = anchor.resources.getIdentifier(attrName, "attr", anchor.context.packageName)
    return if (attrId == 0) fallback else MaterialColors.getColor(anchor, attrId, fallback)
}
