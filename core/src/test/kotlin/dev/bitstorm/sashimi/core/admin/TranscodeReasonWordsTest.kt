package dev.bitstorm.sashimi.core.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TranscodeReasonWordsTest {
    @Test
    fun `every reason Jellyfin 12 knows has plain words`() {
        val expected =
            mapOf(
                "ContainerNotSupported" to "Container",
                "VideoCodecNotSupported" to "Video codec",
                "AudioCodecNotSupported" to "Audio codec",
                "SubtitleCodecNotSupported" to "Subtitle burn-in",
                "AudioIsExternal" to "External audio",
                "SecondaryAudioNotSupported" to "Second audio track",
                "VideoProfileNotSupported" to "Video profile",
                "VideoLevelNotSupported" to "Video level",
                "VideoResolutionNotSupported" to "Resolution",
                "VideoBitDepthNotSupported" to "Bit depth",
                "VideoFramerateNotSupported" to "Frame rate",
                "RefFramesNotSupported" to "Reference frames",
                "AnamorphicVideoNotSupported" to "Anamorphic video",
                "InterlacedVideoNotSupported" to "Interlaced video",
                "AudioChannelsNotSupported" to "Audio channels",
                "AudioProfileNotSupported" to "Audio profile",
                "AudioSampleRateNotSupported" to "Sample rate",
                "AudioBitDepthNotSupported" to "Audio bit depth",
                "ContainerBitrateExceedsLimit" to "Bitrate cap",
                "VideoBitrateNotSupported" to "Video bitrate",
                "AudioBitrateNotSupported" to "Audio bitrate",
                "UnknownVideoStreamInfo" to "Unknown video",
                "UnknownAudioStreamInfo" to "Unknown audio",
                "DirectPlayError" to "Direct play failed",
                "VideoRangeTypeNotSupported" to "HDR → SDR",
                "VideoCodecTagNotSupported" to "Video codec tag",
                "StreamCountExceedsLimit" to "Too many streams",
                "VideoRotationNotSupported" to "Rotated video",
            )
        expected.forEach { (reason, words) -> assertEquals(reason, words, TranscodeReasonWords.of(reason)) }
    }

    @Test
    fun `an unknown future reason is split into words, never shown raw`() {
        assertEquals("Spatial audio", TranscodeReasonWords.of("SpatialAudioNotSupported"))
        assertEquals("Hdr metadata missing", TranscodeReasonWords.of("HdrMetadataMissing"))
        assertFalse(TranscodeReasonWords.of("SpatialAudioNotSupported").contains("NotSupported"))
    }

    @Test
    fun `the line joins all reasons, and none gives null`() {
        assertEquals("Container · Audio codec", TranscodeReasonWords.line(listOf("ContainerNotSupported", "AudioCodecNotSupported")))
        assertNull(TranscodeReasonWords.line(emptyList()))
        assertNull(TranscodeReasonWords.line(null))
    }
}
