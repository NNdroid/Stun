package app.fjj.stun.ui

import android.app.Application
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.fjj.stun.R
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 动画二维码两处界面的**运行时**回归：发送端分享弹窗里新增的播放控件、接收端连续扫码页。
 *
 * 为什么必须跑运行时断言（`assembleDebug` 不够）：
 *
 * 1. **字面百分号 `%%` 只有取出来才知道**。`qr_stream_scan_progress_format` 写成
 *    `已收 %1$d/%2$d 帧 · %3$d%%`，写成单个 `%` aapt 会直接报错，但写成 `%%` 只是**校验通过**，
 *    到底出不出得来一个 `%` 得运行 `getString` 才知道。
 * 2. **控件默认可见性是版式契约**。动画控件平时必须整组 `gone` —— 静态单张二维码模式是既有行为，
 *    多出来的控件会占高度、把原来的版式顶歪。
 * 3. **本地化文件漏键不会报错**，只会静默回落成英文。5 个语种的 `strings_qr_stream.xml`
 *    是手工拆的，键名打错一个就在那门语言里露出英文原文。
 * 4. 接收端布局里是库的 `DecoratedBarcodeView` + M3 组件，能不能在 `Theme.Stun` 下 inflate
 *    也要真跑一次才知道（M3 组件挂到非 Material 主题上会直接抛）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
class AnimatedQrShareLayoutTest {

    // ------------------------------------------------------- 版式契约

    @Test
    fun `分享弹窗里动画控件默认整组 GONE-静态单张码的版式不受影响`() {
        val root = inflate(R.layout.dialog_qr_code)
        for (id in intArrayOf(R.id.tv_qr_stream_info, R.id.qr_stream_controls, R.id.tv_qr_stream_hint)) {
            assertEquals(
                "${root.resources.getResourceEntryName(id)} 默认应为 gone —— 静态模式下多出来的控件会占高度、顶歪原有版式",
                View.GONE,
                root.findViewById<View>(id).visibility,
            )
        }
    }

    @Test
    fun `接收端连续扫码页能在 Theme_Stun 下 inflate 且默认是等待态`() {
        val root = inflate(R.layout.activity_qr_stream_scan)
        val hint = requireNotNull(root.findViewById<TextView>(R.id.tv_qr_scan_hint))
        assertEquals(
            "扫码页提示文案没取到本地化串",
            root.context.getString(R.string.qr_stream_scan_hint),
            hint.text.toString(),
        )
        // 进度条初始为 0，等待文案由 Activity 在 onCreate 里写入（布局本身不带 text）
        assertEquals(0, requireNotNull(root.findViewById<LinearProgressIndicator>(R.id.progress_qr_scan)).progress)
    }

    // ------------------------------------------------------- 文案：百分号与占位符

    @Test
    @Config(qualifiers = "en-rUS-w393dp-h851dp-xhdpi")
    fun `英文进度文案里的百分号取出来是单个百分号`() {
        val ctx = themedContext()
        assertEquals("Received 3/20 frames · 15%", ctx.getString(R.string.qr_stream_scan_progress_format, 3, 20, 15))
    }

    @Test
    fun `中文进度文案里的百分号取出来是单个百分号`() {
        val ctx = themedContext()
        assertEquals("已收 3/20 帧 · 15%", ctx.getString(R.string.qr_stream_scan_progress_format, 3, 20, 15))
    }

    @Test
    @Config(qualifiers = "ja-w393dp-h851dp-xhdpi")
    fun `日文进度文案里的百分号取出来是单个百分号`() {
        val ctx = themedContext()
        assertEquals("3/20 フレーム受信 · 15%", ctx.getString(R.string.qr_stream_scan_progress_format, 3, 20, 15))
    }

    @Test
    fun `帧计数与轮次文案的三个占位符都能填进去`() {
        val ctx = themedContext()
        assertEquals("第 4/20 帧 · 第 2 轮", ctx.getString(R.string.qr_stream_info_format, 4, 20, 2))
        assertTrue(
            "帧数提示里没出现帧数 —— 占位符没被替换",
            ctx.getString(R.string.qr_stream_hint_format, 7).contains("7"),
        )
    }

    // ------------------------------------------------------- 本地化文件键集合

    /**
     * 5 个语种的 `strings_qr_stream.xml` 必须与默认 `values/strings.xml` 里的
     * `qr_stream_*` 键**完全一致**。少一个键 = 那门语言静默显示英文，aapt 不管这事。
     */
    @Test
    fun `五个语种的动画二维码文案键集合与默认串一致`() {
        val valuesDir = findResDir("values")
        val expected = keysIn(File(valuesDir, "strings.xml"))
        assertTrue("默认串里没找到任何 qr_stream_* 键，测试本身失效了", expected.isNotEmpty())

        for (locale in LOCALES) {
            val file = File(findResDir("values-$locale"), "strings_qr_stream.xml")
            assertTrue("缺少 $locale 的动画二维码文案文件：${file.path}", file.isFile)
            val actual = keysIn(file)
            assertEquals("$locale 的 qr_stream_* 键集合与默认串不一致（多写/漏写都会让那门语言露出英文）", expected, actual)
        }
    }

    // ------------------------------------------------------- harness

    private fun inflate(layoutId: Int): View {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        controller.setup()
        val root = LayoutInflater.from(activity)
            .cloneInContext(themedContext())
            .inflate(layoutId, null)
        val dm = root.resources.displayMetrics
        root.measure(
            View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.AT_MOST),
        )
        root.layout(0, 0, dm.widthPixels, root.measuredHeight)
        return root
    }

    /**
     * 必须用 AppCompatActivity（AppCompat 的 view factory 才会挂上，`app:tint` 才生效），
     * 并用 `ContextThemeWrapper` 套 `Theme.Stun` —— 否则 `?attr/colorOnSurfaceVariant`
     * 一类主题角色取不到值，M3 组件也会报错。
     */
    private fun themedContext(): ContextThemeWrapper {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        controller.setup()
        val config = Configuration(activity.resources.configuration).apply { fontScale = 1f }
        return ContextThemeWrapper(activity.createConfigurationContext(config), R.style.Theme_Stun)
    }

    private fun findResDir(name: String): File {
        listOf(File("src/main/res/$name"), File("app/src/main/res/$name"))
            .firstOrNull { it.isDirectory }
            ?.let { return it }
        throw AssertionError("找不到 res/$name（工作目录 ${File(".").absolutePath}）")
    }

    private fun keysIn(file: File): Set<String> =
        KEY.findAll(file.readText(Charsets.UTF_8)).map { it.groupValues[1] }.toSortedSet()

    private companion object {
        /** 与 `values-<locale>` 目录一一对应（见 stun-i18n-string-add 技能里的语言表）。 */
        val LOCALES = listOf("zh-rCN", "zh-rTW", "de", "fr", "ja")

        val KEY = Regex("""<(?:string|plurals)\s+name="(qr_stream_[a-z_0-9]+)"""")
    }
}
