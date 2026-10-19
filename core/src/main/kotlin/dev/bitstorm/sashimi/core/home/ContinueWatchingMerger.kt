package dev.bitstorm.sashimi.core.home

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Continue Watching: Resume + Next Up, most recent activity first, one entry per
 * series, capped at [LIMIT]. Port of sashimi-apple's
 * `HomeViewModel.mergeAndSortContinueItems`.
 *
 * Every candidate gets a real activity date. Resume items use their own
 * LastPlayedDate. A Next Up item uses the newer of its own date and its series'
 * last finished episode from [seriesLastPlayed] (its own can be weeks stale, or
 * missing for an unstarted episode). Failing both it sits a second below the item before
 * it -- Next Up is ordered by series activity -- starting just below the newest
 * real date.
 *
 * Stamping undated Next Up as "now" put every Next Up ahead of every Resume: a
 * movie paused minutes ago (Resume-only) sat behind a show untouched for a week.
 * Anchoring without the series date put a show binged all evening behind any
 * other show with a date (sashimi-apple#647).
 */
object ContinueWatchingMerger {
    const val LIMIT = 20

    fun merge(
        resume: List<BaseItemDto>,
        nextUp: List<BaseItemDto>,
        seriesLastPlayed: Map<String, Long> = emptyMap(),
    ): List<BaseItemDto> {
        val playableResume = resume.filter { it.isPlayable }
        val playableNextUp = nextUp.filter { it.isPlayable }

        val candidates =
            playableResume.mapTo(mutableListOf()) {
                Candidate(it, parseDate(it.userData?.lastPlayedDate) ?: Long.MIN_VALUE, isResume = true)
            }
        val newestKnown =
            (playableResume + playableNextUp).mapNotNull { parseDate(it.userData?.lastPlayedDate) }.maxOrNull()
        var carried = newestKnown ?: Instant.now().toEpochMilli()
        for (item in playableNextUp) {
            // An episode opened weeks ago keeps that stale date while the season
            // before it is binged, so take the newer of its own and its series'.
            val own = parseDate(item.userData?.lastPlayedDate)
            val series = item.seriesId?.let { seriesLastPlayed[it] }
            carried = listOfNotNull(own, series).maxOrNull() ?: (carried - 1000L)
            candidates.add(Candidate(item, carried, isResume = false))
        }

        // Newest first; Resume wins ties so a part-watched episode is offered
        // for resuming rather than skipped past to the next one.
        candidates.sortWith(compareByDescending<Candidate> { it.date }.thenByDescending { it.isResume })

        val merged = mutableListOf<BaseItemDto>()
        val seenSeries = mutableSetOf<String>()
        val seenIds = mutableSetOf<String>()
        for (candidate in candidates) {
            val item = candidate.item
            if (item.id in seenIds) continue
            val seriesId = item.seriesId
            if (seriesId != null && !seenSeries.add(seriesId)) continue
            seenIds.add(item.id)
            merged.add(item)
            if (merged.size >= LIMIT) break
        }
        return merged
    }

    /** Each series' latest finished episode, in epoch millis. */
    fun seriesLastPlayed(episodes: List<BaseItemDto>): Map<String, Long> {
        val dates = mutableMapOf<String, Long>()
        for (episode in episodes) {
            val seriesId = episode.seriesId ?: continue
            val played = parseDate(episode.userData?.lastPlayedDate) ?: continue
            dates[seriesId] = maxOf(dates[seriesId] ?: played, played)
        }
        return dates
    }

    fun parseDate(raw: String?): Long? {
        if (raw.isNullOrEmpty()) return null
        return runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(raw).toEpochMilli() }
            .getOrNull()
    }

    private data class Candidate(
        val item: BaseItemDto,
        val date: Long,
        val isResume: Boolean,
    )

    /** Folders (a partly watched Season or Series from Resume) can't be resumed. */
    private val BaseItemDto.isPlayable: Boolean
        get() =
            type !in setOf(ItemType.SEASON, ItemType.SERIES, ItemType.BOX_SET, ItemType.FOLDER, ItemType.COLLECTION_FOLDER)
}
