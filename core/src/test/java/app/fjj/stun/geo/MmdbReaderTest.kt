package app.fjj.stun.geo

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * [MmdbReader] / [MmdbDecoder] 的回归测试。
 *
 * 分两类，缺一不可：
 *
 * 1. **真实数据对拍**：拿 sapics city 库（IPv4 + IPv6）逐条比对官方 `maxminddb` 导出的向量。
 *    这验证的是 mmap、树遍历、pointer 解析、地址族路由这整条链路 —— 靠合成字节测不出来。
 * 2. **合成字节**：真实库是 `record_size = 24`、字段只有 string / double / map，
 *    所以 **28 位记录的共享字节拆分**、**扩展类型（type == 0）**、**长度扩展档 29/30/31**、
 *    **boolean 无负载** 这几条路径在真实数据里**永远不会被走到**。这几条恰恰是手写实现最容易错的地方，
 *    必须自己造字节覆盖 —— 其中 28 位那条已经真实地挡下过一次错误预期，见对应用例的注释。
 *
 * 二进制库（44 MB）与向量文件都不入库。测试运行时按以下顺序找目录，找不到就 `assumeTrue` 跳过：
 * `-Dstun.mmdb.dir=` → 环境变量 `STUN_MMDB_DIR` → 从工作目录往上找 `.workbuddy/tmp/`。
 * 本地把 `city-ipv4.mmdb` / `city-ipv6.mmdb` / `mmdb_vectors.json` 放进该目录即可跑对拍。
 */
class MmdbReaderTest {

    private val fixtureDir: File? by lazy { resolveFixtureDir() }

    // ---------------------------------------------------------------- 真实数据对拍

    @Test
    fun `元数据与官方一致`() {
        val (v4, v6) = openBoth()
        v4.use { assertMetadata("ipv4", it) }
        v6.use { assertMetadata("ipv6", it) }
    }

    @Test
    fun `IPv4 库对 IPv4 地址逐条与官方向量一致`() {
        val (v4, _) = openBoth()
        v4.use { reader -> assertBucket("ipv4_db", reader) }
    }

    @Test
    fun `IPv6 库对 IPv6 地址逐条与官方向量一致`() {
        val (_, v6) = openBoth()
        v6.use { reader -> assertBucket("ipv6_db", reader) }
    }

    /**
     * 官方 Python 实现遇到"IPv4 库 + IPv6 地址"会抛 `ValueError`，向量里记的就是那个异常。
     * 我方契约不同：**静默返回 null**，绝不抛 —— 地球是装饰件，不能因为一条脏数据让界面崩掉。
     */
    @Test
    fun `IPv4 库查 IPv6 地址返回 null 且不抛异常`() {
        val (v4, _) = openBoth()
        val bucket = vectors().getAsJsonObject("ipv4_db_with_v6_addr")
        assertTrue("向量桶不该为空", bucket.size() > 0)
        v4.use { reader ->
            bucket.keySet().forEach { ip ->
                assertNull("ipv4_db/$ip 应返回 null 而不是抛异常", reader.lookup(ip))
            }
        }
    }

    @Test
    fun `IPv6 库查 IPv4 地址返回 null`() {
        val (_, v6) = openBoth()
        v6.use { reader -> assertBucket("ipv6_db_with_v4_addr", reader) }
    }

    /**
     * [MmdbReader.lookup] 明确声明"只做绝对位置读、可并发调用"，那就得真并发跑一遍。
     * 共用同一个 mmap 缓冲区，任何一处误用 `position()` 都会在这里串味。
     */
    @Test
    fun `多线程并发查询结果与单线程一致`() {
        val (v4, _) = openBoth()
        val ips = vectors().getAsJsonObject("ipv4_db").keySet().toList()
        v4.use { reader ->
            val expected = ips.associateWith { reader.lookup(it) }
            val pool = Executors.newFixedThreadPool(4)
            try {
                val futures = (0 until 4).map {
                    // 显式构造 Callable：submit 同时有 (Runnable) 与 (Callable) 两个重载，
                    // 直接传尾随 lambda 会撞上重载歧义。
                    pool.submit(
                        Callable { ips.associateWith { ip: String -> reader.lookup(ip) } },
                    )
                }
                futures.forEachIndexed { index, future ->
                    assertEquals("第 $index 个线程结果不一致", expected, future.get())
                }
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `open 对缺失文件与垃圾文件都返回 null`() {
        val missing = File(File(System.getProperty("java.io.tmpdir") ?: "."), "stun-missing.mmdb")
        assertFalse("前置条件：该文件不该存在", missing.exists())
        assertNull("缺失文件应返回 null", MmdbReader.open(missing))

        val empty = File.createTempFile("stun-empty", ".mmdb")
        val junk = File.createTempFile("stun-junk", ".mmdb")
        try {
            assertNull("空文件应被拒绝", MmdbReader.open(empty))
            junk.writeBytes(ByteArray(8192) { 'A'.code.toByte() })
            assertNull("没有 MaxMind 标记的文件应被拒绝", MmdbReader.open(junk))
        } finally {
            empty.delete()
            junk.delete()
        }
    }

    // ---------------------------------------------------------------- packIp（DNS 泄露防线）

    /**
     * 本方法的硬约束是**绝不触发 DNS**：入参来自订阅里的服务器地址。
     *
     * 最容易漏的是 `999.1.1.1` 这种 —— 它长得像 IPv4、也过得了朴素正则，
     * 但交给 `InetAddress.getByName` 就会被**当成主机名去解析**。必须自己逐段校验拦下来。
     */
    @Test
    fun `packIp 拒绝任何需要 DNS 解析的输入`() {
        listOf(
            "example.com",
            "localhost",
            "8.8.8.8.nip.io",
            "999.1.1.1",
            "256.0.0.1",
            "8.8.8.-1",
            "8.8.8.8.9",
            "1.2.3",
            "",
            "   ",
        ).forEach { input ->
            assertNull("packIp(${input.quote()}) 必须是 null", MmdbReader.packIp(input))
        }
    }

    @Test
    fun `packIp 正常字面量按大端拆字节`() {
        assertArrayEquals(byteArrayOf(8, 8, 8, 8), MmdbReader.packIp("8.8.8.8"))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), MmdbReader.packIp("001.002.003.004"))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), MmdbReader.packIp("0.0.0.0"))
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1), MmdbReader.packIp("255.255.255.255"))

        assertEquals(16, MmdbReader.packIp("2001:db8::1")!!.size)
        assertEquals(16, MmdbReader.packIp("::1")!!.size)
        // IPv6 zone id（`%wlan0`）必须去掉，否则解析失败
        assertArrayEquals(MmdbReader.packIp("2001:db8::1"), MmdbReader.packIp("2001:db8::1%wlan0"))
    }

    /**
     * 首尾空白**必须容忍**：地址是从订阅内容里抠出来的，行尾常带空格或 `\t`。
     * 这不是"顺手宽松"，如果这里返回 null，中文/CRLF 订阅里的每一台机器都会静默变成"地球上的谜"。
     */
    @Test
    fun `packIp 容忍首尾空白但内容仍需合法`() {
        assertArrayEquals(byteArrayOf(8, 8, 8, 8), MmdbReader.packIp(" 8.8.8.8 "))
        assertArrayEquals(byteArrayOf(8, 8, 8, 8), MmdbReader.packIp("\t8.8.8.8\r\n"))
        assertArrayEquals(MmdbReader.packIp("2001:db8::1"), MmdbReader.packIp(" 2001:db8::1\t"))
    }

    // ---------------------------------------------------------------- 合成字节：真实库走不到的路径

    @Test
    fun `树节点字节宽度三档`() {
        assertEquals(6, MmdbDecoder.nodeByteSize(24))
        assertEquals(7, MmdbDecoder.nodeByteSize(28))
        assertEquals(8, MmdbDecoder.nodeByteSize(32))
    }

    @Test
    fun `24 位记录从节点基址按 index 取三字节`() {
        val buf = ByteBuffer.wrap(byteArrayOf(0x00, 0x00, 0x01, 0x00, 0x00, 0x02))
        assertEquals(1, MmdbDecoder.readRecord(buf, 0, 24, 0))
        assertEquals(2, MmdbDecoder.readRecord(buf, 0, 24, 1))
    }

    @Test
    fun `32 位记录从节点基址按 index 取四字节`() {
        val buf = ByteBuffer.wrap(
            ByteArray(16).also {
                it[8] = 0x11; it[9] = 0x00; it[10] = 0x00; it[11] = 0x00
                it[12] = 0x22; it[13] = 0x00; it[14] = 0x00; it[15] = 0x00
            },
        )
        assertEquals(0x11000000, MmdbDecoder.readRecord(buf, 1, 32, 0))
        assertEquals(0x22000000, MmdbDecoder.readRecord(buf, 1, 32, 1))
    }

    /**
     * 28 位是**唯一有"共享字节"**的一档，真实库不走这条路径。
     *
     * 规范 "Node Layout" 给左记录 28 位的布局是：
     * ```
     * | <------------- node --------------->|
     * | 23 .. 0 | 27..24 | 27..24 | 23 .. 0 |
     * ```
     * 并明确说明：*"For both records, they are prepended and end up in the most significant position."*
     * 也就是说中间字节的**高半字节是左记录的 bit 27..24、低半字节是右记录的 bit 27..24**，
     * 两者都"前置"到各自 28 位值的**高位**（而不是补在低 4 位）。
     *
     * 这里刻意用 `shl 24 or ...` 的表达式写出期望值而不是写死十六进制常量：
     * 最初我把期望写成"低 24 位左移 4 位再把半字节补到最低位"，被这个用例当场抓出 0x7123456 vs 0x1234567 的差异。
     * 表达式形式让"半字节在高位"这件事在断言里直接可见。
     */
    @Test
    fun `28 位记录的共享字节按规范前置到高位`() {
        val buf = ByteBuffer.wrap(
            byteArrayOf(
                // 节点 0：前 3 字节 + 半字节 0x7 / 0x8 + 后 3 字节
                0x12, 0x34, 0x56, 0x78, 0x9A.toByte(), 0xBC.toByte(), 0xDE.toByte(),
                // 节点 1：验证基址是 node * 7
                0x00, 0x00, 0x0A, 0x2B, 0x00, 0x00, 0x0C,
            ),
        )
        assertEquals((0x7 shl 24) or 0x123456, MmdbDecoder.readRecord(buf, 0, 28, 0))
        assertEquals((0x8 shl 24) or 0x9ABCDE, MmdbDecoder.readRecord(buf, 0, 28, 1))
        assertEquals((0x2 shl 24) or 0x00000A, MmdbDecoder.readRecord(buf, 1, 28, 0))
        assertEquals((0xB shl 24) or 0x00000C, MmdbDecoder.readRecord(buf, 1, 28, 1))

        // 两条记录都必须落在 28 位以内，否则说明多带了相邻节点的位
        listOf(0, 1).forEach { node ->
            listOf(0, 1).forEach { index ->
                val record = MmdbDecoder.readRecord(buf, node, 28, index)
                assertTrue("node=$node index=$index 越出 28 位：$record", record in 0..0x0FFFFFFF)
            }
        }
    }

    /**
     * 扩展类型：control byte 的 type 只有 3 位，为 0 时**紧接着的一字节 + 7** 才是真实类型。
     * 只认 0–7 的实现在真实库上常常"碰巧"能跑（常见字段是 string/double/map，都在 0–7 里），
     * 一遇到 float(15) / boolean(14) 就会静默错位。
     */
    @Test
    fun `扩展类型解析 float 与 boolean`() {
        // type=0 size=4 → 下一字节 8 → 真实类型 15(float)；负载 4 字节 = 0x3F800000 = 1.0f
        val floatBuf = ByteBuffer.wrap(byteArrayOf(0x04, 0x08, 0x3F, 0x80.toByte(), 0x00, 0x00))
        val (floatValue, floatNext) = MmdbDecoder.decode(floatBuf, 0, 0)
        assertEquals(1.0f, floatValue)
        assertEquals(6L, floatNext)

        // type=0 size=1 → 下一字节 7 → 真实类型 14(boolean)；size 位本身即取值，**无负载字节**
        val boolBuf = ByteBuffer.wrap(byteArrayOf(0x01, 0x07))
        val (boolValue, boolNext) = MmdbDecoder.decode(boolBuf, 0, 0)
        assertEquals(true, boolValue)
        assertEquals(2L, boolNext)

        val falseBuf = ByteBuffer.wrap(byteArrayOf(0x00, 0x07))
        assertEquals(false, MmdbDecoder.decode(falseBuf, 0, 0).first)
    }

    /** 长度扩展档 29 按"29 + 下一字节"计长；pointer 复用这 5 位当"指针长度档"，必须排除在扩展之外。 */
    @Test
    fun `长度扩展档 29 按 29 加下一字节计长`() {
        val payload = ByteArray(30) { 'x'.code.toByte() }
        val buf = ByteBuffer.wrap(byteArrayOf(0x5D, 0x01) + payload)
        val (value, next) = MmdbDecoder.decode(buf, 0, 0)
        assertEquals("x".repeat(30), value)
        assertEquals(32L, next)
    }

    /**
     * pointer 的 5 位 size 是**指针长度档**而不是长度，所以不参与长度扩展；
     * 且返回值里的"下一位置"是 **pointer 自己的终点**，不是被指向数据的终点 ——
     * 这一点写错会让上层在解 map 时把游标跳到错误位置（尤其是一堆字段值都是 pointer 的真实库）。
     */
    @Test
    fun `pointer 解引用后返回自身终点`() {
        val buf = ByteBuffer.wrap(
            byteArrayOf(
                0x20, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x42, 'H'.code.toByte(), 'i'.code.toByte(),
            ),
        )
        // ctrl=0x20 → type=1、rawSize=0 → pointerSize=1；负载 1 字节 = 8，基准 0 → 指向偏移 8 的字符串
        val (value, next) = MmdbDecoder.decode(buf, 0, 0)
        assertEquals("Hi", value)
        assertEquals(2L, next)
    }

    /**
     * `int32` 的官方语义**是分裂的**：不足 4 字节在左侧补 0（于是恒为非负），满 4 字节才按有符号读。
     *
     * ```python
     * if size != 4:
     *     packed_bytes = packed_bytes.rjust(4, b"\x00")
     * (value,) = struct.unpack(b"!i", packed_bytes)
     * ```
     *
     * 若按"32 位有符号字面语义做符号扩展"，`0xFF` 会解成 -1 而官方是 255 —— 静默分歧。
     * int32 和 uint32 都在 type > 7 的区间，所以这两个用例同时覆盖"扩展类型 + 整数"的组合。
     */
    @Test
    fun `int32 不足四字节左补零 满四字节才有符号`() {
        // int32(8) 属于扩展类型：ctrl = 0x01（type=0, size=1）→ 扩展字节 0x01 → 真实类型 8
        val short = ByteBuffer.wrap(byteArrayOf(0x01, 0x01, 0xFF.toByte()))
        val (shortValue, shortNext) = MmdbDecoder.decode(short, 0, 0)
        assertEquals("不足 4 字节应左补 0，即 255 而不是 -1", 255, shortValue)
        assertEquals(3L, shortNext)

        val full = ByteBuffer.wrap(byteArrayOf(0x04, 0x01, -1, -1, -1, -1))
        val (fullValue, fullNext) = MmdbDecoder.decode(full, 0, 0)
        assertEquals("满 4 字节才按有符号读", -1, fullValue)
        assertEquals(6L, fullNext)

        // uint32(6) 不需要扩展类型：ctrl = 6 << 5 | 4 = 0xC4；无符号，不能溢出成负数
        val unsigned = ByteBuffer.wrap(byteArrayOf(0xC4.toByte(), -1, -1, -1, -1))
        assertEquals(4294967295L, MmdbDecoder.decode(unsigned, 0, 0).first)
    }

    /** 嵌套值 + 混合类型的整体解码：map / string / uint16 / boolean 一起走一遍。 */    @Test
    fun `map 与混合类型整体解码`() {
        val buf = ByteBuffer.wrap(
            byteArrayOf(
                0xE3.toByte(), // map(7) size=3
                0x41, 'a'.code.toByte(), 0x41, 'b'.code.toByte(),
                0x41, 'n'.code.toByte(), 0xA2.toByte(), 0x00, 0x01, // uint16(5) size=2 → 1
                0x41, 't'.code.toByte(), 0x01, 0x07, // boolean true（扩展类型）
            ),
        )
        val (value, next) = MmdbDecoder.decode(buf, 0, 0)
        assertEquals(mapOf("a" to "b", "n" to 1L, "t" to true), value)
        assertEquals(14L, next)
    }

    // ---------------------------------------------------------------- 辅助

    private fun assertMetadata(bucket: String, reader: MmdbReader) {
        val md = root().getAsJsonObject("metadata").getAsJsonObject(bucket)
        assertEquals("$bucket.ip_version", md["ip_version"].asInt, reader.ipVersion)
        assertEquals("$bucket.record_size", md["record_size"].asInt, reader.recordSize)
        assertEquals("$bucket.node_count", md["node_count"].asInt, reader.nodeCount)
        assertEquals("$bucket.build_epoch", md["build_epoch"].asLong, reader.buildEpoch)
        assertEquals("$bucket.database_type", md["database_type"].asString, reader.databaseType)
    }

    private fun assertBucket(bucket: String, reader: MmdbReader) {
        val entries = vectors().getAsJsonObject(bucket)
        assertTrue("向量桶 $bucket 不该为空", entries.size() > 0)
        entries.entrySet().forEach { (ip, expected) ->
            assertRecord("$bucket/$ip", expected, reader.lookup(ip))
        }
    }

    /** `expected` 为 JSON null 记作"查无记录"；否则**字段集合与每个字段值都必须一致**。 */
    private fun assertRecord(label: String, expected: JsonElement, actual: Map<String, Any?>?) {
        if (expected.isJsonNull) {
            assertNull("$label 期望查无记录", actual)
            return
        }
        assertNotNull("$label 期望有记录，实际为 null", actual)
        val record = actual!!
        val expectedObject = expected.asJsonObject
        assertEquals("$label 字段集合", expectedObject.keySet(), record.keys)
        expectedObject.entrySet().forEach { (key, value) ->
            val want = value.asAny()
            val got = record[key]
            if (want is Double) {
                val number = got as? Number
                assertNotNull("$label.$key 期望 $want，实际 $got", number)
                // 精确相等：向量由官方实现导出，坐标是 float32 拓宽来的，两侧应逐位一致
                assertEquals("$label.$key", want, number!!.toDouble(), 0.0)
            } else {
                assertEquals("$label.$key", want, got)
            }
        }
    }

    private fun JsonElement.asAny(): Any =
        if (isJsonPrimitive) {
            asJsonPrimitive.let { if (it.isNumber) it.asDouble else it.asString }
        } else {
            toString()
        }

    private fun openBoth(): Pair<MmdbReader, MmdbReader> {
        val dir = requireFixture()
        val v4 = MmdbReader.open(File(dir, V4_NAME))
        val v6 = MmdbReader.open(File(dir, V6_NAME))
        assertNotNull("IPv4 库打不开：$dir", v4)
        assertNotNull("IPv6 库打不开：$dir", v6)
        return v4!! to v6!!
    }

    private fun root(): JsonObject =
        JsonParser.parseString(File(requireFixture(), VECTORS_NAME).readText()).asJsonObject

    private fun vectors(): JsonObject = root().getAsJsonObject("vectors")

    private fun requireFixture(): File {
        val dir = fixtureDir
        assumeTrue("未找到 mmdb 对拍数据，跳过（见类注释）", dir != null)
        return dir!!
    }

    private fun resolveFixtureDir(): File? = MmdbFixtures.dir

    private fun String.quote(): String = "\"$this\""

    private companion object {
        const val V4_NAME = MmdbFixtures.V4_NAME
        const val V6_NAME = MmdbFixtures.V6_NAME
        const val VECTORS_NAME = MmdbFixtures.VECTORS_NAME
    }
}
