package dev.bitstorm.sashimi.core.home

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.model.UserItemDataDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun ep(
    id: String,
    played: Boolean?,
    season: Int = 1,
) = BaseItemDto(
    id = id,
    name = id,
    type = ItemType.EPISODE,
    userData = played?.let { UserItemDataDto(played = it) },
    parentIndexNumber = season,
)

private fun season(
    index: Int = 1,
    played: Boolean? = null,
) = BaseItemDto(
    id = "s$index",
    name = "Season $index",
    type = ItemType.SEASON,
    indexNumber = index,
    userData = played?.let { UserItemDataDto(played = it) },
)

class SeasonWatchedActionTest {
    @Test
    fun `every episode played offers Unwatched`() {
        val eps = listOf(ep("a", true), ep("b", true), ep("c", true))
        assertEquals(SeasonWatchedAction.MARK_UNWATCHED, SeasonWatchedAction.forSeason(season(), eps))
    }

    @Test
    fun `no episode played offers Watched`() {
        val eps = listOf(ep("a", false), ep("b", false))
        assertEquals(SeasonWatchedAction.MARK_WATCHED, SeasonWatchedAction.forSeason(season(), eps))
    }

    @Test
    fun `a partly watched season offers Watched`() {
        val eps = listOf(ep("a", true), ep("b", false), ep("c", true))
        assertEquals(SeasonWatchedAction.MARK_WATCHED, SeasonWatchedAction.forSeason(season(), eps))
    }

    @Test
    fun `an episode with no user data counts as unplayed`() {
        val eps = listOf(ep("a", true), ep("b", null))
        assertEquals(SeasonWatchedAction.MARK_WATCHED, SeasonWatchedAction.forSeason(season(), eps))
    }

    @Test
    fun `episodes win over a stale season flag`() {
        val eps = listOf(ep("a", true), ep("b", false))
        assertEquals(SeasonWatchedAction.MARK_WATCHED, SeasonWatchedAction.forSeason(season(played = true), eps))
    }

    @Test
    fun `no episodes loaded falls back to the season played flag`() {
        assertEquals(SeasonWatchedAction.MARK_UNWATCHED, SeasonWatchedAction.forSeason(season(played = true), emptyList()))
        assertEquals(SeasonWatchedAction.MARK_WATCHED, SeasonWatchedAction.forSeason(season(played = false), emptyList()))
        assertEquals(SeasonWatchedAction.MARK_WATCHED, SeasonWatchedAction.forSeason(null, emptyList()))
    }

    @Test
    fun `specials decide the same way`() {
        val eps = listOf(ep("sp1", true, season = 0), ep("sp2", true, season = 0))
        assertEquals(SeasonWatchedAction.MARK_UNWATCHED, SeasonWatchedAction.forSeason(season(index = 0), eps))
    }

    @Test
    fun `labels and played values match the action`() {
        assertEquals("Mark Season Watched", SeasonWatchedAction.MARK_WATCHED.label)
        assertEquals("Mark Season Unwatched", SeasonWatchedAction.MARK_UNWATCHED.label)
        assertTrue(SeasonWatchedAction.MARK_WATCHED.markPlayed)
        assertFalse(SeasonWatchedAction.MARK_UNWATCHED.markPlayed)
    }
}
