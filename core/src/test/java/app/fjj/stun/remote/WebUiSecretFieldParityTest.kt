package app.fjj.stun.remote

import app.fjj.stun.repo.ProfileSecrets
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「加凭据字段要三处同改」的机械护栏。
 *
 * 新增一个凭据字段必须同时改三个地方，漏掉任何一处都是**静默失效**：
 * 1. `ProfileSecrets.SECRET_FIELDS` —— 出口掩码 / 写回哨兵（漏了 = 明文进 AI 上下文）
 * 2. `ProfileManager.SECRET_IO_FIELDS` —— SQLite 静止加密（漏了 = 库里躺明文）；
 *    1↔2 的 parity 由 `ProfileSecretFieldsTest` 兜住
 * 3. `app.js` 的 `SECRET_INPUT_IDS` —— WebUI 的「空框 + hint / 删除」显示。漏了的话该密码框
 *    会把服务端返回的 `*****` 直接当 value 显示（`privateKey` / `dnsTunnelPsk` /
 *    `proxyAuthToken` 这三个是 type=text，星号**肉眼可见**），并且没有清空入口，
 *    同时 `lvVal()` 也不再回落到哨兵 ⇒ 已存密码的节点会被校验误报「必填」。
 *
 * 第 3 条此前只靠 `app.js` 里一句注释提醒。本测试把「Kotlin 字段名 → DOM id」的映射显式写成
 * 一张表，任何一侧新增字段都会立刻变红，逼着三处一起补全。
 *
 * DOM id 的命名**不规则**（`icmpCustomPsk` → `edit-node-icmp-psk` 而非
 * `edit-node-icmp-custom-psk`；`kcpPassword` → `edit-node-kcp-pass`；`proxyAuthToken` →
 * `edit-node-auth-token`），推导不出来，只能手写这张表。
 */
class WebUiSecretFieldParityTest {

    /** Kotlin 字段名（= payload 键 = 库列名）→ WebUI 输入框 DOM id。 */
    private val fieldToDomId = mapOf(
        "pass" to "edit-node-pass",
        "privateKey" to "edit-node-private-key",
        "keyPass" to "edit-node-key-pass",
        "proxyAuthToken" to "edit-node-auth-token",
        "proxyAuthPass" to "edit-node-auth-pass",
        "icmpCustomPsk" to "edit-node-icmp-psk",
        "udpCustomPsk" to "edit-node-udp-custom-psk",
        "dnsTunnelPsk" to "edit-node-dns-psk",
        "kcpPassword" to "edit-node-kcp-pass",
    )

    private fun asset(name: String): String {
        val candidates = listOf(
            File("src/main/assets/web/$name"),
            File("core/src/main/assets/web/$name"),
            File("app/src/main/assets/web/$name"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("找不到 $name：尝试过 ${candidates.map { it.absolutePath }}")
    }

    /** 抽 `const SECRET_INPUT_IDS = [ ... ];` 里的字符串字面量。 */
    private fun secretInputIds(js: String): List<String> {
        val start = js.indexOf("const SECRET_INPUT_IDS = [")
        assertTrue("app.js 里找不到 SECRET_INPUT_IDS 数组（被改名或删除了？）", start >= 0)
        val end = js.indexOf("];", start)
        assertTrue("SECRET_INPUT_IDS 数组没有闭合", end > start)
        return Regex("'([^']+)'").findAll(js.substring(start, end))
            .map { it.groupValues[1] }
            .toList()
    }

    @Test
    fun `Kotlin 凭据字段集合与本测试映射表一致`() {
        assertEquals(
            "ProfileSecrets.SECRET_FIELDS 增删了字段，却没有同步本测试的映射表 —— " +
                "请给新字段指定 WebUI DOM id，并把它加进 app.js 的 SECRET_INPUT_IDS",
            ProfileSecrets.SECRET_FIELDS.toSet(),
            fieldToDomId.keys,
        )
    }

    @Test
    fun `app_js 的 SECRET_INPUT_IDS 与映射表一一对应`() {
        val ids = secretInputIds(asset("app.js"))
        assertEquals("SECRET_INPUT_IDS 里有重复项", ids.size, ids.toSet().size)
        assertEquals(
            "app.js 的 SECRET_INPUT_IDS 与本测试映射表不一致（掩码/删除按钮会漏掉某个凭据框）",
            fieldToDomId.values.toSet(),
            ids.toSet(),
        )
    }

    @Test
    fun `映射表里的 DOM id 都真实存在于 index_html`() {
        val declared = Regex("""id="([^"{}]+)"""").findAll(asset("index.html"))
            .map { it.groupValues[1] }
            .toSet()
        val dead = fieldToDomId.values - declared
        assertTrue(
            "映射表指向的 WebUI 输入框在 index.html 里不存在（掩码逻辑会静默跳过它）: $dead",
            dead.isEmpty(),
        )
    }
}
