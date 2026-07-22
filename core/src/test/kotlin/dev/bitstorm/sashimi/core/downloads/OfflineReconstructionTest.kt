package dev.bitstorm.sashimi.core.downloads

import dev.bitstorm.sashimi.core.model.ItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineReconstructionTest {
    private fun episode(
        id: String,
        season: Int,
        ep: Int,
        seriesId: String = "series1",
        seriesName: String = "The Show",
        status: DownloadStatus = DownloadStatus.COMPLETED,
        localTicks: Long = 0,
    ) = DownloadedItemEntity(
        itemId = id,
        name = "Ep $ep",
        seriesName = seriesName,
        seriesId = seriesId,
        seasonNumber = season,
        episodeNumber = ep,
        itemType = ItemType.EPISODE.wireName,
        status = status.wireName,
        localPositionTicks = localTicks,
    )

    private fun movie(
        id: String,
        status: DownloadStatus = DownloadStatus.COMPLETED,
    ) = DownloadedItemEntity(itemId = id, name = id, itemType = ItemType.MOVIE.wireName, status = status.wireName)

    @Test
    fun `synthetic seasons cover distinct season numbers ascending`() {
        val eps = listOf(episode("a", 2, 1), episode("b", 1, 3), episode("c", 1, 1), episode("d", 2, 2))
        val seasons = OfflineReconstruction.syntheticSeasons(eps, "series1")
        assertEquals(listOf("Season 1", "Season 2"), seasons.map { it.name })
        assertEquals(listOf(1, 2), seasons.map { it.indexNumber })
        assertEquals("offline-season-1", seasons.first().id)
        assertTrue(seasons.all { it.type == ItemType.SEASON && it.seriesId == "series1" })
    }

    @Test
    fun `series groups sort episodes by season then episode`() {
        val items = listOf(episode("a", 1, 2), episode("b", 1, 1), episode("c", 2, 1), movie("m"))
        val groups = OfflineReconstruction.seriesGroups(items)
        assertEquals(1, groups.size)
        assertEquals(listOf("b", "a", "c"), groups.first().episodes.map { it.itemId })
        assertEquals("b", groups.first().representative.itemId)
    }

    @Test
    fun `only completed items are browsable offline`() {
        val items =
            listOf(episode("a", 1, 1), episode("b", 1, 2, status = DownloadStatus.DOWNLOADING), movie("m", status = DownloadStatus.QUEUED))
        assertEquals(listOf("a"), OfflineReconstruction.completed(items).map { it.itemId })
        assertTrue(OfflineReconstruction.movies(items).isEmpty())
    }

    @Test
    fun `continue watching only includes items with local progress`() {
        val items = listOf(episode("a", 1, 1, localTicks = 500), episode("b", 1, 2, localTicks = 0))
        assertEquals(listOf("a"), OfflineReconstruction.continueWatching(items).map { it.itemId })
    }

    @Test
    fun `episodes for series matches by id or name fallback`() {
        val items =
            listOf(
                episode("a", 1, 1, seriesId = "series1", seriesName = "The Show"),
                episode("b", 1, 2, seriesId = "other", seriesName = "The Show"),
                episode("c", 1, 3, seriesId = "series1", seriesName = "Different"),
            )
        val matched = OfflineReconstruction.episodesForSeries(items, "series1", "The Show")
        assertEquals(setOf("a", "b", "c"), matched.map { it.itemId }.toSet())
    }

    @Test
    fun `asBaseItemDto carries local resume position when set`() {
        val dto = OfflineReconstruction.asBaseItemDto(episode("a", 3, 4, localTicks = 12345))
        assertEquals(ItemType.EPISODE, dto.type)
        assertEquals(3, dto.parentIndexNumber)
        assertEquals(4, dto.indexNumber)
        assertEquals(12345L, dto.userData?.playbackPositionTicks)
    }

    @Test
    fun `asBaseItemDto omits userData when no local progress`() {
        val dto = OfflineReconstruction.asBaseItemDto(episode("a", 1, 1, localTicks = 0))
        assertEquals(null, dto.userData)
    }
}
