package app.fjj.stun.wear

import androidx.wear.tiles.RequestBuilders.ResourcesRequest
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.tooling.preview.Preview
import androidx.wear.tiles.tooling.preview.TilePreviewData
import androidx.wear.tooling.preview.devices.WearDevices

/*
 * Android Studio tile previews (Koala Feature Drop+). Open this file in Split view to see them.
 *
 * These are NOT @Composable and do NOT use the Compose @Preview - Tiles are ProtoLayout based.
 * Every preview delegates to buildStunTile() / buildStunTileResources(), the same functions the
 * live StunWearTileService uses, so the panel can never drift from shipped output.
 *
 * Preview renderer limitations (per tiles-tooling): no network, no file access, and some Context
 * APIs are unavailable. We only pass literal strings + component name, so we stay within them.
 */

private const val PREVIEW_PACKAGE = "app.fjj.stun.wear"

private fun previewData(statusText: String, statusColor: Int): TilePreviewData = TilePreviewData(
    onTileResourceRequest = { _: ResourcesRequest -> buildStunTileResources() },
    onTileRequest = { _ -> buildStunTile(statusText, statusColor, PREVIEW_PACKAGE) },
)

@Preview(name = "已连接 · 小圆表盘", device = WearDevices.SMALL_ROUND, group = "Stun tile")
fun stunTileConnectedSmallPreview(): TilePreviewData =
    previewData("已连接", StunTileResources.CONNECTED_COLOR)

@Preview(name = "连接中 · 小圆表盘", device = WearDevices.SMALL_ROUND, group = "Stun tile")
fun stunTileConnectingSmallPreview(): TilePreviewData =
    previewData("连接中", StunTileResources.CONNECTING_COLOR)

@Preview(name = "未连接 · 小圆表盘", device = WearDevices.SMALL_ROUND, group = "Stun tile")
fun stunTileDisconnectedSmallPreview(): TilePreviewData =
    previewData("未连接", StunTileResources.DISCONNECTED_COLOR)

@Preview(name = "已连接 · 大圆表盘", device = WearDevices.LARGE_ROUND, group = "Stun tile")
fun stunTileConnectedLargePreview(): TilePreviewData =
    previewData("已连接", StunTileResources.CONNECTED_COLOR)

@Preview(name = "未连接 · 大圆表盘", device = WearDevices.LARGE_ROUND, group = "Stun tile")
fun stunTileDisconnectedLargePreview(): TilePreviewData =
    previewData("未连接", StunTileResources.DISCONNECTED_COLOR)
