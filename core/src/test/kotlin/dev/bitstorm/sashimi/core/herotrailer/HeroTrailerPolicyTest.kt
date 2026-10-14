package dev.bitstorm.sashimi.core.herotrailer

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroTrailerPolicyTest {
    private fun item(
        type: ItemType,
        id: String = "item",
        seriesId: String? = null,
        localTrailerCount: Int? = null,
    ) = BaseItemDto(id = id, type = type, seriesId = seriesId, localTrailerCount = localTrailerCount)

    @Test
    fun `movies and series use their own trailer`() {
        assertEquals("m1", HeroTrailerPolicy.trailerSourceId(item(ItemType.MOVIE, "m1", localTrailerCount = 1)))
        assertEquals("s1", HeroTrailerPolicy.trailerSourceId(item(ItemType.SERIES, "s1", localTrailerCount = 2)))
        // The count is only present when the Fields list asked for it; absent is "ask".
        assertEquals("m2", HeroTrailerPolicy.trailerSourceId(item(ItemType.MOVIE, "m2")))
    }

    @Test
    fun `seasons and episodes use the series trailer`() {
        assertEquals("s1", HeroTrailerPolicy.trailerSourceId(item(ItemType.SEASON, "season", seriesId = "s1")))
        assertEquals("s1", HeroTrailerPolicy.trailerSourceId(item(ItemType.EPISODE, "ep", seriesId = "s1", localTrailerCount = 0)))
    }

    @Test
    fun `no trailer when the server says there is none or the type has none`() {
        assertNull(HeroTrailerPolicy.trailerSourceId(item(ItemType.MOVIE, localTrailerCount = 0)))
        assertNull(HeroTrailerPolicy.trailerSourceId(item(ItemType.SERIES, localTrailerCount = 0)))
        assertNull(HeroTrailerPolicy.trailerSourceId(item(ItemType.EPISODE)))
        assertNull(HeroTrailerPolicy.trailerSourceId(item(ItemType.BOX_SET)))
    }

    @Test
    fun `eligible only when on, online, unmetered and on the active server`() {
        val ok = HeroTrailerConditions(settingOn = true, isOnline = true, isMetered = false, screenServerId = null, activeServerId = "a")
        assertTrue(ok.isEligible)
        assertTrue(ok.copy(screenServerId = "a").isEligible)
        assertFalse(ok.copy(settingOn = false).isEligible)
        assertFalse(ok.copy(isOnline = false).isEligible)
        assertFalse(ok.copy(isMetered = true).isEligible)
        assertFalse(ok.copy(screenServerId = "b").isEligible)
    }

    @Test
    fun `the setting defaults off at compact width and on when wide, and a stored choice wins`() {
        assertFalse(HeroTrailerPolicy.settingOn(stored = null, isCompactWidth = true))
        assertTrue(HeroTrailerPolicy.settingOn(stored = null, isCompactWidth = false))
        assertTrue(HeroTrailerPolicy.settingOn(stored = true, isCompactWidth = true))
        assertFalse(HeroTrailerPolicy.settingOn(stored = false, isCompactWidth = false))
    }

    @Test
    fun `silent under a theme or other audio, audible otherwise`() {
        assertTrue(HeroTrailerPolicy.shouldMute(themeIsPlaying = true, otherAudioPlaying = false))
        assertTrue(HeroTrailerPolicy.shouldMute(themeIsPlaying = false, otherAudioPlaying = true))
        assertFalse(HeroTrailerPolicy.shouldMute(themeIsPlaying = false, otherAudioPlaying = false))
    }

    @Test
    fun `a full trailer skips its opening and plays thirty seconds`() {
        assertEquals(HeroTrailerClip(10.0, 40.0), HeroTrailerPolicy.clip(121.0))
        assertEquals(HeroTrailerClip(10.0, 40.0), HeroTrailerPolicy.clip(45.0))
    }

    @Test
    fun `a short clip or unknown length plays from the start`() {
        assertEquals(HeroTrailerClip(0.0, 30.0), HeroTrailerPolicy.clip(44.9))
        assertEquals(0.0, HeroTrailerPolicy.clip(null).start, 0.0)
        assertEquals(30.0, HeroTrailerPolicy.clip(null).end, 0.0)
    }

    @Test
    fun `the clip never runs past the trailer`() {
        assertEquals(HeroTrailerClip(0.0, 20.0), HeroTrailerPolicy.clip(20.0))
    }

    @Test
    fun `the source is the first trailer with its length in seconds`() {
        val source =
            HeroTrailerSource.from(
                listOf(BaseItemDto(id = "t1", runTimeTicks = 1_210_000_000), BaseItemDto(id = "t2", runTimeTicks = 1)),
            )
        assertEquals(HeroTrailerSource("t1", 121.0), source)
        assertEquals(HeroTrailerSource("t3", null), HeroTrailerSource.from(listOf(BaseItemDto(id = "t3"))))
        assertNull(HeroTrailerSource.from(emptyList()))
    }

    @Test
    fun `transcode caps are 1280 at 4 Mbps on a phone and 1920 at 8 Mbps wide`() {
        assertEquals(HeroTrailerCaps(maxBitrate = 4_000_000, maxWidth = 1280), HeroTrailerCaps.forWidth(isCompactWidth = true))
        assertEquals(HeroTrailerCaps(maxBitrate = 8_000_000, maxWidth = 1920), HeroTrailerCaps.forWidth(isCompactWidth = false))
    }
}
