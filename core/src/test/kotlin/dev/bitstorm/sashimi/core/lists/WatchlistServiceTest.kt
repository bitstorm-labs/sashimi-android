package dev.bitstorm.sashimi.core.lists

import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.network.JellyfinError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The plugin's `GET /SashimiLists/Watchlist` sample (Plan 1 HTTP contract). */
internal const val WATCHLIST_SAMPLE = """{
  "plex": {"username": "chad", "needsReconnect": false},
  "cardDismissed": true,
  "entries": [
    {"id": "e2", "type": "Series", "title": "Breaking Bad", "year": 2008, "posterUrl": "https://image.tmdb.org/t/p/w500/b.jpg",
     "tmdbId": "1396", "tvdbId": "81189", "imdbId": "tt0903747", "addedAt": "2026-11-09T10:00:00Z", "itemId": null},
    {"id": "e1", "type": "Movie", "title": "Heat", "year": 1995, "posterUrl": null,
     "tmdbId": "949", "tvdbId": null, "imdbId": "tt0113277", "addedAt": "2026-11-08T10:00:00Z", "itemId": "abc123def", "future": 1}
  ]
}"""

class WatchlistServiceTest {
    private val stub = StubServer()
    private var expired = false

    private val client =
        JellyfinClient(deviceId = "test", httpClient = OkHttpClient.Builder().addInterceptor(stub).build()).also {
            it.configure(serverUrl = "http://stub.local", accessToken = "token", userId = "me")
            it.sessionExpiredHandler = { expired = true }
        }

    private val service = WatchlistService(client)

    @Test
    fun `view decodes the contract shape`() =
        runTest {
            stub.routes["GET /SashimiLists/Watchlist"] = 200 to WATCHLIST_SAMPLE
            val view = service.view()!!
            assertEquals(PlexLink("chad", false), view.plex)
            assertTrue(view.cardDismissed)
            assertEquals(listOf("e2", "e1"), view.entries.map { it.id })
            assertTrue(view.entries[0].isSeries)
            assertNull(view.entries[0].itemId)
            assertEquals("abc123def", view.entries[1].itemId)
            assertEquals("tt0113277", view.entries[1].imdbId)
            assertTrue(service.available())
            assertEquals(1, stub.calls("GET /SashimiLists/Watchlist").size)
        }

    @Test
    fun `a 404 means no watchlist`() =
        runTest {
            assertNull(service.view())
            assertFalse(service.available())
            assertFalse(service.available())
            assertEquals(1, stub.calls("GET /SashimiLists/Watchlist").size)
            assertFalse(expired)
        }

    @Test
    fun `a 500 is an error not absence`() =
        runTest {
            stub.routes["GET /SashimiLists/Watchlist"] = 500 to ""
            try {
                service.view()
                fail("expected an error")
            } catch (e: JellyfinError) {
                assertEquals(JellyfinError.HttpError(500), e)
            }
            val before = stub.calls("GET /SashimiLists/Watchlist").size
            assertFalse(service.available())
            val after = stub.calls("GET /SashimiLists/Watchlist").size
            assertTrue(after > before)
            stub.routes["GET /SashimiLists/Watchlist"] = 200 to """{"entries":[]}"""
            assertTrue(service.available())
        }

    @Test
    fun `add posts the entry and reads added false for a duplicate`() =
        runTest {
            val entry = """{"id":"e1","type":"Movie","title":"Heat","tmdbId":"949","addedAt":"2026-11-08T10:00:00Z","itemId":"abc"}"""
            stub.routes["POST /SashimiLists/Watchlist/Items"] = 200 to """{"entry":$entry,"added":false}"""
            val result = service.add(NewWatchlistEntry(type = "Movie", title = "Heat", year = 1995, tmdbId = "949"))
            assertFalse(result.added)
            assertEquals("e1", result.entry.id)
            val body = stub.calls("POST /SashimiLists/Watchlist/Items").single().body!!
            val sent = Json.parseToJsonElement(body).jsonObject
            assertEquals(setOf("type", "title", "year", "tmdbId"), sent.keys)
            assertEquals("949", sent["tmdbId"]!!.jsonPrimitive.content)
        }

    @Test
    fun `remove sends DELETE with the entry id`() =
        runTest {
            stub.routes["DELETE /SashimiLists/Watchlist/Items/e9"] = 204 to ""
            service.remove("e9")
            assertEquals(1, stub.calls("DELETE /SashimiLists/Watchlist/Items/e9").size)
        }

    @Test
    fun `link 200 is Linked`() =
        runTest {
            stub.routes["POST /SashimiLists/Watchlist/Plex"] = 200 to """{"username":"chad","needsReconnect":false}"""
            assertEquals(PlexLinkResult.Linked(PlexLink("chad")), service.linkPlex("T", replace = false))
            val sent = Json.parseToJsonElement(stub.calls("POST /SashimiLists/Watchlist/Plex").single().body!!).jsonObject
            assertEquals("T", sent["token"]!!.jsonPrimitive.content)
            assertFalse(sent["replace"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `link 409 is ConflictsWith the current and new username`() =
        runTest {
            stub.routes["POST /SashimiLists/Watchlist/Plex"] = 409 to """{"username":"chad","newUsername":"mondo"}"""
            assertEquals(PlexLinkResult.ConflictsWith("chad", "mondo"), service.linkPlex("T", replace = false))
            assertFalse(expired)
        }

    @Test
    fun `link sends replace true when asked`() =
        runTest {
            stub.routes["POST /SashimiLists/Watchlist/Plex"] = 200 to """{"username":"mondo"}"""
            service.linkPlex("T", replace = true)
            val sent = Json.parseToJsonElement(stub.calls("POST /SashimiLists/Watchlist/Plex").single().body!!).jsonObject
            assertTrue(sent["replace"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `a plex 422 on link is Rejected and does not expire the jellyfin session`() =
        runTest {
            stub.routes["POST /SashimiLists/Watchlist/Plex"] = 422 to ""
            assertEquals(PlexLinkResult.Rejected, service.linkPlex("T", replace = false))
            assertFalse(expired)
        }

    @Test
    fun `a 401 on link signs out as usual`() =
        runTest {
            stub.routes["POST /SashimiLists/Watchlist/Plex"] = 401 to ""
            try {
                service.linkPlex("T", replace = false)
                fail("expected SessionExpired")
            } catch (e: JellyfinError.SessionExpired) {
                assertTrue(expired)
            }
        }

    @Test
    fun `the token never appears in an exception message`() =
        runTest {
            stub.routes["POST /SashimiLists/Watchlist/Plex"] = 418 to """{"error":"bad token SECRET-T"}"""
            try {
                service.linkPlex("SECRET-T", replace = false)
                fail("expected an error")
            } catch (e: JellyfinError) {
                assertFalse(e.toString().contains("SECRET-T"))
                assertFalse((e.message ?: "").contains("SECRET-T"))
                assertEquals(JellyfinError.HttpError(418), e)
            }
        }

    @Test
    fun `unlink and dismiss hit their routes`() =
        runTest {
            stub.routes["DELETE /SashimiLists/Watchlist/Plex"] = 204 to ""
            stub.routes["POST /SashimiLists/Watchlist/DismissCard"] = 204 to ""
            service.unlinkPlex()
            service.dismissCard()
            assertEquals(1, stub.calls("DELETE /SashimiLists/Watchlist/Plex").size)
            assertEquals(1, stub.calls("POST /SashimiLists/Watchlist/DismissCard").size)
        }

    @Test
    fun `library items are keyed by normalized id`() =
        runTest {
            stub.routes["GET /Items"] = 200 to """{"Items":[{"Id":"abc123def","Name":"Heat","Type":"Movie"}],"TotalRecordCount":1}"""
            val items = service.libraryItems(listOf("ABC1-23DE-F"))
            assertEquals("Heat", items[SharedLists.normalizeId("ABC1-23DE-F")]?.name)
            assertEquals("Heat", items[SharedLists.normalizeId("abc123def")]?.name)
            val call = stub.calls("GET /Items").single().call
            assertTrue(call, call.contains("Ids=ABC1-23DE-F"))
            assertTrue(call, call.contains("userId=me"))
            assertTrue(call, call.contains("ProviderIds"))
        }

    @Test
    fun `no library ids makes no call`() =
        runTest {
            assertTrue(service.libraryItems(emptyList()).isEmpty())
            assertTrue(stub.seen.isEmpty())
        }

    @Test
    fun `a 5xx on link is Plex unreachable, not a rejected token`() =
        runTest {
            stub.routes["POST /SashimiLists/Watchlist/Plex"] = 503 to ""
            assertEquals(PlexLinkResult.PlexUnreachable, service.linkPlex("T", replace = false))
            stub.routes["POST /SashimiLists/Watchlist/Plex"] = 500 to ""
            assertEquals(PlexLinkResult.PlexUnreachable, service.linkPlex("T", replace = false))
            assertFalse(expired)
        }

    @Test
    fun `library items are fetched in batches`() =
        runTest {
            stub.routes["GET /Items"] = 200 to """{"Items":[],"TotalRecordCount":0}"""
            service.libraryItems((1..250).map { "id$it" })
            val calls = stub.calls("GET /Items")
            assertEquals(3, calls.size)
            assertTrue(
                calls.all {
                    it.call
                        .substringAfter("Ids=")
                        .substringBefore("&")
                        .split("%2C", ",")
                        .size <= 100
                },
            )
        }
}
