package dev.bitstorm.sashimi.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.bitstorm.sashimi.core.downloads.ActiveDownloadText
import dev.bitstorm.sashimi.core.downloads.DownloadStatus
import dev.bitstorm.sashimi.core.downloads.DownloadedItemEntity
import dev.bitstorm.sashimi.core.downloads.LiveTransfer
import dev.bitstorm.sashimi.core.downloads.StorageAccounting
import dev.bitstorm.sashimi.core.downloads.key
import dev.bitstorm.sashimi.di.ServiceLocator
import dev.bitstorm.sashimi.ui.theme.SashimiAccent
import dev.bitstorm.sashimi.ui.theme.SashimiCard
import dev.bitstorm.sashimi.ui.theme.SashimiTextPrimary
import dev.bitstorm.sashimi.ui.theme.SashimiTextSecondary
import dev.bitstorm.sashimi.ui.theme.SashimiTextTertiary

/**
 * Downloads tab: storage summary bar, then Active / Completed / Failed sections
 * with per-item cancel / delete / retry, Retry All, and Delete All. Ported from
 * the Swift `DownloadsListView`.
 */
@Composable
fun DownloadsScreen(modifier: Modifier = Modifier) {
    val manager = ServiceLocator.downloadManager
    val downloads by manager.downloads.collectAsStateWithLifecycle()
    val needingRedownload by manager.needingRedownload.collectAsStateWithLifecycle()
    val online by ServiceLocator.networkMonitor.isOnline.collectAsStateWithLifecycle()
    val live by manager.live.collectAsStateWithLifecycle()

    var showDeleteAll by remember { mutableStateOf(false) }
    val available = remember(downloads.size) { manager.availableDiskSpace() }

    val active = downloads.filter { it.isActive }.sortedBy { it.dateAdded }
    val completed = downloads.filter { it.isComplete }
    val failed = downloads.filter { it.downloadStatus == DownloadStatus.FAILED }

    // Only short-circuit when there is genuinely nothing to manage. Returning on
    // an empty row list alone hid the storage figure and "Delete All" behind the
    // empty state, which is exactly when they matter most: if rows are lost but
    // files remain on disk, the user saw "No Downloads" with no way to reclaim
    // the space. DownloadManager.recover now deletes such orphans, so this is
    // belt-and-braces -- but the controls should never be unreachable while the
    // app is still holding storage.
    val onDiskBytes = remember(downloads.size) { manager.bytesOnDisk() }
    if (downloads.isEmpty() && onDiskBytes == 0L) {
        EmptyDownloads(modifier)
        return
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().widthIn(max = 640.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StorageSummary(
                    // Measured from disk, not summed from rows: the two can
                    // disagree, and the on-disk figure is the one that reflects
                    // the storage actually consumed.
                    usedBytes = maxOf(StorageAccounting.bytesUsed(downloads), onDiskBytes),
                    freeBytes = available,
                    completedCount = completed.size,
                )
            }

            if (active.isNotEmpty()) {
                item { SectionHeader("Active") }
                items(active, key = { it.listKey }) { row ->
                    ActiveRow(row, live[row.key], online, onCancel = { manager.cancel(row.key) })
                }
            }

            if (completed.isNotEmpty()) {
                item { SectionHeader("Completed") }
                if (needingRedownload.isNotEmpty()) {
                    item { RedownloadNotice(needingRedownload.size, online, onRedownloadAll = { manager.redownloadAll() }) }
                }
                items(completed, key = { it.listKey }) { row ->
                    CompletedRow(
                        row,
                        // Offline the row keeps its badge but not the action: a
                        // re-download un-completes the row, and the file could
                        // not be fetched again until the network returns.
                        onRedownload = if (row.key in needingRedownload && online) ({ manager.redownload(row.key) }) else null,
                        lowQuality = row.key in needingRedownload,
                        onDelete = { manager.delete(row.key) },
                    )
                }
            }

            if (failed.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Failed",
                            color = SashimiTextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { manager.retryAllFailed() }) { Text("Retry All", color = SashimiAccent) }
                    }
                }
                items(failed, key = { it.listKey }) { row ->
                    FailedRow(row, onRetry = { manager.retry(row.key) }, onDelete = { manager.delete(row.key) })
                }
            }

            item {
                TextButton(onClick = { showDeleteAll = true }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Delete All Downloads", color = Color.Red)
                }
            }
        }
    }

    if (showDeleteAll) {
        AlertDialog(
            onDismissRequest = { showDeleteAll = false },
            title = { Text("Delete All Downloads?") },
            text = { Text("This will remove all downloaded files from your device. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteAll = false
                    manager.deleteAll()
                }) { Text("Delete All", color = Color.Red) }
            },
            dismissButton = { TextButton(onClick = { showDeleteAll = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmptyDownloads(modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Download, contentDescription = null, tint = SashimiTextTertiary, modifier = Modifier.size(48.dp))
        Text(
            "No Downloads",
            color = SashimiTextSecondary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            "Download movies and episodes while online to watch them offline.",
            color = SashimiTextTertiary,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun StorageSummary(
    usedBytes: Long,
    freeBytes: Long,
    completedCount: Int,
) {
    Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
        Text("${formatBytes(freeBytes)} available", color = SashimiTextSecondary, fontSize = 13.sp)
        if (completedCount > 0) {
            Text("$completedCount items · ${formatBytes(usedBytes)}", color = SashimiTextTertiary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, color = SashimiTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun DownloadPoster(row: DownloadedItemEntity) {
    Box(Modifier.width(52.dp).height(78.dp).clip(RoundedCornerShape(6.dp)).background(SashimiCard)) {
        AsyncImage(
            model = OfflineImages.posterModel(row.key, fallbackImageItemId = row.seriesId ?: row.itemId),
            contentDescription = row.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun RowScaffold(
    row: DownloadedItemEntity,
    trailing: @Composable () -> Unit,
    subtitle: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SashimiCard).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DownloadPoster(row)
        Column(Modifier.weight(1f)) {
            Text(
                row.displayTitle,
                color = SashimiTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle()
        }
        trailing()
    }
}

@Composable
private fun ActiveRow(
    row: DownloadedItemEntity,
    live: LiveTransfer?,
    online: Boolean,
    onCancel: () -> Unit,
) {
    RowScaffold(
        row = row,
        subtitle = {
            val text = ActiveDownloadText.of(row, live, online)
            Text(text.status, color = SashimiTextSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            text.note?.let { Text(it, color = SashimiTextTertiary, fontSize = 11.sp, maxLines = 2) }
            val fraction = text.fraction
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    color = SashimiAccent,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            } else if (row.downloadStatus == DownloadStatus.DOWNLOADING) {
                LinearProgressIndicator(color = SashimiAccent, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        },
        trailing = {
            IconButton(onClick = onCancel) { Icon(Icons.Filled.Cancel, contentDescription = "Cancel", tint = SashimiTextSecondary) }
        },
    )
}

/**
 * Shown above the completed list while any download predates the fix to the
 * transcoded tiers (see DownloadPolicy.needsRedownload).
 */
@Composable
private fun RedownloadNotice(
    count: Int,
    online: Boolean,
    onRedownloadAll: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SashimiCard).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (count == 1) {
                "1 download was saved at very low quality by an earlier version."
            } else {
                "$count downloads were saved at very low quality by an earlier version."
            },
            color = SashimiTextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        TextButton(onClick = onRedownloadAll, enabled = online) {
            Text("Re-download All", color = if (online) SashimiAccent else SashimiTextTertiary)
        }
    }
}

@Composable
private fun CompletedRow(
    row: DownloadedItemEntity,
    lowQuality: Boolean,
    onRedownload: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    RowScaffold(
        row = row,
        subtitle = {
            Text(formatBytes(row.totalBytes), color = SashimiTextTertiary, fontSize = 12.sp)
            if (lowQuality) {
                Text(
                    if (onRedownload != null) "Low quality file · Re-download" else "Low quality file",
                    color = SashimiAccent,
                    fontSize = 12.sp,
                    modifier = if (onRedownload != null) Modifier.clickable(onClick = onRedownload) else Modifier,
                )
            }
        },
        trailing = {
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = SashimiTextSecondary) }
        },
    )
}

@Composable
private fun FailedRow(
    row: DownloadedItemEntity,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    RowScaffold(
        row = row,
        subtitle = {
            Text(row.errorMessage ?: "Download failed", color = Color.Red, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        trailing = {
            Row {
                IconButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, contentDescription = "Retry", tint = SashimiAccent) }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = SashimiTextSecondary) }
            }
        },
    )
}

/**
 * A lazy-list key unique per download. The item id alone repeats when the same
 * title is downloaded from two servers, and a duplicate key crashes the list.
 */
internal val DownloadedItemEntity.listKey: String get() = "$serverId/$itemId"
