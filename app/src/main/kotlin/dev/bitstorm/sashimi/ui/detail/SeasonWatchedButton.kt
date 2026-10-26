package dev.bitstorm.sashimi.ui.detail

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.bitstorm.sashimi.core.detail.SeasonPage
import dev.bitstorm.sashimi.core.model.BaseItemDto

/**
 * The season page's Mark Season Watched / Unwatched button (sashimi-roku#137,
 * #195). It changes every episode at once, so it asks first. Specials
 * (season 0) behave the same as any other season.
 */
@Composable
internal fun SeasonWatchedButton(
    season: BaseItemDto,
    episodes: List<BaseItemDto>,
    onConfirm: (seasonId: String, played: Boolean) -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    val action = SeasonPage.watchedAction(season, episodes)

    FilledTonalButton(onClick = { confirming = true }) {
        val icon = if (action.markPlayed) Icons.Outlined.CheckCircle else Icons.Filled.RemoveDone
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(action.label, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 6.dp))
    }

    if (confirming) {
        val verb = if (action.markPlayed) "watched" else "unwatched"
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("${action.label}?") },
            text = { Text("Every episode in ${season.name} will be marked $verb.") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onConfirm(season.id, action.markPlayed)
                }) { Text(if (action.markPlayed) "Mark Watched" else "Mark Unwatched") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}
