package app.fjj.stun.util

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「失败反馈必须走 `UserFeedback.error`（Snackbar）」的机械护栏。
 *
 * ## 为什么 Toast 不够
 * Toast 在横屏、多窗口、通知栏下拉时都可能**被遮住用户完全看不到**，而且无法复制。
 * 失败原因恰恰是用户最需要看清、最可能想转述给别人听的一类信息。
 * 收口前 112 处 Toast 只有 7 处 Snackbar，同一个「导入失败」在主界面弹 Toast、
 * 在设置页弹 Snackbar —— 用户学到的是"这个 App 的提示不可靠"，于是开始忽略所有提示。
 *
 * ## 钉住的
 * 1. 那批**失败/原因类**串名不再出现裸 `Toast.makeText`；
 * 2. `UserFeedback.error` 内部必须有 Snackbar 优先 + Toast 兜底（**绝不静默吞掉**）；
 * 3. 少数**刻意保留 Toast** 的地方有明确理由（见 allowlist），不许被"顺手统一"掉。
 */
class UserFeedbackParityTest {

    private val repoRoot: File by lazy {
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle").isFile || File(it, "settings.gradle.kts").isFile }
            ?: throw AssertionError("从 ${File(".").absolutePath} 往上找不到 settings.gradle")
    }

    private fun read(relative: String): String {
        val f = File(repoRoot, relative)
        if (!f.isFile) throw AssertionError("找不到 ${relative}（解析为 ${f.absolutePath}）")
        return f.readText()
    }

    private fun code(relative: String): String = stripComments(read(relative))

    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            when {
                src[i] == '"' -> {
                    val start = i; i++
                    while (i < src.length && src[i] != '"') { if (src[i] == '\\') i++; i++ }
                    i = (i + 1).coerceAtMost(src.length)
                    out.append(src, start, i)
                }
                src.startsWith("//", i) -> { while (i < src.length && src[i] != '\n') i++ }
                src.startsWith("/*", i) -> {
                    i += 2
                    while (i < src.length && !src.startsWith("*/", i)) i++
                    i = (i + 2).coerceAtMost(src.length)
                }
                else -> { out.append(src[i]); i++ }
            }
        }
        return out.toString()
    }

    private val modules = listOf("app", "core", "tv", "car", "wear", "xr")

    private fun mainKotlinFiles(): List<Pair<String, String>> =
        modules.flatMap { m ->
            val root = File(repoRoot, "$m/src/main/java")
            if (!root.isDirectory) return@flatMap emptyList()
            root.walkTopDown()
                .filter { f -> f.isFile && f.name.endsWith(".kt") }
                .map { f ->
                    val rel = "$m/${f.relativeTo(repoRoot).path.replace('\\', '/')}"
                    rel to f.readText()
                }
                .toList()
        }

    /**
     * 失败/原因类反馈不许再走裸 Toast。
     *
     * ⚠️ **必须按「同一行/同一条语句」判定，不能按「同一文件」判定**。第一版写成
     * `文件含 Toast.makeText && 文件含 .string.失败串` ⇒ 一个文件里 15 处已改好的
     * `UserFeedback.error(...invalid_qr...)` 加上别处 10 处正常 Toast，就把整个文件判成违规
     * （app 侧 9 个文件全被误报）。这正是"文件级断言"的典型失效。
     */
    @Test
    fun failureFeedbackNoLongerUsesBareToast() {
        val failureStrings = listOf(
            "vpn_permission_denied", "notification_permission_required", "invalid_qr",
            "main_qr_fail", "error_no_profile_selected", "speed_test_error",
            "error_field_required", "error_invalid_private_key", "export_failed",
            "error_unsupported_backup", "import_failed", "error_prefix", "error_unknown",
            "push_failed", "remote_command_failed", "tv_remote_no_profiles",
        )
        val offenders = mutableListOf<String>()
        for ((path, src) in mainKotlinFiles()) {
            if (path.endsWith("ui/UserFeedback.kt")) continue
            for ((i, line) in stripComments(src).lines().withIndex()) {
                if (!line.contains("Toast.makeText")) continue
                if (failureStrings.any { line.contains(".string.$it") }) {
                    offenders += "$path:${i + 1}  ${line.trim().take(90)}"
                }
            }
        }
        assertTrue("这些失败反馈还在用 Toast，应改 UserFeedback.error：\n${offenders.joinToString("\n")}", offenders.isEmpty())
    }

    /**
     * 兜底不能丢：anchor 不可用时必须回落到 Toast。
     *
     * 反事实：若有人"简化"成 `Snackbar.make(requireNotNull(anchor), …)`，
     * 权限回调那种 `binding` 尚未 inflate 的场景会直接崩；或改成 anchor 为 null 就
     * 静默返回 —— 那是"没提示"，比提示方式不理想糟糕得多。
     */
    @Test
    fun userFeedbackFallsBackToToastInsteadOfDroppingIt() {
        val c = code("core/src/main/java/app/fjj/stun/ui/UserFeedback.kt")
        assertTrue("必须用 Snackbar", c.contains("Snackbar.make("))
        assertTrue("anchor 不可用时必须回落 Toast", c.contains("Toast.makeText("))
        assertTrue(
            "anchor 必须做可用性检查（未 attach / 未测量宽度时 Snackbar 会崩或不可见）",
            c.contains("isAttachedToWindow"),
        )
    }

    /**
     * 少数**刻意保留 Toast** 的场景，钉住理由以免被"顺手统一"。
     *
     * `AnimatedQrScanActivity` 相机权限被拒后**下一行就是 `finish()`** ——
     * Snackbar 还没显示就随 Activity 一起没了，等于没给反馈。Toast 走系统窗口，
     * 不受 Activity 生命周期影响。
     */
    @Test
    fun theFinishImmediatelyCaseKeepsToast() {
        val c = code("app/src/main/java/app/fjj/stun/ui/qr/AnimatedQrScanActivity.kt")
        assertTrue("扫码页权限被拒仍应走 Toast（紧接着就 finish）", c.contains("qr_stream_camera_denied"))
        assertTrue(
            "该处必须紧跟 finish()，否则保留 Toast 的理由不成立",
            Regex("""qr_stream_camera_denied.*\n.*finish\(\)""").containsMatchIn(c),
        )
    }

    /** 短告知（已选中 / 已删除 / 列表为空）保留 Toast 是对的，不许被批量改掉。 */
    @Test
    fun shortNoticesKeepUsingToast() {
        val c = code("app/src/main/java/app/fjj/stun/ui/HomeFragment.kt")
        assertTrue("「已选中」这类短告知应保留 Toast", c.contains("string.main_selected"))
        assertTrue("「已删除」这类短告知应保留 Toast", c.contains("string.toast_deleted"))
    }
}
