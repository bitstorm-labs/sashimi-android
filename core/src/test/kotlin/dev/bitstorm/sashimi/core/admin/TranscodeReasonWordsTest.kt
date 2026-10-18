package dev.bitstorm.sashimi.core.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscodeReasonWordsTest {
    @Test
    fun `every reason Jellyfin 12 knows has the Roku client's plain words`() {
        val expected =
            mapOf(
                "ContainerNotSupported" to "Container not supported",
                "VideoCodecNotSupported" to "Video format not supported",
                "AudioCodecNotSupported" to "Audio format not supported",
                "SubtitleCodecNotSupported" to "Subtitles burned in",
                "AudioIsExternal" to "External audio track",
                "SecondaryAudioNotSupported" to "Extra audio track not supported",
                "VideoProfileNotSupported" to "Video profile not supported",
                "VideoLevelNotSupported" to "Video level not supported",
                "VideoResolutionNotSupported" to "Resolution too high",
                "VideoBitDepthNotSupported" to "Bit depth not supported",
                "VideoFramerateNotSupported" to "Frame rate not supported",
                "RefFramesNotSupported" to "Too many reference frames",
                "AnamorphicVideoNotSupported" to "Anamorphic video not supported",
                "InterlacedVideoNotSupported" to "Interlaced video",
                "AudioChannelsNotSupported" to "Too many audio channels",
                "AudioProfileNotSupported" to "Audio profile not supported",
                "AudioSampleRateNotSupported" to "Sample rate not supported",
                "AudioBitDepthNotSupported" to "Audio bit depth not supported",
                "ContainerBitrateExceedsLimit" to "Bitrate cap",
                "VideoBitrateNotSupported" to "Video bitrate too high",
                "AudioBitrateNotSupported" to "Audio bitrate too high",
                "UnknownVideoStreamInfo" to "Unknown video stream",
                "UnknownAudioStreamInfo" to "Unknown audio stream",
                "DirectPlayError" to "Direct play failed",
                "VideoRangeTypeNotSupported" to "HDR → SDR",
                "VideoCodecTagNotSupported" to "Video codec tag not supported",
                "StreamCountExceedsLimit" to "Too many streams",
                "VideoRotationNotSupported" to "Video rotation not supported",
            )
        expected.forEach { (reason, words) -> assertEquals(reason, words, TranscodeReasonWords.of(reason)) }
    }

    @Test
    fun `an unknown future reason is split into words, never shown raw`() {
        // Roku's humanize(): every capital after the first becomes " " + lower case.
        assertEquals("Spatial audio not supported", TranscodeReasonWords.of("SpatialAudioNotSupported"))
        assertEquals("", TranscodeReasonWords.of(""))
        assertEquals("Hdr metadata missing", TranscodeReasonWords.of("HdrMetadataMissing"))
    }

    @Test
    fun `the line joins all reasons, and none gives null`() {
        assertEquals(
            "Container not supported · Audio format not supported",
            TranscodeReasonWords.line(listOf("ContainerNotSupported", "AudioCodecNotSupported")),
        )
        assertNull(TranscodeReasonWords.line(emptyList()))
        assertNull(TranscodeReasonWords.line(null))
    }
}
