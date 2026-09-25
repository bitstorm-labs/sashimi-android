package dev.bitstorm.sashimi.ui.player

/**
 * Picture-in-picture transport decisions, kept pure so they can be unit tested.
 *
 * From API 33 the system builds PiP controls from the attached MediaSession, so
 * explicit RemoteActions are only supplied below that. Supplying them on 33+ as
 * well would override the session's controls with a less capable copy.
 */
enum class PipAction {
    SEEK_BACK,
    PLAY,
    PAUSE,
    SEEK_FORWARD,
}

object PipActions {
    /** First API level whose PiP window takes its controls from the MediaSession. */
    const val SESSION_CONTROLS_API = 33

    /** First API level with PictureInPictureParams.Builder.setAutoEnterEnabled. */
    const val AUTO_ENTER_API = 31

    /**
     * The explicit PiP actions to show, left to right, or an empty list when the
     * MediaSession supplies them. [showPlayButton] is Media3's
     * `Util.shouldShowPlayButton`, which is true while paused, ended or idle and
     * false while playing or buffering towards play.
     */
    fun actionsFor(
        showPlayButton: Boolean,
        sdkInt: Int,
    ): List<PipAction> {
        if (sdkInt >= SESSION_CONTROLS_API) return emptyList()
        val toggle = if (showPlayButton) PipAction.PLAY else PipAction.PAUSE
        return listOf(PipAction.SEEK_BACK, toggle, PipAction.SEEK_FORWARD)
    }

    /**
     * Whether pressing Home should drop the player into PiP: only while playback
     * is under way. A paused or finished player should background normally, and
     * the ON_STOP pause then applies.
     */
    fun shouldEnterOnLeave(
        showPlayButton: Boolean,
        isLoading: Boolean,
        hasError: Boolean,
    ): Boolean = !showPlayButton && !isLoading && !hasError
}
