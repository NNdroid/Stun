package app.fjj.stun.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BackgroundExemptions] 的命令构造。
 *
 * 这类断言的价值全在**反事实**：这些命令是要 root / shell 身份打到系统上去的，
 * 拼错一个 token（`+pkg` 写成 `pkg`、`default` 写成 `ignore`）在编译期和启动期都不会报错，
 * 只表现为"开关点了没反应"或"白名单永远撤不掉"。所以把字面量钉死在这里。
 *
 * 纯函数、不碰 Android ⇒ 裸 JUnit 即可（`Build.VERSION` 的门槛判断在 [applyViaShizuku] 里，
 * 不在本文件的纯命令构造里）。
 */
class BackgroundExemptionsTest {

    private val pkg = "app.fjj.stun"

    @Test
    fun deviceIdleWhitelistUsesCmdAndSignPrefix() {
        // 用 `cmd` 而不是历史遗留的 `dumpsys`：两条通道现在跑同一条命令，
        // 不会再出现"root 下能加白名单、Shizuku 下加不上"的分裂。
        assertArrayEquals(
            arrayOf("cmd", "deviceidle", "whitelist", "+app.fjj.stun"),
            BackgroundExemptions.deviceIdleWhitelist(pkg, grant = true),
        )
        assertArrayEquals(
            arrayOf("cmd", "deviceidle", "whitelist", "-app.fjj.stun"),
            BackgroundExemptions.deviceIdleWhitelist(pkg, grant = false),
        )
    }

    @Test
    fun standbyBucketIsActive() {
        assertArrayEquals(
            arrayOf("am", "set-standby-bucket", "app.fjj.stun", "active"),
            BackgroundExemptions.standbyBucketActive(pkg),
        )
    }

    @Test
    fun appopsCarriesOpAndMode() {
        assertArrayEquals(
            arrayOf("appops", "set", "app.fjj.stun", "RUN_IN_BACKGROUND", "allow"),
            BackgroundExemptions.appops(pkg, "RUN_IN_BACKGROUND", "allow"),
        )
    }

    @Test
    fun grantAndRevertDifferOnlyByDeviceIdleSign() {
        // 反事实：撤销必须只差一个 `+`/`-`，appops 侧靠 mode 区分（allow → default）。
        // 撤销写成 `ignore` 是**功能性**错误：等于替用户永久禁止后台运行，且再也回不来。
        val grant = BackgroundExemptions.deviceIdleWhitelist(pkg, grant = true).joinToString(" ")
        val revert = BackgroundExemptions.deviceIdleWhitelist(pkg, grant = false).joinToString(" ")
        assertTrue(grant.endsWith("whitelist +$pkg"))
        assertTrue(revert.endsWith("whitelist -$pkg"))
        assertFalse("撤销绝不能用 ignore", revert.contains("ignore"))
    }

    @Test
    fun everyCommandIsTokenizedForShellExecution() {
        // Shizuku 通道传的是 Array<String>（不走 shell 解析），所以**不能**有任何
        // 需要 shell 展开/引号处理的写法；root 通道用 joinToString(" ") 交给 su 解析。
        // 这里守住"token 里不含空格"这条底线，否则两条通道的行为会不一致。
        val all = listOf(
            BackgroundExemptions.deviceIdleWhitelist(pkg, grant = true),
            BackgroundExemptions.deviceIdleWhitelist(pkg, grant = false),
            BackgroundExemptions.standbyBucketActive(pkg),
            BackgroundExemptions.appops(pkg, "WAKE_LOCK", "default"),
        )
        for (cmd in all) {
            assertTrue("命令含空格 token：$cmd", cmd.none { it.contains(' ') })
            assertTrue("命令含引号：$cmd", cmd.none { it.contains('"') })
        }
    }
}
