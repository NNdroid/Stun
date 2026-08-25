package app.fjj.stun.util

import android.content.Context
import android.util.Log
import app.fjj.stun.repo.StunLogger
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.*

object ExecUtils {
    private const val TAG = "ExecUtils"

    private val rootCommandExecutor = Executors.newCachedThreadPool()

    fun checkIsRootPermission(): Boolean {
        return Shell.getShell().isRoot
    }

    /**
     * 执行 root 命令，带有 90 秒超时保护
     * @return 进程退出码，超时或异常返回 -1
     */
    fun executeRootCommand(cmd: String, tag: String = TAG): Int {
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
                Shell.cmd(cmd)
                    .to(outCallback, errCallback)
                    .exec()
            })

            val result = future.get(90, TimeUnit.SECONDS)
            result.code

        } catch (e: TimeoutException) {
            StunLogger.e(tag, "Root execution timed out (90s): $cmd")
            -1
        } catch (e: Exception) {
            StunLogger.e(tag, "Root execution failed: $cmd", e)
            -1
        }
    }

    fun binaryDeploy(context: Context, name: String) {
        val abi = android.os.Build.SUPPORTED_ABIS[0]
        val assetPath = "bin/$abi/$name"
        val destFile = File(context.cacheDir, name)

        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            destFile.setExecutable(true)
        } catch (e: Exception) {
            StunLogger.e(TAG, "Failed to deploy binary: $name", e)
        }
    }

    fun scriptDeploy(context: Context, name: String) {
        val destFile = File(context.cacheDir, name)
        try {
            context.assets.open("scripts/$name").use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            destFile.setExecutable(true)
        } catch (e: Exception) {
            StunLogger.e(TAG, "Failed to deploy script: $name", e)
        }
    }

    fun copyAssetToCache(context: Context, assetPath: String, destName: String) {
        val destFile = File(context.cacheDir, destName)
        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            StunLogger.e(TAG, "Failed to copy asset $assetPath to cache", e)
        }
    }
}
