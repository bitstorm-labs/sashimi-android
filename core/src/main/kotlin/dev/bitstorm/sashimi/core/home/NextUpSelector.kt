package dev.bitstorm.sashimi.core.home

import dev.bitstorm.sashimi.core.model.BaseItemDto

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
}
