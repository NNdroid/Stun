package app.fjj.stun.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver.PendingResult
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import app.fjj.stun.R
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.repo.Profile
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.repo.VpnState
import app.fjj.stun.service.MyTransparentProxyService
import app.fjj.stun.service.MyVpnService
import app.fjj.stun.ui.MainActivity
import app.fjj.stun.ui.VpnQuickActionActivity
import app.fjj.stun.util.ExitInfoStore
import app.fjj.stun.util.LocaleHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 组件版式的"形状"。决定渲染时哪些视图存在、柱状图给多大、按钮列怎么摆。
 * 版式本身由子类的 layoutRes 决定，shape 只用于渲染分支——两者必须对应。
 * ⚠️ RemoteViews 不能触碰当前布局里不存在的 id（会在 apply 时抛 ActionException），
 * 所以这个分支同时是"该版式的布局里有没有这个控件"的开关，不是纯样式糖。
 */
enum class WidgetShape {
    /** 迷你版（2x2）：无地址行、无柱状图，只有名称/时延与克制尺寸的上下行圆钮。 */
    MINI,

    /** 标准版（4x2）：头部 + 地址行 + 上下行柱状图 + 右侧按钮列。 */
    CARD,

    /** 紧凑版（3x2）：保留地址、双向速率和状态操作按钮。 */
    COMPACT,

    /** 平板/桌面版（5x2）：横向舒展信息与图表，不占用空间显示操作按钮。 */
    WIDE,

    /** 展开详情（5x3）：头部 + 星标 + 双列字段网格 + 实时速率图 + 按钮。 */
    DETAIL,

    /** 快捷条（4x1）：头像、节点/时延、双向柱图和独立连接按钮。 */
    CAPSULE,
}

/**
 * 桌面小组件共用的渲染基类（胶囊 / 迷你 / 标准 / 详情四档）。
 *
 * 子类只声明"我是哪套版式"（[layoutRes] / [shape]），状态映射、速率格式化、
 * 柱状图绘制、点击意图全部集中在这里。这样做的原因很实际：多份 provider 各写一遍
 * 渲染逻辑，任何一处状态分支改动都会漏掉其中几个，长期必然行为漂移。
 *
 * 版式是锁定的——拖拽只做等比拉伸，不在尺寸档位间切换布局。
 * 尺寸集：4x1 快捷条 / 2x2 迷你 / 4x2 标准 / 5x3 详情。
 */
abstract class StunWidgetProviderBase : AppWidgetProvider() {

    /** 本组件固定使用的布局资源。 */
    protected abstract val layoutRes: Int

    /** 渲染分支用的形状。 */
    protected open val shape: WidgetShape get() = WidgetShape.CARD

    /**
     * 速率柱状图位图下限（dp）：宽 to 高。实际宽度会按组件真实宽度放大（见 render），
     * 这里只是"至少这么宽"的下限。高度取偏大值——位图被缩小采样仍然清晰，
     * 被放大才会发虚，所以宁可画大一点。
     */
    protected open val graphSizeDp: Pair<Int, Int> get() = 150 to 60

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        renderMany(context, appWidgetManager, appWidgetIds, goAsync())
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        renderMany(context, appWidgetManager, intArrayOf(appWidgetId), goAsync())
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE_VPN) {
            StunWidgets.toggleVpn(context)
            StunWidgets.refreshAll(context)
        }
    }

    private fun renderMany(
        context: Context,
        manager: AppWidgetManager,
        widgetIds: IntArray,
        pendingResult: PendingResult
    ) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                widgetIds.forEach { render(appContext, manager, it) }
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to update widgets", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
        try {
            // RemoteViews 文本由渲染方解析，语言需按应用内设置显式包一层
            val ctx = LocaleHelper.wrapContext(context)
            val views = RemoteViews(context.packageName, layoutRes)

            val state = StunRepository.vpnState.value ?: VpnState.DISCONNECTED
            val isConnected = state == VpnState.CONNECTED
            val isActive = isConnected || state == VpnState.CONNECTING || state == VpnState.RECONNECTING
            val isPending = state == VpnState.CONNECTING || state == VpnState.RECONNECTING
            val showLiveTraffic = isConnected || state == VpnState.RECONNECTING

            // 版式能力位：CARD / DETAIL 有完整状态和地址；胶囊版也保留速率柱图。
            // 这不只是样式选择——RemoteViews 只能触碰当前布局里真实存在的 id，
            // 所以每个分支同时是"该版式的布局里有没有这个控件"的开关，写错会在 apply 时抛 ActionException。
            val richShape = shape in setOf(WidgetShape.CARD, WidgetShape.COMPACT, WidgetShape.WIDE, WidgetShape.DETAIL)
            val graphShape = richShape || shape == WidgetShape.CAPSULE

            val profile = readSelectedProfile(context) ?: Profile(id = "", name = "", sshAddr = "")

            // 1. 状态点与状态文案（胶囊条/迷你版只有状态点，没有文案）
            val statusText = when (state) {
                VpnState.CONNECTED -> ctx.getString(CoreR.string.status_connected)
                VpnState.CONNECTING -> ctx.getString(CoreR.string.main_connecting)
                VpnState.RECONNECTING -> ctx.getString(CoreR.string.main_reconnecting)
                VpnState.DISCONNECTED -> ctx.getString(CoreR.string.status_disconnected)
                VpnState.ERROR -> ctx.getString(CoreR.string.main_connection_failed)
            }
            val statusColor = when (state) {
                VpnState.CONNECTED -> 0xFF10B981.toInt() // Emerald green
                VpnState.CONNECTING, VpnState.RECONNECTING -> 0xFFF59E0B.toInt() // Amber
                else -> 0xFFEF4444.toInt() // Red: disconnected/error
            }
            views.setTextColor(R.id.widget_tv_status_dot, statusColor)
            // 无障碍：胶囊条与迷你版都没有状态文案，状态点得自己把状态播报出来
            // （颜色 TalkBack 读不出来，只会念 "black circle"）。
            views.setContentDescription(R.id.widget_tv_status_dot, statusText)
            if (richShape) {
                views.setTextViewText(R.id.widget_tv_status, statusText)
                views.setTextColor(R.id.widget_tv_status, statusColor)
            }

            // 2. 节点名
            val nodeName = profile.name.ifBlank { ctx.getString(CoreR.string.widget_tap_select_node) }
            views.setTextViewText(R.id.widget_tv_name, nodeName)

            // 3. 时延（TCP RTT，-1 = 未测得）
            val latency = StunRepository.latencyMs.value ?: -1L
            if (isConnected && latency >= 0) {
                views.setTextViewText(R.id.widget_tv_latency, ctx.getString(CoreR.string.widget_latency_format, latency))
                views.setTextColor(R.id.widget_tv_latency, ctx.getColor(R.color.widget_down_accent))
            } else {
                views.setTextViewText(R.id.widget_tv_latency, ctx.getString(R.string.widget_placeholder_latency))
                views.setTextColor(R.id.widget_tv_latency, ctx.getColor(R.color.widget_placeholder))
            }
            views.setViewVisibility(R.id.widget_tv_latency, View.VISIBLE)

            // 4. 地址与协议徽章
            val hasProfile = profile.id.isNotBlank() && profile.sshAddr.isNotBlank()
            val endpoint = profile.proxyAddr.takeIf {
                profile.tunnelType != Profile.TUNNEL_TYPE_RAW && it.isNotBlank()
            } ?: profile.sshAddr
            val protocolBadge = if (
                profile.tunnelType == Profile.TUNNEL_TYPE_RAW && profile.tunnelTlsEnabled
            ) "TLS" else profile.tunnelType.uppercase(Locale.ROOT)
            when (shape) {
                WidgetShape.MINI, WidgetShape.CAPSULE -> Unit
                WidgetShape.CARD, WidgetShape.COMPACT, WidgetShape.WIDE -> {
                    views.setViewVisibility(R.id.widget_row_addr, if (hasProfile) View.VISIBLE else View.GONE)
                    if (hasProfile) {
                        views.setTextViewText(R.id.widget_tv_addr, endpoint)
                        views.setTextViewText(R.id.widget_tv_tls_badge, protocolBadge)
                        views.setViewVisibility(R.id.widget_tv_tls_badge, View.VISIBLE)
                    }
                }
                WidgetShape.DETAIL -> {
                    views.setTextViewText(R.id.widget_tv_tls_badge, protocolBadge)
                    views.setViewVisibility(R.id.widget_tv_tls_badge, if (hasProfile) View.VISIBLE else View.GONE)
                    views.setTextViewText(R.id.widget_tv_endpoint, endpoint)
                }
            }

            // 4b. 出口信息（探测到的公网出口 IP + 位置 + 国旗）
            //     出口 IP 由 HomeFragment 在连接建立后探测并写进设备态库（ExitInfoStore）。
            //     组件刷新可能发生在没有任何 Activity 存活的进程里，所以这里只能读库、不能读内存。
            //     仅连接态展示 —— 断开后它是上一次会话的残留，继续挂着会误导用户。
            val exitRowCaption = ExitInfoStore.label(context)?.takeIf { showLiveTraffic && it.isNotBlank() }
            when (shape) {
                // 胶囊条只有一行高，布局里没有出口行 —— 不能触碰这个 id
                WidgetShape.CAPSULE -> Unit
                // 迷你版没有独立的行容器，直接控制文本本身
                WidgetShape.MINI -> {
                    views.setTextViewText(R.id.widget_tv_exit, exitRowCaption.orEmpty())
                    views.setViewVisibility(
                        R.id.widget_tv_exit,
                        if (exitRowCaption == null) View.GONE else View.VISIBLE
                    )
                }
                WidgetShape.CARD, WidgetShape.COMPACT, WidgetShape.WIDE, WidgetShape.DETAIL -> {
                    views.setTextViewText(R.id.widget_tv_exit, exitRowCaption.orEmpty())
                    views.setViewVisibility(
                        R.id.widget_row_exit,
                        if (exitRowCaption == null) View.GONE else View.VISIBLE
                    )
                }
            }

            // 5. 速率。迷你版与胶囊版使用短格式，避免系统字体放大后挤压布局。
            val compactSpeed = shape == WidgetShape.MINI || shape == WidgetShape.CAPSULE
            val tx = if (showLiveTraffic) StunRepository.txRate.value ?: 0L else 0L
            val rx = if (showLiveTraffic) StunRepository.rxRate.value ?: 0L else 0L
            views.setTextViewText(
                R.id.widget_tv_speed_up,
                if (showLiveTraffic) formatSpeed(tx, short = compactSpeed)
                else ctx.getString(if (compactSpeed) R.string.widget_placeholder_speed_short else R.string.widget_placeholder_speed)
            )
            views.setTextViewText(
                R.id.widget_tv_speed_down,
                if (showLiveTraffic) formatSpeed(rx, short = compactSpeed)
                else ctx.getString(if (compactSpeed) R.string.widget_placeholder_speed_short else R.string.widget_placeholder_speed)
            )
            views.setTextColor(
                R.id.widget_tv_speed_up,
                ctx.getColor(if (showLiveTraffic) R.color.widget_up_accent else R.color.widget_placeholder)
            )
            views.setTextColor(
                R.id.widget_tv_speed_down,
                ctx.getColor(if (showLiveTraffic) R.color.widget_down_accent else R.color.widget_placeholder)
            )
            if (shape == WidgetShape.MINI) {
                val iconColor = ctx.getColor(if (showLiveTraffic) R.color.widget_up_accent else R.color.widget_placeholder)
                val downIconColor = ctx.getColor(if (showLiveTraffic) R.color.widget_down_accent else R.color.widget_placeholder)
                views.setInt(R.id.widget_iv_up, "setColorFilter", iconColor)
                views.setInt(R.id.widget_iv_down, "setColorFilter", downIconColor)
                views.setInt(
                    R.id.widget_iv_up,
                    "setBackgroundResource",
                    if (showLiveTraffic) R.drawable.bg_widget_circle_up else R.drawable.bg_widget_circle_placeholder
                )
                views.setInt(
                    R.id.widget_iv_down,
                    "setBackgroundResource",
                    if (showLiveTraffic) R.drawable.bg_widget_circle_down else R.drawable.bg_widget_circle_placeholder
                )
            }

            // 6. 速率柱状图（标准、详情和快捷条使用；迷你版用圆形箭头）
            if (graphShape) {
                val density = context.resources.displayMetrics.density
                val (widthFloorDp, heightDp) = graphSizeDp
                // 位图宽度跟着组件真实宽度走：组件被拉大后如果仍用固定宽度出图，
                // fitXY 会把它放大，柱条发虚。这里按单元宽度取一半作为位图宽度（两图并列，
                // 等于 2 倍过采样），再以各版式下限兜底。真实视图更窄时是缩小采样，依旧清晰。
                val cellWidthDp = widgetCellSizeDp(context, manager, widgetId)?.first ?: 0
                val widthDp = maxOf(widthFloorDp.toFloat(), cellWidthDp * 0.5f).coerceAtMost(320f)
                val gw = (widthDp * density).toInt().coerceAtLeast(1)
                val gh = (heightDp * density).toInt().coerceAtLeast(1)
                val history = if (showLiveTraffic) StunRepository.rateHistorySnapshot() else PLACEHOLDER_HISTORY
                val upColor = ctx.getColor(if (showLiveTraffic) R.color.widget_up_accent else R.color.widget_placeholder)
                val downColor = ctx.getColor(if (showLiveTraffic) R.color.widget_down_accent else R.color.widget_placeholder)
                views.setImageViewBitmap(
                    R.id.widget_iv_graph_up,
                    buildSparkline(history.map { it.first }, upColor, gw, gh)
                )
                views.setImageViewBitmap(
                    R.id.widget_iv_graph_down,
                    buildSparkline(history.map { it.second }, downColor, gw, gh)
                )
            }

            // 7. 未连接时保留灰色数据骨架，避免组件内容塌缩或跳动。
            when (shape) {
                WidgetShape.MINI -> Unit
                WidgetShape.CAPSULE -> {
                    views.setViewVisibility(R.id.widget_pill_up, View.VISIBLE)
                    views.setViewVisibility(R.id.widget_pill_down, View.VISIBLE)
                }
                WidgetShape.CARD, WidgetShape.COMPACT, WidgetShape.WIDE -> {
                    views.setViewVisibility(R.id.widget_row_rates, View.VISIBLE)
                }
                WidgetShape.DETAIL -> {
                    views.setViewVisibility(R.id.widget_pill_up, View.VISIBLE)
                    views.setViewVisibility(R.id.widget_pill_down, View.VISIBLE)
                }
            }

            // 8. 展开详情专属字段
            if (shape == WidgetShape.DETAIL) {
                views.setImageViewResource(
                    R.id.widget_iv_favorite,
                    if (profile.favorite) R.drawable.ic_widget_star_on else R.drawable.ic_widget_star_off
                )
                views.setTextViewText(R.id.widget_tv_addr_detail, profile.sshAddr.ifBlank { DASH })
                views.setTextViewText(R.id.widget_tv_user, profile.user.ifBlank { DASH })
                views.setTextViewText(R.id.widget_tv_protocol, protocolBadge)
                views.setTextViewText(R.id.widget_tv_host, profile.customHost.ifBlank { DASH })
                views.setTextViewText(
                    R.id.widget_tv_sni,
                    profile.serverName.takeIf { profile.tunnelTlsEnabled && it.isNotBlank() } ?: DASH
                )
                views.setTextViewText(R.id.widget_tv_magic, profile.udpCustomMagic.ifBlank { DASH })
                views.setTextViewText(R.id.widget_tv_last, formatLastConnected(profile.lastConnectedAt))
                views.setTextViewText(R.id.widget_tv_uptime, formatUptime(ctx, StunRepository.sessionStartedAt()))
            }

            // 9. 开关按钮（四档版式都有；迷你版/胶囊条点整卡打开应用，按钮单独负责连接/断开）
            val btnIcon = if (isActive) R.drawable.ic_stop_rounded else R.drawable.ic_play
            val btnText = when {
                isPending -> statusText
                isActive -> ctx.getString(CoreR.string.disconnect)
                else -> ctx.getString(CoreR.string.connect)
            }
            val btnColor = if (isPending) 0xFF2563EB.toInt() else 0xFFEF4444.toInt()
            val btnBackground = when {
                isPending -> R.drawable.bg_widget_button_pending
                isActive -> R.drawable.bg_widget_button_disconnect
                else -> R.drawable.bg_widget_button_connect
            }
            views.setImageViewResource(R.id.widget_iv_toggle, btnIcon)
            views.setViewVisibility(R.id.widget_iv_toggle, if (isPending) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widget_progress_toggle, if (isPending) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.widget_tv_toggle, btnText)
            views.setTextColor(R.id.widget_tv_toggle, btnColor)
            views.setInt(R.id.widget_btn_toggle, "setBackgroundResource", btnBackground)
            val showToggle = when (shape) {
                WidgetShape.CAPSULE, WidgetShape.COMPACT -> true
                WidgetShape.CARD -> !isConnected
                WidgetShape.MINI, WidgetShape.WIDE, WidgetShape.DETAIL -> false
            }
            views.setViewVisibility(R.id.widget_btn_toggle, if (showToggle) View.VISIBLE else View.GONE)

            val togglePendingIntent = if (isActive) {
                // 广播回到"本"组件，而不是写死某一个 provider——各版式的按钮各自生效
                val toggleIntent = Intent(context, javaClass).apply { action = ACTION_TOGGLE_VPN }
                PendingIntent.getBroadcast(
                    context,
                    REQUEST_TOGGLE,
                    toggleIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                val startIntent = Intent(context, VpnQuickActionActivity::class.java).apply {
                    action = MainActivity.ACTION_SHORTCUT_START_VPN
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                PendingIntent.getActivity(
                    context,
                    REQUEST_START,
                    startIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            views.setOnClickPendingIntent(R.id.widget_btn_toggle, togglePendingIntent)

            // 10. 整卡/速率区点击打开应用
            val mainPendingIntent = PendingIntent.getActivity(
                context,
                REQUEST_OPEN,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, mainPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_pill_up, mainPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_pill_down, mainPendingIntent)

            manager.updateAppWidget(widgetId, views)
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to render widget $widgetId", error)
        }
    }

    /**
     * 组件当前尺寸（dp）。四个 OPTION_* 键是"跨方向"的上下界：竖屏取 MIN_WIDTH + MAX_HEIGHT，
     * 横屏取 MAX_WIDTH + MIN_HEIGHT。部分桌面根本不回填这些值，此时返回 null，渲染端回落到默认尺寸。
     */
    private fun widgetCellSizeDp(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int
    ): Pair<Int, Int>? {
        val options = manager.getAppWidgetOptions(widgetId)
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val width = options.getInt(
            if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
            else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH
        )
        val height = options.getInt(
            if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
            else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
        )
        return if (width > 0 && height > 0) width to height else null
    }

    private fun readSelectedProfile(context: Context): Profile? = try {
        val profiles = ProfileManager.getProfiles(context)
        val selectedId = SettingsManager.getSelectedProfileId(context)
        profiles.firstOrNull { it.id == selectedId } ?: profiles.firstOrNull()
    } catch (error: Throwable) {
        Log.e(TAG, "Unable to read selected profile", error)
        null
    }

    companion object {
        const val ACTION_TOGGLE_VPN = "app.fjj.stun.widget.ACTION_TOGGLE_VPN"

        internal const val DASH = "—"
        internal const val TAG = "StunWidget"

        private const val REQUEST_OPEN = 0
        private const val REQUEST_TOGGLE = 1
        private const val REQUEST_START = 2

        /** 未连接态的灰色骨架只表达布局与趋势形状，不表示真实流量。 */
        private val PLACEHOLDER_HISTORY = listOf(
            1L to 1L, 2L to 2L, 3L to 2L, 4L to 3L, 5L to 4L,
            7L to 5L, 6L to 7L, 9L to 8L, 11L to 10L, 10L to 9L,
            13L to 12L, 15L to 14L, 14L to 13L, 16L to 15L, 18L to 17L
        )

        /** 迷你柱状图：把速率历史画成等宽圆角柱，最新值在右侧。 */
        internal fun buildSparkline(samples: List<Long>, color: Int, width: Int, height: Int): Bitmap {
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val slots = 15
            val visibleSamples = samples.takeLast(slots)
            val slotW = width.toFloat() / slots
            val barW = slotW * 0.6f
            val maxV = (visibleSamples.maxOrNull() ?: 0L).coerceAtLeast(1L)
            val offset = slots - visibleSamples.size
            visibleSamples.forEachIndexed { i, v ->
                val left = (offset + i) * slotW + (slotW - barW) / 2f
                val ratio = v.toFloat() / maxV
                val h = (ratio * (height - 2f)).coerceAtLeast(2f)
                val freshness = (i + 1f) / visibleSamples.size.coerceAtLeast(1)
                val alpha = (80 + 175 * freshness).toInt().coerceIn(0, 255)
                paint.color = (color and 0x00FFFFFF) or (alpha shl 24)
                canvas.drawRoundRect(left, height - h, left + barW, height.toFloat(), 2f, 2f, paint)
            }
            return bmp
        }

        internal fun formatSpeed(bytesPerSec: Long, short: Boolean = false): String {
            if (bytesPerSec <= 0) return if (short) "0" else "0 B/s"
            if (bytesPerSec < 1024) return "$bytesPerSec B/s"
            val kb = bytesPerSec / 1024.0
            if (kb < 1024) return String.format(Locale.US, "%.1f KB/s", kb)
            val mb = kb / 1024.0
            if (short) return String.format(Locale.US, "%.1fM", mb)
            return String.format(Locale.US, "%.1f MB/s", mb)
        }

        internal fun formatLastConnected(timestamp: Long): String {
            if (timestamp <= 0L) return DASH
            return android.text.format.DateUtils.getRelativeTimeSpanString(
                timestamp,
                System.currentTimeMillis(),
                android.text.format.DateUtils.MINUTE_IN_MILLIS
            ).toString()
        }

        /** 本次会话已持续时长；无活动会话时返回占位符。 */
        internal fun formatUptime(ctx: Context, sessionStartedAt: Long): String {
            if (sessionStartedAt <= 0L) return DASH
            val elapsed = System.currentTimeMillis() - sessionStartedAt
            if (elapsed <= 0L) return DASH
            val totalMinutes = elapsed / 60_000L
            val days = totalMinutes / (60L * 24L)
            val hours = (totalMinutes / 60L) % 24L
            val minutes = totalMinutes % 60L
            return if (days > 0L) {
                ctx.getString(R.string.widget_uptime_days, days, hours, minutes)
            } else {
                ctx.getString(R.string.widget_uptime_hours, hours, minutes)
            }
        }
    }
}

/**
 * 组件注册表：六个版式的 provider 在此登记，供全局刷新与状态切换使用。
 * 新增版式时只需在这里加一行 + 一份 appwidget-provider 描述 + 一个 manifest receiver。
 */
object StunWidgets {

    /** 已注册的组件 provider，顺序即组件选择器里的推荐顺序。
     *  尺寸集：4x1 快捷条 / 2x2 迷你 / 3x2 紧凑 / 4x2 标准 / 5x2 平板 / 5x3 详情。 */
    private val providers = listOf(
        CapsuleStunWidgetProvider::class.java,
        MiniStunWidgetProvider::class.java,
        CompactStunWidgetProvider::class.java,
        StunAppWidgetProvider::class.java,
        WideStunWidgetProvider::class.java,
        DetailStunWidgetProvider::class.java,
    )

    /**
     * 刷新所有已放置的组件。
     * 走 APPWIDGET_UPDATE 广播而不是直接 updateAppWidget，是为了让渲染逻辑
     * 始终跑在各自 provider 实例里（每个版式的 layoutRes/shape 只有它自己知道）。
     */
    fun refreshAll(context: Context) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val manager = AppWidgetManager.getInstance(appContext) ?: return@launch
                providers.forEach { providerClass ->
                    val ids = manager.getAppWidgetIds(ComponentName(appContext, providerClass))
                    if (ids == null || ids.isEmpty()) return@forEach
                    appContext.sendBroadcast(
                        Intent(appContext, providerClass).apply {
                            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                        }
                    )
                }
            } catch (error: Throwable) {
                Log.e(StunWidgetProviderBase.TAG, "Unable to refresh installed widgets", error)
            }
        }
    }

    /** 已连接则断开，未连接则拉起快速连接页——组件按钮的统一入口。 */
    fun toggleVpn(context: Context) {
        val state = StunRepository.vpnState.value ?: VpnState.DISCONNECTED
        val isConnected = state == VpnState.CONNECTED || state == VpnState.CONNECTING || state == VpnState.RECONNECTING
        if (!isConnected) {
            context.startActivity(Intent(context, VpnQuickActionActivity::class.java).apply {
                action = MainActivity.ACTION_SHORTCUT_START_VPN
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
            return
        }
        val isTProxy = SettingsManager.getServiceMode(context) == SettingsManager.SERVICE_MODE_TPROXY
        val serviceClass = if (isTProxy) MyTransparentProxyService::class.java else MyVpnService::class.java
        val action = if (isTProxy) MyTransparentProxyService.ACTION_STOP else MyVpnService.ACTION_STOP
        context.startForegroundService(Intent(context, serviceClass).apply { this.action = action })
    }
}

/** 快捷条（4x1）：名称、时延、双向柱图和独立连接按钮。 */
class CapsuleStunWidgetProvider : StunWidgetProviderBase() {
    override val layoutRes: Int get() = R.layout.widget_stun_capsule
    override val shape: WidgetShape get() = WidgetShape.CAPSULE
    override val graphSizeDp: Pair<Int, Int> get() = 64 to 24
}

/** 迷你版（2x2）：仅名称、时延与上下行速率。 */
class MiniStunWidgetProvider : StunWidgetProviderBase() {
    override val layoutRes: Int get() = R.layout.widget_stun_mini
    override val shape: WidgetShape get() = WidgetShape.MINI
}

/** 紧凑版（3x2）：标准信息密度 + 状态操作按钮。 */
class CompactStunWidgetProvider : StunWidgetProviderBase() {
    override val layoutRes: Int get() = R.layout.widget_stun_standard
    override val shape: WidgetShape get() = WidgetShape.COMPACT
    override val graphSizeDp: Pair<Int, Int> get() = 100 to 52
}

/**
 * 标准版（4x2，默认）。
 * 沿用原类名，已经放到桌面上的旧组件不会因为改版而失效。
 * resize 区间已放宽到 180~680dp，接管原 compact(3x2) 与 wide(5x2) 的宽度档。
 */
class StunAppWidgetProvider : StunWidgetProviderBase() {
    override val layoutRes: Int get() = R.layout.widget_stun_standard
}

/** 平板/桌面版（5x2）：横向展开信息和实时柱图。 */
class WideStunWidgetProvider : StunWidgetProviderBase() {
    override val layoutRes: Int get() = R.layout.widget_stun_standard
    override val shape: WidgetShape get() = WidgetShape.WIDE
    override val graphSizeDp: Pair<Int, Int> get() = 210 to 64
}

/** 展开详情（5x3）：完整字段 + 实时速率图。 */
class DetailStunWidgetProvider : StunWidgetProviderBase() {
    override val layoutRes: Int get() = R.layout.widget_stun_detail
    override val shape: WidgetShape get() = WidgetShape.DETAIL
    override val graphSizeDp: Pair<Int, Int> get() = 130 to 64
}
