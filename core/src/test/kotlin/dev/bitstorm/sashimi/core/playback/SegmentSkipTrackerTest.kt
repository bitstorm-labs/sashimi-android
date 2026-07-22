package dev.bitstorm.sashimi.core.playback

import dev.bitstorm.sashimi.core.model.MediaSegmentDto
import dev.bitstorm.sashimi.core.model.MediaSegmentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun seg(
    id: String,
    type: MediaSegmentType,
    start: Double,
    end: Double,
) = MediaSegmentDto(id = id, type = type, startSeconds = start, endSeconds = end)

class SegmentSkipTrackerTest {
    private val intro = seg("intro", MediaSegmentType.INTRO, 5.0, 30.0)
    private val credits = seg("credits", MediaSegmentType.OUTRO, 1400.0, 1500.0)

    @Test
    fun `activeSegment inside range, none before or after`() {
        val t = SegmentSkipTracker(listOf(intro, credits))
        assertNull(t.activeSegment(4.9))
        assertEquals("intro", t.activeSegment(5.0)?.id)
        assertEquals("intro", t.activeSegment(29.9)?.id)
        // end is exclusive
        assertNull(t.activeSegment(30.0))
        assertEquals("credits", t.activeSegment(1450.0)?.id)
    }

    @Test
    fun `segment offered once then suppressed`() {
        val t = SegmentSkipTracker(listOf(intro))
        assertEquals("intro", t.activeSegment(10.0)?.id)
        t.markSkipped("intro")
        // Re-entering the same range must not offer it again.
        assertNull(t.activeSegment(10.0))
    }

    @Test
    fun `autoSkipTarget gates on the matching setting`() {
        val t = SegmentSkipTracker(listOf(intro, credits))
        // Intro only when autoSkipIntro is on.
        assertNull(t.autoSkipTarget(10.0, autoSkipIntro = false, autoSkipCredits = true))
        assertEquals("intro", t.autoSkipTarget(10.0, autoSkipIntro = true, autoSkipCredits = false)?.id)
        // Credits only when autoSkipCredits is on.
        assertEquals("credits", t.autoSkipTarget(1450.0, autoSkipIntro = false, autoSkipCredits = true)?.id)
        assertNull(t.autoSkipTarget(1450.0, autoSkipIntro = true, autoSkipCredits = false))
    }

    @Test
    fun `autoSkipTarget respects skip-once`() {
        val t = SegmentSkipTracker(listOf(intro))
        assertEquals("intro", t.autoSkipTarget(10.0, autoSkipIntro = true, autoSkipCredits = false)?.id)
        t.markSkipped("intro")
        assertNull(t.autoSkipTarget(10.0, autoSkipIntro = true, autoSkipCredits = false))
    }

    @Test
    fun `allSkipped reflects state`() {
        val t = SegmentSkipTracker(listOf(intro, credits))
        assertFalse(t.allSkipped)
        t.markSkipped("intro")
        assertFalse(t.allSkipped)
        t.markSkipped("credits")
        assertTrue(t.allSkipped)
    }
}
