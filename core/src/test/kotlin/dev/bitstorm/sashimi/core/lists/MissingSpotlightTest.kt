package dev.bitstorm.sashimi.core.lists

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Watchlist's Spotlight for a title the server doesn't have (watchlist v2
 * §3/§6): the provider's backdrop from `/Watchlist/Details`, else its poster,
 * dimmed, as Apple and Roku draw it.
 */
class MissingSpotlightTest {
    private val poster = "https://image.tmdb.org/t/p/w342/p.jpg"
    private val backdrop = "https://image.tmdb.org/t/p/original/b.jpg"

    private fun entry(
        id: String = "w1",
        type: String = Watchlist.MOVIE,
    ) = WatchlistEntry(
        id = id,
        type = type,
        title = "Dune",
        year = 2021,
        posterUrl = poster,
        tmdbId = "438631",
        addedAt = "2026-11-10T00:00:00Z",
    )

    private fun details(
        backdropUrl: String? = backdrop,
        type: String = Watchlist.MOVIE,
    ) = WatchlistTitleDetails(
        type = type,
        title = "Dune",
        year = 2021,
        overview = "Paul Atreides.",
        posterUrl = "https://image.tmdb.org/t/p/original/abc.jpg",
        backdropUrl = backdropUrl,
        runtimeMinutes = 155,
        seasonCount = 2,
        officialRating = "PG-13",
        genres = listOf("Science Fiction", "Adventure", "Drama"),
    )

    @Test
    fun `the provider's backdrop fills the spotlight, not dimmed`() {
        val s = ListSpotlight.missing(entry(), details())
        assertEquals(backdrop, s.backdrop)
        assertFalse(s.dimmed)
    }

    @Test
    fun `without a backdrop the poster stands in, dimmed`() {
        for (d in listOf(null, details(backdropUrl = null), details(backdropUrl = " "))) {
            val s = ListSpotlight.missing(entry(), d)
            assertNull(s.backdrop)
            assertEquals(poster, s.poster)
            assertTrue(s.dimmed)
        }
    }

    @Test
    fun `the entry's own poster wins, the provider's fills a gap`() {
        assertEquals(poster, ListSpotlight.missing(entry(), details()).poster)
        val bare = entry().copy(posterUrl = null)
        assertEquals("https://image.tmdb.org/t/p/original/abc.jpg", ListSpotlight.missing(bare, details(backdropUrl = null)).poster)
    }

    @Test
    fun `the details fill the line and overview`() {
        val movie = ListSpotlight.missing(entry(), details()).info
        assertEquals("Dune", movie.title)
        assertEquals(listOf("2021", "2h 35m", "PG-13", "Science Fiction, Adventure"), movie.facts)
        assertEquals("Paul Atreides.", movie.overview)
        val show = ListSpotlight.missing(entry(type = Watchlist.SERIES), details(type = Watchlist.SERIES)).info
        assertEquals(listOf("2021", "2 seasons", "PG-13", "Science Fiction, Adventure"), show.facts)
    }

    @Test
    fun `until the details come the entry speaks for itself`() {
        assertEquals(ListSpotlight.info(entry()), ListSpotlight.missing(entry(), null).info)
    }

    @Test
    fun `details are asked once per title, a 404 is kept and a failure is not`() =
        runBlocking {
            var calls = 0
            var fail = true
            val cache =
                MissingTitleDetails { ref ->
                    calls++
                    if (fail) error("offline")
                    if (ref.tmdbId == "438631") details() else null
                }
            assertNull(cache.load(entry()))
            assertFalse(cache.isKnown("w1"))
            fail = false
            assertEquals(backdrop, cache.load(entry())?.backdropUrl)
            assertEquals(backdrop, cache.load(entry())?.backdropUrl)
            assertTrue(cache.isKnown("w1"))
            val unknown = entry(id = "w2").copy(tmdbId = "1")
            assertNull(cache.load(unknown))
            assertNull(cache.load(unknown))
            assertTrue("no provider knows it: not asked again", cache.isKnown("w2"))
            assertEquals(3, calls)
        }
}
