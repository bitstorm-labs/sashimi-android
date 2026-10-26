package dev.bitstorm.sashimi.core.detail

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType

/** A button in a detail page's main action row. */
enum class DetailRowButton { PLAY, START_OVER, SHUFFLE, TRAILER, WATCHED, DOWNLOAD, MORE }

/** An entry in a detail page's "More" menu. */
enum class DetailMenuItem {
    GO_TO_SERIES,
    GO_TO_SEASON,
    FAVORITE,
    FILE_INFO,
    REFRESH_METADATA,
    DELETE,
    ;

    /** Favorite through Delete: server calls, listed after a divider. */
    val isItemManagement: Boolean get() = ordinal >= FAVORITE.ordinal
}

/**
 * Which actions a movie, series or episode detail page offers, and in what
 * order. Pure and Compose-free so the rules are unit-tested and a TV layout
 * can reuse them.
 */
object DetailActions {
    /**
     * The main row, in Apple's order (DetailRowAction.mainRow, #198): Play,
     * Start Over (when [hasStartOver]: there is progress to start over from),
     * Shuffle (series), Trailer (a movie or series with a local trailer),
     * Watched, Download (movies and episodes), More. Watched and Shuffle are
     * server calls, so online-only.
     */
    fun mainRow(
        item: BaseItemDto,
        online: Boolean,
        hasStartOver: Boolean = false,
    ): List<DetailRowButton> =
        buildList {
            add(DetailRowButton.PLAY)
            if (hasStartOver) add(DetailRowButton.START_OVER)
            if (online && item.type == ItemType.SERIES) add(DetailRowButton.SHUFFLE)
            if ((item.localTrailerCount ?: 0) > 0 && item.type != ItemType.EPISODE) add(DetailRowButton.TRAILER)
            if (online) add(DetailRowButton.WATCHED)
            if (item.type == ItemType.MOVIE || item.type == ItemType.EPISODE) add(DetailRowButton.DOWNLOAD)
            add(DetailRowButton.MORE)
        }

    /**
     * The More menu: Go to Series / Go to Season for an episode, then the
     * item management block. Everything in it is a server call, so it is
     * empty (and the More button hidden) offline.
     */
    fun moreMenu(
        item: BaseItemDto,
        online: Boolean,
    ): List<DetailMenuItem> =
        buildList {
            if (!online) return@buildList
            if (item.type == ItemType.EPISODE && item.seriesId != null) add(DetailMenuItem.GO_TO_SERIES)
            if (item.type == ItemType.EPISODE && item.seasonId != null) add(DetailMenuItem.GO_TO_SEASON)
            add(DetailMenuItem.FAVORITE)
            add(DetailMenuItem.FILE_INFO)
            add(DetailMenuItem.REFRESH_METADATA)
            add(DetailMenuItem.DELETE)
        }

    /**
     * The item id Play hands the player: a series plays its resolved next-up
     * [nextEpisode], a season its [SeasonPage.playTarget] among
     * [seasonEpisodes], anything else plays itself.
     */
    fun playTargetId(
        item: BaseItemDto?,
        nextEpisode: BaseItemDto?,
        seasonEpisodes: List<BaseItemDto>,
    ): String? =
        when (item?.type) {
            ItemType.SERIES -> nextEpisode?.id
            ItemType.SEASON -> SeasonPage.playTarget(seasonEpisodes)?.episode?.id
            else -> item?.id
        }

    /** "Go to Season 2" for an episode; Season 0 is "Go to Specials". */
    fun goToSeasonLabel(episode: BaseItemDto): String =
        when (val n = episode.parentIndexNumber) {
            null -> "Go to Season"
            0 -> "Go to Specials"
            else -> "Go to Season $n"
        }
}
