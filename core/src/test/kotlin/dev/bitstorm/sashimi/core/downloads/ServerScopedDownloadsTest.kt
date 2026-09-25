package dev.bitstorm.sashimi.core.downloads

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.session.ServerConfig
import dev.bitstorm.sashimi.core.session.ServerScopedClients
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Downloads remember their server and talk only to it. */
class ServerScopedDownloadsTest {
    private val shared = JellyfinClient("device-1").apply { configure("https://active.example", "active-token", "active-user") }
    private val other = ServerConfig("b", "Other", "https://other.example", "u", "ub")
    private var tokens = mapOf("b" to "other-token")
    private val clients =
        ServerScopedClients(
            shared = shared,
            activeServerId = { "a" },
            servers = { listOf(ServerConfig("a", "Active", "https://active.example", "u", "active-user"), other) },
            tokenFor = { tokens[it] },
        ) { server, token -> shared.forServer(server.url, token, server.userId) }

    private val movie = BaseItemDto(id = "m1", name = "Movie", type = ItemType.MOVIE, productionYear = 2020)

    @Test
    fun `a queued download records the server it came from`() {
        val row = DownloadRecords.queued(movie, DownloadQuality.HIGH, serverId = "b", now = 42)
        assertEquals("b", row.serverId)
        assertEquals("m1", row.itemId)
        assertEquals(DownloadStatus.QUEUED, row.downloadStatus)
        assertEquals(DownloadQuality.HIGH, row.downloadQuality)
        assertEquals(42L, row.dateAdded)
    }

    @Test
    fun `a download from another server fetches with that server's url and token`() {
        val row = DownloadRecords.queued(movie, DownloadQuality.HIGH, serverId = "b", now = 0)
        val spec = DownloadUrlBuilder.requestFor(clients.forRecord(row.serverId)!!, row.itemId, row.downloadQuality)!!

        val url = spec.url.toHttpUrl()
        assertEquals("other.example", url.host)
        assertEquals("/Videos/m1/stream.mp4", url.encodedPath)
        assertEquals("device-1", url.queryParameter("DeviceId"))
        assertEquals("other-token", spec.accessToken)
        // The token rides in a header, never the URL.
        assertNull(url.queryParameter("api_key"))
    }

    @Test
    fun `a legacy download without a server keeps using the active server`() {
        val row = DownloadedItemEntity(itemId = "m1", name = "Movie", quality = DownloadQuality.ORIGINAL.wireName)
        val spec = DownloadUrlBuilder.requestFor(clients.forRecord(row.serverId)!!, row.itemId, row.downloadQuality)!!
        assertEquals("active.example", spec.url.toHttpUrl().host)
        assertEquals("active-token", spec.accessToken)
    }

    @Test
    fun `offline progress syncs to each download's own server`() =
        runBlocking {
            val rows =
                listOf(
                    DownloadedItemEntity(itemId = "on-b", name = "x", serverId = "b", localPositionTicks = 10, pendingProgressSync = true),
                    DownloadedItemEntity(itemId = "legacy", name = "y", localPositionTicks = 20, pendingProgressSync = true),
                    DownloadedItemEntity(itemId = "clean", name = "z", serverId = "b", localPositionTicks = 30),
                )
            val reported = mutableListOf<Triple<String?, String, Long>>()

            val synced =
                PendingProgressSync.sync(rows, clients::forRecord) { client, itemId, ticks ->
                    reported += Triple(client.currentServerUrl, itemId, ticks)
                }

            assertEquals(
                listOf(
                    Triple("https://other.example", "on-b", 10L),
                    Triple("https://active.example", "legacy", 20L),
                ),
                reported,
            )
            assertEquals(listOf("on-b", "legacy"), synced)
        }

    @Test
    fun `progress for a signed-out server stays pending instead of going to the active server`() =
        runBlocking {
            tokens = emptyMap()
            val rows = listOf(DownloadedItemEntity(itemId = "on-b", name = "x", serverId = "b", pendingProgressSync = true))
            val reported = mutableListOf<String?>()

            val synced = PendingProgressSync.sync(rows, clients::forRecord) { client, _, _ -> reported += client.currentServerUrl }

            assertEquals(emptyList<String?>(), reported)
            assertEquals(emptyList<String>(), synced)
        }

    @Test
    fun `a failed report stays pending`() =
        runBlocking {
            val rows = listOf(DownloadedItemEntity(itemId = "on-b", name = "x", serverId = "b", pendingProgressSync = true))
            val synced = PendingProgressSync.sync(rows, clients::forRecord) { _, _, _ -> error("offline") }
            assertEquals(emptyList<String>(), synced)
        }
}
