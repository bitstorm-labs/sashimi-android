package dev.bitstorm.sashimi.core.playback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Capability-to-profile mapping (A3, A4). */
class CapabilityProfileTest {
    private val uhd10BitHdr =
        VideoDecodeSupport(
            maxWidth = 3840,
            maxHeight = 2160,
            tenBit = true,
            rangeTypes = VideoRanges.playable(hdr10 = true, hdr10Plus = false, hlg = true, dolbyVision = false),
        )
    private val fhd8Bit = VideoDecodeSupport(maxWidth = 1920, maxHeight = 1088, tenBit = false, rangeTypes = setOf(VideoRanges.SDR))

    private fun build(
        audio: Set<String> = emptySet(),
        video: Map<String, VideoDecodeSupport> = emptyMap(),
        bitrate: Int = 20_000_000,
        maxWidth: Int? = null,
    ) = DeviceProfileBuilder(FixedCodecCapabilities(audio, video)).build(bitrate, maxWidth)

    private fun DeviceProfile.forCodec(codec: String) =
        codecProfiles.single { it.codec == codec }.conditions.associate { it.property to it }

    @Test
    fun `audio lists only decodable codecs, aac always`() {
        val p = build(audio = setOf(CodecCapabilities.MimeTypes.EAC3, CodecCapabilities.MimeTypes.OPUS, CodecCapabilities.MimeTypes.FLAC))
        p.directPlayProfiles.forEach { assertEquals("aac,eac3,opus,flac", it.audioCodec) }
        assertEquals("aac", build().directPlayProfiles.first().audioCodec)
    }

    @Test
    fun `dts and truehd follow their decoders too`() {
        val p = build(audio = setOf(CodecCapabilities.MimeTypes.DTS, CodecCapabilities.MimeTypes.TRUEHD))
        assertEquals("aac,dts,truehd", p.directPlayProfiles.first().audioCodec)
    }

    @Test
    fun `a 1080p 8-bit hevc decoder caps hevc at its size, 8 bit and SDR`() {
        val hevc = build(video = mapOf(CodecCapabilities.MimeTypes.HEVC to fhd8Bit)).forCodec("hevc")
        assertEquals("1920", hevc.getValue("Width").value)
        assertEquals("1088", hevc.getValue("Height").value)
        assertEquals("8", hevc.getValue("VideoBitDepth").value)
        assertEquals("SDR", hevc.getValue("VideoRangeType").value)
        hevc.values.forEach { assertEquals(false, it.isRequired) }
    }

    @Test
    fun `a 4K 10-bit HDR decoder admits HDR10 and HLG and the DV variants with those base layers, not bare DV`() {
        val hevc = build(video = mapOf(CodecCapabilities.MimeTypes.HEVC to uhd10BitHdr)).forCodec("hevc")
        assertEquals("10", hevc.getValue("VideoBitDepth").value)
        val ranges = hevc.getValue("VideoRangeType").value.split("|").toSet()
        // HDR10+ plays as its HDR10 base.
        assertEquals(
            setOf("SDR", "HDR10", "HDR10Plus", "HLG", "DOVIWithSDR", "DOVIWithHDR10", "DOVIWithHDR10Plus", "DOVIWithHLG"),
            ranges,
        )
        assertEquals("EqualsAny", hevc.getValue("VideoRangeType").condition)
    }

    @Test
    fun `dolby vision needs a DV decoder and display`() {
        val ranges = VideoRanges.playable(hdr10 = true, hdr10Plus = true, hlg = false, dolbyVision = true)
        assertTrue(ranges.containsAll(listOf("DOVI", "DOVIWithEL", "HDR10Plus", "DOVIWithHDR10Plus")))
    }

    @Test
    fun `h264 is always present and gets its own 8-bit condition, so Hi10P transcodes`() {
        val p = build()
        assertEquals("8", p.forCodec("h264").getValue("VideoBitDepth").value)
        p.directPlayProfiles.forEach { assertEquals("h264", it.videoCodec) }
    }

    @Test
    fun `the tier width profile still applies to every codec`() {
        val tier = build(maxWidth = 1280).codecProfiles.single { it.codec == null }
        assertEquals(listOf("Width"), tier.conditions.map { it.property })
        assertNull(build().codecProfiles.singleOrNull { it.codec == null })
    }

    @Test
    fun `low tiers downmix the transcode to stereo`() {
        assertEquals("2", build(bitrate = 4_000_000).transcodingProfiles.single().maxAudioChannels)
        assertEquals("2", build(bitrate = 720_000).transcodingProfiles.single().maxAudioChannels)
        assertEquals("6", build(bitrate = 8_000_000).transcodingProfiles.single().maxAudioChannels)
    }

    @Test
    fun `low tiers cap the transcode's AAC bitrate, higher tiers leave it to the server`() {
        fun audioCap(bitrate: Int) =
            build(bitrate = bitrate).codecProfiles.singleOrNull { it.type == "VideoAudio" }?.let { p ->
                assertEquals("aac", p.codec)
                p.conditions.single { it.property == "AudioBitrate" && it.condition == "LessThanEqual" }.value
            }
        assertEquals("96000", audioCap(720_000))
        assertEquals("96000", audioCap(1_000_000))
        assertEquals("128000", audioCap(2_000_000))
        assertEquals("128000", audioCap(4_000_000))
        assertNull(audioCap(8_000_000))
    }

    @Test
    fun `transcode audio stays aac, never a copied dolby track`() {
        assertEquals("aac", build(audio = setOf(CodecCapabilities.MimeTypes.AC3)).transcodingProfiles.single().audioCodec)
    }

    @Test
    fun `image subtitles are Encode, text subtitles External`() {
        val subs = build().subtitleProfiles.groupBy({ it.method }, { it.format })
        assertTrue(subs.getValue("Encode").contains("pgssub"))
        assertTrue(subs.getValue("External").containsAll(listOf("srt", "ass", "vtt")))
    }

    @Test
    fun `every condition on the wire uses Jellyfin's vocabulary`() {
        val json =
            Json { encodeDefaults = true }.encodeToJsonElement(
                DeviceProfile.serializer(),
                build(video = mapOf(CodecCapabilities.MimeTypes.HEVC to uhd10BitHdr), maxWidth = 854),
            ).jsonObject
        val conditions = json.getValue("CodecProfiles").jsonArray.flatMap { it.jsonObject.getValue("Conditions").jsonArray }
        val types = setOf("Equals", "NotEquals", "LessThanEqual", "GreaterThanEqual", "EqualsAny")
        val ranges =
            setOf(
                "Unknown", "SDR", "HDR10", "HLG", "DOVI", "DOVIWithHDR10", "DOVIWithHLG", "DOVIWithSDR", "DOVIWithEL",
                "DOVIWithHDR10Plus", "DOVIWithELHDR10Plus", "DOVIInvalid", "HDR10Plus",
            )
        conditions.forEach { c ->
            val o = c.jsonObject
            assertTrue(o.toString(), o.getValue("Condition").jsonPrimitive.content in types)
            if (o.getValue("Property").jsonPrimitive.content == "VideoRangeType") {
                o.getValue("Value").jsonPrimitive.content.split("|").forEach { assertTrue(it, it in ranges) }
            }
        }
    }
}

class SubtitleDecisionsTest {
    private val off = SubtitleTrack.OFF
    private val externalSrt = SubtitleTrack(2, "English (SRT)", "eng", isExternal = true, delivery = SubtitleDelivery.of("subrip", true))
    private val embeddedAss = SubtitleTrack(3, "English (ASS)", "eng", isExternal = false, delivery = SubtitleDelivery.of("ass", false))
    private val embeddedPgs = SubtitleTrack(4, "English (PGS)", "eng", isExternal = false, delivery = SubtitleDelivery.of("PGSSUB", false))
    private val spanish = SubtitleTrack(5, "Spanish", "spa", isExternal = false, delivery = SubtitleDelivery.of("subrip", false))
    private val tracks = listOf(off, externalSrt, embeddedAss, embeddedPgs, spanish)

    @Test
    fun `delivery classification`() {
        assertEquals(SubtitleDelivery.EXTERNAL_FILE, externalSrt.delivery)
        assertEquals(SubtitleDelivery.EMBEDDED_TEXT, embeddedAss.delivery)
        assertEquals(SubtitleDelivery.BURN_IN, embeddedPgs.delivery)
        assertEquals(SubtitleDelivery.EMBEDDED_TEXT, SubtitleDelivery.of(null, false))
    }

    @Test
    fun `the server is told -1 unless an image track is wanted on a transcode`() {
        assertEquals(-1, SubtitleDecisions.serverIndex(null, transcoding = true))
        assertEquals(-1, SubtitleDecisions.serverIndex(off, transcoding = true))
        assertEquals(-1, SubtitleDecisions.serverIndex(embeddedAss, transcoding = true))
        assertEquals(-1, SubtitleDecisions.serverIndex(embeddedPgs, transcoding = false))
        assertEquals(4, SubtitleDecisions.serverIndex(embeddedPgs, transcoding = true))
    }

    @Test
    fun `a transcode side-loads external files and the selected embedded text track`() {
        assertEquals(listOf(externalSrt), SubtitleDecisions.sideLoaded(tracks, -1, streamCarriesEmbedded = false))
        assertEquals(listOf(externalSrt, embeddedAss), SubtitleDecisions.sideLoaded(tracks, 3, streamCarriesEmbedded = false))
        // An image track is never side-loaded.
        assertEquals(listOf(externalSrt), SubtitleDecisions.sideLoaded(tracks, 4, streamCarriesEmbedded = false))
    }

    @Test
    fun `a direct play reads embedded tracks from the container`() {
        assertEquals(listOf(externalSrt), SubtitleDecisions.sideLoaded(tracks, 3, streamCarriesEmbedded = true))
    }

    @Test
    fun `what a change costs`() {
        assertEquals(SubtitleChange.RELOAD, SubtitleDecisions.change(off, embeddedAss, streamCarriesEmbedded = false))
        assertEquals(SubtitleChange.IN_PLAYER, SubtitleDecisions.change(off, externalSrt, streamCarriesEmbedded = false))
        assertEquals(SubtitleChange.RENEGOTIATE, SubtitleDecisions.change(off, embeddedPgs, streamCarriesEmbedded = false))
        assertEquals(SubtitleChange.RENEGOTIATE, SubtitleDecisions.change(embeddedPgs, off, streamCarriesEmbedded = false))
        assertEquals(SubtitleChange.IN_PLAYER, SubtitleDecisions.change(off, embeddedPgs, streamCarriesEmbedded = true))
        assertEquals(SubtitleChange.IN_PLAYER, SubtitleDecisions.change(externalSrt, off, streamCarriesEmbedded = false))
    }

    @Test
    fun `initial selection prefers the language among text tracks, never an image track`() {
        val matches = { a: String?, b: String -> a == b }
        assertEquals(spanish, SubtitleDecisions.initialSelection(tracks, true, "spa", matches))
        assertEquals(externalSrt, SubtitleDecisions.initialSelection(tracks, true, "", matches))
        assertNull(SubtitleDecisions.initialSelection(listOf(off, embeddedPgs), true, "eng", matches))
        assertNull(SubtitleDecisions.initialSelection(tracks, false, "eng", matches))
    }
}
