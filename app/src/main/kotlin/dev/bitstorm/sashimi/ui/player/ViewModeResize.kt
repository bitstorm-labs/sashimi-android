package dev.bitstorm.sashimi.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import dev.bitstorm.sashimi.core.playback.VideoViewMode

/** Maps a [VideoViewMode] onto the PlayerView's resize mode. Pure, so it is unit tested. */
@OptIn(UnstableApi::class)
object ViewModeResize {
    /**
     * The `AspectRatioFrameLayout` resize mode for [mode]. Picture-in-picture
     * always fits: the PiP window already takes the video's shape, so Zoom would
     * crop the thumbnail and Stretch would distort it for no gain.
     */
    fun resizeModeFor(
        mode: VideoViewMode,
        inPip: Boolean,
    ): Int {
        if (inPip) return AspectRatioFrameLayout.RESIZE_MODE_FIT
        return when (mode) {
            VideoViewMode.NORMAL -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            VideoViewMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            VideoViewMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
    }
}
