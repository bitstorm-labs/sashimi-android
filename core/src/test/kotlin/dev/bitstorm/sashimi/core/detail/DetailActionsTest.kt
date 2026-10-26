package dev.bitstorm.sashimi.core.detail

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.model.UserItemDataDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DetailActionsTest {
    private val episode =
        BaseItemDto(
            id = "e6",
            type = ItemType.EPISODE,
            seriesId = "show",
            seasonId = "s2",
            parentIndexNumber = 2,
            indexNumber = 6,
        )
    private val movie = BaseItemDto(id = "m", type = ItemType.MOVIE, localTrailerCount = 1)
    private val series = BaseItemDto(id = "show", type = ItemType.SERIES)

    @Test
    fun `episode More menu leads with Go to Series then Go to Season`() {
        assertEquals(
            listOf(
                DetailMenuItem.GO_TO_SERIES,
                DetailMenuItem.GO_TO_SEASON,
                DetailMenuItem.START_OVER,
                DetailMenuItem.FAVORITE,
                DetailMenuItem.FILE_INFO,
                DetailMenuItem.REFRESH_METADATA,
                DetailMenuItem.DELETE,
            ),
            DetailActions.moreMenu(episode, online = true, hasStartOver = true),
        )
    }

    @Test
    fun `Go to Season needs the episode's season id and a connection`() {
        assertFalse(DetailMenuItem.GO_TO_SEASON in DetailActions.moreMenu(episode.copy(seasonId = null), true, false))
        assertFalse(DetailMenuItem.GO_TO_SEASON in DetailActions.moreMenu(episode, online = false, hasStartOver = false))
        assertFalse(DetailMenuItem.GO_TO_SEASON in DetailActions.moreMenu(movie, online = true, hasStartOver = false))
        assertFalse(DetailMenuItem.GO_TO_SEASON in DetailActions.moreMenu(series, online = true, hasStartOver = false))
    }

    @Test
    fun `Go to Season label names the season`() {
        assertEquals("Go to Season 2", DetailActions.goToSeasonLabel(episode))
        assertEquals("Go to Specials", DetailActions.goToSeasonLabel(episode.copy(parentIndexNumber = 0)))
    }

    @Test
    fun `navigation never sits in the main row`() {
        for (item in listOf(episode, movie, series)) {
            val row = DetailActions.mainRow(item, online = true)
            assertEquals(DetailRowButton.PLAY, row.first())
            assertEquals(DetailRowButton.MORE, row.last())
        }
        // The row has no Series / Season / channel buttons at all: only these.
        assertEquals(
            listOf(DetailRowButton.PLAY, DetailRowButton.WATCHED, DetailRowButton.DOWNLOAD, DetailRowButton.MORE),
            DetailActions.mainRow(episode, online = true),
        )
    }

    @Test
    fun `movie More menu keeps trailer and item management`() {
        assertEquals(
            listOf(
                DetailMenuItem.PLAY_TRAILER,
                DetailMenuItem.FAVORITE,
                DetailMenuItem.FILE_INFO,
                DetailMenuItem.REFRESH_METADATA,
                DetailMenuItem.DELETE,
            ),
            DetailActions.moreMenu(movie, online = true, hasStartOver = false),
        )
    }

    @Test
    fun `a season plays its next unwatched episode, not the season item`() {
        val season = BaseItemDto(id = "s2", type = ItemType.SEASON, indexNumber = 2)
        val eps =
            listOf(
                BaseItemDto(id = "e1", type = ItemType.EPISODE, userData = UserItemDataDto(played = true)),
                BaseItemDto(id = "e2", type = ItemType.EPISODE),
            )
        assertEquals("e2", DetailActions.playTargetId(season, nextEpisode = null, seasonEpisodes = eps))
        assertEquals("show-next", DetailActions.playTargetId(series, BaseItemDto(id = "show-next"), emptyList()))
        assertEquals("m", DetailActions.playTargetId(movie, null, emptyList()))
    }
}
