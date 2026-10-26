package dev.bitstorm.sashimi.core.home

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.model.UserItemDataDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeListStateTest {
    private fun ep(
        id: String,
        played: Boolean = false,
        position: Long = 0,
    ) = BaseItemDto(
        id = id,
        name = id,
        type = ItemType.EPISODE,
        userData = UserItemDataDto(played = played, playbackPositionTicks = position),
    )

    @Test
    fun `a reload refetches the season on screen, not only on first open`() {
        assertEquals("season4", EpisodeListState.seasonToLoad(selectedSeasonId = "season4", defaultSeasonId = "season1"))
        assertEquals("season1", EpisodeListState.seasonToLoad(selectedSeasonId = null, defaultSeasonId = "season1"))
    }

    @Test
    fun `marking an episode watched updates its row and clears its progress`() {
        val list = listOf(ep("e1"), ep("e2", position = 500))
        val updated = EpisodeListState.withPlayed(list, "e2", played = true)
        assertFalse(updated[0].userData?.played == true)
        assertTrue(updated[1].userData?.played == true)
        assertEquals(0L, updated[1].userData?.playbackPositionTicks)
    }

    @Test
    fun `marking unwatched keeps the resume position and the label follows the list`() {
        val list = listOf(ep("e1", played = true), ep("e2", played = true, position = 0))
        assertEquals(SeasonWatchedAction.MARK_UNWATCHED, SeasonWatchedAction.forSeason(null, list))
        val updated = EpisodeListState.withPlayed(list, "e1", played = false)
        assertFalse(updated[0].userData?.played == true)
        assertEquals(SeasonWatchedAction.MARK_WATCHED, SeasonWatchedAction.forSeason(null, updated))
    }

    @Test
    fun `an episode with no user data gets some`() {
        val updated = EpisodeListState.withPlayed(listOf(BaseItemDto(id = "x", name = "x")), "x", played = true)
        assertTrue(updated.single().userData?.played == true)
    }
}
