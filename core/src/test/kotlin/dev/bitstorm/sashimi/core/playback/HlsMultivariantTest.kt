package dev.bitstorm.sashimi.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pinning the primary variant when Jellyfin adds HDR to SDR fallbacks (#155; Apple #443). */
class HlsMultivariantTest {
    private val master = "http://192.168.86.151:9096/videos/2377/master.m3u8?MediaSourceId=2377&VideoCodec=h264,hevc"

    /** The shape the live server returned for "300" with HEVC copy allowed (query trimmed). */
    private val hdrCopyMaster =
        """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=46449896,VIDEO-RANGE=PQ,CODECS="hvc1.2.4.L153.B0,mp4a.40.2",SUPPLEMENTAL-CODECS="dvh1.08.06/db1p",RESOLUTION=3840x1600
        main.m3u8?MediaSourceId=2377&VideoCodec=h264,hevc&hevc-rangetype=SDR,HDR10
        #EXT-X-STREAM-INF:BANDWIDTH=46449896,VIDEO-RANGE=SDR,CODECS="hvc1.2.4.L150.B0,mp4a.40.2",RESOLUTION=3840x1600
        main.m3u8?MediaSourceId=2377&VideoCodec=hevc&AllowVideoStreamCopy=false
        #EXT-X-STREAM-INF:BANDWIDTH=46449896,VIDEO-RANGE=SDR,CODECS="avc1.424029,mp4a.40.2",RESOLUTION=3840x1600
        main.m3u8?MediaSourceId=2377&VideoCodec=h264&AllowVideoStreamCopy=false
        #EXT-X-IMAGE-STREAM-INF:BANDWIDTH=8858,RESOLUTION=320x132,CODECS="jpeg",URI="Trickplay/320/tiles.m3u8?MediaSourceId=2377"
        """.trimIndent()

    @Test
    fun `reads every stream-inf with its uri, not the image stream`() {
        val variants = HlsMultivariant.variants(hdrCopyMaster)
        assertEquals(3, variants.size)
        assertEquals(listOf(false, true, true), variants.map { it.isStreamCopyFallback })
    }

    @Test
    fun `fallback variants around a copy pin the copy's media playlist`() {
        assertEquals(
            "http://192.168.86.151:9096/videos/2377/main.m3u8?MediaSourceId=2377&VideoCodec=h264,hevc&hevc-rangetype=SDR,HDR10",
            HlsMultivariant.primaryVariantUrl(hdrCopyMaster, master),
        )
    }

    @Test
    fun `a single-variant master or an ladder without fallbacks is played as it is`() {
        val single = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=8000000\nmain.m3u8?VideoCodec=h264\n"
        assertNull(HlsMultivariant.primaryVariantUrl(single, master))
        val ladder = single + "#EXT-X-STREAM-INF:BANDWIDTH=4000000\nmain.m3u8?VideoCodec=h264&VideoBitrate=4000000\n"
        assertNull(HlsMultivariant.primaryVariantUrl(ladder, master))
    }

    @Test
    fun `a master of nothing but forced re-encodes has no primary to pin`() {
        val forced = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nmain.m3u8?AllowVideoStreamCopy=false\n"
        assertNull(HlsMultivariant.primaryVariantUrl(forced, master))
    }

    @Test
    fun `relative, rooted and absolute uris resolve against the master, keeping a base path`() {
        val base = "https://host.example:8920/jf/videos/1/master.m3u8?a=b"
        assertEquals("https://host.example:8920/jf/videos/1/main.m3u8?x=1", HlsMultivariant.resolve(base, "main.m3u8?x=1"))
        assertEquals("https://host.example:8920/other.m3u8", HlsMultivariant.resolve(base, "/other.m3u8"))
        assertEquals("https://cdn.example/a.m3u8", HlsMultivariant.resolve(base, "https://cdn.example/a.m3u8"))
    }

    @Test
    fun `only a master playlist is worth reading first`() {
        assertTrue(HlsMultivariant.isMultivariant(master))
        assertFalse(HlsMultivariant.isMultivariant("http://h/videos/1/main.m3u8?x=master.m3u8"))
        assertFalse(HlsMultivariant.isMultivariant("http://h/Videos/1/stream.mkv?static=true"))
    }
}
