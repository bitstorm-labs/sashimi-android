package dev.bitstorm.sashimi.core.playback

import dev.bitstorm.sashimi.core.herotrailer.HeroTrailerCaps
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

    private fun engine(
        serverUrl: String = "https://remote.example",
        throughput: SustainedThroughputStore? = null,
        linkKind: LinkKind = LinkKind.WIRED,
        respond: (Request) -> String,
    ): PlaybackEngine {
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
        val client = JellyfinClient("dev", httpClient = http).apply { configure(serverUrl, "t", "u") }
        return PlaybackEngine(
            client,
            DeviceProfileBuilder(FixedCodecCapabilities(emptySet())),
            throughput = throughput,
            linkKind = { linkKind },
        )
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

    private fun postedBitrates() =
        bodies.filter { it.contains("DeviceProfile") }.map {
            Json.parseToJsonElement(it).jsonObject.getValue("MaxStreamingBitrate").jsonPrimitive.int
        }

    @Test
    fun `local Auto over Wi-Fi with nothing remembered asks for the smooth 4K bitrate, wired for the full cap`() =
        runBlocking {
            val lan = "http://192.168.86.151:9096"
            engine(lan, SustainedThroughputStore(InMemoryThroughputPersistence()), LinkKind.WIRELESS) { DIRECT_PLAY }
                .negotiate(itemId = "item-1")
            assertEquals(listOf(24_000_000), postedBitrates())
            bodies.clear()
            engine(lan, SustainedThroughputStore(InMemoryThroughputPersistence()), LinkKind.WIRED) { DIRECT_PLAY }
                .negotiate(itemId = "item-1")
            assertEquals(listOf(100_000_000), postedBitrates())
        }

    @Test
    fun `a Wi-Fi link that has sustained 120 Mbps is trusted with 60, and the source bitrate comes back`() =
        runBlocking {
            val lan = "http://192.168.86.151:9096"
            val store = SustainedThroughputStore(InMemoryThroughputPersistence())
            store.record(lan, LinkKind.WIRELESS, 120_000_000, SustainedThroughputStore.MINIMUM_BYTES)
            // An empty probe body measures nothing, so the cap stays the LAN default.
            val engine =
                engine(lan, store, LinkKind.WIRELESS) { r -> if (r.url.encodedPath.endsWith("/BitrateTest")) "" else DIRECT_PLAY_44 }
            val source = engine.negotiate(itemId = "item-1")
            assertEquals(listOf(60_000_000), postedBitrates())
            assertEquals(60_000_000, source.negotiatedCap)
            assertEquals(44_268_472, source.sourceBitrate)
            assertEquals(PlaybackEngine.AutoStatus(100_000_000, LinkKind.WIRELESS, 120_000_000), engine.autoStatus())
        }

    @Test
    fun `an explicit cap ignores the link memory`() =
        runBlocking {
            val store = SustainedThroughputStore(InMemoryThroughputPersistence())
            engine("http://192.168.86.151:9096", store, LinkKind.WIRELESS) { DIRECT_PLAY }
                .negotiate(itemId = "item-1", maxBitrate = BitrateResolver.NO_CAP)
            assertEquals(listOf(BitrateResolver.NO_CAP), postedBitrates())
        }

    @Test
    fun `recorded readings land under the engine's server and current link`() {
        val store = SustainedThroughputStore(InMemoryThroughputPersistence())
        val lan = "http://192.168.86.151:9096"
        engine(lan, store, LinkKind.METERED) { DIRECT_PLAY }.recordThroughput(9_000_000, SustainedThroughputStore.MINIMUM_BYTES)
        assertEquals(9_000_000, store.sustainedBitrate(lan, LinkKind.METERED))
        assertEquals(null, store.sustainedBitrate(lan, LinkKind.WIRELESS))
    }

    @Test
    fun `a transcode whose master carries fallback variants plays the primary variant's media playlist`() =
        runBlocking {
            val source =
                engine { r ->
                    when {
                        r.url.encodedPath.endsWith("/master.m3u8") -> FALLBACK_MASTER
                        else -> TRANSCODE_1080
                    }
                }.negotiate(itemId = "item-1", maxBitrate = 8_000_000)
            assertTrue(source.streamUrl, source.streamUrl.startsWith("https://remote.example/videos/item-1/main.m3u8?VideoCodec=h264,hevc"))
            assertTrue("master was read: $paths", paths.any { it.endsWith("/master.m3u8") })
        }

    @Test
    fun `an unreadable or plain master is played as before`() =
        runBlocking {
            val source = engine { TRANSCODE_1080 }.negotiate(itemId = "item-1", maxBitrate = 8_000_000)
            assertTrue(source.streamUrl, source.streamUrl.startsWith("https://remote.example/videos/item-1/master.m3u8"))
        }

    @Test
    fun `the hero trailer stream is a forced small transcode with no video copy`() =
        runBlocking {
            val stream = engine { TRANSCODE_1080 }.heroTrailerStream("trailer-1", HeroTrailerCaps.forWidth(isCompactWidth = true))
            val body = Json.parseToJsonElement(bodies.single()).jsonObject
            assertEquals(4_000_000, body.getValue("MaxStreamingBitrate").jsonPrimitive.int)
            assertEquals("false", body.getValue("EnableDirectPlay").jsonPrimitive.content)
            assertEquals("false", body.getValue("EnableDirectStream").jsonPrimitive.content)
            assertEquals("true", body.getValue("EnableTranscoding").jsonPrimitive.content)
            assertEquals("false", body.getValue("AllowVideoStreamCopy").jsonPrimitive.content)
            assertEquals(-1, body.getValue("SubtitleStreamIndex").jsonPrimitive.int)
            assertEquals(listOf("1280"), widthCaps(bodies.single()))
            assertTrue("no bandwidth probe for a fixed cap: $paths", paths.none { it.endsWith("/BitrateTest") })
            assertTrue(stream!!.url.startsWith("https://remote.example/videos/item-1/master.m3u8"))
            assertEquals("ps", stream.playSessionId)
        }

    @Test
    fun `the wide hero trailer asks for 1920 at 8 Mbps`() =
        runBlocking {
            engine { TRANSCODE_1080 }.heroTrailerStream("trailer-1", HeroTrailerCaps.forWidth(isCompactWidth = false))
            val body = Json.parseToJsonElement(bodies.single()).jsonObject
            assertEquals(8_000_000, body.getValue("MaxStreamingBitrate").jsonPrimitive.int)
            assertEquals(listOf("1920"), widthCaps(bodies.single()))
            assertEquals("false", body.getValue("AllowVideoStreamCopy").jsonPrimitive.content)
        }

    @Test
    fun `no hero trailer stream when the server offers no transcode`() =
        runBlocking {
            assertEquals(null, engine { DIRECT_PLAY }.heroTrailerStream("trailer-1", HeroTrailerCaps.COMPACT))
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
        const val FALLBACK_MASTER =
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=46449896,VIDEO-RANGE=PQ\nmain.m3u8?VideoCodec=h264,hevc\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=46449896,VIDEO-RANGE=SDR\nmain.m3u8?VideoCodec=h264&AllowVideoStreamCopy=false\n"
        const val DIRECT_PLAY_44 =
            """{"MediaSources":[{"Id":"item-1","Container":"mkv","Bitrate":44268472,"MediaStreams":[]}],""" +
                """"PlaySessionId":"ps"}"""
        const val DIRECT_PLAY = """{"MediaSources":[{"Id":"item-1","Container":"mp4","MediaStreams":[]}],"PlaySessionId":"ps"}"""
        const val TRANSCODE_1080 =
            """{"MediaSources":[{"Id":"item-1","Container":"mkv",
            "TranscodingUrl":"/videos/item-1/master.m3u8?VideoBitrate=3616000&AudioBitrate=384000&TranscodeReasons=AudioCodecNotSupported,ContainerBitrateExceedsLimit",
            "MediaStreams":[{"Type":"Video","Codec":"h264","Width":1920,"Height":1080,"BitRate":13000000}]}],"PlaySessionId":"ps"}"""
    }
}
