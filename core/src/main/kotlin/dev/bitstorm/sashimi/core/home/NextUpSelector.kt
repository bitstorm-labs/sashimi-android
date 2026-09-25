package dev.bitstorm.sashimi.core.home

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType

/**
 * Next-up episode selection for a series detail page. Factored out of the Swift
 * `findNextEpisodeToPlay()` (PhoneDetailView / MobileDetailView) so the rule is
 * unit-testable and shared: prefer the server's Next Up entry for this series;
 * otherwise fall back to the first unwatched episode across the loaded seasons.
 */
object NextUpSelector {
    /** The Next Up item whose series is [seriesId], if the server returned one. */
    fun fromNextUp(
        nextUp: List<BaseItemDto>,
        seriesId: String,
    ): BaseItemDto? = nextUp.firstOrNull { it.seriesId == seriesId }

    /** First episode not marked played (the season fallback). */
    fun firstUnwatched(episodes: List<BaseItemDto>): BaseItemDto? = episodes.firstOrNull { it.userData?.played != true }

    /** Seasons in play order: Specials (season 0) after the regular seasons. */
    fun specialsLast(seasons: List<BaseItemDto>): List<BaseItemDto> =
        seasons.filter { it.indexNumber != 0 } + seasons.filter { it.indexNumber == 0 }

    /**
     * First regular (non-special) episode of a series' episode list, unwatched if
     * one is left, else the very first. Jellyfin's Next Up hands a never-started
     * show a special (sashimi-roku#134); this is what replaces it.
     */
    fun firstRegular(episodes: List<BaseItemDto>): BaseItemDto? {
        val regular = episodes.filter { !it.isSpecial }
        return firstUnwatched(regular) ?: regular.firstOrNull()
    }
}

/** A Season 0 episode. */
val BaseItemDto.isSpecial: Boolean get() = type == ItemType.EPISODE && parentIndexNumber == 0
