package dev.bitstorm.sashimi.core.home

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.model.UserItemDataDto
import org.junit.Assert.assertEquals
import org.junit.Test

private fun item(
    id: String,
    series: String?,
    played: String?,
    type: ItemType = ItemType.EPISODE,
) = BaseItemDto(
    id = id,
    name = id,
    type = type,
    seriesId = series,
    userData = UserItemDataDto(lastPlayedDate = played),
)

class ContinueWatchingMergerTest {
    /**
     * The live row on 2026-10-10: The 100 binged all evening (S4E10 finished
     * 20:53), its next episode unstarted and undated; a YouTube video stopped
     * part-way the night before. The binged show must lead.
     */
    @Test
    fun undatedNextUpTakesItsSeriesLastWatchedDate() {
        val resume =
            listOf(
                item("exploring-video", "exploring", "2026-10-10T02:03:13Z"),
                item("gcn-video", "gcn", "2026-10-09T19:56:00Z"),
            )
        val nextUp =
            listOf(
                item("the100-s4e11", "the100", null),
                item("gcn-video", "gcn", "2026-10-09T19:56:00Z"),
                item("insidejob-e2", "insidejob", null),
            )
        val recent = listOf(item("the100-s4e10", "the100", "2026-10-10T20:53:32.3531451Z"))

        val row = ContinueWatchingMerger.merge(resume, nextUp, ContinueWatchingMerger.seriesLastPlayed(recent))

        assertEquals(listOf("the100-s4e11", "exploring-video", "gcn-video", "insidejob-e2"), row.map { it.id })
    }

    /** A movie paused just now used to sit behind every Next Up show. */
    @Test
    fun recentlyPausedMovieLeadsOlderNextUp() {
        val resume = listOf(item("movie", null, "2026-10-10T21:00:00Z", ItemType.MOVIE))
        val nextUp =
            listOf(
                item("mash-e24", "mash", "2026-09-22T03:52:12Z"),
                item("ted-s4e1", "ted", null),
            )

        val row = ContinueWatchingMerger.merge(resume, nextUp)

        assertEquals(listOf("movie", "mash-e24", "ted-s4e1"), row.map { it.id })
    }

    /** A just-stopped episode leads and isn't replaced by its undated next episode. */
    @Test
    fun justStoppedEpisodeIsNotReplacedByAnUndatedNextUp() {
        val resume = listOf(item("syd-ride", "syd", "2026-10-07T18:52:58.97Z"))
        val nextUp =
            listOf(
                item("insidejob-clone", "insidejob", null),
                item("syd-gravel", "syd", null),
            )

        val row = ContinueWatchingMerger.merge(resume, nextUp)

        assertEquals("syd-ride", row.first().id)
        assertEquals(false, row.any { it.id == "syd-gravel" })
    }

    @Test
    fun foldersFromResumeNeverReachTheRow() {
        val resume =
            listOf(
                item("season-1", null, "2026-09-23T20:00:00Z", ItemType.SEASON),
                item("ep-1", "s", "2026-09-23T19:00:00Z"),
                item("series-1", null, "2026-09-23T18:00:00Z", ItemType.SERIES),
            )

        assertEquals(listOf("ep-1"), ContinueWatchingMerger.merge(resume, emptyList()).map { it.id })
    }

    @Test
    fun seriesLastPlayedKeepsTheNewestPerSeries() {
        val dates =
            ContinueWatchingMerger.seriesLastPlayed(
                listOf(
                    item("a1", "a", "2026-10-01T00:00:00Z"),
                    item("a2", "a", "2026-10-02T00:00:00Z"),
                    item("b1", null, "2026-10-03T00:00:00Z"),
                    item("c1", "c", null),
                ),
            )

        assertEquals(setOf("a"), dates.keys)
        assertEquals(ContinueWatchingMerger.parseDate("2026-10-02T00:00:00Z"), dates["a"])
    }

    /**
     * The live row on 2026-10-12: The 100's next episode had been opened weeks ago,
     * so it carried that stale date while the season before it was binged. It ranks
     * by its own date or its series' last watch, whichever is newer.
     */
    @Test
    fun nextUpWithAStaleDateStillRanksByItsSeriesLastWatch() {
        val resume =
            listOf(
                item("gcn-video", "gcn", "2026-10-12T01:12:39Z"),
                item("exploring-video", "exploring", "2026-10-10T02:03:13Z"),
                item("sabrina-e2", "sabrina", "2026-10-01T17:35:26Z"),
                item("the100-s1e13", "the100", "2026-09-22T01:32:59Z"),
            )
        val nextUp =
            listOf(
                item("the100-s5e1", "the100", "2026-09-22T02:11:50Z"),
                item("gcn-video", "gcn", "2026-10-12T01:12:39Z"),
                item("darkmatter-s2e5", "darkmatter", "2026-10-02T18:13:24Z"),
            )
        val lastWatched =
            mapOf(
                "the100" to ContinueWatchingMerger.parseDate("2026-10-12T05:41:44Z")!!,
                "gcn" to ContinueWatchingMerger.parseDate("2026-10-12T01:05:36Z")!!,
            )

        val row = ContinueWatchingMerger.merge(resume, nextUp, lastWatched)

        assertEquals(
            listOf("the100-s5e1", "gcn-video", "exploring-video", "darkmatter-s2e5", "sabrina-e2"),
            row.map { it.id },
        )
    }
}
