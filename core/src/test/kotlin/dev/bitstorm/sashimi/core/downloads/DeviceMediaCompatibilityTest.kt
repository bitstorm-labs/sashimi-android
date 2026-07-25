package dev.bitstorm.sashimi.core.downloads

import dev.bitstorm.sashimi.core.model.MediaSourceInfo
import dev.bitstorm.sashimi.core.model.MediaStream
import dev.bitstorm.sashimi.core.playback.CodecCapabilities
import dev.bitstorm.sashimi.core.playback.FixedCodecCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceMediaCompatibilityTest {
    private fun source(
        container: String?,
        streams: List<MediaStream>,
    ) = MediaSourceInfo(id = "s", container = container, mediaStreams = streams)

    private fun video(
        codec: String?,
        range: String? = null,
    ) = MediaStream(type = "Video", codec = codec, videoRangeType = range)

    private fun audio(codec: String?) = MediaStream(type = "Audio", codec = codec)

    @Test
    fun `h264 aac in mp4 direct-plays`() {
        assertTrue(DeviceMediaCompatibility.canDirectPlayOnDevice(source("mp4", listOf(video("h264"), audio("aac")))))
    }

    @Test
    fun `codec aliases are canonicalised`() {
        assertTrue(DeviceMediaCompatibility.canDirectPlayOnDevice(source("mov", listOf(video("h265"), audio("aac")))))
        assertTrue(DeviceMediaCompatibility.canDirectPlayOnDevice(source("m4v", listOf(video("avc"), audio("eac3")))))
        assertEquals("hevc", DeviceMediaCompatibility.canonicalCodec("H265"))
        assertEquals("h264", DeviceMediaCompatibility.canonicalCodec("x264"))
    }

    @Test
    fun `mkv container fails closed`() {
        assertFalse(DeviceMediaCompatibility.canDirectPlayOnDevice(source("mkv", listOf(video("h264"), audio("aac")))))
    }

    @Test
    fun `any non-allowlisted container token fails the whole set`() {
        assertFalse(DeviceMediaCompatibility.canDirectPlayOnDevice(source("mkv,mp4", listOf(video("h264"), audio("aac")))))
    }

    @Test
    fun `unsupported video codec fails`() {
        assertFalse(DeviceMediaCompatibility.canDirectPlayOnDevice(source("mp4", listOf(video("vp9"), audio("aac")))))
    }

    @Test
    fun `unsupported audio codec fails`() {
        assertFalse(DeviceMediaCompatibility.canDirectPlayOnDevice(source("mp4", listOf(video("h264"), audio("dts")))))
    }

    @Test
    fun `nil or empty container fails closed`() {
        assertFalse(DeviceMediaCompatibility.canDirectPlayOnDevice(source(null, listOf(video("h264"), audio("aac")))))
        assertFalse(DeviceMediaCompatibility.canDirectPlayOnDevice(source("", listOf(video("h264"), audio("aac")))))
    }

    @Test
    fun `empty streams fails closed`() {
        assertFalse(DeviceMediaCompatibility.canDirectPlayOnDevice(source("mp4", emptyList())))
    }

    @Test
    fun `bare dolby vision profile 5 rejected on non-DV device but allowed on DV device`() {
        val dvSource = source("mp4", listOf(video("hevc", range = "DOVI"), audio("aac")))
        assertFalse(DeviceMediaCompatibility.canDirectPlayOnDevice(dvSource, deviceSupportsDolbyVision = false))
        assertTrue(DeviceMediaCompatibility.canDirectPlayOnDevice(dvSource, deviceSupportsDolbyVision = true))
    }

    @Test
    fun `dolby vision with hdr fallback passes on non-DV device`() {
        val source = source("mp4", listOf(video("hevc", range = "DOVIWithHDR10"), audio("aac")))
        assertTrue(DeviceMediaCompatibility.canDirectPlayOnDevice(source, deviceSupportsDolbyVision = false))
    }

    @Test
    fun `original fail-closed gate degrades to high when incompatible`() {
        assertEquals(DownloadQuality.ORIGINAL, DownloadQuality.effectiveQuality(DownloadQuality.ORIGINAL, sourceIsCompatible = true))
        assertEquals(DownloadQuality.HIGH, DownloadQuality.effectiveQuality(DownloadQuality.ORIGINAL, sourceIsCompatible = false))
        // Transcoded tiers pass through regardless.
        assertEquals(DownloadQuality.LOW, DownloadQuality.effectiveQuality(DownloadQuality.LOW, sourceIsCompatible = false))
    }

    // MARK: - Real device decoder gate

    @Test
    fun `ac3 is refused on a device with no ac3 decoder`() {
        // ExoPlayer ships no software AC-3 decoder, so the format being on the
        // allowlist is not enough: without this the tier was offered, the file
        // downloaded in full, and it then would not play, with no fallback.
        val noAc3 = FixedCodecCapabilities(setOf(CodecCapabilities.MimeTypes.AAC))
        assertFalse(
            DeviceMediaCompatibility.canDirectPlayOnDevice(
                source("mp4", listOf(video("h264"), audio("ac3"))),
                codecs = noAc3,
            ),
        )
    }

    @Test
    fun `ac3 is allowed when the device really can decode it`() {
        val withAc3 = FixedCodecCapabilities(setOf(CodecCapabilities.MimeTypes.AC3))
        assertTrue(
            DeviceMediaCompatibility.canDirectPlayOnDevice(
                source("mp4", listOf(video("h264"), audio("ac3"))),
                codecs = withAc3,
            ),
        )
    }

    @Test
    fun `a second track the device can decode still qualifies the source`() {
        val aacOnly = FixedCodecCapabilities(setOf(CodecCapabilities.MimeTypes.AAC))
        assertTrue(
            DeviceMediaCompatibility.canDirectPlayOnDevice(
                source("mp4", listOf(video("h264"), audio("ac3"), audio("aac"))),
                codecs = aacOnly,
            ),
        )
    }

    @Test
    fun `omitting capabilities keeps the previous allowlist-only behaviour`() {
        // Callers that cannot supply device capabilities must not regress.
        assertTrue(DeviceMediaCompatibility.canDirectPlayOnDevice(source("mp4", listOf(video("h264"), audio("ac3")))))
    }
}
