package dev.bitstorm.sashimi.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileBuilderTest {
    private fun build(vararg supported: String) = DeviceProfileBuilder(FixedCodecCapabilities(supported.toSet())).build(20_000_000)

    @Test
    fun `h264 always offered, hevc av1 vp9 gated off when no decoder`() {
        val profile = build() // no extra codecs
        profile.directPlayProfiles.forEach { assertEquals("h264", it.videoCodec) }
    }

    @Test
    fun `hevc offered only when decodable`() {
        val profile = build(CodecCapabilities.MimeTypes.HEVC)
        profile.directPlayProfiles.forEach {
            assertEquals("h264,hevc", it.videoCodec)
        }
    }

    @Test
    fun `all codecs offered when device supports them`() {
        val profile =
            build(
                CodecCapabilities.MimeTypes.HEVC,
                CodecCapabilities.MimeTypes.VP9,
                CodecCapabilities.MimeTypes.AV1,
            )
        profile.directPlayProfiles.forEach {
            assertEquals("h264,hevc,vp9,av1", it.videoCodec)
        }
    }

    @Test
    fun `covers mkv and webm containers`() {
        val containers = build().directPlayProfiles.map { it.container }
        assertTrue(containers.any { it.contains("mkv") && it.contains("webm") })
        assertTrue(containers.any { it.contains("mp4") })
    }

    @Test
    fun `transcode fallback targets h264 aac over hls`() {
        val t = build().transcodingProfiles.single()
        assertEquals("h264", t.videoCodec)
        assertEquals("aac", t.audioCodec)
        assertEquals("hls", t.protocol)
        assertEquals("ts", t.container)
    }

    @Test
    fun `subtitles declared external vtt and srt`() {
        val subs = build().subtitleProfiles
        assertEquals(setOf("vtt", "srt"), subs.map { it.format }.toSet())
        assertTrue(subs.all { it.method == "External" })
    }

    @Test
    fun `max streaming bitrate carried into profile`() {
        assertEquals(20_000_000, build().maxStreamingBitrate)
    }
}
