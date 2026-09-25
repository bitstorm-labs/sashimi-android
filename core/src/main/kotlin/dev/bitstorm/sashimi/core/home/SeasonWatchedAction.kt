package dev.bitstorm.sashimi.core.home

import dev.bitstorm.sashimi.core.model.BaseItemDto

/**
 * Which whole-season watched action a series detail page offers for the selected
 * season (sashimi-roku#137, parity across clients). Jellyfin marks every episode
 * of a season with one call: POST / DELETE `/Users/{uid}/PlayedItems/{seasonId}`.
 */
enum class SeasonWatchedAction(
    val label: String,
    /** The `played` value the action sets. */
    val markPlayed: Boolean,
) {
    MARK_WATCHED("Mark Season Watched", markPlayed = true),
    MARK_UNWATCHED("Mark Season Unwatched", markPlayed = false),
    ;

    companion object {
        /**
         * Unwatched only when every episode of the season is already played;
         * anything else (including a partly watched season) offers Watched.
         *
         * [episodes] is the loaded episode list for [season]. When it is empty
         * (not loaded, or an empty season) the season's own played flag decides.
         */
        fun forSeason(
            season: BaseItemDto?,
            episodes: List<BaseItemDto>,
        ): SeasonWatchedAction {
            val allPlayed =
                if (episodes.isNotEmpty()) {
                    episodes.all { it.userData?.played == true }
                } else {
                    season?.userData?.played == true
                }
            return if (allPlayed) MARK_UNWATCHED else MARK_WATCHED
        }
    }
}
