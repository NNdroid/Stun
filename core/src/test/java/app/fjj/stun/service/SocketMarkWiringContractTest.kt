package app.fjj.stun.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「SO_MARK 探测结果 → `buildShellRules` 的 `socketMark` 参数」这条接线。
 *
 * ## 为什么这层必须单独钉
 *
 * [TransparentProxyConfigBuilderTest] 已经钉住了 `socketMark=0` 时会回落到 uid 放行，
 * 但那只覆盖**纯函数**。而线上真正会死循环的地方是**接线**：`MyTransparentProxyService`
 * 早期版本调用 `buildShellRules` 时压根不传 `socketMark`，于是恒取默认值
 * `TProxyPorts.SOCKET_MARK` ⇒ 无论 helper 有没有部署、有没有 root，规则层一律声明
 * "我按 mark 放行"，而 mark 从来没设上 ⇒ 隧道 socket 既无 mark 又不在旁路列表
 * ⇒ 被 TPROXY 抓回本地 socks5，SSH 完全连不上。
 *
 * 这种 bug 编译不报错、单测（只测 Builder）全绿，只有真机连不上才暴露。
 * 所以这里用**源码文本契约**把接线钉死：它不需要跑起 Service，也不需要设备。
 */
class SocketMarkWiringContractTest {

    private val serviceSource = File("src/main/java/app/fjj/stun/service/MyTransparentProxyService.kt")

    private fun source(): String {
        assertTrue(
            "找不到 ${serviceSource.path}，测试应在 :core 模块目录下运行",
            serviceSource.exists(),
        )
        return serviceSource.readText()
    }

    /**
     * 解析「`socketMark` 实参在探测成功时取什么、失败时取什么」。
     *
     * 两种等价写法都要认：
     *  1. 内联三元：`socketMark = if (socketMarkAvailable) TProxyPorts.SOCKET_MARK else 0`
     *  2. 先赋值再传参：`val markInUse = if (socketMarkAvailable) … else 0` + `socketMark = markInUse`
     *     —— 加日志时为了让规则层的实际取值也能打出来，会改成这种写法。**行为完全等价**，
     *     契约关心的是"探测结果参与了决策"，不是某一行字面量的形状。
     *
     * @return Pair(成功时的取值, 失败时的取值)；解析不出时返回 null。
     */
    private fun resolveSocketMarkArg(text: String): Pair<String, String>? {
        Regex(
            "if\\s*\\(\\s*socketMarkAvailable\\s*\\)\\s*([A-Za-z0-9_.]+)\\s*else\\s*([0-9]+)",
        ).find(text)?.let { m ->
            return m.destructured.component1() to m.destructured.component2()
        }
        // 写法 2：局部变量承载三元结果，再作为实参传入。
        Regex("val\\s+(\\w+)\\s*=\\s*if\\s*\\(\\s*socketMarkAvailable\\s*\\)\\s*([A-Za-z0-9_.]+)\\s*else\\s*([0-9]+)")
            .find(text)
            ?.let { m ->
                val (varName, whenTrue, whenFalse) = m.destructured
                // 该变量必须真的被当作 socketMark 实参传下去，否则只是个无关局部变量。
                if (Regex("socketMark\\s*=\\s*$varName\\b").containsMatchIn(text)) {
                    return whenTrue to whenFalse
                }
            }
        return null
    }

    /**
     * `applyRules` 必须按探测结果决定传什么，**不能**无条件用默认值。
     *
     * 断言的是「存在按 `socketMarkAvailable` 分支的传参」这件事本身，
     * 而非某一行字面量 —— 写法可以变，但"探测结果必须参与决策"不能变。
     */
    @Test
    fun applyRulesBranchesOnProbeResult() {
        assertNotNull(
            "applyRules 调用 buildShellRules 时没有按 socketMarkAvailable 分支 —— " +
                "探测失败也会生成 mark 模式，隧道 socket 落进 TPROXY 死循环",
            resolveSocketMarkArg(source()),
        )
    }

    /**
     * 探测必须真的发生，且其返回值被记进 [socketMarkAvailable]。
     *
     * 反事实：把这一行改成 `socketMarkAvailable = registerSocketMarkHelper()` 之外的
     * 任何写法（或干脆写死 true），本测试立刻失败。
     */
    @Test
    fun probeResultIsCapturedIntoField() {
        val text = source()
        assertTrue(
            "没有把 registerSocketMarkHelper() 的返回值存进 socketMarkAvailable —— 探测结果被丢弃",
            Regex("socketMarkAvailable\\s*=\\s*registerSocketMarkHelper\\(\\)").containsMatchIn(text),
        )
        assertTrue(
            "没有调用 probeSocketMark() —— mark 通路可用性从未被验证过",
            text.contains("probeSocketMark()"),
        )
    }

    /**
     * 降级方向必须是「探测失败 → socketMark=0 → uid 放行」这条**保守**的路径。
     *
     * 反事实：若哪天有人把分支反过来写成 `if (!socketMarkAvailable) SOCKET_MARK else 0`，
     * 探测成功反而回落 uid、探测失败反而走 mark，症状是"看起来能连但出口 IP 是本机"
     * 加上"有时连不上"，极难定位。本测试直接比对两侧字面量来排除这种反转。
     */
    @Test
    fun probeFailureYieldsUidBypassNotMark() {
        val pair = resolveSocketMarkArg(source())
        assertNotNull("未能解析 applyRules 里由 socketMarkAvailable 决定的 socketMark 取值", pair)
        val (whenTrue, whenFalse) = pair!!
        assertEquals(
            "探测成功时才用 SOCKET_MARK",
            "TProxyPorts.SOCKET_MARK",
            whenTrue,
        )
        assertEquals(
            "探测失败时必须传 0（触发 uid 放行兜底），而不是 SOCKET_MARK",
            "0",
            whenFalse,
        )
    }

    /**
     * 把 Builder 的两种取值实际拼出来，证明上面那条接线在**行为**上确实闭环：
     * 探测失败 ⇒ 规则里自己回到 `BYPASS_APPS_LIST`（隧道能连上，只是 App 流量全直连）。
     *
     * 这是端到端的最后一环：前三项钉源码形状，本项钉行为结果。
     */
    @Test
    fun probeFailureProducesRulesThatStillBypassByUid() {
        val degraded = TransparentProxyConfigBuilder.buildShellRules(
            selfPackage = "app.fjj.stun",
            tproxyPort = 10812,
            dnsPort = 10553,
            appFilter = AppFilter.EMPTY,
            socketMark = 0,
        )
        val bypass = degraded.lineSequence()
            .first { it.trimStart().startsWith("BYPASS_APPS_LIST=") }
            .substringAfter('=')
        assertTrue(
            "降级配置里 BYPASS_APPS_LIST 必须含自己，否则隧道 socket 仍会被 TPROXY 抓回（实际: '$bypass'）",
            bypass.contains("0:app.fjj.stun"),
        )
    }
}
