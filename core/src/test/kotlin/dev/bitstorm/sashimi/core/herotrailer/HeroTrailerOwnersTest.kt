package dev.bitstorm.sashimi.core.herotrailer

import dev.bitstorm.sashimi.core.herotrailer.HeroTrailerOwners.Decision
import org.junit.Assert.assertEquals
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
    fun `a finished trailer does not replay in the same visit`() {
        owners.appeared("series", "s1", null)
        assertEquals(Decision.Stop, owners.appeared("season", "s1", playingSourceId = null))
        // Returning from the player re-shows the same screen: still no replay.
        assertEquals(Decision.Stop, owners.appeared("season", "s1", playingSourceId = null))
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
