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
