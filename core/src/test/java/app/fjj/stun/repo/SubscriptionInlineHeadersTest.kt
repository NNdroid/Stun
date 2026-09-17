package app.fjj.stun.repo

import android.app.Application
import android.util.Base64
import app.fjj.stun.util.ShareCryptoUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 锁住「订阅文件内自带响应头声明」这套语法：
 *
 * ```
 * #content-disposition: attachment; filename="七星.yaml"
 * #profile-web-page-url: https://example.com
 * #profile-update-interval: 12
 * #subscription-userinfo: upload=1; download=2; total=100; expire=0
 * [ {"id":"a", "name":"A", "sshAddr":"203.0.113.1:22"} ]
 * ```
 *
 * 需求场景：CDN / Gist / raw 文件这类静态托管**没法自定义响应头**，所以把订阅名、
 * 首页、更新间隔、流量额度写进文件正文，让客户端自己认。定位是「补充」，
 * 因此**真响应头优先**（见 [SubscriptionManager.resolveHeaderMeta]）。
 *
 * 这套语法唯一的硬约束是「只认行首（允许前导空白）的 `#`」—— 这是三种正文外壳
 * （裸 JSON / Base64 / PIN 加密）都不会出现的字符组合，所以剥行对它们都安全。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SubscriptionInlineHeadersTest {

    private fun parse(body: String) = SubscriptionManager.parseInlineHeaders(body)

    // ── 基本语法 ──────────────────────────────────────────────────────────────

    @Test
    fun 单条声明被认出_正文剥净() {
        val inline = parse("#content-disposition: attachment; filename=\"七星.yaml\"\n$NODES")

        assertEquals("attachment; filename=\"七星.yaml\"", inline.headers["content-disposition"])
        assertEquals(NODES, inline.body)
        // 端到端：订阅名会走既有的 content-disposition 解析（含 .yaml 后缀剥离）
        assertEquals("七星", SubscriptionManager.parseContentDisposition(inline.headers["content-disposition"]))
    }

    @Test
    fun 多行声明一行算一个_位置任意() {
        val body = buildString {
            append("#content-disposition: attachment; filename=\"A\"\n")
            append("#profile-web-page-url: https://example.com\n")
            append(NODES)
            append("\n#profile-update-interval: 12\n")
        }
        val inline = parse(body)

        assertEquals(3, inline.headers.size)
        assertEquals("https://example.com", inline.headers["profile-web-page-url"])
        assertEquals("12", inline.headers["profile-update-interval"])
        assertEquals(NODES, inline.body)
    }

    @Test
    fun 井号后有无空格等价_头名大小写不敏感() {
        val a = parse("#content-disposition: attachment; filename=\"X\"\n$NODES")
        val b = parse("#  Content-Disposition : attachment; filename=\"X\"\n$NODES")
        val c = parse("   #CONTENT-DISPOSITION: attachment; filename=\"X\"\n$NODES")

        assertEquals(a.headers, b.headers)
        assertEquals(a.headers, c.headers)
        assertEquals(a.body, b.body)
        // 值也做了 trim
        assertEquals("attachment; filename=\"X\"", c.headers["content-disposition"])
    }

    @Test
    fun 前导空白与缩进的声明也认() {
        val inline = parse("\t  #profile-update-interval:   6  \n$NODES")
        assertEquals("6", inline.headers["profile-update-interval"])
    }

    @Test
    fun CRLF换行同样工作() {
        val body = "#content-disposition: attachment; filename=\"Win\"\r\n$NODES"
        val inline = parse(body)
        assertEquals("attachment; filename=\"Win\"", inline.headers["content-disposition"])
        assertEquals(NODES, inline.body)
    }

    // ── 容错：不认识的 `#` 行一律当注释，绝不报错 ─────────────────────────────

    @Test
    fun 无冒号或空头名的行当注释丢掉() {
        val body = "# 这是七星机场的订阅\n#\n#:带空头名\n#普通注释\n$NODES"
        val inline = parse(body)
        assertTrue(inline.headers.isEmpty())
        assertEquals(NODES, inline.body)
    }

    @Test
    fun 未知头名当注释丢掉_便于以后加头而不炸老版本() {
        val body = "#x-custom-thing: hi\n#server-info: nginx\n$NODES"
        val inline = parse(body)
        assertTrue("未知头名不该进 headers", inline.headers.isEmpty())
        assertEquals("未知头名那一行也要从正文剥掉", NODES, inline.body)
    }

    @Test
    fun 同名声明以第一条为准() {
        val body = "#profile-update-interval: 6\n#profile-update-interval: 99\n$NODES"
        assertEquals("6", parse(body).headers["profile-update-interval"])
    }

    @Test
    fun 空值视同未声明() {
        val body = "#content-disposition:\n#profile-update-interval:    \n$NODES"
        val inline = parse(body)
        assertTrue("空值当成没写，交给沿用已存值", inline.headers.isEmpty())
    }

    // ── 不能误伤正常正文 ──────────────────────────────────────────────────────

    @Test
    fun 没有声明时正文逐字节原样返回() {
        // 关键：没有 `#` 行时连重新拼接都不做，原始换行/缩进一字不改
        val messy = "[\n\t{\"id\":\"a\",\n     \"name\":\"A\",\n\"sshAddr\":\"203.0.113.1:22\"}\n]\n\n"
        val inline = parse(messy)
        assertTrue(inline.headers.isEmpty())
        assertEquals(messy, inline.body)
    }

    @Test
    fun 空正文安全() {
        assertEquals("", parse("").body)
        assertTrue(parse("").headers.isEmpty())
        assertTrue(parse("   ").headers.isEmpty())
    }

    @Test
    fun JSON字符串值里的井号不被误伤() {
        val withHash = """[{"id":"a","name":"机场#1 主线路","sshAddr":"203.0.113.1:22"}]"""
        val body = "#content-disposition: attachment; filename=\"H\"\n$withHash"

        val inline = parse(body)
        assertEquals("值里的 # 不在行首，不该被当声明", 1, inline.headers.size)
        val parsed = SubscriptionManager.parseSubscriptionPayload(inline.body)
        assertEquals("机场#1 主线路", parsed.profiles.single().name)
    }

    // ── 与三种正文外壳共存 ────────────────────────────────────────────────────

    @Test
    fun 声明后的裸JSON正文仍能导入() {
        val parsed = SubscriptionManager.parseSubscriptionPayload(parse("#note: x\n$NODES").body)
        assertEquals(1, parsed.profiles.size)
        assertEquals("A", parsed.profiles[0].name)
    }

    @Test
    fun 声明后的Base64正文仍能解析() {
        val b64 = Base64.encodeToString(NODES.toByteArray(Charsets.UTF_8), Base64.DEFAULT)
        val inline = parse("#content-disposition: attachment; filename=\"B64\"\n$b64")

        assertEquals("attachment; filename=\"B64\"", inline.headers["content-disposition"])
        assertEquals(1, SubscriptionManager.parseSubscriptionPayload(inline.body).profiles.size)
    }

    @Test
    fun 声明后的PIN加密正文仍能解析() {
        val encrypted = ShareCryptoUtils.encrypt(NODES, "1234")!!
        val inline = parse("#content-disposition: attachment; filename=\"Secret\"\n$encrypted")

        assertEquals("attachment; filename=\"Secret\"", inline.headers["content-disposition"])
        // 无 PIN → 该报「需要 PIN」，说明剥行没有破坏加密负载的识别
        assertTrue(SubscriptionManager.parseSubscriptionPayload(inline.body).pinRequired)
        // 带 PIN → 正常解出节点
        val ok = SubscriptionManager.parseSubscriptionPayload(inline.body, "1234")
        assertEquals(1, ok.profiles.size)
        assertEquals("A", ok.profiles[0].name)
    }

    @Test
    fun 声明可以带subscription_userinfo() {
        val inline = parse("#subscription-userinfo: upload=1; download=2; total=100; expire=0\n$NODES")
        val usage = SubscriptionManager.parseUsageHeader(inline.headers["subscription-userinfo"])!!
        assertEquals(3L, usage.used)
        assertEquals(100L, usage.total)
    }

    // ── 优先级：真响应头 > 文件内声明 > 上次已存值 ────────────────────────────

    @Test
    fun 响应头优先_文件内声明补缺() {
        // 真头给了名字与主页，文件补了更新间隔 → 合并后三项都有
        val inline = parse(
            "#content-disposition: attachment; filename=\"File\"\n" +
                "#profile-web-page-url: https://file.example\n" +
                "#profile-update-interval: 12\n" + NODES
        )
        val meta = SubscriptionManager.resolveHeaderMeta(
            httpName = "Http",
            httpHomePage = "https://http.example",
            httpIntervalHours = 0,
            inline = inline
        )
        assertEquals("Http", meta.name)
        assertEquals("https://http.example", meta.homePage)
        assertEquals(12, meta.intervalHours)
    }

    @Test
    fun 逐项合并_响应头只给一半另一半用文件() {
        val inline = parse("#content-disposition: attachment; filename=\"File\"\n#profile-update-interval: 6\n$NODES")
        val meta = SubscriptionManager.resolveHeaderMeta("", "", 0, inline)
        assertEquals("File", meta.name)
        assertEquals(6, meta.intervalHours)
        assertEquals("文件没声明主页 ⇒ 留空，交给沿用已存值", "", meta.homePage)
    }

    @Test
    fun 两者都没有则全空() {
        val meta = SubscriptionManager.resolveHeaderMeta("", "", 0, parse(NODES))
        assertEquals("", meta.name)
        assertEquals("", meta.homePage)
        assertEquals(0, meta.intervalHours)
    }

    @Test
    fun 响应头未给主页时用文件声明() {
        // 注意契约：syncSingle 会先把响应头里的主页过一遍 isValidWebUrl（无协议值置空），
        // 所以进到 resolveHeaderMeta 的 httpHomePage 已经是"校验过的值"。这里喂空串，
        // 断言的就是"上游没给出可用值时，文件声明接上"。
        val inline = parse("#profile-web-page-url: https://file.example\n$NODES")
        val meta = SubscriptionManager.resolveHeaderMeta("", "", 0, inline)
        assertEquals("https://file.example", meta.homePage)
    }

    @Test
    fun 文件里的无效homepage同样不算数() {
        val inline = parse("#profile-web-page-url: example.com\n$NODES")
        assertEquals("", SubscriptionManager.resolveHeaderMeta("", "", 0, inline).homePage)
    }

    @Test
    fun 响应头间隔为0视同未给_用文件声明() {
        val inline = parse("#profile-update-interval: 6\n$NODES")
        assertEquals(6, SubscriptionManager.resolveHeaderMeta("", "", 0, inline).intervalHours)
        assertEquals("真头给了有效值就用真头", 24, SubscriptionManager.resolveHeaderMeta("", "", 24, inline).intervalHours)
    }

    @Test
    fun 流量快照_响应头优先_无效时回落文件声明() {
        val inline = parse("#subscription-userinfo: upload=10; download=20; total=100\n$NODES")

        val fromHttp = SubscriptionManager.resolveUsage("upload=1; download=2; total=100", inline)!!
        assertEquals(3L, fromHttp.used)
        assertEquals("响应头存在但解析不出有效值 ⇒ 用文件声明", 30L,
            SubscriptionManager.resolveUsage("upload=0; download=0; total=0; expire=0", inline)!!.used)
        // 两者都没有 ⇒ null ⇒ 不动已存快照、UI 显示「暂无用量」
        assertNull(SubscriptionManager.resolveUsage(null, parse(NODES)))
        assertNull(SubscriptionManager.resolveUsage("  ", parse(NODES)))
    }

    private companion object {
        /** 最小的合法正文（一个节点），用来验证"声明被剥、正文不动"。 */
        const val NODES = """[{"id":"a","name":"A","sshAddr":"203.0.113.1:22"}]"""

        /**
         * 从工作目录逐级向上找交付的示例文件（Gradle 跑 core 测试时 cwd 是 `core/`）。
         * 找不到就 assumeTrue 跳过——示例文件在 `.workbuddy/tmp/` 下，不进版本库。
         */
        fun locateSample(): java.io.File? {
            var dir: java.io.File? = java.io.File("").absoluteFile
            repeat(5) {
                val f = java.io.File(dir, ".workbuddy/tmp/subscription-sample/sub-with-inline-headers.txt")
                if (f.isFile) return f
                dir = dir?.parentFile
            }
            return null
        }
    }

    /**
     * 交付给用户的示例文件本身必须能被解析 —— 否则"文档说能这么写"与实际行为脱节。
     * 示例文件不在库里，找不到就跳过。
     */
    @Test
    fun 交付的示例文件本身能被解析() {
        val file = locateSample()
        org.junit.Assume.assumeTrue("未找到示例文件，跳过", file != null)
        val inline = parse(file!!.readText())

        // 四条声明全部认出，且正文里只剩节点数组
        assertEquals(4, inline.headers.size)
        assertEquals("attachment; filename=\"七星机场.json\"", inline.headers["content-disposition"])
        assertEquals("七星机场", SubscriptionManager.parseContentDisposition(inline.headers["content-disposition"]))
        assertEquals("https://example.com", inline.headers["profile-web-page-url"])
        assertEquals("12", inline.headers["profile-update-interval"])
        assertEquals(11_111_111_100L, SubscriptionManager.resolveUsage(null, inline)!!.used)

        val parsed = SubscriptionManager.parseSubscriptionPayload(inline.body)
        assertEquals(listOf("jp-tokyo-01", "sg-singapore-01"), parsed.profiles.map { it.id })
        assertEquals("东京 · 01", parsed.profiles[0].name)
    }
}
