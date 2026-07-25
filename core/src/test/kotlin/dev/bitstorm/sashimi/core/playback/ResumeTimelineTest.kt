package dev.bitstorm.sashimi.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TICKS_PER_MS = 10_000L
private const val NINETY_MIN_MS = 90L * 60 * 1000
private val NINETY_MIN_TICKS = NINETY_MIN_MS * TICKS_PER_MS

class ResumeTimelineTest {
    // MARK: - Which timeline the player starts on

    @Test
    fun `transcode starts the player at zero because the stream already starts at the resume point`() {
        assertEquals(0L, ResumeTimeline.playerStartMs(isTranscoding = true, resumeTicks = NINETY_MIN_TICKS))
    }

    @Test
    fun `direct play seeks, because the stream serves the whole file`() {
        assertEquals(NINETY_MIN_MS, ResumeTimeline.playerStartMs(isTranscoding = false, resumeTicks = NINETY_MIN_TICKS))
    }

    @Test
    fun `a transcode from the beginning has no offset to special-case`() {
        assertEquals(0L, ResumeTimeline.playerStartMs(isTranscoding = true, resumeTicks = 0))
        assertEquals(0L, ResumeTimeline.timelineOffsetMs(isTranscoding = true, resumeTicks = 0))
    }

    @Test
    fun `only a resumed transcode carries an offset`() {
        assertEquals(NINETY_MIN_MS, ResumeTimeline.timelineOffsetMs(isTranscoding = true, resumeTicks = NINETY_MIN_TICKS))
        assertEquals(0L, ResumeTimeline.timelineOffsetMs(isTranscoding = false, resumeTicks = NINETY_MIN_TICKS))
    }

    // MARK: - The regression this class exists for

    @Test
    fun `five minutes into a transcode resumed at ninety reports ninety-five, not five`() {
        // The original defect: the raw player position was reported verbatim, so
        // the server was told the user had reached 5 minutes and 85 minutes of
        // progress were destroyed for every client.
        val offset = ResumeTimeline.timelineOffsetMs(isTranscoding = true, resumeTicks = NINETY_MIN_TICKS)
        val fiveMinutesIn = 5L * 60 * 1000

        assertEquals(95L * 60 * 1000, ResumeTimeline.absoluteMs(offset, fiveMinutesIn))
    }

    @Test
    fun `direct play position passes through untouched`() {
        val offset = ResumeTimeline.timelineOffsetMs(isTranscoding = false, resumeTicks = NINETY_MIN_TICKS)
        assertEquals(NINETY_MIN_MS, ResumeTimeline.absoluteMs(offset, NINETY_MIN_MS))
    }

    @Test
    fun `absolute and timeline conversions round-trip`() {
        val offset = ResumeTimeline.timelineOffsetMs(isTranscoding = true, resumeTicks = NINETY_MIN_TICKS)
        val absolute = 95L * 60 * 1000
        assertEquals(absolute, ResumeTimeline.absoluteMs(offset, ResumeTimeline.timelineMs(offset, absolute)))
    }

    @Test
    fun `an unset or negative player position never reads as before the resume point`() {
        // ExoPlayer reports 0 (and briefly negative values on some devices)
        // before the timeline is known; that must not report progress that
        // precedes where the stream actually starts.
        val offset = ResumeTimeline.timelineOffsetMs(isTranscoding = true, resumeTicks = NINETY_MIN_TICKS)
        assertEquals(NINETY_MIN_MS, ResumeTimeline.absoluteMs(offset, 0))
        assertEquals(NINETY_MIN_MS, ResumeTimeline.absoluteMs(offset, -1_000))
    }

    // MARK: - Seeking backwards past the resume point

    @Test
    fun `scrubbing before the resume point needs a re-negotiate, not a seek`() {
        val offset = ResumeTimeline.timelineOffsetMs(isTranscoding = true, resumeTicks = NINETY_MIN_TICKS)
        assertTrue(ResumeTimeline.requiresRenegotiation(offset, absoluteTargetMs = 10L * 60 * 1000))
        assertFalse(ResumeTimeline.requiresRenegotiation(offset, absoluteTargetMs = 95L * 60 * 1000))
    }

    @Test
    fun `direct play never needs a re-negotiate to seek anywhere`() {
        val offset = ResumeTimeline.timelineOffsetMs(isTranscoding = false, resumeTicks = NINETY_MIN_TICKS)
        assertFalse(ResumeTimeline.requiresRenegotiation(offset, absoluteTargetMs = 0))
    }

    @Test
    fun `a target before the stream start clamps rather than going negative`() {
        val offset = ResumeTimeline.timelineOffsetMs(isTranscoding = true, resumeTicks = NINETY_MIN_TICKS)
        assertEquals(0L, ResumeTimeline.timelineMs(offset, absoluteMs = 0))
    }
}
