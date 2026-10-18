package dev.bitstorm.sashimi.core.admin

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.MediaStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayModesTest {
    private fun ti(
        video: Boolean?,
        audio: Boolean?,
        vararg reasons: String,
        width: Int? = null,
        height: Int? = null,
        videoCodec: String? = null,
        audioCodec: String? = null,
    ) = TranscodingInfoDto(
        isVideoDirect = video,
        isAudioDirect = audio,
        transcodeReasons = reasons.toList(),
        width = width,
        height = height,
        videoCodec = videoCodec,
        audioCodec = audioCodec,
    )

    private val dv4k =
        BaseItemDto(
            id = "m",
            name = "Movie",
            mediaStreams =
                listOf(
                    MediaStream(
                        type = "Video",
                        codec = "hevc",
                        width = 3840,
                        height = 2160,
                        videoRangeType = "DOVIWithHDR10",
                        bitRate = 60_000_000,
                    ),
                    MediaStream(type = "Audio", codec = "dts", index = 1, isDefault = true, bitRate = 1_500_000),
                    MediaStream(type = "Audio", codec = "aac", index = 2, bitRate = 256_000),
                ),
        )

    private fun session(
        t: TranscodingInfoDto?,
        item: BaseItemDto? = dv4k,
    ) = SessionInfoDto(id = "s", nowPlayingItem = item, transcodingInfo = t)

    // Truth table, row by row.

    @Test
    fun `no TranscodingInfo is direct play`() {
        assertEquals(PlayMode.DIRECT_PLAY, PlayModes.of(null))
    }

    @Test
    fun `video and audio both direct is direct play, and it is a remux`() {
        val t = ti(true, true, "ContainerNotSupported")
        assertEquals(PlayMode.DIRECT_PLAY, PlayModes.of(t))
        assertTrue(PlayModes.isRemux(t))
        assertFalse(PlayModes.isRemux(null))
    }

    @Test
    fun `video direct and audio converted is converting audio`() {
        assertEquals(PlayMode.CONVERTING_AUDIO, PlayModes.of(ti(true, false, "AudioCodecNotSupported")))
    }

    @Test
    fun `video transcoded is converting video, whatever the audio does`() {
        assertEquals(PlayMode.CONVERTING_VIDEO, PlayModes.of(ti(false, true)))
        assertEquals(PlayMode.CONVERTING_VIDEO, PlayModes.of(ti(false, false)))
    }

    @Test
    fun `subtitle burn-in is converting video even when the flags say direct`() {
        assertEquals(PlayMode.CONVERTING_VIDEO, PlayModes.of(ti(true, true, "SubtitleCodecNotSupported")))
        assertEquals(PlayMode.CONVERTING_VIDEO, PlayModes.of(ti(true, false, "SubtitleCodecNotSupported")))
        assertFalse(PlayModes.isRemux(ti(true, true, "SubtitleCodecNotSupported")))
    }

    @Test
    fun `HDR to SDR tone-mapping is converting video`() {
        assertEquals(PlayMode.CONVERTING_VIDEO, PlayModes.of(ti(false, true, "VideoRangeTypeNotSupported")))
    }

    @Test
    fun `missing direct flags count as converted, never as direct`() {
        assertEquals(PlayMode.CONVERTING_VIDEO, PlayModes.of(ti(null, null)))
        assertEquals(PlayMode.CONVERTING_AUDIO, PlayModes.of(ti(true, null)))
    }

    @Test
    fun `badge labels are the spec's words`() {
        assertEquals("Direct play", PlayMode.DIRECT_PLAY.label)
        assertEquals("Converting audio", PlayMode.CONVERTING_AUDIO.label)
        assertEquals("Converting video", PlayMode.CONVERTING_VIDEO.label)
    }

    // Detail line.

    @Test
    fun `source format names the tier and the HDR range`() {
        assertEquals("4K Dolby Vision", PlayModes.sourceFormat(dv4k))
        val sdr1080 =
            BaseItemDto(id = "x", mediaStreams = listOf(MediaStream(type = "Video", width = 1920, height = 1080, videoRangeType = "SDR")))
        assertEquals("1080p", PlayModes.sourceFormat(sdr1080))
        val hdr10p =
            BaseItemDto(
                id = "x",
                mediaStreams = listOf(MediaStream(type = "Video", width = 3840, height = 1600, videoRangeType = "HDR10Plus")),
            )
        assertEquals("4K HDR10+", PlayModes.sourceFormat(hdr10p))
        val hlg720 =
            BaseItemDto(id = "x", mediaStreams = listOf(MediaStream(type = "Video", width = 1280, height = 720, videoRangeType = "HLG")))
        assertEquals("720p HLG", PlayModes.sourceFormat(hlg720))
        val sd =
            BaseItemDto(id = "x", mediaStreams = listOf(MediaStream(type = "Video", width = 720, height = 480, videoRangeType = "HDR10")))
        assertEquals("480p HDR10", PlayModes.sourceFormat(sd))
        assertEquals(null, PlayModes.sourceFormat(BaseItemDto(id = "x")))
    }

    @Test
    fun `direct play detail is just the source format`() {
        assertEquals("4K Dolby Vision", PlayModes.detail(session(null)))
    }

    @Test
    fun `remux detail says Remux`() {
        assertEquals("4K Dolby Vision · Remux", PlayModes.detail(session(ti(true, true, "ContainerNotSupported"))))
    }

    @Test
    fun `audio conversion detail lists the reasons and the codec change`() {
        assertEquals(
            "4K Dolby Vision · Audio codec · DTS → AC3",
            PlayModes.detail(session(ti(true, false, "AudioCodecNotSupported", audioCodec = "ac3"))),
        )
    }

    @Test
    fun `video conversion detail shows the resolution change`() {
        assertEquals(
            "4K Dolby Vision · Bitrate cap · 4K → 1080p",
            PlayModes.detail(session(ti(false, true, "ContainerBitrateExceedsLimit", width = 1920, height = 1080, videoCodec = "h264"))),
        )
    }

    @Test
    fun `video conversion at the same tier shows the codec change`() {
        assertEquals(
            "4K Dolby Vision · Video codec · HEVC → H264",
            PlayModes.detail(session(ti(false, true, "VideoCodecNotSupported", width = 3840, height = 2160, videoCodec = "h264"))),
        )
    }

    @Test
    fun `the reason line lists every reason`() {
        assertEquals(
            "4K Dolby Vision · Subtitle burn-in · Audio codec · 4K → 1080p · DTS → AAC",
            PlayModes.detail(
                session(
                    ti(
                        false,
                        false,
                        "SubtitleCodecNotSupported",
                        "AudioCodecNotSupported",
                        width = 1920,
                        height = 1080,
                        videoCodec = "h264",
                        audioCodec = "aac",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `audio change uses the audio stream that is playing`() {
        val s =
            SessionInfoDto(
                id = "s",
                nowPlayingItem = dv4k,
                playState = PlayStateDto(audioStreamIndex = 2),
                transcodingInfo = ti(true, false, "AudioCodecNotSupported", audioCodec = "mp3"),
            )
        assertEquals("4K Dolby Vision · Audio codec · AAC → MP3", PlayModes.detail(s))
    }
}
