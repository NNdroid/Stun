package app.fjj.stun.util

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import app.fjj.stun.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ThemeColors] 加的两级缓存**不能改变取色结果** —— 这是它唯一的风险点，所以拿改造前的
 * 内联实现当基准逐属性对拍。
 *
 * 被替换掉的内联实现在 `ProfileAdapter` 与 `HomeFragment` 里各有一份（逻辑相同），
 * 都长这样：按名字 `getIdentifier` 找 attrId → `resolveAttribute` → 有 resourceId 走
 * `ContextCompat.getColor`、否则取 `data`。本测试的 [legacy] 就是它的逐字翻版。
 *
 * 另外锁两条容易踩的边界：
 * - **未知属性**必须回落到**调用点自己传的** default。同一个 attr 名在不同调用点有不同
 *   default（`colorPrimary` 有人传 BLUE、有人传 RED），一旦把 default 也缓存进去就会串味；
 * - **日夜两套主题互不串味**。`Theme.Stun` 在 `values-night` 有另一套 `md_theme_dark_*`，
 *   缓存键漏掉主题就要么留白天的色、要么留夜里的色。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
class ThemeColorsTest {

    /** 改造前的内联实现，逐字抄自 ProfileAdapter / HomeFragment：结果必须与它完全一致。 */
    private fun legacy(context: Context, attrName: String, default: Int): Int {
        val attrId = context.resources.getIdentifier(attrName, "attr", context.packageName).takeIf { it != 0 }
            ?: context.resources.getIdentifier(attrName, "attr", "android").takeIf { it != 0 }
            ?: return default

        val typedValue = TypedValue()
        return if (context.theme.resolveAttribute(attrId, typedValue, true)) {
            if (typedValue.resourceId != 0) {
                ContextCompat.getColor(context, typedValue.resourceId)
            } else {
                typedValue.data
            }
        } else {
            default
        }
    }

    private fun themedContext(night: Boolean): Context {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        val config = Configuration(activity.resources.configuration).apply {
            uiMode = if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        return ContextThemeWrapper(activity.createConfigurationContext(config), R.style.Theme_Stun)
    }

    /** 面板/列表实际取的那批属性（ProfileAdapter 3 个 + HomeFragment 2 个 + 延迟色 3 个）。 */
    private val attrs = listOf(
        "colorPrimary",
        "colorTertiary",
        "colorError",
        "colorOnSurface",
        "colorOnSurfaceVariant",
        "colorPrimaryContainer",
        "colorOnPrimaryContainer",
        "colorErrorContainer",
        "colorSurfaceContainerLow",
        "colorSurfaceContainerHigh",
    )

    private fun assertSameAsLegacy(ctx: Context, themeLabel: String) {
        for (attr in attrs) {
            assertEquals(
                "$themeLabel 下 attr=$attr 的取色结果被缓存改掉了",
                legacy(ctx, attr, -1),
                ThemeColors.color(ctx, attr, -1),
            )
        }
    }

    @Test
    fun `浅色主题下逐个属性都与改造前的内联实现一致`() {
        assertSameAsLegacy(themedContext(night = false), "浅色")
    }

    @Test
    fun `深色主题下逐个属性都与改造前的内联实现一致`() {
        assertSameAsLegacy(themedContext(night = true), "深色")
    }

    /**
     * `-1` 是"根本没解析到"的哨兵。上面那批属性在两种主题下都必须解析出真色，
     * 否则"与 legacy 一致"就退化成了"两边一起回落到 default"，测试变成假的。
     */
    @Test
    fun `这批属性在两种主题下都解析出了真色`() {
        for (night in listOf(false, true)) {
            val ctx = themedContext(night)
            for (attr in attrs) {
                assertNotEquals(
                    "${if (night) "深色" else "浅色"} 下 attr=$attr 没解析到颜色（两边一起回落到 default，" +
                        "会让「与 legacy 一致」的断言失去意义）",
                    -1,
                    ThemeColors.color(ctx, attr, -1),
                )
            }
        }
    }

    /** 同一个 attr 名、不同调用点传不同 default：谁也不能把别人的 default 缓存下来。 */
    @Test
    fun `未知属性回落到调用点自己的默认值`() {
        val ctx = themedContext(night = false)
        assertEquals(0x111111, ThemeColors.color(ctx, "stun_attr_that_does_not_exist", 0x111111))
        assertEquals(0x222222, ThemeColors.color(ctx, "stun_attr_that_does_not_exist", 0x222222))
        assertEquals(0x333333, ThemeColors.color(ctx, "stun_another_missing_attr", 0x333333))
    }

    /** 缓存命中之后结果仍恒定（连接中每秒要取两次，不能越取越飘）。 */
    @Test
    fun `重复取色结果稳定`() {
        val ctx = themedContext(night = false)
        for (attr in attrs) {
            val first = ThemeColors.color(ctx, attr, -1)
            repeat(5) { assertEquals("attr=$attr 第 ${it + 2} 次取色变了", first, ThemeColors.color(ctx, attr, -1)) }
        }
    }

    /** 先取深色、回头再取浅色：浅色不能被深色的缓存条目顶掉。 */
    @Test
    fun `深浅两套主题的取色互不串味`() {
        val day = themedContext(night = false)
        val night = themedContext(night = true)

        val nightSurface = ThemeColors.color(night, "colorOnSurface", -1)
        val daySurface = ThemeColors.color(day, "colorOnSurface", -1)

        assertEquals(legacy(night, "colorOnSurface", -1), nightSurface)
        assertEquals(legacy(day, "colorOnSurface", -1), daySurface)
        // colorOnSurface 深浅两套必然不同（近黑 / 近白）—— 用不等的值当串味的探针最可靠。
        assertNotEquals("深浅两套主题的 colorOnSurface 不该相同，「互不串味」断言无意义", daySurface, nightSurface)
    }
}
