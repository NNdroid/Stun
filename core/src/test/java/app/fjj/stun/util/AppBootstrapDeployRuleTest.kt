package app.fjj.stun.util

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [AppBootstrap.needsDeploy] 的**部署判定矩阵**。
 *
 * 这是「规则库每次冷启动重铺 12.9MB」那次修复的唯一核心：旧判定拿 `last_update_time`
 * 当部署依据，而它只在**在线下载成功**后才 > 0，于是首次安装（或从没下载成功过）的机器
 * `lastUpdate <= 0` 恒真 ⇒ 每次冷启动都重铺。新判定把「上次部署依据」存在**文件自己的
 * mtime** 上，零新增状态。这里把 5 种场景逐条钉死，谁改判定谁就会在这里翻车：
 *
 * ① 文件缺失（cacheDir 被系统清走）→ 重铺
 * ② 稳态：铺完的 mtime 晚于 APK 安装时间 → **不**重铺（本次修复的主目标）
 * ③ APK 升级：新安装时间晚于副本 mtime → 重铺
 * ④ 副本 mtime 恰好等于 APK 安装时间 → 不重铺（判定是严格小于）
 * ⑤ 0 字节副本 → 重铺（拷贝中途失败会留下 mtime 很新的空文件，光看 mtime 会漏）
 * ⑥ `apkUpdateTime` 读不到（0）→ 退化为「只补缺失/空文件」，不因时间比较而重铺
 *
 * 纯 JVM（`java.io.File` + 临时目录），不挂 Robolectric —— `:core` 的纯逻辑单测
 * 没配默认 SDK，挂了会抛 `IllegalArgumentException`。
 */
class AppBootstrapDeployRuleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fileWith(mtimeMs: Long, length: Long = 8L): File {
        val f = File(tmp.root, "geoip.dat")
        f.writeText("x".repeat(length.toInt()))
        assertTrue("测试前置：setLastModified 应成功", f.setLastModified(mtimeMs))
        return f
    }

    @Test
    fun `文件缺失必须重铺`() {
        assertFalse(File(tmp.root, "not-deployed.dat").exists())
        assertTrue(AppBootstrap.needsDeploy(File(tmp.root, "not-deployed.dat"), apkUpdateTimeMs = 1_000L))
    }

    @Test
    fun `稳态下刚铺完的副本不重铺`() {
        // 铺完的 mtime（= 部署时刻）必然晚于 APK 安装时间 —— 这正是旧判定修不掉的场景。
        val f = fileWith(mtimeMs = 2_000_000L)
        assertFalse(AppBootstrap.needsDeploy(f, apkUpdateTimeMs = 1_000_000L))
    }

    @Test
    fun `APK 升级后必须重铺`() {
        val f = fileWith(mtimeMs = 1_000_000L)
        assertTrue(AppBootstrap.needsDeploy(f, apkUpdateTimeMs = 2_000_000L))
    }

    @Test
    fun `副本 mtime 等于 APK 安装时间时不重铺`() {
        val f = fileWith(mtimeMs = 1_000_000L)
        assertFalse(AppBootstrap.needsDeploy(f, apkUpdateTimeMs = 1_000_000L))
    }

    @Test
    fun `零字节副本必须重铺`() {
        val f = fileWith(mtimeMs = 2_000_000L, length = 0)
        assertTrue("空文件 mtime 再新也不能算已部署", AppBootstrap.needsDeploy(f, apkUpdateTimeMs = 1_000_000L))
    }

    @Test
    fun `apkUpdateTime 读不到时退化为只补缺失文件`() {
        // apkUpdateTimeMs = 0：任何真实文件的 mtime 都不小于 0，于是时间比较恒为假，
        // 只剩「缺失 / 空文件」两条兜底 —— 比「每次启动都重铺」保守得多。
        val f = fileWith(mtimeMs = 1_000L)
        assertFalse(AppBootstrap.needsDeploy(f, apkUpdateTimeMs = 0L))
        assertTrue(AppBootstrap.needsDeploy(File(tmp.root, "missing.dat"), apkUpdateTimeMs = 0L))
    }
}
