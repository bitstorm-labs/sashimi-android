package dev.bitstorm.sashimi.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class BackdropFadeTest {
    @Test
    fun `Lightest fade - left edge opaque from 10 percent`() {
        assertEquals(0.10f, BackdropFade.LEFT_FADE_END, 0.0001f)
    }

    @Test
    fun `Lightest fade - bottom stays opaque to 92 percent`() {
        assertEquals(0.92f, BackdropFade.BOTTOM_FADE_START, 0.0001f)
    }

    @Test
    fun `a phone hero fades in from the top like the left edge`() {
        assertEquals(BackdropFade.LEFT_FADE_END, BackdropFade.TOP_FADE_END, 0.0001f)
        assertEquals(
            listOf(0f to false, 0.10f to true, 0.92f to true, 1f to false),
            BackdropFade.verticalStops(fadeTop = true),
        )
    }

    @Test
    fun `a hero that runs off the top keeps a square top`() {
        assertEquals(listOf(0.92f to true, 1f to false), BackdropFade.verticalStops(fadeTop = false))
    }
}
