package dev.bitstorm.sashimi.core.downloads

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.model.UserItemDataDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkDownloadPlannerTest {
    private fun ep(
        id: String,
        season: Int,
        played: Boolean = false,
    ) = BaseItemDto(
        id = id,
        name = id,
        type = ItemType.EPISODE,
        parentIndexNumber = season,
        userData = UserItemDataDto(played = played),
    )

    private fun row(
        id: String,
        status: DownloadStatus,
        serverId: String = "a",
    ) = DownloadedItemEntity(itemId = id, name = id, status = status.wireName, serverId = serverId)

    private val series =
        listOf(
            ep("sp1", season = 0),
            ep("s1e1", season = 1, played = true),
            ep("s1e2", season = 1),
            ep("s2e1", season = 2),
            ep("s2e2", season = 2, played = true),
        )

    private fun ids(
        action: BulkDownloadAction,
        episodes: List<BaseItemDto> = series,
        downloads: List<DownloadedItemEntity> = emptyList(),
        serverId: String? = "a",
    ) = BulkDownloadPlanner.select(action, episodes, downloads, serverId).map { it.id }

    @Test
    fun `labels match the Apple client`() {
        assertEquals(
            listOf("Download Season", "Download Unwatched in Season", "Download Unwatched", "Download Series"),
            BulkDownloadAction.entries.map { it.label },
        )
    }

    @Test
    fun `download unwatched takes every unwatched regular episode across seasons`() {
        assertEquals(listOf("s1e2", "s2e1"), ids(BulkDownloadAction.SERIES_UNWATCHED))
    }

    @Test
    fun `download series takes every episode, specials included`() {
        assertEquals(listOf("sp1", "s1e1", "s1e2", "s2e1", "s2e2"), ids(BulkDownloadAction.SERIES))
    }

    @Test
    fun `season actions take the season, or its unwatched episodes`() {
        val season1 = series.filter { it.parentIndexNumber == 1 }
        assertEquals(listOf("s1e1", "s1e2"), ids(BulkDownloadAction.SEASON, season1))
        assertEquals(listOf("s1e2"), ids(BulkDownloadAction.SEASON_UNWATCHED, season1))
    }

    @Test
    fun `unwatched in the specials season keeps its specials`() {
        val specials = listOf(ep("sp1", season = 0), ep("sp2", season = 0, played = true))
        assertEquals(listOf("sp1"), ids(BulkDownloadAction.SEASON_UNWATCHED, specials))
    }

    @Test
    fun `downloaded, queued and downloading episodes are skipped, failed ones are not`() {
        val downloads =
            listOf(
                row("sp1", DownloadStatus.COMPLETED),
                row("s1e1", DownloadStatus.QUEUED),
                row("s1e2", DownloadStatus.DOWNLOADING),
                row("s2e1", DownloadStatus.FAILED),
            )
        assertEquals(listOf("s2e1", "s2e2"), ids(BulkDownloadAction.SERIES, downloads = downloads))
    }

    @Test
    fun `a download of the same item from another server does not count`() {
        val downloads = listOf(row("s1e2", DownloadStatus.COMPLETED, serverId = "b"))
        assertEquals(listOf("s1e2", "s2e1"), ids(BulkDownloadAction.SERIES_UNWATCHED, downloads = downloads))
    }

    @Test
    fun `an episode listed twice is queued once`() {
        assertEquals(listOf("x"), ids(BulkDownloadAction.SERIES, listOf(ep("x", 1), ep("x", 1))))
    }

    @Test
    fun `more than ten episodes asks first`() {
        assertFalse(BulkDownloadPlanner.needsConfirmation(10))
        assertTrue(BulkDownloadPlanner.needsConfirmation(11))
        assertEquals("Download 24 episodes?", BulkDownloadPlanner.confirmationTitle(24))
    }
}

class DownloadActivityTest {
    private fun row(
        id: String,
        status: DownloadStatus,
        progress: Double = 0.0,
    ) = DownloadedItemEntity(itemId = id, name = id, status = status.wireName, progress = progress)

    @Test
    fun `hidden when nothing is queued or running`() {
        assertNull(DownloadActivity.of(emptyList()))
        assertNull(DownloadActivity.of(listOf(row("a", DownloadStatus.COMPLETED, 1.0), row("b", DownloadStatus.FAILED))))
    }

    @Test
    fun `counts active items and averages their progress`() {
        val activity =
            DownloadActivity.of(
                listOf(
                    row("a", DownloadStatus.DOWNLOADING, 0.5),
                    row("b", DownloadStatus.DOWNLOADING, 1.0),
                    row("c", DownloadStatus.QUEUED),
                    row("d", DownloadStatus.PREPARING),
                    row("e", DownloadStatus.COMPLETED, 1.0),
                ),
            )!!
        assertEquals(4, activity.activeCount)
        assertEquals(0.375f, activity.progress!!, 0.0001f)
    }

    @Test
    fun `unknown-size streams are left out of the average`() {
        val activity =
            DownloadActivity.of(
                listOf(
                    row("a", DownloadStatus.DOWNLOADING, DownloadManager.PROGRESS_UNKNOWN),
                    row("b", DownloadStatus.DOWNLOADING, 0.4),
                ),
            )!!
        assertEquals(2, activity.activeCount)
        assertEquals(0.4f, activity.progress!!, 0.0001f)
    }

    @Test
    fun `indeterminate when every active item is of unknown size`() {
        val activity = DownloadActivity.of(listOf(row("a", DownloadStatus.DOWNLOADING, DownloadManager.PROGRESS_UNKNOWN)))!!
        assertEquals(1, activity.activeCount)
        assertNull(activity.progress)
    }
}
