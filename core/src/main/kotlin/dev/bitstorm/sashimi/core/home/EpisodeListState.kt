package dev.bitstorm.sashimi.core.home

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.UserItemDataDto

/**
 * Keeping a detail page's episode list in step with the server (#186). Pure,
 * so the rules are unit-tested; the detail ViewModel applies them.
 */
object EpisodeListState {
    /**
     * The season whose episodes a (re)load fetches: the one the user is on,
     * else the default. A reload must refetch the selected season too, or the
     * list keeps the watched marks it had when the page first opened.
     */
    fun seasonToLoad(
        selectedSeasonId: String?,
        defaultSeasonId: String?,
    ): String? = selectedSeasonId ?: defaultSeasonId

    /**
     * [episodes] with [itemId] marked played or unplayed, the way the server
     * now has it: marking played clears the resume position, as Jellyfin does.
     */
    fun withPlayed(
        episodes: List<BaseItemDto>,
        itemId: String,
        played: Boolean,
    ): List<BaseItemDto> =
        episodes.map { episode ->
            if (episode.id != itemId) return@map episode
            val data = episode.userData ?: UserItemDataDto()
            episode.copy(
                userData =
                    data.copy(
                        played = played,
                        playbackPositionTicks = if (played) 0 else data.playbackPositionTicks,
                    ),
            )
        }
}
