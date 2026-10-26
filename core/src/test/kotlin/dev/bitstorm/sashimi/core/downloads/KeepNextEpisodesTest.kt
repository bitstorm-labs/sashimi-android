package dev.bitstorm.sashimi.core.downloads

import dev.bitstorm.sashimi.core.network.JellyfinClient
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of sashimi-apple KeepNextEpisodesPlannerTests: same cases, same expectations. */
class KeepNextEpisodesPlannerTest {
    private val downloadedAt = 1_000_000_000L
    private val before = downloadedAt - 3_600_000
    private val after = downloadedAt + 3_600_000

    private fun episode(
        id: String,
        special: Boolean = false,
        played: Boolean = false,
        lastPlayed: Long? = null,
    ) = KeepNextEpisodesPlanner.Episode(id, special, played, lastPlayed)

    private fun download(
        id: String,
        watchedLocally: Boolean = false,
    ) = KeepNextEpisodesPlanner.Download(id, downloadedAt, watchedLocally)

    private fun plan(
        episodes: List<KeepNextEpisodesPlanner.Episode>,
        downloads: List<KeepNextEpisodesPlanner.Download> = emptyList(),
        count: Int,
    ) = KeepNextEpisodesPlanner.plan(episodes, downloads, count)

    @Test
    fun `a fresh show queues the first N regular episodes`() {
        val episodes = listOf(episode("s0e1", special = true)) + (1..4).map { episode("e$it") }
        val plan = plan(episodes, count = 3)
        assertEquals(listOf("e1", "e2", "e3"), plan.enqueue)
        assertEquals(emptyList<String>(), plan.delete)
    }

    @Test
    fun `the window starts after the last watched episode and includes the in-progress one`() {
        val episodes =
            listOf(
                episode("e1", played = true, lastPlayed = before),
                episode("e2"),
                episode("e3", played = true, lastPlayed = before),
                episode("e4"),
                episode("e5"),
                episode("e6"),
            )
        assertEquals(listOf("e4", "e5"), plan(episodes, count = 2).enqueue)
    }

    @Test
    fun `existing downloads count toward the window`() {
        val episodes = (1..5).map { episode("e$it") }
        val plan = plan(episodes, listOf(download("e1"), download("e3")), count = 3)
        assertEquals(listOf("e2"), plan.enqueue)
        assertEquals(emptyList<String>(), plan.delete)
    }

    @Test
    fun `nothing to do when the window is already downloaded`() {
        val episodes = (1..4).map { episode("e$it") }
        assertTrue(plan(episodes, listOf(download("e1"), download("e2")), count = 2).isEmpty)
    }

    @Test
    fun `off plans nothing`() {
        val episodes = listOf(episode("e1", played = true, lastPlayed = after), episode("e2"))
        assertTrue(plan(episodes, listOf(download("e1")), count = 0).isEmpty)
    }

    @Test
    fun `the window stops at the end of the show`() {
        val episodes = listOf(episode("e1", played = true, lastPlayed = before), episode("e2"))
        assertEquals(listOf("e2"), plan(episodes, count = 5).enqueue)
    }

    @Test
    fun `a finished episode is deleted and the next one queued`() {
        val episodes = listOf(episode("e1", played = true, lastPlayed = after)) + (2..4).map { episode("e$it") }
        val plan = plan(episodes, listOf(download("e1"), download("e2"), download("e3")), count = 3)
        assertEquals(listOf("e1"), plan.delete)
        assertEquals(listOf("e4"), plan.enqueue)
    }

    @Test
    fun `an episode watched offline is deleted before the server knows`() {
        val episodes = (1..3).map { episode("e$it") }
        val plan = plan(episodes, listOf(download("e1", watchedLocally = true), download("e2")), count = 2)
        assertEquals(listOf("e1"), plan.delete)
        assertEquals(listOf("e3"), plan.enqueue)
    }

    @Test
    fun `an episode downloaded after it was watched is kept`() {
        val episodes = listOf(episode("e1", played = true, lastPlayed = before), episode("e2"))
        assertEquals(emptyList<String>(), plan(episodes, listOf(download("e1"), download("e2")), count = 1).delete)
    }

    @Test
    fun `a played episode with no date is kept`() {
        val episodes = listOf(episode("e1", played = true, lastPlayed = null), episode("e2"))
        val plan = plan(episodes, listOf(download("e1")), count = 1)
        assertEquals(emptyList<String>(), plan.delete)
        assertEquals(listOf("e2"), plan.enqueue)
    }

    @Test
    fun `unwatched downloads beyond the window are not deleted`() {
        val episodes = (1..6).map { episode("e$it") }
        assertTrue(plan(episodes, (1..6).map { download("e$it") }, count = 1).isEmpty)
    }

    @Test
    fun `a download played to 90 percent offline counts as watched`() {
        val row =
            DownloadedItemEntity(itemId = "e1", name = "e1", runTimeTicks = 1000, localPositionTicks = 900, dateAdded = downloadedAt)
        assertTrue(KeepNextEpisodesPlanner.download(row).isWatchedLocally)
        assertTrue(!KeepNextEpisodesPlanner.download(row.copy(localPositionTicks = 899)).isWatchedLocally)
    }
}

class KeepNextEpisodesStoreTest {
    @Test
    fun `the setting is per server and series and survives a relaunch`() {
        val persistence = InMemoryKeepNextEpisodesPersistence()
        KeepNextEpisodesStore(persistence).set(3, serverId = "serverA", seriesId = "show", quality = DownloadQuality.MEDIUM)

        val relaunched = KeepNextEpisodesStore(persistence)

        assertEquals(3, relaunched.count("serverA", "show"))
        assertEquals(DownloadQuality.MEDIUM, relaunched.setting("serverA", "show")?.downloadQuality)
        assertEquals(0, relaunched.count("serverB", "show"))
        assertEquals(0, relaunched.count(null, "show"))
    }

    @Test
    fun `changing the count keeps the quality and off removes it`() {
        val persistence = InMemoryKeepNextEpisodesPersistence()
        val store = KeepNextEpisodesStore(persistence)
        store.set(3, "s", "show", DownloadQuality.LOW)

        store.set(5, "s", "show")
        assertEquals(DownloadQuality.LOW, store.setting("s", "show")?.downloadQuality)
        assertEquals(5, store.count("s", "show"))

        store.set(0, "s", "show")
        assertNull(store.setting("s", "show"))
        assertTrue(KeepNextEpisodesStore(persistence).active.isEmpty())
    }
}

private class EpisodesServer(
    private val body: String,
) : Interceptor {
    var calls = 0

    override fun intercept(chain: Interceptor.Chain): Response {
        calls++
        val ok = chain.request().url.encodedPath == "/Shows/show/Episodes"
        return Response
            .Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(if (ok) 200 else 404)
            .message("stub")
            .body((if (ok) body else "").toResponseBody("application/json".toMediaType()))
            .build()
    }
}

class KeepNextEpisodesSyncTest {
    // e1 finished after its download; e2 downloaded; e3, e4 not.
    private val episodesJson =
        """{"Items":[
        {"Id":"e1","Name":"e1","Type":"Episode","SeriesId":"show","ParentIndexNumber":1,"IndexNumber":1,
         "UserData":{"Played":true,"LastPlayedDate":"2026-10-20T10:00:00Z"}},
        {"Id":"e2","Name":"e2","Type":"Episode","SeriesId":"show","ParentIndexNumber":1,"IndexNumber":2,"UserData":{"Played":false}},
        {"Id":"e3","Name":"e3","Type":"Episode","SeriesId":"show","ParentIndexNumber":1,"IndexNumber":3,"UserData":{"Played":false}},
        {"Id":"e4","Name":"e4","Type":"Episode","SeriesId":"show","ParentIndexNumber":1,"IndexNumber":4,"UserData":{"Played":false}}
        ],"TotalRecordCount":4}"""

    private val downloadedAt =
        java.time.Instant
            .parse("2026-10-19T10:00:00Z")
            .toEpochMilli()
    private val server = EpisodesServer(episodesJson)
    private val store = KeepNextEpisodesStore(InMemoryKeepNextEpisodesPersistence())
    private val enqueued = mutableListOf<Pair<List<String>, DownloadQuality>>()
    private val deleted = mutableListOf<DownloadKey>()
    private var online = true

    private fun row(id: String) =
        DownloadedItemEntity(itemId = id, name = id, seriesId = "show", serverId = "srv", dateAdded = downloadedAt)

    private fun TestScope.sync() =
        KeepNextEpisodesSync(
            store = store,
            clientFor = { id ->
                if (id != "srv") {
                    null
                } else {
                    JellyfinClient(deviceId = "t", httpClient = OkHttpClient.Builder().addInterceptor(server).build())
                        .also { it.configure("http://stub.local", "token", "u1") }
                }
            },
            downloads = { listOf(row("e1"), row("e2")) },
            enqueue = { items, quality, _ -> enqueued += items.map { it.id } to quality },
            delete = { deleted += it },
            isOnline = { online },
            scope = this,
        )

    @Test
    fun `a sync deletes the finished episode and queues the next at the setting's quality`() =
        runTest {
            store.set(2, "srv", "show", DownloadQuality.LOW)

            sync().runSync()

            assertEquals(listOf(DownloadKey.of("srv", "e1")), deleted)
            assertEquals(listOf(listOf("e3") to DownloadQuality.LOW), enqueued)
        }

    @Test
    fun `nothing runs offline or while a player is open`() =
        runTest {
            store.set(2, "srv", "show", DownloadQuality.LOW)
            val sync = sync()

            online = false
            sync.runSync()
            online = true
            sync.playerOpened(this)
            sync.runSync()

            assertEquals(0, server.calls)
            assertTrue(deleted.isEmpty() && enqueued.isEmpty())
        }

    @Test
    fun `closing the player schedules a sync`() =
        runTest {
            store.set(2, "srv", "show", DownloadQuality.LOW)
            val sync = sync()
            sync.playerOpened(this)

            val scheduled = sync.playerClosed(this)
            advanceUntilIdle()
            // The episodes call runs on OkHttp's own threads, outside virtual time.
            scheduled?.join()

            assertEquals(listOf(listOf("e3") to DownloadQuality.LOW), enqueued)
        }

    @Test
    fun `no active settings means no server calls`() =
        runTest {
            sync().scheduleSync(0)
            advanceUntilIdle()
            assertEquals(0, server.calls)
        }
}
