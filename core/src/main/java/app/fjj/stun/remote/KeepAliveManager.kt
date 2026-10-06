package app.fjj.stun.remote

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.util.BackgroundExemptions
import app.fjj.stun.util.RootShell
import app.fjj.stun.util.ShizukuState
import app.fjj.stun.util.ShizukuUtils
import app.fjj.stun.worker.KeepAliveWorker
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 「保活与后台」开关的执行端（Web 控制台侧）。
 *
 * ## 两条路径，各自解决什么
 *
 * 远程控制面（Web 控制台 [WebServer]、蓝牙 [BluetoothSyncManager]、局域网 [RemoteSyncManager]、
 * MCP [StunMcpServer]）都是**跑在 app 进程里的 object**，不是 Service：进程被系统回收 ⇒
 * 监听面一起消失，而且**没有任何组件会重建它们**（本仓没有 `BOOT_COMPLETED` receiver）。
 * 所以要让「随时能开控制台开隧道 / 用手机远控」，得有人把进程拉回来。
 *
 * 1. **Magisk `service.d`**（需 root，重启后仍在）
 *    写一个脚本进 `/data/adb/service.d/`，开机由 Magisk / KernelSU / APatch 执行：拉起 App
 *    ⇒ 监听面随宿主重建；脚本随后 fork 出的看门狗在进程被杀之后反复拉起。
 *    **唯一能扛住设备重启**的方案。
 * 2. **Shizuku**（免 root，但依赖 Shizuku 自己在跑）
 *    周期任务（[KeepAliveWorker]）在进程被杀后由 WorkManager 唤活进程，并经
 *    [RemoteControlHost.restoreAll] 把掉了的监听面补回来；同时借 Shizuku 反复断言电池白名单
 *    / 待机桶 —— **Doze 才是掉线的主因**，白名单被摘掉的话光有周期任务也不顶用。
 *
 * 具体"该补回哪些监听面"不在本类判定：交 [RemoteControlHost]，前台保活服务与 Shizuku
 * 周期任务共用同一份逻辑。
 *
 * ## 开关语义（重要）
 * 设置里的 flag 只是**用户意图**，不等于"保活正在生效"：service.d 脚本可能因刷机还原 `/data/adb`
 * 而消失，Shizuku 也可能根本没在跑。所以 [statusBundle] 把「意图」与「现实」分开报给前端，
 * 由 UI 提示用户（例如「开关是开的，但脚本已丢失」）。
 * **动作失败时绝不落库** —— 否则开关会撒谎。
 */
object KeepAliveManager {

    private const val TAG = "KeepAlive"

    /** 写入 Magisk service.d 的脚本名（不含路径）。 */
    const val SERVICE_D_SCRIPT_NAME = "stun-keepalive.sh"
    private const val SERVICE_D_DIR = "/data/adb/service.d"
    private val SERVICE_D_PATH = "$SERVICE_D_DIR/$SERVICE_D_SCRIPT_NAME"

    /** 看门狗巡检间隔：太密白烧电，太疏则崩了之后控制台长时间不可用。 */
    private const val WATCHDOG_INTERVAL_SEC = 30
    /** 开机首次拉起前的等待，等 User 0 / 包管理就绪，否则 `am start` 会被挡掉。 */
    private const val BOOT_DELAY_SEC = 25

    /** 失败码。前端据此本地化，服务端不拼文案（也别在这里返回给人看的句子）。 */
    object Code {
        const val NO_ROOT = "no_root"
        const val LAUNCHER_NOT_FOUND = "launcher_not_found"
        const val SCRIPT_WRITE_FAILED = "script_write_failed"
        const val SCRIPT_REMOVE_FAILED = "script_remove_failed"
        const val SHIZUKU_NOT_RUNNING = "shizuku_not_running"
        const val SHIZUKU_NO_PERMISSION = "shizuku_no_permission"
    }


    sealed class Outcome {
        object Ok : Outcome()
        data class Failed(val code: String) : Outcome()
    }

    // ── 运行环境探测 ─────────────────────────────────────────────

    fun hasRoot(): Boolean = RootShell.isRoot()

    /**
     * `/data/adb/service.d/` 里的脚本是不是真的还在。
     *
     * 读它需要 root，所以只在开关已开时才调用（见 [statusBundle]）—— 否则每次打开设置页
     * 都要起一次 root shell，在没 root 的设备上纯属浪费（还可能触发 su 授权弹窗）。
     */
    fun isServiceDInstalled(): Boolean =
        RootShell.exec("test -f \"$SERVICE_D_PATH\"", TAG) == 0


    // ── Magisk service.d ─────────────────────────────────────────

    /**
     * 找出本包的启动 Activity，输出 `am start -n` 需要的 `pkg/activity`。
     *
     * 刻意在**安装时**用 PackageManager 解析出具体组件再写死进脚本，而不是让脚本自己在 shell
     * 里猜 LAUNCHER：电视端 manifest 挂的是 `LEANBACK_LAUNCHER`、手机端是 `LAUNCHER`，
     * 两种 category 不同，拿其中一个去查另一个模块会查不到。
     */
    private fun resolveLauncherComponent(context: Context): String? {
        // 两个 category 都查一遍：电视端 manifest 挂 LEANBACK_LAUNCHER、手机端挂 LAUNCHER，
        // 各自只能命中自己那一个。queryIntentActivities 是按"intent 里声明的 category"去匹配
        // filter 的，不受设备形态影响，所以不需要额外造一个电视配置的 Context。
        val probes = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
        )
        for (intent in probes) {
            val matches = try {
                context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            } catch (_: Throwable) {
                emptyList()
            }
            val hit = matches.firstOrNull { it.activityInfo != null }
            if (hit != null) {
                return ComponentName(context.packageName, hit.activityInfo.name).flattenToString()
            }
        }
        return null
    }

    private fun renderScript(context: Context, component: String): String? = try {
        context.assets.open("scripts/$SERVICE_D_SCRIPT_NAME").bufferedReader().use { it.readText() }
            .replace("__PKG__", context.packageName)
            .replace("__COMP__", component)
            .replace("__INTERVAL__", WATCHDOG_INTERVAL_SEC.toString())
            .replace("__BOOT_DELAY__", BOOT_DELAY_SEC.toString())
    } catch (e: Exception) {
        StunLogger.e(TAG, "Failed to read/render $SERVICE_D_SCRIPT_NAME", e)
        null
    }

    /**
     * 开启：把脚本装进 `/data/adb/service.d/` 并验证落地。
     * 返回 [Outcome.Failed] 时**不**落库 —— 调用方要保证设置 flag 仍是关闭的。
     */
    fun installServiceD(context: Context): Outcome {
        if (!hasRoot()) return Outcome.Failed(Code.NO_ROOT)
        val component = resolveLauncherComponent(context) ?: run {
            StunLogger.e(TAG, "No launcher activity found for ${context.packageName}")
            return Outcome.Failed(Code.LAUNCHER_NOT_FOUND)
        }
        val script = renderScript(context, component) ?: return Outcome.Failed(Code.SCRIPT_WRITE_FAILED)

        // 先落到 cacheDir（app 自己的目录，不需要 root），再让 root 搬过去。
        val staged = try {
            File(context.cacheDir, SERVICE_D_SCRIPT_NAME).apply {
                writeText(script)
                setExecutable(true, true)
            }
        } catch (e: Exception) {
            StunLogger.e(TAG, "Failed to stage keep-alive script", e)
            return Outcome.Failed(Code.SCRIPT_WRITE_FAILED)
        }

        // `&&` 串起 cp / chmod / restorecon，`;` 之后的 test -f 决定最终退出码 ——
        // 校验必须落在最后，否则 cp 失败但 test 成功会被误判成"已安装"。
        val cmd = "cp -f \"${staged.absolutePath}\" \"$SERVICE_D_PATH\" && " +
            "chmod 700 \"$SERVICE_D_PATH\" && " +
            "(restorecon \"$SERVICE_D_PATH\" 2>/dev/null || true); " +
            "test -f \"$SERVICE_D_PATH\""
        val code = RootShell.exec(cmd, TAG)
        if (code != 0) {
            StunLogger.e(TAG, "installServiceD failed, root exit=$code")
            return Outcome.Failed(Code.SCRIPT_WRITE_FAILED)
        }
        StunLogger.i(TAG, "service.d keep-alive installed, launcher=$component")
        return Outcome.Ok
    }

    /** 关闭：删掉脚本并验证已消失。 */
    fun removeServiceD(): Outcome {
        if (!hasRoot()) return Outcome.Failed(Code.NO_ROOT)
        val cmd = "rm -f \"$SERVICE_D_PATH\"; " +
            "if [ -e \"$SERVICE_D_PATH\" ]; then exit 1; else exit 0; fi"
        val code = RootShell.exec(cmd, TAG)
        if (code != 0) {
            StunLogger.e(TAG, "removeServiceD failed, root exit=$code")
            return Outcome.Failed(Code.SCRIPT_REMOVE_FAILED)
        }
        StunLogger.i(TAG, "service.d keep-alive removed")
        return Outcome.Ok
    }

    // ── Shizuku ──────────────────────────────────────────────────

    /**
     * 开启 Shizuku 保活。
     *
     * @param requestPermission Shizuku 在跑但尚未授权时，是否弹窗申请。远端 WebUI 场景下
     *   弹窗是显示在**设备屏幕**上的，所以调用方一般只在确定用户就在设备前时才传 true；
     *   否则直接返回 `shizuku_no_permission`，让 UI 提示用户去设备上处理。
     */
    fun enableShizukuKeepAlive(context: Context, requestPermission: Boolean): Outcome {
        when (ShizukuUtils.state()) {
            ShizukuState.NOT_RUNNING -> return Outcome.Failed(Code.SHIZUKU_NOT_RUNNING)
            ShizukuState.NO_PERMISSION -> {
                if (!requestPermission) return Outcome.Failed(Code.SHIZUKU_NO_PERMISSION)
                // requestPermissionAwait 内部已内置超时（见 ShizukuUtils），不会无限挂着 HTTP 请求
                val granted = runBlocking { ShizukuUtils.requestPermissionAwait() }
                if (!granted) return Outcome.Failed(Code.SHIZUKU_NO_PERMISSION)
            }
            ShizukuState.READY -> Unit
        }
        BackgroundExemptions.applyViaShizuku(context.packageName)
        SettingsManager.saveShizukuKeepAliveEnabled(context, true)
        KeepAliveWorker.schedule(context)
        // 立刻跑一次，别让用户开完开关还要干等一个周期才见效。
        runShizukuKeepAliveOnce(context)
        StunLogger.i(TAG, "Shizuku keep-alive enabled")
        return Outcome.Ok
    }

    fun disableShizukuKeepAlive(context: Context): Outcome {
        KeepAliveWorker.cancel(context)
        // 撤掉省电豁免：deviceidle 白名单是**跨重启留存**的，只停 worker 会让「已关闭」的开关
        // 继续把本 App 留在系统后台白名单里（卸载都不一定清得掉）。撤销是尽力而为 ——
        // Shizuku 不在跑时静默跳过（此时确实没法撤），不影响开关本身的落库。
        BackgroundExemptions.revertViaShizuku(context.packageName)
        SettingsManager.saveShizukuKeepAliveEnabled(context, false)
        StunLogger.i(TAG, "Shizuku keep-alive disabled")
        return Outcome.Ok
    }

    /**
     * 周期任务里跑的那一次；返回一行便于看日志的描述。
     *
     * 刻意**不去 `am start` 拉界面**：所有远程控制面只要一个 `Context` 就能起，而 worker
     * 运行时通常一个 Activity 都没有（进程刚被 WorkManager 唤活）。不拉界面就不会在电视上反复弹窗。
     */
    fun runShizukuKeepAliveOnce(context: Context): String {
        val app = context.applicationContext
        val notes = mutableListOf<String>()

        // 1) 重新断言省电豁免：不少 ROM 的"清理加速 / 自启动管理"会偷偷把白名单摘掉。
        if (ShizukuUtils.state() == ShizukuState.READY) {
            BackgroundExemptions.applyViaShizuku(app.packageName)
            notes += "exemption-reasserted"
        } else {
            notes += "shizuku-unavailable"
        }

        // 2) 监听面恢复统一交给 RemoteControlHost：它知道本机有哪些远程控制面、哪些该开着，
        //    Shizuku 周期任务与前台保活服务共用同一份判定，不再各自手抄一份 WebServer 检查。
        notes += RemoteControlHost.restoreAll(app)

        // 3) 前台保活服务不会跟着 WorkManager 的唤活自己回来，补挂一次（幂等）：
        //    挂上后进程回到前台优先级，才不会在下一个 15min 周期到来前又被裁掉。
        //    Android 12+ 的后台启动限制可能拒绝 —— start() 内部已降级为仅日志。
        if (RemoteControlHost.needsProcessKeepAlive(app)) {
            RemoteControlHost.attachProcessKeepAlive(app)
            notes += "keepalive-fgs-attach"
        }
        return notes.joinToString(",")
    }

    // ── 状态上报（/api/keepalive/status 用）────────────────────────

    fun statusBundle(context: Context): Map<String, Any?> {
        val app = context.applicationContext
        val magiskOn = SettingsManager.isMagiskServiceDEnabled(app)
        return mapOf(
            // 远程控制常驻（前台保活服务）的开关意图。
            "foregroundEnabled" to SettingsManager.isRemoteControlKeepAlive(app),
            "magiskEnabled" to magiskOn,
            // 只在开关已开时才起 root shell 核实脚本是否还在；没开过就别去打扰用户设备。
            "magiskInstalled" to if (magiskOn) isServiceDInstalled() else false,
            "shizukuEnabled" to SettingsManager.isShizukuKeepAliveEnabled(app),
            "shizukuState" to ShizukuUtils.state().name,
            "hasRoot" to hasRoot(),
            // 各监听面的「意图 / 现状」：开关开了但通道没起来（蓝牙权限被拒等）也能被看见。
            "listeners" to RemoteControlHost.listenerStatus(app),
            "processKeepAliveNeeded" to RemoteControlHost.needsProcessKeepAlive(app),
        )
    }
}
