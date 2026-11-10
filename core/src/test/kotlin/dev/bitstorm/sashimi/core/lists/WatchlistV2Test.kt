package dev.bitstorm.sashimi.core.lists

import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.network.JellyfinError
import dev.bitstorm.sashimi.core.seerr.SeerrMediaKind
import dev.bitstorm.sashimi.core.seerr.SeerrTarget
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The 0.3.0 contract's search sample (2026-11-10-watchlist-v2-api.md §2). */
private const val SEARCH_SAMPLE = """{
  "query": "dune",
  "onServer": [
    {"type": "Movie", "title": "Dune", "year": 2021, "tmdbId": "438631", "tvdbId": null, "imdbId": "tt1160419",
     "posterUrl": "https://image.tmdb.org/t/p/original/abc.jpg", "overview": "Paul Atreides...",
     "itemId": "0123456789abcdef0123456789abcdef", "onWatchlist": true, "watchlistEntryId": "fedcba9876543210fedcba9876543210"}
  ],
  "notOnServer": [
    {"type": "Movie", "title": "Dune: Part Three", "year": 2026, "tmdbId": "1170608", "tvdbId": null, "imdbId": null,
     "posterUrl": "https://image.tmdb.org/t/p/original/def.jpg", "overview": "...", "itemId": null,
     "onWatchlist": false, "watchlistEntryId": null, "later": 1}
  ]
}"""

/** §3's sample: fields the provider lacks are absent, not null. */
private const val DETAILS_SAMPLE = """{
  "type": "Movie", "title": "Dune", "year": 2021, "overview": "Paul Atreides...",
  "posterUrl": "https://image.tmdb.org/t/p/original/abc.jpg", "backdropUrl": "https://image.tmdb.org/t/p/original/xyz.jpg",
  "runtimeMinutes": 155, "officialRating": "PG-13", "genres": ["Science Fiction", "Adventure"], "communityRating": 7.8,
  "source": "TMDB", "tmdbId": "438631", "imdbId": "tt1160419",
  "onWatchlist": true, "watchlistEntryId": "fedcba9876543210fedcba9876543210"
}"""

/** Watchlist v2 (design §2–§4) against the plugin 0.3.0 contract. */
class WatchlistV2Test {
    private val stub = StubServer()
    private val client =
        JellyfinClient(deviceId = "test", httpClient = OkHttpClient.Builder().addInterceptor(stub).build()).also {
            it.configure(serverUrl = "http://stub.local", accessToken = "token", userId = "me")
        }
    private val service = WatchlistService(client)

    private val dune =
        WatchlistTitleRef(type = "Movie", title = "Dune", year = 2021, tmdbId = "438631", tvdbId = null, imdbId = "tt1160419")

    // MARK: - Search

    @Test
    fun `search decodes both groups and sends the trimmed query`() =
        runTest {
            stub.routes["GET /SashimiLists/Watchlist/Search"] = 200 to SEARCH_SAMPLE
            val result = service.search("  dune ")!!
            assertEquals(
                "q=dune",
                stub
                    .calls("GET /SashimiLists/Watchlist/Search")
                    .single()
                    .call
                    .substringAfter('?'),
            )
            assertEquals("dune", result.query)
            val on = result.onServer.single()
            assertEquals("0123456789abcdef0123456789abcdef", on.itemId)
            assertTrue(on.onWatchlist)
            assertEquals("fedcba9876543210fedcba9876543210", on.watchlistEntryId)
            val off = result.notOnServer.single()
            assertNull(off.itemId)
            assertFalse(off.onWatchlist)
            assertEquals("1170608", off.tmdbId)
        }

    @Test
    fun `an older plugin answers 404 and search is simply not there`() =
        runTest {
            assertNull(service.search("dune"))
            assertNull(service.config())
            assertFalse(service.v2Available())
        }

    @Test
    fun `a server error is an error, not an older plugin`() =
        runTest {
            stub.routes["GET /SashimiLists/Watchlist/Search"] = 500 to ""
            try {
                service.search("dune")
                fail("expected an error")
            } catch (e: JellyfinError.HttpError) {
                assertEquals(500, e.statusCode)
            }
        }

    @Test
    fun `searching waits for two characters and stops at two hundred`() {
        assertFalse(WatchlistV2.searchable(""))
        assertFalse(WatchlistV2.searchable(" d "))
        assertTrue(WatchlistV2.searchable("du"))
        assertTrue(WatchlistV2.searchable("a".repeat(200)))
        assertFalse(WatchlistV2.searchable("a".repeat(201)))
    }

    @Test
    fun `a reply for an older query is dropped`() {
        val result = WatchlistSearchResult(query = "dun")
        assertTrue(WatchlistV2.isCurrent(result, " dun "))
        assertFalse(WatchlistV2.isCurrent(result, "dune"))
    }

    @Test
    fun `plus posts the hit's own fields to the add route`() {
        val hit = Json { ignoreUnknownKeys = true }.decodeFromString<WatchlistSearchResult>(SEARCH_SAMPLE).notOnServer.single()
        assertEquals(
            NewWatchlistEntry("Movie", "Dune: Part Three", 2026, "https://image.tmdb.org/t/p/original/def.jpg", "1170608", null, null),
            WatchlistV2.newEntry(hit),
        )
        assertEquals("2026 · Movie", WatchlistV2.hitLine(hit))
        assertEquals("Show", WatchlistV2.hitLine(hit.copy(type = "Series", year = null)))
    }

    // MARK: - Details

    @Test
    fun `details sends every id it has and decodes what the provider knew`() =
        runTest {
            stub.routes["GET /SashimiLists/Watchlist/Details"] = 200 to DETAILS_SAMPLE
            val details = service.details(dune)!!
            assertEquals(
                "type=movie&tmdbId=438631&imdbId=tt1160419",
                stub
                    .calls("GET /SashimiLists/Watchlist/Details")
                    .single()
                    .call
                    .substringAfter('?'),
            )
            assertEquals("https://image.tmdb.org/t/p/original/xyz.jpg", details.backdropUrl)
            assertEquals("TMDB", details.source)
            assertNull("absent, not null", details.seasonCount)
            assertNull(details.itemId)
            assertTrue(details.onWatchlist)
            assertEquals(listOf("2021", "2h 35m", "PG-13", "Science Fiction, Adventure", "★ 7.8"), WatchlistV2.facts(details))
        }

    @Test
    fun `no provider knows it, or an older plugin, gives no details and throws nothing`() =
        runTest {
            assertNull(service.details(dune))
        }

    @Test
    fun `a show reads its seasons, and missing facts are left out`() {
        val show = WatchlistTitleDetails(type = "Series", title = "The Sinner", seasonCount = 4, runtimeMinutes = 45, communityRating = 0.0)
        assertEquals(listOf("4 seasons"), WatchlistV2.facts(show))
        assertEquals(emptyList<String>(), WatchlistV2.facts(WatchlistTitleDetails(type = "Movie", title = "X", genres = emptyList())))
    }

    @Test
    fun `a details page's ref comes from a Watchlist entry or a hit, and requests by TMDB id`() {
        val entry =
            WatchlistEntry(
                id = "e",
                type = "Series",
                title = "The Sinner",
                year = 2017,
                tmdbId = "71728",
                tvdbId = "",
                addedAt = "2026-11-10T00:00:00Z",
            )
        val ref = WatchlistTitleRef.of(entry)
        assertEquals(listOf("type" to "series", "tmdbId" to "71728"), ref.query())
        assertEquals(SeerrTarget(SeerrMediaKind.TV, 71728, "The Sinner"), ref.seerrTarget())
        assertNull(ref.copy(tmdbId = null).seerrTarget())
        assertEquals(NewWatchlistEntry("Series", "The Sinner", 2017, null, "71728", "", null), ref.newEntry())
    }

    // MARK: - Config

    @Test
    fun `config reads the server-set Requests address`() =
        runTest {
            stub.routes["GET /SashimiLists/Config"] = 200 to """{"requestsUrl":"https://requests.example.com"}"""
            assertEquals("https://requests.example.com", service.config()?.requestsUrl)
            assertTrue(service.v2Available())
            stub.routes["GET /SashimiLists/Config"] = 200 to """{"requestsUrl":null}"""
            assertNull(service.config()?.requestsUrl)
        }

    @Test
    fun `v2 is asked once per server and user, and a failure is asked again`() =
        runTest {
            stub.unreachable += "GET /SashimiLists/Config"
            assertFalse(service.v2Available())
            stub.unreachable.clear()
            stub.routes["GET /SashimiLists/Config"] = 200 to """{"requestsUrl":null}"""
            assertTrue(service.v2Available())
            val asked = stub.calls("GET /SashimiLists/Config").size
            assertTrue(service.v2Available())
            assertEquals("a real answer is kept", asked, stub.calls("GET /SashimiLists/Config").size)
        }

    @Test
    fun `the add body from details carries the ids the server knows`() {
        val details = Json { ignoreUnknownKeys = true }.decodeFromString<WatchlistTitleDetails>(DETAILS_SAMPLE)
        val body = Json.encodeToString(NewWatchlistEntry.serializer(), WatchlistV2.newEntry(details))
        val obj = Json.parseToJsonElement(body).jsonObject
        assertEquals("Movie", obj["type"]!!.jsonPrimitive.content)
        assertEquals("438631", obj["tmdbId"]!!.jsonPrimitive.content)
        assertEquals("tt1160419", obj["imdbId"]!!.jsonPrimitive.content)
    }
}
