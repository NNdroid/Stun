package app.fjj.stun.util

import android.content.Context
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * 启动期的「慢活儿」异步化 —— 从前它们全在 `Application.onCreate` 的主线程上，
 * 直接吃进冷启动：
 *
 * | 活儿 | 成本 | 频率 |
 * |---|---|---|
 * | 解压并落盘 `geoip.dat` + `geosite.dat`（bundled 资产 ~12.9MB） | 几百 ms ~ 1s+ | 首次安装 / APK 升级 / cacheDir 被系统清理 |
 * | 解压 `hev-socks5-tproxy` + 两个脚本（~900KB）+ 两次 chmod | 十几 ~ 几十 ms | 同上 |
 *
 * ## 为什么不能只把 `initAssets` 挪到 IO 就完事
 * TProxy 模式的三件套是 `MyTransparentProxyService` **直接从 `cacheDir` 读了就执行**的
 * （`nohup <cacheDir>/hev-socks5-tproxy` / `<cacheDir>/tproxy.sh`），既没有存在性检查、
 * 也没有兜底重部署。而这两条路径以前能工作，只是因为 `Application.onCreate` 一定先跑完 ——
 * 这个隐含的顺序保证一旦被异步化打破，用户点连接就会拿到「文件不存在」。
 * 所以异步化的同时必须给出一个**显式的就绪门**：[awaitAssets]。
 *
 * ## 部署判定：只看文件自己的 mtime，不看 `last_update_time`
 * 规则库原先用 `last_update_time` 判「要不要重铺」，而那个字段是**用户可见的「上次更新」**
 * （设置页把 0 显示成 "Never"，WebUI 也读它），只在**在线下载成功**后才写。于是它一旦为 0
 * （首次安装、或从来没下载成功过），旧判定 `lastUpdate <= 0` 就恒真 ⇒ **每次冷启动重铺 12.9MB**。
 * 现在规则库和 TProxy 三件套共用 [needsDeploy]：**上次部署依据存在文件自己的 mtime 上**，
 * 不需要任何额外状态，也不动「上次更新」的展示语义。
 *
 * ## 为什么 `awaitAssets` 用独立的 `CompletableDeferred` 而不是 `async{}`
 * 它刻意不进任何 CoroutineScope：`Deferred.await()` 在等待方被取消时会连带取消
 * 「作为其子 Job」的 deferred，而 VPN 服务的 scope 是会随服务销毁被 cancel 的。
 * 一个无父 Job 的 `CompletableDeferred` 只让**等待方**收到 CancellationException，
 * 部署任务本身照跑完，后到的等待方仍能正常拿到结果。
 * 反过来，部署失败时也必须 `complete()`：卡住 await 只会让 VPN 永远起不来，
 * 失败详情已经进日志，运行期还有 [needsDeploy] 的存在性判定兜底。
 */
object AppBootstrap {

    private const val TAG = "AppBootstrap"

    private const val BIN_TPROXY = "hev-socks5-tproxy"
    private const val SCRIPT_TPROXY = "tproxy.sh"
    private const val SCRIPT_WATCHDOG = "watchdog.sh"

    private const val ASSET_GEOIP = "rules-dat/geoip.dat"
    private const val ASSET_GEOSITE = "rules-dat/geosite.dat"
    private const val FILE_GEOIP = "geoip.dat"
    private const val FILE_GEOSITE = "geosite.dat"

    private val lock = Any()

    /** 资产部署任务。null = 还没启动过。 */
    private var assetsJob: CompletableDeferred<Unit>? = null

    private var keystoreWarmUpStarted = false

    /**
     * 幂等启动资产部署（`Application.onCreate` 调）。重复调用只启动一次。
     * 不阻塞：立刻返回，部署在 IO 上跑。
     *
     * 部署跑完（**无论成功失败**）会顺手做一次规则库更新检查，见 [start] 里的注释。
     */
    fun start(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (assetsJob != null) return
            val deferred = CompletableDeferred<Unit>()
            assetsJob = deferred
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    deployAssets(app)
                } catch (t: Throwable) {
                    StunLogger.e(TAG, "Asset deploy failed (file presence will be re-checked before VPN start)", t)
                } finally {
                    // 规则库更新检查必须排在部署**之后**：它拿 `File.exists()` 判「要不要立刻
                    // 下载一份」，部署还在异步进行时去问，答案必然是「缺」⇒ 每次冷启动都白排一个
                    // 13MB 的下载，还会跟正在进行的资产拷贝抢同一个文件名。
                    // 放在 finally 里是为了部署失败时也能走：那时文件确实缺，正是该下载的场景。
                    try {
                        SettingsManager.checkAndUpdateGeoData(app)
                    } catch (t: Throwable) {
                        StunLogger.e(TAG, "Rule-set update check failed", t)
                    }
                    deferred.complete(Unit)
                }
            }
        }
    }

    /**
     * 等资产就绪。VPN 服务在**碰任何 cacheDir 下资产之前**必须调它。
     * 没 [start] 过（例如服务被系统单独拉起、或单测）就就地启动，行为一致。
     */
    suspend fun awaitAssets(context: Context) {
        val job = synchronized(lock) { assetsJob } ?: run {
            start(context)
            synchronized(lock) { assetsJob }
        }
        job?.await()
    }

    /**
     * 幂等地在 IO 上预热 Android Keystore。
     *
     * 单独一个任务、**不给它就绪门**：Keystore 的正确性由 `KeystoreUtils` 自己保证
     * （`encrypt`/`decrypt` 会 `ensureInitialized` 自愈），所以这里只是把「第一次
     * 生成/读取主密钥」这段本来压在主线程上的开销提前挪走，不需要任何人等它。
     */
    fun warmUpKeystore(context: Context) {
        val app = context.applicationContext
        // 先**同步**登记 context（两次字段赋值）：真正的初始化在 IO 上，但 KeystoreUtils
        // 的自愈重试要靠 appContext 已就位 —— 否则「预热还没跑完就有代码来加解密」会误报未初始化。
        KeystoreUtils.rememberContext(app)
        synchronized(lock) {
            if (keystoreWarmUpStarted) return
            keystoreWarmUpStarted = true
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                KeystoreUtils.init(app)
                // 从未设置过备份 PIN 时生成随机 6 位默认值（见 SettingsManager 注释）。
                // 稳态下它只是一次 prefs 读 + 早返回；只有首启会真的写一次。
                SettingsManager.ensureWebDavPin(app)
            } catch (t: Throwable) {
                // 从前这里是 `catch (_: Throwable) {}` —— init 失败会让 aead 恒为 null，
                // 之后 22 处 encrypt/decrypt 全抛 IllegalStateException 且不留任何痕迹。
                StunLogger.e(TAG, "Keystore warm-up failed (will retry in place on first crypto use)", t)
            }
        }
    }

    private fun deployAssets(context: Context) {
        val apkUpdateTimeMs = readApkUpdateTimeMs(context)

        deployTproxyRuntime(context, apkUpdateTimeMs)
        deployRuleSet(context, apkUpdateTimeMs)
    }

    private fun readApkUpdateTimeMs(context: Context): Long = try {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    } catch (e: Exception) {
        // 读不到就当 0：[needsDeploy] 退化为「只补缺失文件」，比「每次都重铺」保守。
        StunLogger.w(TAG, "Failed to read APK install time, falling back to missing-file-only mode: ${e.message}")
        0L
    }

    /**
     * bundled 规则库（geosite/geoip）。
     *
     * ⚠️ 这里**刻意不碰 `last_update_time`**。那个字段是设置页/WebUI 的「上次更新」，
     * 语义是「最后一次从网络成功更新」，由 `SettingsManager.updateGeoDataSync` 在下载成功时写。
     * 拿它当部署依据会把两件不相干的事绑在一起：它一为 0（首次安装、或用户从没下载成功过），
     * `lastUpdate <= 0` 就恒真 ⇒ 每次冷启动重铺 12.9MB。
     * 换成文件自己的 mtime 后，这个判定与展示语义彻底解耦。
     */
    private fun deployRuleSet(context: Context, apkUpdateTimeMs: Long) {
        val geoip = File(context.cacheDir, FILE_GEOIP)
        val geosite = File(context.cacheDir, FILE_GEOSITE)
        if (!needsDeploy(geoip, apkUpdateTimeMs) && !needsDeploy(geosite, apkUpdateTimeMs)) return

        StunLogger.i(TAG, "Rule-set needs redeploy (missing/empty file or APK upgraded), extracting bundled assets...")
        if (needsDeploy(geoip, apkUpdateTimeMs)) {
            ExecUtils.copyAssetToCache(context, ASSET_GEOIP, FILE_GEOIP)
        }
        if (needsDeploy(geosite, apkUpdateTimeMs)) {
            ExecUtils.copyAssetToCache(context, ASSET_GEOSITE, FILE_GEOSITE)
        }
    }

    /**
     * TProxy 运行期三件套（二进制 + 两个脚本）。
     *
     * 原实现**每次启动都无条件重拷**，且只 `setExecutable(true)`。这里与规则库共用 [needsDeploy]。
     */
    private fun deployTproxyRuntime(context: Context, apkUpdateTimeMs: Long) {
        if (needsDeploy(File(context.cacheDir, BIN_TPROXY), apkUpdateTimeMs)) {
            ExecUtils.binaryDeploy(context, BIN_TPROXY)
        }
        if (needsDeploy(File(context.cacheDir, SCRIPT_TPROXY), apkUpdateTimeMs)) {
            ExecUtils.scriptDeploy(context, SCRIPT_TPROXY)
        }
        if (needsDeploy(File(context.cacheDir, SCRIPT_WATCHDOG), apkUpdateTimeMs)) {
            ExecUtils.scriptDeploy(context, SCRIPT_WATCHDOG)
        }
    }

    /**
     * 「这个 cacheDir 副本要不要重新铺」——**唯一**的部署判定，规则库与 TProxy 三件套共用。
     *
     * 三个条件分别对应三种失效：
     * - `!exists()`：`cacheDir` 会被系统在存储紧张时清理，而任何时间戳标记都不会被一起清掉，
     *   只比时间戳会把「文件已被清走」的机器判成已部署，连接时才炸；
     * - `length() == 0L`：拷贝中途失败（磁盘满 / 进程被杀）会留下一个 0 字节文件，
     *   它 mtime 很新、容易骗过下面那条时间比较；
     * - `lastModified() < apkUpdateTimeMs`：APK 升级后资产可能变了，需要重铺。
     *   用副本自己的 mtime 跟 APK 安装时间比，就**不必再引入任何 prefs 标记**——
     *   「上次部署依据」这个记忆直接存在文件上（`ExecUtils` 落盘时不会动 mtime）。
     *
     * 边界：`apkUpdateTimeMs` 读到 0 时退化为「只补缺失/空文件」；设备时钟被往回调时可能
     * 漏铺一次（下一次文件被清或时钟恢复正常即自愈）。
     */
    internal fun needsDeploy(file: File, apkUpdateTimeMs: Long): Boolean =
        !file.exists() || file.length() == 0L || file.lastModified() < apkUpdateTimeMs
}
