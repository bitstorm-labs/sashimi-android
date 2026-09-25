package dev.bitstorm.sashimi.ui.detail

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.bitstorm.sashimi.core.home.SeasonWatchedAction
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.ui.theme.SashimiAccent

/**
 * The season selector's "⋮" menu: Mark Season Watched / Unwatched for the
 * selected season (sashimi-roku#137). It changes every episode at once, so it
 * asks first. Specials (season 0) behave the same as any other season.
 */
@Composable
internal fun SeasonWatchedMenu(
    season: BaseItemDto,
    episodes: List<BaseItemDto>,
    onConfirm: (seasonId: String, played: Boolean) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<SeasonWatchedAction?>(null) }
    val action = SeasonWatchedAction.forSeason(season, episodes)

    IconButton(onClick = { menuOpen = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "Season options", tint = SashimiAccent)
    }
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        DropdownMenuItem(
            text = { Text(action.label) },
            leadingIcon = {
                val icon = if (action.markPlayed) Icons.Outlined.CheckCircle else Icons.Filled.RemoveDone
                Icon(icon, contentDescription = null)
            },
            onClick = {
                menuOpen = false
                pending = action
            },
        )
    }

    pending?.let { confirm ->
        val verb = if (confirm.markPlayed) "watched" else "unwatched"
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("${confirm.label}?") },
            text = { Text("Every episode in ${season.name} will be marked $verb.") },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    onConfirm(season.id, confirm.markPlayed)
                }) { Text(if (confirm.markPlayed) "Mark Watched" else "Mark Unwatched") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}
