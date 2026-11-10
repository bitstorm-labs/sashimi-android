package dev.bitstorm.sashimi.core.lists

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.model.UserItemDataDto
import dev.bitstorm.sashimi.core.shuffle.TvShuffleSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Spotlight list page's rules (#244, list-layouts mockup option A). */
class ListSpotlightTest {
    private val ticksPerMinute = 600_000_000L

    @Test
    fun `a movie reads year, runtime, rating and genres`() {
        val movie =
            BaseItemDto(
                id = "m",
                name = "The Right Stuff",
                type = ItemType.MOVIE,
                productionYear = 1983,
                runTimeTicks = 193 * ticksPerMinute,
                officialRating = "PG",
                genres = listOf("Drama", "History", "Adventure"),
                overview = "The Mercury 7.",
            )
        val info = ListSpotlight.info(movie)
        assertEquals("The Right Stuff", info.title)
        assertEquals(listOf("1983", "3h 13m", "PG", "Drama, History"), info.facts)
        assertEquals("The Mercury 7.", info.overview)
    }

    @Test
    fun `a show reads its number of seasons, not a runtime`() {
        val show =
            BaseItemDto(
                id = "s",
                name = "Manifest",
                type = ItemType.SERIES,
                productionYear = 2018,
                childCount = 4,
                runTimeTicks =
                    43 * ticksPerMinute,
            )
        assertEquals(listOf("2018", "4 seasons"), ListSpotlight.info(show).facts)
        assertEquals(listOf("1 season"), ListSpotlight.info(show.copy(productionYear = null, childCount = 1)).facts)
    }

    @Test
    fun `an episode card is titled by its show, with the episode in the facts`() {
        val ep =
            BaseItemDto(
                id = "e",
                name = "Pilot",
                type = ItemType.EPISODE,
                seriesName = "Mayday",
                parentIndexNumber = 2,
                indexNumber = 4,
                runTimeTicks = 45 * ticksPerMinute,
            )
        val info = ListSpotlight.info(ep)
        assertEquals("Mayday", info.title)
        assertEquals(listOf("S2:E4 · Pilot", "45 min"), info.facts)
    }

    @Test
    fun `fields the server lacks are left out, and a short runtime reads in minutes`() {
        val bare =
            BaseItemDto(
                id = "x",
                name = "X",
                type = ItemType.MOVIE,
                runTimeTicks = 50 * ticksPerMinute,
                genres = emptyList(),
                officialRating = " ",
            )
        assertEquals(listOf("50 min"), ListSpotlight.info(bare).facts)
        assertNull(ListSpotlight.info(bare.copy(overview = "  ")).overview)
    }

    @Test
    fun `a Watchlist title not on the server reads what the entry has`() {
        val entry = WatchlistEntry(id = "w", type = "Series", title = "Sinners", year = 2025, addedAt = "2026-11-10T00:00:00Z")
        val info = ListSpotlight.info(entry)
        assertEquals("Sinners", info.title)
        assertEquals(listOf("2025", "Show"), info.facts)
        assertNull(info.overview)
        assertEquals(listOf("Movie"), ListSpotlight.info(entry.copy(type = "Movie", year = null)).facts)
    }

    @Test
    fun `the spotlight keeps its title while it stays on the list, else the first`() {
        assertEquals("b", ListSpotlight.selected(listOf("a", "b", "c"), "b"))
        assertEquals("a", ListSpotlight.selected(listOf("a", "c"), "b"))
        assertEquals("a", ListSpotlight.selected(listOf("a", "c"), null))
        assertNull(ListSpotlight.selected(emptyList(), "b"))
    }

    private class FakeSource(
        val nextUp: BaseItemDto? = null,
        val all: List<BaseItemDto> = emptyList(),
    ) : TvShuffleSource {
        override suspend fun randomItem(
            parentId: String,
            includeTypes: List<ItemType>,
        ): BaseItemDto? = null

        override suspend fun nextUpEpisode(seriesId: String) = nextUp

        override suspend fun episodes(seriesId: String) = all
    }

    private fun ep(
        id: String,
        season: Int,
        number: Int,
        played: Boolean = false,
    ) = BaseItemDto(
        id = id,
        type = ItemType.EPISODE,
        seriesId = "s",
        parentIndexNumber = season,
        indexNumber = number,
        userData = UserItemDataDto(played = played),
    )

    @Test
    fun `Play starts a movie or episode itself and a show at its next episode`() =
        runBlocking {
            val movie = BaseItemDto(id = "m", type = ItemType.MOVIE)
            assertEquals("m", ListSpotlight.playTarget(movie, FakeSource()))
            assertEquals("e9", ListSpotlight.playTarget(ep("e9", 3, 1), FakeSource()))
            val show = BaseItemDto(id = "s", type = ItemType.SERIES)
            assertEquals("n", ListSpotlight.playTarget(show, FakeSource(nextUp = ep("n", 2, 2))))
            assertEquals(
                "b",
                ListSpotlight.playTarget(show, FakeSource(all = listOf(ep("sp", 0, 1), ep("a", 1, 1, played = true), ep("b", 1, 2)))),
            )
            assertNull(ListSpotlight.playTarget(show, FakeSource()))
        }

    @Test
    fun `details are fetched once per title and a failure is not cached`() =
        runBlocking {
            var calls = 0
            var fail = true
            val details =
                SpotlightDetails { id ->
                    calls++
                    if (fail) error("offline")
                    BaseItemDto(id = id, overview = "full")
                }
            assertNull(details.load("a"))
            fail = false
            assertEquals("full", details.load("a")?.overview)
            assertEquals("full", details.load("a")?.overview)
            assertEquals("full", details.cached("a")?.overview)
            assertEquals(2, calls)
        }
}
