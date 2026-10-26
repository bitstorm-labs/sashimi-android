package dev.bitstorm.sashimi.core.detail

import dev.bitstorm.sashimi.core.downloads.OfflineReconstruction
import dev.bitstorm.sashimi.core.home.SeasonWatchedAction
import dev.bitstorm.sashimi.core.model.BaseItemDto

/** What the season page's Play button plays. */
data class SeasonPlayTarget(
    val episode: BaseItemDto,
    /** The episode has a saved position to resume from. */
    val resume: Boolean,
    /** Every episode is watched, so this replays E1 from its start. */
    val fromBeginning: Boolean,
) {
    /** "Play S2·E6" / "Resume S2·E6". */
    val label: String
        get() {
            val verb = if (resume) "Resume" else "Play"
            val s = episode.parentIndexNumber
            val e = episode.indexNumber
            return if (s != null && e != null) "$verb S$s·E$e" else verb
        }
}

/** A button in the season page's action row, in order. */
enum class SeasonPageButton { PLAY, MARK_SEASON, DOWNLOAD_SEASON, SERIES }

/** What tapping (or pressing select on) a season tab does. */
enum class SeasonTabTap { SELECT, OPEN }

/**
 * The season page (#195): one season's episodes with Play, Mark Season
 * Watched, Download Season and Series. Pure and Compose-free so the rules
 * are unit-tested and a TV layout can reuse them.
 */
object SeasonPage {
    /**
     * The next unwatched episode (resumed when it has progress); when every
     * episode is watched, the first episode from its start. [episodes] is the
     * season in order.
     */
    fun playTarget(episodes: List<BaseItemDto>): SeasonPlayTarget? {
        val next = episodes.firstOrNull { it.userData?.played != true }
        if (next != null) {
            return SeasonPlayTarget(next, resume = (next.userData?.playbackPositionTicks ?: 0) > 0, fromBeginning = false)
        }
        val first = episodes.firstOrNull() ?: return null
        return SeasonPlayTarget(first, resume = false, fromBeginning = true)
    }

    /** "Season 2 · 8 episodes · 5 of 8 watched". */
    fun subtitle(
        season: BaseItemDto,
        episodes: List<BaseItemDto>,
    ): String {
        val count = episodes.size
        val watched = episodes.count { it.userData?.played == true }
        val noun = if (count == 1) "episode" else "episodes"
        return "${season.name} · $count $noun · $watched of $count watched"
    }

    /** Mark Season Watched, or Unwatched once every episode is watched. */
    fun watchedAction(
        season: BaseItemDto,
        episodes: List<BaseItemDto>,
    ): SeasonWatchedAction = SeasonWatchedAction.forSeason(season, episodes)

    /** Marking and downloading are server calls, so online-only. */
    fun buttons(online: Boolean): List<SeasonPageButton> =
        buildList {
            add(SeasonPageButton.PLAY)
            if (online) {
                add(SeasonPageButton.MARK_SEASON)
                add(SeasonPageButton.DOWNLOAD_SEASON)
            }
            add(SeasonPageButton.SERIES)
        }

    /**
     * Selecting the already-selected season tab again opens its page; any
     * other tab just selects. Offline seasons never open.
     */
    fun onTabTap(
        seasonId: String,
        selectedSeasonId: String?,
    ): SeasonTabTap = if (seasonId == selectedSeasonId && canOpen(seasonId)) SeasonTabTap.OPEN else SeasonTabTap.SELECT

    /**
     * Whether [seasonId] has a page. Offline series pages use synthetic
     * season ids built from the download store, which the server can't load.
     */
    fun canOpen(seasonId: String): Boolean = !seasonId.startsWith(OfflineReconstruction.SEASON_ID_PREFIX)
}
