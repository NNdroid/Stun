package app.fjj.stun.service

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VpnTeardown] 的**等待契约**。
 *
 * 这是「把 `onDestroy` 的无界阻塞改成有界」这件事唯一的真风险点：改早了统计丢，
 * 改晚了主线程照旧被按住。两种情况都不会崩、只会静默出错，所以必须钉死语义：
 *
 * ① `run` 返回时**统计一定已经跑完**（不丢统计，这是改动的底线）；
 * ② 原生清理**不被等待**（它不能拖慢收尾，且释放的是本进程内资源）；
 * ③ 统计卡死时 `run` 在超时附近返回（有界，不会退化成原来的无限等待）；
 * ④ 超时只截断"等待"，**不取消写库任务**（最坏丢的是一次计时，不是数据）；
 * ⑤ 两类任务抛异常（含 `Error`，例如 JNI 库不可用的 `UnsatisfiedLinkError`）都不回抛，
 *    各自走上报口 —— 收尾路径不能因为异常再炸一次。
 *
 * 全程用裸 JUnit：`VpnTeardown` 只依赖 coroutines + `System.nanoTime`，不需要 Robolectric，
 * 也不能挂 `RobolectricTestRunner`（`:core` 没配默认 SDK）。
 */
class VpnTeardownTest {

    private fun awaitTrue(timeoutMs: Long = 3_000, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    @Test
    fun `返回时统计已落库且原生清理不被等待`() {
        val statsDone = AtomicBoolean(false)
        val cleanupStarted = CountDownLatch(1)
        val cleanupGate = CountDownLatch(1)
        val cleanupDone = AtomicBoolean(false)

        val blocked = VpnTeardown.run(
            flushStats = { statsDone.set(true) },
            cleanupNative = {
                cleanupStarted.countDown()
                cleanupGate.await()
                cleanupDone.set(true)
            },
            timeoutMs = 2_000,
        )

        assertTrue("run 返回时统计必须已经跑完", statsDone.get())
        assertTrue("原生清理应该已启动", cleanupStarted.await(2, TimeUnit.SECONDS))
        assertFalse("原生清理不能被 run 等待（否则等于没改）", cleanupDone.get())
        // 统计是瞬时的，这里就不该出现"等待"：一旦打满超时（≥300ms）说明写库真的卡住了。
        assertTrue("瞬时统计时主线程应几乎不被占用，实测 ${blocked}ms", blocked < 300)

        cleanupGate.countDown()
        assertTrue("放行后原生清理应自行跑完", awaitTrue { cleanupDone.get() })
    }

    @Test
    fun `统计卡死时有界返回而不是无限等待`() {
        val gate = CompletableDeferred<Unit>()
        val blocked = VpnTeardown.run(
            flushStats = { gate.await() },
            cleanupNative = {},
            timeoutMs = 120,
        )
        assertTrue("应在超时附近返回，实测 ${blocked}ms", blocked in 80..1_000)
        gate.complete(Unit)
    }

    @Test
    fun `超时只截断等待不取消写库任务`() {
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val blocked = VpnTeardown.run(
            flushStats = {
                started.countDown()
                Thread.sleep(400)
                finished.countDown()
            },
            cleanupNative = {},
            timeoutMs = 80,
        )

        assertTrue("等待必须被截断，实测 ${blocked}ms", blocked < 400)
        assertTrue("写库应已开始", started.await(2, TimeUnit.SECONDS))
        assertTrue("超时不能掐掉正在进行的写库", finished.await(3, TimeUnit.SECONDS))
    }

    @Test
    fun `统计慢但在预算内时必须等它`() {
        val blocked = VpnTeardown.run(
            flushStats = { Thread.sleep(150) },
            cleanupNative = {},
            timeoutMs = 2_000,
        )
        assertTrue("统计耗时在预算内时不能被提前放弃，实测 ${blocked}ms", blocked >= 120)
        assertTrue("也不该等超过预算，实测 ${blocked}ms", blocked < 2_000)
    }

    @Test
    fun `两类任务抛 Throwable 都不回抛且各自上报`() {
        val reported = CopyOnWriteArrayList<String>()

        // 不期望抛异常：能走到断言就说明收尾没有把异常放出来。
        VpnTeardown.run(
            flushStats = { throw IllegalStateException("db boom") },
            cleanupNative = { throw UnsatisfiedLinkError("no jni") },
            timeoutMs = 2_000,
            onError = { what, _ -> reported += what },
        )

        assertTrue("异步的清理失败也要上报", awaitTrue { reported.size == 2 })
        assertEquals(
            "两类任务的上报口要能区分",
            setOf("stats flush", "native cleanup"),
            reported.toSet(),
        )
    }
}
