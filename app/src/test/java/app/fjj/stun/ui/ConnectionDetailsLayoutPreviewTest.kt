package app.fjj.stun.ui

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import app.fjj.stun.R
import app.fjj.stun.geo.GeoPoint
import app.fjj.stun.geo.GlobeArc
import app.fjj.stun.geo.GlobeMarker
import app.fjj.stun.geo.GlobeTopology
import app.fjj.stun.ui.view.GlobeView
import app.fjj.stun.ui.view.TrafficBarChartView
import app.fjj.stun.util.SshBannerRenderer
import com.google.android.material.color.MaterialColors
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 连接详情面板的**渲染预览**：把生产 XML 真的画成 PNG 落到
 * `app/build/reports/ui-preview/connection-details-{light,dark}.png`。
 *
 * 为什么不直接截真机图：改一行 XML 就能重出一版，深色/窄屏/大字体换一组 qualifiers 即可，
 * 而且进 PR 说明时不用手举手机。
 *
 * 这里刻意**自己把字段填成效果图里的那份数据**（而不是跑 HomeFragment）：
 * 面板上绝大多数 TextView 的文案是运行时填的，`tools:text` 不会渲染 ——
 * 不填的话预览出来是一片空卡片，看不出任何版式问题。
 * 同理，地球也要喂一份拓扑，否则只有球体与海岸线。
 *
 * 出图不当断言用（图形差异太脆）；不过顺手断言一条硬约束：**卡头副标题不能被省略号吃掉**
 * —— 它现在是卡片唯一的标题，被截断等于这一块没有名字。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectionDetailsLayoutPreviewTest {

    @Test
    fun `浅色面板预览`() {
        val sheet = inflateSheet(night = false)
        fillSampleData(sheet)

        // 顺手一条硬约束：身份头里的服务器地址是"连的是哪台机器"的唯一凭据，不能被省略号吃掉。
        assertNotTruncated(sheet, R.id.tv_globe_server)

        writePng(sheet, "connection-details-light")
    }

    @Test
    fun `深色面板预览`() {
        val sheet = inflateSheet(night = true)
        fillSampleData(sheet)
        writePng(sheet, "connection-details-dark")
    }

    // ────────────────────────────────────────────────────────── 断言

    /** 被省略号截断就等于这条信息没显示出来。 */
    private fun assertNotTruncated(sheet: View, id: Int) {
        val view = sheet.findViewById<TextView>(id)
        val layout = view.layout ?: return
        for (line in 0 until layout.lineCount) {
            assertEquals(
                "「${view.text}」第 ${line + 1} 行被省略号截断了",
                0,
                layout.getEllipsisCount(line),
            )
        }
    }

    // ────────────────────────────────────────────────────────── 填充效果图那份数据

    private fun fillSampleData(sheet: View) {
        val ctx = sheet.context

        // ── 顶栏身份区
        sheet.fill(R.id.tv_detail_avatar_letter, "G")
        sheet.fill(R.id.tv_detail_name, "Guangzhou Home")
        sheet.fill(R.id.tv_detail_latency, "667 ms")
        sheet.findViewById<TextView>(R.id.tv_detail_quality).apply {
            visibility = View.VISIBLE
            text = ctx.getString(R.string.connection_quality_format, "★★★★☆")
        }

        // ── 实时速率（两根柱子按效果图的形状给：上行一个尖峰、下行一个更高的尖峰）
        val up = sheet.findViewById<TrafficBarChartView>(R.id.iv_detail_up_chart)
        val down = sheet.findViewById<TrafficBarChartView>(R.id.iv_detail_down_chart)
        listOf(up, down).forEach { chart ->
            chart.slots = SAMPLES
            chart.showAxes = true
        }
        up.submitSamples(
            listOf(0, 0, 12, 40, 0, 6, 30, 90, 0, 16, 52, 330, 240, 70, 18).map { it * 1024L },
            ContextCompat.getColor(ctx, R.color.widget_up_accent),
        )
        down.submitSamples(
            listOf(0, 0, 0, 8, 60, 0, 0, 20, 140, 40, 0, 0, 2600, 420, 60).map { it * 1024L },
            ContextCompat.getColor(ctx, R.color.widget_down_accent),
        )
        sheet.fill(R.id.tv_detail_up_rate, "↑ 0 B/s")
        sheet.fill(R.id.tv_detail_down_rate, "↓ 0 B/s")

        // ── 拓扑卡身份头
        sheet.fill(R.id.tv_globe_avatar_letter, "G")
        sheet.fill(R.id.tv_globe_name, "Guangzhou Home")
        sheet.fill(R.id.tv_globe_server, SERVER)
        sheet.fill(R.id.tv_globe_protocol_chip, "UDP_CUSTOM")
        sheet.fill(R.id.tv_globe_magic_chip, ctx.getString(R.string.connection_magic_format, "UDPC"))
        sheet.fill(R.id.tv_globe_latency, "810 ms")
        sheet.fill(R.id.tv_globe_last, ctx.getString(R.string.connection_last_connected_short, "0 分钟前"))
        sheet.fill(R.id.tv_globe_traffic, "↑ 153.5 MB  ↓ 1.6 GB")

        // ── 地球：一份"广州 → 新加坡 / 东京"的拓扑，与效果图同构
        sheet.findViewById<GlobeView>(R.id.globe_topology).submitTopology(sampleTopology())

        // ── 节点详情
        sheet.fill(R.id.tv_detail_server, SERVER)
        sheet.fill(R.id.tv_detail_user, "root")
        sheet.fill(R.id.tv_detail_last_connected, "34 分钟前")
        sheet.fill(R.id.tv_detail_protocol, "UDP_CUSTOM")
        sheet.fill(R.id.tv_detail_exit, "43.138.143.71 · 🇨🇳 Guangzhou China")
        sheet.fill(R.id.tv_detail_note, "—")

        // ── 辅助信息（服务器提示走真实的 ANSI 渲染管线，不另画一版假样式）
        sheet.fill(R.id.tv_detail_ssh_banner, "SSH-2.0-OpenSSH_10.0p2 Debian-7")
        SshBannerRenderer.applyTo(
            sheet.findViewById(R.id.tv_detail_ssh_notice),
            "\u001b[33mAUTHORIZED ACCESS ONLY\u001b[0m\n\u001b[32m哈吉米南北绿豆\u001b[0m",
        )
        sheet.findViewById<View>(R.id.row_detail_ssh_notice).visibility = View.VISIBLE
        sheet.findViewById<View>(R.id.divider_detail_ssh_notice).visibility = View.VISIBLE
        sheet.fill(R.id.tv_detail_sni, "—")
        sheet.fill(R.id.tv_detail_host, "tunnel.stun.app")
        sheet.fill(R.id.tv_detail_total, "↑ 791.9 KB  ↓ 1.3 MB")
        sheet.fill(R.id.tv_detail_uptime, "0 小时 34 分钟")
    }

    private fun sampleTopology(): GlobeTopology {
        val hub = GeoPoint(23.1291, 113.2644, "CN", "Guangzhou")
        val singapore = GeoPoint(1.3521, 103.8198, "SG", "新加坡")
        val tokyo = GeoPoint(35.6762, 139.6503, "JP", "东京")
        return GlobeTopology(
            markers = listOf(
                GlobeMarker(
                    point = hub, isCurrent = true, connectionCount = 3, bytesPerSecond = 120_000,
                    label = "Guangzhou Home", address = "43.138.143.71:22",
                ),
                GlobeMarker(
                    point = singapore, isExit = true, connectionCount = 4, bytesPerSecond = 169_000,
                    label = "新加坡节点",
                ),
                GlobeMarker(point = tokyo, connectionCount = 2, bytesPerSecond = 42_000),
            ),
            arcs = listOf(
                GlobeArc(hub, singapore, connectionCount = 4, bytesPerSecond = 169_000),
                GlobeArc(hub, tokyo, connectionCount = 2, bytesPerSecond = 42_000),
            ),
            available = true,
        )
    }

    // ────────────────────────────────────────────────────────── 工具

    private fun View.fill(id: Int, text: CharSequence) {
        findViewById<TextView>(id).text = text
    }

    /**
     * 真实 attach 到窗口（复合 drawable / 主题属性都走 `onAttachedToWindow`，不挂会画不出来）。
     *
     * 有两点是"跟真机对齐"所必需的，少一个预览就会骗人：
     *
     * 1. **必须用 AppCompatActivity**。这个 layout 里所有图标都只写了 `app:tint`，而 `app:tint`
     *    是靠 AppCompat 的 view factory 把 `ImageView`/`ImageButton` 换成 AppCompat 版才生效的
     *    （生产侧 `BottomSheetConnectionDetailsBinding.inflate(layoutInflater)` 拿到的正是带
     *    factory 的那个 inflater）。裸 `Activity` 的 inflater 没有 factory，`app:tint` 被整条丢掉，
     *    图标就退回矢量里写死的 `@android:color/white` —— 浅色面板上是白底白图标，等于什么都没有，
     *    而真机上它们是 `colorOnSecondaryContainer` / `colorOnSurfaceVariant`。
     * 2. **夜间要换配置上下文，而 inflater 得用 [LayoutInflater.cloneInContext] 复制**。直接
     *    `LayoutInflater.from(themedContext)` 会丢掉第 1 点里的 factory；`cloneInContext` 是唯一
     *    既换 context（拿到夜间资源）又保留 factory 的写法。
     */
    private fun inflateSheet(night: Boolean): View {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        controller.setup()
        val config = Configuration(activity.resources.configuration).apply {
            fontScale = 1f
            uiMode = if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val themed = ContextThemeWrapper(activity.createConfigurationContext(config), R.style.Theme_Stun)
        val sheet = LayoutInflater.from(activity).cloneInContext(themed)
            .inflate(R.layout.bottom_sheet_connection_details, null)
        activity.setContentView(sheet)
        return sheet
    }

    private fun writePng(sheet: View, name: String) {
        val density = sheet.resources.displayMetrics.density
        val width = (SHEET_WIDTH_DP * density).toInt()
        sheet.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        sheet.layout(0, 0, width, sheet.measuredHeight)

        // 先把面板画进一个**独立图层**，再把它合成到垫好底的成品上。
        //
        // 为什么不能"先垫底再 sheet.draw"直接画到成品位图上：GlobeView 每帧会
        // `drawColor(TRANSPARENT, Mode.CLEAR)` 清自己的整块区域（它是非 opaque 的装饰件，
        // 在真机上靠窗口背景当底色，扁平分模式下必须显式清帧否则残留星空模式的黑底）。
        // CLEAR 是不认图的——画在同一张位图上时，它连先前垫的底一起擦成透明，
        // 于是 PNG 里地球方框的角上是 `alpha=0`，任何看图器都会给它配一层白底，
        // 深色预览就在地球那块露出一片刺眼的白。
        // 分层之后 CLEAR 只作用于图层，图层再以 SRC_OVER 合成到垫底上，透明处自然透出底色。
        val layer = Bitmap.createBitmap(width, sheet.height, Bitmap.Config.ARGB_8888)
        Canvas(layer).apply { sheet.draw(this) }
        val bitmap = Bitmap.createBitmap(width, sheet.height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            // 垫的这层必须**走主题**取"卡片承载面"色，写死浅色同样会让深色预览露白。
            drawColor(cardBackdropColor(sheet))
            drawBitmap(layer, 0f, 0f, null)
        }
        assertFullyOpaque(bitmap)
        val dir = File("build/reports/ui-preview").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * 预览图必须**处处不透明**。透明像素一旦落到 PNG 里，看图器会给它补一层白底 ——
     * 浅色面板看不出来，深色面板就在透明处露出一片刺眼的白（地球那块踩过这个坑：
     * GlobeView 清帧用的是 `Mode.CLEAR`，把先前垫的底一起擦掉了）。
     * 出图前卡一道，防止以后有人把绘制顺序改回"先垫底再画面板"。
     */
    private fun assertFullyOpaque(bitmap: Bitmap) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val transparent = pixels.count { Color.alpha(it) == 0 }
        assertEquals(
            "预览图里有 $transparent 个全透明像素 —— 绘制顺序把垫底擦掉了，看图器会给它补白底",
            0,
            transparent,
        )
    }

    /**
     * 面板里卡片的承载面角色色。与 `HomeFragment.getThemeColor` 同一套取法：
     * 属性 id 得在本 app 的包名里查（非传递 R 类，`R.attr` 里没有库属性，
     * 而资源合并后库属性挂在 app 包名下）。
     */
    private fun cardBackdropColor(sheet: View): Int {
        val resources = sheet.resources
        for (name in listOf("colorSurfaceContainerLow", "colorSurface")) {
            val id = resources.getIdentifier(name, "attr", sheet.context.packageName)
            if (id != 0) return MaterialColors.getColor(sheet, id, Color.WHITE)
        }
        return Color.WHITE
    }

    private companion object {
        const val SHEET_WIDTH_DP = 393f
        const val SAMPLES = 15
        const val SERVER = "43.138.143.71:25000-26000 → 43.138.143.71:22"
    }
}
