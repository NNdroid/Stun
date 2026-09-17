package app.fjj.stun.remote

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * WebUI ↔ 服务端的**机械 parity 测试**：JS 编辑器发出的每个字段，服务端
 * `/api/profiles/update` 都必须受理。
 *
 * 背景（2026-09-15 parity 审计）：JS payload 里有 8 个字段服务端不受理、静默丢弃 ——
 * 其中 `tunnelTlsEnabled` 丢了会存出「raw+TLS 但 proxy_addr 空」的自相矛盾配置，
 * `icmpCustomPsk` 丢了会被回退成 SSH 密码（碰巧能连，最难发现）。
 *
 * 本测试从 `assets/web/app.js` 里机械抽取 `const payload = { ... }` 的键集合，
 * 与 [ACCEPTED_FIELDS]（`WebServer.kt` copy(...) 受理集的镜像）对比：
 * 谁再加字段忘了改另一边，这条就红。ACCEPTED_FIELDS 是手抄镜像 —— 改 WebServer 时**必须**同步改这里。
 */
class WebUiPayloadParityTest {

    private fun appJs(): File {
        val candidates = listOf(
            File("src/main/assets/web/app.js"),
            File("core/src/main/assets/web/app.js"),
            File("app/src/main/assets/web/app.js"),
        )
        val found = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 app.js：尝试过 ${candidates.map { it.absolutePath }}")
        return found
    }

    /** 抽取 submitEditProfile 里 `const payload = { ... };` 的键（全为 shorthand 标识符）。 */
    private fun payloadKeys(js: String): Set<String> {
        val start = js.indexOf("const payload = {")
        if (start < 0) fail("app.js 里找不到 payload 组装块")
        val end = js.indexOf("};", start)
        if (end < 0) fail("payload 块没有闭合")
        val block = js.substring(start, end)
        return Regex("[A-Za-z_][A-Za-z0-9_]*").findAll(block)
            .map { it.value }
            .filter { it != "const" && it != "payload" }
            .toSet()
    }

    @Test
    fun `JS 发出的每个字段服务端都必须受理`() {
        val keys = payloadKeys(appJs().readText())
        assertTrue("app.js payload 一个键都没抽到，抽取逻辑坏了", keys.size > 50)
        val unknown = keys - ACCEPTED_FIELDS
        assertTrue(
            "app.js payload 发了服务端不受理的字段（会被静默丢弃）: $unknown —— " +
                "去 WebServer.kt /api/profiles/update 补 copy(...) 受理，并同步更新 ACCEPTED_FIELDS",
            unknown.isEmpty(),
        )
    }

    @Test
    fun `上轮漂移的 8 个字段必须保持被受理`() {
        val missing = PARITY_REGRESSION_FIELDS - ACCEPTED_FIELDS
        assertTrue(
            "这 8 个字段曾在 WebUI 静默丢失（审计 2026-09-15），ACCEPTED_FIELDS 里不能少了: $missing",
            missing.isEmpty(),
        )
    }

    /**
     * `getElementById('字面量')` 引用的元素 id 必须在 index.html 或 JS 自己生成的
     * 模板字符串里存在。钉住的是 kcp-nodelay 那类事故：控件从 index.html 删了、
     * openEditModal 里的赋值没删 → `null.checked =` 抛 TypeError，弹窗后半段字段全部不回填。
     */
    @Test
    fun `app_js 字面量元素引用不能是死链`() {
        val htmlCandidates = listOf(
            File("src/main/assets/web/index.html"),
            File("core/src/main/assets/web/index.html"),
            File("app/src/main/assets/web/index.html"),
        )
        val html = htmlCandidates.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("找不到 index.html：尝试过 ${htmlCandidates.map { it.absolutePath }}")
        val js = appJs().readText()
        val declared = Regex("""id="([^"{}]+)"""").findAll(html).map { it.groupValues[1] }.toMutableSet()
        // JS 用模板字符串动态注入的 id（innerHTML 里的 id="..."）也算已声明。
        Regex("""id="([^"{}]+)"""").findAll(js).forEach { declared += it.groupValues[1] }
        val referenced = Regex("""getElementById\('([^']+)'\)""").findAll(js).map { it.groupValues[1] }.toSet()
        val dead = referenced - declared
        assertTrue(
            "app.js 引用了 index.html 与 JS 模板里都不存在的元素 id（赋值会抛 TypeError）: $dead",
            dead.isEmpty(),
        )
    }

    /**
     * `WebServer.kt` `/api/profiles/update` 里 `existing.copy(...)` 受理字段的镜像。
     * **手抄清单**：服务端加/删字段时必须同步维护（本清单就是为防漏改而存在的）。
     */
    private companion object {
        val PARITY_REGRESSION_FIELDS = setOf(
            "tunnelTlsEnabled",
            "icmpCustomPsk", "icmpCustomMagic", "icmpCustomPublicKey", "icmpCustomMtuMode",
            "icmpCustomMaxPayload", "icmpCustomPaceMS", "icmpCustomIdRange",
        )

        val ACCEPTED_FIELDS = setOf(
            "id",
            "name", "sshAddr", "user", "pass", "authType", "privateKey", "keyPass",
            "tunnelType", "tunnelTlsEnabled", "proxyAddr", "customHost", "serverName",
            "customPath", "enableCustomPath", "httpPayload", "disableStatusCheck", "alpn",
            "proxyAuthRequired", "proxyAuthToken", "proxyAuthUser", "proxyAuthPass",
            "verifyFingerprint", "serverFingerprint", "verifyCertFingerprint", "serverCertFingerprint",
            "dnsTunnelDomain", "dnsTunnelServers", "dnsTunnelType", "dnsTunnelPublicKey",
            "dnsTunnelEDNS0", "dnsTunnelPsk", "dnsTunnelMarker",
            "kcpPassword", "kcpCrypt", "kcpMode", "kcpSndWnd", "kcpRcvWnd", "kcpMtu",
            "kcpNoComp", "kcpSmuxVer", "kcpKeepAlive", "kcpDataShards", "kcpParityShards",
            "udpCustomPsk", "udpCustomMagic", "udpCustomPublicKey", "udpCustomPaths",
            "udpCustomSockets", "udpCustomSendWindow", "udpCustomMaxPkt", "udpCustomMtuProbe",
            "icmpCustomPsk", "icmpCustomMagic", "icmpCustomPublicKey", "icmpCustomMtuMode",
            "icmpCustomMaxPayload", "icmpCustomPaceMS", "icmpCustomIdRange",
            "xhttpChunkSizeKB", "xhttpStreamMode", "bindInterface", "heartbeatIntervalMs",
            "paddingMinBytes", "masqueAlpn", "noisePublicKey",
            "dnsOverride", "remoteDns", "localDns", "udpgwVersion", "udpgwAddr",
            "geositeDirect", "geoipDirect", "appFilterOverride", "filterMode", "filterApps",
            "note", "favorite",
        )
    }
}
