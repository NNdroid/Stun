package app.fjj.stun.ui

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import app.fjj.stun.R
import app.fjj.stun.core.R as CoreR
import com.google.android.material.bottomsheet.BottomSheetDragHandleView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 订阅面板"整页单滚动"的结构约定（inflate 真实 XML，不是重画的模型）。
 *
 * 背景：面板原先只有中间一段订阅列表能滚，标题/流量卡/底部三个按钮全部固定，
 * 在 90% 屏高里把列表压成一条缝 —— 展开的订阅卡（URL/PIN/名称/间隔/删除保存）
 * 比滚动视口还高，滑不动也编辑不了，一弹键盘更没地方。
 *
 * 这里锁住改造后的形态：拖拽条之外，任何东西都必须待在同一个滚动容器里。
 * 谁要是再把流量卡或操作按钮挪回去固定，测试会立刻红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubscriptionSheetLayoutTest {

    private fun inflate(@LayoutRes layout: Int): View {
        // AppCompatActivity 不能换成裸 Activity：非 AppCompat 的 inflater 没有 view factory，
        // app:tint / app:icon 之类的属性会被静默丢掉。
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        val themed = ContextThemeWrapper(activity, R.style.Theme_Stun)
        val view = LayoutInflater.from(activity).cloneInContext(themed).inflate(layout, null)
        // 真挂到窗口上：TextView 的复合 drawable、矢量 tint 要到 attach 之后才解析。
        activity.setContentView(view)
        return view
    }

    private fun collectRecyclers(group: ViewGroup, out: MutableList<RecyclerView>) {
        for (i in 0 until group.childCount) {
            when (val child = group.getChildAt(i)) {
                is RecyclerView -> out += child
                is ViewGroup -> collectRecyclers(child, out)
            }
        }
    }

    @Test
    fun onlyTheDragHandleIsPinnedOutsideTheScrollContainer() {
        val root = inflate(R.layout.bottom_sheet_subscription) as ViewGroup
        val pinned = (0 until root.childCount)
            .map { root.getChildAt(it) }
            .filterNot { it is RecyclerView }

        assertEquals(
            "滚动容器之外只允许留一个元素（拖拽条）：标题、流量卡、操作按钮都必须在滚动内容里",
            1, pinned.size
        )
        assertTrue(
            "唯一固定的元素应是拖拽条，实际是 ${pinned[0].javaClass.simpleName}",
            pinned[0] is BottomSheetDragHandleView
        )
    }

    @Test
    fun exactlyOneScrollContainerForTheWholeSheet() {
        val root = inflate(R.layout.bottom_sheet_subscription)
        val recyclers = mutableListOf<RecyclerView>()
        collectRecyclers(root as ViewGroup, recyclers)

        assertEquals(
            "整页只能有一套纵向滚动系统（不允许再套 NestedScrollView 之类的双层滚动）",
            1, recyclers.size
        )
    }

    @Test
    fun scrollContainerStretchesAndAcceptsKeyboardPadding() {
        val root = inflate(R.layout.bottom_sheet_subscription)
        val rv: RecyclerView? = root.findViewById(R.id.rv_subscription_list)
        assertNotNull("整页滚动容器必须存在", rv)

        assertFalse(
            "clipToPadding 必须为 false：键盘避让把底部内边距加在列表上，内容要能滚进这块留白",
            rv!!.clipToPadding
        )
        val lp = rv.layoutParams as LinearLayout.LayoutParams
        assertEquals("滚动容器应吃掉拖拽条以外的全部高度", 1f, lp.weight, 0f)
    }

    @Test
    fun heavyContentIsNoLongerAnchoredInsideTheSheetLayout() {
        val root = inflate(R.layout.bottom_sheet_subscription)
        val pinnedByMistake = mapOf(
            "流量卡" to R.id.card_subscription_usage,
            "后台自动同步开关" to R.id.sw_auto_sync,
            "添加订阅按钮" to R.id.btn_add_sub,
            "从剪贴板粘贴按钮" to R.id.btn_paste,
            "立即同步按钮" to R.id.btn_sync,
            "空态提示" to R.id.tv_subscription_empty
        )
        for ((label, id) in pinnedByMistake) {
            assertNull(
                "$label 出现在面板根布局里了 —— 它必须随列表滚动（在 item_subscription_header / item_subscription_actions 内）",
                root.findViewById<View>(id)
            )
        }
    }

    @Test
    fun headerItemCarriesTitleUsageCardAndEmptyHint() {
        val header = inflate(R.layout.item_subscription_header)
        assertNotNull("头部要有后台自动同步开关", header.findViewById<View>(R.id.sw_auto_sync))
        assertNotNull("头部要有流量卡", header.findViewById<View>(R.id.card_subscription_usage))
        assertNotNull("头部要有趋势图（会被回收重建，绑定阶段必须重放数据）", header.findViewById<View>(R.id.iv_usage_trend))
        assertNotNull("空态提示应落在内容流里", header.findViewById<View>(R.id.tv_subscription_empty))
        assertNotNull("头部要有上次同步文案", header.findViewById<View>(R.id.tv_last_sync))
    }

    @Test
    fun actionsItemCarriesTheThreeButtons() {
        val actions = inflate(R.layout.item_subscription_actions)
        assertNotNull("操作区要有添加订阅", actions.findViewById<View>(R.id.btn_add_sub))
        assertNotNull("操作区要有从剪贴板粘贴", actions.findViewById<View>(R.id.btn_paste))
        assertNotNull("操作区要有立即同步", actions.findViewById<View>(R.id.btn_sync))
    }

    // ─────────────────────────────────── 2026-09-17 设计稿版式（图标化 / 单行用量）

    @Test
    fun usageCardExposesTrendToggleAndCopyAction() {
        val header = inflate(R.layout.item_subscription_header)
        val res = header.context.resources

        val trend = header.findViewById<TextView>(R.id.btn_usage_trend)
        assertNotNull("流量卡标题行要有「用量趋势」开关", trend)
        assertNotNull(
            "「用量趋势」要带柱状图图标（drawableStart，attach 之后才解析）",
            trend.compoundDrawablesRelative.getOrNull(0)
        )
        assertEquals(
            "趋势图默认收起：面板高度本来就紧，不该在 XML 里就占掉 64dp",
            View.GONE,
            header.findViewById<View>(R.id.iv_usage_trend).visibility
        )

        val copy = header.findViewById<View>(R.id.btn_usage_copy)
        assertNotNull("流量卡标题行要有复制用量按钮", copy)
        assertTrue("复制用量应是图标按钮", copy is ImageButton)
        assertNotNull("只有图标的按钮必须有 contentDescription", copy.contentDescription)

        assertNotNull(
            "上次同步那行要带日历图标",
            header.findViewById<TextView>(R.id.tv_last_sync).compoundDrawablesRelative.getOrNull(0)
        )

        assertEquals(
            "旧的「单独一行 of X」已并入单行的「已用 X / Y」，不该再出现",
            0,
            res.getIdentifier("tv_usage_total", "id", header.context.packageName)
        )
    }

    @Test
    fun editorIsTitledAndEveryFieldCarriesALeadingIcon() {
        val row = inflate(R.layout.item_subscription_row)
        val editor: ViewGroup? = row.findViewById(R.id.ll_editor)
        assertNotNull("编辑区容器必须存在", editor)

        val title = row.context.getString(CoreR.string.subscription_settings)
        val hasSectionTitle = (0 until editor!!.childCount)
            .map { editor.getChildAt(it) }
            .filterIsInstance<TextView>()
            .any { it.text.toString() == title }
        assertTrue("编辑区顶部要有「$title」分区标题", hasSectionTitle)

        val fields = linkedMapOf(
            "订阅链接" to R.id.til_row_url,
            "PIN" to R.id.til_row_pin,
            "名称" to R.id.til_row_name,
            "更新间隔" to R.id.til_row_interval
        )
        for ((label, id) in fields) {
            val til = row.findViewById<TextInputLayout>(id)
            assertNotNull("$label 输入框必须存在", til)
            assertNotNull("$label 输入框要有前置图标（app:startIconDrawable）", til.startIconDrawable)
        }
        assertEquals(
            "PIN 输入的显隐切换不能丢",
            TextInputLayout.END_ICON_PASSWORD_TOGGLE,
            row.findViewById<TextInputLayout>(R.id.til_row_pin).endIconMode
        )
    }

    // ─────────────────────────── 2026-09-17 标题后的问号 → 订阅说明页

    @Test
    fun titleCarriesAHelpButtonThatSitsRightAfterTheTitleText() {
        val header = inflate(R.layout.item_subscription_header)
        val res = header.context.resources

        val help = header.findViewById<View>(R.id.btn_subscription_help)
        assertNotNull("标题后面要有打开订阅说明的问号", help)
        assertTrue("问号应是图标按钮", help is ImageButton)
        assertNotNull("只有图标的按钮必须有 contentDescription", help.contentDescription)
        assertEquals(
            "问号的无障碍描述要用订阅说明那条串",
            res.getString(CoreR.string.subscription_help),
            help.contentDescription.toString()
        )

        // 问号必须和标题同属一个容器，且该容器（不是标题自己）吃 weight ——
        // 否则问号会被推到行尾，看着像开关的附属按钮。
        val titleRow = header.findViewById<View>(R.id.btn_subscription_help).parent as ViewGroup
        val title = (0 until titleRow.childCount)
            .map { titleRow.getChildAt(it) }
            .filterIsInstance<TextView>()
            .firstOrNull { it.text.toString() == res.getString(CoreR.string.subscription_title) }
        assertNotNull("问号与标题必须同处一个横向容器", title)
        val rowLp = titleRow.layoutParams as LinearLayout.LayoutParams
        assertEquals("承载「标题 + 问号」的容器应该吃满剩余宽度", 1f, rowLp.weight, 0f)
        assertEquals(
            "标题自身应收到 wrap_content，否则问号会被推到容器右端",
            ViewGroup.LayoutParams.WRAP_CONTENT,
            title!!.layoutParams.width
        )
    }

    @Test
    fun helpButtonStaysVisibleEvenWithoutUsageData() {
        // 说明入口跟有没有流量数据无关：bind() 里给按钮挂监听必须在
        // "无用量数据" 的早退分支之前，所以 XML 里它不能是 gone。
        val header = inflate(R.layout.item_subscription_header)
        assertEquals(
            "问号默认必须可见（用量为空时也不能跟着卡片一起消失）",
            View.VISIBLE,
            header.findViewById<View>(R.id.btn_subscription_help).visibility
        )
    }

    @Test
    fun pasteButtonCarriesAPasteIcon() {
        val actions = inflate(R.layout.item_subscription_actions)
        val paste = actions.findViewById<MaterialButton>(R.id.btn_paste)
        assertNotNull("操作区要有从剪贴板粘贴", paste)
        assertNotNull("粘贴按钮要带图标（设计稿）", paste.icon)
    }
}
