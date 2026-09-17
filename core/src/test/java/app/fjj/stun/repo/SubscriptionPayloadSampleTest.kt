package app.fjj.stun.repo

import android.app.Application
import android.util.Base64
import app.fjj.stun.util.ShareCryptoUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 锁住「订阅 URL 该返回什么」这份对外契约（`SubscriptionManager.parseSubscriptionPayload`
 * / `parseUsageHeader`）。
 *
 * 这里同时充当**示例文档的可执行版本**：下面的 [SAMPLE_BODY] 就是一份可以直接丢到
 * 服务器上、能被 App「订阅同步」原样吃下的最小可用响应体。改字段名/改解析分支时
 * 请先让本测试变红，再决定是不是有意破坏兼容。
 *
 * 三条容易踩的坑：
 * 1. 必须是**裸的 JSON 数组**——外面包一层 `{"nodes": [...]}` 直接不认；
 * 2. 每个节点**必须带自己稳定的 `id`**——不写 `id` 时 Gson 走 Profile 的无参构造，
 *    拿到的是随机 UUID，于是每同步一次就重复导入一遍（见 [missingIdMeansDuplicateOnResync]）；
 * 3. `sshAddr` / `proxyAddr` 至少要有其一，否则整份负载被判定为「没有有效节点」。
 *
 * 此外支持**多行 `stun://` 格式**：每行一个 `stun://<ShareCryptoUtils 加密的单节点>`，
 * 整份文件共用同一个订阅 PIN 逐行解密（见 multiLineStunUri* 用例）。非 `stun://` 行被忽略；
 * 解密失败（PIN 错/缺失）按整文件级报 pinInvalid / pinRequired，符合既有错误码契约。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SubscriptionPayloadSampleTest {

    private fun parse(body: String) = SubscriptionManager.parseSubscriptionPayload(body)

    // ── 示例响应体 ────────────────────────────────────────────────────────────

    @Test
    fun sampleBodyImportsBothNodes() {
        val result = parse(SAMPLE_BODY)
        assertFalse("明文负载不该要求 PIN", result.pinRequired)
        assertFalse(result.pinInvalid)
        assertEquals(2, result.profiles.size)
    }

    @Test
    fun sampleFieldsLandOnProfile() {
        val first = parse(SAMPLE_BODY).profiles.first()
        assertEquals("jp-tokyo-01", first.id)
        assertEquals("东京 · 01", first.name)
        assertEquals("203.0.113.10:22", first.sshAddr)
        assertEquals("stun", first.user)
        assertEquals("your-password", first.pass)
        assertEquals(Profile.AUTH_TYPE_PASSWORD, first.authType)
        assertEquals(Profile.TUNNEL_TYPE_WEBSOCKET, first.tunnelType)
        assertTrue(first.tunnelTlsEnabled)
        assertEquals("cdn.example.com:443", first.proxyAddr)
        assertEquals("cdn.example.com", first.customHost)
        assertEquals("cdn.example.com", first.serverName)
        assertTrue(first.enableCustomPath)
        assertEquals("/ws", first.customPath)
        assertEquals("示例节点 A", first.note)
    }

    @Test
    fun omittedFieldsKeepModelDefaults() {
        // 示例里没写的键必须落到 Profile 的默认值上，而不是 null（解析器随后会对它们取值）
        val defaults = Profile()
        val second = parse(SAMPLE_BODY).profiles[1]
        assertEquals(Profile.TUNNEL_TYPE_H2, second.tunnelType)
        assertEquals(defaults.customPath, second.customPath)
        assertEquals(defaults.enableCustomPath, second.enableCustomPath)
        assertFalse(second.dnsOverride)
        assertEquals(defaults.xhttpChunkSizeKB, second.xhttpChunkSizeKB)
    }

    // ── 同一份内容的其它两种合法外壳 ──────────────────────────────────────────

    @Test
    fun base64OfSameBodyIsEquivalent() {
        val encoded = Base64.encodeToString(SAMPLE_BODY.toByteArray(Charsets.UTF_8), Base64.DEFAULT)
        val direct = parse(SAMPLE_BODY).profiles
        val viaB64 = parse(encoded).profiles
        assertEquals(direct.size, viaB64.size)
        assertEquals(direct.map { it.id }, viaB64.map { it.id })
        assertEquals(direct[0].name, viaB64[0].name)
        assertEquals(direct[0].sshAddr, viaB64[0].sshAddr)
    }

    @Test
    fun base64WithSurroundingWhitespaceStillDecodes() {
        // 服务端常把 base64 折行输出；解析前会先剥掉所有空白
        val encoded = Base64.encodeToString(SAMPLE_BODY.toByteArray(Charsets.UTF_8), Base64.DEFAULT)
        val wrapped = encoded.chunked(60).joinToString("\n")
        assertEquals(2, parse(wrapped).profiles.size)
    }

    // ── 反例：这些形状不认 ────────────────────────────────────────────────────

    @Test
    fun objectWrappedArrayIsRejected() {
        // 只有裸数组能被识别；包一层 {"nodes": [...]} 会走到「没有有效节点」
        val wrapped = """{"nodes": $SAMPLE_BODY}"""
        assertEquals(0, parse(wrapped).profiles.size)
    }

    @Test
    fun missingAddressSilentlyFallsBackToModelDefault() {
        // ⚠️ 反直觉：sshAddr / proxyAddr 在 Profile 里**带默认值**，所以"没给地址"既不会
        // 报错也不会被过滤，而是静默指向默认那个演示地址。服务端漏写地址 = 节点列表里
        // 凭空多出一堆指向 185.248.33.40 的节点，且用户无从察觉。
        val noAddr = """[{"id":"a","name":"A","user":"u","pass":"p"}]"""
        val defaults = Profile()
        val parsed = parse(noAddr).profiles.single()
        assertEquals(defaults.sshAddr, parsed.sshAddr)
        assertEquals(defaults.proxyAddr, parsed.proxyAddr)
    }

    @Test
    fun emptyArrayIsRejected() {
        assertEquals(0, parse("[]").profiles.size)
        assertEquals(0, parse("").profiles.size)
    }

    @Test
    fun missingIdMeansDuplicateOnResync() {
        // 坑点 2 的可执行证据：不写 id 时每次解析都会生成不同的随机 UUID，
        // 于是同一台节点在每次同步里都被当成新节点 → 列表里越堆越多。
        val noId = """[{"name":"A","sshAddr":"203.0.113.30:22","user":"u","pass":"p"}]"""
        val first = parse(noId).profiles.single().id
        val second = parse(noId).profiles.single().id
        assertTrue("缺省 id 不该是空串（空串会被上层换成随机值）", first.isNotBlank())
        assertNotEquals("缺省 id 每次解析都不同 ⇒ 必然重复导入", first, second)
    }

    // ── 流量头 ───────────────────────────────────────────────────────────────

    @Test
    fun usageHeaderParsesToSnapshot() {
        val usage = SubscriptionManager.parseUsageHeader(SAMPLE_USERINFO_HEADER)!!
        assertEquals(1_234_567_890L, usage.upload)
        assertEquals(9_876_543_210L, usage.download)
        assertEquals(107_374_182_400L, usage.total)
        assertEquals(1_798_761_600L, usage.expire)
        assertEquals(1_234_567_890L + 9_876_543_210L, usage.used)
        assertFalse(usage.unlimited)
        assertTrue(usage.hasExpire)
        assertTrue(usage.ratio > 0.0 && usage.ratio < 1.0)
    }

    @Test
    fun usageHeaderToleratesSpacingAndCaseAndPartialFields() {
        // 大小写、空格、缺键都要能吃下（服务端实现五花八门）
        val spaced = SubscriptionManager.parseUsageHeader(" Upload=100 ;DOWNLOAD=200; Total=0 ")!!
        assertEquals(100L, spaced.upload)
        assertEquals(200L, spaced.download)
        assertTrue("total<=0 视为不限量", spaced.unlimited)
        assertEquals(0L, spaced.expire)
        assertFalse("无 expire 不该显示到期", spaced.hasExpire)

        val onlyTotal = SubscriptionManager.parseUsageHeader("total=1024")!!
        assertEquals(0L, onlyTotal.upload)
        assertEquals(1024L, onlyTotal.total)
    }

    @Test
    fun usageHeaderAllZeroOrBlankIsNoData() {
        // 全 0 / 空头等价于「没有数据」→ UI 回落「暂无用量」
        assertEquals(null, SubscriptionManager.parseUsageHeader(null))
        assertEquals(null, SubscriptionManager.parseUsageHeader(""))
        assertEquals(null, SubscriptionManager.parseUsageHeader("   "))
        assertEquals(null, SubscriptionManager.parseUsageHeader("upload=0; download=0; total=0; expire=0"))
        assertEquals(null, SubscriptionManager.parseUsageHeader("not-a-kv; =123"))
    }

    // ── 多行 stun://（每行一个单节点，整文件共用一个 PIN）────────────────────────────

    @Test
    fun multiLineStunUriImportsNodesWithFilePin() {
        val pin = "secret"
        val body = buildString {
            appendLine(stunLine("a", "198.51.100.1:22", pin))
            appendLine(stunLine("b", "198.51.100.2:22", pin))
        }
        val result = SubscriptionManager.parseSubscriptionPayload(body, pin)
        assertFalse("带正确 PIN 不该要求/报错", result.pinRequired)
        assertFalse(result.pinInvalid)
        assertEquals(2, result.profiles.size)
        assertEquals("198.51.100.1:22", result.profiles.first().sshAddr)
    }

    @Test
    fun multiLineStunUriWrongPinReportsInvalid() {
        val body = buildString {
            appendLine(stunLine("a", "198.51.100.1:22", "secret"))
            appendLine(stunLine("b", "198.51.100.2:22", "secret"))
        }
        val result = SubscriptionManager.parseSubscriptionPayload(body, "wrong-pin")
        assertFalse(result.pinRequired)
        assertTrue("整文件 PIN 错误应报 pinInvalid", result.pinInvalid)
        assertEquals(0, result.profiles.size)
    }

    @Test
    fun multiLineStunUriMissingPinReportsRequired() {
        val body = buildString {
            appendLine(stunLine("a", "198.51.100.1:22", "secret"))
        }
        val result = SubscriptionManager.parseSubscriptionPayload(body, null)
        assertTrue("未提供 PIN 应报 pinRequired", result.pinRequired)
        assertFalse(result.pinInvalid)
        assertEquals(0, result.profiles.size)
    }

    @Test
    fun multiLineStunUriIgnoresNonStunLines() {
        // 决策 1：只认 stun:// 行；裸 JSON 数组那行被忽略
        val pin = "secret"
        val stunPart = stunLine("a", "198.51.100.1:22", pin)
        val plainArray = """[{"id":"plain","name":"plain","sshAddr":"198.51.100.9:22","user":"u","pass":"p"}]"""
        val body = "$stunPart\n$plainArray"
        val result = SubscriptionManager.parseSubscriptionPayload(body, pin)
        assertEquals(1, result.profiles.size)
        assertEquals("a", result.profiles.first().id)
    }

    @Test
    fun multiLineStunUriMixedValidAndGarbageStillImportsValid() {
        val pin = "secret"
        val body = buildString {
            appendLine(stunLine("a", "198.51.100.1:22", pin))
            appendLine("stun://not-a-valid-encrypted-blob")
        }
        val result = SubscriptionManager.parseSubscriptionPayload(body, pin)
        assertEquals(1, result.profiles.size)
        assertEquals("a", result.profiles.first().id)
    }

    private fun nodeJson(id: String, sshAddr: String) =
        """{"id":"$id","name":"$id","sshAddr":"$sshAddr","user":"u","pass":"p","tunnelType":"h2"}"""

    private fun stunLine(id: String, sshAddr: String, pin: String) =
        "stun://" + ShareCryptoUtils.encrypt(nodeJson(id, sshAddr), pin)

    private companion object {
        /** 与 `.workbuddy/tmp/subscription-sample/nodes.json` 逐字节相同。 */
        val SAMPLE_BODY = """
            [
              {
                "id": "jp-tokyo-01",
                "name": "东京 · 01",
                "sshAddr": "203.0.113.10:22",
                "user": "stun",
                "pass": "your-password",
                "authType": "password",
                "tunnelType": "websocket",
                "tunnelTlsEnabled": true,
                "proxyAddr": "cdn.example.com:443",
                "customHost": "cdn.example.com",
                "serverName": "cdn.example.com",
                "enableCustomPath": true,
                "customPath": "/ws",
                "alpn": "h2,http/1.1",
                "dnsOverride": false,
                "note": "示例节点 A"
              },
              {
                "id": "sg-singapore-01",
                "name": "新加坡 · 01",
                "sshAddr": "203.0.113.20:22",
                "user": "stun",
                "pass": "your-password",
                "authType": "password",
                "tunnelType": "h2",
                "tunnelTlsEnabled": true,
                "proxyAddr": "203.0.113.20:443",
                "verifyFingerprint": false,
                "note": "示例节点 B"
              }
            ]
        """.trimIndent()

        /** 与 `.workbuddy/tmp/subscription-sample/response-headers.txt` 中的示例一致。 */
        const val SAMPLE_USERINFO_HEADER =
            "upload=1234567890; download=9876543210; total=107374182400; expire=1798761600"
    }
}
