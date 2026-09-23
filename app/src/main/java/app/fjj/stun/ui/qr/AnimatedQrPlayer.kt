package app.fjj.stun.ui.qr

import android.graphics.Bitmap
import android.widget.ImageView
import app.fjj.stun.qr.AnimatedQrProtocol
import java.util.Random
import app.fjj.stun.qr.QrFrameRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 发送端的动画二维码播放器：把 [AnimatedQrProtocol.SplitSession] 的帧按固定节奏刷进一个 ImageView。
 *
 * ## 一轮有多少帧
 *
 * 播放序列比源分片数长：除了 N 个原始帧，还掺了约等量的**修复帧**（若干源分片的异或，
 * 见 [AnimatedQrProtocol] 类注释）。所以 `onFrame` 给的 `total` 是 [AnimatedQrProtocol.SplitSession.sequenceLength]
 * 而不是源分片数 —— 屏幕上"第 x/y 帧"里的 y 因此会比载荷的分片数大，这是对的，一轮确实要播这么多张。
 *
 * ## 为什么每轮洗一次牌
 *
 * 固定顺序 0,1,…,M-1 循环时，某一帧总是在**同一时刻**播出。那一刻如果恰好赶上反光、手抖
 * 或相机正在重新对焦，这个失败就会每一轮原样重演 —— 表现为"进度条卡在 97% 死活不动"。
 * 每轮换一个排列把这个周期性失败打散。收益在独立丢帧模型下量不到（模拟约 1.0x），
 * 但真实世界的失败恰恰是**时间相关**的，而洗牌的成本只有每轮 M 次交换。
 *
 * ## 为什么不缓存已渲染的位图
 *
 * 直觉做法是拿个 LRU 把渲染好的帧存起来，第二轮就不用重编码了。但这里**故意不缓存**：
 *
 * - 缓存要为"当前正显示的那一帧不能被回收"额外做一套引用记账（LRU 淘汰时可能正好淘汰掉屏幕上那张，
 *   下一次 draw 就崩在 recycled bitmap 上），复杂度换来的只是省几毫秒编码时间；
 * - 不缓存时任何时刻只有 1~2 张位图存活，内存**可预测**：360dp 方形帧 RGB_565 约 260KB，
 *   跟帧数、跟轮次都无关。缓存则要按"帧数 × 单帧大小"兜底，几十 KB 的载荷就可能顶到十几 MB。
 *
 * 实测一帧（约 1100 字符、version 24 附近）编码在毫秒量级，而 20 FPS 的预算是 50ms，编码根本不是瓶颈。
 * 修复帧多一次"取 d 个分片异或"，仍是同一量级。
 *
 * ## 节奏控制
 *
 * 每帧"编码 + 上屏"本身要花时间，若简单地 `delay(1000/fps)`，实际周期会变成"编码耗时 + 延迟"，
 * 帧率随设备性能漂移。所以这里按**单调时钟**算剩余时间：本帧从开始到上屏花了多久，
 * 就从目标周期里扣掉再 delay。设备再慢也只会退化成"能多快就多快"，不会越跑越慢。
 *
 * 本类所有方法都要求在**主线程**调用（它直接操作 ImageView）。
 */
internal class AnimatedQrPlayer(
    private val scope: CoroutineScope,
    private val imageView: ImageView,
    /** 每刷出一帧回调一次：`(帧序号, 总帧数, 已循环轮次)`，轮次从 1 开始。 */
    private val onFrame: (index: Int, total: Int, round: Int) -> Unit,
    /** 渲染失败或版本测量失败时回调一次（随后播放已停止）。 */
    private val onFailed: () -> Unit,
) {

    /**
     * 播放速度预设。
     *
     * 为什么不做成"随便拖"的连续值：真正决定成败的不是发送端刷多快，而是**接收端摄像头能不能
     * 在每帧停留时间内完成一次检测**。这个上限取决于机型、距离、光线，用户手里唯一能试的杠杆
     * 就是快慢，三档足够覆盖"能扫到"的区间，再细也没有可依据的判断标准。
     */
    enum class Speed(val fps: Int) {
        /** 6 FPS。帧停留约 167ms，给廉价机身/远距离用。 */
        SLOW(6),

        /** 12 FPS。默认档，多数机型的最优平衡点。 */
        MEDIUM(12),

        /** 20 FPS。旗舰机近距离用，吞吐最高但容易丢帧。 */
        FAST(20),
    }

    private var session: AnimatedQrProtocol.SplitSession? = null
    private var version = 0
    private var sizePx = 0
    private var job: Job? = null
    private var displayed: Bitmap? = null
    private var logo: Bitmap? = null

    var speed: Speed = Speed.MEDIUM

    @Volatile
    var isPaused: Boolean = false
        private set

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * 开始播放。会先停掉上一次播放。
     *
     * "测整轮统一版本"这一步是一次完整编码，放到后台线程做，避免在弹窗刚弹出时卡一下主线程。
     *
     * @param logo 中心要盖的应用图标（已光栅化，见 [QrLogoBadge.rasterize]）；null 就是不带徽标。
     */
    fun start(session: AnimatedQrProtocol.SplitSession, sizePx: Int, logo: Bitmap? = null) {
        stop()
        this.session = session
        this.sizePx = sizePx
        this.logo = logo
        isPaused = false
        job = scope.launch {
            // 用最长的一帧（第 0 帧）量版本 —— 它决定了整轮的统一模块数，见 QrFrameRenderer 类注释
            val measured = withContext(Dispatchers.Default) {
                runCatching { QrFrameRenderer.versionFor(session.frameAt(0)) }.getOrDefault(0)
            }
            if (measured <= 0) {
                onFailed()
                return@launch
            }
            version = measured
            playLoop(session)
        }
    }

    fun setPaused(paused: Boolean) {
        isPaused = paused
    }

    /** 停止播放并释放当前位图。 */
    fun stop() {
        job?.cancel()
        job = null
        session = null
        imageView.setImageDrawable(null)
        displayed?.recycle()
        displayed = null
    }

    private suspend fun playLoop(session: AnimatedQrProtocol.SplitSession) {
        val total = session.sequenceLength
        val order = IntArray(total)
        var shuffledForRound = -1
        var cursor = 0
        var round = 1
        // 用协程自身的存活状态判断，而不是 job 字段：lifecycleScope 是 Main.immediate，
        // launch 的协程体可能在 job 字段被赋值之前就开始同步执行，读字段会读到 null。
        while (currentCoroutineContext().isActive) {
            if (isPaused) {
                delay(PAUSE_POLL_MS)
                continue
            }
            if (shuffledForRound != round) {
                for (i in 0 until total) order[i] = i
                val rnd = Random(session.sessionId.toLong() xor (round.toLong() * 0x9E3779B9L))
                for (i in total - 1 downTo 1) {
                    val j = rnd.nextInt(i + 1)
                    val t = order[i]
                    order[i] = order[j]
                    order[j] = t
                }
                shuffledForRound = round
            }
            val index = order[cursor]
            val startedAt = System.nanoTime()

            val bitmap = withContext(Dispatchers.Default) {
                QrFrameRenderer.render(session.frameAt(index), sizePx, version)?.also {
                    // 徽标必须**每帧**重画：每张位图都是新编出来的，画在上帧那张上会丢。
                    // 画在后台线程是安全的 —— logo 是 [QrLogoBadge.rasterize] 出来的只读位图。
                    QrLogoBadge.drawInto(it, logo)
                }
            }
            // 渲染期间可能已经被 stop()（切 PIN、关弹窗）：此时**绝不能**再往 ImageView 上写，
            // 否则上一轮的停帧会盖掉新一轮的画面。丢掉这张位图直接退出。
            if (!currentCoroutineContext().isActive) {
                bitmap?.recycle()
                return
            }
            if (bitmap == null) {
                onFailed()
                return
            }

            val previous = displayed
            imageView.setImageBitmap(bitmap)
            displayed = bitmap
            // 换上新图之后再回收旧的：此刻它已经不在任何 Drawable 里，主线程上回收是安全的
            previous?.recycle()

            onFrame(cursor, total, round)

            cursor++
            if (cursor >= total) {
                cursor = 0
                round++
            }

            val spentMs = (System.nanoTime() - startedAt) / 1_000_000
            val remaining = 1_000L / speed.fps - spentMs
            // 设备跟不上就干脆不给延迟，让它全速跑（总比人为拖慢更早扫完）
            if (remaining > 0) delay(remaining)
        }
    }

    private companion object {
        /** 暂停时的轮询间隔。暂停不需要响应速度，粗一点省电。 */
        const val PAUSE_POLL_MS = 80L
    }
}
