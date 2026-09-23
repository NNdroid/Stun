package app.fjj.stun.qr

import java.security.MessageDigest
import java.util.Base64
import java.util.Random
import java.util.zip.CRC32

/**
 * 动画二维码（多帧二维码流）的**帧协议**：把一份任意大小的载荷切成 N 个二维码连续播放，
 * 接收端边扫边拼。用来突破单张二维码的字节容量上限（见 [SINGLE_QR_MAX_BYTES]）。
 *
 * ## 为什么帧内容必须是纯可打印 ASCII
 *
 * 这是本协议最关键的一条约束，**不是审美问题**。zxing 解码 byte 段时，若码里没有 ECI，
 * 会调用 `StringUtils.guessEncoding()` 去**猜**字符集（UTF-8 → Shift_JIS → ISO-8859-1）。
 * 一段随机二进制很容易命中 Shift_JIS 的判定条件，于是同一张码在不同解码实现下
 * 解出**不同的字符串**，而且是静默的。所以：载荷统一走 base64url，帧头全部是定长
 * 十六进制/十进制 —— 整帧字符集恒为 `[A-Za-z0-9-_]`，怎么猜都不会错。
 *
 * 附带好处：帧可打印 ⇒ 能直接进日志、能人工肉眼核对、能被单元测试逐字符断言。
 *
 * ## 帧格式（定长头 + 变长体，按偏移解析，不需要分隔符）
 *
 * ```
 * 偏移  长度  内容
 * 0     4     MAGIC "SQR2"
 * 4     8     sessionId       8 位十六进制（31 位随机）
 * 12    4     totalFrames     4 位十进制，源分片数 N
 * 16    8     totalBytes      8 位十六进制，原始载荷字节数（用来裁掉分片补齐的 0）
 * 24    1     degree          1 位十进制，本帧由几个源分片异或而成（1..9）
 * 25    8     seed            8 位十六进制，度 1 时即源索引；度 >1 时由它派生索引表
 * 33    8     crc32           8 位十六进制，覆盖**本帧载荷字节**
 * 41    16    sha256Head      16 位十六进制，= 整份载荷 SHA-256 的前 8 字节
 * 57    ...   payload         base64url（无填充）编码的本帧字节
 * ```
 *
 * 定长字段 ⇒ 解析就是几个 `substring`，没有分隔符转义、没有歧义；[HEADER_LEN] 固定 57。
 *
 * ## 为什么要有修复帧（XOR 冗余包）—— 这段是 v2 相对 v1 的全部动机
 *
 * v1 只播 N 个**原始分片**，丢帧全靠"下一轮重播"补。这在数学上是**赠券收集问题**：
 * 收集到第 k 个不同分片需要 (H_N − H_{N−k})/p 轮，于是**最后那几帧要吃掉一半的总时间**
 * （H 是调和数）。用户感受到的就是"前面唰唰涨，最后一段死活收不齐"。
 *
 * v2 在序列里掺入 degree > 1 的**修复帧**：若干个源分片按位异或。接收端收到一个修复帧时，
 * 只要它参与的源分片里"只剩一个还没收到"，就能当场把那一片解出来 —— 于是**任何一个**
 * 覆盖到缺失分片的修复帧都能救回它，而不再需要死等那一片自己播过来。
 *
 * 蒙特卡洛实测（N=52、12FPS、每帧被扫中概率 p）：
 * ```
 *                p=0.3            p=0.5            p=0.7            p=0.9
 * v1 纯重播   55.5s(最差182)   28.6s(最差69)    16.7s(最差41)     9.4s(最差22)
 * 度6 比例1   20.0s(最差 32)   10.9s(最差20)     7.0s(最差14)     5.5s(最差13)
 * ```
 * 长尾（最差情况）改善得比均值还多 —— 因为它治的正是长尾本身。
 *
 * ### 度必须 ≥ 3，度 2 是净亏
 *
 * 这是模拟里最反直觉的一条：掺修复帧会把轮长从 N 拉到 N(1+ρ)，而"能救回某个缺失分片"的
 * 机会只按 (1+ρ·d) 增长。d=2 时两者几乎抵消，实测**比不加还慢**（0.9~1.04x）。
 * 只有 d ≥ 3 才开始真正赚，d=6 附近最优（d=8 又略回落：未知数太多，得等更久才能解）。
 *
 * ### 为什么度/索引用 seed 派生而不是直接写进帧头
 *
 * 一个度 6 的帧要带 6 个索引，直写至少 24 个字符，头会胖一倍、把 QR 版本顶上去。
 * 这里只带一个 8 位 seed，收发双方用同一条 [indicesFor] 从 seed 复现索引表
 * （`java.util.Random` 的序列是规范化的、跨实现一致），代价是每帧多算 6 个随机数。
 *
 * ## 为什么分片要补齐成等长
 *
 * XOR 要求操作数等长。载荷末尾那一片天然比别人短，所以这里把**所有**分片都补 0 到
 * [DEFAULT_CHUNK_SIZE]，原始长度另存 `totalBytes`，接收端拼完再裁掉尾部。
 * 否则接收端算不出最后一片的真实长度（它恰恰是最容易缺、最需要靠修复帧解出来的那片）。
 *
 * ## 为什么 CRC32 和 SHA-256 都要
 *
 * 两者管的是**不同的错**，缺一不可：
 * - `crc32` 逐帧，抓"这一帧扫花了"。QR 自身有 Reed-Solomon，但屏幕反光/摩尔纹造成的
 *   误码偶尔会落在一个"合法但错误"的码字上；逐帧校验让坏帧**当场被丢弃并重扫**，
 *   而不是把脏字节悄悄拼进结果、直到最后才发现整体不对。
 * - `sha256Head` 覆盖整份载荷，抓"帧与帧之间不匹配"：混进了另一次传输的帧（session 撞车）、
 *   某帧被替换、拼接顺序错乱。它还有个副作用好处 —— 接收端不必等收齐才知道对不对，
 *   任何一个帧都能提前看到"目标指纹"。
 *
 * ## 为什么默认分片 800 字节
 *
 * 分片大小是**每帧数据量**与**可扫性**的权衡，不是越大越好。实测（弹窗 360dp 宽）：
 * ```
 * chunk   帧字符  version  模块数  dp/模块
 *  800     1111     24      113     3.19   ← 当前取值
 * 1200     1644     30      137     2.63
 * 1600     2178     34      153     2.35
 * 2100     2844     40      177     2.03
 * ```
 * 每模块低于约 2.5dp 后，摄像头能不能稳定分辨就要看机型和距离了。800 换来的 3.19dp
 * 留了足够余量 —— 宁可多播几帧（靠修复帧把长尾压平），也不要每帧都贴着可扫性下限。
 *
 * ## 与单张二维码的关系
 *
 * 本协议**只是一条传输层**：载荷仍然是既有的 `ShareCryptoUtils.encrypt()` 产物（密文 ASCII 串）。
 * 因此能塞进单张码时继续走原来的静态二维码，旧版本照常互认；只有超限才切到动画模式。
 */
object AnimatedQrProtocol {

    /** 帧前缀。同时作为"这是动画帧"的快速判据。v2 起带修复帧（v1 是 "SQR1"）。 */
    const val MAGIC = "SQR2"

    private const val SESSION_HEX_LEN = 8
    private const val COUNT_DEC_LEN = 4
    private const val TOTAL_BYTES_HEX_LEN = 8
    private const val DEGREE_DEC_LEN = 1
    private const val SEED_HEX_LEN = 8
    private const val CRC_HEX_LEN = 8
    private const val SHA_HEX_LEN = 16

    /** 头部总长度；解析时按此偏移切分。 */
    const val HEADER_LEN = 4 + SESSION_HEX_LEN + COUNT_DEC_LEN + TOTAL_BYTES_HEX_LEN +
        DEGREE_DEC_LEN + SEED_HEX_LEN + CRC_HEX_LEN + SHA_HEX_LEN

    /** 单帧默认载荷字节数。理由见类注释「为什么默认分片 800 字节」。 */
    const val DEFAULT_CHUNK_SIZE = 800

    /** 帧数上限（受 4 位十进制字段限制）。800B × 9999 ≈ 7.8MB，远超实际需要。 */
    const val MAX_FRAMES = 9999

    /**
     * 修复帧的度（参与异或的源分片数）。6 是蒙特卡洛实测的最优值，理由见类注释「度必须 ≥ 3」。
     * 受 1 位十进制字段限制，上限为 9。
     */
    const val REPAIR_DEGREE = 6

    /** 修复帧条数 = ceil([REPAIR_RATIO] × N)。1.0 ⇒ 播放序列一半是原始帧、一半是修复帧。 */
    const val REPAIR_RATIO = 1.0f

    /**
     * 单张二维码（version 40 / 纠错级 L / byte 模式）的字节容量上限。
     *
     * 这是**分界点**：载荷不超过它就走原来的静态单张二维码（旧版本照常互认），
     * 超过才切到动画模式。发送端拿它做决策，所以它必须是个准确值而不是"留点余量"的估值 ——
     * 取小了会把本来塞得下的载荷白白升级成动画（要求对方也升级 App），
     * 取大了则会生成失败。
     *
     * 值是**实测**出来的（`QrFrameRendererTest` 用 version 40 当容量探针二分），不是抄的资料：
     * 资料上常写 2953，但 zxing 3.4.1 这条编码路径的位预算只到 2952 —— 差这 1 字节就会让
     * 一段恰好 2953 字节的载荷在"以为装得下"的判定下直接生成失败。测试同时断言 2952 装得下、
     * 2953 装不下，把边界两侧都钉住。
     */
    const val SINGLE_QR_MAX_BYTES = 2952

    /** 载荷 SHA-256 头部取多少字节。8 字节（64 位）碰撞概率对"扫错屏"这个场景绰绰有余。 */
    private const val SHA_HEAD_BYTES = 8

    private val urlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val urlDecoder: Base64.Decoder = Base64.getUrlDecoder()

    /** 便宜的"是不是动画帧"判据，供扫码层在解析前先分流。 */
    fun isAnimatedFrame(text: String): Boolean = text.startsWith(MAGIC)

    /**
     * 由 seed 复现一个修复帧参与异或的源分片索引表。**收发双方必须完全一致**，
     * 否则接收端解出来的字节是错的（会被整份 SHA-256 挡下，但那一轮就白扫了）。
     *
     * 用 [Random] 是因为它的输出序列在 JDK 规范里是写死的，跨实现、跨平台一致。
     *
     * @param degree 期望的度；会被夹到 1..min(9, totalFrames)
     */
    fun indicesFor(seed: Int, degree: Int, totalFrames: Int): IntArray {
        require(totalFrames > 0) { "totalFrames must be positive" }
        val d = degree.coerceIn(1, minOf(9, totalFrames))
        // 全部参与：直接顺序返回，此时随机抽取必然抽不满
        if (d == totalFrames) return IntArray(d) { it }
        // 度 1 就是原始分片本身，seed 直接当索引用
        if (d == 1) return intArrayOf(Math.floorMod(seed, totalFrames))

        val rnd = Random(seed.toLong())
        val seen = LinkedHashSet<Int>(d)
        var guard = 0
        while (seen.size < d && guard++ < d * 32) seen.add(rnd.nextInt(totalFrames))
        // 极小概率（N 接近 d 时随机抽不满）退化成顺序补位，保证一定返回 d 个不同索引
        var v = Math.floorMod(seed, totalFrames)
        while (seen.size < d) {
            seen.add(v)
            v = (v + 1) % totalFrames
        }
        return seen.toIntArray()
    }

    /**
     * 把一份载荷切成一次传输会话。
     *
     * 只做切分与元数据计算，**不预生成帧字符串**：几百 KB 的载荷会切成几百帧，
     * 调用方（发送端）按需 [SplitSession.frameAt] 取用即可，避免多存一份副本。
     *
     * @param payload 待传输载荷的**原始字节**（通常是 `ShareCryptoUtils.encrypt()` 出来的 ASCII 串的 UTF-8 字节）
     * @param chunkSize 单帧字节数，见 [DEFAULT_CHUNK_SIZE]；所有分片都会补齐到这个长度
     * @param sessionId 会话号；不传则随机生成。同一次传输的所有帧共用一个会话号，
     *   接收端靠它区分"这次传输"与"上一次"，也用来防止两次传输的帧被混着拼起来。
     *   它还决定了修复帧的 seed 序列，因此同一次会话重复 split 会得到同一套帧。
     */
    fun split(
        payload: ByteArray,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        sessionId: Int = randomSessionId(),
    ): SplitSession {
        require(chunkSize > 0) { "chunkSize must be positive" }
        val total = if (payload.isEmpty()) 1 else (payload.size + chunkSize - 1) / chunkSize
        require(total <= MAX_FRAMES) {
            "payload too large: $total frames exceeds $MAX_FRAMES (chunkSize=$chunkSize)"
        }
        val sid = sessionId and 0x7FFFFFFF
        // 只有一个分片时没有"缺一片"可言，修复帧纯属浪费
        val repairCount = if (total <= 1) 0 else (total * REPAIR_RATIO).toInt().coerceAtLeast(1)
        val seeds = IntArray(repairCount) { Random(sid.toLong() * 31L + it).nextInt() }
        return SplitSession(
            payload = payload,
            chunkSize = chunkSize,
            sessionId = sid,
            totalFrames = total,
            sha256Head = sha256Head(payload),
            repairSeeds = seeds,
        )
    }

    /**
     * 解析一个扫到的帧。
     *
     * 三种结果分开返回，是为了让扫码层能做正确的**降级决策**：
     * - [ParseResult.NotAnimatedFrame] ⇒ 不是本协议的码（用户可能扫的是普通单张分享码），
     *   调用方应回退到原有的单码导入路径，而**不是**报错；
     * - [ParseResult.Corrupt] ⇒ 是我们的帧但这一帧不可用。逐帧丢弃即可，靠循环重播补回来；
     * - [ParseResult.Ok] ⇒ 可用帧。
     */
    fun parse(text: String): ParseResult {
        if (!isAnimatedFrame(text)) return ParseResult.NotAnimatedFrame
        if (text.length < HEADER_LEN) return ParseResult.Corrupt(FrameError.TRUNCATED)

        val sessionId = parseHex(text, 4, SESSION_HEX_LEN) ?: return ParseResult.Corrupt(FrameError.BAD_FIELD)
        val total = parseDec(text, 12, COUNT_DEC_LEN) ?: return ParseResult.Corrupt(FrameError.BAD_FIELD)
        val totalBytes = parseHexLong(text, 16, TOTAL_BYTES_HEX_LEN) ?: return ParseResult.Corrupt(FrameError.BAD_FIELD)
        val degree = parseDec(text, 24, DEGREE_DEC_LEN) ?: return ParseResult.Corrupt(FrameError.BAD_FIELD)
        // CRC32 是无符号 32 位，值可能超过 Int.MAX，必须按 Long 取，否则约一半的帧会被误判成字段非法
        val crc = parseHexLong(text, 33, CRC_HEX_LEN) ?: return ParseResult.Corrupt(FrameError.BAD_FIELD)
        val shaHead = text.substring(41, 41 + SHA_HEX_LEN)
        if (!isLowerHex(shaHead)) return ParseResult.Corrupt(FrameError.BAD_FIELD)
        val seed = parseHexLong(text, 25, SEED_HEX_LEN) ?: return ParseResult.Corrupt(FrameError.BAD_FIELD)

        if (total < 1 || total > MAX_FRAMES) return ParseResult.Corrupt(FrameError.BAD_FIELD)
        if (degree < 1 || degree > 9) return ParseResult.Corrupt(FrameError.BAD_FIELD)
        // 8 位十六进制能表达到 4GB，超过 Int 范围的直接当坏帧 —— 否则 toInt() 会溢出成负数
        if (totalBytes > Int.MAX_VALUE) return ParseResult.Corrupt(FrameError.BAD_FIELD)

        val payload = try {
            urlDecoder.decode(text.substring(HEADER_LEN))
        } catch (_: IllegalArgumentException) {
            return ParseResult.Corrupt(FrameError.BAD_FIELD)
        }

        // 先验 CRC 再交出去：坏帧绝不允许进入重组器，否则脏字节会污染最终结果
        if (crc32(payload) != crc) return ParseResult.Corrupt(FrameError.CRC_MISMATCH)

        return ParseResult.Ok(
            QrFrame(
                sessionId = sessionId,
                totalFrames = total,
                totalBytes = totalBytes.toInt(),
                degree = degree,
                seed = seed.toInt(),
                sha256Head = shaHead,
                payload = payload,
            )
        )
    }

    /** 帧解析失败的具体原因。用于日志与接收端 UI 反馈，不直接上屏（文案由调用方本地化）。 */
    enum class FrameError {
        /** 长度不足 [HEADER_LEN]。 */
        TRUNCATED,

        /** 定长字段里有非法字符，或数值越界（total/degree/seed 等）。 */
        BAD_FIELD,

        /** CRC32 对不上：这一帧扫花了，丢弃重扫。 */
        CRC_MISMATCH,
    }

    /** [parse] 的结果。 */
    sealed interface ParseResult {
        data class Ok(val frame: QrFrame) : ParseResult
        object NotAnimatedFrame : ParseResult
        data class Corrupt(val reason: FrameError) : ParseResult
    }

    /**
     * 一个可用帧。[payload] 已通过 CRC 校验。
     *
     * `degree == 1` 时 [payload] 就是第 [seed] 个源分片；`degree > 1` 时它是
     * `indicesFor(seed, degree, totalFrames)` 那几片按位异或的结果。
     */
    data class QrFrame(
        val sessionId: Int,
        val totalFrames: Int,
        /** 原始载荷的总字节数（分片补齐前的真实长度）。 */
        val totalBytes: Int,
        val degree: Int,
        val seed: Int,
        /** 整份载荷 SHA-256 的前 8 字节（十六进制），每个帧都一样，用于提前展示目标指纹。 */
        val sha256Head: String,
        val payload: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean = this === other ||
            (other is QrFrame && sessionId == other.sessionId && totalFrames == other.totalFrames &&
                totalBytes == other.totalBytes && degree == other.degree && seed == other.seed &&
                sha256Head == other.sha256Head && payload.contentEquals(other.payload))

        override fun hashCode(): Int =
            (((((sessionId * 31 + totalFrames) * 31 + totalBytes) * 31 + degree) * 31 + seed) * 31 +
                sha256Head.hashCode()) * 31 + payload.contentHashCode()
    }

    /**
     * 一次传输会话。发送端持它逐帧取字符串去光栅化（见 [frameAt]）。
     *
     * 刻意做成不可变 + 无状态：光栅化缓存在 UI 层，协议层不碰 Bitmap，便于纯 JVM 单测。
     */
    class SplitSession internal constructor(
        val payload: ByteArray,
        val chunkSize: Int,
        val sessionId: Int,
        /** 源分片数 N。进度条的分母用它，不是 [sequenceLength]。 */
        val totalFrames: Int,
        val sha256Head: String,
        /** 修复帧的 seed 序列；序列长度 N + 修复帧数。 */
        private val repairSeeds: IntArray,
    ) {
        /** 原始载荷字节数，等于 `payload.size`（分片补齐前的真实长度）。 */
        val totalBytes: Int get() = payload.size

        /** 播放序列总长度 = N 个原始帧 + 修复帧。发送端按它循环。 */
        val sequenceLength: Int get() = totalFrames + repairSeeds.size

        /**
         * 取播放序列第 [index] 帧的**编码后字符串**（可直接交给 zxing 生成二维码）。
         *
         * `index < totalFrames` 时是原始分片帧（度 1，seed 即分片索引）；
         * 之后是修复帧（度 [REPAIR_DEGREE]，seed 来自 [repairSeeds]）。
         */
        fun frameAt(index: Int): String {
            require(index in 0 until sequenceLength) { "frame index $index out of 0..${sequenceLength - 1}" }
            val degree: Int
            val seed: Int
            if (index < totalFrames) {
                degree = 1
                seed = index
            } else {
                degree = REPAIR_DEGREE
                seed = repairSeeds[index - totalFrames]
            }
            val indices = indicesFor(seed, degree, totalFrames)
            val body = ByteArray(chunkSize)
            for (i in indices) {
                val c = chunkAt(i)
                for (j in c.indices) body[j] = (body[j].toInt() xor c[j].toInt()).toByte()
            }
            // ⚠️ 写进帧头的必须是**实际用到的度**：indicesFor 会把它夹到 1..min(9, N)，
            // 分片数少于 REPAIR_DEGREE 时配置值与实际值不等。写配置值会让头与内容不符，
            // 任何"拿 degree 校验一下"的下游判断都会得出错误结论。
            return MAGIC +
                hex(sessionId.toLong(), SESSION_HEX_LEN) +
                dec(totalFrames, COUNT_DEC_LEN) +
                hex(totalBytes.toLong(), TOTAL_BYTES_HEX_LEN) +
                dec(indices.size, DEGREE_DEC_LEN) +
                hex(seed.toLong() and 0xFFFFFFFFL, SEED_HEX_LEN) +
                hex(crc32(body), CRC_HEX_LEN) +
                sha256Head +
                urlEncoder.encodeToString(body)
        }

        /** 第 [i] 个源分片，**补齐到 [chunkSize]**（末尾补 0）以保证 XOR 操作数等长。 */
        private fun chunkAt(i: Int): ByteArray {
            val out = ByteArray(chunkSize)
            val from = i * chunkSize
            if (from >= payload.size) return out
            val to = minOf(from + chunkSize, payload.size)
            payload.copyInto(out, 0, from, to)
            return out
        }
    }

    /** 整份载荷的 SHA-256 前 [SHA_HEAD_BYTES] 字节，小写十六进制（16 字符）。 */
    fun sha256Head(payload: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        return buildString(SHA_HEX_LEN) {
            for (i in 0 until SHA_HEAD_BYTES) append("%02x".format(digest[i].toInt() and 0xFF))
        }
    }

    private fun crc32(data: ByteArray): Long = CRC32().apply { update(data) }.value

    private fun hex(value: Long, width: Int): String = "%0${width}x".format(value)

    private fun dec(value: Int, width: Int): String = "%0${width}d".format(value)

    /**
     * 定长十六进制字段，按 `Int` 收（用于 sessionId —— 生成时已掩到 31 位，必然放得下）。
     * 非法（含越界）返回 null 而不是抛，解析路径上不该有异常。
     */
    private fun parseHex(text: String, offset: Int, len: Int): Int? {
        val v = parseHexLong(text, offset, len) ?: return null
        return if (v > Int.MAX_VALUE) null else v.toInt()
    }

    /**
     * 定长十六进制字段，按 `Long` 收。**CRC32 必须走这条**：它是无符号 32 位，
     * 值域 0..4294967295 越过了 `Int.MAX_VALUE`，用 Int 收会把一半的帧判成非法。
     */
    private fun parseHexLong(text: String, offset: Int, len: Int): Long? {
        val s = text.substring(offset, offset + len)
        if (!isLowerHex(s)) return null
        return s.toLong(16)
    }

    /** 定长十进制字段；不接受符号与空白。 */
    private fun parseDec(text: String, offset: Int, len: Int): Int? {
        var acc = 0
        for (i in offset until offset + len) {
            val c = text[i]
            if (c < '0' || c > '9') return null
            acc = acc * 10 + (c - '0')
        }
        return acc
    }

    private fun isLowerHex(s: String): Boolean =
        s.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * 随机会话号，避开 0（0 保留给"未开始"这种哨兵语义，便于排查）。
     * 不需要密码学强度：它只用来区分并发传输，不是安全边界。
     */
    private fun randomSessionId(): Int = (Random().nextInt(Int.MAX_VALUE - 1) + 1) and 0x7FFFFFFF
}
