package dev.bitstorm.sashimi.core.playback

import dev.bitstorm.sashimi.core.model.BaseItemDto

/**
 * Player auto-play-next-episode logic, factored out of the Swift
 * PlayerViewModel `playNextEpisode()` / `findNextEpisode()`. Kept separate from
 * [dev.bitstorm.sashimi.core.home.NextUpSelector] (which drives the *detail*
 * page's next-up button): this resolves the episode that plays automatically
 * when the current one ends, including season rollover and the YouTube
 * index-based ordering.
 *
 * The two decisions are pure functions over already-loaded data; the player
 * ViewModel orchestrates the fetch of the next season's episodes when
 * [nextSeasonId] returns non-null.
 */
object AutoPlayNextResolver {
    /**
     * The episode immediately after [current] within [episodes] (same season, or
     * the flat ordered list a YouTube "series" exposes). Matches the Swift
     * index-based lookup: find the current item, return the following one.
     * Null when [current] is not found or is the last entry.
     */
    fun nextInList(
        current: BaseItemDto,
        episodes: List<BaseItemDto>,
    ): BaseItemDto? {
        val index = episodes.indexOfFirst { it.id == current.id }
        if (index < 0 || index + 1 >= episodes.size) return null
        return episodes[index + 1]
    }

    /**
     * The id of the season that follows the one containing [current], for season
     * rollover. [seasons] is expected in display order (the Shows/Seasons order).
     * Returns null when the current season is the last, or can't be located.
     *
     * The Swift code rolls over to the next season and plays its first episode;
     * the caller loads that season's episodes and takes `.first`.
     */
    fun nextSeasonId(
        currentSeasonId: String?,
        seasons: List<BaseItemDto>,
    ): String? {
        if (currentSeasonId == null) return null
        val index = seasons.indexOfFirst { it.id == currentSeasonId }
        if (index < 0 || index + 1 >= seasons.size) return null
        return seasons[index + 1].id
    }
}
