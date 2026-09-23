package app.fjj.stun.backup

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WebDAV 同步（resume 档：仅上传 / 仅下载 / 双向）的**判定内核**回归。
 *
 * 只碰 [WebDavSyncMode] 与 [WebDavBackupManager] 里那几个纯函数（模式归一化、内容指纹、
 * 冲突时间戳、拉取判定、meta 解析）—— 真正跑网络与设备的 `sync()` 不在这里，那是集成测试的活。
 *
 * 为什么要钉死这几个函数：它们是**唯一**决定「谁覆盖谁」的地方。任何一处退化（比如指纹不再
 * 排序、首次观测不再返回 0）都不会崩，只会安静地让某台设备的数据被另一台永久压住 ——
 * 用户要几天后才发现同步"有时灵有时不灵"。所以宁可在这里把边界写死。
 *
 * ⚠️ 本类**必须**挂 Robolectric，尽管逻辑本身零 Android 依赖：`parseMeta` 的解码失败分支会
 * 走 `StunLogger.w`，而 `StunLogger` 的 object 初始化里就有 `"#9E9E9E".toColorInt()`
 * （→ `android.graphics.Color`）。裸 JVM 下那是 `Stub!` 抛 `ExceptionInInitializerError`，
 * 症状是"测试莫名在一条日志语句上炸"，而不是断言失败。`:core` 没配默认 SDK，故显式给 sdk。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WebDavSyncDecisionTest {

    // ── 模式归一化 ──

    @Test
    fun `sync mode defaults to upload so existing users see no behaviour change`() {
        assertEquals(WebDavSyncMode.UPLOAD, WebDavSyncMode.DEFAULT)
        assertEquals(WebDavSyncMode.UPLOAD, WebDavSyncMode.fromId(null))
    }

    @Test
    fun `sync mode tolerates blank and garbage ids`() {
        assertEquals(WebDavSyncMode.UPLOAD, WebDavSyncMode.fromId(""))
        assertEquals(WebDavSyncMode.UPLOAD, WebDavSyncMode.fromId("   "))
        // 旧版本里这个键根本不存在，WebUI / MCP 传进来的又都是外部输入
        assertEquals(WebDavSyncMode.UPLOAD, WebDavSyncMode.fromId("upload_only"))
        assertEquals(WebDavSyncMode.UPLOAD, WebDavSyncMode.fromId("双向"))
    }

    @Test
    fun `sync mode parses known ids case and whitespace insensitively`() {
        assertEquals(WebDavSyncMode.UPLOAD, WebDavSyncMode.fromId("upload"))
        assertEquals(WebDavSyncMode.DOWNLOAD, WebDavSyncMode.fromId("download"))
        assertEquals(WebDavSyncMode.BOTH, WebDavSyncMode.fromId("both"))
        assertEquals(WebDavSyncMode.BOTH, WebDavSyncMode.fromId(" BOTH "))
        assertEquals(WebDavSyncMode.DOWNLOAD, WebDavSyncMode.fromId("Download"))
    }

    @Test
    fun `only upload skips pulling and only download skips pushing`() {
        assertFalse(WebDavSyncMode.UPLOAD.pulls)
        assertTrue(WebDavSyncMode.UPLOAD.pushes)

        assertTrue(WebDavSyncMode.DOWNLOAD.pulls)
        assertFalse(WebDavSyncMode.DOWNLOAD.pushes)

        assertTrue(WebDavSyncMode.BOTH.pulls)
        assertTrue(WebDavSyncMode.BOTH.pushes)
    }

    // ── 内容指纹 ──

    @Test
    fun `fingerprint ignores object key order`() {
        // 设置快照是从 SharedPreferences.all 枚举出来的，键序不该被当成"内容变了"
        assertEquals(
            WebDavBackupManager.fingerprint("""{"b":1,"a":2}"""),
            WebDavBackupManager.fingerprint("""{"a":2,"b":1}"""),
        )
    }

    @Test
    fun `fingerprint ignores nested object key order`() {
        assertEquals(
            WebDavBackupManager.fingerprint("""{"outer":{"y":1,"x":{"q":2,"p":3}}}"""),
            WebDavBackupManager.fingerprint("""{"outer":{"x":{"p":3,"q":2},"y":1}}"""),
        )
    }

    @Test
    fun `fingerprint flips when any value changes`() {
        assertNotEquals(
            WebDavBackupManager.fingerprint("""{"a":1,"b":2}"""),
            WebDavBackupManager.fingerprint("""{"a":1,"b":3}"""),
        )
    }

    @Test
    fun `fingerprint keeps array order significant`() {
        // 数组本身是有序语义，排序会把"顺序真的变了"这种改动吞掉
        assertNotEquals(
            WebDavBackupManager.fingerprint("""[1,2,3]"""),
            WebDavBackupManager.fingerprint("""[3,2,1]"""),
        )
    }

    @Test
    fun `fingerprint of malformed json must not throw and stays stable`() {
        // 宁可指纹不稳，也不能在同步路径上抛
        val a = WebDavBackupManager.fingerprint("not json at all")
        val b = WebDavBackupManager.fingerprint("not json at all")
        assertEquals(a, b)
        assertNotEquals(a, WebDavBackupManager.fingerprint("not json at al2"))
    }

    // ── 冲突时间戳判定 ──

    @Test
    fun `absent local content keeps stored mtime and never wins`() {
        assertEquals(
            777L,
            WebDavBackupManager.localMtime(
                storedMtime = 777L, storedHash = "h", hash = null, remoteMtime = 5_000L, now = 9_000L,
            ),
        )
    }

    @Test
    fun `first observation reports zero so a fresh device can still pull`() {
        // 关键：装完就"刚看见"≠"我改过"。给 0 才能让云端的内容盖下来。
        assertEquals(
            0L,
            WebDavBackupManager.localMtime(
                storedMtime = 0L, storedHash = "", hash = "fresh", remoteMtime = 5_000L, now = 9_000L,
            ),
        )
    }

    @Test
    fun `unchanged content keeps stored mtime`() {
        assertEquals(
            4_200L,
            WebDavBackupManager.localMtime(
                storedMtime = 4_200L, storedHash = "same", hash = "same", remoteMtime = 9_999L, now = 9_999L,
            ),
        )
    }

    @Test
    fun `changed content anchors to now when local clock is ahead`() {
        assertEquals(
            9_000L,
            WebDavBackupManager.localMtime(
                storedMtime = 4_200L, storedHash = "old", hash = "new", remoteMtime = 5_000L, now = 9_000L,
            ),
        )
    }

    @Test
    fun `changed content beats a remote mtime from a fast clock`() {
        // 防时钟偏差：本机慢钟时 now < 远端，若照抄 now 就永远推不上去
        assertEquals(
            5_001L,
            WebDavBackupManager.localMtime(
                storedMtime = 4_200L, storedHash = "old", hash = "new", remoteMtime = 5_000L, now = 100L,
            ),
        )
    }

    @Test
    fun `pull only when remote is strictly newer`() {
        assertTrue(WebDavBackupManager.shouldPull(remoteMtime = 101L, localMtime = 100L))
        assertFalse(WebDavBackupManager.shouldPull(remoteMtime = 100L, localMtime = 100L))
        assertFalse(WebDavBackupManager.shouldPull(remoteMtime = 99L, localMtime = 100L))
    }

    @Test
    fun `push mtime anchors a never-observed part to now but leaves real mtimes alone`() {
        assertEquals(12_345L, WebDavBackupManager.anchorMtime(0L, now = 12_345L))
        assertEquals(4_200L, WebDavBackupManager.anchorMtime(4_200L, now = 12_345L))
    }

    // ── 同步元数据解析 ──

    @Test
    fun `parse meta reads a well formed stamp table`() {
        val parsed = WebDavBackupManager.parseMeta("""{"profiles":1700000000000,"subscription":1700000000001}""")
        assertEquals(mapOf("profiles" to 1_700_000_000_000L, "subscription" to 1_700_000_000_001L), parsed)
    }

    @Test
    fun `parse meta accepts an empty table`() {
        assertEquals(emptyMap<String, Long>(), WebDavBackupManager.parseMeta("{}"))
    }

    @Test
    fun `parse meta rejects malformed payloads instead of faking an empty table`() {
        // 返回空表会让"这一轮没有任何远端更新"和"这份 meta 坏了"变成同一件事
        assertNull(WebDavBackupManager.parseMeta("not json"))
        assertNull(WebDavBackupManager.parseMeta("[1,2,3]"))
        assertNull(WebDavBackupManager.parseMeta(""))
        assertNull(WebDavBackupManager.parseMeta("""{"profiles":"not-a-number"}"""))
    }
}
