package dev.bitstorm.sashimi.core.admin

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SessionTitlesTest {
    private val episode =
        BaseItemDto(
            id = "e",
            name = "Matryoshka",
            type = ItemType.EPISODE,
            seriesName = "The 100",
            parentIndexNumber = 6,
            indexNumber = 10,
            runTimeTicks = 25_442_240_000,
        )
    private val movie = BaseItemDto(id = "m", name = "300", type = ItemType.MOVIE)

    @Test
    fun `an episode shows its series with the episode line below`() {
        assertEquals("The 100", SessionTitles.title(episode))
        assertEquals("S6:E10 · Matryoshka", SessionTitles.episodeLine(episode))
    }

    @Test
    fun `a movie shows its own name and no episode line`() {
        assertEquals("300", SessionTitles.title(movie))
        assertNull(SessionTitles.episodeLine(movie))
    }

    @Test
    fun `position and progress`() {
        assertEquals("15:36 / 42:24", SessionTitles.position(9_360_000_000, episode.runTimeTicks))
        assertEquals("1:02:03 / 2:00:00", SessionTitles.position(37_230_000_000, 72_000_000_000))
        assertEquals(0.5f, SessionTitles.progress(50, 100)!!, 1e-6f)
        assertEquals(1f, SessionTitles.progress(150, 100)!!, 1e-6f)
        assertNull(SessionTitles.progress(10, 0))
        assertNull(SessionTitles.position(null, 100))
    }

    @Test
    fun `details never carry ids or addresses`() {
        val s =
            SessionInfoDto(
                id = "secret-session-id",
                userName = "mom",
                deviceName = "Roku",
                client = "Sashimi",
                nowPlayingItem = episode,
                transcodingInfo =
                    TranscodingInfoDto(
                        isVideoDirect = true,
                        isAudioDirect = false,
                        audioCodec = "aac",
                        bitrate = 8_000_000,
                        hardwareAccelerationType = "qsv",
                    ),
            )
        val text = SessionTitles.details(s).joinToString { "${it.first}=${it.second}" }
        assertFalse(text.contains("secret-session-id"))
        assertEquals("Converting audio", SessionTitles.details(s).toMap()["Play mode"])
        assertNull("audio-only work is not a hardware encode", SessionTitles.details(s).toMap()["Hardware"])
        val video = s.copy(transcodingInfo = s.transcodingInfo!!.copy(isVideoDirect = false))
        assertEquals("Intel Quick Sync", SessionTitles.details(video).toMap()["Hardware"])
        val remux = s.copy(transcodingInfo = s.transcodingInfo!!.copy(isAudioDirect = true))
        assertNull(SessionTitles.details(remux).toMap()["Hardware"])
    }
}
