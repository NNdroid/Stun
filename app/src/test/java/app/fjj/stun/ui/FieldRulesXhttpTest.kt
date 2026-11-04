package app.fjj.stun.ui

import app.fjj.stun.repo.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * myssh 24195a2 暴露的三个 xhttp 参数的校验回归 + 新版 PSK 必填语义。
 *
 * padding 规则逐条镜像 xhttptunnel `tunnel/padding.go` 的 `parsePaddingSpec`：
 * 空/auto/default/off/none、`MIN-MAX` / `BELOW:MIN-MAX` 分段（≤8 段、阈值递增、
 * MIN≥1、MIN≤MAX≤65535、MIN≥BELOW-1）。断言里刻意放了**反事实**：只测合法值的话，
 * 一个恒 true 的谓词也能全绿。
 */
class FieldRulesXhttpTest {

    // ── padding 语法 ────────────────────────────────────────────

    @Test
    fun acceptsKeywordsAndBuiltInPolicy() {
        for (v in listOf("", "  ", "auto", "AUTO", "default", "off", "OFF", "none")) {
            assertTrue("[$v] 应被 SDK 接受", FieldRules.isXhttpPadding(v))
        }
    }

    @Test
    fun acceptsSimpleAndMultiBandRules() {
        assertTrue(FieldRules.isXhttpPadding("400-600"))
        assertTrue(FieldRules.isXhttpPadding(" 400 - 600 "))          // SDK 忽略空白
        assertTrue(FieldRules.isXhttpPadding("23:120-200,400:400-600"))
        assertTrue(FieldRules.isXhttpPadding("400:400-600"))           // 等价于 400-600
        assertTrue(FieldRules.isXhttpPadding("7:8-200,400:400-600"))
    }

    @Test
    fun rejectsMalformedRulesTheSdkWouldReject() {
        val bad = listOf(
            "bogus",                 // 非关键字、非 MIN-MAX
            "400",                   // 缺 '-'
            "600-400",               // 区间倒挂
            "0-600",                 // MIN < 1
            "400-65536",             // MAX 超过 pad 字段宽度 65535
            "-400-600",              // 负号不被 parsePaddingSize 接受
            "+400-600",
            "0:400-600",             // 显式阈值必须 >= 1
            "500:400-600",           // MIN < BELOW-1（400 < 499）
            "400:400-600,23:120-200",// 阈值必须递增
            "400-600,",              // 空分段
            "400-600,23:120-200",    // 阈值递减
            "1-2,3-4,5-6,7-8,9-10,11-12,13-14,15-16,17-18", // 超过 8 段
        )
        for (v in bad) {
            assertFalse("[$v] 应被 SDK 拒绝", FieldRules.isXhttpPadding(v))
        }
    }

    // ── 窗口 / 并发上限 ─────────────────────────────────────────

    @Test
    fun windowIsZeroOrOneToSixtyFour() {
        for (v in listOf("", "0", "1", "8", "64")) {
            assertTrue("[$v] 应合法", FieldRules.isXhttpWindowSizeMB(v))
        }
        for (v in listOf("65", "100", "-1", "abc")) {
            assertFalse("[$v] 应非法", FieldRules.isXhttpWindowSizeMB(v))
        }
    }

    @Test
    fun maxConnsIsZeroOrPositive() {
        for (v in listOf("", "0", "1", "2000", "99999")) {
            assertTrue("[$v] 应合法", FieldRules.isXhttpMaxConns(v))
        }
        for (v in listOf("-1", "abc")) {
            assertFalse("[$v] 应非法", FieldRules.isXhttpMaxConns(v))
        }
    }

    // ── compute：xhttp 专属组 + 无条件 PSK ──────────────────────

    @Test
    fun xhttpRequiresPskEvenWhenTheAuthSwitchIsOff() {
        val xhttp = baseInputs(TunnelFieldSpecs.forType(Profile.TUNNEL_TYPE_XHTTP))
            .copy(authEnabled = false, authToken = "")
        assertTrue(
            "xhttp 无条件把 proxy_auth_token 当 PSK；开关关掉也必须报必填",
            FieldRules.compute(xhttp).errors.containsKey(FieldKey.AUTH_TOKEN),
        )
        // 反事实：其它 bearer 隧道仍受开关控制，别把规则错误地全局化
        val h2 = baseInputs(TunnelFieldSpecs.forType(Profile.TUNNEL_TYPE_H2))
            .copy(authEnabled = false, authToken = "")
        assertFalse(
            "h2 关掉鉴权开关时不该再要求 token",
            FieldRules.compute(h2).errors.containsKey(FieldKey.AUTH_TOKEN),
        )
    }

    @Test
    fun xhttpValidatesAllThreeNewFieldsTogether() {
        val verdict = FieldRules.compute(
            baseInputs(TunnelFieldSpecs.forType(Profile.TUNNEL_TYPE_XHTTP)).copy(
                xhttpPadding = "600-400",
                xhttpWindowSizeMB = "65",
                xhttpMaxConns = "-3",
            )
        )
        assertEquals(
            setOf(FieldKey.XHTTP_PADDING, FieldKey.XHTTP_WINDOW_SIZE, FieldKey.XHTTP_MAX_CONNS),
            verdict.errors.keys,
        )
    }

    /** 只填必要项的最小快照；避免每个用例手抄 40+ 构造参数。 */
    private fun baseInputs(spec: TunnelFieldSpec) = FieldInputs(
        spec = spec,
        tlsActive = false,
        sshAddr = "127.0.0.1:22",
        proxyAddr = "example.com:443",
        serverName = "",
        customPath = "",
        customPathVisible = false,
        dnsServers = "",
        dnsDomain = "",
        dnsRecordType = "",
        dnsPsk = "",
        dnsMarker = "",
        noisePublicKey = "",
        udpPsk = "",
        udpMagic = "",
        udpMaxPkt = "",
        udpMtuProbe = "",
        icmpPsk = "",
        icmpMagic = "",
        kcpPassword = "",
        kcpSndwnd = "",
        kcpRcvwnd = "",
        kcpMtu = "",
        kcpSmuxver = "",
        kcpKeepalive = "",
        kcpDataShards = "",
        kcpParityShards = "",
        httpPayload = "",
        authEnabled = true,
        authUser = "",
        authPass = "",
        authToken = "psk-token",
        verifySshFp = false,
        sshFingerprint = "",
        verifyCertFp = false,
        certFingerprint = "",
        chunkSize = "",
        heartbeat = "",
        paddingMinBytes = "",
        masqueAlpn = "",
        xhttpPadding = "",
        xhttpWindowSizeMB = "",
        xhttpMaxConns = "",
        sshPassFallbackAvailable = true,
    )
}
