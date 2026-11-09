package dev.bitstorm.sashimi.core.lists

import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.network.JellyfinError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Settings › Plex (watchlist design, owner 2026-11-09). */
class WatchlistPlexStatusTest {
    private val stub = StubServer()

    private val client =
        JellyfinClient(deviceId = "test", httpClient = OkHttpClient.Builder().addInterceptor(stub).build()).also {
            it.configure(serverUrl = "http://stub.local", accessToken = "token", userId = "me")
        }

    private val service = WatchlistService(client)

    @Test
    fun `the row reads the link`() {
        assertEquals(PlexSetting.NotConnected, PlexSetting.of(WatchlistRead.Ready(WatchlistView(plex = null))))
        assertEquals(
            PlexSetting.Connected("chad", needsReconnect = false),
            PlexSetting.of(WatchlistRead.Ready(WatchlistView(plex = PlexLink("chad")))),
        )
        assertEquals(
            PlexSetting.Connected("chad", needsReconnect = true),
            PlexSetting.of(WatchlistRead.Ready(WatchlistView(plex = PlexLink("chad", needsReconnect = true)))),
        )
        assertEquals(PlexSetting.Loading, PlexSetting.of(WatchlistRead.Pending))
    }

    @Test
    fun `only a 404 hides the row`() {
        assertNull(PlexSetting.of(WatchlistRead.Absent))
        assertEquals(PlexSetting.Unknown, PlexSetting.of(WatchlistRead.Failed(JellyfinError.HttpError(503))))
        assertEquals(PlexSetting.Unknown, PlexSetting.of(WatchlistRead.Failed(JellyfinError.NetworkError(java.io.IOException("offline")))))
    }

    private fun TestScope.status(): Pair<WatchlistPlexStatus, WatchlistPlexLinker> {
        val linker = WatchlistPlexLinker(service, backgroundScope)
        return WatchlistPlexStatus(service, linker, backgroundScope) to linker
    }

    /** The next setting after the current one (the re-read goes over the stub's real threads). */
    private suspend fun WatchlistPlexStatus.settles(): PlexSetting? {
        val now = setting.value
        return withContext(Dispatchers.Default) { withTimeout(5_000) { setting.first { it != now } } }
    }

    @Test
    fun `refresh reads the server`() =
        runTest {
            stub.routes["GET /SashimiLists/Watchlist"] = 200 to """{"plex":{"username":"chad"},"entries":[]}"""
            val (status, _) = status()
            status.refresh().join()
            assertEquals(PlexSetting.Connected("chad", false), status.setting.value)
            stub.routes["GET /SashimiLists/Watchlist"] = 404 to ""
            status.refresh().join()
            assertNull(status.setting.value)
        }

    @Test
    fun `connecting on the watchlist screen updates settings`() =
        runTest {
            stub.routes["GET /SashimiLists/Watchlist"] = 200 to """{"plex":null,"entries":[]}"""
            val (status, linker) = status()
            status.refresh().join()
            assertEquals(PlexSetting.NotConnected, status.setting.value)
            stub.routes["POST /SashimiLists/Watchlist/Plex"] = 200 to """{"username":"mondo"}"""
            stub.routes["GET /SashimiLists/Watchlist"] = 200 to """{"plex":{"username":"mondo"},"entries":[]}"""
            runCurrent()
            linker.connect("T")
            assertEquals(PlexSetting.Connected("mondo", false), status.settles())
        }

    @Test
    fun `disconnecting updates settings`() =
        runTest {
            stub.routes["GET /SashimiLists/Watchlist"] = 200 to """{"plex":{"username":"mondo"},"entries":[]}"""
            val (status, linker) = status()
            status.refresh().join()
            stub.routes["DELETE /SashimiLists/Watchlist/Plex"] = 204 to ""
            stub.routes["GET /SashimiLists/Watchlist"] = 200 to """{"plex":null,"entries":[]}"""
            runCurrent()
            linker.disconnect()
            assertEquals(PlexSetting.NotConnected, status.settles())
        }
}
