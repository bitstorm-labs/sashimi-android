package dev.bitstorm.sashimi.core.playback

import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.network.JellyfinError
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Playback for a title on a non-active server runs entirely against that server. */
class ServerScopedPlaybackTest {
    private val requests = mutableListOf<Request>()

    private fun http(respond: (Request) -> Pair<Int, String>): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                synchronized(requests) { requests += request }
                val (code, body) = respond(request)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("x")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()

    private val profiles = DeviceProfileBuilder(FixedCodecCapabilities(emptySet()))

    private fun clients(respond: (Request) -> Pair<Int, String>): Pair<JellyfinClient, JellyfinClient> {
        val shared = JellyfinClient("dev", httpClient = http(respond))
        shared.configure("https://active.example", "active-token", "active-user")
        val other = shared.forServer("https://other.example", "other-token", "other-user")
        return shared to other
    }

    @Test
    fun `negotiation and the stream url use the item's server, not the active one`() =
        runBlocking {
            val (shared, other) = clients { 200 to DIRECT_PLAY }
            val engine = PlaybackEngine(shared, profiles).withClient(other)

            val source = engine.negotiate(itemId = "item-1")

            val negotiation = requests.single()
            assertEquals("other.example", negotiation.url.host)
            assertEquals("/Items/item-1/PlaybackInfo", negotiation.url.encodedPath)
            assertEquals("other-user", negotiation.url.queryParameter("UserId"))
            assertTrue(negotiation.header("Authorization")!!.contains("Token=\"other-token\""))

            val stream = source.streamUrl.toHttpUrl()
            assertEquals("other.example", stream.host)
            assertEquals("other-token", stream.queryParameter("api_key"))
            assertEquals("other.example", engine.subtitleStreamUrl("item-1", 3, "src")!!.toHttpUrl().host)

            // The shared client is untouched.
            assertEquals("https://active.example", shared.currentServerUrl)
            assertEquals("active-token", shared.currentAccessToken)
        }

    @Test
    fun `a transcode on the item's server is torn down there`() =
        runBlocking {
            val (shared, other) = clients { 200 to "" }
            PlaybackEngine(shared, profiles).withClient(other).stopTranscode("ps-1")
            assertEquals("other.example", requests.single().url.host)
            assertEquals("/Videos/ActiveEncodings", requests.single().url.encodedPath)
        }

    @Test
    fun `withClient on the engine's own client is the same engine`() {
        val (shared, _) = clients { 200 to "" }
        val engine = PlaybackEngine(shared, profiles)
        assertSame(engine, engine.withClient(shared))
    }

    @Test
    fun `start, progress and stop are reported to the item's server`() =
        runBlocking {
            val (_, other) = clients { 204 to "" }
            val reporter =
                ProgressReporter(
                    client = other,
                    itemId = "item-1",
                    playSessionId = "ps-1",
                    reportedPlayMethod = "DirectPlay",
                    resumePositionTicks = 0,
                )
            reporter.reportStart(0)
            reporter.reportProgress(50_000_000, isPaused = false)
            reporter.reportStopped(60_000_000)

            assertTrue(requests.isNotEmpty())
            requests.forEach { assertEquals("other.example", it.url.host) }
            assertEquals(
                listOf("/Sessions/Playing", "/Sessions/Playing/Progress", "/Sessions/Playing/Stopped"),
                requests.map { it.url.encodedPath },
            )
        }

    @Test
    fun `a 401 while playing from another server never signs out the active one`() =
        runBlocking {
            val (shared, other) = clients { 401 to "" }
            var expiredFor: String? = null
            shared.sessionExpiredHandler = { expiredFor = it }

            try {
                PlaybackEngine(shared, profiles).withClient(other).negotiate(itemId = "item-1")
                fail("expected SessionExpired")
            } catch (e: JellyfinError.SessionExpired) {
                // expected: the failure is the other server's, and stays there
            }
            try {
                other.markPlayed("item-1")
                fail("expected SessionExpired")
            } catch (e: JellyfinError.SessionExpired) {
                // expected
            }

            assertNull(expiredFor)
            assertEquals("https://active.example", shared.currentServerUrl)
        }

    private companion object {
        const val DIRECT_PLAY =
            """{"MediaSources":[{"Id":"src","Container":"mp4","SupportsDirectPlay":true}],"PlaySessionId":"ps-1"}"""
    }
}
