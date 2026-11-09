package dev.bitstorm.sashimi.core.lists

import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.seerr.MemorySeerrSettings
import dev.bitstorm.sashimi.core.seerr.MemoryTokenStore
import dev.bitstorm.sashimi.core.seerr.PlexPinClient
import dev.bitstorm.sashimi.core.seerr.SeerrAuthState
import dev.bitstorm.sashimi.core.seerr.SeerrClient
import dev.bitstorm.sashimi.core.seerr.SeerrFixtures
import dev.bitstorm.sashimi.core.seerr.SeerrSession
import dev.bitstorm.sashimi.core.seerr.SeerrStub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
import org.junit.Test

class WatchlistPlexLinkerTest {
    private val stub = StubServer()
    private var expired = false

    private val client =
        JellyfinClient(deviceId = "test", httpClient = OkHttpClient.Builder().addInterceptor(stub).build()).also {
            it.configure(serverUrl = "http://stub.local", accessToken = "token", userId = "me")
            it.sessionExpiredHandler = { expired = true }
        }

    private val service = WatchlistService(client)
    private val notices = mutableListOf<String>()

    private fun TestScope.linker(scope: CoroutineScope = this): WatchlistPlexLinker =
        WatchlistPlexLinker(service, scope).also { l ->
            backgroundScope.launch { l.notices.collect { notices += it } }
            runCurrent()
        }

    private val unlinked = """{"plex":null,"entries":[]}"""
    private val linkedAsChad = """{"plex":{"username":"chad"},"entries":[]}"""

    private fun linkCalls() = stub.calls("POST /SashimiLists/Watchlist/Plex")

    private fun replaceSent(i: Int) =
        Json
            .parseToJsonElement(linkCalls()[i].body!!)
            .jsonObject["replace"]!!
            .jsonPrimitive.boolean

    @Test
    fun `first auto-link links and says so once`() =
        runTest {
            stub.routes["GET /SashimiLists/Watchlist"] = 200 to unlinked
            stub.routes["POST /SashimiLists/Watchlist/Plex"] = 200 to """{"username":"chad"}"""
            val linker = linker()
            linker.autoLink("T")
            runCurrent()
            assertEquals(listOf("Your Watchlist now syncs with Plex"), notices)
            assertEquals(1, linkCalls().size)
            assertFalse(replaceSent(0))
            assertNull(linker.prompt.value)
        }

    @Test
    fun `auto-link when already linked to the same account says nothing`() =
        runTest {
            stub.routes["GET /SashimiLists/Watchlist"] = 200 to linkedAsChad
            stub.routes["POST /SashimiLists/Watchlist/Plex"] = 200 to """{"username":"chad"}"""
            val linker = linker()
            linker.autoLink("T")
            runCurrent()
            assertEquals(1, linkCalls().size)
            assertTrue(notices.isEmpty())
            assertNull(linker.prompt.value)
        }

    @Test
    fun `auto-link to a different account asks with that account's name`() =
        runTest {
            stub.routes["GET /SashimiLists/Watchlist"] = 200 to linkedAsChad
            stub.routes["POST /SashimiLists/Watchlist/Plex"] = 409 to """{"username":"chad","newUsername":"mondo"}"""
            val linker = linker()
            linker.autoLink("T")
            assertEquals(LinkPrompt.Replace("mondo"), linker.prompt.value)
            assertTrue(notices.isEmpty())
        }

    @Test
    fun `answering yes relinks with replace true and drops the token`() =
        runTest {
            stub.routes["GET /SashimiLists/Watchlist"] = 200 to linkedAsChad
            stub.queued["POST /SashimiLists/Watchlist/Plex"] = ArrayDeque(listOf(409 to """{"username":"chad","newUsername":"mondo"}"""))
            stub.routes["POST /SashimiLists/Watchlist/Plex"] = 200 to """{"username":"mondo"}"""
            val linker = linker()
            linker.autoLink("T")
            linker.answer(true)!!.join()
            advanceUntilIdle()
            assertEquals(2, linkCalls().size)
            assertTrue(replaceSent(1))
            assertNull(linker.prompt.value)
            assertEquals(listOf("Your Watchlist now syncs with Plex"), notices)
            assertNull(linker.answer(true))
            assertEquals(2, linkCalls().size)
        }

    @Test
    fun `answering no drops the token without a call`() =
        runTest {
            stub.routes["GET /SashimiLists/Watchlist"] = 200 to linkedAsChad
            stub.routes["POST /SashimiLists/Watchlist/Plex"] = 409 to """{"username":"chad","newUsername":"mondo"}"""
            val linker = linker()
            linker.autoLink("T")
            linker.answer(false)
            advanceUntilIdle()
            assertEquals(1, linkCalls().size)
            assertNull(linker.prompt.value)
            assertNull(linker.answer(true))
            assertEquals(1, linkCalls().size)
        }

    @Test
    fun `auto-link on a server without the plugin does nothing and never fails seerr sign-in`() =
        runTest {
            val linker = linker()
            linker.autoLink("T")
            assertTrue(linkCalls().isEmpty())
            assertNull(linker.prompt.value)
            assertTrue(notices.isEmpty())

            val seerr = SeerrStub()
            seerr.on("POST", "/api/v2/pins", status = 201, body = """{"id":42,"code":"K7QF"}""")
            seerr.on("GET", "/api/v2/pins/42", body = """{"id":42,"code":"K7QF","authToken":"plex-token"}""")
            seerr.on("POST", "/api/v1/auth/plex", body = SeerrFixtures.user(), headers = mapOf("Set-Cookie" to SeerrFixtures.SET_COOKIE))
            val session =
                SeerrSession(
                    settings = MemorySeerrSettings(),
                    cookies = MemoryTokenStore(),
                    pollIntervalMillis = 0,
                    clientFactory = { url, cookie -> SeerrClient(url, cookie, seerr.http) },
                    plexFactory = { PlexPinClient(it, "1.0", seerr.http) },
                    onPlexToken = { linker.autoLink(it) },
                ).also { it.setServerAddress(SeerrFixtures.SERVER) }
            session.completePlexSignIn(session.beginPlexSignIn()!!)
            assertTrue(session.state.value is SeerrAuthState.SignedIn)
            assertNull(session.errorMessage.value)
            assertEquals(2, stub.calls("GET /SashimiLists/Watchlist").size)
            assertTrue(linkCalls().isEmpty())
        }

    @Test
    fun `a throwing token hand-off never changes seerr's state or error`() =
        runTest {
            val seerr = SeerrStub()
            seerr.on("POST", "/api/v2/pins", status = 201, body = """{"id":42,"code":"K7QF"}""")
            seerr.on("GET", "/api/v2/pins/42", body = """{"id":42,"code":"K7QF","authToken":"plex-token"}""")
            seerr.on("POST", "/api/v1/auth/plex", body = SeerrFixtures.user(), headers = mapOf("Set-Cookie" to SeerrFixtures.SET_COOKIE))
            var handed: String? = null
            val session =
                SeerrSession(
                    settings = MemorySeerrSettings(),
                    cookies = MemoryTokenStore(),
                    pollIntervalMillis = 0,
                    clientFactory = { url, cookie -> SeerrClient(url, cookie, seerr.http) },
                    plexFactory = { PlexPinClient(it, "1.0", seerr.http) },
                    onPlexToken = {
                        handed = it
                        error("boom")
                    },
                ).also { it.setServerAddress(SeerrFixtures.SERVER) }
            session.completePlexSignIn(session.beginPlexSignIn()!!)
            assertEquals("plex-token", handed)
            assertTrue(session.state.value is SeerrAuthState.SignedIn)
            assertNull(session.errorMessage.value)
        }

    @Test
    fun `auto-link failure is silent and drops the token`() =
        runTest {
            stub.unreachable += "GET /SashimiLists/Watchlist"
            val linker = linker()
            linker.autoLink("T")
            advanceUntilIdle()
            assertTrue(linkCalls().isEmpty())
            assertNull(linker.prompt.value)
            assertTrue(notices.isEmpty())
            assertFalse(expired)
            assertNull(linker.answer(true))
            assertTrue(linkCalls().isEmpty())
        }

    @Test
    fun `a rejected auto-link is silent`() =
        runTest {
            stub.routes["GET /SashimiLists/Watchlist"] = 200 to unlinked
            stub.routes["POST /SashimiLists/Watchlist/Plex"] = 422 to ""
            val linker = linker()
            linker.autoLink("T")
            runCurrent()
            assertTrue(notices.isEmpty())
            assertNull(linker.prompt.value)
        }

    @Test
    fun `connect conflict asks too`() =
        runTest {
            stub.routes["POST /SashimiLists/Watchlist/Plex"] = 409 to """{"username":"chad","newUsername":"mondo"}"""
            val linker = linker()
            assertEquals(PlexLinkResult.ConflictsWith("chad", "mondo"), linker.connect("T"))
            assertEquals(LinkPrompt.Replace("mondo"), linker.prompt.value)
        }

    @Test
    fun `prompt toString never contains the token`() =
        runTest {
            stub.routes["POST /SashimiLists/Watchlist/Plex"] = 409 to """{"username":"chad","newUsername":"mondo"}"""
            val linker = linker()
            linker.connect("SECRET-T")
            assertFalse(
                linker.prompt.value
                    .toString()
                    .contains("SECRET-T"),
            )
            assertFalse(linker.toString().contains("SECRET-T"))
        }

    @Test
    fun `a held token is dropped once the jellyfin user changes`() =
        runTest {
            stub.routes["GET /SashimiLists/Watchlist"] = 200 to linkedAsChad
            stub.routes["POST /SashimiLists/Watchlist/Plex"] = 409 to """{"username":"chad","newUsername":"mondo"}"""
            val linker = linker()
            linker.autoLink("T")
            assertTrue(linker.promptStillValid())
            client.configure(serverUrl = "http://stub.local", accessToken = "token-b", userId = "someone-else")
            assertFalse(linker.promptStillValid())
            assertNull(linker.prompt.value)
            assertNull(linker.answer(true))
            assertEquals(1, linkCalls().size)
        }

    @Test
    fun `answering yes after a user switch never relinks`() =
        runTest {
            stub.routes["GET /SashimiLists/Watchlist"] = 200 to linkedAsChad
            stub.routes["POST /SashimiLists/Watchlist/Plex"] = 409 to """{"username":"chad","newUsername":"mondo"}"""
            val linker = linker()
            linker.autoLink("T")
            client.configure(serverUrl = "http://other.local", accessToken = "token-b", userId = "me")
            assertNull(linker.answer(true))
            assertEquals(1, linkCalls().size)
        }

    @Test
    fun `requests sign-in does not wait for the watchlist link`() =
        runTest {
            stub.routes["GET /SashimiLists/Watchlist"] = 200 to unlinked
            stub.routes["POST /SashimiLists/Watchlist/Plex"] = 200 to """{"username":"chad"}"""
            val idle = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
            val linker = WatchlistPlexLinker(service, idle)
            val seerr = SeerrStub()
            seerr.on("POST", "/api/v2/pins", status = 201, body = """{"id":42,"code":"K7QF"}""")
            seerr.on("GET", "/api/v2/pins/42", body = """{"id":42,"code":"K7QF","authToken":"plex-token"}""")
            seerr.on("POST", "/api/v1/auth/plex", body = SeerrFixtures.user(), headers = mapOf("Set-Cookie" to SeerrFixtures.SET_COOKIE))
            val session =
                SeerrSession(
                    settings = MemorySeerrSettings(),
                    cookies = MemoryTokenStore(),
                    pollIntervalMillis = 0,
                    clientFactory = { url, cookie -> SeerrClient(url, cookie, seerr.http) },
                    plexFactory = { PlexPinClient(it, "1.0", seerr.http) },
                    onPlexToken = { linker.autoLinkInBackground(it) },
                ).also { it.setServerAddress(SeerrFixtures.SERVER) }
            session.completePlexSignIn(session.beginPlexSignIn()!!)
            assertTrue(session.state.value is SeerrAuthState.SignedIn)
            assertTrue("the link has not run yet", stub.calls("GET /SashimiLists/Watchlist").isEmpty())
            advanceUntilIdle()
            kotlinx.coroutines.yield()
        }
}
