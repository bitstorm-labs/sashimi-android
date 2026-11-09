package dev.bitstorm.sashimi.core.lists

import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.seerr.MemorySeerrSettings
import dev.bitstorm.sashimi.core.seerr.PlexPin
import dev.bitstorm.sashimi.core.seerr.PlexPinClient
import dev.bitstorm.sashimi.core.seerr.PlexPinSignIn
import dev.bitstorm.sashimi.core.seerr.SeerrSession
import dev.bitstorm.sashimi.core.seerr.SeerrStub
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Watchlist's Connect Plex, from the Watchlist card or Settings › Plex. */
class WatchlistConnectPlexTest {
    private val jellyfin = StubServer()
    private val plex = SeerrStub()

    /** Requests never set up: no Seerr address, no Seerr session, nothing stored. */
    private val settings = MemorySeerrSettings()

    private val client =
        JellyfinClient(deviceId = "test", httpClient = OkHttpClient.Builder().addInterceptor(jellyfin).build()).also {
            it.configure(serverUrl = "http://stub.local", accessToken = "token", userId = "me")
        }

    private fun connectPlex(scope: kotlinx.coroutines.CoroutineScope): WatchlistConnectPlex {
        val signIn = PlexPinSignIn(settings, { PlexPinClient(it, "1.0", plex.http) }, pollIntervalMillis = 0)
        return WatchlistConnectPlex(signIn, WatchlistPlexLinker(WatchlistService(client), scope))
    }

    private val opened = mutableListOf<PlexPin>()

    @Test
    fun `a pin kept across process death is polled again, not replaced`() =
        runTest {
            plex.on("GET", "/api/v2/pins/42", body = """{"id":42,"code":"K7QF","authToken":"plex-token"}""")
            jellyfin.routes["POST /SashimiLists/Watchlist/Plex"] = 200 to """{"username":"mondo"}"""
            val outcome =
                connectPlex(backgroundScope).run(
                    resume = PlexPin(42, "K7QF"),
                    onPin = { opened += it },
                    onToken = {},
                    stillWaiting = { true },
                )
            assertEquals(ConnectPlexOutcome.Linked, outcome)
            assertTrue("no new PIN", plex.requests("POST", "/api/v2/pins").isEmpty())
            assertTrue("Plex's page isn't opened again", opened.isEmpty())
            assertEquals(1, jellyfin.calls("POST /SashimiLists/Watchlist/Plex").size)
        }

    @Test
    fun `connect plex needs nothing from requests`() =
        runTest {
            plex.on("POST", "/api/v2/pins", status = 201, body = """{"id":7,"code":"ABCD"}""")
            plex.on("GET", "/api/v2/pins/7", body = """{"id":7,"code":"ABCD","authToken":"plex-token"}""")
            jellyfin.routes["POST /SashimiLists/Watchlist/Plex"] = 200 to """{"username":"mondo"}"""
            assertTrue(settings.strings[SeerrSession.KEY_SERVER] == null)
            val outcome =
                connectPlex(backgroundScope).run(
                    resume = null,
                    onPin = { opened += it },
                    onToken = {},
                    stillWaiting = { true },
                )
            assertEquals(ConnectPlexOutcome.Linked, outcome)
            assertEquals(listOf(PlexPin(7, "ABCD")), opened)
            // Only plex.tv and Jellyfin were asked: never a Seerr server.
            assertTrue(plex.requests.all { it.url.host == "plex.tv" })
        }

    @Test
    fun `closing the dialog on a kept pin never asks for a new one`() =
        runTest {
            plex.on("GET", "/api/v2/pins/42", status = 404, body = "")
            var polls = 0
            val outcome =
                connectPlex(backgroundScope).run(
                    resume = PlexPin(42, "K7QF"),
                    onPin = { opened += it },
                    onToken = {},
                    stillWaiting = { polls++ < 3 },
                )
            assertEquals(ConnectPlexOutcome.Abandoned, outcome)
            assertTrue(plex.requests("POST", "/api/v2/pins").isEmpty())
        }
}
