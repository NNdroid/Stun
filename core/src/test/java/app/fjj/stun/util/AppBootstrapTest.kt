package app.fjj.stun.util

import android.app.Application
import android.content.Context
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [AppBootstrap] 的**就绪门契约**。
 *
 * 这是「把资产部署移出主线程」这件事唯一的真风险点：从前 `Application.onCreate` 同步跑完
 * 部署，`MyTransparentProxyService` 直接读 cacheDir 里的二进制/脚本执行 —— 那条路径能成立，
 * 靠的是**顺序保证**，而不是任何检查。改成异步之后，一旦 await 在某条路径上永不返回，
 * 用户点连接就会永远停在 CONNECTING，而且日志里看不出原因。
 *
 * 所以这里不测部署本身（那是文件系统的事），只把门的语义钉死：
 * ① await 一定在有限时间内返回 —— **即使部署失败**，失败只该进日志，不该卡住门；
 * ② 没调 [AppBootstrap.start] 也能 await（服务被系统单独拉起、或调用方顺序不确定）；
 * ③ await 可重复调用，已就绪后立刻返回；
 * ④ [AppBootstrap.start] 幂等 **且不阻塞**（它是 onCreate 里调的，返回慢了等于没优化）；
 * ⑤ 第一个等待方被取消**不会连累**后来的等待方 —— 这正是用无父 Job 的
 *   `CompletableDeferred` 而不是 `async{}` 的原因，测试环境里极易被改回 `async{}` 而无人察觉。
 *
 * 注意：单测环境里 ABI 与资产未必对得上，`deployAssets` 很可能走「失败但完成」的分支 ——
 * 那恰好就是 ① 想覆盖的最坏情况。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppBootstrapTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `awaitAssets 在有限时间内返回 —— 即使部署失败`() = runBlocking {
        AppBootstrap.start(context)
        withTimeout(30_000) { AppBootstrap.awaitAssets(context) }
    }

    @Test
    fun `没调 start 也能直接 awaitAssets`() = runBlocking {
        withTimeout(30_000) { AppBootstrap.awaitAssets(context) }
    }

    @Test
    fun `已就绪后重复 await 立刻返回`() = runBlocking {
        withTimeout(30_000) { AppBootstrap.awaitAssets(context) }
        val began = System.nanoTime()
        withTimeout(2_000) { AppBootstrap.awaitAssets(context) }
        val elapsedMs = (System.nanoTime() - began) / 1_000_000
        assertTrue("已就绪后的 await 应当立刻返回，实测 ${elapsedMs}ms", elapsedMs < 500)
    }

    @Test
    fun `start 幂等且不阻塞调用线程`() {
        val began = System.nanoTime()
        AppBootstrap.start(context)
        AppBootstrap.start(context)
        AppBootstrap.start(context)
        val elapsedMs = (System.nanoTime() - began) / 1_000_000
        // 部署（首启要解压 ~12.8MB）如果在调用线程上跑，这里会直接是几百 ms 起。
        assertTrue("start 必须立刻返回（重活在 IO 上），实测 ${elapsedMs}ms", elapsedMs < 500)
    }

    @Test
    fun `首个等待方被取消不会连累后来的等待方`() = runBlocking {
        AppBootstrap.start(context)
        val first = launch { AppBootstrap.awaitAssets(context) }
        yield()
        first.cancelAndJoin()
        // 若部署任务被绑定到 first 的 Job 上（例如改成了 async{}），这里会直接
        // 抛 CancellationException 或一直等到超时。
        withTimeout(30_000) { AppBootstrap.awaitAssets(context) }
    }
}
