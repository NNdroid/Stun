package app.fjj.stun.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「哪些字段是凭据」在仓库里有两个独立清单，本测试把它们钉死：
 *
 * | 清单 | 用途 |
 * |---|---|
 * | [ProfileSecrets.SECRET_FIELDS] | 对外出口打码（MCP / WebUI 的 `*****`） |
 * | [SECRET_IO_FIELDS] | 落盘加密（Android Keystore 的 `ENC:`） |
 *
 * 两者必须**同名同量**。不同步的后果正是本测试的由来：`icmpCustomPsk` / `udpCustomPsk` /
 * `dnsTunnelPsk` / `kcpPassword` 一直在 `SECRET_FIELDS` 里（所以对外显示 `*****`，
 * 光看输出看不出毛病），却不在加密清单里 —— SQLite 里躺着明文，而当时没有任何测试会失败。
 *
 * 纯 JVM：[mapSecretFields] 接受注入的 transform，[needsEncryption] 只依赖
 * `KeystoreUtils.ENC_PREFIX`（`const`，编译期内联），两者都不会拉起 Android Keystore / Tink。
 */
class ProfileSecretFieldsTest {

    private val mnemonic = "-----BEGIN OPENSSH PRIVATE KEY-----"

    private fun secretProfile() = Profile(
        id = "p1",
        name = "node",
        sshAddr = "1.2.3.4:22",
        user = "root",
        pass = "ssh-pass",
        privateKey = mnemonic,
        keyPass = "key-pass",
        proxyAuthToken = "proxy-token",
        proxyAuthPass = "proxy-pass",
        icmpCustomPsk = "icmp-psk",
        udpCustomPsk = "udp-psk",
        dnsTunnelPsk = "dns-psk",
        kcpPassword = "kcp-pass"
    )

    /** 上面那 9 个凭据字段的原始取值 —— 用来验证「正好这 9 个、每个只过一次」。 */
    private val originals = setOf(
        "ssh-pass", mnemonic, "key-pass", "proxy-token", "proxy-pass",
        "icmp-psk", "udp-psk", "dns-psk", "kcp-pass"
    )

    @Test
    fun `出口掩码清单与落盘加密清单必须严格一致`() {
        assertEquals(
            "ProfileSecrets.SECRET_FIELDS 与 SECRET_IO_FIELDS 不同步，" +
                "就会出现「对外打码、库里明文」的半安全状态",
            ProfileSecrets.SECRET_FIELDS.toSet(),
            SECRET_IO_FIELDS.map { it.name }.toSet()
        )
    }

    @Test
    fun `清单内无重复字段`() {
        val names = SECRET_IO_FIELDS.map { it.name }
        assertEquals("$names 有重复项，同一字段会被加密两次", names.size, names.toSet().size)
    }

    @Test
    fun `mapSecretFields 覆盖全部凭据字段且每个只过一次`() {
        val profile = secretProfile()
        val seen = mutableListOf<String>()

        val result = mapSecretFields(profile) { value ->
            seen += value
            "T($value)"
        }

        assertEquals("漏了谁或多改谁都会在这里暴露", originals, seen.toSet())
        assertEquals("同一字段被变换多次（会导致二次加密）", originals.size, seen.size)

        SECRET_IO_FIELDS.forEach { field ->
            assertEquals(
                "字段 ${field.name} 的变换结果没有写回",
                "T(${field.readFrom(profile)})",
                field.readFrom(result)
            )
        }
    }

    @Test
    fun `mapSecretFields 不动非凭据字段`() {
        val profile = secretProfile()
        val result = mapSecretFields(profile) { "" }

        assertEquals(profile.id, result.id)
        assertEquals(profile.name, result.name)
        assertEquals(profile.sshAddr, result.sshAddr)
        assertEquals("用户名不是凭据（单独存在无法通过认证），不该被加密", profile.user, result.user)
    }

    @Test
    fun `迁移判定只认 ENC 前缀且跳过空值`() {
        assertTrue("明文必须加密", needsEncryption("plain-password"))
        assertFalse("空串 = 未设置，不该写成密文", needsEncryption(""))
        assertFalse("已经是密文，不该二次加密", needsEncryption("ENC:AAAA"))
        assertTrue("前缀大小写敏感，enc: 开头的不是我们的密文", needsEncryption("enc:aaaa"))
        assertTrue("长度不足 4 也不该被当成密文", needsEncryption("EN"))
    }
}
