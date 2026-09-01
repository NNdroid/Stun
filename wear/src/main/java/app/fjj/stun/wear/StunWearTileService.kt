package app.fjj.stun.wear

import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.ResourceBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.repo.VpnState
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class StunWearTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val state = StunRepository.vpnState.value ?: VpnState.DISCONNECTED
        val isConnected = state == VpnState.CONNECTED

        val statusText = if (isConnected) {
            getString(CoreR.string.wear_tile_connected)
        } else {
            getString(CoreR.string.wear_tile_disconnected)
        }

        val textElement = LayoutElementBuilders.Text.Builder()
            .setText(statusText)
            .build()

        val layout = LayoutElementBuilders.Layout.Builder()
            .setRoot(textElement)
            .build()

        val timelineEntry = TimelineBuilders.TimelineEntry.Builder()
            .setLayout(layout)
            .build()

        val timeline = TimelineBuilders.Timeline.Builder()
            .addTimelineEntry(timelineEntry)
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion("1")
            .setTileTimeline(timeline)
            .build()

        return Futures.immediateFuture(tile)
    }

    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        val resources = ResourceBuilders.Resources.Builder()
            .setVersion("1")
            .build()
        return Futures.immediateFuture(resources)
    }
}
