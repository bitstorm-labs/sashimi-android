package dev.bitstorm.sashimi.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.bitstorm.sashimi.core.model.JellyfinLibrary
import dev.bitstorm.sashimi.di.ServiceLocator
import dev.bitstorm.sashimi.ui.theme.SashimiAccent
import dev.bitstorm.sashimi.ui.theme.SashimiTextPrimary

/** Libraries tab: the list of media libraries. Port of PhoneLibrariesTab. */
@Composable
fun LibrariesScreen(
    onOpenLibrary: (libraryId: String, name: String, collectionType: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val libraries by produceState(initialValue = emptyList<JellyfinLibrary>()) {
        value = runCatching { ServiceLocator.client.getUserViews() }.getOrDefault(emptyList())
    }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            "Libraries",
            style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
            color = SashimiTextPrimary,
            modifier = Modifier.padding(16.dp),
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(libraries, key = { it.id }) { library ->
                LibraryRow(library = library, onClick = { onOpenLibrary(library.id, library.name, library.collectionType) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun LibraryRow(
    library: JellyfinLibrary,
    onClick: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(iconFor(library.collectionType), contentDescription = null, tint = SashimiAccent)
        Text(library.name, color = SashimiTextPrimary, modifier = Modifier.padding(start = 16.dp))
    }
}

private fun iconFor(collectionType: String?): ImageVector =
    when (collectionType) {
        "movies" -> Icons.Filled.Movie
        "tvshows" -> Icons.Filled.Tv
        "music" -> Icons.Filled.MusicNote
        else -> Icons.Filled.Folder
    }
