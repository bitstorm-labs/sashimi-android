package dev.bitstorm.sashimi.core.detail

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType

/** A button in a detail page's main action row. */
enum class DetailRowButton { PLAY, WATCHED, DOWNLOAD, SHUFFLE, MORE }

/** An entry in a detail page's "More" menu. */
enum class DetailMenuItem {
    GO_TO_SERIES,
    GO_TO_SEASON,
    START_OVER,
    PLAY_TRAILER,
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
    /** The main row. Every server-backed button is online-only. */
    fun mainRow(
        item: BaseItemDto,
        online: Boolean,
    ): List<DetailRowButton> =
        buildList {
            add(DetailRowButton.PLAY)
            if (online) add(DetailRowButton.WATCHED)
            if (item.type == ItemType.MOVIE || item.type == ItemType.EPISODE) add(DetailRowButton.DOWNLOAD)
            if (item.type == ItemType.SERIES) add(DetailRowButton.SHUFFLE)
            add(DetailRowButton.MORE)
        }

    /**
     * The More menu. [hasStartOver] is whether there is progress to start over
     * from. Empty means the More button is hidden.
     */
    fun moreMenu(
        item: BaseItemDto,
        online: Boolean,
        hasStartOver: Boolean,
    ): List<DetailMenuItem> =
        buildList {
            if (hasStartOver) add(DetailMenuItem.START_OVER)
            if (online && item.type == ItemType.EPISODE && item.seriesId != null) add(DetailMenuItem.GO_TO_SERIES)
            if ((item.localTrailerCount ?: 0) > 0) add(DetailMenuItem.PLAY_TRAILER)
            if (online) {
                add(DetailMenuItem.FAVORITE)
                add(DetailMenuItem.FILE_INFO)
                add(DetailMenuItem.REFRESH_METADATA)
                add(DetailMenuItem.DELETE)
            }
        }

    /**
     * The item id Play hands the player: a series plays its resolved next-up
     * [nextEpisode], anything else plays itself.
     */
    fun playTargetId(
        item: BaseItemDto?,
        nextEpisode: BaseItemDto?,
    ): String? = if (item?.type == ItemType.SERIES) nextEpisode?.id else item?.id

    /** "Go to Season 2" for an episode; Season 0 is "Go to Specials". */
    fun goToSeasonLabel(episode: BaseItemDto): String =
        when (val n = episode.parentIndexNumber) {
            null -> "Go to Season"
            0 -> "Go to Specials"
            else -> "Go to Season $n"
        }
}
