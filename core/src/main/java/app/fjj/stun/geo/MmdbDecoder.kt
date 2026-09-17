package app.fjj.stun.geo

import java.math.BigInteger
import java.nio.ByteBuffer

/**
 * MaxMind DB 数据段的**原始读取原语与值解码**，全部按大端。
 *
 * 独立成一个 object 而不是塞进 [MmdbReader] 的实例方法：元数据段必须在**构造 reader 之前**
 * 就解出来（`node_count` / `record_size` / `ip_version` 全在元数据里），
 * 所以解码逻辑不能依赖实例状态。
 *
 * ## 两个最容易写错的地方（都已按官方实现逐位对齐）
 *
 * 1. **扩展类型**：control byte 的 type 只有 3 位（0–7），而规范定义到 15。
 *    当这 3 位为 0 时，**紧接着的一字节 + 7 才是真实类型**，且该字节在长度字节之前。
 *    只处理 0–7 的实现在真实库里通常会"碰巧"跑通（常见字段是 string/double/map），
 *    但一遇到 uint32（type 6）之外的类型就会错位。
 * 2. **pointer 不走长度扩展**：pointer 复用那 5 个 size 位编码"指针长度档"，
 *    所以 `size >= 29` 的长度扩展必须排除 `type == 1`。
 */
internal object MmdbDecoder {

    const val TYPE_POINTER = 1
    const val TYPE_STRING = 2
    const val TYPE_DOUBLE = 3
    const val TYPE_BYTES = 4
    const val TYPE_UINT16 = 5
    const val TYPE_UINT32 = 6
    const val TYPE_MAP = 7
    const val TYPE_INT32 = 8
    const val TYPE_UINT64 = 9
    const val TYPE_UINT128 = 10
    const val TYPE_ARRAY = 11
    const val TYPE_BOOLEAN = 14
    const val TYPE_FLOAT = 15

    /** 下标即 pointerSize(1..4)：小尺寸加固定基准，4 字节档不加。 */
    private val POINTER_VALUE_OFFSETS = longArrayOf(0L, 0L, 2048L, 526336L, 0L)

    fun u8(buf: ByteBuffer, offset: Long): Int = buf.get(offset.toInt()).toInt() and 0xFF

    fun u16(buf: ByteBuffer, offset: Long): Long =
        (u8(buf, offset).toLong() shl 8) or u8(buf, offset + 1).toLong()

    fun u24(buf: ByteBuffer, offset: Long): Long =
        (u8(buf, offset).toLong() shl 16) or
            (u8(buf, offset + 1).toLong() shl 8) or u8(buf, offset + 2).toLong()

    fun u32(buf: ByteBuffer, offset: Long): Long =
        (u8(buf, offset).toLong() shl 24) or (u8(buf, offset + 1).toLong() shl 16) or
            (u8(buf, offset + 2).toLong() shl 8) or u8(buf, offset + 3).toLong()

    fun u64(buf: ByteBuffer, offset: Long): Long {
        var value = 0L
        for (k in 0 until 8) value = (value shl 8) or u8(buf, offset + k).toLong()
        return value
    }

    fun bytes(buf: ByteBuffer, offset: Long, size: Long): ByteArray {
        val out = ByteArray(size.toInt())
        buf.duplicate().also { it.position(offset.toInt()) }.get(out)
        return out
    }

    /** 树节点占用的字节数：24→6、28→7、32→8。 */
    fun nodeByteSize(recordSize: Int): Int = recordSize / 4

    /**
     * 读节点 [nodeNumber] 的第 [index] 条记录（0=左/bit0，1=右/bit1）。
     *
     * 三档位宽的拆分与官方实现逐位对齐：24 位 = 两个各 3 字节的记录；32 位 = 两个各 4 字节的记录。
     *
     * 28 位是唯一有"共享字节"的一档，真实库（`record_size = 24`）不走这条路径，也最容易写错。
     * 规范 "Node Layout" 给出的布局是 `| 23..0 | 27..24 | 27..24 | 23..0 |`，并明确指出
     * 两条记录的那 4 位都是 *"prepended and end up in the most significant position"*：
     * 中间字节的**高半字节是左记录的 bit 27..24、低半字节是右记录的 bit 27..24**，
     * 位置在**高位**而不是补到低 4 位。即左记录 = `高半字节 << 24 | 前 3 字节`。
     *
     * 这个"补高位"还是"补低位"的分歧极隐蔽：两种写法都能算出一个 28 位数、都不会越界，
     * 只有在 28 位的库里才会全线错位。回归测试用合成字节专门钉住这条。
     */
    fun readRecord(buf: ByteBuffer, nodeNumber: Int, recordSize: Int, index: Int): Int {
        val base = nodeNumber.toLong() * nodeByteSize(recordSize)
        return when (recordSize) {
            24 -> u24(buf, base + index * 3L).toInt()
            28 -> if (index == 1) {
                (u32(buf, base + 3L) and 0x0FFFFFFFL).toInt()
            } else {
                val record = u32(buf, base)
                ((record ushr 8) or ((record and 0xF0L) shl 20)).toInt()
            }
            32 -> u32(buf, base + index * 4L).toInt()
            else -> 0
        }
    }

    /**
     * 从 [offset] 解出一个值，返回 `(值, 下一个字节位置)`。
     *
     * [base] 是该段内 pointer 的相对基准：数据段用「数据段起点」，
     * 元数据段用元数据自身起点（元数据里也可能出现 pointer）。
     */
    fun decode(buf: ByteBuffer, offset: Long, base: Long): Pair<Any?, Long> {
        val ctrl = u8(buf, offset)
        var pos = offset + 1

        // ① 扩展类型：3 位 type 为 0 时，下一字节 + 7 才是真实类型。
        var type = ctrl ushr 5
        if (type == 0) {
            type = u8(buf, pos) + 7
            pos += 1
        }

        val rawSize = ctrl and 0x1F
        var size = rawSize.toLong()

        // ② pointer 的 5 个 size 位是"指针长度档"，不是长度，跳过扩展。
        if (size >= 29 && type != TYPE_POINTER) {
            when (rawSize) {
                29 -> {
                    size = 29L + u8(buf, pos); pos += 1
                }
                30 -> {
                    size = 285L + u16(buf, pos); pos += 2
                }
                31 -> {
                    size = 65821L + u24(buf, pos); pos += 3
                }
            }
        }

        if (type == TYPE_POINTER) {
            val pointerSize = (rawSize ushr 3) + 1
            var value = 0L
            for (k in 0 until pointerSize) value = (value shl 8) or u8(buf, pos + k).toLong()
            if (pointerSize < 4) {
                value = value or ((rawSize and 0x7).toLong() shl (pointerSize * 8))
                value += POINTER_VALUE_OFFSETS[pointerSize]
            }
            val next = pos + pointerSize
            return decode(buf, value + base, base).first to next
        }

        return when (type) {
            TYPE_STRING -> String(bytes(buf, pos, size), Charsets.UTF_8) to (pos + size)

            // double / float 的宽度由类型定死，与长度位不一致的文件视为损坏。
            TYPE_DOUBLE -> if (size == 8L) {
                Double.fromBits(u64(buf, pos)) to (pos + 8)
            } else {
                null to (pos + size)
            }

            TYPE_FLOAT -> if (size == 4L) {
                Float.fromBits(u32(buf, pos).toInt()) to (pos + 4)
            } else {
                null to (pos + size)
            }

            TYPE_BYTES -> bytes(buf, pos, size) to (pos + size)

            TYPE_UINT16, TYPE_UINT32, TYPE_UINT64, TYPE_UINT128 ->
                unsignedValue(buf, pos, size.toInt()) to (pos + size)

            TYPE_INT32 -> int32OfSize(buf, pos, size.toInt()) to (pos + size)

            TYPE_MAP -> {
                val map = LinkedHashMap<String, Any?>(size.toInt().coerceAtLeast(0))
                var cursor = pos
                for (n in 0 until size) {
                    val (key, afterKey) = decode(buf, cursor, base)
                    val (value, afterValue) = decode(buf, afterKey, base)
                    if (key is String) map[key] = value
                    cursor = afterValue
                }
                map to cursor
            }

            TYPE_ARRAY -> {
                val list = ArrayList<Any?>(size.toInt().coerceAtLeast(0))
                var cursor = pos
                for (n in 0 until size) {
                    val (value, after) = decode(buf, cursor, base)
                    list.add(value)
                    cursor = after
                }
                list to cursor
            }

            // boolean 的 size 位本身就是取值，**没有负载字节**。
            TYPE_BOOLEAN -> (size != 0L) to pos

            else -> null to (pos + size)
        }
    }

    private fun unsignedValue(buf: ByteBuffer, offset: Long, size: Int): Any =
        if (size <= 8) {
            var value = 0L
            for (k in 0 until size) value = (value shl 8) or u8(buf, offset + k).toLong()
            value
        } else {
            BigInteger(1, bytes(buf, offset, size.toLong()))
        }

    /**
     * `int32`：官方实现在**左侧补 0** 到 4 字节后按有符号 32 位读：
     *
     * ```python
     * if size != 4:
     *     packed_bytes = packed_bytes.rjust(4, b"\x00")
     * (value,) = struct.unpack(b"!i", packed_bytes)
     * ```
     *
     * 所以语义是分裂的：**不足 4 字节的取值恒为非负**（高位补 0），只有满 4 字节才可能为负。
     * 这里照抄官方而不是"按 32 位有符号做符号扩展"——后者会把 `0xFF` 解成 -1 而官方解成 255，
     * 属于静默分歧。当前 city 库只有 string / double / map，不含 int32，这条对实际数据无影响，
     * 但没必要留一个"看起来更合理、实际与规范不一致"的坑。
     */
    private fun int32OfSize(buf: ByteBuffer, offset: Long, size: Int): Int {
        if (size <= 0 || size > 4) return 0 // 官方对 >4 字节直接判为损坏，这里退化成 0（不抛）
        var value = 0
        for (k in 0 until size) value = (value shl 8) or u8(buf, offset + k)
        return value
    }
}
