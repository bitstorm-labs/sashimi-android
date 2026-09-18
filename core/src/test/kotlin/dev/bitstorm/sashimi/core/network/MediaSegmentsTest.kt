package dev.bitstorm.sashimi.core.network

import dev.bitstorm.sashimi.core.model.MediaSegmentType
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Skip segments come from Jellyfin's native `/MediaSegments/{id}` first, with
 * Intro Skipper's private `/Episode/{id}/IntroSkipperSegments` as a fallback.
 * Intro Skipper 12 dropped the private route (404), which is how every episode
 * lost its skip button while the native API had the data all along.
 */
class MediaSegmentsTest {
    private val itemId = "ep-1"
    private val nativePath = "/MediaSegments/$itemId"
    private val legacyPath = "/Episode/$itemId/IntroSkipperSegments"

    private val nativeBody =
        """
        {
          "Items": [
            { "Id": "seg-intro", "ItemId": "ep-1", "Type": "Intro", "StartTicks": 5280000000, "EndTicks": 6346340000 },
            { "Id": "seg-outro", "ItemId": "ep-1", "Type": "Outro", "StartTicks": 28090000000, "EndTicks": 28708270000 },
            { "Id": "seg-ad", "ItemId": "ep-1", "Type": "Commercial", "StartTicks": 10000000000, "EndTicks": 10300000000 }
          ],
          "TotalRecordCount": 3,
          "StartIndex": 0
        }
        """.trimIndent()

    private val legacyBody =
        """{ "Introduction": { "Start": 0, "End": 90 }, "Credits": { "Start": 1200, "End": 1300 } }"""

    /** Canned responses per path; records every path requested, in order. */
    private class FakeServer(private val routes: Map<String, Pair<Int, String>>) : Interceptor {
        val requested = mutableListOf<String>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requested += request.url.encodedPath
            val (code, body) = routes[request.url.encodedPath] ?: (404 to "")
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Not Found")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun client(server: FakeServer): JellyfinClient =
        JellyfinClient(
            deviceId = "test-device",
            httpClient = OkHttpClient.Builder().addInterceptor(server).build(),
        ).apply { configure("https://jelly.example.com", "token", "user-1") }

    @Test
    fun `native segments are used and the legacy route is never asked`() =
        runTest {
            val server = FakeServer(mapOf(nativePath to (200 to nativeBody), legacyPath to (200 to legacyBody)))
            val segments = client(server).getMediaSegments(itemId)

            assertEquals(listOf(nativePath), server.requested)
            assertEquals(listOf(MediaSegmentType.INTRO, MediaSegmentType.OUTRO, MediaSegmentType.UNKNOWN), segments.map { it.type })
            assertEquals("seg-intro", segments[0].id)
        }

    @Test
    fun `ticks convert to seconds without losing the fraction`() =
        runTest {
            val server = FakeServer(mapOf(nativePath to (200 to nativeBody)))
            val intro = client(server).getMediaSegments(itemId).first()

            assertEquals(528.0, intro.startSeconds, 1e-9)
            assertEquals(634.634, intro.endSeconds, 1e-9)
        }

    @Test
    fun `commercial segments map to UNKNOWN so the tracker never offers to skip them`() =
        runTest {
            val server = FakeServer(mapOf(nativePath to (200 to nativeBody)))
            val ad = client(server).getMediaSegments(itemId).single { it.id == "seg-ad" }

            assertEquals(MediaSegmentType.UNKNOWN, ad.type)
        }

    @Test
    fun `a pre-10_10 server (native 404) falls back to the plugin route`() =
        runTest {
            val server = FakeServer(mapOf(legacyPath to (200 to legacyBody)))
            val segments = client(server).getMediaSegments(itemId)

            assertEquals(listOf(nativePath, legacyPath), server.requested)
            assertEquals(setOf(MediaSegmentType.INTRO, MediaSegmentType.OUTRO), segments.map { it.type }.toSet())
            assertEquals(90.0, segments.single { it.type == MediaSegmentType.INTRO }.endSeconds, 1e-9)
        }

    @Test
    fun `an empty native answer still consults the plugin route`() =
        runTest {
            val empty = """{ "Items": [], "TotalRecordCount": 0, "StartIndex": 0 }"""
            val server = FakeServer(mapOf(nativePath to (200 to empty), legacyPath to (200 to legacyBody)))
            val segments = client(server).getMediaSegments(itemId)

            assertEquals(listOf(nativePath, legacyPath), server.requested)
            assertEquals(2, segments.size)
        }

    @Test
    fun `no segments anywhere is an empty list, not an error`() =
        runTest {
            val server = FakeServer(emptyMap())
            val segments = client(server).getMediaSegments(itemId)

            assertTrue(segments.isEmpty())
        }
}
