package dev.bitstorm.sashimi.core.admin

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.model.MediaStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OpsDec-style Now playing card (design revision 2026-10-18). Cases and
 * expected strings are ported one for one from sashimi-roku's
 * tests/AdminDashTests.brs so the three clients show the same card.
 */
class StreamCardsTest {
    private val video =
        MediaStream(
            type = "Video",
            codec = "hevc",
            width = 3840,
            height = 2160,
            videoRangeType = "DOVIWithHDR10",
            bitRate = 58_000_000,
            realFrameRate = 23.976025f,
            averageFrameRate = 23.976025f,
        )
    private val dts = MediaStream(type = "Audio", codec = "dts", index = 1, isDefault = true, bitRate = 1_509_000, channels = 6)
    private val ac3 = MediaStream(type = "Audio", codec = "ac3", index = 2, isDefault = false, bitRate = 640_000, channels = 2)

    private val movie =
        BaseItemDto(
            id = "m",
            name = "300",
            type = ItemType.MOVIE,
            productionYear = 2006,
            runTimeTicks = 70_200_000_000,
            mediaStreams = listOf(video, dts, ac3),
        )

    /** A playing session as /Sessions returns it (the Roku suite's `session()`). */
    private fun session(
        ti: TranscodingInfoDto? = null,
        item: BaseItemDto? = movie,
        playState: PlayStateDto? = PlayStateDto(positionTicks = 12_440_000_000, isPaused = false, playMethod = "Transcode"),
        remote: String? = null,
    ) = SessionInfoDto(
        id = "s1",
        userName = "mom",
        deviceName = "Living Room Roku",
        client = "Sashimi",
        supportsRemoteControl = true,
        nowPlayingItem = item,
        playState = playState,
        transcodingInfo = ti,
        remoteEndPoint = remote,
    )

    private fun withVideo(change: (MediaStream) -> MediaStream) = movie.copy(mediaStreams = listOf(change(video), dts, ac3))

    private fun ti(
        videoDirect: Boolean? = null,
        audioDirect: Boolean? = null,
        videoCodec: String? = null,
        audioCodec: String? = null,
        width: Int? = null,
        height: Int? = null,
        bitrate: Long? = null,
        framerate: Float? = null,
        audioChannels: Int? = null,
        hw: String? = null,
        reasons: List<String> = emptyList(),
    ) = TranscodingInfoDto(
        isVideoDirect = videoDirect,
        isAudioDirect = audioDirect,
        videoCodec = videoCodec,
        audioCodec = audioCodec,
        width = width,
        height = height,
        bitrate = bitrate,
        framerate = framerate,
        audioChannels = audioChannels,
        hardwareAccelerationType = hw,
        transcodeReasons = reasons,
    )

    /** Video re-encoded by QSV at 43.2 fps from a 23.976 fps source, bitrate cap. */
    private val tcInfo =
        ti(false, true, "h264", "dts", 1920, 1080, 8_000_000, 43.2f, 6, "qsv", listOf("ContainerBitrateExceedsLimit"))
    private val tc = session(tcInfo)

    /** Video copied, DTS to AC3 at real time: often over before the next poll. */
    private val audioInfo =
        ti(true, false, "hevc", "ac3", 3840, 2160, 58_640_000, 23.976f, 6, "qsv", listOf("AudioCodecNotSupported"))
    private val audio = session(audioInfo)

    private val direct = session()

    // State dot

    @Test
    fun `state dot is green playing, amber paused, grey otherwise`() {
        assertEquals(StreamState.PLAYING, StreamCards.state(direct))
        assertEquals(StreamState.PAUSED, StreamCards.state(session(playState = PlayStateDto(isPaused = true))))
        assertEquals(StreamState.OTHER, StreamCards.state(session(playState = null)))
        assertEquals(StreamState.OTHER, StreamCards.state(session(playState = PlayStateDto(positionTicks = 0))))
    }

    // Title

    @Test
    fun `episode title is the episode, sub the series, tag S04E03`() {
        val ep =
            BaseItemDto(id = "e", name = "Mercy", type = ItemType.EPISODE, seriesName = "Vikings", parentIndexNumber = 4, indexNumber = 3)
        assertEquals(CardTitle("Mercy", "Vikings", "S04E03"), StreamCards.title(ep))
    }

    @Test
    fun `episode tag pads to two digits and drops missing parts`() {
        fun tag(
            season: Int?,
            episode: Int?,
            type: ItemType = ItemType.EPISODE,
        ) = StreamCards.episodeTag(BaseItemDto(id = "e", type = type, parentIndexNumber = season, indexNumber = episode))
        assertEquals("S12E112", tag(12, 112))
        assertEquals("S00E02", tag(0, 2))
        assertEquals("E07", tag(null, 7))
        assertEquals("", tag(null, null))
        assertEquals("", tag(1, 1, ItemType.MOVIE))
    }

    @Test
    fun `movie sub is Movie and the year`() {
        assertEquals(CardTitle("300", "Movie · 2006", ""), StreamCards.title(movie))
        assertEquals("Movie", StreamCards.title(BaseItemDto(id = "x", name = "X", type = ItemType.MOVIE)).sub)
        assertEquals("1999", StreamCards.title(BaseItemDto(id = "y", name = "Y", type = ItemType.VIDEO, productionYear = 1999)).sub)
        assertEquals(CardTitle("", "", ""), StreamCards.title(null))
    }

    // People

    @Test
    fun `device line is the app then the device`() {
        assertEquals("Sashimi · Living Room Roku", StreamCards.deviceLine(direct))
        assertEquals("Pixel 8", StreamCards.deviceLine(SessionInfoDto(id = "a", deviceName = "Pixel 8")))
        assertEquals("Jellyfin Web", StreamCards.deviceLine(SessionInfoDto(id = "a", client = "Jellyfin Web")))
    }

    @Test
    fun `avatar is the user's initial`() {
        assertEquals("M", StreamCards.initialOf("mom"))
        assertEquals("?", StreamCards.initialOf(""))
        assertEquals("?", StreamCards.initialOf(null))
    }

    @Test
    fun `percent is rounded, and empty without a runtime`() {
        assertEquals("18%", StreamCards.percentText(direct))
        assertEquals("", StreamCards.percentText(session(item = BaseItemDto(id = "x"))))
    }

    // Resolution pill

    @Test
    fun `resolution shows the change only when video is re-encoded`() {
        assertEquals("4K", StreamCards.resolutionPill(direct))
        assertEquals("4K → 1080p", StreamCards.resolutionPill(tc))
        // Video copied: the delivered size is the source, whatever the server says.
        assertEquals("4K", StreamCards.resolutionPill(audio))
        // Even when the server reports a smaller size for an audio-only conversion.
        assertEquals("4K", StreamCards.resolutionPill(session(audioInfo.copy(width = 1920, height = 1080))))
        assertEquals("4K", StreamCards.resolutionPill(session(ti(false, true, "h264", width = 3840, height = 2160))))
        assertEquals("4K", StreamCards.resolutionPill(session(ti(false, true, "h264"))))
        assertEquals("", StreamCards.resolutionPill(session(item = BaseItemDto(id = "x", mediaStreams = emptyList()))))
    }

    @Test
    fun `resolution tiers go by width first`() {
        assertEquals("1080p", StreamCards.resolutionLabel(1920, 800))
        assertEquals("720p", StreamCards.resolutionLabel(1280, 536))
        assertEquals("480p", StreamCards.resolutionLabel(720, 480))
        assertEquals("480p", StreamCards.resolutionLabel(640, 272))
        assertEquals("SD", StreamCards.resolutionLabel(320, 240))
        assertEquals("4K", StreamCards.resolutionLabel(null, 1800))
        assertEquals("", StreamCards.resolutionLabel(null, null))
    }

    // Video pill

    @Test
    fun `video pill names codec and range`() {
        assertEquals("HEVC DV", StreamCards.videoPill(direct))
        mapOf(
            "HDR10" to "HEVC HDR10",
            "HDR10Plus" to "HEVC HDR10+",
            "HLG" to "HEVC HLG",
            "DOVIWithSDR" to "HEVC DV",
            "SDR" to "HEVC",
        ).forEach { (range, pill) ->
            assertEquals(range, pill, StreamCards.videoPill(session(item = withVideo { it.copy(videoRangeType = range) })))
        }
        assertEquals("HEVC", StreamCards.videoPill(session(item = withVideo { it.copy(codec = "h265", videoRangeType = "SDR") })))
        assertEquals("", StreamCards.videoPill(session(item = BaseItemDto(id = "x", mediaStreams = emptyList()))))
    }

    @Test
    fun `video pill shows the codec change and SDR only when tone-mapping is a reason`() {
        assertEquals("HEVC DV → H264", StreamCards.videoPill(tc))
        val toneMap = listOf("VideoRangeTypeNotSupported")
        assertEquals("HEVC DV → HEVC SDR", StreamCards.videoPill(session(ti(false, true, "hevc", reasons = toneMap))))
        assertEquals("HEVC DV → H264 SDR", StreamCards.videoPill(session(ti(false, true, "h264", reasons = toneMap))))
        // Same codec and range at another bitrate: no arrow.
        assertEquals(
            "HEVC DV",
            StreamCards.videoPill(session(ti(false, true, "hevc", reasons = listOf("ContainerBitrateExceedsLimit")))),
        )
        assertEquals("HEVC DV", StreamCards.videoPill(audio))
    }

    // Audio pill

    @Test
    fun `audio pill shows codec and channels, and the change when converted`() {
        assertEquals("DTS 5.1", StreamCards.audioPill(direct))
        // Same channel count either side: said once, after the arrow.
        assertEquals("DTS → AC3 5.1", StreamCards.audioPill(audio))
        assertEquals("DTS 5.1 → AAC 2.0", StreamCards.audioPill(session(ti(true, false, audioCodec = "aac", audioChannels = 2))))
        assertEquals("DTS 5.1 → AAC", StreamCards.audioPill(session(ti(true, false, audioCodec = "aac"))))
        // Audio copied while the video converts: the source track as is.
        assertEquals("DTS 5.1", StreamCards.audioPill(tc))
        // The flag decides: a copied track is the source even if AudioCodec disagrees.
        assertEquals("DTS 5.1", StreamCards.audioPill(session(ti(false, true, "h264", "aac", audioChannels = 2))))
        assertEquals("AC3 2.0", StreamCards.audioPill(session(playState = PlayStateDto(audioStreamIndex = 2))))
        val truehd = movie.copy(mediaStreams = listOf(video, dts.copy(codec = "truehd", channels = 8)))
        assertEquals("TRUEHD 7.1", StreamCards.audioPill(session(item = truehd)))
        assertEquals("", StreamCards.audioPill(session(item = BaseItemDto(id = "x", mediaStreams = emptyList()))))
    }

    @Test
    fun `channel counts read as layouts`() {
        mapOf(1 to "1.0", 2 to "2.0", 3 to "2.1", 5 to "5.0", 6 to "5.1", 7 to "6.1", 8 to "7.1", 4 to "4ch")
            .forEach { (n, label) -> assertEquals("$n", label, StreamCards.channelsLabel(n)) }
        assertEquals("", StreamCards.channelsLabel(null))
        assertEquals("", StreamCards.channelsLabel(0))
    }

    // Bitrate pill

    @Test
    fun `bitrate shows source to output only when video is re-encoded`() {
        assertEquals("60 Mbps", StreamCards.ratePill(direct))
        assertEquals("60 → 8 Mbps", StreamCards.ratePill(tc))
        // Video copied: the delivered rate, no arrow.
        assertEquals("59 Mbps", StreamCards.ratePill(audio))
        assertEquals("60 Mbps → 720 kbps", StreamCards.ratePill(session(ti(false, true, bitrate = 720_000))))
        assertEquals(
            "8 Mbps",
            StreamCards.ratePill(session(ti(false, true, bitrate = 8_000_000), item = movie.copy(mediaStreams = emptyList()))),
        )
        assertEquals("60 Mbps", StreamCards.ratePill(session(ti(false, true, bitrate = 59_509_000))))
        assertEquals("", StreamCards.ratePill(session(item = BaseItemDto(id = "x"))))
    }

    @Test
    fun `bitrate under 1 Mbps is in kbps, one decimal under 10 Mbps`() {
        assertEquals("720 kbps", StreamCards.formatBitrate(720_000.0))
        assertEquals("8.4 Mbps", StreamCards.formatBitrate(8_400_000.0))
        assertEquals("8 Mbps", StreamCards.formatBitrate(8_000_000.0))
        assertEquals("", StreamCards.formatBitrate(0.0))
    }

    // Speed and the play-mode pill

    @Test
    fun `speed is encoder fps over source fps`() {
        assertEquals("1.8x", StreamCards.speed(tc))
        assertEquals("1.0x", StreamCards.speed(audio))
        assertEquals("4.2x", StreamCards.speed(session(tcInfo.copy(framerate = 100f))))
        assertEquals("0.5x", StreamCards.speed(session(tcInfo.copy(framerate = 12f))))
        assertEquals("12x", StreamCards.speed(session(tcInfo.copy(framerate = 288f))))
        // RealFrameRate missing falls back to AverageFrameRate.
        assertEquals("1.8x", StreamCards.speed(session(tcInfo, item = withVideo { it.copy(realFrameRate = null, averageFrameRate = 24f) })))
        assertEquals("", StreamCards.speed(session(tcInfo, item = withVideo { it.copy(realFrameRate = null, averageFrameRate = null) })))
        assertEquals("", StreamCards.speed(session(tcInfo.copy(framerate = null))))
        assertEquals("", StreamCards.speed(session(tcInfo.copy(framerate = 0f))))
        assertEquals("", StreamCards.speed(direct))
    }

    @Test
    fun `mode pill reads Direct Play, Audio only, or TC with speed and HW`() {
        assertEquals("Direct Play", StreamCards.modeLabel(direct))
        val remux = ti(true, true, hw = "qsv", framerate = 50f, reasons = listOf("ContainerNotSupported"))
        assertEquals("Direct Play", StreamCards.modeLabel(session(remux)))
        assertEquals("Audio only · 1.0x", StreamCards.modeLabel(audio))
        assertEquals("Audio only", StreamCards.modeLabel(session(audioInfo.copy(framerate = null))))
        assertEquals("TC 1.8x HW", StreamCards.modeLabel(tc))
        assertEquals("TC 1.8x", StreamCards.modeLabel(session(tcInfo.copy(hardwareAccelerationType = "none"))))
        assertEquals("TC 1.8x", StreamCards.modeLabel(session(tcInfo.copy(hardwareAccelerationType = "None"))))
        assertEquals("TC 1.8x", StreamCards.modeLabel(session(tcInfo.copy(hardwareAccelerationType = null))))
        assertEquals("TC HW", StreamCards.modeLabel(session(tcInfo.copy(framerate = null))))
    }

    @Test
    fun `HW is claimed only when the picture is encoded`() {
        // The server names its accelerator on every TranscodingInfo, remuxes included.
        assertTrue(StreamCards.isHardware(tc))
        assertFalse(StreamCards.isHardware(audio))
        assertFalse(StreamCards.isHardware(session(ti(true, true, hw = "qsv"))))
        assertFalse(StreamCards.isHardware(direct))
    }

    @Test
    fun `pills come in a fixed order with the mode last`() {
        assertEquals(
            listOf(
                Pill(PillKind.RESOLUTION, "4K → 1080p"),
                Pill(PillKind.VIDEO, "HEVC DV → H264"),
                Pill(PillKind.AUDIO, "DTS 5.1"),
                Pill(PillKind.BITRATE, "60 → 8 Mbps"),
                Pill(PillKind.MODE, "TC 1.8x HW"),
            ),
            StreamCards.pills(tc),
        )
        assertEquals(
            listOf("4K", "HEVC DV", "DTS → AC3 5.1", "59 Mbps", "Audio only · 1.0x"),
            StreamCards.pills(audio).map { it.text },
        )
        assertEquals("Direct Play", StreamCards.pills(direct).last().text)
        // Nothing known about the file: only the play mode.
        assertEquals(listOf(Pill(PillKind.MODE, "Direct Play")), StreamCards.pills(session(item = BaseItemDto(id = "x"))))
    }

    // Footer

    @Test
    fun `footer gives the reason in words`() {
        assertEquals("Plays as-is", StreamCards.footerReason(direct))
        assertEquals("Remux", StreamCards.footerReason(session(ti(true, true, reasons = listOf("ContainerNotSupported")))))
        assertEquals("Audio format not supported", StreamCards.footerReason(audio))
        assertEquals("Bitrate cap", StreamCards.footerReason(tc))
        val many = listOf("VideoResolutionNotSupported", "SubtitleCodecNotSupported", "VideoResolutionNotSupported")
        assertEquals("Resolution too high · Subtitles burned in", StreamCards.footerReason(session(ti(false, true, reasons = many))))
        assertEquals("Converting", StreamCards.footerReason(session(ti(false, true))))
    }

    @Test
    fun `local covers RFC 1918, loopback and link-local, not CGNAT`() {
        listOf(
            "192.168.86.30", "10.0.0.5", "10.255.255.255", "172.16.0.1", "172.31.255.255", "127.0.0.1", "169.254.10.1",
            "::1", "fe80::1", "FE80::ABCD", "febf::1", "fd12:3456::1", "fc00::1", "::ffff:192.168.1.5", "[fe80::1]:8096",
            "192.168.1.5:54321", " 10.1.2.3 ",
        ).forEach { assertTrue("local $it", StreamCards.isLocalAddress(it)) }
        listOf(
            "100.64.0.1", "100.127.255.255", "172.15.0.1", "172.32.0.1", "8.8.8.8", "192.169.0.1", "11.0.0.1", "fec0::1",
            "fe7f::1", "2001:db8::1", "::ffff:8.8.8.8", "::ffff:100.64.0.1", "999.168.1.1", "192.168.1", "localhost", "", "::",
        ).forEach { assertFalse("remote $it", StreamCards.isLocalAddress(it)) }
        assertFalse(StreamCards.isLocalAddress(null))
    }

    @Test
    fun `where is Local or Remote, and empty without an address`() {
        assertEquals("Local", StreamCards.whereLabel(session(remote = "192.168.86.30")))
        assertEquals("Remote", StreamCards.whereLabel(session(remote = "100.70.1.2")))
        assertEquals("Remote", StreamCards.whereLabel(session(remote = "72.14.3.99")))
        assertEquals("", StreamCards.whereLabel(session(remote = "")))
        assertEquals("", StreamCards.whereLabel(session(remote = null)))
    }
}
