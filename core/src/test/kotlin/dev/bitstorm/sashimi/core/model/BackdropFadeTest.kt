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
}
