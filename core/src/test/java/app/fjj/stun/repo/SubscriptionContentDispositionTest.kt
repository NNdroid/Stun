package app.fjj.stun.repo

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 锁住订阅名解析（`SubscriptionManager.parseContentDisposition`）的行为。
 *
 * 这里有两个曾经踩过的坑，改动解析逻辑时务必保持：
 * 1. **无引号** 的 `filename=Airport.yaml` 过去因惰性量词 + 空回引用只捕获到首字符 `A`；
 * 2. RFC 5987 的 `filename*=UTF-8'en'…`（带语言标签）过去匹配不上，直接丢掉名称。
 *
 * 另外锁「剥掉常见扩展名」这一展示约定：服务端 filename 常写成「名称.yaml」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SubscriptionContentDispositionTest {

    private fun parse(header: String?): String =
        SubscriptionManager.parseContentDisposition(header)

    // ── 普通式 ──────────────────────────────────────────────────────────────

    @Test
    fun parsesQuotedFilename() {
        assertEquals("My Airport", parse("attachment; filename=\"My Airport.yaml\""))
        assertEquals("Airport", parse("attachment;filename=\"Airport\""))
        assertEquals("sub", parse("inline; filename=\"sub.txt\""))
    }

    @Test
    fun parsesUnquotedFilenameInFull() {
        // 回归：曾只返回首字符
        assertEquals("MyAirport", parse("attachment; filename=MyAirport.yaml"))
        assertEquals("Airport", parse("attachment; filename=Airport"))
        // 长名字（含连字符/下划线）也要整体吃下，只是末尾 .txt 按约定剥掉
        assertEquals("Long-Name_2026", parse("attachment; filename=Long-Name_2026.txt"))
    }

    @Test
    fun unquotedValueStopsAtSemicolon() {
        assertEquals("Airport", parse("attachment; filename=Airport; size=1234"))
    }

    // ── RFC 5987 扩展式 ──────────────────────────────────────────────────────

    @Test
    fun parsesRfc5987Utf8Filename() {
        assertEquals("机场", parse("attachment; filename*=UTF-8''%E6%9C%BA%E5%9C%BA"))
        assertEquals("机场", parse("attachment; filename*=UTF-8''%E6%9C%BA%E5%9C%BA.yaml"))
        assertEquals("MySub", parse("attachment; filename*=utf-8''MySub"))
    }

    @Test
    fun parsesRfc5987WithLanguageTag() {
        // 回归：`charset'lang'value` 三段式，lang 非空
        assertEquals("机场", parse("attachment; filename*=UTF-8'en'%E6%9C%BA%E5%9C%BA"))
        assertEquals("机场", parse("attachment; filename*=UTF-8'zh-CN'%E6%9C%BA%E5%9C%BA.txt"))
    }

    @Test
    fun extendedFormWinsOverPlainForm() {
        assertEquals(
            "机场",
            parse("attachment; filename=\"A.yaml\"; filename*=UTF-8''%E6%9C%BA%E5%9C%BA")
        )
    }

    // ── 扩展名剥离 ───────────────────────────────────────────────────────────

    @Test
    fun stripsCommonFileExtensions() {
        assertEquals("Airport", parse("attachment; filename=\"Airport.yml\""))
        assertEquals("Airport", parse("attachment; filename=\"Airport.json\""))
        assertEquals("Airport", parse("attachment; filename=\"Airport.conf\""))
        assertEquals("Airport", parse("attachment; filename=\"Airport.YAML\""))
    }

    @Test
    fun keepsUnknownOrMeaningfulDots() {
        // 非白名单后缀保留
        assertEquals("Airport.yaml.bak", parse("attachment; filename=\"Airport.yaml.bak\""))
        // 名字里的点不是扩展名分隔（后缀不在白名单）
        assertEquals("v1.2", parse("attachment; filename=\"v1.2\""))
        // 带前导点：整体就是"扩展名"，原样返回而不是剥成空
        assertEquals(".yaml", parse("attachment; filename=\".yaml\""))
        // 有多个点时只剥最后一段
        assertEquals("东京.高速", parse("attachment; filename=\"东京.高速.yaml\""))
    }

    // ── 退化 / 异常输入 ──────────────────────────────────────────────────────

    @Test
    fun returnsEmptyWhenNameAbsent() {
        assertEquals("", parse(null))
        assertEquals("", parse(""))
        assertEquals("", parse("attachment"))
        assertEquals("", parse("attachment; size=1234"))
        assertEquals("", parse("attachment; filename=\"\""))
        // 只有空白也不算名称
        assertEquals("", parse("attachment; filename=\"   \""))
    }

    @Test
    fun toleratesLooseWhitespace() {
        assertEquals("Airport", parse("attachment; filename = \"Airport.yaml\""))
        assertEquals("Airport", parse("attachment; filename =  Airport.yaml"))
        assertEquals("Airport", parse("attachment; filename*= UTF-8''Airport"))
    }
}
