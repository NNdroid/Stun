package app.fjj.stun.ui.view

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import app.fjj.stun.R
import app.fjj.stun.geo.GeoPoint
import app.fjj.stun.geo.GeoLocator
import app.fjj.stun.geo.GlobeArc
import app.fjj.stun.geo.GlobeMarker
import app.fjj.stun.geo.GlobeNode
import app.fjj.stun.geo.GlobeTopology
import app.fjj.stun.geo.GlobeTopologyBuilder
import app.fjj.stun.geo.NullGeoResolver
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [GlobeView] 的**真实渲染**回归：NATIVE 图形模式画进 Bitmap，既出预览图也做像素断言。
 *
 * 为什么不能只做结构断言：这个视图的价值全在"画对了没有"。
 * 海岸线没解码、相机没对准 hub、弧线没画 —— 这些都不会崩，只会画出一张空球。
 *
 * 预览图落在 `app/build/reports/ui-preview/globe-*.png`，人肉扫一眼即可确认视觉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlobeViewTest {

    // ────────────────────────────────────────────────────────── 尺寸

    @Test
    fun `方形自适应_高度等于宽度`() {
        val view = GlobeView(themedContext())
        val width = dp(view, 320f)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            // XML 里写 wrap_content 就是这个组合：高度 AT_MOST/UNSPECIFIED，由视图自己决定。
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        assertEquals("方形自适应要求高度跟随宽度", width, view.measuredHeight)
    }

    @Test
    fun `外层写死高度时尊重外层_球按短边缩不溢出`() {
        val view = GlobeView(themedContext())
        val width = dp(view, 320f)
        val forcedHeight = dp(view, 180f)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(forcedHeight, View.MeasureSpec.EXACTLY),
        )
        assertEquals(width, view.measuredWidth)
        assertEquals(forcedHeight, view.measuredHeight)
    }

    // ────────────────────────────────────────────────────────── 渲染

    /**
     * 一条**决定实现方式**的底层事实：`Canvas.drawBitmapMesh` 没有 UV 参数。
     *
     * 它收到的 `verts` 只是"网格顶点被挪到哪儿"，贴图坐标是**隐式均匀网格**——
     * 第 i 列的 u 恒等于 `i / meshWidth`，跟 verts 把它挪到哪儿毫无关系。
     * 于是"把 equirectangular 贴图按正确经纬度铺上球面"这件事，只能靠
     * **让网格列与贴图列对齐**（整张 360° 全宽、世界锚定的网格），
     * 不能像 `drawVertices` 那样自由指定 UV。
     *
     * 这个事实不写下来，下一个人极容易以为 verts 的数组里能顺手带上 u
     * ——历史实现就是这么错的（算了一组 UV 却压根没传进去，等于把 180° 可见半球
     * 当成整张贴图来铺，全球地图被压进了正面半球）。
     */
    @Test
    fun `drawBitmapMesh 的贴图坐标是隐式均匀网格_不随顶点位置移动`() {
        val tex = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        tex.setPixel(0, 0, Color.RED)
        tex.setPixel(1, 0, Color.BLUE)

        val out = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { isFilterBitmap = false }
        // 1 格网格（2 列 1 行）整体挪到 x ∈ [4, 8]：如果 u 跟着顶点走，
        // 这一格里应该已经没有红像素了；u 是隐式均匀网格的话，它照样是"左红右蓝"。
        Canvas(out).drawBitmapMesh(
            tex, 1, 1,
            floatArrayOf(4f, 0f, 8f, 0f, 4f, 4f, 8f, 4f), 0, null, 0, paint,
        )

        assertEquals("网格左端应取贴图 u=0（红）", Color.RED, out.getPixel(5, 2))
        assertEquals("网格右端应取贴图 u=1（蓝）", Color.BLUE, out.getPixel(7, 2))
    }

    /**
     * 另一条同样**决定实现方式**的事实：`verts` 的排列是**行主序**（先横后纵）。
     *
     * 官方文档只说"meshWidth+1 个横向顶点、meshHeight+1 个纵向顶点、数组是 x0,y0,x1,y1…"，
     * 没明说是"一行一行排"还是"一列一列排"。这里用**单个四边形**来钉：四边形只有一个，
     * 两种排法算出来的几何完全一样，唯一区别是"哪个角取到贴图哪个角"——
     * 于是贴图四个角落涂四种颜色就能一眼看出排法。
     *
     * 排错了不会崩、也不会报错，只会让整张贴图在球上被打乱（本文件的历史实现已经踩过一次：
     * 顶点按行填、却按列读，结果球面是一团噪声）。
     */
    @Test
    fun `drawBitmapMesh 的顶点数组是行主序_先横后纵`() {
        val tex = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        tex.setPixel(0, 0, Color.RED)     // u=0, v=0（贴图左上）
        tex.setPixel(1, 0, Color.GREEN)   // u=1, v=0（右上）
        tex.setPixel(0, 1, Color.BLUE)    // u=0, v=1（左下）
        tex.setPixel(1, 1, Color.WHITE)   // u=1, v=1（右下）

        val out = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { isFilterBitmap = false }
        // 行主序填：左上、右上、左下、右下。按列读会把右上/左下对调。
        Canvas(out).drawBitmapMesh(
            tex, 1, 1,
            floatArrayOf(0f, 0f, 8f, 0f, 0f, 8f, 8f, 8f), 0, null, 0, paint,
        )

        assertEquals("左上角应取贴图左上（红）", Color.RED, out.getPixel(1, 1))
        assertEquals(
            "右上角应取贴图右上（绿）—— 若是蓝的，说明 verts 是列主序，网格要按列填",
            Color.GREEN, out.getPixel(6, 1),
        )
        assertEquals("左下角应取贴图左下（蓝）", Color.BLUE, out.getPixel(1, 6))
        assertEquals("右下角应取贴图右下（白）", Color.WHITE, out.getPixel(6, 6))
    }

    @Test
    fun `海岸线必须真的画出来_而不是一个空球`() {
        val view = newSizedView()
        view.submitTopology(null) // 只画球体 + 海岸线
        val bitmap = paint(view, "globe-coastline-only")

        // 空球只有两种颜色（垫底色 + 海洋）。陆地填充 + 海岸线描边会引入明显更多的层次。
        val colors = distinctColorsInDisc(bitmap)
        assertTrue(
            "球面内颜色层次只有 $colors 种 —— 海岸线资产很可能没解码成功（退化成空球）",
            colors >= 4,
        )
    }

    @Test
    fun `相机对准hub_正中心就是当前节点`() {
        val themed = themedContext()
        val view = newSizedView(themed)
        view.submitTopology(topology())
        val bitmap = paint(view, "globe-full")

        // hub 落在 (0,0)：对准之后它精确投影到盘心，所以正中心那颗像素必须是"在线"语义色。
        // 这一条同时钉住了三件事：hub 参与了绘制、相机确实对准了 hub、标记颜色用了语义色资源。
        assertEquals(
            "盘心必须是 hub 的在线语义色",
            themed.getColor(R.color.connection_state_online),
            bitmap.getPixel(bitmap.width / 2, bitmap.height / 2),
        )
    }

    /**
     * 对准必须**精确**，不能被摆动相位带偏。
     *
     * 自转改成绕中心摆动之后，多了"相位"这个能影响相机经度的东西。如果 [GlobeView.submitTopology]
     * 只写了 `lonDeg` 而没管摆动状态（或者对准时相位不是 0），盘心就会偏开 hub ——
     * 用户打开面板第一眼看见的就不是自己连的节点了。用**非零** hub 才测得出这一条。
     */
    @Test
    fun `提交拓扑后摆动相位必须归零_盘心精确落在hub上`() {
        val themed = themedContext()
        val view = newSizedView(themed)
        val hub = GeoPoint(30.0, 30.0, "JP")
        view.submitTopology(
            GlobeTopology(
                markers = listOf(GlobeMarker(point = hub, isCurrent = true, label = "hub")),
                arcs = emptyList(),
                available = true,
            ),
        )
        val bitmap = paint(view, "globe-hub-centered-nonzero")

        assertEquals(
            "hub 在 (30,30) 时盘心必须是 hub —— 偏了就说明摆动相位在提交时没归零",
            themed.getColor(R.color.connection_state_online),
            bitmap.getPixel(bitmap.width / 2, bitmap.height / 2),
        )
    }

    @Test
    fun `画标记与弧线后颜色层次变多`() {
        // 弧线是半透明细线，横跨海洋与陆地，必然带来新的混色。
        // 这里不去断言"某个像素等于某个值"（混色值依赖抗锯齿，卡不牢），
        // 而是断言"确实多画了东西"；弧线几何形状由预览图人工确认。
        val plain = newSizedView().apply { submitTopology(null) }
        val withTopology = newSizedView().apply { submitTopology(topology()) }

        val plainColors = distinctColorsInDisc(paint(plain, "globe-coastline-only"))
        val fullColors = distinctColorsInDisc(paint(withTopology, "globe-full"))

        assertTrue(
            "加了标记和弧线之后颜色层次应该变多：$plainColors → $fullColors",
            fullColors > plainColors,
        )
    }

    @Test
    fun `星空模式铺近黑太空_扁平模式铺不透明主题托盘`() {
        // 两种模式的背景契约：
        // · 星空 = 铺满不透明的近黑太空（星野叠在上面）；
        // · 扁平 = 铺一层**不透明**的主题托盘（球外面那一圈方框底）。
        //
        // 这里刻意断言扁平是"不透明的托盘色"而不是"透明"：早先是 drawColor(CLEAR) 清帧，
        // 结果是整个方框消失、卡片底色直接透出来 —— 用户看到的现象就是
        // "关闭星空模式的时候背景为什么变成了透明的"。它同时守住了 CLEAR 原本的用意
        // （不留上一帧的近黑残影）：角落必须正好是托盘色，不许是太空色。
        val flat = newSizedView().apply { submitTopology(null) }
        val starry = newSizedView().apply { starryMode = true; submitTopology(null) }

        val flatCorner = paint(flat, "globe-flat-corner").getPixel(2, 2)
        val starryBitmap = paint(starry, "globe-starry-corner")

        // 星野是真实贴图（geo/stars.jpg）：单像素可能恰好落在一颗星上，
        // 所以"近黑太空"按角落小块的平均亮度判定，同时要求整块都不透明。
        var sum = 0L
        var count = 0
        var allOpaque = true
        for (y in 0 until 24) {
            for (x in 0 until 24) {
                val p = starryBitmap.getPixel(x, y)
                if (Color.alpha(p) <= 200) allOpaque = false
                sum += Color.red(p) + Color.green(p) + Color.blue(p)
                count++
            }
        }
        assertTrue(
            "星空模式角落应是不透明的近黑太空，实际平均亮度=${sum / count}，allOpaque=$allOpaque",
            allOpaque && sum / count < 60,
        )

        assertEquals(
            "扁平模式的球外角落必须是不透明的主题托盘色（既不能透明，也不能是太空残影）",
            themeColorOf(flat, "colorSurfaceContainerLow"),
            flatCorner,
        )
    }

    /**
     * 星空模式开关**不许改变配色本身**：关掉之后必须逐像素回到"从没切过"的样子。
     *
     * ⚠️ 守的是 `Paint.setColor()` 的隐式副作用 —— 它会连 alpha 通道一起重置成**传入色的**
     * alpha（palette 里的值全是 FF）。所以只在 `applyPaletteToPaints()` 里写
     * `paint.color = palette.x`，就会把 init 里辛苦设好的 `LAND_ALPHA / COASTLINE_ALPHA /
     * OCEAN_ALPHA` 三档透明度**永久冲成不透明**。用户看到的现象因此是：
     * 默认打开的面板是柔和的半透明淡洗（陆/海都靠托盘透出来），
     * 只要点过一次星空开关（开或关都算），球就变成实心近黑陆地 + 实心琥珀海洋，与浅色卡片完全不搭。
     *
     * 判据分两层，缺一不可：
     * ⓪ **默认那一帧**的盘面里不许出现纯 `colorOnSurface` / `colorPrimary` —— 半透明混过色的像素
     *    不可能正好等于纯色（实测默认 0 个；alpha 一丢 `colorOnSurface` 就出现十万个以上）。
     *    没有这一层，把 `init` 也改成不透明同样能让 ① 相等，但默认观感就悄悄变了。
     * ① 切换过开关之后整盘**逐像素**必须回到 ⓪ 那一帧的样子。取逐像素而不是"某个地理点颜色差不多"：
     *    这三档 alpha 一丢，整颗球每一寸都会变；逐像素比对既不用挑采样点，也顺手把
     *    "切换时顺手改了别的东西"一起钉住。
     */
    @Test
    fun `切换星空模式开关不改变扁平模式配色`() {
        val pristine = newSizedView().apply { submitTopology(null) }
        val toggled = newSizedView().apply { submitTopology(null) }
        toggled.starryMode = true
        toggled.starryMode = false

        val before = paint(pristine, "globe-flat-pristine")
        val after = paint(toggled, "globe-flat-after-toggle")

        // ⓪ 先把"默认那套长什么样"钉死：陆/海是**半透明淡洗**，所以盘面里不该出现
        // 纯 `colorOnSurface` / `colorPrimary` 像素 —— 半透明混过色的像素不可能正好等于纯色。
        // 少了这条，把 init 也改成不透明同样能让下面 ① 的逐像素相等，但用户看到的默认观感就变了。
        // 实测：默认盘内 0 个纯色像素；alpha 被冲掉后 `colorOnSurface` 会出现十万个以上。
        val onSurface = themeColorOf(pristine, "colorOnSurface")
        val primary = themeColorOf(pristine, "colorPrimary")
        val midX = before.width / 2
        val midY = before.height / 2
        val discRadius = minOf(midX, midY) * DISC_FILL_RATIO
        var solid = 0
        var sy = midY - discRadius
        while (sy <= midY + discRadius) {
            var sx = midX - discRadius
            while (sx <= midX + discRadius) {
                val dx = sx - midX
                val dy = sy - midY
                if (dx * dx + dy * dy <= discRadius * discRadius) {
                    val p = before.getPixel(sx.toInt(), sy.toInt())
                    if (p == onSurface || p == primary) solid++
                }
                sx += 1f
            }
            sy += 1f
        }
        assertEquals(
            "扁平模式默认渲染的盘面里出现了 $solid 个纯主题色像素（colorOnSurface / colorPrimary）—— " +
                "陆/海本该是半透明的淡洗，出现纯色说明透明度被冲成了 255",
            0,
            solid,
        )

        // ① 切换过开关之后，整盘必须逐像素回到上面那个样子。
        var diff = 0
        var sample = "（两图完全相同）"
        for (y in 0 until before.height) {
            for (x in 0 until before.width) {
                val pa = before.getPixel(x, y)
                val pb = after.getPixel(x, y)
                if (pa != pb) {
                    diff++
                    if (diff == 1) {
                        sample = "首处差异 ($x,$y)：#%08X → #%08X".format(pa, pb)
                    }
                }
            }
        }
        assertEquals(
            "切换过星空模式开关之后扁平模式变了 $diff 个像素（$sample）—— " +
                "十有八九是 applyPaletteToPaints() 里的 Paint.setColor() 把 alpha 一起冲掉了",
            0,
            diff,
        )
    }

    /**
     * 网格顶点算得再对，也得确认 `drawBitmapMesh` 真把贴图画到了盘面上。
     *
     * 判据取"盘面里的颜色种类数"：地球贴图是一张照片，画上去必然带来成千上万种颜色；
     * 而"贴图没生效、退回矢量球"只剩海洋渐变 + 海岸线 + 描边，量级差两个数量级。
     * 这条是 [GlobeView.debugMeshVerts] 那条几何断言的像素侧补充 —— 几何对不代表真画出来了。
     */
    @Test
    fun `星空模式的球面贴图必须真的画到盘面上`() {
        val view = newSizedView().apply { starryMode = true; submitTopology(null) }
        val colors = distinctColorsInDisc(paint(view, "globe-starry-textured"))
        assertTrue(
            "盘面只有 $colors 种颜色 —— 地球贴图很可能没画上去（退回成了矢量球）",
            colors > 500,
        )
    }

    /**
     * 深色主题下"球面必须跟托盘分得开"。
     *
     * 这条守的是用户报的那个 bug：扁平模式的海 / 陆 / 海岸线原本是 10% / 22% / 51% 的低 alpha
     * 淡洗，且整套值是按**浅色卡片**挑的 —— 深色主题下 `colorPrimary`(=#FFB77B) @10% 铺在
     * 卡片(#282220)上只剩 #3D3128，跟托盘几乎同色，球直接糊进背景里。
     * 判据用"逐通道绝对差之和"（不要求球一定更亮，只要求**分得开**），
     * 换成新的配色后 ≈199，回退到旧的淡洗则只有 ≈34。
     */
    @Test
    fun `深色主题下球面与托盘必须分得开`() {
        val view = newSizedView(themedContext(night = true)).apply { submitTopology(null) }
        val bitmap = paint(view, "globe-night-flat")

        val corner = bitmap.getPixel(2, 2)
        val center = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        val distance = Math.abs(Color.red(corner) - Color.red(center)) +
            Math.abs(Color.green(corner) - Color.green(center)) +
            Math.abs(Color.blue(corner) - Color.blue(center))
        assertTrue(
            "深色主题下球面与托盘几乎同色（通道差之和只有 $distance，corner=$corner center=$center）" +
                " —— 球糊进背景了",
            distance > 80,
        )
    }

    /**
     * 贴图必须是**球面投影**，不是平面裁窗。
     *
     * 平面裁窗把 equirectangular 图当矩形铺进圆里：所有经线的屏幕间距处处相等。
     * 正交球面投影则必须按 cos 因子把间距收向两侧视界 —— 球缘处相邻经线几乎重合，
     * 中心一格 ÷ 视界前一格 ≈ 23（72 列网格、每列 5°：`sin(5°) / (1 - sin(85°))`）。
     *
     * 判据只用**正对相机的那半圈**列：网格是按整张 360° 锚定的，背面那些列会被压到视界上
     * （间距恒为 0），混进来"最窄"就恒为 0，比值判据失去意义。
     *
     * 用网格顶点而不是像素：像素上抗锯齿和贴图内容都会干扰，顶点是精确的。
     */
    @Test
    fun `贴图按球面投影_经度间距收向两侧视界`() {
        val view = newSizedView()
        // hub 放 (0,0) ⇒ 相机也对准 (0,0)：纬度 0 那一行正好穿过盘心，几何最干净。
        view.submitTopology(
            GlobeTopology(
                markers = listOf(GlobeMarker(point = GeoPoint(0.0, 0.0, "CN"), isCurrent = true)),
                arcs = emptyList(),
                available = true,
            ),
        )

        val xs = meshRowX(view, 0f)
        val cols = xs.size - 1
        // 网格按世界经度锚定：第 i 列的经度是 -180 + 360·i/cols，相机经度 0 ⇒ 中心列是 i = cols/2。
        val center = cols / 2
        val toLimb = Math.round(90f / (360f / cols)).toInt()

        val centerGap = xs[center + 1] - xs[center]
        val limbGap = xs[center + toLimb] - xs[center + toLimb - 1]
        assertTrue(
            "两端经线间距没有收拢（中心格=$centerGap 视界前一格=$limbGap，" +
                "比值=${centerGap / limbGap}）—— 贴图又变回平面裁窗了",
            centerGap / limbGap > 10f,
        )

        // 从盘心往视界走，间距必须**单调**变窄 —— 这是 cos 因子的直接后果。
        val sampled = FloatArray(7) {
            val col = center + toLimb * it / 6
            xs[col + 1] - xs[col]
        }
        for (i in 1 until sampled.size) {
            assertTrue(
                "经线间距不是单调收向视界，第 $i 段反而变宽了：${sampled.toList()}",
                sampled[i] < sampled[i - 1],
            )
        }

        // 左右必须对称（同 cos 因子、同列距）。
        val mirrored = xs[center] - xs[center - 1]
        assertTrue(
            "盘心两侧的经线间距不对称（左=$mirrored 右=$centerGap）",
            abs(mirrored - centerGap) < centerGap * 0.02f,
        )
    }

    /**
     * 贴图必须**按经纬度对位**：既不能把整张图缩进球里，也不能左右镜像 / 上下颠倒。
     *
     * 这条是"`drawBitmapMesh` 的贴图坐标是隐式均匀网格"那个约束的**端到端**验证：
     * 只有网格整张 360° 按世界经度锚定，第 col 列才会正对贴图上 `col/MESH_COLS` 的经度。
     * 一旦退回"只铺可见半球"（网格只跨 ±90°），整张贴图会被压进正面半球 ——
     * 盘心取到的就不再是几内亚湾，而是别处的内容，这条断言立刻炸。
     *
     * 取样点选贴图里**大面积同色**的位置（这张图是扁平绘制：海洋恒为 `#1E3C74`，
     * 海洋点邻域 ±2° 只有一种颜色）。两组对比都跨"海 ↔ 陆"，所以判据取
     * **通道差之差**，不依赖配色也不依赖晨昏线压暗多少（幅值只按比例缩放）：
     * - 左右：(−20°, 0°) 大西洋 ↔ (−60°, 0°) 亚马逊
     * - 上下：(0°, −45°) 南大西洋 ↔ (25°, 50°) 俄罗斯/乌克兰
     * 镜像或整图缩放都会把某个"海"样本换成"陆"，差值符号翻转。
     *
     * "现在"钉在 [EQUINOX_NOON_UTC]：太阳直射点在 (0,0) = 盘心 ⇒ 整个可见盘面都在白昼侧，
     * 城市灯光层的 alpha 在盘内恒为 0（它从 1.1R 才起亮），晨昏线压暗也最轻。
     * 不钉的话同一时刻太阳可能在球背面，取样点会变成近乎全黑的夜面 —— 断言就成了掷骰子。
     */
    @Test
    fun `球面贴图按经纬度对位_盘心是几内亚湾`() {
        val view = newSizedView().apply {
            starryMode = true
            sunEpochMillis = EQUINOX_NOON_UTC
        }
        view.submitTopology(
            GlobeTopology(
                markers = listOf(GlobeMarker(point = GeoPoint(0.0, 0.0, "CN"), isCurrent = true)),
                arcs = emptyList(),
                available = true,
            ),
        )
        val bitmap = paint(view, "globe-starry-geo")

        val cx = bitmap.width / 2f
        val cy = bitmap.height / 2f
        val r = minOf(bitmap.width, bitmap.height) / 2f * DISC_FILL_RATIO
        // 相机对准 (0,0) ⇒ 正交投影下 x = cx + r·cos(lat)·sin(lon)，y = cy − r·sin(lat)。
        fun screenX(lon: Float, lat: Float) = cx +
            r * cos(Math.toRadians(lat.toDouble())).toFloat() * sin(Math.toRadians(lon.toDouble())).toFloat()
        fun screenY(lat: Float) = cy - r * sin(Math.toRadians(lat.toDouble())).toFloat()

        val atlantic = averageColor(bitmap, screenX(-20f, 0f), screenY(0f))
        val amazon = averageColor(bitmap, screenX(-60f, 0f), screenY(0f))
        val sAtlantic = averageColor(bitmap, screenX(0f, -45f), screenY(-45f))
        val russia = averageColor(bitmap, screenX(25f, 50f), screenY(50f))

        // 海洋偏蓝（b−r>0）、陆地偏黄绿（b−r<0）；"海的蓝度 − 陆的蓝度"两处都应明显为正。
        val lonOk = (Color.blue(atlantic) - Color.red(atlantic)) -
            (Color.blue(amazon) - Color.red(amazon))
        assertTrue(
            "经度方向没对上（镜像？整图缩进球里？）：大西洋样本=$atlantic 亚马逊样本=$amazon，" +
                "蓝度差=$lonOk，应 > 60",
            lonOk > 60,
        )
        val latOk = (Color.blue(sAtlantic) - Color.red(sAtlantic)) -
            (Color.blue(russia) - Color.red(russia))
        assertTrue(
            "纬度方向没对上（上下颠倒？）：南大西洋样本=$sAtlantic 俄罗斯样本=$russia，" +
                "蓝度差=$latOk，应 > 60",
            latOk > 60,
        )
    }

    /**
     * 相机经度**非退化**时贴图仍然对位 —— 贴图锚点/列偏移（`anchorLonFor` ↔
     * `DayNightCompositor.offsetPxFor`）的回归。
     *
     * 上一条测试把相机钉在 (0°, 0°)，而那恰好是锚点**退化成恒等**的位姿：目标经度
     * `round((lon+180)/30)·30` 在 lon=0 时取 180，`offsetPx = (180+180)/360·W mod W = 0`
     * —— 烘贴图时一列都不搬。于是"读源图时列偏移取错方向"这类 bug 在那条测试下完全隐形。
     *
     * 这条路真踩过（当时是位图循环移位）：两笔画在 `+offPx` / `-(W-offPx)`，而 `drawBitmap`
     * 的语义做出来的是 `out[x] = src[(x-offPx) mod W]` —— 做成了**关于中点镜像**而不是平移。
     * 后果是贴图整体转错一个角度：相机经度 111° 时误差正好 120°，"澳大利亚"的位置被画成了
     * 太平洋；而相机经度 ≈ ±180° 时误差恰好为 0，所以老测试永远看不见它。
     *
     * 现在这步并进了 [DayNightCompositor.compose] 的内层循环（输出第 x 列读源图第 `x + off`
     * 列，游标自增而不是取模），同样的方向错误仍然可能犯、症状也完全一样 —— 所以这条必须留着。
     *
     * 判据与上一条同构（海/陆的**蓝度差**），但相机经度换成了三个偏移量既不为 0、也不为 W/2
     * 的值，并且每组都配"一块大内陆 + 一片开阔洋"：一旦转错角度，陆地点会取到海、海点会取到
     * 陆，符号立刻翻转。每组都让太阳直射点跟着相机经度走（秋分赤纬 ≈0 ⇒ 直射点在赤道），
     * 并把取样点选在离直射点 35° 内，避开晨昏线过渡带带来的通道缩放干扰。
     */
    @Test
    fun `滚动贴图在非退化相机经度下仍然对位`() {
        // 相机经度 / 陆地点(lon,lat) / 海洋点(lon,lat)
        val cases = listOf(
            // offPx/W = 1/3：老实现的误差是 120°
            Triple(111.65f, floatArrayOf(134f, -25f), floatArrayOf(95f, -15f)),
            // offPx/W = 1/4：老实现的误差是 180°
            Triple(90f, floatArrayOf(78f, 22f), floatArrayOf(100f, -10f)),
            // offPx/W = 1/8：老实现的误差是 270°
            Triple(45f, floatArrayOf(25f, 25f), floatArrayOf(60f, -20f)),
        )
        for ((lon, land, sea) in cases) {
            val view = newSizedView().apply {
                starryMode = true
                sunEpochMillis = EQUINOX_NOON_UTC - (lon / 15f * 3_600_000L).toLong()
            }
            view.submitTopology(
                GlobeTopology(
                    markers = listOf(
                        // ⚠️ GeoPoint 是 (latitude, longitude)，别写反。
                        GlobeMarker(point = GeoPoint(0.0, lon.toDouble(), "CN"), isCurrent = true),
                    ),
                    arcs = emptyList(),
                    available = true,
                ),
            )
            val bitmap = paint(view, "globe-roll-lon${lon.toInt()}")
            val cx = bitmap.width / 2f
            val cy = bitmap.height / 2f
            val r = minOf(bitmap.width, bitmap.height) / 2f * DISC_FILL_RATIO
            // 相机纬度 0 ⇒ 正交投影下 x = cx + r·cos(lat)·sin(lon−lonC)，y = cy − r·sin(lat)。
            fun at(lonDeg: Float, latDeg: Float): Int = averageColor(
                bitmap,
                cx + r * cos(Math.toRadians(latDeg.toDouble())).toFloat() *
                    sin(Math.toRadians((lonDeg - lon).toDouble())).toFloat(),
                cy - r * sin(Math.toRadians(latDeg.toDouble())).toFloat(),
                6,
            )
            val landColor = at(land[0], land[1])
            val seaColor = at(sea[0], sea[1])
            val delta = (Color.blue(seaColor) - Color.red(seaColor)) -
                (Color.blue(landColor) - Color.red(landColor))
            assertTrue(
                "相机经度 $lon° 下贴图没对位（滚动方向错？）：陆地点(${land[0]},${land[1]})取到 " +
                    "$landColor，海洋点(${sea[0]},${sea[1]})取到 $seaColor，蓝度差=$delta 应 > 60",
                delta > 60,
            )
        }
    }

    /**
     * 夜面压暗的**形状**必须来自球面几何，不能是"以直射点屏幕投影为圆心的径向渐变"。
     *
     * 判据用一个不需要任何外部基准图的构造 —— 两帧、同一个相机（都对准 (0°,0°)）：
     * - 太阳落在**盘心**时，可见半球上任意点与直射点的角距都 ≤ 90°，所以**全盘都是白昼**、
     *   压暗恒等于 0。这一帧就是天然的"零压暗"基准图，而且它与下面那帧同相机、同贴图、
     *   同网格、同锚点（相机经度没动），白昼侧**逐像素**可比。
     * - 太阳移到相机以西 90° 时，晨昏线恰好是过盘心的一条竖线：盘面左半边（`right < 0`）
     *   仍在白昼，右半边是夜面。
     *
     * 于是断言很硬：左半边必须与基准帧几乎**完全一致**，右半边必须显著变暗。
     *
     * ⚠️ 这条测试在**旧实现下是假阴性**：当时夜面是"每帧裁出区域再叠 5 层"，而第 1 层
     * （θ = 90° 那层）算错了 —— 太阳在盘心时它会把整个盘面盖上，两帧于是被蒙了同一层，
     * "白昼侧偏差 ≈ 0"是这么来的，并不是真的没压暗。现在昼夜是**逐纹素烘进贴图**的
     * （见 `DayNightCompositor`），白昼侧是真的逐位等于白天贴图。
     * 真正能钉住"边界落在球面晨昏线上"的是下面两条（相机转动不翻面 + 分界过盘心）。
     */
    @Test
    fun `夜面压暗贴着真实晨昏线_白昼侧一点不压暗`() {
        fun frame(sunLon: Float, name: String): Bitmap {
            val view = newSizedView().apply {
                starryMode = true
                // 秋分 ⇒ 赤纬 ≈0 ⇒ 直射点在赤道，经度由 sunLon 定。
                sunEpochMillis = EQUINOX_NOON_UTC - (sunLon / 15f * 3_600_000L).toLong()
            }
            view.submitTopology(
                GlobeTopology(
                    // ⚠️ GeoPoint 是 (latitude, longitude)，别写反。
                    markers = listOf(GlobeMarker(point = GeoPoint(0.0, 0.0, "CN"), isCurrent = true)),
                    arcs = emptyList(),
                    available = true,
                ),
            )
            return paint(view, name)
        }

        val base = frame(0f, "globe-night-noshade")       // 太阳正对盘心 ⇒ 全盘白昼
        val term = frame(-90f, "globe-night-terminator")  // 太阳在球缘 ⇒ 晨昏线过盘心

        val cx = base.width / 2f
        val cy = base.height / 2f
        val r = minOf(base.width, base.height) / 2f * DISC_FILL_RATIO
        fun lum(c: Int) = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)

        // 纵向刻意避开盘心那一带：`isCurrent` 落点的名字胶囊压在球心，别把它算进采样。
        val ups = listOf(0.35f, 0.45f, -0.35f, -0.45f)

        var daySum = 0f
        var dayMax = 0f
        var dayN = 0
        for (right in listOf(-0.80f, -0.70f, -0.60f, -0.50f)) {
            for (up in ups) {
                val x = cx + right * r
                val y = cy - up * r
                val la = lum(averageColor(base, x, y, 3))
                val lb = lum(averageColor(term, x, y, 3))
                if (la < 24f) continue
                val d = abs(lb - la) / la
                daySum += d
                if (d > dayMax) dayMax = d
                dayN++
            }
        }
        var nightSum = 0f
        var nightN = 0
        for (right in listOf(0.55f, 0.65f, 0.78f)) {
            for (up in ups) {
                val x = cx + right * r
                val y = cy - up * r
                val la = lum(averageColor(base, x, y, 3))
                val lb = lum(averageColor(term, x, y, 3))
                if (la < 24f) continue
                nightSum += lb / la
                nightN++
            }
        }

        assertTrue("白昼侧采样太少（$dayN），判据不成立", dayN >= 10)
        assertTrue("夜面采样太少（$nightN），判据不成立", nightN >= 8)
        val dayAvg = daySum / dayN
        val nightAvg = nightSum / nightN
        assertTrue(
            "白昼侧被压暗了：相对偏差平均 %.3f / 最大 %.3f，应 ≈0。".format(dayAvg, dayMax) +
                "以直射点屏幕投影为圆心的径向渐变会在球缘漏压暗（直射点在盘心时球缘已有 ~12%）。",
            dayAvg < 0.04f,
        )
        assertTrue(
            "夜面压得不够：平均亮度比 %.3f，应 < 0.5".format(nightAvg),
            nightAvg < 0.5f,
        )
    }

    /**
     * ⭐ **"一转动就一闪一闪"的回归**（用户报的那条）。
     *
     * 判据：太阳钉死、相机（= hub）经度从 0° 转到 30°，同一个**世界坐标**上的昼夜归属
     * 必须一帧都不变 —— 昼点恒亮、夜点恒暗、而且各自的亮度几乎不漂。
     *
     * 旧实现（每帧重新裁"夜面区域 ∩ 可见半球"）在这里必然炸：θ = 90° 那一层的边界是大圆，
     * 与视界的两个交点恰好是对径点，于是"沿视界补弧往哪边绕"退化成 `atan2(±0, −1)` ——
     * **方向由浮点零的符号决定**。相机一转符号就翻，昼夜整块镜像，这正是用户看到的闪烁。
     * 现在昼夜是**逐纹素烘进贴图**的（`DayNightCompositor`），相机姿态根本不进烘焙，
     * 所以这条断言在结构上必然成立。
     */
    @Test
    fun `转动相机时昼夜不翻面`() {
        // 直射点 = (0°, +90°) ⇒ nd = cos(lat)·sin(lon)：lon > 0 是白昼、lon < 0 是黑夜。
        val sunEpoch = EQUINOX_NOON_UTC - 6 * 3_600_000L
        // ⚠️ GeoPoint 是 (latitude, longitude)，别写反。
        val dayPoint = GeoPoint(-35.0, 25.0, "?")
        val nightPoint = GeoPoint(-35.0, -25.0, "?")
        // nd = ±cos(35°)·sin(25°) ≈ ±0.35，离混合带（±0.04）很远 ⇒ 不掺抗锯齿的运气。
        val dayLum = ArrayList<Float>()
        val nightLum = ArrayList<Float>()

        for (camLon in listOf(0f, 15f, 30f)) {
            val view = newSizedView().apply {
                starryMode = true
                sunEpochMillis = sunEpoch
            }
            view.submitTopology(
                GlobeTopology(
                    markers = listOf(
                        GlobeMarker(point = GeoPoint(0.0, camLon.toDouble(), "CN"), isCurrent = true),
                    ),
                    arcs = emptyList(),
                    available = true,
                ),
            )
            val bitmap = paint(view, "globe-noflip-lon${camLon.toInt()}")
            val cx = bitmap.width / 2f
            val cy = bitmap.height / 2f
            val r = minOf(bitmap.width, bitmap.height) / 2f * DISC_FILL_RATIO
            fun lum(c: Int) = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
            fun at(p: GeoPoint): Float {
                val dLon = Math.toRadians(p.longitude - camLon)
                val latRad = Math.toRadians(p.latitude)
                val x = cx + r * (cos(latRad) * sin(dLon)).toFloat()
                val y = cy - r * sin(latRad).toFloat()
                return lum(averageColor(bitmap, x, y, 5))
            }
            dayLum += at(dayPoint)
            nightLum += at(nightPoint)
        }

        for (i in dayLum.indices) {
            assertTrue(
                "相机转到第 $i 档时昼夜翻面了：昼点亮度=${dayLum[i]}，夜点=${nightLum[i]}",
                dayLum[i] > nightLum[i] * 2f + 10f,
            )
        }
        val dayRatio = dayLum.maxOrNull()!! / dayLum.minOrNull()!!
        val nightRatio = nightLum.maxOrNull()!! / nightLum.minOrNull()!!
        assertTrue(
            "相机转动后同一个世界点的昼侧亮度漂了 %.2f 倍，应基本不变".format(dayRatio),
            dayRatio < 1.35f,
        )
        assertTrue(
            "相机转动后同一个世界点的夜侧亮度漂了 %.2f 倍，应基本不变".format(nightRatio),
            nightRatio < 1.6f,
        )
    }

    /**
     * 昼夜分界必须落在**球面晨昏线**投影出来的位置上，不能偏到夜侧。
     *
     * 构造：太阳固定在（赤纬 0°，东经 90°）⇒ 晨昏线就是 0°/180° 子午线；相机（hub）放在
     * (30°N, 0°)，于是这条子午线上任意点的 `v·east = cos(lat)·sin(0) = 0`
     * ⇒ **整条晨昏线在屏幕上就是竖直线 `x = cx`**，与相机纬度无关。
     *
     * 取样行取 `up = −0.5`：该行离盘心的 hub 胶囊最远（hub 按定义恒在盘心），
     * 而行内左右两侧 `lon ≈ ∓30°` 处分别是纯夜 / 纯昼（`nd = ∓0.5`）。
     *
     * 容差 0.06r：混合带自身有点积宽度 ±0.04，在赤道行上正好折成 ±0.04r。
     * 旧实现把分界偏到**夜侧 10°**（≈ 0.17r）并且整块镜像，这里会直接炸。
     */
    @Test
    fun `晨昏线分界过盘心_不偏到夜侧`() {
        val view = newSizedView().apply {
            starryMode = true
            sunEpochMillis = EQUINOX_NOON_UTC - 6 * 3_600_000L
        }
        view.submitTopology(
            GlobeTopology(
                // ⚠️ GeoPoint 是 (latitude, longitude)。
                markers = listOf(GlobeMarker(point = GeoPoint(30.0, 0.0, "CN"), isCurrent = true)),
                arcs = emptyList(),
                available = true,
            ),
        )
        val bitmap = paint(view, "globe-terminator-centerline")
        val cx = bitmap.width / 2f
        val cy = bitmap.height / 2f
        val r = minOf(bitmap.width, bitmap.height) / 2f * DISC_FILL_RATIO
        val rowY = cy + 0.5f * r
        fun lum(c: Int) = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)

        val nightLevel = lum(averageColor(bitmap, cx - 0.5f * r, rowY, 4))
        val dayLevel = lum(averageColor(bitmap, cx + 0.5f * r, rowY, 4))
        assertTrue(
            "这一行的昼夜电平没拉开（夜=$nightLevel 昼=$dayLevel），判据不成立",
            dayLevel > nightLevel * 2f + 10f,
        )

        val mid = (nightLevel + dayLevel) / 2f
        var crossX = Float.NaN
        var x = cx - 0.75f * r
        while (x <= cx + 0.75f * r) {
            if (lum(averageColor(bitmap, x, rowY, 2)) > mid) {
                crossX = x
                break
            }
            x += 1f
        }
        assertTrue("这一行没扫到昼夜分界，输出可能是常数", !crossX.isNaN())
        assertTrue(
            "昼夜分界没落在过盘心的竖直线上：x=$crossX，盘心 cx=$cx，偏差 %.3fr".format(
                (crossX - cx) / r,
            ),
            abs(crossX - cx) <= 0.06f * r,
        )
    }

    /**
     * 晨昏线必须跟着**真实时刻**走，不能只是"形状像那么回事"。
     *
     * 构造：相机固定在 hub = 北京（39.9°N, 116.4°E），取样点选在同纬度往西 56° 的开阔处
     * —— 离盘心约 200px，避开 hub 的名字胶囊。UTC 08:00 与 20:00 相差整整 12 小时，
     * 直射点绕到地球另一侧（实测（+2.0°, +60.0°）↔（+2.0°, −60.0°）），
     * 该点的 `N·S` 从 **+0.79** 翻到 **−0.36**：必然由深昼变深夜。
     *
     * 这条钉的是直射点经度公式的**符号**（`subsolarLon = (12 − hourUtc)·15`）—— 那类错误
     * 在"形状对不对"的几何断言里完全看不出来（照出来的晨昏线依然是一条完美的斜线）。
     */
    @Test
    fun `同一地点昼夜随真实时刻翻转`() {
        val sample = GeoPoint(39.9, 60.0, "?")

        fun frame(epoch: Long, name: String): Bitmap {
            val view = newSizedView().apply {
                starryMode = true
                sunEpochMillis = epoch
            }
            view.submitTopology(
                GlobeTopology(
                    markers = listOf(
                        // ⚠️ GeoPoint 是 (latitude, longitude)。
                        GlobeMarker(point = GeoPoint(39.9, 116.4, "CN"), isCurrent = true),
                    ),
                    arcs = emptyList(),
                    available = true,
                ),
            )
            return paint(view, name)
        }

        /** 按正交投影把 [sample] 投到屏幕（相机对准北京）。 */
        fun sampledLum(bmp: Bitmap): Float {
            val cx = bmp.width / 2f
            val cy = bmp.height / 2f
            val r = minOf(bmp.width, bmp.height) / 2f * DISC_FILL_RATIO
            val camLat = Math.toRadians(39.9)
            val dLon = Math.toRadians(sample.longitude - 116.4)
            val lat = Math.toRadians(sample.latitude)
            val right = (cos(lat) * sin(dLon)).toFloat()
            val up = (-sin(camLat) * cos(lat) * cos(dLon) + cos(camLat) * sin(lat)).toFloat()
            val c = averageColor(bmp, cx + right * r, cy - up * r, 5)
            return 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
        }

        val day = sampledLum(frame(REAL_1600_CST_2026_09_16, "globe-anchor-day"))
        val night = sampledLum(frame(REAL_1600_CST_2026_09_16 + 12 * 3_600_000L, "globe-anchor-night"))
        assertTrue("取样点在当地中午应是白昼，实测亮度 $day", day > 30f)
        assertTrue(
            "相隔 12 小时后同一点没变暗（$day → $night），晨昏线没跟着真实时刻走",
            night < day * 0.5f,
        )
    }

    /**
     * 网格必须按**世界经度**锚定，不能按"可见窗口"锚定。
     *
     * 这一条是整张贴图对不对位的**根**：`drawBitmapMesh` 的贴图坐标是隐式的均匀网格
     * （第 col 列恒取 `col/MESH_COLS`），所以"屏幕上的这一列取到贴图哪条经线"完全由
     * **列号 ↔ 世界经度**的对应关系决定。只有网格铺满整张 360°、第 col 列钉在
     * `-180 + 360·col/COLS` 上，那个隐式 u 才恰好等于贴图上的经度。
     *
     * 如果改成按相机窗口锚定（`lonDeg ± 90` 上撒 36 列），屏幕上的列位置**看起来也会**
     * 满足"中间疏两边密"—— 上面那条球面投影几何断言照样通过，但贴图会整张压进半球。
     * 所以必须单独钉住锚定方式：把每列的屏幕 x 与 `cx + r·sin(世界经度)` 对上。
     *
     * 只查正对相机的那半圈：背面那些列会被压到视界上（x 恒为 cx ± r），不在这个关系里。
     *
     * ⚠️ 这里的锚点恰好等于 −180°：相机经度 0 ⇒ `anchorLonFor` 的目标经度取 180，
     * 整数列偏移为 0 ⇒ 锚点 −180°（两者相差 360°，同一条经线）。**锚点非退化**的那些相机
     * 经度由 `滚动贴图在非退化相机经度下仍然对位` 覆盖 —— 那一条才是"锚点换算有没有写错"
     * 的判据，这一条只负责"列号 ↔ 世界经度"这层映射的线性关系。
     */
    @Test
    fun `网格按世界经度锚定_不是按可见窗口`() {
        val view = newSizedView()
        view.submitTopology(
            GlobeTopology(
                markers = listOf(GlobeMarker(point = GeoPoint(0.0, 0.0, "CN"), isCurrent = true)),
                arcs = emptyList(),
                available = true,
            ),
        )

        val xs = meshRowX(view, 0f)
        val cols = xs.size - 1
        val cx = view.width / 2f
        val r = minOf(view.width, view.height) / 2f * DISC_FILL_RATIO

        var checked = 0
        for (col in 0..cols) {
            val lon = -180f + 360f * col / cols
            if (abs(lon) > 89f) continue   // 视界上的列（含背面压上来的）不参与
            val expected = cx + r * sin(Math.toRadians(lon.toDouble())).toFloat()
            assertTrue(
                "第 $col 列（世界经度 $lon°）落在 x=${xs[col]}，按世界锚定应在 x=$expected" +
                    " —— 网格锚到可见窗口上去了，贴图会被整张压进半球",
                abs(xs[col] - expected) < 0.5f,
            )
            checked++
        }
        assertTrue("没有检查到任何一列，网格列数或相机姿态不对", checked >= 30)
    }

    /**
     * 一条**决定实现方式**的底层事实：零面积（退化）四边形一个像素都不画。
     *
     * 网格必须铺满整张 360°（见上一条），而背面列在正交投影下不可见、又不能从数组里抽掉，
     * 所以唯一的出路就是"让它们跟邻居**重合**"——四边形四角只剩两个位置，面积恒为 0。
     * [GlobeView] 的 `writeTextureRow` 整个背面策略都建立在这条前提上，所以必须钉住：
     * 哪天换了渲染后端、"退化四边形"被画成一条横跨盘面的带，那个策略就当场失效
     * （症状是接缝那一行的贴图糊满整块正面：北半球陆地被太平洋盖掉）。
     *
     * 钉法：2 格网格（3 列 2 行），把第 1、2 列都压到 x=6 —— 左格正常、右格面积恒为 0。
     * 退化格右侧必须还是**纯背景色**，而正常格照常画出来（否则测试自己就不成立）。
     */
    @Test
    fun `零面积格子画不画`() {
        val tex = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        tex.setPixel(0, 0, Color.RED)
        tex.setPixel(1, 0, Color.BLUE)

        for (antialias in listOf(false, true)) {
            val out = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
            out.eraseColor(Color.BLACK)
            val verts = floatArrayOf(
                2f, 2f, 6f, 2f, 6f, 2f,
                2f, 18f, 6f, 18f, 6f, 18f,
            )
            val paint = Paint(if (antialias) Paint.ANTI_ALIAS_FLAG else 0)
            paint.isFilterBitmap = true
            Canvas(out).drawBitmapMesh(tex, 2, 1, verts, 0, null, 0, paint)

            assertEquals(
                "退化格（面积 0）的右侧被画上了东西（antialias=$antialias）—— " +
                    "零面积不再是「不画」的保证，背面列的「塌成零面积」就藏不住了",
                Color.BLACK,
                out.getPixel(20, 10),
            )
            assertNotEquals(
                "正常格没画出来，这条测试自己就不成立",
                Color.BLACK,
                out.getPixel(4, 10),
            )
        }
    }

    // ────────────────────────────────────────────────────────── 自转（有限摆动）

    /**
     * 自转是**绕中心摆动**，不是整圈转 —— 判据是"走满一个周期必须回到原位"。
     *
     * 帧是直接喂 dt 推进的，不走 Choreographer：帧回调自我重投且延迟为零，
     * `ShadowLooper.idleFor` 会永远到不了目标时刻（实测把测试挂死）。见 [GlobeView.advance] 的注释。
     */
    @Test
    fun `自转是有限摆动_一个周期后回到原位`() {
        val themed = themedContext()
        val view = newSizedView(themed)
        view.submitTopology(topology()) // hub 在 (0,0)：纬度 0 ⇒ 无论怎么摆都落在盘心那一行上
        val hubColor = themed.getColor(R.color.connection_state_online)
        val centerX = view.width / 2

        // 相机半径的像素值 —— 与 GlobeView 里的 SPHERE_FILL_RATIO 一致（测试里那个常量就是抄它的）。
        val radiusPx = minOf(view.width, view.height) / 2f * DISC_FILL_RATIO
        val maxOffset = (radiusPx * sin(SWAY_DEG_RAD)).toInt() + PIXEL_SLACK

        // 中点由像素范围取整得来，天然带 1px 偏差，所以这里是"贴着盘心"而不是"等于盘心"。
        val startOffset = abs(hubOffset(paint(view, "globe-sway-t0"), hubColor, centerX))
        assertTrue("对准之后 hub 必须贴着盘心，实际偏了 $startOffset px", startOffset <= CENTER_SLACK)

        // 四分之一周期 ⇒ 正弦取到极值 ⇒ 偏移应当明显，且不超上限。
        view.elapse(SWAY_PERIOD_SEC / 4f)
        val quarterOffset = abs(hubOffset(paint(view, "globe-sway-quarter"), hubColor, centerX))
        assertTrue("摆了四分之一周期还没动，说明摆动没接管自转", quarterOffset > PIXEL_SLACK)
        assertTrue(
            "摆幅 $quarterOffset px 超过了 ±$SWAY_DEG° 的上限（$maxOffset px）",
            quarterOffset <= maxOffset,
        )

        // 再走四分之三 ⇒ 整周期回到相位 0 ⇒ 必须回到原位。整圈转会在这里偏出去一大截。
        view.elapse(SWAY_PERIOD_SEC * 3f / 4f)
        val fullOffset = abs(hubOffset(paint(view, "globe-sway-full-period"), hubColor, centerX))
        assertTrue(
            "走满一个周期后 hub 偏了 $fullOffset px —— 这不是摆动（整圈转？）",
            fullOffset <= PIXEL_SLACK,
        )
    }

    /** 按 60fps 喂帧推进 [seconds] 秒。分步推进是为了贴合真实逐帧的累积误差。 */
    private fun GlobeView.elapse(seconds: Float) {
        var elapsed = 0f
        while (elapsed < seconds) {
            advance(FRAME_SECONDS)
            elapsed += FRAME_SECONDS
        }
    }

    // ────────────────────────────────────────────────────────── 彗星（弧线上的动效）

    /**
     * 弧线上必须有一颗**会移动**的亮点 —— 这是"连线看着是活的"的唯一来源。
     *
     * 判据是"两个时刻的彗星头位置不同"。为了让画面里**只有**彗星在动，两次绘制之间刻意走满
     * 一个摆动周期：摆动是严格周期的，相机回到原位，底弧、标记、海岸线又都是静态的，
     * 于是任何位移都只可能来自彗星。
     */
    @Test
    fun `彗星头必须沿弧前进`() {
        val themed = themedContext()
        val view = newSizedView(themed)
        view.submitTopology(cometTopology())
        val accent = themed.getColor(R.color.widget_down_accent)

        val start = cometCentroid(paint(view, "globe-comet-t0"), accent)
        assertNotNull("第一帧找不到彗星头（accent 色的实心块）—— 彗星压根没画出来", start)

        view.elapse(SWAY_PERIOD_SEC)
        val later = cometCentroid(paint(view, "globe-comet-t1"), accent)
        assertNotNull("第二帧找不到彗星头", later)

        val dx = later!!.first - start!!.first
        val dy = later.second - start.second
        val moved = sqrt(dx * dx + dy * dy)
        assertTrue("彗星头没动（位移 $moved px）—— 弧线还是死的", moved > COMET_MIN_MOVE_PX)
    }

    /**
     * **空闲弧也要跑彗星**。
     *
     * 只给"带流量"的弧加动效的话，绝大多数连线是静止的 —— 满屏死线看着就是没在干活。
     * 空闲弧的彗星用弧线本色而不是 accent 色，所以这里断言不了颜色，改为断言"画面确实变了"：
     * 同样走满一个摆动周期，静态的东西全部回到原位，剩下的差异只能来自彗星。
     */
    @Test
    fun `空闲弧上也要有彗星在动`() {
        val view = newSizedView()
        view.submitTopology(cometTopology(arcConnections = 0))

        val before = paint(view, "globe-comet-idle-t0")
        view.elapse(SWAY_PERIOD_SEC)
        val after = paint(view, "globe-comet-idle-t1")

        val changed = differingPixels(before, after)
        assertTrue(
            "空闲弧在两个时刻几乎一模一样（只有 $changed px 不同）—— 空闲弧上没有动效",
            changed >= MIN_COMET_DIFF_PX,
        )
    }

    // ────────────────────────────────────────────────────────── 退化路径

    @Test
    fun `没有数据时也能画且不崩`() {
        val view = newSizedView()
        view.submitTopology(null)
        paint(view, "globe-empty")

        view.submitTopology(GlobeTopology.EMPTY)
        paint(view, "globe-empty")

        // 只有 hub、没有任何目的地 → 没有弧，也不该崩。
        view.submitTopology(
            GlobeTopology(
                markers = listOf(GlobeMarker(point = GeoPoint(0.0, 0.0), isCurrent = true)),
                arcs = emptyList(),
                available = true,
            ),
        )
        paint(view, "globe-hub-only")
    }

    @Test
    fun `地理库不可用时构造出的拓扑也能安全绘制`() {
        // 没下载地理库时走的就是这条路：available=false、markers 为空。
        val topology = GlobeTopologyBuilder(GeoLocator(NullGeoResolver)).build(
            currentNode = GlobeNode("1.2.3.4:22", "hub"),
        )
        val view = newSizedView()
        view.submitTopology(topology)
        paint(view, "globe-no-library")

        assertTrue(topology.isEmpty)
    }

    // ────────────────────────────────────────────────────────── 手势

    @Test
    fun `横向拖拽接管旋转_把hub从盘心挪开`() {
        val themed = themedContext()
        val view = newSizedView(themed)
        view.submitTopology(topology())

        // 相机对准 hub 时 hub 压在**视轴上**，所有大圆弧都退化成穿过盘心的直线
        // （平面含视轴 ⇒ 投影为直线）。这是几何必然，不是画错。
        // 拖开之后 hub 离开盘心，弧线才该显出真正的弧度 —— 这张预览就是人工确认这一点的。
        drag(view, dx = dp(view, 260f).toFloat(), dy = dp(view, 90f).toFloat())
        val bitmap = paint(view, "globe-rotated")

        assertNotEquals(
            "横向拖拽必须真的转动相机（盘心不该还是 hub 的语义色）",
            themed.getColor(R.color.connection_state_online),
            bitmap.getPixel(bitmap.width / 2, bitmap.height / 2),
        )
    }

    // ────────────────────────────────────────────────────────── 点选

    @Test
    fun `点中落点_发出带锚点的选中回调`() {
        val themed = themedContext()
        val view = newSizedView(themed)
        view.submitTopology(cometTopology())
        val emissions = ArrayList<GlobeView.Selection?>()
        view.onSelectionChanged = { emissions.add(it) }

        tap(view, view.width / 2f, view.height / 2f)

        val selection = emissions.lastOrNull()
        assertNotNull("点在盘心必须选中 hub", selection)
        assertEquals("hub", selection!!.marker.label)
        assertEquals(
            "锚点必须是落点的投影位置（hub 对准盘心）",
            view.width / 2f,
            selection.anchorX,
            2f,
        )
        assertEquals(view.height / 2f, selection.anchorY, 2f)
    }

    @Test
    fun `再点同一个落点_取消选中`() {
        val themed = themedContext()
        val view = newSizedView(themed)
        view.submitTopology(cometTopology())
        val emissions = ArrayList<GlobeView.Selection?>()
        view.onSelectionChanged = { emissions.add(it) }

        val center = Pair(view.width / 2f, view.height / 2f)
        tap(view, center.first, center.second)
        assertNotNull("第一次点必须选中", emissions.lastOrNull())
        tap(view, center.first, center.second)
        assertNull(
            "再点同一个落点是最常见的「看完关掉」手势，必须清掉选中",
            emissions.lastOrNull(),
        )
    }

    @Test
    fun `点空白_取消选中`() {
        val themed = themedContext()
        val view = newSizedView(themed)
        view.submitTopology(cometTopology())
        val emissions = ArrayList<GlobeView.Selection?>()
        view.onSelectionChanged = { emissions.add(it) }

        val mid = arcMidTap(view)
        tap(view, mid.first, mid.second)
        assertNotNull("先选中弧的终点", emissions.lastOrNull())
        // 角落离盘面 128px 起，标记/弧的触控半径都够不着 —— 是确定的空白。
        tap(view, 10f, 10f)
        assertNull("点空白必须清掉选中", emissions.lastOrNull())
    }

    @Test
    fun `点中弧_选中它的终点落点`() {
        val themed = themedContext()
        val view = newSizedView(themed)
        view.submitTopology(cometTopology())
        val emissions = ArrayList<GlobeView.Selection?>()
        view.onSelectionChanged = { emissions.add(it) }

        val mid = arcMidTap(view)
        // 中点到 hub 117px、到终点 96px，都超出标记触控半径（48px）—— 只可能命中弧本身。
        tap(view, mid.first, mid.second)

        assertEquals(
            "点弧应该选中它的终点落点（用户关心的是「连到哪儿」）",
            "n1",
            emissions.lastOrNull()?.marker?.label,
        )
    }

    @Test
    fun `选中期间摆动冻结_但彗星还在走`() {
        val themed = themedContext()
        val view = newSizedView(themed)
        view.submitTopology(cometTopology())
        tap(view, view.width / 2f, view.height / 2f) // 选中 hub → 相机冻结

        val before = paint(view, "globe-selected-t0")
        repeat(60) { view.advance(0.1f) } // 6 秒 = 半个摆动周期
        val after = paint(view, "globe-selected-t1")

        val online = themed.getColor(R.color.connection_state_online)
        val offset = hubOffset(after, online, after.width / 2)
        assertTrue(
            "选中期间相机必须冻结：6 秒足以让摆动把 hub 甩出半个周期，它却必须还钉在盘心" +
                "（实际偏移 $offset px，±1 是圆点描边抗锯齿的取整余量）",
            abs(offset) <= 1,
        )
        assertTrue(
            "冻结的只是相机 —— 彗星/呼吸相位必须继续推进，画面不能真的静止",
            differingPixels(before, after) > 0,
        )
    }

    @Test
    fun `提交新拓扑后选中按坐标重解析_落点消失则清空`() {
        val themed = themedContext()
        val view = newSizedView(themed)
        view.submitTopology(cometTopology())
        val emissions = ArrayList<GlobeView.Selection?>()
        view.onSelectionChanged = { emissions.add(it) }

        val mid = arcMidTap(view)
        tap(view, mid.first, mid.second)
        assertEquals("n1", emissions.lastOrNull()?.marker?.label)

        // 2 秒刷新的常态：同一个落点在下一轮拓扑里还在 —— 选中按坐标找回来并重新发出。
        view.submitTopology(cometTopology())
        assertEquals(
            "落点还在时选中必须保留（索引会变，坐标不变）",
            "n1",
            emissions.lastOrNull()?.marker?.label,
        )

        // 落点消失（节点被删 / 定位失败）：选中必须清掉，不能悬在空坐标上。
        view.submitTopology(hubOnlyTopology())
        assertNull("选中的落点消失后必须清空选中", emissions.lastOrNull())
    }

    @Test
    fun `拖拽取消选中`() {
        val themed = themedContext()
        val view = newSizedView(themed)
        view.submitTopology(cometTopology())
        val emissions = ArrayList<GlobeView.Selection?>()
        view.onSelectionChanged = { emissions.add(it) }

        tap(view, view.width / 2f, view.height / 2f)
        assertNotNull(emissions.lastOrNull())
        drag(view, dx = dp(view, 260f).toFloat(), dy = dp(view, 90f).toFloat())
        assertNull(
            "拖拽是换角度看，不是在看那个点 —— 必须清掉选中",
            emissions.lastOrNull(),
        )
    }

    // ────────────────────────────────────────────────────────── 城市名胶囊文案

    /**
     * [GlobeView.labelOf] 的优先级：城市 → 节点名（**跳过 IP**）→ 本地化国名。
     * 活跃连接点的 label 在查不到城市时会兜底成主机名（常常就是裸 IP），
     * 胶囊里露 IP 难读 —— 这条回归钉死"IP 让位给国家名"。
     * 测试 locale 是 zh-rCN（见 @Config），国名断言用中文。
     */
    @Test
    fun `胶囊文案_城市优先_IP顶位时改用本地化国名`() {
        val view = GlobeView(themedContext())

        // ① 有城市：城市名压过一切
        assertEquals(
            "Mountain View",
            view.labelOf(
                GlobeMarker(point = GeoPoint(37.4, -122.1, "US", city = "Mountain View"), connectionCount = 1, label = "203.0.113.7"),
            ),
        )

        // ② 没城市 + label 是 IPv4 → 跳过 IP，用本地化国名
        assertEquals(
            "美国",
            view.labelOf(
                GlobeMarker(point = GeoPoint(37.4, -122.1, "US"), connectionCount = 1, label = "203.0.113.7"),
            ),
        )

        // ③ 没城市 + label 是 IPv6（含带方括号形态）→ 同样让位给国名
        assertEquals(
            "日本",
            view.labelOf(
                GlobeMarker(point = GeoPoint(35.7, 139.7, "JP"), connectionCount = 1, label = "2400:3200::1"),
            ),
        )
        assertEquals(
            "日本",
            view.labelOf(
                GlobeMarker(point = GeoPoint(35.7, 139.7, "JP"), connectionCount = 1, label = "[2400:3200::1]"),
            ),
        )

        // ④ 没城市 + label 是用户起的节点名 → 保留名字（比国名更有信息量）
        assertEquals(
            "NingDe Home",
            view.labelOf(
                GlobeMarker(point = GeoPoint(26.7, 119.5, "CN"), isCurrent = true, label = "NingDe Home"),
            ),
        )

        // ⑤ 普通订阅节点（非 hub/出口/无连接）→ 整个不标
        assertNull(
            view.labelOf(GlobeMarker(point = GeoPoint(1.0, 103.0, "SG", city = "Singapore"))),
        )

        // ⑥ 城市、名字、国家全缺 → 没有可显示的东西，返回 null 而不是空胶囊
        assertNull(
            view.labelOf(GlobeMarker(point = GeoPoint(0.0, 0.0), connectionCount = 1, label = "10.0.0.1")),
        )
    }

    // ────────────────────────────────────────────────────────── 夹具

    /** 单击：按下与抬起同点，位移为零必然在 touch slop 之内。 */
    private fun tap(view: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(
            MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0),
        )
        view.dispatchTouchEvent(
            MotionEvent.obtain(t, t + 16, MotionEvent.ACTION_UP, x, y, 0),
        )
    }

    /**
     * [cometTopology] 那条弧（hub → 正北节点）的中点在屏幕上的位置。
     * 相机对准 hub 时子午线投影成竖直线，弧的中点就在盘心正上方半个弧程处。
     */
    private fun arcMidTap(view: GlobeView): Pair<Float, Float> {
        val latDeg = 540.0 / 11.0
        val radius = view.width / 2f * 0.94f
        val y = view.height / 2f - radius * sin(Math.toRadians(latDeg / 2)).toFloat()
        return Pair(view.width / 2f, y)
    }

    private fun hubOnlyTopology(): GlobeTopology = GlobeTopology(
        markers = listOf(
            GlobeMarker(point = GeoPoint(0.0, 0.0, "CN"), isCurrent = true, label = "hub"),
        ),
        arcs = emptyList(),
        available = true,
    )

    private fun drag(view: View, dx: Float, dy: Float) {
        val start = 100f
        val t = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(
            MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, start, start, 0),
        )
        // 分两步：第一步超过 touch slop 建立"接管"，第二步走完剩余距离。
        view.dispatchTouchEvent(
            MotionEvent.obtain(t, t + 16, MotionEvent.ACTION_MOVE, start + dx * 0.2f, start + dy * 0.2f, 0),
        )
        view.dispatchTouchEvent(
            MotionEvent.obtain(t, t + 32, MotionEvent.ACTION_MOVE, start + dx, start + dy, 0),
        )
        view.dispatchTouchEvent(
            MotionEvent.obtain(t, t + 48, MotionEvent.ACTION_UP, start + dx, start + dy, 0),
        )
    }

    private fun topology(): GlobeTopology {
        // hub 放在 (0,0)：与相机初始角度（20,20）不同，于是"盘心=hub"这个断言
        // **只能靠 submitTopology 里的对准逻辑成立**，不会因为初始值恰好重合而假通过。
        val hub = GeoPoint(0.0, 0.0, "CN")
        val node = GeoPoint(40.0, 60.0, "US")
        val active = GeoPoint(-30.0, -50.0, "BR")
        val exit = GeoPoint(30.0, 30.0, "JP")

        return GlobeTopology(
            markers = listOf(
                GlobeMarker(point = hub, isCurrent = true, label = "hub"),
                GlobeMarker(point = node, label = "n1"),
                GlobeMarker(point = active, connectionCount = 3, bytesPerSecond = 900_000, label = "c1"),
                GlobeMarker(point = exit, isExit = true, label = "exit"),
            ),
            arcs = listOf(
                GlobeArc(from = hub, to = node, connectionCount = 0, bytesPerSecond = 0),
                GlobeArc(from = hub, to = active, connectionCount = 3, bytesPerSecond = 900_000),
                GlobeArc(from = hub, to = exit, connectionCount = 0, bytesPerSecond = 0),
            ),
            available = true,
        )
    }

    /**
     * 单条弧的夹具：hub 在 (0,0)，目的地纬度取 `540/11`。
     *
     * 这个纬度不是随手写的 —— [GlobeView] 里彗星的起步错相是 `(lon·7 + lat·11)/360` 取小数，
     * 用 `540/11` 恰好把相位钉在 `0.5`：打开面板时彗星正处在弧的中点。这样
     * ① 它离 hub 足够远，不会被盘心的 hub 标记盖住；② 测试有确定的像位可断言。
     *
     * 标记刻意**不给** connectionCount：有连接就会画光晕，而光晕用的正是彗星头那个 accent 色，
     * 会把形心带偏。忙/闲只体现在弧上。
     */
    private fun cometTopology(arcConnections: Int = 1): GlobeTopology {
        val hub = GeoPoint(0.0, 0.0, "CN")
        val node = GeoPoint(540.0 / 11.0, 0.0, "US")
        return GlobeTopology(
            markers = listOf(
                GlobeMarker(point = hub, isCurrent = true, label = "hub"),
                GlobeMarker(point = node, label = "n1"),
            ),
            arcs = listOf(
                // 速率未知 ⇒ 档位由连接数决定（见 pulseTier 的整数分档），不会出现小数档位。
                GlobeArc(from = hub, to = node, connectionCount = arcConnections, bytesPerSecond = 0),
            ),
            available = true,
        )
    }

    /** 从整张球面网格里切出纬度 [latDeg] 那一行的屏幕 x（按列序）。 */
    private fun meshRowX(view: GlobeView, latDeg: Float): FloatArray {
        val verts = view.debugMeshVerts()
        val cols = GlobeView.MESH_COLS
        val row = Math.round((90f - latDeg) / 180f * GlobeView.MESH_ROWS).toInt()
        return FloatArray(cols + 1) { verts[(row * (cols + 1) + it) * 2] }
    }

    private fun newSizedView(themed: Context = themedContext()): GlobeView {
        val view = GlobeView(themed)
        val width = dp(view, GLOBE_SIZE_DP)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, width, view.measuredHeight)
        return view
    }

    /** 真实 draw 进 Bitmap，并落一张 PNG 到约定的预览目录。 */
    private fun paint(view: GlobeView, name: String): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // 垫一层主题承载面，否则透明区域看不出球体边界（也贴近平板里的真实观感）。
        canvas.drawColor(view.context.let { ctx ->
            val id = ctx.resources.getIdentifier("colorSurfaceContainerHigh", "attr", ctx.packageName)
            if (id == 0) Color.WHITE else com.google.android.material.color.MaterialColors.getColor(ctx, id, Color.WHITE)
        })
        view.draw(canvas)

        val dir = File("build/reports/ui-preview").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    /**
     * 以 ([x], [y]) 为中心取 `(2r+1)²` 块的平均色（逐通道整数平均）。
     *
     * 取平均而不是取单点：单点可能正好落在抗锯齿的边缘、或贴图上的一颗"城市灯光"上；
     * 块平均只在**邻域同色**的地方用（贴图对位测试挑的就是这种位置）。
     */
    private fun averageColor(bitmap: Bitmap, x: Float, y: Float, r: Int = 2): Int {
        var sr = 0
        var sg = 0
        var sb = 0
        var n = 0
        for (dy in -r..r) {
            for (dx in -r..r) {
                val p = bitmap.getPixel(
                    (x + dx).toInt().coerceIn(0, bitmap.width - 1),
                    (y + dy).toInt().coerceIn(0, bitmap.height - 1),
                )
                sr += Color.red(p)
                sg += Color.green(p)
                sb += Color.blue(p)
                n++
            }
        }
        return Color.rgb(sr / n, sg / n, sb / n)
    }

    /** 盘面内有多少种**不完全透明**的颜色。空球只会有 2 种（垫底色 + 海洋）。 */
    private fun distinctColorsInDisc(bitmap: Bitmap): Int {
        val cx = bitmap.width / 2
        val cy = bitmap.height / 2
        val radius = (minOf(cx, cy) * (DISC_FILL_RATIO - 0.02f))
        val colors = HashSet<Int>()
        var y = cy - radius
        while (y <= cy + radius) {
            var x = cx - radius
            while (x <= cx + radius) {
                val dx = x - cx
                val dy = y - cy
                if (dx * dx + dy * dy <= radius * radius) {
                    val pixel = bitmap.getPixel(x.toInt(), y.toInt())
                    if (Color.alpha(pixel) != 0) colors += pixel
                }
                x += 1f
            }
            y += 1f
        }
        return colors.size
    }

    /**
     * 画面里"彗星头"（近似 accent 实心色）像素的形心；一个都没有时返回 null。
     *
     * 用**近似**而不是精确相等：头是半透明画上去的，混色结果依赖底下的背景，卡死成某个 RGB 会脆。
     * 光晕的 alpha 只有头核心的 38% 量级，混完离 accent 很远，不会被算进来。
     */
    private fun cometCentroid(bitmap: Bitmap, accent: Int): Pair<Float, Float>? {
        var sumX = 0f
        var sumY = 0f
        var count = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (!nearAccent(bitmap.getPixel(x, y), accent)) continue
                sumX += x
                sumY += y
                count++
            }
        }
        return if (count == 0) null else Pair(sumX / count, sumY / count)
    }

    private fun nearAccent(pixel: Int, accent: Int): Boolean =
        abs(Color.red(pixel) - Color.red(accent)) <= COLOR_TOL &&
            abs(Color.green(pixel) - Color.green(accent)) <= COLOR_TOL &&
            abs(Color.blue(pixel) - Color.blue(accent)) <= COLOR_TOL

    /** 两张同尺寸位图之间有多少个像素不同。 */
    private fun differingPixels(a: Bitmap, b: Bitmap): Int {
        var count = 0
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) count++
            }
        }
        return count
    }

    /**
     * 盘心那一行上 hub 语义色的像素范围中点 x；该行没有这颗色时返回 null。
     *
     * hub 的纬度恒等于相机纬度（摆动只动经度），所以它必定落在**盘心那条水平线**上，
     * 逐行扫描是不必要的。
     */
    private fun hubCenterX(bitmap: Bitmap, hubColor: Int): Int? {
        val y = bitmap.height / 2
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        for (x in 0 until bitmap.width) {
            if (bitmap.getPixel(x, y) != hubColor) continue
            if (x < minX) minX = x
            if (x > maxX) maxX = x
        }
        return if (minX > maxX) null else (minX + maxX) / 2
    }

    /** hub 相对盘心的水平偏移（px）。找不到 hub 就直接失败 —— 那本身就是要抓的 bug。 */
    private fun hubOffset(bitmap: Bitmap, hubColor: Int, centerX: Int): Int {
        val x = requireNotNull(hubCenterX(bitmap, hubColor)) {
            "盘心那一行找不到 hub 的语义色 —— 它被摆出视野，或被别的绘制盖住了"
        }
        return x - centerX
    }

    private fun dp(view: View, value: Float): Int =
        (value * view.resources.displayMetrics.density).toInt()

    private fun themedContext(night: Boolean = false): Context {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        val config = Configuration(activity.resources.configuration).apply {
            fontScale = 1f
            uiMode = if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val themed = ContextThemeWrapper(activity.createConfigurationContext(config), R.style.Theme_Stun)
        controller.setup()
        return themed
    }

    /** 按 [GlobeView] 同一套取法拿主题角色色（非传递 R 类，属性 id 得在本 app 包名下查）。 */
    private fun themeColorOf(host: View, attr: String, fallback: Int = Color.WHITE): Int {
        val id = host.context.resources.getIdentifier(attr, "attr", host.context.packageName)
        return if (id == 0) {
            fallback
        } else {
            com.google.android.material.color.MaterialColors.getColor(host, id, fallback)
        }
    }

    private companion object {
        const val GLOBE_SIZE_DP = 300f

        /** 必须与 [GlobeView] 里的 `SPHERE_FILL_RATIO` 一致：采样时往内缩一点避开描边。 */
        const val DISC_FILL_RATIO = 0.94f

        /**
         * 与 [GlobeView] 的私有常量 `SWAY_DEG` / `SWAY_PERIOD_SEC` 对齐。
         *
         * 刻意**各写一份**而不是把那边改成 public：这两个数是"设计参数"，故意改动时应该让测试
         * 一起改（改动者被迫确认新数值是否还满足"hub 不出视野"），而不是自动跟着跑。
         */
        const val SWAY_DEG = 25f
        const val SWAY_PERIOD_SEC = 12f

        /** 像素判定的容差：抗锯齿取整 + 一帧的相位误差 + 摆幅上限的量化。 */
        const val PIXEL_SLACK = 20

        /** "贴在盘心"的容差。中点由像素范围取整得来，天生带 1px 偏差。 */
        const val CENTER_SLACK = 2

        /** 走帧用的步长（60fps）。 */
        const val FRAME_SECONDS = 1f / 60f

        /** 摆幅换算成像素要用弧度。 */
        val SWAY_DEG_RAD = Math.toRadians(SWAY_DEG.toDouble()).toFloat()

        /** 一个摆动周期内彗星头的位移下限（px）。实测约 80px，留足余量。 */
        const val COMET_MIN_MOVE_PX = 8f

        /**
         * 空闲弧的彗星在同一个检查窗口里至少该改动多少像素。实测数百，这里只做"确实动了"的下限。
         */
        const val MIN_COMET_DIFF_PX = 120

        /** 认成"彗星头那个色"的通道容差。半透明混色 + 抗锯齿带来的偏差远小于它。 */
        const val COLOR_TOL = 14

        /**
         * 2026-09-21T12:00Z（秋分正午）。
         *
         * 挑这一瞬是因为 [GlobeView] 的太阳直射点用"赤纬 + 时角"粗算：这一天赤纬 ≈ 0、
         * UTC 正午时角也归零 ⇒ 直射点正好在 (0,0)。面板里 hub 在 (0,0) 时球心就是 (0,0)，
         * 于是直射点落在**盘心**、整个可见盘面都在白昼侧，贴图对位测试取样时不会被夜面污染。
         */
        const val EQUINOX_NOON_UTC = 1_789_992_000_000L

        /**
         * 2026-09-16T08:00Z（= 北京时间 16:00）。挑的是"真实的一天"：
         * 北京当地时间下午 ⇒ 北京必须白昼、北美当地凌晨 ⇒ 必须黑夜 —— 用来钉住
         * 直射点经度的**符号**（见 `同一地点昼夜随真实时刻翻转`）。
         */
        const val REAL_1600_CST_2026_09_16 = 1_789_545_600_000L
    }
}
