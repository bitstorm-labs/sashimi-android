package dev.bitstorm.sashimi.ui.downloads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bitstorm.sashimi.core.downloads.DownloadKey
import dev.bitstorm.sashimi.core.downloads.DownloadStatus
import dev.bitstorm.sashimi.core.downloads.EpisodeDownloadPlanner
import dev.bitstorm.sashimi.core.downloads.KeepNextEpisodesOption
import dev.bitstorm.sashimi.core.downloads.key
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.util.runCatchingCancellable
import dev.bitstorm.sashimi.di.ServiceLocator
import kotlinx.coroutines.launch

private enum class MenuPage { MAIN, NEXT_COUNT, KEEP_NEXT }

/**
 * An episode's download button (#181). Opens a menu instead of downloading
 * at once:
 *
 *     S2 E3 · Shooter Ready
 *     ↓ This episode            (or the existing Remove / Cancel / Retry)
 *     ↓ This and the next…      (pick N)
 *     ─────────────
 *     ↓ Unwatched in Season 2
 *     ↓ Unwatched in series
 *     ⟳ Keep next N downloaded  (pick N)
 *
 * then the usual quality picker. Which episodes each entry queues is decided
 * by [EpisodeDownloadPlanner] in :core. Offline, the bulk entries can't load
 * the series, so the plain [DownloadButton] is used instead.
 */
@Composable
fun EpisodeDownloadMenu(
    episode: BaseItemDto,
    serverId: String?,
    client: JellyfinClient,
    modifier: Modifier = Modifier,
) {
    val manager = ServiceLocator.downloadManager
    val downloads by manager.downloads.collectAsStateWithLifecycle()
    val key = DownloadKey.of(serverId, episode.id)
    val row = downloads.firstOrNull { it.key == key }
    val seriesId = episode.seriesId

    val scope = rememberCoroutineScope()
    val flow = rememberBulkDownloadFlow(serverId)
    val notificationGate = rememberNotificationPermissionGate()
    var open by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(MenuPage.MAIN) }
    var loading by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf(false) }

    fun close() {
        open = false
        page = MenuPage.MAIN
    }

    /** Loads the whole series once per choice, then stages what [select] picks. */
    fun fromSeries(select: (List<BaseItemDto>) -> List<BaseItemDto>) {
        close()
        val id = seriesId ?: return
        scope.launch {
            loading = true
            val all = runCatchingCancellable { client.getEpisodes(id) }.getOrNull()
            loading = false
            if (all == null) {
                flow.failed("Couldn't load the episodes. Check your connection and try again.")
            } else {
                flow.stage(select(all))
            }
        }
    }

    Box(modifier) {
        if (loading) {
            Box(Modifier.size(40.dp), Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        } else {
            FilledTonalIconButton(onClick = { open = true }) { DownloadButtonIcon(row) }
        }

        DropdownMenu(expanded = open, onDismissRequest = ::close) {
            when (page) {
                MenuPage.MAIN -> {
                    DropdownMenuItem(
                        text = { Text(EpisodeDownloadPlanner.menuTitle(episode), fontWeight = FontWeight.SemiBold) },
                        onClick = {},
                        enabled = false,
                    )
                    when (row?.downloadStatus) {
                        null ->
                            DropdownMenuItem(
                                text = { Text("This episode") },
                                leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                                onClick = {
                                    close()
                                    showQualityDialog = true
                                },
                            )
                        DownloadStatus.COMPLETED ->
                            DropdownMenuItem(
                                text = { Text("Remove Download") },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = {
                                    close()
                                    showRemoveConfirm = true
                                },
                            )
                        DownloadStatus.FAILED ->
                            DropdownMenuItem(
                                text = { Text("Retry Download") },
                                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                                onClick = {
                                    close()
                                    notificationGate { manager.retry(key) }
                                },
                            )
                        else ->
                            DropdownMenuItem(
                                text = { Text("Cancel Download") },
                                leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
                                onClick = {
                                    close()
                                    manager.cancel(key)
                                },
                            )
                    }
                    if (seriesId != null) {
                        DropdownMenuItem(
                            text = { Text("This and the next…") },
                            leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                            onClick = { page = MenuPage.NEXT_COUNT },
                        )
                        HorizontalDivider()
                        EpisodeDownloadPlanner.seasonLabel(episode)?.let { season ->
                            DropdownMenuItem(
                                text = { Text("Unwatched in $season") },
                                leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                                onClick = {
                                    fromSeries { EpisodeDownloadPlanner.unwatchedInSeason(episode, it, manager.downloads.value, serverId) }
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Unwatched in series") },
                            leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                            onClick = {
                                fromSeries { EpisodeDownloadPlanner.unwatchedInSeries(it, manager.downloads.value, serverId) }
                            },
                        )
                        val keeping =
                            dev.bitstorm.sashimi.ui.downloads
                                .keepNextCount(flow, seriesId)
                        DropdownMenuItem(
                            text = { Text(KeepNextEpisodesOption.menuTitle(keeping)) },
                            leadingIcon = { Icon(Icons.Filled.Autorenew, contentDescription = null) },
                            onClick = { page = MenuPage.KEEP_NEXT },
                        )
                    }
                }
                MenuPage.NEXT_COUNT -> {
                    BackItem("This and the next…") { page = MenuPage.MAIN }
                    EpisodeDownloadPlanner.NEXT_COUNTS.forEach { n ->
                        DropdownMenuItem(
                            text = { Text(KeepNextEpisodesOption.countTitle(n)) },
                            onClick = {
                                fromSeries { EpisodeDownloadPlanner.thisAndNext(episode, it, n, manager.downloads.value, serverId) }
                            },
                        )
                    }
                }
                MenuPage.KEEP_NEXT -> {
                    BackItem("Keep Next Episodes Downloaded") { page = MenuPage.MAIN }
                    if (seriesId != null) {
                        KeepNextEpisodesMenuItems(flow, seriesId, proxyItem = episode, onChosen = ::close)
                    }
                }
            }
        }
    }

    BulkDownloadDialogs(flow, serverId, client)

    if (showQualityDialog) {
        QualityDialog(
            item = episode,
            client = client,
            onDismiss = { showQualityDialog = false },
            onPick = { quality ->
                showQualityDialog = false
                notificationGate { manager.enqueueDownload(episode, quality, serverId) }
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
                    manager.delete(key)
                }) { Text("Remove", color = Color.Red) }
            },
            dismissButton = { TextButton(onClick = { showRemoveConfirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BackItem(
    title: String,
    onBack: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(title, fontWeight = FontWeight.SemiBold) },
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") },
        onClick = onBack,
    )
    HorizontalDivider()
}
