package dev.bitstorm.sashimi.ui.home

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.network.JellyfinClient

/**
 * Loads a library's Recently Added items: fetch latest (incl. watched), dedupe
 * by series, cap at 20, and — for TV libraries — resolve per-series unplayed
 * counts for the "X new" badge. Port of MobileRecentlyAddedRow.loadItems, shared
 * by the Home row and its See All grid.
 */
object RecentlyAddedLoader {
    suspend fun load(
        client: JellyfinClient,
        libraryId: String,
        libraryName: String,
        collectionType: String?,
    ): RecentlyAddedData {
        val isYouTube = libraryName.contains("youtube", ignoreCase = true)
        val latest =
            runCatching {
                client.getLatestMedia(
                    parentId = libraryId,
                    limit = 30,
                    includeWatched = true,
                    collectionType = collectionType,
                    isYouTubeLibrary = isYouTube,
                )
            }.getOrDefault(emptyList())

        val deduped = deduplicateBySeries(latest)
        val badgeCounts =
            if (collectionType?.lowercase() == "tvshows") loadUnplayedCounts(client, deduped) else emptyMap()
        return RecentlyAddedData(items = deduped, badgeCounts = badgeCounts)
    }

    private suspend fun loadUnplayedCounts(
        client: JellyfinClient,
        items: List<BaseItemDto>,
    ): Map<String, Int> {
        val seriesIds =
            items.mapNotNull { item ->
                when (item.type) {
                    ItemType.EPISODE, ItemType.VIDEO -> item.seriesId
                    ItemType.SERIES -> item.id
                    else -> null
                }
            }.toSet()
        val counts = mutableMapOf<String, Int>()
        for (seriesId in seriesIds) {
            runCatching {
                val unplayed = client.getItem(seriesId).userData?.unplayedItemCount
                if (unplayed != null && unplayed >= 1) counts[seriesId] = unplayed
            }
        }
        return counts
    }

    private fun deduplicateBySeries(items: List<BaseItemDto>): List<BaseItemDto> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<BaseItemDto>()
        for (item in items) {
            val key =
                if (item.type == ItemType.EPISODE || item.type == ItemType.VIDEO) {
                    item.seriesId ?: item.id
                } else {
                    item.id
                }
            if (seen.add(key)) result.add(item)
        }
        return result.take(20)
    }
}
