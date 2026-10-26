package dev.bitstorm.sashimi.core.downloads

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.model.UserItemDataDto
import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeDownloadPlannerTest {
    private fun ep(
        id: String,
        season: Int,
        number: Int = 1,
        played: Boolean = false,
    ) = BaseItemDto(
        id = id,
        name = id,
        type = ItemType.EPISODE,
        seasonId = "season$season",
        parentIndexNumber = season,
        indexNumber = number,
        userData = UserItemDataDto(played = played),
    )

    private fun row(
        id: String,
        status: DownloadStatus,
        serverId: String = "a",
    ) = DownloadedItemEntity(itemId = id, name = id, status = status.wireName, serverId = serverId)

    // Airing order as /Shows/{id}/Episodes returns it; a special filed within season 2.
    private val series =
        listOf(
            ep("sp1", season = 0),
            ep("s1e1", season = 1, number = 1, played = true),
            ep("s1e2", season = 1, number = 2),
            ep("s1e3", season = 1, number = 3),
            ep("s2e1", season = 2, number = 1),
            ep("sp2", season = 0, number = 2),
            ep("s2e2", season = 2, number = 2, played = true),
            ep("s2e3", season = 2, number = 3),
        )

    private fun next(
        tapped: String,
        count: Int,
        downloads: List<DownloadedItemEntity> = emptyList(),
        serverId: String? = "a",
    ) = EpisodeDownloadPlanner
        .thisAndNext(series.first { it.id == tapped }, series, count, downloads, serverId)
        .map { it.id }

    @Test
    fun `this and the next N crosses into the next season`() {
        assertEquals(listOf("s1e2", "s1e3", "s2e1"), next("s1e2", 2))
    }

    @Test
    fun `specials are never among the next N`() {
        assertEquals(listOf("s2e1", "s2e2", "s2e3"), next("s2e1", 2))
    }

    @Test
    fun `already downloaded or queued episodes in the window are skipped, a failed one is queued again`() {
        val downloads =
            listOf(
                row("s1e3", DownloadStatus.COMPLETED),
                row("s2e1", DownloadStatus.QUEUED),
                row("s2e2", DownloadStatus.FAILED),
                // Another server's copy does not count.
                row("s1e2", DownloadStatus.COMPLETED, serverId = "b"),
            )
        assertEquals(listOf("s1e2", "s2e2"), next("s1e2", 3, downloads))
    }

    @Test
    fun `the window stops at the end of the series`() {
        assertEquals(listOf("s2e2", "s2e3"), next("s2e2", 10))
    }

    @Test
    fun `zero means just this episode`() {
        assertEquals(listOf("s1e2"), next("s1e2", 0))
    }

    @Test
    fun `unwatched in season uses the tapped episode's season only`() {
        val tapped = series.first { it.id == "s2e1" }
        val ids = EpisodeDownloadPlanner.unwatchedInSeason(tapped, series, emptyList(), "a").map { it.id }
        assertEquals(listOf("s2e1", "s2e3"), ids)
    }

    @Test
    fun `unwatched in series skips specials and played episodes`() {
        val ids = EpisodeDownloadPlanner.unwatchedInSeries(series, emptyList(), "a").map { it.id }
        assertEquals(listOf("s1e2", "s1e3", "s2e1", "s2e3"), ids)
    }

    @Test
    fun `labels match the approved mockup`() {
        val tapped = ep("x", season = 2, number = 3).copy(name = "Shooter Ready")
        assertEquals("S2 E3 · Shooter Ready", EpisodeDownloadPlanner.menuTitle(tapped))
        assertEquals("Season 2", EpisodeDownloadPlanner.seasonLabel(tapped))
        assertEquals("Specials", EpisodeDownloadPlanner.seasonLabel(ep("y", season = 0)))
    }
}
