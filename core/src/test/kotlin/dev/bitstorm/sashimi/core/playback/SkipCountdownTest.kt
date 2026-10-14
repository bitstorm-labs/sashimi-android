package dev.bitstorm.sashimi.core.playback

import dev.bitstorm.sashimi.core.model.MediaSegmentDto
import dev.bitstorm.sashimi.core.model.MediaSegmentType
import org.junit.Assert.assertEquals
import org.junit.Test

class SkipCountdownTest {
    // A 60 s intro from 0:30 to 1:30.
    private val intro = MediaSegmentDto("s", MediaSegmentType.INTRO, startSeconds = 30.0, endSeconds = 90.0)

    @Test
    fun `fill is the played fraction of the segment`() {
        assertEquals(0f, SkipCountdown.fraction(intro, 30.0), 0.0001f)
        assertEquals(0.25f, SkipCountdown.fraction(intro, 45.0), 0.0001f)
        assertEquals(0.5f, SkipCountdown.fraction(intro, 60.0), 0.0001f)
        assertEquals(1f, SkipCountdown.fraction(intro, 90.0), 0.0001f)
    }

    @Test
    fun `fill is clamped outside the segment (seeks, poll lag)`() {
        assertEquals(0f, SkipCountdown.fraction(intro, 10.0), 0f)
        assertEquals(1f, SkipCountdown.fraction(intro, 200.0), 0f)
    }

    @Test
    fun `a zero-length segment reads as finished, not NaN`() {
        assertEquals(1f, SkipCountdown.fraction(intro.copy(endSeconds = 30.0), 30.0), 0f)
    }

    @Test
    fun `seconds left round up and never go negative`() {
        assertEquals(60, SkipCountdown.secondsLeft(intro, 30.0))
        assertEquals(24, SkipCountdown.secondsLeft(intro, 66.2))
        assertEquals(1, SkipCountdown.secondsLeft(intro, 89.9))
        assertEquals(0, SkipCountdown.secondsLeft(intro, 95.0))
    }

    @Test
    fun `clock is m colon ss`() {
        assertEquals("0:00", SkipCountdown.clock(0))
        assertEquals("0:09", SkipCountdown.clock(9))
        assertEquals("0:24", SkipCountdown.clock(24))
        assertEquals("1:05", SkipCountdown.clock(65))
        assertEquals("12:00", SkipCountdown.clock(720))
        assertEquals("0:00", SkipCountdown.clock(-3))
    }

    @Test
    fun `labels`() {
        assertEquals("Skip Intro", SkipCountdown.label(MediaSegmentType.INTRO))
        assertEquals("Skip Credits", SkipCountdown.label(MediaSegmentType.OUTRO))
        assertEquals("Skip Recap", SkipCountdown.label(MediaSegmentType.RECAP))
        assertEquals("Skip Preview", SkipCountdown.label(MediaSegmentType.PREVIEW))
        assertEquals("Skip", SkipCountdown.label(MediaSegmentType.UNKNOWN))
    }

    @Test
    fun `content description speaks the time left`() {
        assertEquals("Skip Intro, 24 seconds left", SkipCountdown.contentDescription(MediaSegmentType.INTRO, 24))
        assertEquals("Skip Credits, 1 second left", SkipCountdown.contentDescription(MediaSegmentType.OUTRO, 1))
    }
}
