package dev.bitstorm.sashimi.core.themesong

import dev.bitstorm.sashimi.core.model.ItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeTargetTest {
    @Test
    fun `an active-server show keeps the bare series id as its visit key`() {
        assertEquals("s1", ThemeTarget(serverId = null, seriesId = "s1").visitKey)
    }

    @Test
    fun `the same series id on another server is a different visit`() {
        val active = ThemeTarget(null, "s1")
        val other = ThemeTarget("b", "s1")
        assertNotEquals(active.visitKey, other.visitKey)
        assertEquals("b/s1", other.visitKey)

        val visits = ThemeVisitState()
        visits.appeared(active.visitKey)
        val action = visits.appeared(other.visitKey)
        assertEquals(ThemeVisitAction.Start(other.visitKey, replacing = active.visitKey), action)
    }

    @Test
    fun `episodes of a show on another server target that show on that server`() {
        assertEquals(ThemeTarget("b", "series-1"), themeTargetFor(ItemType.EPISODE, "ep-1", "series-1", "b"))
        assertEquals(ThemeTarget("b", "series-1"), themeTargetFor(ItemType.SERIES, "series-1", null, "b"))
        assertNull(themeTargetFor(ItemType.MOVIE, "m1", null, "b"))
    }
}
