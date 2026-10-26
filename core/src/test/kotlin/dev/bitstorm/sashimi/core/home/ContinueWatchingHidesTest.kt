package dev.bitstorm.sashimi.core.home

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.model.UserItemDataDto
import dev.bitstorm.sashimi.core.network.JellyfinClient
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/** Answers "METHOD /path" from a table (unlisted = 404) and records each call with its body. */
private class RecordingServer : Interceptor {
    val routes = mutableMapOf<String, Pair<Int, String>>()
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val bodies = mutableMapOf<String, String>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val key = "${request.method} ${request.url.encodedPath}"
        calls += key
        request.body?.let { body -> bodies[key] = Buffer().also(body::writeTo).readUtf8() }
        val (status, body) = routes[key] ?: (404 to "")
        return Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("stub")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}

private fun episode(
    id: String,
    series: String,
    positionTicks: Long = 0,
) = BaseItemDto(
    id = id,
    name = id,
    type = ItemType.EPISODE,
    seriesId = series,
    userData = UserItemDataDto(playbackPositionTicks = positionTicks),
)

private fun movie(
    id: String,
    positionTicks: Long,
) = BaseItemDto(id = id, name = id, type = ItemType.MOVIE, userData = UserItemDataDto(playbackPositionTicks = positionTicks))

private fun rowJson(vararg items: Pair<String, String>): String =
    items.joinToString(prefix = """{"Items":[""", postfix = """],"TotalRecordCount":${items.size}}""") { (id, series) ->
        """{"Id":"$id","Name":"$id","Type":"Episode","SeriesId":"$series","UserData":{"PlaybackPositionTicks":0,"Played":false}}"""
    }

class HiddenSeriesRuleTest {
    @Test
    fun `a series stays hidden while its row entry is the stored episode`() {
        val row = listOf(episode("s1e3", "show"), episode("other-e1", "other"))
        val outcome = HiddenSeriesRule.apply(row, listOf(HiddenSeries("show", "s1e3")))

        assertEquals(listOf("other-e1"), outcome.visible.map { it.id })
        assertEquals(listOf(HiddenSeries("show", "s1e3")), outcome.kept)
    }

    @Test
    fun `a different episode of a hidden series means the hide is stale and the series shows`() {
        val row = listOf(episode("s1e4", "show"))
        val outcome = HiddenSeriesRule.apply(row, listOf(HiddenSeries("show", "s1e3")))

        assertEquals(listOf("s1e4"), outcome.visible.map { it.id })
        assertTrue(outcome.kept.isEmpty())
        assertEquals(listOf(HiddenSeries("show", "s1e3")), outcome.stale)
    }

    @Test
    fun `a hidden series missing from the row keeps its hide`() {
        val outcome = HiddenSeriesRule.apply(listOf(episode("x", "other")), listOf(HiddenSeries("show", "s1e3")))

        assertEquals(listOf(HiddenSeries("show", "s1e3")), outcome.kept)
        assertTrue(outcome.stale.isEmpty())
    }
}

class ContinueWatchingHidesTest {
    private val user = "u1"
    private val server = RecordingServer()
    private val store = InMemoryHiddenSeriesStore()
    private val hides = ContinueWatchingHides(store)

    private fun client() =
        JellyfinClient(
            deviceId = "test",
            httpClient = OkHttpClient.Builder().addInterceptor(server).build(),
        ).also { it.configure(serverUrl = "http://stub.local", accessToken = "token", userId = user) }

    @Test
    fun `removing a resumed movie clears only its position and hides nothing`() =
        runTest {
            server.routes["POST /UserItems/m1/UserData"] = 200 to "{}"
            val client = client()

            val result = hides.remove(client, movie("m1", positionTicks = 9_000))

            assertTrue(result is ContinueWatchingHides.Result.Removed)
            assertEquals("""{"PlaybackPositionTicks":0}""", server.bodies["POST /UserItems/m1/UserData"])
            assertFalse(server.calls.any { it.contains("PlayedItems") })
            assertFalse(server.calls.any { it.contains("/ContinueWatching/Hidden") })
            assertTrue(hides.hidden(client).isEmpty())
        }

    @Test
    fun `an older server takes the per-user UserData path`() =
        runTest {
            server.routes["POST /Users/$user/Items/m1/UserData"] = 200 to "{}"

            val result = hides.remove(client(), movie("m1", positionTicks = 9_000))

            assertTrue(result is ContinueWatchingHides.Result.Removed)
            assertEquals(listOf("POST /UserItems/m1/UserData", "POST /Users/$user/Items/m1/UserData"), server.calls)
        }

    @Test
    fun `a movie whose position could not be cleared reports failure`() =
        runTest {
            server.routes["POST /UserItems/m1/UserData"] = 500 to ""

            val result = hides.remove(client(), movie("m1", positionTicks = 9_000))

            assertTrue(result is ContinueWatchingHides.Result.Failed)
        }

    @Test
    fun `an episode is hidden on the plugin when the server has the endpoint`() =
        runTest {
            server.routes["POST /UserItems/e3/UserData"] = 200 to "{}"
            server.routes["POST /ContinueWatching/Hidden/e3"] = 204 to ""
            val client = client()

            hides.remove(client, episode("e3", "show", positionTicks = 5_000))

            assertTrue("POST /ContinueWatching/Hidden/e3" in server.calls)
            assertEquals(listOf(HiddenSeries("show", "e3", onServer = true)), hides.hidden(client))
        }

    @Test
    fun `without the plugin a Next Up episode is hidden locally and its position is left alone`() =
        runTest {
            val client = client()

            val result = hides.remove(client, episode("e3", "show"))

            assertTrue(result is ContinueWatchingHides.Result.Removed)
            assertFalse(server.calls.any { it.endsWith("/UserData") })
            assertEquals(listOf(HiddenSeries("show", "e3", onServer = false)), hides.hidden(client))
        }

    @Test
    fun `a failing plugin call still hides the series locally`() =
        runTest {
            server.routes["POST /ContinueWatching/Hidden/e3"] = 500 to ""
            val client = client()

            val result = hides.remove(client, episode("e3", "show"))

            assertTrue(result is ContinueWatchingHides.Result.Removed)
            assertEquals(listOf(HiddenSeries("show", "e3", onServer = false)), hides.hidden(client))
        }

    @Test
    fun `hides are kept per server and user`() =
        runTest {
            hides.remove(client(), episode("e3", "show"))
            val otherUser =
                JellyfinClient(deviceId = "test", httpClient = OkHttpClient.Builder().addInterceptor(server).build())
                    .also { it.configure(serverUrl = "http://stub.local", accessToken = "token", userId = "u2") }

            assertTrue(hides.hidden(otherUser).isEmpty())
        }

    @Test
    fun `undo restores the position and deletes the plugin hide`() =
        runTest {
            server.routes["POST /UserItems/e3/UserData"] = 200 to "{}"
            server.routes["POST /ContinueWatching/Hidden/e3"] = 204 to ""
            server.routes["DELETE /ContinueWatching/Hidden/e3"] = 204 to ""
            val client = client()
            val receipt =
                (hides.remove(client, episode("e3", "show", positionTicks = 5_000)) as ContinueWatchingHides.Result.Removed).receipt

            hides.undo(client, receipt)

            assertTrue("DELETE /ContinueWatching/Hidden/e3" in server.calls)
            assertEquals("""{"PlaybackPositionTicks":5000}""", server.bodies["POST /UserItems/e3/UserData"])
            assertTrue(hides.hidden(client).isEmpty())
        }

    @Test
    fun `playing any episode of a hidden series brings it back`() =
        runTest {
            val client = client()
            hides.remove(client, episode("e3", "show"))
            hides.remove(client, episode("x1", "other"))

            hides.onPlaybackStarted(client, episode("e7", "show"))

            assertEquals(listOf(HiddenSeries("other", "x1")), hides.hidden(client))
        }

    @Test
    fun `the merged row drops a hidden series and still fills from the rest`() =
        runTest {
            server.routes["GET /Users/$user/Items/Resume"] = 200 to rowJson()
            server.routes["GET /Shows/NextUp"] =
                200 to rowJson(*(0..ContinueWatchingMerger.LIMIT).map { "e$it" to "show$it" }.toTypedArray())
            server.routes["GET /Users/$user/Items"] = 200 to rowJson()
            val client = client()
            hides.remove(client, episode("e0", "show0"))

            val row = ContinueWatchingSource.load(client, hides)

            assertFalse("e0" in row.map { it.id })
            assertEquals(ContinueWatchingMerger.LIMIT, row.size)
        }

    @Test
    fun `the plugin row is filtered too, and a moved-on series returns with its hide dropped`() =
        runTest {
            val client = client()
            hides.remove(client, episode("e3", "show"))
            hides.remove(client, episode("x1", "other"))
            server.routes["GET /ContinueWatching"] = 200 to rowJson("e4" to "show", "x1" to "other", "y" to "third")

            val row = ContinueWatchingSource.load(client, hides)

            assertEquals(listOf("e4", "y"), row.map { it.id })
            assertEquals(listOf(HiddenSeries("other", "x1")), hides.hidden(client))
        }
}
