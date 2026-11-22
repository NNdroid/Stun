package app.fjj.stun.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Contracts enforced by Google Play's TV app quality review (TV-LB / TV-BN / TV-LM).
 *
 * The reviewer rejects banners whose title is clipped at the edge, banners with a
 * transparent background, and launcher icons that do not fill the icon space.
 * The previous vector-only banner had 'N' ending at x=322 of a 320-wide viewport,
 * and the TV app reused the phone's transparent fox vector as its icon.
 */
class TvLauncherAssetsTest {

    private fun asset(name: String): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        for (i in 0 until 6) {
            for (candidate in listOf(
                File(dir, "src/main/res/drawable-xhdpi/$name"),
                File(dir, "tv/src/main/res/drawable-xhdpi/$name")
            )) {
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile ?: error("missing TV launcher asset: $name")
        }
        error("missing TV launcher asset: $name (cwd=${System.getProperty("user.dir")})")
    }

    private fun source(name: String): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        for (i in 0 until 6) {
            val candidate = File(dir, name)
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: error("missing source file: $name")
        }
        error("missing source file: $name")
    }

    private fun sourceDir(name: String): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        for (i in 0 until 6) {
            val candidate = File(dir, name)
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: error("missing source dir: $name")
        }
        error("missing source dir: $name")
    }

    private fun image(name: String): BufferedImage = ImageIO.read(asset(name))
        ?: error("cannot decode $name")

    private fun minAlpha(img: BufferedImage): Int {
        var min = 255
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                min = minOf(min, img.getRGB(x, y) ushr 24)
            }
        }
        return min
    }

    private fun brightNearEdge(img: BufferedImage, margin: Int, bright: (Int) -> Boolean): Int {
        var count = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val nearEdge = x < margin || x >= img.width - margin ||
                    y < margin || y >= img.height - margin
                if (nearEdge && bright(img.getRGB(x, y) and 0xFFFFFF)) count++
            }
        }
        return count
    }

    @Test
    fun bannerIsExactly320x180AndFullyOpaque() {
        val img = image("tv_banner.png")
        assertEquals(320, img.width)
        assertEquals(180, img.height)
        assertEquals("banner background must be opaque", 255, minAlpha(img))
    }

    @Test
    fun tvIconIs512x512AndFullyOpaque() {
        val img = image("ic_launcher_tv.png")
        assertEquals(512, img.width)
        assertEquals(512, img.height)
        assertEquals("TV launcher icon must be opaque", 255, minAlpha(img))
    }

    @Test
    fun bannerKeepsTitleAndMascotOutOfTheEdgeSafeArea() {
        val img = image("tv_banner.png")
        val whiteTitle = { rgb: Int ->
            val r = rgb shr 16 and 0xFF
            val g = rgb shr 8 and 0xFF
            val b = rgb and 0xFF
            r > 180 && g > 180 && b > 180
        }
        assertEquals(
            "white title pixels must stay 16px inside every edge",
            0,
            brightNearEdge(img, 16, whiteTitle)
        )
    }

    @Test
    fun tvIconKeepsFoxOutOfTheEdgeSafeArea() {
        val img = image("ic_launcher_tv.png")
        val orangeFox = { rgb: Int ->
            val r = rgb shr 16 and 0xFF
            val g = rgb shr 8 and 0xFF
            val b = rgb and 0xFF
            r > 150 && g > 60 && b < 120
        }
        assertEquals(
            "fox must not be clipped by a 21px (66/108 of 512) icon mask",
            0,
            brightNearEdge(img, 21, orangeFox)
        )
    }

    @Test
    fun everyTvLocaleLabelMatchesTheBannerTitle() {
        val res = sourceDir("tv/src/main/res")
        val dirs = listOf(File(res, "values")) + res.listFiles { f ->
            f.isDirectory && f.name.startsWith("values-") && f.name != "values-night"
        }.orEmpty()
        val regex = Regex("""<string name="app_name"[^>]*>([^<]+)</string>""")
        dirs.forEach { dir ->
            val file = File(dir, "strings.xml")
            if (!file.isFile) return@forEach
            regex.find(file.readText())?.let {
                assertEquals("label mismatch in ${dir.name}", "Stun TV", it.groupValues[1])
            }
        }
    }

    @Test
    fun manifestUsesTheFullSizeTvAssets() {
        val manifest = source("tv/src/main/AndroidManifest.xml")
        val text = manifest.readText()
        assertTrue("must use the opaque 512x512 TV icon", "@drawable/ic_launcher_tv" in text)
        assertTrue("must keep the TV banner", "@drawable/tv_banner" in text)
    }
}
