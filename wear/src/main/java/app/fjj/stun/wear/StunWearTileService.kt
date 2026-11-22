package app.fjj.stun.wear

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.repo.VpnState
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Builds the tile layout from explicit inputs. Top level on purpose: the live service and the
 * Android Studio tile preview must render the exact same layout - a preview that duplicates the
 * layout code would silently drift from what ships.
 */
internal fun buildStunTile(
    statusText: String,
    statusColor: Int,
    packageName: String,
): TileBuilders.Tile {
    // 1. Large prominent icon (52dp x 52dp)
    val iconElement = LayoutElementBuilders.Image.Builder()
        .setResourceId(StunTileResources.ICON_ID)
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

    return TileBuilders.Tile.Builder()
        .setResourcesVersion(StunTileResources.VERSION)
        .setFreshnessIntervalMillis(30_000L)
        .setTileTimeline(timeline)
        .build()
}

/** Resource mapping shared by the live service and the preview. */
internal fun buildStunTileResources(): ResourceBuilders.Resources {
    val imageResource = ResourceBuilders.ImageResource.Builder()
        .setAndroidResourceByResId(
            ResourceBuilders.AndroidImageResourceByResId.Builder()
                .setResourceId(CoreR.drawable.ic_fox_logo)
                .build()
        )
        .build()

    return ResourceBuilders.Resources.Builder()
        .setVersion(StunTileResources.VERSION)
        .addIdToImageMapping(StunTileResources.ICON_ID, imageResource)
        .build()
}

internal object StunTileResources {
    const val VERSION = "2"
    const val ICON_ID = "stun_wear_tile_icon"

    const val CONNECTED_COLOR = 0xFF10B981.toInt()
    const val CONNECTING_COLOR = 0xFFF59E0B.toInt()
    // 断开=红，与应用内状态点同一语义（原来是蓝色，和应用内红点互相矛盾）
    const val DISCONNECTED_COLOR = 0xFFF44336.toInt()
}

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
            isConnected -> StunTileResources.CONNECTED_COLOR
            isConnecting -> StunTileResources.CONNECTING_COLOR
            else -> StunTileResources.DISCONNECTED_COLOR
        }

        return Futures.immediateFuture(buildStunTile(statusText, statusColor, packageName))
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(buildStunTileResources())

    companion object {

        /**
         * VPN 状态变化后主动请求系统刷新 Tile。默认 30s freshness 意味着手机/手表上
         * 连接后表盘最长 30s 还显示旧状态，所以观察者里每个状态变化都推一次。
         */
        fun requestTileUpdate(context: Context) {
            runCatching {
                TileService.getUpdater(context).requestUpdate(StunWearTileService::class.java)
            }
        }
    }
}
