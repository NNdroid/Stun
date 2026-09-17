package app.fjj.stun.repo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 锁定 masque ALPN 的旧值迁移。
 *
 * SDK 只接受 `""`(auto)/`"h3"`/`"h2"`；配置面历史上曾把 `"h3,h2"` / `"h2,h3"` 当作
 * auto 的别名（已移除）。[Profile.normalizeMasqueAlpn] 负责把旧存档里残留的这两个值
 * 归一到 `"auto"`，避免旧节点被 SDK 拒绝而连不上，同时让下次保存写回干净值。
 *
 * 这是纯字符串函数，用裸 JUnit（不需要 Robolectric，避开 core 无默认 SDK 的问题）。
 */
class ProfileMasqueAlpnTest {

    @Test
    fun migratesTheRemovedAliasesToAuto() {
        assertEquals("auto", Profile.normalizeMasqueAlpn("h3,h2"))
        assertEquals("auto", Profile.normalizeMasqueAlpn("h2,h3"))
        // 大小写/空格归一后仍命中
        assertEquals("auto", Profile.normalizeMasqueAlpn("H3, H2"))
        assertEquals("auto", Profile.normalizeMasqueAlpn("  h2,h3  "))
    }

    @Test
    fun keepsTheThreeSupportedValues() {
        assertEquals("auto", Profile.normalizeMasqueAlpn(""))
        assertEquals("auto", Profile.normalizeMasqueAlpn("auto"))
        assertEquals("auto", Profile.normalizeMasqueAlpn("AUTO"))
        assertEquals("h3", Profile.normalizeMasqueAlpn("h3"))
        assertEquals("h2", Profile.normalizeMasqueAlpn("h2"))
    }

    @Test
    fun passesUnknownValuesThroughUntouched() {
        // 非法值不在这里静默改写，交由 SDK 校验拒绝（与 Go 侧 normalizeMasqueALPN 的 default 分支一致）
        assertEquals("bogus", Profile.normalizeMasqueAlpn("bogus"))
        assertEquals("h2,h3,http/1.1", Profile.normalizeMasqueAlpn("h2,h3,http/1.1"))
    }
}
