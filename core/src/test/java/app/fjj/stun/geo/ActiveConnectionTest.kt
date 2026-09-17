package app.fjj.stun.geo

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ActiveConnections.parse] 与 [hostOf] 的回归测试。
 *
 * 重点全在**退化路径**上：地球是装饰件，Go 侧改字段、某个条目烂掉、或者整段 JSON 被截断，
 * 正确行为都只能是"少画几个点"，绝不能把连接详情面板打崩。
 */
class ActiveConnectionTest {

    // ------------------------------------------------------------------ parse：正常路径

    @Test
    fun `解析Go导出的活跃连接JSON`() {
        val json = """
            [
              {"id":1,"target_addr":"142.250.72.14:443","target_host":"142.250.72.14",
               "proxy_addr":"185.248.33.40:443","start_time":"2026-09-15T21:00:00.123456789+08:00",
               "read_bytes":2048,"write_bytes":512},
              {"id":2,"target_addr":"104.244.42.1:443","target_host":"104.244.42.1",
               "proxy_addr":"185.248.33.40:443","start_time":"2026-09-15T21:01:02.5Z",
               "read_bytes":0,"write_bytes":1024}
            ]
        """.trimIndent()

        val list = ActiveConnections.parse(json)

        assertEquals(2, list.size)

        val first = list[0]
        assertEquals(1L, first.id)
        assertEquals("142.250.72.14:443", first.targetAddr)
        assertEquals("142.250.72.14", first.host)
        assertEquals("185.248.33.40:443", first.proxyAddr)
        assertEquals(2048L, first.readBytes)
        assertEquals(512L, first.writeBytes)
        assertEquals(2560L, first.totalBytes)
        // +08:00 必须被算进去：同一个瞬间按 UTC 是 13:00:00.123456789。
        assertEquals(Instant.parse("2026-09-15T13:00:00.123456789Z").toEpochMilli(), first.startedAtMillis)

        val second = list[1]
        assertEquals(2L, second.id)
        assertEquals(Instant.parse("2026-09-15T21:01:02.500Z").toEpochMilli(), second.startedAtMillis)
        assertEquals(1024L, second.totalBytes)
    }

    @Test
    fun `target_host 为空时回退到 target_addr 里切出来的 host`() {
        // UDP 那条路径（WrapPacketConn）在某些入参下就是切不动端口从而留空 target_host，
        // 这时候必须能从 target_addr 兜回来，否则整条活跃连接会从地球上消失。
        val json = """[{"id":7,"target_addr":"[2400:3200::1]:443","target_host":""}]"""

        val list = ActiveConnections.parse(json)

        assertEquals(1, list.size)
        assertEquals("2400:3200::1", list[0].host)
    }

    // ------------------------------------------------------------------ parse：退化路径

    @Test
    fun `空输入与坏格式都退化成空列表`() {
        assertTrue(ActiveConnections.parse(null).isEmpty())
        assertTrue(ActiveConnections.parse("").isEmpty())
        assertTrue(ActiveConnections.parse("   ").isEmpty())
        assertTrue(ActiveConnections.parse("[]").isEmpty())
        // 下面这些一旦抛出异常，就会顺着 onDraw 一路冒到面板上，所以必须逐个钉住。
        assertTrue(ActiveConnections.parse("null").isEmpty())
        assertTrue(ActiveConnections.parse("{").isEmpty())
        assertTrue(ActiveConnections.parse("not json").isEmpty())
        assertTrue(ActiveConnections.parse("""{"connections":[]}""").isEmpty())
        assertTrue(ActiveConnections.parse("""[{"id":1},""").isEmpty())
    }

    @Test
    fun `单条脏数据只丢它自己`() {
        val json = """
            [
              {"id":9,"target_addr":"1.2.3.4:80","target_host":"1.2.3.4","proxy_addr":"p",
               "start_time":"bogus","read_bytes":"not-a-number","write_bytes":7},
              "just a string",
              null,
              {},
              {"id":10,"target_addr":"[2400:3200::1]:443","start_time":"2026-09-15T00:00:00Z",
               "read_bytes":5,"write_bytes":5}
            ]
        """.trimIndent()

        val list = ActiveConnections.parse(json)

        // `just a string` / `null` / `{}` 都没有可用的目标主机 → 丢弃；有效条目原样留下。
        assertEquals(2, list.size)

        val dirty = list[0]
        assertEquals(9L, dirty.id)
        assertEquals("1.2.3.4", dirty.host)
        // 时间戳烂了只记 0（不影响排序以外的东西），脏的字节数记 0，好的那个照常读。
        assertEquals(0L, dirty.startedAtMillis)
        assertEquals(0L, dirty.readBytes)
        assertEquals(7L, dirty.writeBytes)

        assertEquals(10L, list[1].id)
        assertEquals("2400:3200::1", list[1].host)
        assertEquals(10L, list[1].totalBytes)
    }

    @Test
    fun `Go零值时间归零而不是变成负数`() {
        // Go 未设置的时间会序列化成 0001-01-01T00:00:00Z，直接解 epoch 会得到一个大负数，
        // 下游看到"连接建立于公元 1 年"会排出很奇怪的顺序。
        val json = """[{"id":1,"target_addr":"1.2.3.4:80","start_time":"0001-01-01T00:00:00Z"}]"""

        assertEquals(0L, ActiveConnections.parse(json)[0].startedAtMillis)
    }

    @Test
    fun `条目数超上限时截断而不是全丢`() {
        val builder = StringBuilder("[")
        repeat(5_000) { index ->
            if (index > 0) builder.append(',')
            builder.append("""{"id":$index,"target_addr":"10.0.0.${index % 256}:80"}""")
        }
        builder.append(']')

        val list = ActiveConnections.parse(builder.toString())

        // 上限是 4096：病态输入不至于把内存吃满，正常量级（几十条）完全够用。
        assertEquals(4096, list.size)
        assertEquals(0L, list[0].id)
    }

    @Test
    fun `整段超长时直接放弃_不截断出半截JSON`() {
        // 曾经的实现把超长输入截断到 N 个字符再解析 —— 结果 JSON 语法必然残缺，
        // Gson 一个条目都解不出来，等于把"病态输入"伪装成了"没有活跃连接"。
        // 现在改成明确放弃，结果一样是空，但语义诚实。
        val oversize = "[" + "1".repeat(1 shl 20) + "]"
        assertTrue(ActiveConnections.parse(oversize).isEmpty())

        // 边界另一侧：刚好卡在上限内的正常载荷必须照常解析出来。
        val builder = StringBuilder("[")
        repeat(4_000) { index ->
            if (index > 0) builder.append(',')
            builder.append("""{"id":$index,"target_addr":"10.0.0.${index % 256}:80"}""")
        }
        builder.append(']')
        assertEquals(4_000, ActiveConnections.parse(builder.toString()).size)
    }

    // ------------------------------------------------------------------ hostOf

    @Test
    fun `hostOf 认全三种地址形态`() {
        // 带端口
        assertEquals("185.248.33.40", hostOf("185.248.33.40:443"))
        assertEquals("hk1.example.com", hostOf("hk1.example.com:443"))
        // 不带端口
        assertEquals("185.248.33.40", hostOf("185.248.33.40"))
        assertEquals("hk1.example.com", hostOf("hk1.example.com"))
        // 裸 IPv6：冒号是地址的一部分，不能当端口切
        assertEquals("2400:3200::1", hostOf("2400:3200::1"))
        // 带方括号的 IPv6：必须靠括号定位
        assertEquals("2400:3200::1", hostOf("[2400:3200::1]:22"))
        assertEquals("2400:3200::1", hostOf("[2400:3200::1]"))
        // 空白与退化输入
        assertEquals("", hostOf(""))
        assertEquals("", hostOf("   "))
        assertEquals("", hostOf(":443"))
        assertEquals("185.248.33.40", hostOf("  185.248.33.40:443  "))
    }

    @Test
    fun `两种来源都取不到主机时条目被丢弃`() {
        // 空 host 不是异常，而是「这条连接画不到地球上」的正常表达：
        // 与其在下游到处判空，不如在这里就丢掉。
        val list = ActiveConnections.parse("""[{"id":1,"target_addr":"","target_host":""}]""")
        assertTrue(list.isEmpty())
    }
}
