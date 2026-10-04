package app.fjj.stun.util

import android.os.Build
import app.fjj.stun.repo.StunLogger

/**
 * **「让本进程别被系统掐掉」这一个意图的唯一实现。**
 *
 * 同一件事在仓库里曾经有 5 份实现，两种方言：
 *
 * | 位置 | 通道 | 做什么 |
 * |---|---|---|
 * | `MyVpnService.applyShizukuOptimizations` | Shizuku | deviceidle 白名单 + 待机桶 |
 * | `MyTransparentProxyService.applyShizukuOptimizations` | Shizuku | 同上（**逐字复制**） |
 * | `KeepAliveManager.applyPowerExemptions` | Shizuku | 同上（**第三份**） |
 * | `HomeFragment.applyShizukuKeepAlive` | Shizuku | 同上（**第四份**，还多包了一层 `isReady()`） |
 * | `MyTransparentProxyService.optimizeSystemForBackground` | root | deviceidle 白名单 + appops ×2 |
 *
 * 后果不是"多几行代码"，而是**改一处必然漏三处**：比如某天发现 appops 复位要用别的 op，
 * 改完 root 那份，Shizuku 那三份照旧；又比如 4 份各自包 `if (isReady())`，而底层
 * `addSelfToBatteryWhitelist` 内部还会再判一次 —— 同一次调用判三遍，且没人说得清哪一遍是权威。
 *
 * 现在按**通道**暴露两个入口，命令的构造只有这一份。新增一种豁免 = 在这里加一行，
 * 两条通道同时生效。
 *
 * ## 两条通道的能力差异（刻意保留，不是漏做）
 * - **Shizuku** 只能改"自己 shell 身份"能改的东西：deviceidle 白名单 + 待机桶，**没有 appops**。
 * - **root** 额外能改 appops（`RUN_IN_BACKGROUND` / `WAKE_LOCK`），但没必要动待机桶
 *   （appops 已经是更强的手段）。
 * 强行让两边跑同一份清单会引入未经验证的行为变化，所以只统一**构造**，不统一**集合**。
 */
object BackgroundExemptions {
    private const val TAG = "BackgroundExemptions"

    /**
     * Doze 白名单。
     *
     * 用 `cmd deviceidle` 而不是 `dumpsys deviceidle`：`cmd` 是 Android 官方的 setter
     * 形式（`dumpsys` 那个是历史遗留的 dump+set 混合体，7.0 起逐步被 `cmd` 取代）。
     * 两条通道现在跑的是**同一条**命令 —— 原先 root 走 dumpsys、Shizuku 走 cmd，
     * 同名开关在两种权限下行为可能不一致，这本身就是"两处实现"的代价。
     */
    internal fun deviceIdleWhitelist(packageName: String, grant: Boolean): Array<String> =
        arrayOf("cmd", "deviceidle", "whitelist", (if (grant) "+" else "-") + packageName)

    /**
     * 待机桶（App Standby Buckets，API 28+）。低版本没有这个机制，命令不存在会直接报错。
     */
    internal fun standbyBucketActive(packageName: String): Array<String> =
        arrayOf("am", "set-standby-bucket", packageName, "active")

    /** appops 模式。`mode` 取 `allow` / `default` / `ignore` —— 复位只能回 `default`。 */
    internal fun appops(packageName: String, op: String, mode: String): Array<String> =
        arrayOf("appops", "set", packageName, op, mode)

    /** appops 里本 App 实际用到、且需要成对开关的两个 op。 */
    private val APPOPS_OPS = listOf("RUN_IN_BACKGROUND", "WAKE_LOCK")

    /**
     * 经 Shizuku 加豁免。
     *
     * fire-and-forget：这是"重复断言无害"的加固动作（每次连上服务、每 15 分钟巡检都会再打一遍），
     * 拿不到退出码也不影响正确性，所以不阻塞调用方。**不要**拿它做需要确认的写入。
     */
    fun applyViaShizuku(packageName: String) {
        if (ShizukuUtils.state() != ShizukuState.READY) {
            StunLogger.d(TAG, "applyViaShizuku skipped: Shizuku not ready")
            return
        }
        ShizukuUtils.executeShellCommandAsync(deviceIdleWhitelist(packageName, grant = true))
        if (ShizukuUtils.isAtLeast(Build.VERSION_CODES.P)) {
            ShizukuUtils.executeShellCommandAsync(standbyBucketActive(packageName))
        }
    }

    /**
     * 经 root 加豁免（tproxy 模式专用 —— 它本来就要求 root）。
     *
     * 同步执行：调用点在服务启动协程里（已经是 IO），而这些命令要参与随后的失败判断。
     */
    fun applyViaRoot(packageName: String) {
        RootShell.exec(deviceIdleWhitelist(packageName, grant = true).joinToString(" "), TAG)
        for (op in APPOPS_OPS) {
            RootShell.exec(appops(packageName, op, "allow").joinToString(" "), TAG)
        }
    }

    /**
     * 经 root 撤销豁免。
     *
     * ⚠️ 必须成对存在：deviceidle 白名单是**跨重启留存**的，只加不撤等于永久留在系统后台白名单里
     * （卸载都不一定清得掉），既是耗电也是隐私。appops 复位用 `default`（交回 ROM 默认）
     * 而不是 `ignore` —— 后者等于替用户永久禁止后台运行。
     */
    fun revertViaRoot(packageName: String) {
        RootShell.exec(deviceIdleWhitelist(packageName, grant = false).joinToString(" "), TAG)
        for (op in APPOPS_OPS) {
            RootShell.exec(appops(packageName, op, "default").joinToString(" "), TAG)
        }
    }
}
