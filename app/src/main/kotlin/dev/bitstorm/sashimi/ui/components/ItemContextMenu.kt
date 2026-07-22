package dev.bitstorm.sashimi.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.bitstorm.sashimi.core.model.BaseItemDto
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
    val scope = rememberCoroutineScope()
    val client = ServiceLocator.client

    val isPlayed = item.userData?.played == true
    val isFavorite = item.userData?.isFavorite == true

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
}
