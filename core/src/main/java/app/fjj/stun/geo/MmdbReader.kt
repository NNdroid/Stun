package app.fjj.stun.geo

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Locale

/**
 * 极简 MaxMind DB（`.mmdb`）读取器 —— 纯 Kotlin、零第三方依赖。
 *
 * 只做一件事：给定 IP 字面量，返回它的记录（扁平 Map）。实现严格对齐官方规范：
 * 树节点按 24/28/32 三档 `record_size` 拆分；数据段支持全部 14 种类型（见 [MmdbDecoder]）。
 *
 * **用 mmap 而不是把整个文件读进堆**：city 库的搜索树本身就有约 22 MB（3.67M 节点 × 6 字节），
 * 双栈合计 44 MB，读进堆对移动端不可接受。mmap 只把真正被访问到的页拉进来。
 * 注意 mmap 的生命周期挂在文件描述符上，所以 [RandomAccessFile] 必须持有到 [close]，
 * 不能在 `open()` 里提前关掉。
 *
 * 地址族行为（实测确认，必须守住）：
 * - IPv4 库查 IPv6 地址 → **返回 null**（官方 Python 实现在此处会抛异常，不该把那个坑带给调用方）。
 * - IPv6 库查 IPv4 地址 → 走 [ipv4Start] 捷径按 v4-mapped 处理；city 库实测无 v4-mapped 数据 → null。
 *
 * **任何失败都返回 null，绝不抛异常** —— 地球是装饰件，不能因为一条查不到就崩。
 */
class MmdbReader private constructor(
    private val raf: RandomAccessFile,
    private val buffer: ByteBuffer,
    /** 库里承载的地址族：4 或 6。 */
    val ipVersion: Int,
    /** 24 / 28 / 32。 */
    val recordSize: Int,
    val nodeCount: Int,
    val databaseType: String,
    /** 库的构建时间戳（秒）。**判断数据新旧看这个，别看 release 的 `published_at`。** */
    val buildEpoch: Long,
) : AutoCloseable {

    private val nodeByteSize = recordSize / 4
    private val searchTreeSize = nodeCount.toLong() * nodeByteSize

    /** 数据段内 pointer 的相对基准 = 数据段起点。 */
    private val pointerBase = searchTreeSize + DATA_SECTION_SEPARATOR_SIZE

    /** IPv6 树里 IPv4 的入口节点（连走 96 个 0 位）。 */
    private val ipv4Start: Int = if (ipVersion == 6) {
        var node = 0
        var i = 0
        while (i < 96 && node < nodeCount) {
            node = readNode(node, 0)
            i++
        }
        node
    } else {
        0
    }

    /**
     * 查询一条记录。**找不到 / 地址族不匹配 / 数据损坏一律返回 null。**
     * 只做 ByteBuffer 绝对位置读、不改 position，因此可被多线程并发调用。
     */
    fun lookup(ip: String): Map<String, Any?>? {
        val packed = packIp(ip) ?: return null
        // IPv4 库不认 IPv6 地址：直接给 null，而不是让位遍历跑出树外。
        if (ipVersion == 4 && packed.size == 16) return null
        // 位遍历要按树里的下标读 buffer，损坏的库会让下标越界（BufferUnderflow/IndexOutOfBounds）。
        // 调用方（地球）不该为一条脏数据挂掉，所以这里兜住，契约见类注释。
        return try {
            walk(packed)
        } catch (_: Exception) {
            null
        }
    }

    private fun walk(packed: ByteArray): Map<String, Any?>? {
        val bitCount = packed.size * 8
        var node = if (ipVersion == 6 && packed.size == 4) ipv4Start else 0
        var i = 0
        while (i < bitCount && node.toLong() < nodeCount.toLong()) {
            val bit = (packed[i shr 3].toInt() ushr (7 - (i and 7))) and 1
            node = readNode(node, bit)
            i++
        }

        // 走完所有位仍停在内节点：该前缀没有数据。
        if (node.toLong() < nodeCount.toLong()) return null
        // 记录值为 node_count 表示空记录。
        if (node.toLong() == nodeCount.toLong()) return null

        val absolute = node.toLong() - nodeCount + searchTreeSize
        @Suppress("UNCHECKED_CAST")
        return MmdbDecoder.decode(buffer, absolute, pointerBase).first as? Map<String, Any?>
    }

    override fun close() {
        runCatching { raf.close() }
    }

    override fun toString(): String = String.format(
        Locale.ROOT,
        "MmdbReader(type=%s, ipv%d, record=%d, nodes=%d, build=%d)",
        databaseType, ipVersion, recordSize, nodeCount, buildEpoch,
    )

    private fun readNode(nodeNumber: Int, index: Int): Int =
        MmdbDecoder.readRecord(buffer, nodeNumber, recordSize, index)

    companion object {
        private const val DATA_SECTION_SEPARATOR_SIZE = 16L
        private const val METADATA_SEARCH_BYTES = 128 * 1024L

        /** 元数据段起始标记：`AB CD EF` + `MaxMind.com`。 */
        private val METADATA_MARKER =
            byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte()) +
                "MaxMind.com".toByteArray(Charsets.US_ASCII)

        /** 打开一个 mmdb。文件不存在 / 格式不对 / 元数据非法一律返回 null。 */
        fun open(file: File): MmdbReader? {
            if (!file.isFile || file.length() <= 0L) return null
            val raf = try {
                RandomAccessFile(file, "r")
            } catch (_: Exception) {
                return null
            }
            try {
                val size = raf.channel.size()
                if (size <= 0L) {
                    raf.close()
                    return null
                }
                val buffer = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
                buffer.order(ByteOrder.BIG_ENDIAN)

                val metadataStart = findMetadataStart(buffer, size)
                if (metadataStart == null) {
                    raf.close()
                    return null
                }
                // 元数据段内 pointer 的相对基准是**标记的起始位置**，而不是标记之后。
                // 官方 Reader 传的就是 `rfind(_METADATA_START)` 的结果。按"标记之后"当基准会整体
                // 偏移 14 字节；真实库的元数据里不含 pointer 所以看不出来，属于潜在错位，按规范写死。
                val metadataBase = metadataStart - METADATA_MARKER.size
                val md = MmdbDecoder.decode(buffer, metadataStart, metadataBase).first as? Map<*, *>
                if (md == null) {
                    raf.close()
                    return null
                }
                val nodes = (md["node_count"] as? Number)?.toInt()
                val record = (md["record_size"] as? Number)?.toInt()
                val version = (md["ip_version"] as? Number)?.toInt()
                if (nodes == null || nodes <= 0) { raf.close(); return null }
                if (record == null || record !in setOf(24, 28, 32)) { raf.close(); return null }
                if (version == null || version !in setOf(4, 6)) { raf.close(); return null }
                if (nodes.toLong() * (record / 4) + DATA_SECTION_SEPARATOR_SIZE > size) {
                    raf.close()
                    return null
                }

                return MmdbReader(
                    raf = raf,
                    buffer = buffer,
                    ipVersion = version,
                    recordSize = record,
                    nodeCount = nodes,
                    databaseType = md["database_type"] as? String ?: "",
                    buildEpoch = (md["build_epoch"] as? Number)?.toLong() ?: 0L,
                )
            } catch (_: Exception) {
                runCatching { raf.close() }
                return null
            }
        }

        /** 从尾部往前找元数据标记（官方实现限制在最后 128 KiB 内）。 */
        private fun findMetadataStart(buffer: ByteBuffer, size: Long): Long? {
            val tailLen = minOf(METADATA_SEARCH_BYTES, size).toInt()
            val start = size - tailLen
            val tail = ByteArray(tailLen)
            buffer.duplicate().also { it.position(start.toInt()) }.get(tail)
            val idx = lastIndexOf(tail, METADATA_MARKER)
            return if (idx < 0) null else start + idx + METADATA_MARKER.size
        }

        private fun lastIndexOf(haystack: ByteArray, needle: ByteArray): Int {
            if (needle.isEmpty() || needle.size > haystack.size) return -1
            outer@ for (i in haystack.size - needle.size downTo 0) {
                for (j in needle.indices) {
                    if (haystack[i + j] != needle[j]) continue@outer
                }
                return i
            }
            return -1
        }

        /**
         * 把字符串转成地址字节（IPv4→4 字节，IPv6→16 字节）。
         *
         * **绝不触发 DNS**，这是本方法的硬约束：入参来自订阅里的服务器地址，
         * 一个意外的域名解析会泄露用户正在连哪台机器。
         *
         * - IPv4：**自己逐段校验**，每个八位组必须是 0..255 的十进制数。不能只靠正则再交给
         *   [java.net.InetAddress] —— 像 `999.1.1.1` 这种能过正则、却会被 Java 当成
         *   **主机名去查 DNS**，那正是要避免的。
         * - IPv6：字面量必然含 `:`，而主机名不可能含 `:`，所以交给 [java.net.InetAddress] 是安全的。
         */
        fun packIp(raw: String): ByteArray? {
            val ip = raw.trim().substringBefore('%') // 去掉 IPv6 zone id
            if (ip.isEmpty()) return null

            if (ip.contains(':')) {
                return try {
                    java.net.InetAddress.getByName(ip).address
                } catch (_: Exception) {
                    null
                }
            }

            val parts = ip.split('.')
            if (parts.size != 4) return null
            val out = ByteArray(4)
            for (i in 0..3) {
                val part = parts[i]
                if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
                val value = part.toIntOrNull() ?: return null
                if (value > 255) return null
                out[i] = value.toByte()
            }
            return out
        }
    }
}
