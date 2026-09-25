package dev.bitstorm.sashimi.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import dev.bitstorm.sashimi.core.playback.VideoViewMode
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(UnstableApi::class)
class ViewModeResizeTest {
    @Test
    fun `each mode maps to its Media3 resize mode`() {
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, ViewModeResize.resizeModeFor(VideoViewMode.NORMAL, inPip = false))
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, ViewModeResize.resizeModeFor(VideoViewMode.ZOOM, inPip = false))
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FILL, ViewModeResize.resizeModeFor(VideoViewMode.STRETCH, inPip = false))
    }

    @Test
    fun `picture-in-picture always fits`() {
        VideoViewMode.entries.forEach { mode ->
            assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, ViewModeResize.resizeModeFor(mode, inPip = true))
        }
    }
}
