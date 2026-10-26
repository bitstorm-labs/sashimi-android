package dev.bitstorm.sashimi.core.downloads

import dev.bitstorm.sashimi.core.model.BaseItemDto

/**
 * The bulk download actions on a series page (#68, parity with
 * sashimi-apple#112). Labels are shared with the Apple client word for word.
 */
enum class BulkDownloadAction(
    val label: String,
    /** True for the series-wide actions, which need every season's episodes. */
    val wholeSeries: Boolean,
    val unwatchedOnly: Boolean,
) {
    SEASON("Download Season", wholeSeries = false, unwatchedOnly = false),
    SEASON_UNWATCHED("Download Unwatched in Season", wholeSeries = false, unwatchedOnly = true),
    SERIES_UNWATCHED("Download Unwatched", wholeSeries = true, unwatchedOnly = true),
    SERIES("Download Series", wholeSeries = true, unwatchedOnly = false),
}

/** Which episodes a [BulkDownloadAction] queues. Pure, so the selection is unit-testable. */
object BulkDownloadPlanner {
    /** More episodes than this ask for confirmation first. */
    const val CONFIRM_THRESHOLD = 10

    /**
     * The episodes [action] would queue from [episodes] (the selected season's
     * for a season action, every season's for a series action), in order.
     *
     * - Unwatched actions drop played episodes. The series-wide one also drops
     *   specials (season 0): "unwatched" means the show's regular run.
     * - Anything already downloaded, queued or downloading from [serverId] is
     *   skipped, matching the per-item duplicate guard. A failed download is
     *   queued again.
     * - An episode listed twice (the same id under two seasons) is queued once.
     */
    fun select(
        action: BulkDownloadAction,
        episodes: List<BaseItemDto>,
        downloads: List<DownloadedItemEntity>,
        serverId: String?,
    ): List<BaseItemDto> {
        val server = serverId ?: DownloadKey.UNKNOWN_SERVER
        val taken =
            downloads
                .filter { it.serverId == server && DownloadPolicy.isDuplicate(it) }
                .map { it.itemId }
                .toSet()
        return episodes
            .asSequence()
            .filter { !action.unwatchedOnly || it.userData?.played != true }
            .filter { !(action.unwatchedOnly && action.wholeSeries && isSpecial(it)) }
            .filter { it.id !in taken }
            .distinctBy { it.id }
            .toList()
    }

    /** True for a special: an episode filed under season 0. */
    fun isSpecial(episode: BaseItemDto): Boolean = episode.parentIndexNumber == 0

    /** Whether queuing [count] episodes asks "Download N episodes?" first. */
    fun needsConfirmation(count: Int): Boolean = count > CONFIRM_THRESHOLD

    /** The confirmation title (no size: there is no per-episode size estimate yet). */
    fun confirmationTitle(count: Int): String = "Download $count episodes?"
}

/**
 * The download menu on a single episode (#181): which episodes each entry
 * queues. Every list is in the series' airing order (the order
 * `/Shows/{id}/Episodes` returns, seasons ascending) and drops anything
 * already downloaded, queued or downloading from the server, as
 * [BulkDownloadPlanner.select] does.
 */
object EpisodeDownloadPlanner {
    /** The counts offered for "This and the next…". */
    val NEXT_COUNTS = listOf(1, 3, 5, 10)

    /**
     * "This and the next N": [episode] plus the [count] regular episodes that
     * follow it in [seriesEpisodes], crossing into later seasons. Specials
     * (season 0) are never among the next N. The window is positional: an
     * episode in it that is already downloaded or queued is skipped, not
     * replaced by one further on. Watched episodes in the window are included.
     */
    fun thisAndNext(
        episode: BaseItemDto,
        seriesEpisodes: List<BaseItemDto>,
        count: Int,
        downloads: List<DownloadedItemEntity>,
        serverId: String?,
    ): List<BaseItemDto> {
        val ordered = seriesEpisodes.distinctBy { it.id }
        val start = ordered.indexOfFirst { it.id == episode.id }
        val following =
            if (start < 0) {
                emptyList()
            } else {
                ordered
                    .drop(start + 1)
                    .filterNot(BulkDownloadPlanner::isSpecial)
                    .take(count.coerceAtLeast(0))
            }
        return BulkDownloadPlanner.select(BulkDownloadAction.SERIES, listOf(episode) + following, downloads, serverId)
    }

    /** "Unwatched in Season X": [episode]'s season, as the series page's season action. */
    fun unwatchedInSeason(
        episode: BaseItemDto,
        seriesEpisodes: List<BaseItemDto>,
        downloads: List<DownloadedItemEntity>,
        serverId: String?,
    ): List<BaseItemDto> {
        val season = seriesEpisodes.filter { sameSeason(it, episode) }
        return BulkDownloadPlanner.select(BulkDownloadAction.SEASON_UNWATCHED, season, downloads, serverId)
    }

    /** "Unwatched in series": the series page's "Download Unwatched". */
    fun unwatchedInSeries(
        seriesEpisodes: List<BaseItemDto>,
        downloads: List<DownloadedItemEntity>,
        serverId: String?,
    ): List<BaseItemDto> = BulkDownloadPlanner.select(BulkDownloadAction.SERIES_UNWATCHED, seriesEpisodes, downloads, serverId)

    /** "Season 2", or "Specials" for season 0; null when the episode has no season number. */
    fun seasonLabel(episode: BaseItemDto): String? =
        when (val n = episode.parentIndexNumber) {
            null -> null
            0 -> "Specials"
            else -> "Season $n"
        }

    /** "S2 E3 · Shooter Ready", the menu's header. */
    fun menuTitle(episode: BaseItemDto): String {
        val s = episode.parentIndexNumber
        val e = episode.indexNumber
        return if (s != null && e != null) "S$s E$e \u00B7 ${episode.name}" else episode.name
    }

    private fun sameSeason(
        a: BaseItemDto,
        b: BaseItemDto,
    ): Boolean {
        val aId = a.seasonId
        val bId = b.seasonId
        return if (aId != null && bId != null) aId == bId else a.parentIndexNumber == b.parentIndexNumber
    }
}

/**
 * What the global download indicator shows (#69, parity with
 * sashimi-apple#115): how many downloads are queued or running, and their
 * overall progress.
 */
data class DownloadActivity(
    /** Queued, preparing and downloading items. */
    val activeCount: Int,
    /**
     * Overall progress in 0..1: the average of every active item's progress,
     * with queued and preparing items at 0. Null (an indeterminate ring) when
     * every active item is a stream of unknown size.
     */
    val progress: Float?,
) {
    companion object {
        /** The indicator for [downloads], or null when nothing is active (hide it). */
        fun of(downloads: List<DownloadedItemEntity>): DownloadActivity? {
            val active = downloads.filter { it.isActive }
            if (active.isEmpty()) return null
            val known =
                active.mapNotNull { row ->
                    when (row.downloadStatus) {
                        DownloadStatus.DOWNLOADING -> row.progress.takeIf { it >= 0 }
                        else -> 0.0
                    }
                }
            val progress = if (known.isEmpty()) null else known.average().coerceIn(0.0, 1.0).toFloat()
            return DownloadActivity(activeCount = active.size, progress = progress)
        }
    }
}
