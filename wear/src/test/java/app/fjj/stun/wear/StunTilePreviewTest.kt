package app.fjj.stun.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preview panel invokes @Preview functions through reflection, so a compile-only check is
 * not enough - a wrong signature or a preview that drifted from the live tile would still build.
 * These tests call the same top-level builders the preview functions delegate to and pin the
 * contracts that make the panel render: resource version parity, icon mapping, and 3 states.
 */
class StunTilePreviewTest {

    @Test
    fun previewFunctionsAreTopLevelAndReturnPreviewData() {
        val previews = listOf(
            ::stunTileConnectedSmallPreview,
            ::stunTileConnectingSmallPreview,
            ::stunTileDisconnectedSmallPreview,
            ::stunTileConnectedLargePreview,
            ::stunTileDisconnectedLargePreview,
        )
        assertEquals(5, previews.size)
        previews.forEach { preview ->
            val data = preview()
            // Call both callbacks, not just check non-null: a lambda that throws or returns a
            // blank tile still compiles but leaves the preview panel empty.
            val tile = data.onTileRequest(
                androidx.wear.tiles.RequestBuilders.TileRequest.Builder().build()
            )
            assertTrue("preview tile must carry a timeline", tile.tileTimeline != null)
            val resources = data.onTileResourceRequest(
                androidx.wear.tiles.RequestBuilders.ResourcesRequest.Builder().build()
            )
            assertTrue("preview must map the fox icon", resources.idToImageMapping.isNotEmpty())
        }
    }

    @Test
    fun previewTileUsesTheSameResourceVersionAsTheLiveTile() {
        val live = buildStunTile("已连接", StunTileResources.CONNECTED_COLOR, "app.fjj.stun.wear")
        val preview = stunTileConnectedSmallPreview().onTileRequest(
            androidx.wear.tiles.RequestBuilders.TileRequest.Builder().build()
        )
        assertEquals(live.resourcesVersion, preview.resourcesVersion)
        assertEquals(StunTileResources.VERSION, preview.resourcesVersion)
    }

    @Test
    fun previewResourcesMapTheFoxIcon() {
        val resources = stunTileConnectedSmallPreview().onTileResourceRequest(
            androidx.wear.tiles.RequestBuilders.ResourcesRequest.Builder().build()
        )
        assertEquals(StunTileResources.VERSION, resources.version)
        assertTrue(
            "preview must map the fox icon so the tile is not blank",
            resources.idToImageMapping.containsKey(StunTileResources.ICON_ID)
        )
    }

    @Test
    fun allThreeStatusStatesProduceDistinctColors() {
        val colors = setOf(
            StunTileResources.CONNECTED_COLOR,
            StunTileResources.CONNECTING_COLOR,
            StunTileResources.DISCONNECTED_COLOR,
        )
        assertEquals(3, colors.size)
    }
}
