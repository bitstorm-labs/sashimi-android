package dev.bitstorm.sashimi.core.lists

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.seerr.SeerrMediaKind
import dev.bitstorm.sashimi.core.seerr.SeerrTitleDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Add to List picker's Watchlist row: only a 404 hides it, and it never holds back your lists. */
class AddToListPickerTest {
    private val stub = StubServer()

    private val client =
        JellyfinClient(deviceId = "test", httpClient = OkHttpClient.Builder().addInterceptor(stub).build()).also {
            it.configure(serverUrl = "http://stub.local", accessToken = "token", userId = "me")
        }

    private val picker = AddToListPicker(SharedListsService(client), WatchlistService(client)) { "error: ${it::class.simpleName}" }

    private val heat =
        AddToListTarget.Library(
            BaseItemDto(id = "heat1", name = "Heat", type = ItemType.MOVIE, providerIds = mapOf("Tmdb" to "949")),
        )

    private val seerrTitle =
        AddToListTarget.Seerr(SeerrTitleDetails(kind = SeerrMediaKind.MOVIE, tmdbId = 949, title = "Heat"))

    private data class Shown(
        val at: Long,
        val state: AddToListUiState,
    )

    private suspend fun TestScope.states(target: AddToListTarget): List<Shown> {
        val out = mutableListOf<Shown>()
        picker.load(target).collect { out += Shown(currentTime, it) }
        return out
    }

    private fun noLists() {
        stub.routes["GET /Items"] = 200 to """{"Items":[],"TotalRecordCount":0}"""
    }

    @Test
    fun `a 5xx keeps the watchlist row`() =
        runTest {
            noLists()
            stub.routes["GET /SashimiLists/Watchlist"] = 503 to ""
            val last = states(heat).last().state
            assertFalse(last.isLoading)
            assertNull(last.error)
            assertNotNull(last.watchlist)
            val row = last.watchlist!!
            assertNull(row.count)
            assertFalse(row.alreadyOn)
        }

    @Test
    fun `a network error keeps the watchlist row`() =
        runTest {
            noLists()
            stub.unreachable += "GET /SashimiLists/Watchlist"
            assertNotNull(states(heat).last().state.watchlist)
        }

    @Test
    fun `your lists show without waiting for the watchlist read`() =
        runTest {
            noLists()
            // The Watchlist read hangs (a slow plugin, or one retrying a 5xx) until the lists have shown.
            val held = java.util.concurrent.CountDownLatch(1)
            stub.held["GET /SashimiLists/Watchlist"] = held
            stub.routes["GET /SashimiLists/Watchlist"] = 503 to ""
            try {
                val first =
                    withContext(Dispatchers.Default) {
                        withTimeout(5_000) { picker.load(heat).first { !it.isLoading } }
                    }
                assertNull(first.error)
                assertNotNull("the row stays while the read is pending", first.watchlist)
                assertNull(first.watchlist!!.count)
            } finally {
                held.countDown()
            }
        }

    @Test
    fun `a 404 hides the row and keeps your lists`() =
        runTest {
            noLists()
            val last = states(heat).last().state
            assertNull(last.watchlist)
            assertNull(last.error)
            assertTrue(last.showsLists)
        }

    @Test
    fun `a read watchlist gives the count and already-on`() =
        runTest {
            noLists()
            stub.routes["GET /SashimiLists/Watchlist"] = 200 to WATCHLIST_SAMPLE
            val row = states(heat).last().state.watchlist!!
            assertEquals(2, row.count)
            assertTrue(row.alreadyOn) // e1 is tmdb 949
        }

    @Test
    fun `a seerr title keeps the row on a 5xx and shows no error`() =
        runTest {
            stub.routes["GET /SashimiLists/Watchlist"] = 500 to ""
            val shown = states(seerrTitle)
            val last = shown.last().state
            assertNull(last.error)
            assertNotNull(last.watchlist)
            assertFalse(last.showsLists)
            assertTrue("row shown at ${shown.first { !it.state.isLoading }.at} ms", shown.first { !it.state.isLoading }.at < 1_000)
        }

    @Test
    fun `a seerr title on a server without the plugin says so`() =
        runTest {
            val last = states(seerrTitle).last().state
            assertNull(last.watchlist)
            assertEquals(AddToListPicker.NO_WATCHLIST, last.error)
        }
}
