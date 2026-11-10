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

            var link: kotlinx.coroutines.Job? = null
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
                    onPlexToken = { link = linker.autoLinkInBackground(it) },
                ).also { it.setServerAddress(SeerrFixtures.SERVER) }
            session.completePlexSignIn(session.beginPlexSignIn()!!)
            assertTrue(session.state.value is SeerrAuthState.SignedIn)
            assertNull(session.errorMessage.value)
            link!!.join()
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

    private val changes = mutableListOf<Unit>()

    private fun TestScope.watchChanges(linker: WatchlistPlexLinker) {
        backgroundScope.launch { linker.changes.collect { changes += it } }
        runCurrent()
    }

    @Test
    fun `switching accounts tells open screens to re-read`() =
        runTest {
            stub.routes["GET /SashimiLists/Watchlist"] = 200 to linkedAsChad
            stub.queued["POST /SashimiLists/Watchlist/Plex"] = ArrayDeque(listOf(409 to """{"username":"chad","newUsername":"mondo"}"""))
            stub.routes["POST /SashimiLists/Watchlist/Plex"] = 200 to """{"username":"mondo"}"""
            val linker = linker()
            watchChanges(linker)
            linker.autoLink("T")
            assertTrue("a question is no change", changes.isEmpty())
            linker.answer(true)!!.join()
            runCurrent()
            assertEquals(1, changes.size)
        }

    @Test
    fun `connect tells open screens to re-read`() =
        runTest {
            stub.routes["POST /SashimiLists/Watchlist/Plex"] = 200 to """{"username":"mondo"}"""
            val linker = linker()
            watchChanges(linker)
            linker.connect("T")
            runCurrent()
            assertEquals(1, changes.size)
        }

    @Test
    fun `a silent auto-relink of the same account still tells open screens`() =
        runTest {
            // "Plex stopped syncing" goes away once Requests sign-in relinks the same account.
            stub.routes["GET /SashimiLists/Watchlist"] = 200 to """{"plex":{"username":"chad","needsReconnect":true},"entries":[]}"""
            stub.routes["POST /SashimiLists/Watchlist/Plex"] = 200 to """{"username":"chad"}"""
            val linker = linker()
            watchChanges(linker)
            linker.autoLink("T")
            runCurrent()
            assertTrue(notices.isEmpty())
            assertEquals(1, changes.size)
        }

    @Test
    fun `disconnect tells open screens to re-read`() =
        runTest {
            stub.routes["DELETE /SashimiLists/Watchlist/Plex"] = 204 to ""
            val linker = linker()
            watchChanges(linker)
            linker.disconnect()
            runCurrent()
            assertEquals(1, changes.size)
            assertEquals(1, stub.calls("DELETE /SashimiLists/Watchlist/Plex").size)
        }

    @Test
    fun `a link that didn't happen changes nothing`() =
        runTest {
            stub.routes["POST /SashimiLists/Watchlist/Plex"] = 422 to ""
            val linker = linker()
            watchChanges(linker)
            linker.connect("T")
            stub.routes["DELETE /SashimiLists/Watchlist/Plex"] = 500 to ""
            runCatching { linker.disconnect() }
            runCurrent()
            assertTrue(changes.isEmpty())
        }

    /**
     * Requests sign-in with Plex against [watchlist]'s answers, through the
     * hand-off the app wires ([WatchlistPlexLinker.autoLinkInBackground]).
     * Returns whether sign-in finished before the held Watchlist was let go.
     */
    private suspend fun signInToRequestsWhile(watchlist: () -> Unit): Boolean {
        watchlist()
        val latch = java.util.concurrent.CountDownLatch(1)
        stub.held["GET /SashimiLists/Watchlist"] = latch
        stub.held["POST /SashimiLists/Watchlist/Plex"] = latch
        val released =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        Thread {
            Thread.sleep(5_000)
            released.set(true)
            latch.countDown()
        }.start()
        // A real background scope: the link really runs, and really hangs on the held Watchlist.
        val linkScope = CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        val linker = WatchlistPlexLinker(service, linkScope)
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
                onPlexToken = linker::autoLinkInBackground,
            ).also { it.setServerAddress(SeerrFixtures.SERVER) }
        session.completePlexSignIn(session.beginPlexSignIn()!!)
        val finishedFirst = !released.get()
        assertTrue(session.state.value is SeerrAuthState.SignedIn)
        assertNull(session.errorMessage.value)
        latch.countDown()
        linkScope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        return finishedFirst
    }

    @Test
    fun `requests sign-in finishes while jellyfin is slow`() =
        runTest {
            assertTrue(signInToRequestsWhile { stub.routes["GET /SashimiLists/Watchlist"] = 200 to unlinked })
        }

    @Test
    fun `requests sign-in finishes when the server has no watchlist`() = runTest { assertTrue(signInToRequestsWhile { }) }

    @Test
    fun `requests sign-in finishes when the watchlist 5xxs`() =
        runTest { assertTrue(signInToRequestsWhile { stub.routes["GET /SashimiLists/Watchlist"] = 503 to "" }) }

    @Test
    fun `requests sign-in finishes when the link 5xxs or plex refuses it`() =
        runTest {
            assertTrue(
                signInToRequestsWhile {
                    stub.routes["GET /SashimiLists/Watchlist"] = 200 to unlinked
                    stub.routes["POST /SashimiLists/Watchlist/Plex"] = 502 to ""
                },
            )
            assertTrue(
                signInToRequestsWhile {
                    stub.routes["POST /SashimiLists/Watchlist/Plex"] = 422 to ""
                },
            )
        }

    @Test
    fun `requests sign-in finishes when jellyfin is unreachable`() =
        runTest { assertTrue(signInToRequestsWhile { stub.unreachable += "GET /SashimiLists/Watchlist" }) }

    @Test
    fun `auto-link leaves alone someone who disconnected plex`() =
        runTest {
            stub.routes["GET /SashimiLists/Watchlist"] = 200 to """{"plex":null,"plexOptedOut":true,"entries":[]}"""
            stub.routes["POST /SashimiLists/Watchlist/Plex"] = 200 to """{"username":"chad"}"""
            val linker = linker()
            watchChanges(linker)
            linker.autoLink("T")
            runCurrent()
            assertTrue(linkCalls().isEmpty())
            assertTrue(notices.isEmpty())
            assertTrue(changes.isEmpty())
            assertNull(linker.prompt.value)
        }

    @Test
    fun `an explicit connect still links after a disconnect`() =
        runTest {
            stub.routes["GET /SashimiLists/Watchlist"] = 200 to """{"plex":null,"plexOptedOut":true,"entries":[]}"""
            stub.routes["POST /SashimiLists/Watchlist/Plex"] = 200 to """{"username":"chad"}"""
            assertEquals(PlexLinkResult.Linked(PlexLink("chad")), linker().connect("T"))
            assertEquals(1, linkCalls().size)
        }
}
