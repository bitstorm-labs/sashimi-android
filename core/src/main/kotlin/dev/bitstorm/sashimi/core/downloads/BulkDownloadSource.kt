package dev.bitstorm.sashimi.core.downloads

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.network.JellyfinClient

/**
 * The episodes a [BulkDownloadAction] chooses from, fetched from the server at
 * the moment of the choice (#186). The page's own episode list can be minutes
 * old, so "Unwatched in Season" picked from it could queue an episode the
 * user has since watched on another device, or skip one they unmarked.
 */
object BulkDownloadSource {
    suspend fun episodes(
        client: JellyfinClient,
        action: BulkDownloadAction,
        seriesId: String,
        seasonId: String?,
    ): List<BaseItemDto> =
        if (action.wholeSeries) {
            client.getEpisodes(seriesId)
        } else {
            client.getEpisodes(seriesId, seasonId ?: return emptyList())
        }
}
