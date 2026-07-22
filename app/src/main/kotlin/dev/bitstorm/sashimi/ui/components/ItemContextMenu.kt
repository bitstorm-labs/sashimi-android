package dev.bitstorm.sashimi.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.di.ServiceLocator
import kotlinx.coroutines.launch

/**
 * Wraps [content] with a tap (navigate) + long-press (context menu) affordance.
 * The menu offers watched + favorite toggles, firing the client calls fire-and-
 * forget like the Swift `ItemContextMenu`; [onAction] lets the host refresh so
 * the change becomes visible (Swift relied on the next natural reload).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContextMenuBox(
    item: BaseItemDto,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onAction: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showSeriesWatchedDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val client = ServiceLocator.client

    val isPlayed = item.userData?.played == true
    val isFavorite = item.userData?.isFavorite == true
    val seriesId = item.seriesId
    val canMarkSeries = item.type == ItemType.EPISODE && seriesId != null

    Box(
        modifier =
            modifier.combinedClickable(
                onClick = onClick,
                onLongClick = { expanded = true },
            ),
    ) {
        content()
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(if (isPlayed) "Mark as Unwatched" else "Mark as Watched") },
                leadingIcon = {
                    Icon(
                        if (isPlayed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = null,
                    )
                },
                onClick = {
                    expanded = false
                    scope.launch {
                        runCatching {
                            if (isPlayed) client.markUnplayed(item.id) else client.markPlayed(item.id)
                        }
                        onAction()
                    }
                },
            )
            // Episodes in Continue Watching: marking the EPISODE watched just
            // advances Next Up to the next episode — the show never leaves the row.
            // Marking the whole SERIES watched is the only API mechanism that
            // dismisses a show from Continue Watching (verified against the server).
            if (canMarkSeries) {
                DropdownMenuItem(
                    text = { Text("Mark Series as Watched") },
                    leadingIcon = { Icon(Icons.Filled.DoneAll, contentDescription = null) },
                    onClick = {
                        expanded = false
                        showSeriesWatchedDialog = true
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(if (isFavorite) "Remove from Favorites" else "Add to Favorites") },
                leadingIcon = {
                    Icon(
                        if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = null,
                    )
                },
                onClick = {
                    expanded = false
                    scope.launch {
                        runCatching {
                            if (isFavorite) client.removeFavorite(item.id) else client.markFavorite(item.id)
                        }
                        onAction()
                    }
                },
            )
        }
    }

    if (showSeriesWatchedDialog && seriesId != null) {
        val seriesLabel = item.seriesName ?: "this series"
        AlertDialog(
            onDismissRequest = { showSeriesWatchedDialog = false },
            title = { Text("Mark Series as Watched") },
            text = { Text("Marks all episodes of $seriesLabel as watched. The show will leave Continue Watching.") },
            confirmButton = {
                TextButton(onClick = {
                    showSeriesWatchedDialog = false
                    scope.launch {
                        runCatching { client.markPlayed(seriesId) }
                        onAction()
                    }
                }) {
                    Text("Mark Watched")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSeriesWatchedDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}
