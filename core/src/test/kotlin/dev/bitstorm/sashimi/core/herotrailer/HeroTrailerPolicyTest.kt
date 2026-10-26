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
    fun `every detail hero has a trailer layer but a YouTube channel's banner`() {
        // The season page plays its series' trailer like the series page (#201).
        assertTrue(HeroTrailerPolicy.hasTrailerLayer(item(ItemType.SEASON, seriesId = "show"), isYouTube = false))
        assertTrue(HeroTrailerPolicy.hasTrailerLayer(item(ItemType.SERIES), isYouTube = false))
        assertTrue(HeroTrailerPolicy.hasTrailerLayer(item(ItemType.EPISODE, seriesId = "show"), isYouTube = true))
        assertFalse(HeroTrailerPolicy.hasTrailerLayer(item(ItemType.SERIES), isYouTube = true))
        assertTrue(HeroTrailerPolicy.hasTrailerLayer(null, isYouTube = false))
    }

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
    fun `movies and series follow the movie and series rules`() {
        assertEquals(HeroTrailerKind.MOVIE, HeroTrailerPolicy.trailerKind(item(ItemType.MOVIE)))
        assertEquals(HeroTrailerKind.SERIES, HeroTrailerPolicy.trailerKind(item(ItemType.SERIES)))
        assertEquals(HeroTrailerKind.SERIES, HeroTrailerPolicy.trailerKind(item(ItemType.SEASON, seriesId = "s")))
        assertEquals(HeroTrailerKind.SERIES, HeroTrailerPolicy.trailerKind(item(ItemType.EPISODE, seriesId = "s")))
    }

    // Movies

    @Test
    fun `a movie trailer skips its opening and plays to its own end`() {
        val clip = HeroTrailerPolicy.clip(121.0, HeroTrailerKind.MOVIE)
        assertEquals(10.0, clip.start, 0.0)
        assertEquals(121.0, clip.end!!, 0.0)
    }

    @Test
    fun `a short movie clip plays from the start to its own end`() {
        val clip = HeroTrailerPolicy.clip(44.9, HeroTrailerKind.MOVIE)
        assertEquals(0.0, clip.start, 0.0)
        assertEquals(44.9, clip.end!!, 0.0)
    }

    @Test
    fun `a movie trailer of unknown length plays until the item ends`() {
        val clip = HeroTrailerPolicy.clip(null, HeroTrailerKind.MOVIE)
        assertEquals(0.0, clip.start, 0.0)
        assertNull(clip.end)
    }

    @Test
    fun `a theme song never changes where a movie trailer ends`() {
        val clip = HeroTrailerPolicy.clip(121.0, HeroTrailerKind.MOVIE)
        assertEquals(121.0, HeroTrailerPolicy.clipEnd(clip, HeroTrailerTheme.Playing)!!, 0.0)
        assertEquals(121.0, HeroTrailerPolicy.clipEnd(clip, HeroTrailerTheme.Ended(at = 25.0))!!, 0.0)
    }

    // Series without a theme: today's thirty-second clip

    @Test
    fun `a series trailer with no theme skips its opening and plays thirty seconds`() {
        val clip = HeroTrailerPolicy.clip(121.0, HeroTrailerKind.SERIES)
        assertEquals(10.0, clip.start, 0.0)
        assertEquals(40.0, HeroTrailerPolicy.clipEnd(clip, HeroTrailerTheme.None)!!, 0.0)
        assertEquals(40.0, HeroTrailerPolicy.clipEnd(HeroTrailerPolicy.clip(45.0, HeroTrailerKind.SERIES), HeroTrailerTheme.None)!!, 0.0)
    }

    @Test
    fun `a short or unknown-length series trailer with no theme plays thirty seconds from the start`() {
        val short = HeroTrailerPolicy.clip(44.9, HeroTrailerKind.SERIES)
        assertEquals(0.0, short.start, 0.0)
        assertEquals(30.0, HeroTrailerPolicy.clipEnd(short, HeroTrailerTheme.None)!!, 0.0)
        val unknown = HeroTrailerPolicy.clip(null, HeroTrailerKind.SERIES)
        assertEquals(0.0, unknown.start, 0.0)
        assertEquals(30.0, HeroTrailerPolicy.clipEnd(unknown, HeroTrailerTheme.None)!!, 0.0)
    }

    @Test
    fun `the series clip never runs past the trailer`() {
        assertEquals(20.0, HeroTrailerPolicy.clipEnd(HeroTrailerPolicy.clip(20.0, HeroTrailerKind.SERIES), HeroTrailerTheme.None)!!, 0.0)
    }

    // Series with a theme: the trailer ends with the theme

    @Test
    fun `while the theme plays the series trailer is not cut at thirty seconds`() {
        val clip = HeroTrailerPolicy.clip(121.0, HeroTrailerKind.SERIES)
        assertEquals(121.0, HeroTrailerPolicy.clipEnd(clip, HeroTrailerTheme.Playing)!!, 0.0)
    }

    @Test
    fun `the series trailer ends when the theme ends`() {
        val clip = HeroTrailerPolicy.clip(121.0, HeroTrailerKind.SERIES)
        // Theme ended 45 s into the trailer: past the 30 s clip, before the trailer's end.
        assertEquals(45.0, HeroTrailerPolicy.clipEnd(clip, HeroTrailerTheme.Ended(at = 45.0))!!, 0.0)
    }

    @Test
    fun `the series trailer still runs twenty seconds when the theme ends sooner`() {
        val full = HeroTrailerPolicy.clip(121.0, HeroTrailerKind.SERIES)
        assertEquals(30.0, HeroTrailerPolicy.clipEnd(full, HeroTrailerTheme.Ended(at = 14.0))!!, 0.0)
        val short = HeroTrailerPolicy.clip(40.0, HeroTrailerKind.SERIES)
        assertEquals(20.0, HeroTrailerPolicy.clipEnd(short, HeroTrailerTheme.Ended(at = 5.0))!!, 0.0)
    }

    @Test
    fun `the series trailer never runs past its own end when the theme outlasts it`() {
        val clip = HeroTrailerPolicy.clip(60.0, HeroTrailerKind.SERIES)
        assertEquals(60.0, HeroTrailerPolicy.clipEnd(clip, HeroTrailerTheme.Playing)!!, 0.0)
        assertEquals(60.0, HeroTrailerPolicy.clipEnd(clip, HeroTrailerTheme.Ended(at = 90.0))!!, 0.0)
        // Shorter than the twenty-second minimum: its own end still wins.
        val tiny = HeroTrailerPolicy.clip(12.0, HeroTrailerKind.SERIES)
        assertEquals(12.0, HeroTrailerPolicy.clipEnd(tiny, HeroTrailerTheme.Ended(at = 3.0))!!, 0.0)
    }

    @Test
    fun `an unknown-length series trailer with a theme runs until the theme ends or the item does`() {
        val clip = HeroTrailerPolicy.clip(null, HeroTrailerKind.SERIES)
        assertNull(HeroTrailerPolicy.clipEnd(clip, HeroTrailerTheme.Playing))
        assertEquals(50.0, HeroTrailerPolicy.clipEnd(clip, HeroTrailerTheme.Ended(at = 50.0))!!, 0.0)
        assertEquals(20.0, HeroTrailerPolicy.clipEnd(clip, HeroTrailerTheme.Ended(at = 8.0))!!, 0.0)
    }

    // The theme as seen by a running trailer

    @Test
    fun `a theme counts once it plays during the trailer and ends where it stopped`() {
        assertEquals(HeroTrailerTheme.Playing, HeroTrailerTheme.None.started())
        assertEquals(HeroTrailerTheme.Ended(at = 33.0), HeroTrailerTheme.Playing.ended(at = 33.0))
    }

    @Test
    fun `a theme ending that was never heard during the trailer changes nothing`() {
        assertEquals(HeroTrailerTheme.None, HeroTrailerTheme.None.ended(at = 12.0))
    }

    @Test
    fun `the first theme ending is the one that counts`() {
        val ended = HeroTrailerTheme.Ended(at = 33.0)
        assertEquals(ended, ended.ended(at = 34.5))
        assertEquals(ended, ended.started())
        assertEquals(HeroTrailerTheme.Playing, HeroTrailerTheme.Playing.started())
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
