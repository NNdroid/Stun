package app.fjj.stun.util

import android.content.Context
import app.fjj.stun.repo.StunLogger
import java.io.File
import java.io.FileOutputStream

/**
 * **资产落盘（assets → cacheDir）的唯一入口**，含"要不要重铺"的判定。
 *
 * 拆分理由：这些方法原先和 root 执行混在 `ExecUtils` 里（见 [RootShell]），而它们**根本不需要
 * root** —— 纯 `assets.open()` + 文件拷贝。名字混在一起会让读代码的人误判"拷规则库也需要 root"，
 * 也会让"部署判定"这个真正需要统一策略的逻辑散落在调用点。
 *
 * 判定策略见 [needsDeploy]：**只看副本自己的 mtime 与长度，不引入任何 prefs 标记**。
 */
object AssetDeployer {
    private const val TAG = "AssetDeployer"

    /**
     * bundled 资产是否需要（重新）落盘。
     *
     * 三个条件各自堵一个洞：
     * - `!exists()`：cacheDir 被系统清理、或用户"清除缓存"过。只比时间戳会把"文件已被清走"
     *   的机器判成已部署，等到真要执行时才发现文件不存在（tproxy 是直接读 cacheDir 就跑的）。
     * - `length() == 0L`：拷贝中途失败（磁盘满 / 进程被杀）会留下 0 字节文件，它 mtime 很新、
     *   容易骗过下面那条时间比较。
     * - `lastModified() < apkUpdateTimeMs`：APK 升级后资产可能变了。用副本自己的 mtime 跟
     *   APK 安装时间比，就不必再引入 prefs 标记 —— "上次部署依据"这个记忆直接存在文件上
     *   （落盘不会改 mtime）。
     *
     * ⚠️ 这里**刻意不碰 `last_update_time`**：那个字段是设置页/WebUI 的「上次更新」，
     * 语义是"最后一次从网络成功更新"。拿它当部署依据会让两件不相干的事绑在一起：它一为 0
     * （首次安装、或用户从没下载成功过）判定就恒真 ⇒ 每次冷启动重铺 12.9MB。
     *
     * 边界：`apkUpdateTimeMs` 读到 0 时退化为「只补缺失/空文件」；设备时钟被往回调时可能
     * 漏铺一次（下一次文件被清或时钟恢复正常即自愈）。
     */
    fun needsDeploy(file: File, apkUpdateTimeMs: Long): Boolean =
        !file.exists() || file.length() == 0L || file.lastModified() < apkUpdateTimeMs

    /** 按 [needsDeploy] 判定后落盘；不需要则什么都不做。 */
    fun deployIfNeeded(
        context: Context,
        assetPath: String,
        destName: String,
        apkUpdateTimeMs: Long,
        executable: Boolean = false,
    ): Boolean {
        if (!needsDeploy(File(context.cacheDir, destName), apkUpdateTimeMs)) return false
        return copyToCache(context, assetPath, destName, executable)
    }

    /** 从 `assets/bin/<abi>/` 解出当前 ABI 的可执行文件到 cacheDir 并 chmod。 */
    fun deployBinary(context: Context, name: String): Boolean {
        val abi = android.os.Build.SUPPORTED_ABIS[0]
        return copyToCache(context, "bin/$abi/$name", name, executable = true)
    }

    /** 从 `assets/scripts/` 解出脚本到 cacheDir 并 chmod。 */
    fun deployScript(context: Context, name: String): Boolean =
        copyToCache(context, "scripts/$name", name, executable = true)

    /**
     * 拷贝单个 asset 到 cacheDir。
     *
     * @param executable 落地后 `setExecutable(true)`。tproxy 的二进制与脚本是**直接执行**的
     *   （`nohup <cacheDir>/hev-socks5-tproxy`），没有这一步就是 "Permission denied"；
     *   规则库只是被 Go 侧 `os.Open` 读取，不需要可执行位。
     * @return 是否成功。失败只记日志不抛 —— 部署失败不该让冷启动崩掉，运行期还有存在性判定兜底。
     */
    fun copyToCache(
        context: Context,
        assetPath: String,
        destName: String,
        executable: Boolean = false,
    ): Boolean {
        val destFile = File(context.cacheDir, destName)
        return try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
            if (executable) destFile.setExecutable(true)
            true
        } catch (e: Exception) {
            StunLogger.e(TAG, "Failed to deploy asset $assetPath -> $destName", e)
            false
        }
    }
}
