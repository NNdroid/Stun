package app.fjj.stun.qr

/**
 * 动画二维码的**接收端重组器**：把扫到的一串帧拼回原始载荷。
 *
 * 摄像头前的现实是不合作的：帧会丢（手抖/反光/帧率不匹配）、会重复（一帧停两拍被扫两次）、
 * 会乱序（先扫到后面的）、也会扫花（CRC 不符），而且用户随时可能把摄像头挪到另一个码上。
 * 所以这里不是一个"按顺序收"的累加器，而是一个**以会话（session）为单位的收集器**：
 *
 * - **乱序**：帧自带索引（度 1 帧的 seed 就是索引），填进对应槽位即可，到达顺序无关；
 * - **重复**：槽位已有内容且内容一致 ⇒ 静默忽略并计入 [State.duplicateFrames]（用来评估画面帧率是否偏快）；
 * - **丢帧**：发送端循环重播，下一轮自然补上；**更主要**的是靠 [AnimatedQrProtocol] 的修复帧
 *   （若干源分片的异或）—— 见下面的「BP 解码」；
 * - **扫花**：CRC 不符的帧在 [AnimatedQrProtocol.parse] 就被拦下，根本进不来；计数器照记，供 UI 提示。
 * - **换码**：来了个不同会话（或目标指纹不同）的帧 ⇒ 视为新传输，**整个状态重置**。
 *   这比"继续往里塞"安全得多：两次传输的帧混拼出来的结果一定是坏的，不如早点重来。
 *
 * ## BP 解码（Belief Propagation，这里只用它的"单未知即解"那一步）
 *
 * 一个度 d 的修复帧参与的是 d 个源分片的异或。收到它时看这 d 个里还有几个没收到：
 * - 0 个未知 ⇒ 这一帧已经完全冗余（全被别的帧覆盖了），丢掉；
 * - 1 个未知 ⇒ 把已知的那些异或掉，剩下的**就是**那个缺失的分片，当场解出；
 * - ≥2 个未知 ⇒ 现在还解不动，存进 [pending] 等将来。
 *
 * 每解出一个新分片，就回过头把 [pending] 里所有含它的条目消掉一项（异或 + 去掉该索引）。
 * 这一消可能让别的条目从"2 个未知"变成"1 个未知"，于是又能解出下一片 —— 形成级联。
 * 这就是为什么修复帧能把"最后一段死活收不齐"压平：缺第 k 片时，任何一个覆盖到 k 的修复帧
 * 都能把它救回来，而不是只能干等第 k 片自己播过来。
 *
 * 只做"单未知即解"这一步是有意的：完整解（高斯消元）要 O(N³) 且得存整个矩阵，
 * 而这里 d=6 时级联几乎总能解到底，解不动的场合重播一轮又会送来新的修复帧。
 *
 * ## 安全/健壮性边界
 *
 * 二维码是**外部输入**（谁都能在你面前放一个码）。所以：
 * - 收齐后必做整份 SHA-256 校验（比对每帧都携带的 `sha256Head`），不通过就整体作废 + 重置；
 * - 累计载荷有 [maxTotalBytes] 上限，防止一个"声明 9999 帧、每帧都很大"的码把内存吃光；
 *   [pending] 同理有 [MAX_PENDING] 上限，超出就淘汰最老的（重播还会再送来）；
 * - 所有失败都返回结果对象而不是抛异常 —— 扫码回调里抛异常会直接把相机预览搞停。
 *
 * 本类**零 Android 依赖**（不碰 Bitmap、不打日志），因此可以用裸 JUnit 完整覆盖。
 * 日志与 UI 文案由调用方负责。
 */
class AnimatedQrAssembler(
    private val maxTotalBytes: Int = DEFAULT_MAX_TOTAL_BYTES,
) {

    /** 会话标识三元组：谁在发、发多少、发的是什么。三者任一不同即视为新传输。 */
    private data class SessionKey(val sessionId: Int, val totalFrames: Int, val sha256Head: String)

    /** 一个暂时解不动的修复帧：[indices] 是**仍未收到**的参与索引，[data] 是异或结果。 */
    private class Pending(val indices: IntArray, val data: ByteArray)

    private var key: SessionKey? = null
    private var slots: Array<ByteArray?> = emptyArray()
    private var received = 0
    private var duplicateFrames = 0
    private var corruptFrames = 0
    private var receivedBytes = 0L
    private var lastFrameAtMs = 0L
    private var rounds = 0
    /** 原始载荷字节数，来自帧头；-1 表示尚未开始。收齐后按它裁掉分片补齐的 0。 */
    private var totalBytes = -1

    private var pending = ArrayDeque<Pending>()

    /** 当前传输状态；没有进行中的传输时 [State.isActive] 为 false。 */
    data class State(
        val sessionId: Int,
        val totalFrames: Int,
        val received: Int,
        val duplicateFrames: Int,
        val corruptFrames: Int,
        /** 相对上一帧的"轮次"：收满一轮 +1，可用来提示"已经循环播放 r 轮了"。 */
        val rounds: Int,
        val sha256Head: String,
    ) {
        val isActive: Boolean get() = totalFrames > 0
        val missing: Int get() = (totalFrames - received).coerceAtLeast(0)

        /** 0..100，直接给进度条用。 */
        val percent: Int get() = if (totalFrames <= 0) 0 else (received * 100 / totalFrames)
    }

    /** [offer] 的结果。 */
    sealed interface Offer {
        /** 不是本协议的帧：调用方应回退到原有的单张二维码导入路径。 */
        object NotAnimatedFrame : Offer

        /** 是本协议的帧但这一帧不可用（截断/字段非法/CRC 不符）。丢弃并等下一轮即可。 */
        data class Corrupt(val reason: AnimatedQrProtocol.FrameError) : Offer

        /** 帧已收下。`duplicate = true` 表示这一帧之前已经有了（画面里同一帧停留过久）。 */
        data class Accepted(
            val received: Int,
            val totalFrames: Int,
            val duplicate: Boolean,
            val newSession: Boolean,
        ) : Offer

        /**
         * 帧已收下，且它是由**修复帧解出来的**（而不是直接扫到的原始分片）。
         * UI 可以用它提示"正在用冗余帧补洞"。
         */
        data class Repaired(
            val received: Int,
            val totalFrames: Int,
            val solvedFromRepair: Int,
        ) : Offer

        /** 收齐且整份 SHA-256 校验通过。 */
        data class Completed(val payload: ByteArray, val state: State) : Offer

        /**
         * 收齐了但整体校验不通过，或超出了体积上限。**此时状态已重置**，
         * 用户可以重扫 —— 这比交出一份坏数据安全。
         */
        data class Rejected(val reason: RejectReason, val state: State) : Offer
    }

    enum class RejectReason {
        /** 拼接结果与帧头声明的 SHA-256 不一致：有帧被替换或混入了别的传输。 */
        CHECKSUM_MISMATCH,

        /** 累计载荷超过 [maxTotalBytes]。 */
        PAYLOAD_TOO_LARGE,
    }

    /**
     * 喂入一个扫到的字符串。
     *
     * 不会抛异常：扫码回调是相机线程的高频路径，异常会直接把预览打断。
     */
    fun offer(text: String): Offer {
        when (val parsed = AnimatedQrProtocol.parse(text)) {
            AnimatedQrProtocol.ParseResult.NotAnimatedFrame -> return Offer.NotAnimatedFrame

            is AnimatedQrProtocol.ParseResult.Corrupt -> {
                // 坏帧只记数，不影响已收集的部分 —— 下一轮会把这一帧重播一遍
                corruptFrames++
                return Offer.Corrupt(parsed.reason)
            }

            is AnimatedQrProtocol.ParseResult.Ok -> return accept(parsed.frame)
        }
    }

    private fun accept(frame: AnimatedQrProtocol.QrFrame): Offer {
        val incoming = SessionKey(frame.sessionId, frame.totalFrames, frame.sha256Head)
        val newSession = incoming != key
        if (newSession) startSession(incoming, frame.totalBytes)

        lastFrameAtMs = System.currentTimeMillis()

        // 声明的体积本身就得先过一遍上限，否则后面按它分配会直接被撑爆
        if (totalBytes > maxTotalBytes) return reject(RejectReason.PAYLOAD_TOO_LARGE)

        val indices = AnimatedQrProtocol.indicesFor(frame.seed, frame.degree, frame.totalFrames)

        if (frame.degree == 1) {
            val target = indices[0]
            when (store(target, frame.payload)) {
                StoreResult.STORED -> {
                    // ⚠️ 收到的虽然是原始帧，也必须去简化挂起的修复帧 —— 见 [propagate] 的注释。
                    // 少了这一步，之前存下来的修复帧会一直挂着不更新，永远等不到"只剩 1 个未知"的那一刻。
                    val cascaded = propagate(target)
                    if (cascaded < 0) return rejectOf(cascaded)
                    return progress(solvedFromRepair = cascaded, newSession = newSession)
                }
                StoreResult.DUPLICATE -> {
                    duplicateFrames++
                    return progress(duplicate = true, newSession = newSession)
                }
                // 同一槽位两次内容不一致 ⇒ 会话号撞车或码被改过。整体作废更安全。
                StoreResult.CONFLICT -> return reject(RejectReason.CHECKSUM_MISMATCH)
                StoreResult.TOO_LARGE -> return reject(RejectReason.PAYLOAD_TOO_LARGE)
            }
        }

        // 度 > 1：把已收到的那些异或掉，看还剩几个未知
        val unknown = ArrayList<Int>(indices.size)
        val buf = frame.payload.copyOf()
        for (i in indices) {
            val known = slots.getOrNull(i)
            if (known == null) {
                unknown.add(i)
            } else {
                xorInto(buf, known)
            }
        }
        return when (unknown.size) {
            0 -> {
                // 完全冗余 ⇒ 参与的每一片都已知，异或完应当是全 0。
                // 不为 0 说明帧之间对不上，整体作废而不是把可疑字节留在槽位里。
                if (!buf.all { it == 0.toByte() }) return reject(RejectReason.CHECKSUM_MISMATCH)
                duplicateFrames++
                progress(duplicate = true, newSession = newSession)
            }
            1 -> {
                val target = unknown[0]
                when (store(target, buf)) {
                    StoreResult.STORED -> {
                        val cascaded = propagate(target)
                        if (cascaded < 0) return rejectOf(cascaded)
                        progress(solvedFromRepair = 1 + cascaded, newSession = newSession)
                    }
                    StoreResult.DUPLICATE -> {
                        duplicateFrames++
                        progress(duplicate = true, newSession = newSession)
                    }
                    StoreResult.CONFLICT -> reject(RejectReason.CHECKSUM_MISMATCH)
                    StoreResult.TOO_LARGE -> reject(RejectReason.PAYLOAD_TOO_LARGE)
                }
            }
            else -> {
                pending.addLast(Pending(unknown.toIntArray(), buf))
                // 缓存不设限的话，一个"声明 9999 帧"的恶意码能把内存吃光
                while (pending.size > MAX_PENDING) pending.removeFirst()
                progress(newSession = newSession)
            }
        }
    }

    /** 统一出口：收齐就收尾，否则报进度。 */
    private fun progress(
        duplicate: Boolean = false,
        solvedFromRepair: Int = 0,
        newSession: Boolean = false,
    ): Offer {
        if (received == slots.size) {
            rounds++
            return finish()
        }
        if (duplicate) return Offer.Accepted(received, slots.size, duplicate = true, newSession)
        if (solvedFromRepair > 0) return Offer.Repaired(received, slots.size, solvedFromRepair)
        return Offer.Accepted(received, slots.size, duplicate = false, newSession)
    }

    /**
     * 传播：拿刚到手的 [startIndex] 号分片去简化所有挂起的修复帧；能解出的直接落盘，
     * 解出的那一片再继续传播，直到没有新进展。
     *
     * ⚠️ 这一步**必须在任何途径获得新分片后都跑一次**，包括收到的是普通原始帧。
     * 只在"又来了一个修复帧"时才简化的话，先前挂起的那些帧会一直保持着它们入队时的
     * 未知数，眼看着槽位被原始帧一个个填满也不更新，于是永远等不到"只剩 1 个未知"
     * 的那一刻 —— 修复帧就白掺了（实测收益会从约 2.6x 掉到 1.2x，而且从外部完全看不出来，
     * 只是"好像还是有点慢"）。
     *
     * @return 本次连带解出了几片；-1 表示内容冲突，-2 表示超出体积上限
     */
    private fun propagate(startIndex: Int): Int {
        var solved = 0
        val queue = ArrayDeque<Int>()
        queue.addLast(startIndex)
        while (queue.isNotEmpty()) {
            val idx = queue.removeFirst()
            val piece = slots[idx] ?: continue
            val simplified = ArrayList<Pending>(8)
            val it = pending.iterator()
            while (it.hasNext()) {
                val p = it.next()
                if (idx !in p.indices) continue
                it.remove()
                val nb = p.data.copyOf()
                xorInto(nb, piece)
                val nu = p.indices.filter { it != idx }.toIntArray()
                when {
                    nu.isEmpty() -> {
                        // 参与的每一片都已知了 ⇒ 异或完必须是全 0，否则帧之间对不上
                        if (!nb.all { x -> x == 0.toByte() }) return -1
                    }
                    nu.size == 1 -> when (store(nu[0], nb)) {
                        StoreResult.STORED -> {
                            solved++
                            queue.addLast(nu[0])
                        }
                        // 已被别的路径先解出来且内容一致，不是错误
                        StoreResult.DUPLICATE -> Unit
                        StoreResult.CONFLICT -> return -1
                        StoreResult.TOO_LARGE -> return -2
                    }
                    else -> simplified.add(Pending(nu, nb))
                }
            }
            // 简化过但仍解不动的放回队尾 —— 它们相对入队时已经"前进"了一步
            for (s in simplified) pending.addLast(s)
        }
        while (pending.size > MAX_PENDING) pending.removeFirst()
        return solved
    }

    /** [propagate] 的负数返回码 → 对应的失败结果。 */
    private fun rejectOf(code: Int): Offer = if (code == -2) {
        reject(RejectReason.PAYLOAD_TOO_LARGE)
    } else {
        reject(RejectReason.CHECKSUM_MISMATCH)
    }

    private enum class StoreResult { STORED, DUPLICATE, CONFLICT, TOO_LARGE }

    private fun store(index: Int, data: ByteArray): StoreResult {
        val existing = slots.getOrNull(index)
        if (existing != null) {
            return if (existing.contentEquals(data)) StoreResult.DUPLICATE else StoreResult.CONFLICT
        }
        val projected = receivedBytes + data.size
        if (projected > maxTotalBytes) return StoreResult.TOO_LARGE
        slots[index] = data
        received++
        receivedBytes = projected
        return StoreResult.STORED
    }

    private fun xorInto(target: ByteArray, other: ByteArray) {
        val n = minOf(target.size, other.size)
        for (i in 0 until n) target[i] = (target[i].toInt() xor other[i].toInt()).toByte()
    }

    private fun finish(): Offer {
        val state = snapshot()!!
        val body = java.io.ByteArrayOutputStream(receivedBytes.toInt().coerceAtLeast(0))
        for (slot in slots) {
            // 收齐了就不可能为 null；真出现 null 说明逻辑坏了，宁可当校验失败也不抛
            body.write(slot ?: return reject(RejectReason.CHECKSUM_MISMATCH))
        }
        // 分片是补齐到等长的（为了 XOR），真实长度只有帧头里的 totalBytes 知道
        val declared = if (totalBytes in 0..body.size()) totalBytes else body.size()
        val payload = body.toByteArray().copyOf(declared)
        if (AnimatedQrProtocol.sha256Head(payload) != state.sha256Head) {
            return reject(RejectReason.CHECKSUM_MISMATCH)
        }
        reset()
        return Offer.Completed(payload, state)
    }

    private fun startSession(incoming: SessionKey, declaredBytes: Int) {
        key = incoming
        slots = arrayOfNulls(incoming.totalFrames)
        received = 0
        receivedBytes = 0L
        duplicateFrames = 0
        corruptFrames = 0
        rounds = 0
        totalBytes = declaredBytes
        pending = ArrayDeque()
    }

    private fun reject(reason: RejectReason): Offer {
        val state = snapshot() ?: State(0, 0, 0, 0, 0, 0, "")
        reset()
        return Offer.Rejected(reason, state)
    }

    /** 当前状态；没有进行中的传输时返回 null。 */
    fun snapshot(): State? {
        val k = key ?: return null
        return State(
            sessionId = k.sessionId,
            totalFrames = k.totalFrames,
            received = received,
            duplicateFrames = duplicateFrames,
            corruptFrames = corruptFrames,
            rounds = rounds,
            sha256Head = k.sha256Head,
        )
    }

    /** 丢弃当前进度（用户取消、离开页面、或想重扫）。 */
    fun reset() {
        key = null
        slots = emptyArray()
        received = 0
        receivedBytes = 0L
        duplicateFrames = 0
        corruptFrames = 0
        rounds = 0
        lastFrameAtMs = 0L
        totalBytes = -1
        pending = ArrayDeque()
    }

    /**
     * 摄像头挪开一会儿就没人喂帧了。UI 可以周期性调用本方法，把"早就没动静"的传输清掉，
     * 免得用户重新对回来时进度条还停在某次作废的传输上。
     *
     * @return true 表示这次调用真的清掉了一个过期会话
     */
    fun resetIfStale(nowMs: Long, timeoutMs: Long): Boolean {
        if (key == null) return false
        if (lastFrameAtMs == 0L) return false
        if (nowMs - lastFrameAtMs < timeoutMs) return false
        reset()
        return true
    }

    companion object {
        /**
         * 累计载荷上限。放得很宽（真实载荷是分享用的加密节点 JSON，通常几十 KB 到几百 KB），
         * 只为挡住"声明 9999 帧、每帧都塞满"这种把内存吃光的码。
         */
        const val DEFAULT_MAX_TOTAL_BYTES = 8 * 1024 * 1024

        /**
         * 解不动的修复帧最多缓存多少条。每条是一帧的字节数（默认 800B），
         * 256 条约 200KB。超出淘汰最老的 —— 循环重播还会把新的一轮送过来，
         * 留着反而占内存。
         */
        const val MAX_PENDING = 256
    }
}
