package dev.bitstorm.sashimi.core.downloads

import dev.bitstorm.sashimi.core.network.JellyfinClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The requests the downloads engine puts on the wire. A server with legacy
 * authorization off answers `X-Emby-Token` with 401 on every endpoint that
 * needs auth (measured: `/Items/{id}/Download`, `/Videos/{id}/Trickplay/...`),
 * so every request must carry the `Authorization: MediaBrowser ...` header the
 * rest of the app uses.
 */
class DownloadRequestsTest {
    private fun client(token: String? = "tok-123") =
        JellyfinClient(deviceId = "device-1").also { it.configure("https://jelly.example.com", token, "user-1") }

    private fun requestFor(quality: DownloadQuality) =
        DownloadUrlBuilder.requestFor(client(), "abc", quality)!!.let { DownloadRequests.get(it.url, it.authorization) }

    @Test
    fun `every quality authenticates with the MediaBrowser Authorization header`() {
        DownloadQuality.entries.forEach { quality ->
            val header = requestFor(quality).header("Authorization")
            assertTrue("${quality.wireName}: $header", header!!.startsWith("MediaBrowser "))
            assertTrue("${quality.wireName}: $header", header.contains("Token=\"tok-123\""))
            assertTrue("${quality.wireName}: $header", header.contains("DeviceId=\"device-1\""))
        }
    }

    @Test
    fun `no request relies on the legacy X-Emby-Token header`() {
        DownloadQuality.entries.forEach { quality ->
            val request = requestFor(quality)
            assertNull(quality.wireName, request.header("X-Emby-Token"))
            assertEquals(quality.wireName, setOf("Authorization"), request.headers.names())
        }
    }

    @Test
    fun `the token stays out of the url`() {
        DownloadQuality.entries.forEach { assertTrue("tok-123" !in requestFor(it).url.toString()) }
    }

    @Test
    fun `artwork, subtitle and other side fetches use the same header`() {
        val authorization = client().currentAuthorization!!
        val request = DownloadRequests.get("https://jelly.example.com/Items/abc/Images/Primary?maxWidth=400", authorization)
        assertEquals(authorization, request.header("Authorization"))
        assertNull(request.header("X-Emby-Token"))
    }

    @Test
    fun `a resume adds a Range from the bytes already on disk, a fresh fetch has none`() {
        val authorization = client().currentAuthorization!!
        assertNull(DownloadRequests.get("https://jelly.example.com/x", authorization).header("Range"))
        assertEquals("bytes=1024-", DownloadRequests.get("https://jelly.example.com/x", authorization, resumeFrom = 1024).header("Range"))
    }

    @Test
    fun `without a token there is no authorization and no request`() {
        assertNull(client(token = null).currentAuthorization)
        assertNull(DownloadUrlBuilder.requestFor(client(token = null), "abc", DownloadQuality.HIGH))
    }

    @Test
    fun `the header name the trickplay loader sends is Authorization`() {
        assertEquals("Authorization", JellyfinClient.AUTHORIZATION_HEADER)
    }
}
