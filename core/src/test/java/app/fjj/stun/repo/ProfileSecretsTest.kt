package app.fjj.stun.repo

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP / 只读出口的凭据掩码回归。
 *
 * 为什么值得单独钉住：这些断言对应的失败模式全都是**静默**的 —— 少掩一个字段不会有编译
 * 错误，也不会有别的测试发现，只是真实的 SSH 密码 / PEM 私钥进了 AI 客户端的会话上下文、
 * 聊天记录和日志。
 */
class ProfileSecretsTest {

    private val gson = ProfileSecrets.redactingGson()

    /** 每个凭据字段都填上可识别的假值，便于断言"原值没漏出去"。 */
    private fun profileWithSecrets() = Profile(
        id = "p1",
        name = "node",
        user = "root",
        pass = "s3cr3t-password",
        privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----",
        keyPass = "key-passphrase",
        proxyAuthToken = "proxy-token-123",
        proxyAuthPass = "proxy-pass",
        icmpCustomPsk = "icmp-psk",
        udpCustomPsk = "udp-psk",
        dnsTunnelPsk = "dns-psk",
        kcpPassword = "kcp-pass",
        noisePublicKey = "noise-pubkey",
        serverFingerprint = "SHA256:abcdef",
        proxyAuthUser = "proxy-user"
    )

    @Test
    fun `serializing a profile masks every credential field`() {
        val json = gson.toJson(profileWithSecrets())
        ProfileSecrets.SECRET_FIELDS.forEach { field ->
            assertTrue(
                "字段 $field 应被掩码，实际输出: $json",
                json.contains("\"$field\":\"${ProfileSecrets.MASK}\"")
            )
        }
    }

    @Test
    fun `serializing a profile never leaks the raw secrets`() {
        val json = gson.toJson(profileWithSecrets())
        listOf(
            "s3cr3t-password", "BEGIN OPENSSH PRIVATE KEY", "key-passphrase",
            "proxy-token-123", "proxy-pass", "icmp-psk", "udp-psk", "dns-psk", "kcp-pass"
        ).forEach { secret ->
            assertFalse("凭据 $secret 不得出现在输出里: $json", json.contains(secret))
        }
    }

    @Test
    fun `list serialization masks as well`() {
        val json = gson.toJson(listOf(profileWithSecrets(), profileWithSecrets()))
        assertFalse("List<Profile> 走的是同一个 adapter，不得漏明文", json.contains("s3cr3t-password"))
        assertTrue(json.contains(ProfileSecrets.MASK))
    }

    /**
     * 空串保持空串，不能统一换成 `*****`：调用方要能区分"这个节点没设密码"
     * 与"设了但不给我看"，否则 UI/AI 会把未配置的节点报成已配置。
     */
    @Test
    fun `empty credentials stay empty so not-set is distinguishable from hidden`() {
        val json = gson.toJson(Profile(id = "p2", name = "no-auth", pass = "", kcpPassword = ""))
        assertTrue(json.contains("\"pass\":\"\""))
        assertTrue(json.contains("\"kcpPassword\":\"\""))
    }

    /** 公钥 / 指纹 / 用户名不是机密，掩掉会破坏调用方的比对与展示。 */
    @Test
    fun `non-credential fields are left untouched`() {
        val json = gson.toJson(profileWithSecrets())
        assertTrue(json.contains("\"noisePublicKey\":\"noise-pubkey\""))
        assertTrue(json.contains("\"serverFingerprint\":\"SHA256:abcdef\""))
        assertTrue(json.contains("\"proxyAuthUser\":\"proxy-user\""))
        assertTrue(json.contains("\"user\":\"root\""))
    }

    /**
     * 双向契约的写入侧：`get_profile_detail` 读到 `*****` 后原样回写 `update_profile`
     * 是 AI 客户端的常见工作流，必须落成"保持原值"，否则真实密码被覆盖成五个星号
     * —— 节点连不上，而用户从界面上看不出原因。
     */
    @Test
    fun `masked arguments are dropped so read-then-write cannot destroy credentials`() {
        val args = JsonObject().apply {
            addProperty("profileId", "p1")
            addProperty("pass", ProfileSecrets.MASK)
            addProperty("kcpPassword", "real-kcp-pass")
            addProperty("name", "renamed")
        }
        ProfileSecrets.dropMaskedSecrets(args)
        assertFalse("掩码值必须被剔除", args.has("pass"))
        assertEquals("真值必须保留", "real-kcp-pass", args.get("kcpPassword").asString)
        assertEquals("非凭据字段不受影响", "renamed", args.get("name").asString)
        assertTrue(args.has("profileId"))
    }

    @Test
    fun `maskInPlace ignores empty and non-string values`() {
        val json = JsonObject().apply {
            addProperty("pass", "")
            addProperty("proxyAuthToken", 42)
        }
        ProfileSecrets.maskInPlace(json)
        assertEquals("空串不能被掩码", "", json.get("pass").asString)
        assertEquals("非字符串原样保留", 42, json.get("proxyAuthToken").asInt)
    }

    @Test
    fun `isMask only recognises the exact sentinel`() {
        assertTrue(ProfileSecrets.isMask(ProfileSecrets.MASK))
        assertFalse(ProfileSecrets.isMask("****"))
        assertFalse(ProfileSecrets.isMask(""))
        assertFalse(ProfileSecrets.isMask(null))
    }

    /**
     * WebUI 那条链路用的是 Map 形态的参数（ktor `call.receive<Map<String, Any?>>()`）。
     *
     * 掩码必须落成 `null`，下游 `(body["pass"] as? String) ?: existing.pass` 才会回落到原值；
     * 而**空串必须原样保留** —— 空串是用户"把这个密码清掉"的表达，被误判成"没改"就永远清不掉。
     */
    @Test
    fun `map arguments turn masks into null but keep empty strings`() {
        val body = mutableMapOf<String, Any?>(
            "id" to "p1",
            "pass" to ProfileSecrets.MASK,
            "kcpPassword" to "",
            "proxyAuthToken" to "real-token",
            "name" to "renamed"
        )
        ProfileSecrets.dropMaskedSecrets(body)
        assertNull("掩码必须变成 null，否则会把密码写成星号", body["pass"])
        assertEquals("空串代表清空意图，必须原样保留", "", body["kcpPassword"])
        assertEquals("真值原样保留", "real-token", body["proxyAuthToken"])
        assertEquals("非凭据字段不受影响", "renamed", body["name"])
        assertEquals("p1", body["id"])
    }

    /**
     * 端到端契约：WebUI 的"打开编辑页 → 什么都不改 → 保存"不得损坏凭据。
     *
     * 这里的赋值表达式与 `WebServer` 的 `/api/profiles/update` 保持一致（`?: existing.x`），
     * 锁的是"掩码 → null → 回落原值"这条链。它是这次改动的存在理由：只要有一环断掉，
     * 用户改个节点名就会把密码写成 `*****`。
     */
    @Test
    fun `webui read-then-write round trip keeps the original secrets`() {
        val existing = Profile(
            id = "p1",
            name = "node",
            pass = "real-ssh-pass",
            kcpPassword = "real-kcp-pass",
            udpCustomPsk = "real-udp-psk"
        )
        // 前端把 GET /api/profiles 拿到的掩码原样提交回来
        val body = mutableMapOf<String, Any?>(
            "id" to "p1",
            "name" to "renamed",
            "pass" to ProfileSecrets.MASK,
            "kcpPassword" to ProfileSecrets.MASK,
            "udpCustomPsk" to ProfileSecrets.MASK
        )
        ProfileSecrets.dropMaskedSecrets(body)

        val updated = existing.copy(
            name = (body["name"] as? String)?.trim() ?: existing.name,
            pass = (body["pass"] as? String) ?: existing.pass,
            kcpPassword = (body["kcpPassword"] as? String) ?: existing.kcpPassword,
            udpCustomPsk = (body["udpCustomPsk"] as? String) ?: existing.udpCustomPsk
        )

        assertEquals("real-ssh-pass", updated.pass)
        assertEquals("real-kcp-pass", updated.kcpPassword)
        assertEquals("real-udp-psk", updated.udpCustomPsk)
        assertEquals("renamed", updated.name)
    }

    /** ktor 的 ContentNegotiation 用的是框架内部的 Gson，必须能挂上同一套脱敏。 */
    @Test
    fun `registerRedaction applies the same masking to an external GsonBuilder`() {
        val external = ProfileSecrets.registerRedaction(GsonBuilder()).create()
        val json = external.toJson(profileWithSecrets())
        assertFalse("外部 Gson 也不得漏明文: $json", json.contains("s3cr3t-password"))
        assertTrue(json.contains("\"pass\":\"${ProfileSecrets.MASK}\""))
    }
}
