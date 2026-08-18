package dev.bitstorm.sashimi.core.themesong

import dev.bitstorm.sashimi.core.model.ItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeVisitStateTest {
    private val showA = "series-a"
    private val showB = "series-b"

    @Test
    fun `first detail screen for a show starts its theme`() {
        val state = ThemeVisitState()
        assertEquals(ThemeVisitAction.Start(showA, replacing = null), state.appeared(showA))
        assertEquals(showA, state.currentKey)
    }

    @Test
    fun `drilling into a season or episode of the same show does not restart`() {
        val state = ThemeVisitState()
        state.appeared(showA)
        // A season, then an episode -- both resolve to the same series key.
        assertEquals(ThemeVisitAction.None, state.appeared(showA))
        assertEquals(ThemeVisitAction.None, state.appeared(showA))
        assertEquals(3, state.depthOf(showA))
    }

    @Test
    fun `backing out of a drilled-into screen does not stop while one remains`() {
        val state = ThemeVisitState()
        state.appeared(showA)
        state.appeared(showA)
        assertEquals(ThemeVisitAction.None, state.disappeared(showA))
        assertEquals(1, state.depthOf(showA))
        assertEquals(showA, state.currentKey)
    }

    @Test
    fun `the last detail screen going away stops the theme`() {
        val state = ThemeVisitState()
        state.appeared(showA)
        assertEquals(ThemeVisitAction.Stop, state.disappeared(showA))
        assertEquals(0, state.depthOf(showA))
        assertNull(state.currentKey)
    }

    @Test
    fun `depth only reaches zero after every screen for the show has gone`() {
        val state = ThemeVisitState()
        state.appeared(showA)
        state.appeared(showA)
        state.appeared(showA)
        assertEquals(ThemeVisitAction.None, state.disappeared(showA))
        assertEquals(ThemeVisitAction.None, state.disappeared(showA))
        assertEquals(ThemeVisitAction.Stop, state.disappeared(showA))
    }

    @Test
    fun `a video player round trip is silent because it never touches the depth`() {
        val state = ThemeVisitState()
        assertEquals(ThemeVisitAction.Start(showA, replacing = null), state.appeared(showA))
        // Opening the player and coming back produces no visit events at all --
        // the player is not a detail screen. The show stays at depth 1 the whole
        // time, so there is no second Start to make noise on the way back.
        assertEquals(1, state.depthOf(showA))
        assertEquals(showA, state.currentKey)
    }

    @Test
    fun `a second show starts its own theme and names the one it replaced`() {
        val state = ThemeVisitState()
        state.appeared(showA)
        assertEquals(ThemeVisitAction.Start(showB, replacing = showA), state.appeared(showB))
        assertEquals(showB, state.currentKey)
    }

    @Test
    fun `the replaced show closing does not silence the show that replaced it`() {
        val state = ThemeVisitState()
        state.appeared(showA)
        state.appeared(showB)
        assertEquals(ThemeVisitAction.None, state.disappeared(showA))
        assertEquals(showB, state.currentKey)
    }

    @Test
    fun `closing the replacing show stops, and the show underneath does not resume`() {
        val state = ThemeVisitState()
        state.appeared(showA)
        state.appeared(showB)
        assertEquals(ThemeVisitAction.Stop, state.disappeared(showB))
        assertNull(state.currentKey)
        // showA is still on the back stack at depth 1, but its visit is long
        // over: one play per show-visit, so nothing restarts.
        assertEquals(1, state.depthOf(showA))
        assertEquals(ThemeVisitAction.None, state.disappeared(showA))
    }

    @Test
    fun `a disappearance for a show that was never seen is ignored`() {
        val state = ThemeVisitState()
        assertEquals(ThemeVisitAction.None, state.disappeared(showA))
        assertEquals(0, state.depthOf(showA))
        assertNull(state.currentKey)
    }

    @Test
    fun `an unbalanced extra disappearance cannot drive the depth negative`() {
        val state = ThemeVisitState()
        state.appeared(showA)
        state.disappeared(showA)
        assertEquals(ThemeVisitAction.None, state.disappeared(showA))
        assertEquals(0, state.depthOf(showA))
    }

    @Test
    fun `reset clears every show and the current key`() {
        val state = ThemeVisitState()
        state.appeared(showA)
        state.appeared(showA)
        state.reset()
        assertEquals(0, state.depthOf(showA))
        assertNull(state.currentKey)
        // A fresh visit after a reset is a Start again.
        assertEquals(ThemeVisitAction.Start(showA, replacing = null), state.appeared(showA))
    }

    @Test
    fun `re-entering a show after fully leaving it plays again`() {
        val state = ThemeVisitState()
        state.appeared(showA)
        state.disappeared(showA)
        assertEquals(ThemeVisitAction.Start(showA, replacing = null), state.appeared(showA))
    }

    // MARK: - key resolution

    @Test
    fun `a series is its own theme key`() {
        assertEquals("s1", themeKeyFor(ItemType.SERIES, "s1", seriesId = null))
    }

    @Test
    fun `a season and an episode use their parent series`() {
        assertEquals("s1", themeKeyFor(ItemType.SEASON, "season-1", seriesId = "s1"))
        assertEquals("s1", themeKeyFor(ItemType.EPISODE, "ep-1", seriesId = "s1"))
    }

    @Test
    fun `an episode with no series id has no key`() {
        assertNull(themeKeyFor(ItemType.EPISODE, "ep-1", seriesId = null))
    }

    @Test
    fun `movies videos and everything else never play a theme`() {
        assertNull(themeKeyFor(ItemType.MOVIE, "m1", seriesId = null))
        // A movie that somehow carries a series id still has no theme key.
        assertNull(themeKeyFor(ItemType.MOVIE, "m1", seriesId = "s1"))
        assertNull(themeKeyFor(ItemType.VIDEO, "v1", seriesId = "s1"))
        assertNull(themeKeyFor(ItemType.BOX_SET, "b1", seriesId = "s1"))
        assertNull(themeKeyFor(ItemType.FOLDER, "f1", seriesId = "s1"))
        assertNull(themeKeyFor(ItemType.UNKNOWN, "u1", seriesId = "s1"))
        assertNull(themeKeyFor(null, "x1", seriesId = "s1"))
    }
}
