package app.fjj.stun.util

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.repo.StunRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashHandler {

    private const val TAG = "CrashHandler"
    private const val CRASH_FILE_NAME = "last_crash.txt"
    private const val PREFS_NAME = "stun_crash_handler"
    private const val KEY_CRASH_REPORT = "key_last_crash_report"

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true

        val appContext = context.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                handleUncaughtException(appContext, thread, throwable)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to record crash: ${e.message}", e)
            } finally {
                // Delegate back to default handler for system crash handling
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun threadIdCompat(thread: Thread): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) thread.threadId() else thread.id

    private fun handleUncaughtException(context: Context, thread: Thread, throwable: Throwable) {
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val appVersion = try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pInfo.versionName} (${PackageInfoCompat.getLongVersionCode(pInfo)})"
        } catch (_: Exception) {
            "Unknown"
        }

        val stackTrace = Log.getStackTraceString(throwable)
        val sb = StringBuilder()
        sb.append("================ STUN CRASH REPORT ================\n")
        sb.append("Time: ").append(timeStr).append("\n")
        sb.append("App Version: ").append(appVersion).append("\n")
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n")
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("Thread: ").append(thread.name).append(" (id=").append(threadIdCompat(thread)).append(")\n")
        sb.append("Exception: ").append(throwable.javaClass.name).append("\n")
        sb.append("Message: ").append(throwable.message ?: "null").append("\n")
        sb.append("----------------- STACKTRACE -----------------\n")
        sb.append(stackTrace).append("\n")
        sb.append("==================================================\n")

        val crashReport = sb.toString()
        Log.e(TAG, "Uncaught Exception Captured:\n$crashReport")

        // 1. Write to internal storage file
        try {
            val file = File(context.filesDir, CRASH_FILE_NAME)
            file.writeText(crashReport)
        } catch (_: Throwable) {}

        // 2. Backup to SharedPreferences as well
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit(commit = true) {
                putString(KEY_CRASH_REPORT, crashReport)
            }
        } catch (_: Throwable) {}

        // 3. 追加进崩溃历史台账。与上面 last_crash.txt 那份不同：后者是「上次崩溃」的一次性
        //    标记，checkPreviousCrash 读一次就删；这里长期累积，供 WebUI 事后查询，由用户手动清空。
        CrashHistoryStore.record(context, CrashHistoryStore.TYPE_JVM, crashReport)
    }

    /**
     * Checks if a previous crash log exists (Java/Kotlin or Go core).
     * If found, returns the log text and clears the flag so it won't repeat.
     */
    fun checkPreviousCrash(context: Context): String? {
        // 1. Check Java/Kotlin crash file
        try {
            val file = File(context.filesDir, CRASH_FILE_NAME)
            if (file.exists() && file.length() > 0) {
                val content = file.readText()
                file.delete()
                // Clear prefs backup
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    remove(KEY_CRASH_REPORT)
                }
                return content
            }
        } catch (_: Throwable) {}

        // 2. Check SharedPreferences backup
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val report = prefs.getString(KEY_CRASH_REPORT, null)
            if (!report.isNullOrBlank()) {
                prefs.edit { remove(KEY_CRASH_REPORT) }
                return report
            }
        } catch (_: Throwable) {}

        // 3. Check Go core crash file
        try {
            val goCrash = StunRepository.checkPreviousCrash(context)
            if (!goCrash.isNullOrBlank()) {
                return "================ GO CORE CRASH DUMP ================\n$goCrash\n===================================================="
            }
        } catch (_: Throwable) {}

        return null
    }

    /**
     * Shows a Material 3 dialog on Activity startup if a crash occurred previously.
     */
    fun showCrashDialogIfAny(activity: Activity) {
        val crashReport = checkPreviousCrash(activity) ?: return
        // 这里是 **App 进程**上次崩溃（JVM 或 Go core dump），不是内核引擎运行期异常 ——
        // 所以用 app_crash_dialog_title，与 HomeFragment 处理 crashEvent 的那条路径区分开。
        showCrashDialog(
            activity,
            crashReport,
            CrashDialogStyle(titleRes = CoreR.string.app_crash_dialog_title),
        )
    }

    /**
     * 崩溃日志弹窗的**唯一实现**。
     *
     * ## 为什么必须是同一个函数
     * 这段弹窗构造原先被抄了 **6 份**（本文件 + app 的 HomeFragment + tv/car/wear/xr 的
     * MainActivity），且**每一份都漂移出不同的缺陷**：
     *
     * | 能力 | 本文件 | HomeFragment | tv | car | xr | wear |
     * |---|---|---|---|---|---|---|
     * | 复制日志 | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ |
     * | **分享日志** | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
     * | 窗口高度自适应 | ✅ | **固定 80%** | ✅ | ✅ | ✅ | ✅ |
     * | 日志字号 | sp | **px** | **px** | **px** | **px** | **px** |
     * | 文本可选中 | ✅ | ✅ | ✅ | ✅ | ✅ | **❌** |
     *
     * 三处是用户能直接感知的问题，不是洁癖：
     *  - **分享缺失**：崩溃日志是用户唯一能拿给开发者看的材料，却要靠长按选中再手打
     *    粘贴到聊天框 —— 几十 KB 的栈在手机上几乎无法完成。
     *  - **字号用 px**：`textSize = 13f` 这个属性赋值的单位是 **PX**，不是 SP。3x 密度屏上
     *    等效只有 4.3sp，日志小到几乎读不清，且不跟随系统字体缩放（无障碍失效）。
     *  - **tv/car/xr/wear 四份都缺复制与分享**：那些端一旦崩溃，日志完全出不来。
     *
     * ## 差异怎么参数化
     * 真正需要按端区分的只有**标题与字号**（手表屏幕小），其余全同构。见 [CrashDialogStyle]。
     */
    fun showCrashDialog(
        activity: Activity,
        crashReport: String,
        style: CrashDialogStyle = CrashDialogStyle(),
    ) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread

            val density = activity.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()
            val paddingH = dp(style.horizontalPaddingDp)
            val paddingV = dp(style.verticalPaddingDp)
            // 滚动区最大高度：屏幕高的 70%，给标题与按钮条留出空间。窗口高度交给内容自适应，
            // 短日志贴紧、长日志滚到上限封顶 —— 不再出现按钮下方的大片空白。
            val maxScrollHeight = (activity.resources.displayMetrics.heightPixels * 0.70f).toInt()
            val dialogWidth = (activity.resources.displayMetrics.widthPixels * 0.95f).toInt()

            val textView = TextView(activity).apply {
                text = crashReport
                typeface = Typeface.MONOSPACE
                // ⚠️ 必须走 setTextSize(COMPLEX_UNIT_SP, …)：`textSize = 13f` 那个属性赋值的
                // 单位是 **PX**，高密度屏上等效只有 4.3sp，小到读不清且不跟随系统字体缩放。
                // 六份副本里有五份踩了这个。
                setTextSize(TypedValue.COMPLEX_UNIT_SP, style.textSizeSp)
                // 长按选中复制是"没有分享按钮时"的最后退路，不能在手表上关掉。
                setTextIsSelectable(true)
                setPadding(paddingH, paddingV, paddingH, paddingV)
            }

            val scrollView = object : ScrollView(activity) {
                override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                    super.onMeasure(
                        widthSpec,
                        MeasureSpec.makeMeasureSpec(maxScrollHeight, MeasureSpec.AT_MOST),
                    )
                }
            }.apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(paddingH, paddingV, paddingH, paddingV)
                clipToPadding = false
            }
            scrollView.addView(textView, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            // 标题与"复制成功"提示都带日志长度。走串资源而不是拼 " chars"：
            // 非英文界面下混一个英文单位是本地化缺口。
            val sizeSuffix = activity.getString(CoreR.string.app_crash_dialog_size_suffix, crashReport.length)

            val dialog = MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(style.titleRes) + sizeSuffix)
                .setView(scrollView)
                .setPositiveButton(activity.getString(CoreR.string.app_crash_dialog_copy)) { _, _ ->
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clip = ClipData.newPlainText("Stun Crash Log", crashReport)
                    clipboard?.setPrimaryClip(clip)
                    val msg = activity.getString(CoreR.string.app_crash_dialog_copied) + sizeSuffix
                    // ⚠️ 复制反馈**默认 Toast，但有 Snackbar 锚点时优先 Snackbar**：
                    // Toast 在横屏 / 多窗口 / 通知栏下拉时可能被遮住，用户根本看不到。
                    // HomeFragment 原本就是 Snackbar —— 收口时不能把它退化成 Toast。
                    val anchor = style.feedbackAnchor?.takeIf { it.isAttachedToWindow }
                    if (anchor != null) {
                        Snackbar.make(anchor, msg, Snackbar.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                    }
                }
                .setNeutralButton(activity.getString(CoreR.string.app_crash_dialog_share)) { _, _ ->
                    shareCrashReport(activity, crashReport)
                }
                .setNegativeButton(activity.getString(CoreR.string.app_crash_dialog_dismiss), null)
                .create()

            dialog.show()
            // 只固定宽度，高度交给内容：短日志贴紧、长日志由上面的滚动区封顶。
            dialog.window?.setLayout(
                dialogWidth,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    /**
     * 按端定制崩溃弹窗的排版与反馈方式。
     *
     * 只暴露真正需要按端区分的量；按钮集合、复制/分享行为、滚动高度上限一律共用 ——
     * 那些不是"端差异"，是当初各抄各的漏抄。
     *
     * @param titleRes 注意 `crash_dialog_title*` 是**内核引擎**崩溃，
     *   `app_crash_dialog_title` 是 **App 进程**崩溃，两者语义不同，别互相顶替。
     * @param feedbackAnchor Snackbar 锚点（通常是 Fragment 的 `binding.root`）。传了就用
     *   Snackbar 显示复制反馈，没传（或已 detach）回落 Toast。
     */
    data class CrashDialogStyle(
        val titleRes: Int = CoreR.string.app_crash_dialog_title,
        /** 日志字号（SP）。手表屏幕小，用 11；其余 12。 */
        val textSizeSp: Float = 12f,
        val horizontalPaddingDp: Int = 16,
        val verticalPaddingDp: Int = 8,
        val feedbackAnchor: View? = null,
    )

    /**
     * 调起系统分享面板发送崩溃日志。
     *
     * 单独抽出来是因为它是唯一带 `startActivity` 的分支：车机 / 头显 / 手表上未必装了
     * 可接收 `text/plain` 的应用，`createChooser` 与 `startActivity` 都会抛
     * `ActivityNotFoundException`，所以整段兜底而不是只 catch 一次。
     */
    private fun shareCrashReport(activity: Activity, crashReport: String) {
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Stun Crash Report")
                putExtra(Intent.EXTRA_TEXT, crashReport)
            }
            activity.startActivity(
                Intent.createChooser(shareIntent, activity.getString(CoreR.string.app_crash_dialog_share))
            )
        } catch (_: Throwable) {
            // 没有可接收的应用（车机 / 头显常态）。此时用户仍可长按日志手动复制，
            // 所以静默即可 —— 这不是错误路径，只是"这台设备没装分享目标"。
        }
    }
}
