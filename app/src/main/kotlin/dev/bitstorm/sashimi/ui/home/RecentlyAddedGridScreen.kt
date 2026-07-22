package dev.bitstorm.sashimi.ui.home

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import dev.bitstorm.sashimi.core.model.cleanedYouTubeTitle
import dev.bitstorm.sashimi.di.ServiceLocator
import dev.bitstorm.sashimi.ui.components.BackTopBar
import dev.bitstorm.sashimi.ui.components.PosterGrid

/**
 * "See All" grid for a Home Recently Added row. Reuses [RecentlyAddedLoader] so
 * the dedupe/cap/badge rules match the row exactly. Port of
 * MobileRecentlyAddedGridView.
 */
@Composable
fun RecentlyAddedGridScreen(
    libraryId: String,
    libraryName: String,
    collectionType: String?,
    isCompact: Boolean,
    onBack: () -> Unit,
    onOpenDetail: (itemId: String, libraryName: String?) -> Unit,
) {
    val data by produceState(initialValue = RecentlyAddedData(), libraryId) {
        value = RecentlyAddedLoader.load(ServiceLocator.client, libraryId, libraryName, collectionType)
    }

    Scaffold(
        topBar = {
            BackTopBar(title = "Recently Added $libraryName".cleanedYouTubeTitle(), onBack = onBack)
        },
    ) { padding ->
        PosterGrid(
            items = data.items,
            isCompact = isCompact,
            libraryName = libraryName,
            badgeCounts = data.badgeCounts,
            onOpenDetail = onOpenDetail,
            modifier = Modifier.padding(padding),
        )
    }
}
