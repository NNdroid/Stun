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

    /** 流身份标识，同时用在消息前缀和 stderr 判定里 —— 别两处各写一份字面量。 */
    private const val STREAM_EXEC_OUT = "EXEC-OUT"
    private const val STREAM_EXEC_ERR = "EXEC-ERR"

    /**
     * 脚本自己声明的日志级别前缀。级别是**流身份判断不了的**：Warn 和 Error 都走
     * stderr、Info 和 Debug 走 stdout，而 iptables 这类工具会往两个流里都吐不带前缀的
     * 行，所以只能信脚本自己写在行首的 `[Level]:`。行首可选带一个
     * `YYYY-MM-DD HH:MM:SS ` 时间戳（LOG_TIMESTAMP=1 时）。
     */
    private val SCRIPT_LOG_PREFIX = Regex("""^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} )?\[(Debug|Info|Warn|Error)\]:\s*""")

    /**
     * 记录一行 shell 输出：优先按脚本声明的级别，没有可识别前缀时退回
     * 「stdout=debug / stderr=error」。`stream` 保留在原消息里，仍能看出这行来自哪个 fd。
     */
    private fun logShellLine(stream: String, tag: String, line: String) {
        val msg = "[$stream] $line"
        when (SCRIPT_LOG_PREFIX.find(line)?.groupValues?.get(2)) {
            "Debug" -> StunLogger.d(tag, msg)
            "Info" -> StunLogger.i(tag, msg)
            "Warn" -> StunLogger.w(tag, msg)
            "Error" -> StunLogger.e(tag, msg)
            null -> if (stream == STREAM_EXEC_ERR) StunLogger.e(tag, msg) else StunLogger.d(tag, msg)
        }
    }

    /**
     * 执行一条 root 命令。
     *
     * @return 进程退出码；超时（[EXEC_TIMEOUT_SECONDS]）或抛异常时返回 -1。
     *   非零退出码会打一条 WARN（脚本静默失败时日志里至少留个痕迹）。
     *   ⚠️ 调用方**不能**把 0 当成"成功"的唯一证据 —— 拼错的命令在某些 su 实现下也返回 0，
     *   真正需要确认的动作应在命令尾部自带校验（例如 `... ; test -f <path>`）。
     */
    fun exec(cmd: String, tag: String = TAG): Int {
        val outCallback = object : CallbackList<String>() {
            override fun onAddElement(line: String?) {
                line?.let { logShellLine(STREAM_EXEC_OUT, tag, it) }
            }
        }
        val errCallback = object : CallbackList<String>() {
            override fun onAddElement(line: String?) {
                line?.let { logShellLine(STREAM_EXEC_ERR, tag, it) }
            }
        }

        return try {
            val future = rootCommandExecutor.submit(Callable {
                Shell.cmd(cmd).to(outCallback, errCallback).exec()
            })
            val code = future.get(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS).code
            if (code != 0) {
                StunLogger.w(tag, "Root command exited $code: $cmd")
            }
            code
        } catch (e: TimeoutException) {
            StunLogger.e(tag, "Root execution timed out (${EXEC_TIMEOUT_SECONDS}s): $cmd")
            -1
        } catch (e: Exception) {
            StunLogger.e(tag, "Root execution failed: $cmd", e)
            -1
        }
    }
}
