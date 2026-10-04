package dev.bitstorm.sashimi.core.playback

import dev.bitstorm.sashimi.core.model.MediaSourceInfo
import dev.bitstorm.sashimi.core.network.JellyfinClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the engine actually puts on the wire, checked against the server behaviour seen live. */
class NegotiationBodyTest {
    private val bodies = mutableListOf<String>()
    private val paths = mutableListOf<String>()

    private fun engine(respond: (Request) -> String): PlaybackEngine {
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    synchronized(paths) {
                        paths += request.url.encodedPath
                        request.body?.let { b -> bodies += Buffer().also(b::writeTo).readUtf8() }
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("ok")
                        .body(respond(request).toResponseBody("application/json".toMediaType()))
                        .build()
                }.build()
        val client = JellyfinClient("dev", httpClient = http).apply { configure("https://remote.example", "t", "u") }
        return PlaybackEngine(client, DeviceProfileBuilder(FixedCodecCapabilities(emptySet())))
    }

    private fun widthCaps(body: String): List<String> =
        Json.parseToJsonElement(body).jsonObject.getValue("DeviceProfile").jsonObject.getValue("CodecProfiles").jsonArray
            .filter { it.jsonObject["Codec"] == null }
            .flatMap { it.jsonObject.getValue("Conditions").jsonArray }
            .map { it.jsonObject.getValue("Value").jsonPrimitive.content }

    @Test
    fun `every negotiation names its media source and sends SubtitleStreamIndex -1`() =
        runBlocking {
            engine { DIRECT_PLAY }.negotiate(itemId = "item-1", maxBitrate = 8_000_000, audioStreamIndex = 2)
            val body = Json.parseToJsonElement(bodies.single()).jsonObject
            assertEquals("item-1", body.getValue("MediaSourceId").jsonPrimitive.content)
            assertEquals(-1, body.getValue("SubtitleStreamIndex").jsonPrimitive.int)
            assertEquals(2, body.getValue("AudioStreamIndex").jsonPrimitive.int)
        }

    @Test
    fun `unmeasured remote Auto asks for 4 Mbps, and a video re-encode is asked again at the width 4 Mbps carries`() =
        runBlocking {
            val engine =
                engine { r ->
                    when {
                        r.url.encodedPath.endsWith("/BitrateTest") -> ""
                        else -> TRANSCODE_1080
                    }
                }
            val source = engine.negotiate(itemId = "item-1")
            val posts = bodies.filter { it.contains("DeviceProfile") }
            assertEquals(2, posts.size)
            posts.forEach {
                assertEquals(
                    4_000_000,
                    Json.parseToJsonElement(it).jsonObject.getValue("MaxStreamingBitrate").jsonPrimitive.int,
                )
            }
            assertTrue(widthCaps(posts[0]).isEmpty())
            assertEquals(listOf("854"), widthCaps(posts[1]))
            assertEquals(4_000_000, source.negotiatedCap)
            assertEquals(listOf("audio codec", "bitrate limit"), source.transcodeReasons)
            assertTrue("probe went to the server: $paths", paths.any { it.endsWith("/Playback/BitrateTest") })
        }

    @Test
    fun `transcode reasons come from the TranscodingUrl when the field is absent`() {
        val source =
            MediaSourceInfo(
                id = "abc",
                transcodingUrl =
                    "/videos/abc/master.m3u8?VideoCodec=h264" +
                        "&TranscodeReasons=AudioCodecNotSupported%2CContainerBitrateExceedsLimit",
            )
        assertEquals(listOf("AudioCodecNotSupported", "ContainerBitrateExceedsLimit"), TranscodeReasons.of(source))
        assertEquals(
            listOf("VideoCodecNotSupported"),
            TranscodeReasons.of(source.copy(transcodeReasons = listOf("VideoCodecNotSupported"))),
        )
        assertEquals(null, TranscodeReasons.of(MediaSourceInfo(id = "x")))
    }

    private companion object {
        const val DIRECT_PLAY = """{"MediaSources":[{"Id":"item-1","Container":"mp4","MediaStreams":[]}],"PlaySessionId":"ps"}"""
        const val TRANSCODE_1080 =
            """{"MediaSources":[{"Id":"item-1","Container":"mkv",
            "TranscodingUrl":"/videos/item-1/master.m3u8?VideoBitrate=3616000&AudioBitrate=384000&TranscodeReasons=AudioCodecNotSupported,ContainerBitrateExceedsLimit",
            "MediaStreams":[{"Type":"Video","Codec":"h264","Width":1920,"Height":1080,"BitRate":13000000}]}],"PlaySessionId":"ps"}"""
    }
}
