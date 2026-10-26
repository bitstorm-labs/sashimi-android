package dev.bitstorm.sashimi.core.detail

import dev.bitstorm.sashimi.core.home.SeasonWatchedAction
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.model.UserItemDataDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun ep(
    n: Int,
    played: Boolean = false,
    position: Long = 0,
) = BaseItemDto(
    id = "e$n",
    name = "Episode $n",
    type = ItemType.EPISODE,
    parentIndexNumber = 2,
    indexNumber = n,
    userData = UserItemDataDto(played = played, playbackPositionTicks = position),
)

private val season = BaseItemDto(id = "s2", name = "Season 2", type = ItemType.SEASON, indexNumber = 2, seriesId = "show")

class SeasonPageTest {
    @Test
    fun `Play is the first unwatched episode`() {
        val eps = (1..5).map { ep(it, played = true) } + (6..8).map { ep(it) }
        val target = SeasonPage.playTarget(eps)!!
        assertEquals("e6", target.episode.id)
        assertFalse(target.fromBeginning)
        assertEquals("Play S2·E6", target.label)
    }

    @Test
    fun `an unwatched episode with progress resumes`() {
        val eps = listOf(ep(1, played = true), ep(2, position = 1_000), ep(3))
        val target = SeasonPage.playTarget(eps)!!
        assertEquals("e2", target.episode.id)
        assertEquals("Resume S2·E2", target.label)
        assertFalse(target.fromBeginning)
    }

    @Test
    fun `a fully watched season plays E1 from the start`() {
        val eps = (1..3).map { ep(it, played = true) }
        val target = SeasonPage.playTarget(eps)!!
        assertEquals("e1", target.episode.id)
        assertEquals("Play S2·E1", target.label)
        assertTrue(target.fromBeginning)
    }

    @Test
    fun `no episodes, no play target`() {
        assertNull(SeasonPage.playTarget(emptyList()))
    }

    @Test
    fun `subtitle counts episodes and watched`() {
        val eps = (1..5).map { ep(it, played = true) } + (6..8).map { ep(it) }
        assertEquals("Season 2 · 8 episodes · 5 of 8 watched", SeasonPage.subtitle(season, eps))
        assertEquals("Season 2 · 1 episode · 0 of 1 watched", SeasonPage.subtitle(season, listOf(ep(1))))
    }

    @Test
    fun `Mark Season Watched flips to Unwatched once every episode is watched`() {
        val partly = listOf(ep(1, played = true), ep(2))
        assertEquals(SeasonWatchedAction.MARK_WATCHED, SeasonPage.watchedAction(season, partly))
        assertEquals("Mark Season Watched", SeasonPage.watchedAction(season, partly).label)
        val all = listOf(ep(1, played = true), ep(2, played = true))
        assertEquals("Mark Season Unwatched", SeasonPage.watchedAction(season, all).label)
    }

    @Test
    fun `action row is Play, Mark Season, Download Season, Series online`() {
        assertEquals(
            listOf(
                SeasonPageButton.PLAY,
                SeasonPageButton.MARK_SEASON,
                SeasonPageButton.DOWNLOAD_SEASON,
                SeasonPageButton.SERIES,
            ),
            SeasonPage.buttons(online = true),
        )
        assertEquals(listOf(SeasonPageButton.PLAY, SeasonPageButton.SERIES), SeasonPage.buttons(online = false))
    }

    @Test
    fun `tapping the selected season tab opens it, another tab selects it`() {
        assertEquals(SeasonTabTap.OPEN, SeasonPage.onTabTap(seasonId = "s2", selectedSeasonId = "s2"))
        assertEquals(SeasonTabTap.SELECT, SeasonPage.onTabTap(seasonId = "s3", selectedSeasonId = "s2"))
        // Offline seasons are rebuilt from downloads and have no page to open.
        assertEquals(SeasonTabTap.SELECT, SeasonPage.onTabTap("offline-season-2", "offline-season-2"))
        assertTrue(SeasonPage.canOpen("s2"))
        assertFalse(SeasonPage.canOpen("offline-season-2"))
    }
}
