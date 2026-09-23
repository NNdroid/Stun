package app.fjj.stun.util

import app.fjj.stun.util.GridSpans.columnCount
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 节点列表"宽屏多列"的列数规则。
 *
 * 纯 JUnit（**不挂 Robolectric**）：[columnCount] 只做整数比较、不碰任何 Android 资源，
 * 而 core 模块没配默认 SDK —— 挂 `@RunWith(RobolectricTestRunner::class)` 会直接抛
 * `IllegalArgumentException`（`DefaultSdkPicker`）。这条坑见 MEMORY。
 *
 * ⚠️ 这里所有数字都用 **1px = 1dp** 来读（密度当 1）：被测函数是 px 对 px 的纯整数运算，
 * 结论跟密度无关，这样写只是为了看得懂。真正取 dp 的换算发生在 `getDimensionPixelSize`。
 */
class GridSpansTest {

    private val minColumn = 360
    private val minGridWidth = 600

    @Test
    fun `窄于门槛一律单列`() {
        assertEquals(1, columnCount(0, minColumn, minGridWidth))
        assertEquals(1, columnCount(320, minColumn, minGridWidth))
        assertEquals(1, columnCount(599, minColumn, minGridWidth))
    }

    /** 门槛是"小于才单列"，正好等于门槛就该分列了。 */
    @Test
    fun `正好到门槛就分列而不是等到下一档`() {
        assertEquals(2, columnCount(600, minColumn, minGridWidth))
    }

    /** 600/360 整除得 1，但既然已经到门槛就必须至少两列 —— 这是刻意夹到 2 的那条。 */
    @Test
    fun `门槛附近整除不足两列时补到两列`() {
        assertEquals(2, columnCount(600, minColumn, minGridWidth))
        assertEquals(2, columnCount(719, minColumn, minGridWidth))
        // 720 才开始真的整除得 2
        assertEquals(2, columnCount(720, minColumn, minGridWidth))
    }

    @Test
    fun `按每列最小宽度整除`() {
        assertEquals(2, columnCount(800, minColumn, minGridWidth))
        assertEquals(3, columnCount(1080, minColumn, minGridWidth))
        assertEquals(4, columnCount(1440, minColumn, minGridWidth))
        assertEquals(5, columnCount(1920, minColumn, minGridWidth))
    }

    @Test
    fun `超过上限就封顶`() {
        assertEquals(6, columnCount(3840, minColumn, minGridWidth))
        assertEquals(6, columnCount(10_000, minColumn, minGridWidth))
    }

    /** 每个模块的期望列数：同一套规则 + 各自的"每列最小宽"。 */
    @Test
    fun `四个模块的典型宽度`() {
        // app（手机/平板，每列 360dp）
        assertEquals("393dp 手机", 1, columnCount(393, minColumn, minGridWidth))
        assertEquals("800dp 平板竖屏", 2, columnCount(800, minColumn, minGridWidth))
        assertEquals("1280dp 平板横屏", 3, columnCount(1280, minColumn, minGridWidth))

        // tv（每列 480dp —— 10 尺 UI 卡片要比手机大）
        val tvMinColumn = 480
        assertEquals("960dp 电视", 2, columnCount(960, tvMinColumn, minGridWidth))
        assertEquals("1280dp 电视", 2, columnCount(1280, tvMinColumn, minGridWidth))
        assertEquals("1920dp 4K 电视", 4, columnCount(1920, tvMinColumn, minGridWidth))

        // xr：列表只占屏宽 60%，传进来的是列表自己的宽度
        assertEquals("XR 列表实际 960dp", 2, columnCount(960, minColumn, minGridWidth))
    }

    /** 参数退化时不能除零、也不能返回 0 或 1（否则调用方会算出"两列以外"的怪值）。 */
    @Test
    fun `参数退化时不除零且结果始终在 2 与上限之间`() {
        // 每列最小宽被夹到 1px ⇒ 宽度全用来分列，最终由上限封顶（生产路径不会这么传）
        assertEquals(GridSpans.DEFAULT_MAX_COLUMNS, columnCount(1000, 0, minGridWidth))
        assertEquals(GridSpans.DEFAULT_MAX_COLUMNS, columnCount(1000, -50, minGridWidth))
        // maxColumns 小于 2 时夹到 2：调用方拿到的永远是"要么单列(1)、要么至少两列"
        assertEquals(2, columnCount(1000, minColumn, minGridWidth, maxColumns = 0))
        assertEquals(2, columnCount(1000, minColumn, minGridWidth, maxColumns = 1))
    }

    /** maxColumns 必须是可控上限，而不是被 coerceIn 悄悄改掉。 */
    @Test
    fun `上限可以调小`() {
        assertEquals(2, columnCount(1920, minColumn, minGridWidth, maxColumns = 2))
        assertEquals(3, columnCount(1920, minColumn, minGridWidth, maxColumns = 3))
    }
}
