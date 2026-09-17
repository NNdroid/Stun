package app.fjj.stun.wear

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Typography
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
        val isConnecting = state == VpnState.CONNECTING || state == VpnState.RECONNECTING

        val statusText = when {
            isConnected -> getString(CoreR.string.wear_tile_connected)
            isConnecting -> getString(CoreR.string.main_connecting)
            else -> getString(CoreR.string.wear_tile_disconnected)
        }

        val statusColor = when {
            isConnected -> 0xFF10B981.toInt()
            isConnecting -> 0xFFF59E0B.toInt()
            else -> 0xFF38BDF8.toInt()
        }

        // 1. Large prominent icon (52dp x 52dp)
        val iconElement = LayoutElementBuilders.Image.Builder()
            .setResourceId(RESOURCES_ICON_ID)
            .setWidth(dp(52f))
            .setHeight(dp(52f))
            .build()

        // 2. Status Label with bold typography
        val textElement = LayoutElementBuilders.Text.Builder()
            .setText(statusText)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(14f))
                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                    .setColor(argb(statusColor))
                    .build()
            )
            .build()

        // 3. Spacing
        val spacer = LayoutElementBuilders.Spacer.Builder()
            .setHeight(dp(8f))
            .build()

        // 4. Click Modifier to launch App MainActivity
        val launchAction = ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName("app.fjj.stun.wear.WearMainActivity")
                    .build()
            )
            .build()

        val clickModifier = ModifiersBuilders.Modifiers.Builder()
            .setClickable(
                ModifiersBuilders.Clickable.Builder()
                    .setOnClick(launchAction)
                    .setId("action_open_app")
                    .build()
            )
            .build()

        val column = LayoutElementBuilders.Column.Builder()
            .setModifiers(clickModifier)
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(iconElement)
            .addContent(spacer)
            .addContent(textElement)
            .build()

        val layout = LayoutElementBuilders.Layout.Builder()
            .setRoot(column)
            .build()

        val timelineEntry = TimelineBuilders.TimelineEntry.Builder()
            .setLayout(layout)
            .build()

        val timeline = TimelineBuilders.Timeline.Builder()
            .addTimelineEntry(timelineEntry)
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(30_000L)
            .setTileTimeline(timeline)
            .build()

        return Futures.immediateFuture(tile)
    }

    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        val imageResource = ResourceBuilders.ImageResource.Builder()
            .setAndroidResourceByResId(
                ResourceBuilders.AndroidImageResourceByResId.Builder()
                    .setResourceId(CoreR.drawable.ic_fox_logo)
                    .build()
            )
            .build()

        val resources = ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .addIdToImageMapping(RESOURCES_ICON_ID, imageResource)
            .build()

        return Futures.immediateFuture(resources)
    }

    companion object {
        private const val RESOURCES_VERSION = "2"
        private const val RESOURCES_ICON_ID = "stun_wear_tile_icon"
    }
}
