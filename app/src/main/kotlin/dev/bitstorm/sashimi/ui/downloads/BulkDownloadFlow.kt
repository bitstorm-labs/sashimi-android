package dev.bitstorm.sashimi.ui.downloads

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bitstorm.sashimi.core.downloads.BulkDownloadPlanner
import dev.bitstorm.sashimi.core.downloads.DownloadQuality
import dev.bitstorm.sashimi.core.downloads.KeepNextEpisodesOption
import dev.bitstorm.sashimi.core.downloads.KeepNextEpisodesStore
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.di.ServiceLocator

/**
 * The shared tail of every bulk download (series page menu, episode menu):
 * "nothing to download" notice, the quality picker, the "Download N episodes?"
 * confirmation above [BulkDownloadPlanner.CONFIRM_THRESHOLD], then the queue.
 * Also turns "Keep next episodes downloaded" on, asking for a quality only
 * when the show has no download to take it from (sashimi-apple parity).
 */
@Stable
class BulkDownloadFlow internal constructor(
    /** The saved server the downloads come from; null = the active one. */
    private val serverId: String?,
) {
    internal var notice by mutableStateOf<String?>(null)
    internal var pending by mutableStateOf<List<BaseItemDto>?>(null)
    internal var confirmQuality by mutableStateOf<DownloadQuality?>(null)
    internal var keepNextPending by mutableStateOf<KeepNextRequest?>(null)

    internal data class KeepNextRequest(
        val count: Int,
        val seriesId: String,
        val proxyItem: BaseItemDto,
    )

    /** Queues [selection] after the quality (and, for big batches, a confirmation). */
    fun stage(selection: List<BaseItemDto>) {
        if (selection.isEmpty()) {
            notice = "Every episode is already downloaded or queued."
        } else {
            pending = selection
        }
    }

    fun failed(message: String) {
        notice = message
    }

    /** The server whose keep-next setting this flow edits. */
    val keepNextServerId: String?
        get() = serverId ?: ServiceLocator.session.activeServerId.value
}

@Composable
fun rememberBulkDownloadFlow(serverId: String?): BulkDownloadFlow = remember(serverId) { BulkDownloadFlow(serverId) }

/** The show's kept count (0 = off), recomposing when the setting changes. */
@Composable
fun keepNextCount(
    flow: BulkDownloadFlow,
    seriesId: String,
): Int {
    val settings by ServiceLocator.keepNextEpisodes.settings.collectAsStateWithLifecycle()
    val serverId = flow.keepNextServerId ?: return 0
    return settings[KeepNextEpisodesStore.key(serverId, seriesId)]?.count ?: 0
}

/** The dialogs a [BulkDownloadFlow] shows. Place once next to the menu that drives it. */
@Composable
fun BulkDownloadDialogs(
    flow: BulkDownloadFlow,
    serverId: String?,
    client: JellyfinClient,
) {
    val manager = ServiceLocator.downloadManager
    val context = LocalContext.current
    val notificationGate = rememberNotificationPermissionGate()

    fun enqueue(
        selection: List<BaseItemDto>,
        quality: DownloadQuality,
    ) {
        flow.pending = null
        flow.confirmQuality = null
        notificationGate { manager.enqueueDownloads(selection, quality, serverId) }
    }

    flow.notice?.let { message ->
        AlertDialog(
            onDismissRequest = { flow.notice = null },
            title = { Text("Nothing to Download") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { flow.notice = null }) { Text("OK") } },
        )
    }

    flow.keepNextPending?.let { request ->
        QualityDialog(
            item = request.proxyItem,
            client = client,
            onDismiss = { flow.keepNextPending = null },
            onPick = { quality ->
                flow.keepNextPending = null
                val server = flow.keepNextServerId ?: return@QualityDialog
                notificationGate { applyKeepNext(context, request.count, server, request.seriesId, quality) }
            },
        )
    }

    val selection = flow.pending
    val quality = flow.confirmQuality
    if (selection != null && quality == null) {
        QualityDialog(
            item = selection.first(),
            client = client,
            seasonProxyItemId = selection.first().id,
            onDismiss = { flow.pending = null },
            onPick = { picked ->
                if (BulkDownloadPlanner.needsConfirmation(selection.size)) {
                    flow.confirmQuality = picked
                } else {
                    enqueue(selection, picked)
                }
            },
        )
    }
    if (selection != null && quality != null) {
        AlertDialog(
            onDismissRequest = {
                flow.pending = null
                flow.confirmQuality = null
            },
            title = { Text(BulkDownloadPlanner.confirmationTitle(selection.size)) },
            text = { Text("${quality.displayName}. Episodes already downloaded or queued are skipped.") },
            confirmButton = { TextButton(onClick = { enqueue(selection, quality) }) { Text("Download") } },
            dismissButton = {
                TextButton(onClick = {
                    flow.pending = null
                    flow.confirmQuality = null
                }) { Text("Cancel") }
            },
        )
    }
}

/**
 * The 1 / 3 / 5 / Off choices for "Keep next episodes downloaded", as menu
 * items with a check on the current one. Changing the count or turning it off
 * needs no quality; turning it on takes the quality of the show's latest
 * download, or asks for one.
 */
@Composable
fun KeepNextEpisodesMenuItems(
    flow: BulkDownloadFlow,
    seriesId: String,
    /** An episode of the show, for the quality dialog's Original check. */
    proxyItem: BaseItemDto,
    onChosen: () -> Unit,
) {
    val context = LocalContext.current
    val store = ServiceLocator.keepNextEpisodes
    val serverId = flow.keepNextServerId ?: return
    val current = keepNextCount(flow, seriesId)
    (KeepNextEpisodesOption.COUNTS + 0).forEach { count ->
        DropdownMenuItem(
            text = { Text(if (count == 0) "Off" else KeepNextEpisodesOption.countTitle(count)) },
            leadingIcon = { if (count == current) Icon(Icons.Filled.Check, contentDescription = "Selected") },
            onClick = {
                onChosen()
                if (count == 0 || store.setting(serverId, seriesId) != null) {
                    applyKeepNext(context, count, serverId, seriesId, quality = null)
                } else {
                    val latest =
                        ServiceLocator.downloadManager.downloads.value
                            .filter { it.serverId == serverId && it.seriesId == seriesId }
                            .maxByOrNull { it.dateAdded }
                            ?.downloadQuality
                    if (latest != null) {
                        applyKeepNext(context, count, serverId, seriesId, latest)
                    } else {
                        flow.keepNextPending = BulkDownloadFlow.KeepNextRequest(count, seriesId, proxyItem)
                    }
                }
            },
        )
    }
}

private fun applyKeepNext(
    context: android.content.Context,
    count: Int,
    serverId: String,
    seriesId: String,
    quality: DownloadQuality?,
) {
    ServiceLocator.keepNextEpisodes.set(count, serverId, seriesId, quality)
    if (count > 0) {
        Toast.makeText(context, KeepNextEpisodesOption.confirmation(count), Toast.LENGTH_SHORT).show()
        ServiceLocator.keepNextSync.scheduleSync(0)
    }
}
