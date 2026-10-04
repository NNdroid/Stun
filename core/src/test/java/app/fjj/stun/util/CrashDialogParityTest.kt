package app.fjj.stun.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「崩溃弹窗 / 复制到剪贴板必须走统一实现」的机械护栏。
 *
 * 这类收口最容易的失败方式不是改错，而是**慢慢漏回去**。崩溃弹窗尤其危险：
 * 六份副本里 tv/car/xr/wear 四份**都没有复制与分享按钮**，车机上手表上崩溃后日志完全
 * 出不来，而这种缺陷在编译期和测试期都是隐形的 —— 只有真机崩了才知道。
 *
 * 钉住的四条：
 *  1. 崩溃弹窗只有 [CrashHandler.showCrashDialog] 一处实现（不许再出现本地 `showCrashDialog`）；
 *  2. 崩溃日志字号必须用 SP（`textSize = Nf` 那个重载是 **PX**，高密度屏上读不清、
 *     且不跟随系统字体缩放）；
 *  3. 复制到剪贴板只有 [ClipboardUtils.copy] 一处，且**必须带提示**；
 *  4. `app_crash_dialog_*` 三件套（复制 / 分享 / 关闭）必须一起出现 ——
 *     只有"复制"没有"分享"的弹窗就是这次收口前的 HomeFragment。
 */
class CrashDialogParityTest {

    /**
     * 仓库根目录。
     *
     * ⚠️⚠️ **Gradle 把单元测试的工作目录设成「模块目录」**（core 的测试里 `File(".")`
     * 实测是 `E:/AndroidStudioProjects/Stun/core`，**不是仓库根**）。踩过这个坑：第一版护栏写
     * `File("app/src/main/java")` 去扫全部模块，结果解析成 `core/app/src/main/java`（不存在）
     * ⇒ `walkTopDown()` 返回空列表 ⇒ `assertTrue(offenders.isEmpty())` **恒真**，
     * 注入裸 `setPrimaryClip` 也照样全绿。**扫全仓的护栏必须用这个，不能直接 `File("app/…")`。**
     */
    private val repoRoot: File by lazy {
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle").isFile || File(it, "settings.gradle.kts").isFile }
            ?: throw AssertionError(
                "从 ${File(".").absolutePath} 往上找不到 settings.gradle，无法定位仓库根"
            )
    }

    private fun sourceFile(relative: String): String {
        val file = File(repoRoot, relative)
        if (!file.isFile) throw AssertionError("找不到 ${relative}（解析为 ${file.absolutePath}）")
        return file.readText()
    }

    /** 剥掉注释，只留代码。护栏必须只看代码，否则会被自己的 KDoc 判成违规。 */
    private fun code(relative: String): String = stripComments(sourceFile(relative))

    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            when {
                src[i] == '"' -> {
                    val start = i
                    i++
                    while (i < src.length && src[i] != '"') {
                        if (src[i] == '\\') i++
                        i++
                    }
                    i = (i + 1).coerceAtMost(src.length)
                    out.append(src, start, i)
                }
                src.startsWith("//", i) -> {
                    while (i < src.length && src[i] != '\n') i++
                }
                src.startsWith("/*", i) -> {
                    i += 2
                    while (i < src.length && !src.startsWith("*/", i)) i++
                    i = (i + 2).coerceAtMost(src.length)
                }
                else -> {
                    out.append(src[i]); i++
                }
            }
        }
        return out.toString()
    }

    /** 有源码的模块。core 之外五个都有崩溃弹窗调用点。 */
    private val MODULES = listOf("app", "core", "tv", "car", "wear", "xr", "dbwebui")

    /** 五个有崩溃弹窗的模块 + core 实现本身。 */
    private val crashDialogCallSites: List<Pair<String, String>> = listOf(
        "app/HomeFragment" to "app/src/main/java/app/fjj/stun/ui/HomeFragment.kt",
        "tv/MainActivity" to "tv/src/main/java/app/fjj/stun/tv/MainActivity.kt",
        "car/CarMainActivity" to "car/src/main/java/app/fjj/stun/car/CarMainActivity.kt",
        "wear/WearMainActivity" to "wear/src/main/java/app/fjj/stun/wear/WearMainActivity.kt",
        "xr/XRMainActivity" to "xr/src/main/java/app/fjj/stun/xr/XRMainActivity.kt",
    )

    @Test
    fun everyCrashDialogCallSiteGoesThroughCrashHandler() {
        for ((name, path) in crashDialogCallSites) {
            val c = code(path)
            assertTrue("$name 应调用 CrashHandler.showCrashDialog", c.contains("CrashHandler.showCrashDialog("))
        }
    }

    /**
     * 不许再出现本地实现的弹窗。
     *
     * 反事实：若有人为了"少传一个参数"把 `CrashHandler.showCrashDialog(...)` 内联回自己文件，
     * 上一条会立刻变红 —— 但反过来，若有人**新写**一个 `private fun showCrashDialog` 而调用点
     * 恰好还留着 `CrashHandler.` 的 import（未使用 import 不报错），上一条同样拦不住。
     * 所以补这条：弹窗构造的实体（`MaterialAlertDialogBuilder` + `ScrollView` 的组合）
     * 只准出现在 core 的 CrashHandler 里。
     */
    @Test
    fun noLocalCrashDialogImplementationAnywhere() {
        val offenders = crashDialogCallSites
            .map { (name, path) -> name to code(path) }
            .filter { (_, c) ->
                // 本地弹窗实现的指纹：自己 new 一个 MaterialAlertDialogBuilder
                c.contains("MaterialAlertDialogBuilder(") && c.contains("ScrollView(")
            }
            .map { it.first }
        assertTrue("这些文件自己实现了一份崩溃弹窗：$offenders", offenders.isEmpty())
    }

    /**
     * 日志字号必须走 SP。
     *
     * `textView.textSize = 13f` 的单位是 **PX**（`setTextSize(COMPLEX_UNIT_PX, …)` 的便捷
     * 写法），3x 密度屏上等效只有 4.3sp，且不跟随系统字体缩放 —— 无障碍直接失效。
     * 收口前六份副本里有五份踩了这个。
     */
    @Test
    fun crashLogFontSizeUsesSpNotPx() {
        val c = code("core/src/main/java/app/fjj/stun/util/CrashHandler.kt")
        assertTrue("崩溃日志字号必须用 COMPLEX_UNIT_SP", c.contains("TypedValue.COMPLEX_UNIT_SP"))
        assertFalse(
            "崩溃日志字号不能用 textSize = Nf（单位是 PX，密度屏上读不清）",
            Regex("""\.textSize\s*=\s*\d""").containsMatchIn(c),
        )
    }

    /**
     * 复制 / 分享 / 关闭三件套必须同时存在于 core 实现里。
     *
     * 收口前 HomeFragment 那份只有"复制 + 关闭"、tv/car/wear/xr 四份只有"关闭"。
     * 分享是唯一能把日志交到用户手里的途径（车机 / 手表上长按选中手动粘贴几乎做不到），
     * 所以缺它就算漂移。
     */
    @Test
    fun crashDialogKeepsCopyShareAndDismissTogether() {
        val c = code("core/src/main/java/app/fjj/stun/util/CrashHandler.kt")
        for (button in listOf("setPositiveButton", "setNeutralButton", "setNegativeButton")) {
            assertTrue("崩溃弹窗缺少 $button", c.contains(button))
        }
        assertTrue("崩溃弹窗应提供分享（ACTION_SEND）", c.contains("Intent.ACTION_SEND"))
    }

    /**
     * 复制收口 [ClipboardUtils]。
     *
     * 仓库里有 9 处 `setPrimaryClip`，原先每处自己拼 `CLIPBOARD_SERVICE` + `ClipData` +
     * `Toast`。收口后除 core 的 ClipboardUtils 与 CrashHandler（带长度后缀的专用提示）之外，
     * 不该再有裸的 `setPrimaryClip`。
     */
    @Test
    fun clipboardWritesGoThroughClipboardUtils() {
        val roots = MODULES
            .map { File(repoRoot, "$it/src/main/java") }
            .filter { it.isDirectory }
        // 反事实：路径写错时 walkTopDown() 会静默返回空列表，断言恒真。
        // 这里显式要求至少扫到 app 与 core 两个模块。
        assertTrue(
            "源码根目录没解析对，实际扫到：${roots.map { it.absolutePath }}",
            roots.any { it.path.replace('\\', '/').endsWith("app/src/main/java") } &&
                roots.any { it.path.replace('\\', '/').endsWith("core/src/main/java") },
        )

        val allowed = setOf("ClipboardUtils.kt", "CrashHandler.kt")
        val offenders = roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".kt") && it.name !in allowed }
                .filter { stripComments(it.readText()).contains("setPrimaryClip") }
                .map { it.path.replace('\\', '/') }
        }
        assertTrue("这些文件绕过了 ClipboardUtils 直接写剪贴板：$offenders", offenders.isEmpty())
    }

    /** 「复制必须有反馈」是硬约定 —— 静默复制会让用户以为按钮坏了然后反复点。 */
    @Test
    fun clipboardUtilsAlwaysGivesFeedback() {
        val c = code("core/src/main/java/app/fjj/stun/util/ClipboardUtils.kt")
        assertTrue("复制后必须给用户反馈", c.contains("Toast.makeText("))
        // 取不到 ClipboardManager 时**不能**弹"已复制" —— 弹了比不弹更糟。
        assertTrue(
            "取不到 ClipboardManager 时应直接返回 false 且不提示",
            c.contains("?: return false"),
        )
    }

    /**
     * 冗余串 `copied_to_clipboard` 与 `copy_success` 六门语言里内容完全相同
     * （zh-rTW 差一个字，纯无意义漂移），已统一到后者，别让它再回来。
     */
    @Test
    fun duplicateCopiedStringIsGone() {
        val locales = listOf("values", "values-de", "values-fr", "values-ja", "values-zh-rCN", "values-zh-rTW")
        val offenders = locales
            .map { File(repoRoot, "core/src/main/res/$it/strings.xml") }
            .filter { it.isFile && it.readText().contains("copied_to_clipboard") }
            .map { it.parentFile.name }
        // 同上：6 个文件必须**真的读到**了，否则这条也是恒真
        assertEquals("六个语言文件都要读到", locales.size, locales.count {
            File(repoRoot, "core/src/main/res/$it/strings.xml").isFile
        })
        assertTrue("copied_to_clipboard 与 copy_success 重复，应只用后者：$offenders", offenders.isEmpty())
    }
}
