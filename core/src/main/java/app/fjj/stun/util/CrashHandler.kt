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
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.repo.StunRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
        showCrashDialog(activity, crashReport)
    }

    fun showCrashDialog(activity: Activity, crashReport: String) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread

            val density = activity.resources.displayMetrics.density
            val paddingPx = (16 * density).toInt()
            // 滚动区最大高度：屏幕高的 70%，给标题与按钮条留出空间。窗口高度交给内容自适应，
            // 短日志贴紧、长日志滚到上限封顶 —— 不再出现按钮下方的大片空白。
            val maxScrollHeight = (activity.resources.displayMetrics.heightPixels * 0.70f).toInt()
            val dialogWidth = (activity.resources.displayMetrics.widthPixels * 0.95f).toInt()

            val textView = TextView(activity).apply {
                text = crashReport
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextIsSelectable(true)
                setPadding(paddingPx, paddingPx / 2, paddingPx, paddingPx)
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
                setPadding(paddingPx, paddingPx / 2, paddingPx, paddingPx)
                clipToPadding = false
            }
            scrollView.addView(textView, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            val dialog = MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(CoreR.string.app_crash_dialog_title) +
                    " (${crashReport.length} chars)")
                .setView(scrollView)
                .setPositiveButton(activity.getString(CoreR.string.app_crash_dialog_copy)) { _, _ ->
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clip = ClipData.newPlainText("Stun Crash Log", crashReport)
                    clipboard?.setPrimaryClip(clip)
                    Toast.makeText(activity, activity.getString(CoreR.string.app_crash_dialog_copied) +
                        " (${crashReport.length} chars)", Toast.LENGTH_LONG).show()
                }
                .setNeutralButton(activity.getString(CoreR.string.app_crash_dialog_share)) { _, _ ->
                    try {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Stun Crash Report")
                            putExtra(Intent.EXTRA_TEXT, crashReport)
                        }
                        activity.startActivity(Intent.createChooser(shareIntent, activity.getString(CoreR.string.app_crash_dialog_share)))
                    } catch (_: Throwable) {}
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
}
