package dev.bitstorm.sashimi.ui.downloads

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bitstorm.sashimi.core.downloads.DeviceMediaCompatibility
import dev.bitstorm.sashimi.core.downloads.DownloadQuality
import dev.bitstorm.sashimi.core.downloads.DownloadStatus
import dev.bitstorm.sashimi.core.downloads.DownloadedItemEntity
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.di.ServiceLocator

private val Success = Color(0xFF4CAF50)
private val Warning = Color(0xFFFF9800)

/**
 * Per-item download control, ported from the Swift `DownloadButton` state
 * machine: idle → quality dialog → queued/preparing/downloading% → completed
 * (tap to remove) / failed (tap to retry). The Original tier is fail-closed —
 * hidden unless the item's media source can direct-play on this device.
 */
@Composable
fun DownloadButton(
    item: BaseItemDto,
    modifier: Modifier = Modifier,
) {
    val manager = ServiceLocator.downloadManager
    val downloads by manager.downloads.collectAsStateWithLifecycle()
    val row = downloads.firstOrNull { it.itemId == item.id }

    var showQualityDialog by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    val notificationGate = rememberNotificationPermissionGate()

    FilledTonalIconButton(onClick = {
        when (row?.downloadStatus) {
            null -> showQualityDialog = true
            DownloadStatus.QUEUED, DownloadStatus.PREPARING, DownloadStatus.DOWNLOADING -> manager.cancel(item.id)
            DownloadStatus.COMPLETED -> showRemoveConfirm = true
            DownloadStatus.FAILED, DownloadStatus.PAUSED -> notificationGate { manager.retry(item.id) }
        }
    }, modifier = modifier) {
        DownloadButtonIcon(row)
    }

    if (showQualityDialog) {
        QualityDialog(
            item = item,
            onDismiss = { showQualityDialog = false },
            onPick = { quality ->
                showQualityDialog = false
                notificationGate { manager.enqueueDownload(item, quality) }
            },
        )
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("Remove Download?") },
            text = { Text("This will remove the downloaded file from your device.") },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveConfirm = false
                    manager.delete(item.id)
                }) { Text("Remove", color = Color.Red) }
            },
            dismissButton = { TextButton(onClick = { showRemoveConfirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DownloadButtonIcon(row: DownloadedItemEntity?) {
    when (row?.downloadStatus) {
        // Not-downloaded: neutral tint (inherits the tonal button's content
        // color). Purple/accent is reserved for the COMPLETED state so the
        // accent actually signals "downloaded", not "downloadable".
        null ->
            Icon(Icons.Filled.Download, contentDescription = "Download")
        DownloadStatus.QUEUED ->
            Icon(Icons.Filled.HourglassEmpty, contentDescription = "Queued", tint = Color.Gray)
        DownloadStatus.PREPARING ->
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        DownloadStatus.DOWNLOADING -> {
            val p = row.progress
            if (p < 0) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                CircularProgressIndicator(
                    progress = { p.toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
        DownloadStatus.COMPLETED ->
            Icon(Icons.Filled.CheckCircle, contentDescription = "Downloaded", tint = Success)
        DownloadStatus.FAILED ->
            Icon(Icons.Filled.ErrorOutline, contentDescription = "Retry download", tint = Color.Red)
        DownloadStatus.PAUSED ->
            Icon(Icons.Filled.Download, contentDescription = "Resume download", tint = Warning)
    }
}

/**
 * Quality confirmation dialog. Resolves the Original gate on open: fetches the
 * item's PlaybackInfo and runs [DeviceMediaCompatibility.canDirectPlayOnDevice];
 * Original is only offered when compatible (fail-closed on any error).
 */
@Composable
fun QualityDialog(
    item: BaseItemDto,
    onDismiss: () -> Unit,
    onPick: (DownloadQuality) -> Unit,
    seasonProxyItemId: String? = null,
) {
    var originalAllowed by remember(item.id) { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(item.id, seasonProxyItemId) {
        val probeId = seasonProxyItemId ?: item.id
        originalAllowed =
            runCatching {
                val source = ServiceLocator.client.getPlaybackInfo(probeId).mediaSources?.firstOrNull()
                source != null && DeviceMediaCompatibility.canDirectPlayOnDevice(source)
            }.getOrDefault(false)
    }

    val qualities =
        DownloadQuality.entries.filter { it != DownloadQuality.ORIGINAL || originalAllowed == true }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download Quality") },
        text = {
            if (originalAllowed == null) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                androidx.compose.foundation.layout.Column {
                    qualities.forEach { q ->
                        TextButton(onClick = { onPick(q) }) {
                            Text("${q.displayName} — ${q.subtitle}")
                        }
                    }
                    if (originalAllowed == false) {
                        Text(
                            "Original isn't available — this file's format can't play offline on this device.",
                            color = Color.Gray,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
