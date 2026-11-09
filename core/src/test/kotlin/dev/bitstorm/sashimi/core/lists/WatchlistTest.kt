package dev.bitstorm.sashimi.core.lists

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.seerr.SeerrBadge
import dev.bitstorm.sashimi.core.seerr.SeerrMediaKind
import dev.bitstorm.sashimi.core.seerr.SeerrTitleDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchlistTest {
    private fun item(
        type: ItemType?,
        ids: Map<String, String>? = null,
        id: String = "abc123def",
    ) = BaseItemDto(id = id, name = "Heat", type = type, productionYear = 1995, providerIds = ids)

    private fun entry(
        itemId: String? = null,
        tmdb: String? = null,
        imdb: String? = null,
        tvdb: String? = null,
        type: String = "Movie",
    ) = WatchlistEntry(
        id = "e1",
        type = type,
        title = "Heat",
        tmdbId = tmdb,
        imdbId = imdb,
        tvdbId = tvdb,
        addedAt = "2026-11-09T00:00:00Z",
        itemId = itemId,
    )

    @Test
    fun `movies and series with any external id are eligible`() {
        assertTrue(Watchlist.eligible(item(ItemType.MOVIE, mapOf("Tmdb" to "949"))))
        assertTrue(Watchlist.eligible(item(ItemType.SERIES, mapOf("Tvdb" to "81189"))))
        assertTrue(Watchlist.eligible(item(ItemType.MOVIE, mapOf("Imdb" to "tt0113277"))))
    }

    @Test
    fun `episodes and items without tmdb tvdb or imdb are not eligible`() {
        assertFalse(Watchlist.eligible(item(ItemType.EPISODE, mapOf("Tmdb" to "1"))))
        assertFalse(Watchlist.eligible(item(ItemType.MOVIE, mapOf("YouTube" to "x"))))
        assertFalse(Watchlist.eligible(item(ItemType.MOVIE, mapOf("Tmdb" to ""))))
        assertFalse(Watchlist.eligible(item(ItemType.MOVIE, null)))
    }

    @Test
    fun `provider id keys match in any case`() {
        assertTrue(Watchlist.eligible(item(ItemType.MOVIE, mapOf("tmdb" to "949"))))
        assertEquals("tt1", Watchlist.newEntry(item(ItemType.MOVIE, mapOf("IMDB" to "tt1")))?.imdbId)
    }

    @Test
    fun `entry from a library item carries its ids and no poster`() {
        val e = Watchlist.newEntry(item(ItemType.SERIES, mapOf("Tmdb" to "1396", "Tvdb" to "81189", "Imdb" to "tt0903747")))!!
        assertEquals(NewWatchlistEntry("Series", "Heat", 1995, null, "1396", "81189", "tt0903747"), e)
        assertNull(Watchlist.newEntry(item(ItemType.EPISODE, mapOf("Tmdb" to "1"))))
    }

    @Test
    fun `entry from seerr details carries tmdb imdb tvdb and the tmdb poster`() {
        val d =
            SeerrTitleDetails(
                tmdbId = 1396,
                kind = SeerrMediaKind.TV,
                title = "Breaking Bad",
                date = "2008-01-20",
                posterPath = "/p.jpg",
                imdbId = "tt0903747",
                tvdbId = 81189,
            )
        assertEquals(
            NewWatchlistEntry("Series", "Breaking Bad", 2008, "https://image.tmdb.org/t/p/w500/p.jpg", "1396", "81189", "tt0903747"),
            Watchlist.newEntry(d),
        )
        assertEquals("Movie", Watchlist.newEntry(d.copy(kind = SeerrMediaKind.MOVIE)).type)
    }

    @Test
    fun `already on matches ids in any case and dash form`() {
        val byItem = WatchlistView(entries = listOf(entry(itemId = "abc123def")))
        assertTrue(Watchlist.alreadyOn(byItem, item(ItemType.MOVIE, mapOf("Tmdb" to "9"), id = "ABC1-23DE-F")))
        val byImdb = WatchlistView(entries = listOf(entry(imdb = "TT1")))
        assertTrue(Watchlist.alreadyOn(byImdb, item(ItemType.MOVIE, mapOf("Imdb" to "tt1"), id = "other")))
        val disjoint = WatchlistView(entries = listOf(entry(tmdb = "1", imdb = "tt1")))
        assertFalse(Watchlist.alreadyOn(disjoint, item(ItemType.MOVIE, mapOf("Tmdb" to "2", "Imdb" to "tt2"), id = "other")))
    }

    @Test
    fun `matches needs a shared non-null id`() {
        assertTrue(Watchlist.matches(entry(tmdb = "1"), NewWatchlistEntry("Movie", "x", tmdbId = "1", imdbId = "tt9")))
        assertFalse(Watchlist.matches(entry(tmdb = "1"), NewWatchlistEntry("Movie", "x", imdbId = "tt1")))
        assertFalse(Watchlist.matches(entry(), NewWatchlistEntry("Movie", "x")))
    }

    @Test
    fun `on-server entry has no tag`() {
        assertNull(Watchlist.tag(entry(itemId = "a"), SeerrBadge.PENDING))
        assertNull(Watchlist.tag(entry(itemId = "a"), null))
    }

    @Test
    fun `pending or coming request reads Requested`() {
        assertEquals(WatchlistTag.REQUESTED, Watchlist.tag(entry(), SeerrBadge.PENDING))
        assertEquals(WatchlistTag.REQUESTED, Watchlist.tag(entry(), SeerrBadge.COMING))
        assertEquals("Requested", WatchlistTag.REQUESTED.label)
    }

    @Test
    fun `declined, request, or no seerr session reads Not on server`() {
        assertEquals(WatchlistTag.NOT_ON_SERVER, Watchlist.tag(entry(), SeerrBadge.DECLINED))
        assertEquals(WatchlistTag.NOT_ON_SERVER, Watchlist.tag(entry(), SeerrBadge.REQUEST))
        assertEquals(WatchlistTag.NOT_ON_SERVER, Watchlist.tag(entry(), null))
        assertEquals("Not on server", WatchlistTag.NOT_ON_SERVER.label)
    }

    @Test
    fun `tile without tmdb id has no seerr target`() {
        assertNull(Watchlist.seerrTarget(entry(imdb = "tt1")))
        assertNull(Watchlist.seerrTarget(entry(tmdb = "abc")))
        val movie = Watchlist.seerrTarget(entry(tmdb = "949"))!!
        assertEquals(SeerrMediaKind.MOVIE, movie.kind)
        assertEquals(949, movie.tmdbId)
    }

    @Test
    fun `series entry targets seerr tv`() {
        assertEquals(SeerrMediaKind.TV, Watchlist.seerrTarget(entry(tmdb = "1396", type = "Series"))?.kind)
    }

    @Test
    fun `picker hides the watchlist for an episode`() {
        val view = WatchlistView()
        assertFalse(Watchlist.pickerShowsWatchlist(item(ItemType.EPISODE, mapOf("Tmdb" to "1")), view))
        assertTrue(Watchlist.pickerShowsWatchlist(item(ItemType.MOVIE, mapOf("Tmdb" to "1")), view))
    }

    @Test
    fun `picker hides the watchlist without the plugin`() {
        assertFalse(Watchlist.pickerShowsWatchlist(item(ItemType.MOVIE, mapOf("Tmdb" to "1")), null))
    }

    @Test
    fun `seerr picker offers only the watchlist`() {
        assertTrue(Watchlist.pickerShowsWatchlist(null, WatchlistView()))
        assertFalse(Watchlist.pickerShowsWatchlist(null, null))
        assertFalse(Watchlist.pickerShowsLists(isSeerr = true))
        assertTrue(Watchlist.pickerShowsLists(isSeerr = false))
    }
}
