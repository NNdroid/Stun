package app.fjj.stun.util

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「平台能力（root / Shizuku）必须走统一入口」的机械护栏。
 *
 * 这类收口最容易的失败方式不是改错，而是**慢慢漏回去**：某天有人写 root 命令时图方便直接
 * `Shell.cmd(...)`，某天有人嫌 `ShizukuUtils.state()` 绕就自己 `Shizuku.pingBinder()` 一下。
 * 两次都不报错，两次都让"唯一入口"变成谎言。
 *
 * 钉住的四条：
 * 1. root 只能从 [RootShell] 进（`com.topjohnwu.superuser.Shell` 不许出现在 util 之外）；
 * 2. Shizuku 只能从 [ShizukuUtils] 进（`rikka.shizuku.Shizuku` 同理）；
 * 3. 「给 app 加后台豁免」只有一个实现 [BackgroundExemptions] —— 调用点不许内联
 *    `cmd deviceidle` / `am set-standby-bucket` / `appops set` 这三条命令；
 * 4. 流量口径只有一个实现 [app.fjj.stun.service.TrafficStatsSink]。
 */
class PlatformCapabilityParityTest {

    private fun sourceFile(relative: String): String {
        val candidates = listOf(File(relative), File("core/$relative"), File("../$relative"))
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("找不到 $relative：尝试过 ${candidates.map { it.absolutePath }}")
    }

    private val vpnService get() = sourceFile("src/main/java/app/fjj/stun/service/MyVpnService.kt")
    private val tproxyService get() = sourceFile("src/main/java/app/fjj/stun/service/MyTransparentProxyService.kt")
    private val keepAliveManager get() = sourceFile("src/main/java/app/fjj/stun/remote/KeepAliveManager.kt")
    private val appBootstrap get() = sourceFile("src/main/java/app/fjj/stun/util/AppBootstrap.kt")

    /** 遍历 app/core 的 main 源文件（跳过 build 与 util 包本身）。 */
    private fun mainSources(excludingUtilPackage: Boolean): List<File> {
        val roots = listOf(File("src/main/java"), File("core/src/main/java"))
            .filter { it.isDirectory }
        return roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".kt") }
                .filter { !(excludingUtilPackage && it.path.replace('\\', '/').contains("/app/fjj/stun/util/")) }
                .toList()
        }
    }

    /**
     * 剥掉注释再判断"这段源码里有没有出现某关键字"。
     *
     * 护栏必须只看**代码**：这些服务的 KDoc 里大量解释了"为什么不内联 deviceidle/appops"
     * （那是本护栏存在的理由本身），裸 `contains` 会把自己的说明文档判成违规 ——
     * 第一次跑就红，然后所有人都会把断言改成 `false` 来"修绿"，护栏就此失效。
     */
    private fun code(relative: String): String = stripComments(sourceFile(relative))

    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            when {
                // 字符串字面量：原样拷贝（含转义），否则注释里的 "http://" 会被误判
                src[i] == '"' -> {
                    val start = i
                    i++
                    while (i < src.length && src[i] != '"') {
                        if (src[i] == '\\') i++
                        i++
                    }
                    i = (i + 1).coerceAtMost(src.length)
                    out.append(src, start, i)
                }
                src.startsWith("//", i) -> {
                    while (i < src.length && src[i] != '\n') i++
                }
                src.startsWith("/*", i) -> {
                    i += 2
                    while (i < src.length && !src.startsWith("*/", i)) i++
                    i = (i + 2).coerceAtMost(src.length)
                }
                else -> {
                    out.append(src[i]); i++
                }
            }
        }
        return out.toString()
    }

    @Test
    fun rootIsOnlyReachableThroughRootShell() {
        val offenders = mainSources(excludingUtilPackage = true)
            .filter { stripComments(it.readText()).contains("com.topjohnwu.superuser") }
            .map { it.name }
        assertTrue("这些文件绕过了 RootShell 直接用 libsu：$offenders", offenders.isEmpty())
    }

    /**
     * root 判定**必须**用 `RootShell.isRoot()`。
     *
     * 上一条护栏只拦"直接 import libsu"，漏了一个更隐蔽的绕法：把 `RootShell.isRoot()`
     * 换成 `ShizukuUtils.isAvailable()`（用全限定名，连 import 都不用加）。这样**能编译、
     * 测试全绿**，但语义已经错了 —— Shizuku 在跑不等于设备有 root，用户没开 Magisk 时
     * tproxy 会拿着"有权限"的假象继续往下走，最后在第一条 root 命令上莫名失败。
     *
     * ⚠️ 只列 **tproxy**，别把 `MyVpnService` 也写进来：VPN 模式走系统 `VpnService`，
     * **本来就不需要 root**，全仓 grep `RootShell` 在该文件里是零命中 —— 硬加这条断言会
     * 逼着代码去加一个根本没用的 root 判定（第一版就犯了这个错，直接把测试写红了）。
     *
     * ⚠️ 这里**故意写成无条件断言**。第一版是 `if (code.contains("isRoot")) assertTrue(...)`，
     * 结果反向验证直接漏掉：注入把 `RootShell.isRoot()` 整段换成别的东西后，**`isRoot`
     * 这个词也一并消失** ⇒ 前置条件不成立 ⇒ 断言被静默跳过 ⇒ 护栏形同虚设。
     * **断言的前置条件绝不能依赖被验证的内容本身**，否则最典型的回归恰好能把它绕掉。
     */
    @Test
    fun rootPermissionCheckGoesThroughRootShellIsRoot() {
        assertTrue(
            "tproxy 的 root 判定必须走 RootShell.isRoot()（不能换成 Shizuku 等其它能力）",
            code("src/main/java/app/fjj/stun/service/MyTransparentProxyService.kt")
                .contains("RootShell.isRoot()"),
        )
    }

    @Test
    fun shizukuApiIsOnlyReachableThroughShizukuUtils() {
        val offenders = mainSources(excludingUtilPackage = true)
            .filter {
                val t = stripComments(it.readText())
                t.contains("rikka.shizuku.Shizuku") || t.contains("import rikka.shizuku")
            }
            .map { it.name }
        assertTrue("这些文件绕过了 ShizukuUtils 直接用 Shizuku API：$offenders", offenders.isEmpty())
    }

    @Test
    fun execUtilsIsGone() {
        // 它把 root 执行和资产部署混在一个类里，两件无关的事共用一个名字。
        for (c in listOf(File("core/src/main/java/app/fjj/stun/util/ExecUtils.kt"), File("src/main/java/app/fjj/stun/util/ExecUtils.kt"))) {
            assertFalse("ExecUtils 应已拆分（root→RootShell，资产→AssetDeployer）", c.exists())
        }
    }

    @Test
    fun backgroundExemptionCommandsExistInExactlyOnePlace() {
        // 调用点只准调 BackgroundExemptions，不准自己拼这三条系统命令。
        // 注意用 code() 而非原文：这三份 KDoc 里都写着"为什么不内联这些命令"。
        for ((name, src) in listOf(
            "MyVpnService" to code("src/main/java/app/fjj/stun/service/MyVpnService.kt"),
            "MyTransparentProxyService" to code("src/main/java/app/fjj/stun/service/MyTransparentProxyService.kt"),
            "KeepAliveManager" to code("src/main/java/app/fjj/stun/remote/KeepAliveManager.kt"),
        )) {
            assertFalse("$name 内联了 deviceidle 白名单命令", src.contains("deviceidle"))
            assertFalse("$name 内联了 appops 命令", src.contains("appops"))
            assertFalse("$name 内联了 standby-bucket 命令", src.contains("set-standby-bucket"))
        }
    }

    @Test
    fun allExemptionCallSitesGoThroughTheSingleEntry() {
        for ((name, src) in listOf(
            "MyVpnService" to vpnService,
            "MyTransparentProxyService" to tproxyService,
            "KeepAliveManager" to keepAliveManager,
        )) {
            assertTrue("$name 应调用 BackgroundExemptions", src.contains("BackgroundExemptions."))
        }
        // 旧的四个方法名必须消失，否则"两份实现"只是换了个地方。
        for (gone in listOf("addSelfToBatteryWhitelist", "setStandbyBucketActive", "applyShizukuOptimizations")) {
            assertFalse("旧入口 $gone 仍存在，说明还有第二份实现", vpnService.contains(gone))
            assertFalse("旧入口 $gone 仍存在，说明还有第二份实现", tproxyService.contains(gone))
        }
    }

    @Test
    fun shizukuStateIsOwnedByShizukuUtils() {
        // 三态判定只在 ShizukuUtils.state() 一处；KeepAliveManager 不再自带一份。
        assertFalse("KeepAliveManager 不该再自定义状态枚举", keepAliveManager.contains("enum class ShizukuState"))
        assertTrue(keepAliveManager.contains("ShizukuUtils.state()"))
    }

    @Test
    fun trafficAccountingHasOneImplementation() {
        for ((name, src) in listOf(
            "MyVpnService" to vpnService,
            "MyTransparentProxyService" to tproxyService,
        )) {
            assertTrue("$name 应通过 TrafficStatsSink 落库", src.contains("statsSink.ingest("))
            // 同上：基准字段名在 KDoc 里被专门解释过 why，必须只看代码。
            val codeOnly = stripComments(src)
            assertFalse("$name 不该再自己算 delta", codeOnly.contains("lastSessionTx"))
            assertFalse("$name 不该再自己算 delta", codeOnly.contains("lastSessionRx"))
            assertFalse("$name 不该绕过 sink 自己调 addTrafficStats", codeOnly.contains("ProfileManager.addTrafficStats"))
        }
    }

    /** 两种模式都必须在停服前补齐最后一小段流量；缺了就会永久丢几十 KB。 */
    @Test
    fun bothServiceModesFlushPendingTrafficOnStop() {
        assertTrue("VPN 停服前应补齐收尾流量", stripComments(vpnService).contains("statsSink.flushPending("))
        assertTrue("tproxy 停服前应补齐收尾流量", stripComments(tproxyService).contains("statsSink.flushPending("))
    }

    @Test
    fun assetDeploymentGoesThroughAssetDeployer() {
        assertTrue(appBootstrap.contains("AssetDeployer.needsDeploy("))
        assertTrue(appBootstrap.contains("AssetDeployer.deployIfNeeded("))
        assertFalse("AppBootstrap 不该再自带部署判定", appBootstrap.contains("fun needsDeploy"))
    }
}
