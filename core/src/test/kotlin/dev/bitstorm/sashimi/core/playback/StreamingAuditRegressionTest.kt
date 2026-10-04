package dev.bitstorm.sashimi.core.playback

import dev.bitstorm.sashimi.core.model.MediaSourceInfo
import dev.bitstorm.sashimi.core.model.MediaStream
import dev.bitstorm.sashimi.core.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 2026-10-02 streaming audit findings, written against APIs that existed
 * before the fix so each one fails by assertion (not just by compilation) on
 * the old code.
 */
class StreamingAuditRegressionTest {
    private fun profile(vararg mimes: String) = DeviceProfileBuilder(FixedCodecCapabilities(mimes.toSet())).build(20_000_000)

    // A3

    @Test
    fun `A3 a device with no Dolby or DTS decoder is not offered that audio for direct play`() {
        profile().directPlayProfiles.forEach { dp ->
            val audio = dp.audioCodec.split(",")
            assertFalse("ac3 offered without a decoder: $audio", "ac3" in audio)
            assertFalse("eac3 offered without a decoder: $audio", "eac3" in audio)
            assertTrue("aac must always be offered: $audio", "aac" in audio)
        }
    }

    @Test
    fun `A3 a device with an AC-3 decoder is offered AC-3`() {
        profile(CodecCapabilities.MimeTypes.AC3).directPlayProfiles.forEach { dp ->
            assertTrue(dp.audioCodec, "ac3" in dp.audioCodec.split(","))
            assertFalse(dp.audioCodec, "eac3" in dp.audioCodec.split(","))
        }
    }

    // A4

    @Test
    fun `A4 every advertised video codec carries a bit-depth and range condition`() {
        val p = profile(CodecCapabilities.MimeTypes.HEVC)
        val conditions = p.codecProfiles.flatMap { it.conditions }
        // With no 10-bit or HDR capability known, both must be pinned to the safe values.
        assertTrue(conditions.toString(), conditions.any { it.property == "VideoBitDepth" && it.value == "8" })
        assertTrue(conditions.toString(), conditions.any { it.property == "VideoRangeType" && !it.value.contains("HDR10") })
    }

    // A5

    @Test
    fun `A5 ASS and SSA subtitles are declared deliverable as External`() {
        val external = profile().subtitleProfiles.filter { it.method == "External" }.map { it.format }
        assertTrue(external.toString(), "ass" in external && "ssa" in external && "subrip" in external)
    }

    // A8 / K2

    @Test
    fun `A8 the quality tiers reach down to 720 kbps, each with a width`() {
        val tiers = QualityOption.entries.mapNotNull { q -> q.maxBitrate?.let { it to q.maxWidth } }.toSet()
        assertEquals(
            setOf(
                20_000_000 to 1920,
                8_000_000 to 1280,
                4_000_000 to 854,
                2_000_000 to 1280,
                1_000_000 to 854,
                720_000 to 640,
            ),
            tiers,
        )
    }

    @Test
    fun `A8 Settings max bitrate offers the low tiers`() {
        val values = AppSettings.MAX_BITRATE_OPTIONS.values
        assertTrue(values.toString(), 1_000_000 in values && 720_000 in values)
    }

    // R6 (Android exposure)

    @Test
    fun `R6 an HDR transcode is pinned to the variant playlist, an SDR one is not`() {
        val url = "/videos/abc/master.m3u8?DeviceId=d&VideoCodec=h264&PlaySessionId=p"

        fun source(range: String) =
            MediaSourceInfo(
                id = "abc",
                transcodingUrl = url,
                mediaStreams = listOf(MediaStream(type = "Video", codec = "hevc", videoRangeType = range)),
            )
        val hdr = SourceSelector.choose(source("HDR10")) as SourceChoice.Transcode
        assertEquals("/videos/abc/main.m3u8?DeviceId=d&VideoCodec=h264&PlaySessionId=p", hdr.path)
        val sdr = SourceSelector.choose(source("SDR")) as SourceChoice.Transcode
        assertEquals(url, sdr.path)
    }
}
