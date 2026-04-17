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
     * 安全且防卡死的 Root 检查
     */
    fun checkIsRootPermission(): Boolean {
        var process: Process? = null
        var os: DataOutputStream? = null
        try {
            // 尝试申请 su
            process = Runtime.getRuntime().exec("su")
            os = DataOutputStream(process.outputStream)

            // 随便执行一个轻量命令并退出，防止挂起
            os.writeBytes("id\n")
            os.writeBytes("exit\n")
            os.flush()

            // 【关键修复】：绝对不能使用无限期的 process.waitFor()
            // 设定 2 秒超时。如果 2 秒内 su 没有返回（比如弹窗没点），直接当作没有 Root 权限
            val isFinished = process.waitFor(2, TimeUnit.SECONDS)

            if (!isFinished) {
                // 超时了，强行杀掉挂起的 su 进程
                process.destroy()
                return false
            }

            // exitValue 为 0 代表 su 命令正常结束且拥有权限
            return process.exitValue() == 0

        } catch (e: Exception) {
            // 找不到 su 二进制文件或抛出异常，说明没 Root
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
            process.waitFor()
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
     * 自動部署架構對應的二進位文件到 cache 目錄
     * @param context 上下文
     * @param binName 二進位文件名，例如 "tproxy_core"
     * @return 部署後的 File 對象，失敗則返回 null
     */
    @SuppressLint("SetWorldReadable", "SetWorldWritable")
    fun binaryDeploy(context: Context, binName: String): File? {
        val tag = "binaryDeploy"

        // 1. 打印设备真实支持的所有 ABI 列表
        val supportedAbis = Build.SUPPORTED_ABIS
        StunLogger.i(tag, "📱 设备支持的架构优先级: ${supportedAbis.joinToString(", ")}")

        val targetFile = File(context.cacheDir, binName)

        for (abi in supportedAbis) {
            val assetPath = "bin/$abi/$binName"
            StunLogger.d(tag, "🔍 正在尝试匹配路径: assets/$assetPath")

            try {
                // 檢查該 ABI 夾路徑是否存在於 assets 中
                context.assets.open(assetPath).use { inputStream ->
                    StunLogger.i(tag, "✅ 命中！侦测到匹配架构: $abi, 正在复制...")

                    // 2. 執行複製
                    FileOutputStream(targetFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }

                    // 3. 賦予可執行權限
                    targetFile.setExecutable(true, false)
                    targetFile.setReadable(true, false)
                    targetFile.setWritable(true, false)

                    StunLogger.i(tag, "🚀 $binName ($abi) 部署成功: ${targetFile.absolutePath}")
                    return targetFile
                }
            } catch (e: IOException) {
                // 明确打印是因为找不到文件跳过，还是因为其他 IO 异常跳过
                StunLogger.w(tag, "⚠️ 跳过架构 $abi: assets/$assetPath 不存在或无法读取，casue by: ${e.message}")
                continue
            }
        }

        StunLogger.e(tag, "❌ 找不到适合当前设备架构的 $binName 二进制文件")
        return null
    }
}