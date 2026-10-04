package app.fjj.stun.util

import app.fjj.stun.repo.StunLogger
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * **root（su）能力的唯一入口。**
 *
 * 原先这些方法散在 `ExecUtils` 里，而 `ExecUtils` 同时还管着资产部署（binaryDeploy /
 * scriptDeploy / copyAssetToCache）—— 两件毫不相干的事共用一个类名，于是"跑 root 命令"
 * 和"拷文件"在调用点看起来一样重要。现在拆开：root 归这里，资产部署归 [AssetDeployer]。
 *
 * 仓库里**任何**需要 root 的地方都必须走这里（判断权限、执行命令），不允许直接碰
 * `com.topjohnwu.superuser.Shell` —— 90 秒超时、stdout/stderr 回收、异常兜底只有这一份实现，
 * 各自 `Shell.cmd(...)` 的话就会出现"有的调用点不超时、超时行为还不一样"的不一致。
 */
object RootShell {
    private const val TAG = "RootShell"

    /** root 命令执行超时上限。libsu 的 su 握手在 ROM 上可能卡住，没有兜底就会挂死调用线程。 */
    const val EXEC_TIMEOUT_SECONDS = 90L

    /**
     * root 命令跑在**独立线程池**上而不是调用线程：`Shell.cmd().exec()` 内部会等 su 进程
     * 建链，App 主线程 / 服务主线程直接调会 ANR。
     */
    private val rootCommandExecutor = Executors.newCachedThreadPool()

    /** 设备是否已授予 root。注意这会触发 su 握手，**耗时**，别在主线程裸调。 */
    fun isRoot(): Boolean = try {
        Shell.getShell().isRoot
    } catch (e: Exception) {
        StunLogger.w(TAG, "Root check failed: ${e.message}")
        false
    }

    /**
     * 执行一条 root 命令。
     *
     * @return 进程退出码；超时（[EXEC_TIMEOUT_SECONDS]）或抛异常时返回 -1。
     *   ⚠️ 调用方**不能**把 0 当成"成功"的唯一证据 —— 拼错的命令在某些 su 实现下也返回 0，
     *   真正需要确认的动作应在命令尾部自带校验（例如 `... ; test -f <path>`）。
     */
    fun exec(cmd: String, tag: String = TAG): Int {
        val outCallback = object : CallbackList<String>() {
            override fun onAddElement(line: String?) {
                line?.let { StunLogger.d(tag, "[EXEC-OUT] $it") }
            }
        }
        val errCallback = object : CallbackList<String>() {
            override fun onAddElement(line: String?) {
                line?.let { StunLogger.e(tag, "[EXEC-ERR] $it") }
            }
        }

        return try {
            val future = rootCommandExecutor.submit(Callable {
                Shell.cmd(cmd).to(outCallback, errCallback).exec()
            })
            future.get(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS).code
        } catch (e: TimeoutException) {
            StunLogger.e(tag, "Root execution timed out (${EXEC_TIMEOUT_SECONDS}s): $cmd")
            -1
        } catch (e: Exception) {
            StunLogger.e(tag, "Root execution failed: $cmd", e)
            -1
        }
    }
}
