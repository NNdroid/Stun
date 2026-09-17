package app.fjj.stun.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定 masque ALPN 的值域。
 *
 * h2tunnel SDK（transport_masque_client.go 定义、client_api.go 校验）只有
 * `""`(auto) / `"h3"` / `"h2"` 三个合法取值，且按严格字符串相等比较、不做逗号拆分。
 *
 * 历史背景：配置面曾把 `"h3,h2"` / `"h2,h3"` 当作 auto 的别名，并作为独立下拉选项
 * 暴露给用户，但这两个写法并不表达 SDK 支持的任何能力（不存在「优先级排序」这一
 * 语义，行为与 auto 完全相同）。已从下拉选项、校验、错误文案、文档中一并移除，
 * `Profile.normalizeMasqueAlpn` 负责把旧存档里的残留值归一到 auto。
 *
 * 下面的断言就是防止这些值被重新加回来。
 */
class FieldRulesMasqueAlpnTest {

    @Test
    fun acceptsTheThreeValuesTheSdkActuallySupports() {
        // "" = SDK 自动协商（h3 优先，grace 失败后 pin h2）；auto = 它的等价配置面写法
        assertTrue(FieldRules.isMasqueAlpn(""))
        assertTrue(FieldRules.isMasqueAlpn("auto"))
        // h3 = 仅 QUIC/UDP；h2 = 仅 TCP 扩展 CONNECT（RFC 8441）
        assertTrue(FieldRules.isMasqueAlpn("h3"))
        assertTrue(FieldRules.isMasqueAlpn("h2"))
    }

    @Test
    fun isCaseInsensitiveAndToleratesSpaces() {
        assertTrue(FieldRules.isMasqueAlpn("AUTO"))
        assertTrue(FieldRules.isMasqueAlpn("H3"))
        assertTrue(FieldRules.isMasqueAlpn(" H2 "))
    }

    @Test
    fun rejectsTheRemovedMultiValueAliases() {
        assertFalse(FieldRules.isMasqueAlpn("h3,h2"))
        assertFalse(FieldRules.isMasqueAlpn("h2,h3"))
        // 大小写/空格归一后仍是多值，同样拒绝
        assertFalse(FieldRules.isMasqueAlpn("H3, H2"))
        assertFalse(FieldRules.isMasqueAlpn("h2,h3,http/1.1"))
    }

    @Test
    fun rejectsAnythingElse() {
        // http/1.1 是 xhttp 的合法 ALPN（多值列表），但对 masque 无效——两个字段值域不同，勿混用
        assertFalse(FieldRules.isMasqueAlpn("http/1.1"))
        assertFalse(FieldRules.isMasqueAlpn("h1"))
        assertFalse(FieldRules.isMasqueAlpn("bogus"))
    }
}
