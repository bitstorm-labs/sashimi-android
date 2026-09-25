package dev.bitstorm.sashimi.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipActionsTest {
    @Test
    fun `below API 33 a playing player offers pause between the seeks`() {
        assertEquals(
            listOf(PipAction.SEEK_BACK, PipAction.PAUSE, PipAction.SEEK_FORWARD),
            PipActions.actionsFor(showPlayButton = false, sdkInt = 32),
        )
    }

    @Test
    fun `below API 33 a paused player offers play`() {
        assertEquals(
            listOf(PipAction.SEEK_BACK, PipAction.PLAY, PipAction.SEEK_FORWARD),
            PipActions.actionsFor(showPlayButton = true, sdkInt = 26),
        )
    }

    @Test
    fun `from API 33 the MediaSession supplies the controls`() {
        assertTrue(PipActions.actionsFor(showPlayButton = false, sdkInt = 33).isEmpty())
        assertTrue(PipActions.actionsFor(showPlayButton = true, sdkInt = 36).isEmpty())
    }

    @Test
    fun `Home enters PiP only while playback is under way`() {
        assertTrue(PipActions.shouldEnterOnLeave(showPlayButton = false, isLoading = false, hasError = false))
        assertFalse(PipActions.shouldEnterOnLeave(showPlayButton = true, isLoading = false, hasError = false))
        assertFalse(PipActions.shouldEnterOnLeave(showPlayButton = false, isLoading = true, hasError = false))
        assertFalse(PipActions.shouldEnterOnLeave(showPlayButton = false, isLoading = false, hasError = true))
    }
}
