package dev.bitstorm.sashimi.core.herotrailer

import dev.bitstorm.sashimi.core.herotrailer.HeroTrailerOwners.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HeroTrailerOwnersTest {
    private val owners = HeroTrailerOwners()

    @Test
    fun `the first screen starts its trailer`() {
        assertEquals(Decision.Start("s1"), owners.appeared("series", "s1", playingSourceId = null))
        assertEquals("series", owners.topOwner)
    }

    @Test
    fun `drilling into the same show hands the trailer over, and backing out hands it back`() {
        owners.appeared("series", "s1", null)
        assertEquals(Decision.HandOff, owners.appeared("season", "s1", playingSourceId = "s1"))
        assertEquals("season", owners.topOwner)
        // The series underneath is disposed after the season composed: not the top, so nothing stops.
        assertEquals(Decision.Ignore, owners.hidden("series"))
        // Back: the series composes again first, then the season is disposed and popped.
        assertEquals(Decision.HandOff, owners.appeared("series", "s1", playingSourceId = "s1"))
        assertEquals(Decision.Ignore, owners.hidden("season"))
        assertEquals(Decision.Ignore, owners.dismissed("season", playingSourceId = "s1"))
        assertEquals("series", owners.topOwner)
    }

    @Test
    fun `drilling in before the trailer has started hands the pending start over`() {
        // The start waits out a settle delay, so a quick tap through to the
        // episode (or "Go to Series") arrives while nothing is playing yet. That
        // must keep the scheduled start, not count it as already played.
        assertEquals(Decision.Start("s1"), owners.appeared("episode", "s1", null))
        assertEquals(Decision.HandOff, owners.appeared("series", "s1", playingSourceId = null))
        assertEquals("series", owners.topOwner)
        assertEquals(Decision.Ignore, owners.hidden("episode"))
        // Back before it starts: the episode takes the pending start back.
        assertEquals(Decision.HandOff, owners.appeared("episode", "s1", playingSourceId = null))
        assertEquals(Decision.Ignore, owners.dismissed("series", playingSourceId = null))
    }

    @Test
    fun `a stopped trailer does not replay in the same visit`() {
        owners.appeared("movie", "m", null)
        // Play pressed: the player covers the screen and the trailer stops.
        assertEquals(Decision.Stop, owners.hidden("movie"))
        // Returning from the player re-shows the same screen: no replay.
        assertEquals(Decision.Stop, owners.appeared("movie", "m", playingSourceId = null))
        // Nor in a screen drilled into from there.
        assertEquals(Decision.Stop, owners.appeared("season", "m", playingSourceId = null))
    }

    @Test
    fun `a trailer that ran out never starts again in the same visit`() {
        owners.appeared("series", "s1", null)
        // It played and ended on its own (nothing playing now): not a fresh start.
        assertNotEquals(Decision.Start("s1"), owners.appeared("season", "s1", playingSourceId = null))
        assertNotEquals(Decision.Start("s1"), owners.appeared("series", "s1", playingSourceId = null))
    }

    @Test
    fun `a new visit plays it again`() {
        owners.appeared("series", "s1", null)
        assertEquals(Decision.Stop, owners.dismissed("series", playingSourceId = null))
        assertEquals(Decision.Start("s1"), owners.appeared("series-again", "s1", null))
    }

    @Test
    fun `a different title on top starts its own, and backing out to the first stops`() {
        owners.appeared("movie-a", "a", null)
        assertEquals(Decision.Start("b"), owners.appeared("movie-b", "b", playingSourceId = "a"))
        assertEquals(Decision.Stop, owners.dismissed("movie-b", playingSourceId = "b"))
        assertEquals("movie-a", owners.topOwner)
    }

    @Test
    fun `the top screen being covered stops it`() {
        owners.appeared("movie", "m", null)
        assertEquals(Decision.Stop, owners.hidden("movie"))
    }

    @Test
    fun `a screen with no trailer stops the one underneath`() {
        owners.appeared("movie", "m", null)
        assertEquals(Decision.Stop, owners.appeared("collection", null, playingSourceId = "m"))
    }

    @Test
    fun `dismissing a covered screen changes nothing`() {
        owners.appeared("a", "x", null)
        owners.appeared("b", "y", null)
        assertEquals(Decision.Ignore, owners.dismissed("a", playingSourceId = "y"))
        assertEquals(Decision.Ignore, owners.dismissed("never-seen", playingSourceId = "y"))
        assertEquals("b", owners.topOwner)
    }
}
