package app.fjj.stun.util

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import app.fjj.stun.repo.StunLogger
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

object ExecUtils {
    /**
     * Safe and non-blocking Root check
     */
    fun checkIsRootPermission(): Boolean {
        var process: Process? = null
        var os: DataOutputStream? = null
        try {
            // Attempt to request su
            process = Runtime.getRuntime().exec("su")
            os = DataOutputStream(process.outputStream)

            // Execute a lightweight command and exit to prevent hanging
            os.writeBytes("id\n")
            os.writeBytes("exit\n")
            os.flush()

            // [CRITICAL FIX]: Never use an indefinite process.waitFor()
            // Set a 2-second timeout. If su does not return within 2 seconds (e.g. no user response), assume no root.
            val isFinished = process.waitFor(2, TimeUnit.SECONDS)

            if (!isFinished) {
                // Timed out, forcibly killing the hanging su process
                process.destroy()
                return false
            }

            // exitValue of 0 means su command finished normally and has permissions
            return process.exitValue() == 0

        } catch (e: Exception) {
            // su binary not found or exception thrown, indicating no Root access
            return false
        } finally {
            try {
                os?.close()
            } catch (e: Exception) {
                // ignore
            }
            process?.destroy()
        }
    }
    fun executeRootCommand(cmd: String): Int {
        val tag = "[MySsh|${Thread.currentThread().name}]"
        StunLogger.d(tag, "[ROOT] $cmd")
        return try {
            val process = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()

            process.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { line -> StunLogger.d(tag, "[EXEC] $line") }
            }
            val exited = process.waitFor(8, TimeUnit.SECONDS)
            if (!exited) {
                StunLogger.e(tag, "Root execution timed out: $cmd")
                process.destroy()
                -1
            } else {
                process.exitValue()
            }
        } catch (e: Exception) {
            StunLogger.e(tag, "Root execution failed: $cmd", e)
            -1
        }
    }
    fun copyAssetToCache(context: Context, fileName: String): File? {
        val tag = "copyAssetToCache"
        val targetFile = File(context.cacheDir, fileName)
        return try {
            context.assets.open(fileName).use { input ->
                FileOutputStream(targetFile).use { output -> input.copyTo(output) }
            }
            targetFile.setExecutable(true, false)
            targetFile
        } catch (e: IOException) {
            StunLogger.e(tag, "Asset extraction failed: $fileName", e)
            null
        }
    }
    /**
     * Automatically deploy the architecture-specific binary file to the cache directory.
     * @param context Context
     * @param binName Binary file name, e.g., "tproxy_core"
     * @return The deployed File object, or null if deployment fails.
     */
    @SuppressLint("SetWorldReadable", "SetWorldWritable")
    fun binaryDeploy(context: Context, binName: String): File? {
        val tag = "binaryDeploy"

        // 1. Log the list of ABIs supported by the device
        val supportedAbis = Build.SUPPORTED_ABIS
        StunLogger.i(tag, "📱 Supported ABIs in order of priority: ${supportedAbis.joinToString(", ")}")

        val targetFile = File(context.cacheDir, binName)

        for (abi in supportedAbis) {
            val assetPath = "bin/$abi/$binName"
            StunLogger.d(tag, "🔍 Attempting to match path: assets/$assetPath")

            try {
                // Check if the binary for this ABI exists in assets
                context.assets.open(assetPath).use { inputStream ->
                    StunLogger.i(tag, "✅ Hit! Detected matching architecture: $abi, copying...")

                    // 2. Perform copy
                    FileOutputStream(targetFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }

                    // 3. Grant execution permissions
                    targetFile.setExecutable(true, false)
                    targetFile.setReadable(true, false)
                    targetFile.setWritable(true, false)

                    StunLogger.i(tag, "🚀 $binName ($abi) deployed successfully: ${targetFile.absolutePath}")
                    return targetFile
                }
            } catch (e: IOException) {
                // Specifically log whether it was skipped because file not found or other IO error
                StunLogger.w(tag, "⚠️ Skipping architecture $abi: assets/$assetPath does not exist or is unreadable, caused by: ${e.message}")
                continue
            }
        }

        StunLogger.e(tag, "❌ No suitable $binName binary found for the current device architecture")
        return null
    }

    /**
     * 部署脚本文件（如 .sh 文件）到缓存目录。
     * 脚本不需要区分 CPU 架构。
     * * @param context 上下文
     * @param scriptName 脚本在 assets 中的文件名（例如 "tproxy.sh"）
     * @return 部署成功返回 File，失败返回 null
     */
    @SuppressLint("SetWorldReadable", "SetWorldWritable", "SetWorldExecutable")
    fun scriptDeploy(context: Context, scriptName: String): File? {
        val tag = "scriptDeploy"

        val targetFile = File(context.cacheDir, scriptName)
        // 假设你的脚本直接放在 assets/ 根目录下。
        // 如果你放在了特定的文件夹里（比如 assets/scripts/），请改为 "scripts/$scriptName"
        val assetPath = "scripts/$scriptName"

        StunLogger.d(tag, "🔍 Attempting to deploy script: assets/$assetPath")

        try {
            context.assets.open(assetPath).use { inputStream ->
                StunLogger.i(tag, "✅ Found script: $scriptName, copying to cache...")

                // 1. 执行文件拷贝
                FileOutputStream(targetFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }

                // 2. 赋予读写和执行权限 (脚本文件必须有执行权限才能通过 root 运行)
                targetFile.setExecutable(true, false)
                targetFile.setReadable(true, false)
                targetFile.setWritable(true, false)

                StunLogger.i(tag, "🚀 Script $scriptName deployed successfully: ${targetFile.absolutePath}")
                return targetFile
            }
        } catch (e: IOException) {
            // 如果文件不存在或发生 IO 异常，直接抛出错误日志
            StunLogger.e(tag, "❌ Failed to deploy script $scriptName. Does assets/$assetPath exist? Caused by: ${e.message}")
            return null
        }
    }
}