package dev.bitstorm.sashimi.core.admin

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.MediaStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerSummaryTest {
    private val item =
        BaseItemDto(
            id = "i",
            mediaStreams =
                listOf(
                    MediaStream(type = "Video", bitRate = 60_000_000),
                    MediaStream(type = "Audio", index = 1, bitRate = 1_000_000),
                    MediaStream(type = "Audio", index = 2, isDefault = true, bitRate = 640_000),
                ),
        )

    @Test
    fun `transcode bitrate wins when the server is converting`() {
        val s =
            SessionInfoDto(
                id = "a",
                nowPlayingItem = item,
                transcodingInfo = TranscodingInfoDto(isVideoDirect = false, bitrate = 8_000_000),
            )
        assertEquals(8_000_000L, ServerSummary.bitrate(s))
    }

    @Test
    fun `direct play bitrate is the video plus the playing audio`() {
        val picked = SessionInfoDto(id = "a", nowPlayingItem = item, playState = PlayStateDto(audioStreamIndex = 1))
        assertEquals(61_000_000L, ServerSummary.bitrate(picked))
        val default = SessionInfoDto(id = "a", nowPlayingItem = item)
        assertEquals(60_640_000L, ServerSummary.bitrate(default))
        assertNull(ServerSummary.bitrate(SessionInfoDto(id = "a", nowPlayingItem = BaseItemDto(id = "x"))))
    }

    @Test
    fun `idle sessions are not streams and do not count`() {
        val sessions =
            listOf(
                SessionInfoDto(id = "a", nowPlayingItem = item),
                SessionInfoDto(
                    id = "b",
                    nowPlayingItem = item,
                    transcodingInfo = TranscodingInfoDto(isVideoDirect = true, isAudioDirect = false, bitrate = 30_000_000),
                ),
                SessionInfoDto(
                    id = "c",
                    nowPlayingItem = item,
                    transcodingInfo = TranscodingInfoDto(isVideoDirect = false, bitrate = 8_000_000),
                ),
                SessionInfoDto(
                    id = "idle",
                    nowPlayingItem = null,
                    transcodingInfo = TranscodingInfoDto(isVideoDirect = false, bitrate = 99_000_000),
                ),
            )
        val summary = ServerSummary.of(sessions)
        assertEquals(3, summary.streaming)
        assertEquals(2, summary.converting)
        assertEquals(60_640_000L + 30_000_000L + 8_000_000L, summary.totalBitrate)
        assertEquals(listOf("a", "b", "c"), ServerSummary.playing(sessions).map { it.id })
        assertEquals("3 streaming · 2 converting · 99 Mbps out", ServerSummary.headline(summary))
    }

    @Test
    fun `headline when nobody is watching`() {
        assertEquals("Nobody's watching", ServerSummary.headline(ServerSummary.of(emptyList())))
    }

    @Test
    fun `remux counts as streaming but not converting`() {
        val s =
            listOf(
                SessionInfoDto(
                    id = "a",
                    nowPlayingItem = item,
                    transcodingInfo = TranscodingInfoDto(isVideoDirect = true, isAudioDirect = true, bitrate = 10_500_000),
                ),
            )
        assertEquals("1 streaming · 0 converting · 11 Mbps out", ServerSummary.headline(ServerSummary.of(s)))
    }

    @Test
    fun `mbps formatting`() {
        assertEquals("142 Mbps", ServerSummary.mbps(142_300_000))
        assertEquals("8.4 Mbps", ServerSummary.mbps(8_400_000))
        assertEquals("8 Mbps", ServerSummary.mbps(8_000_000))
        assertEquals("0.6 Mbps", ServerSummary.mbps(640_000))
    }
}
