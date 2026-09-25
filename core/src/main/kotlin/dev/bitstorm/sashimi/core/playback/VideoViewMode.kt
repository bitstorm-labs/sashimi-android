package dev.bitstorm.sashimi.core.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How the picture is scaled to the screen. Names, order and stored [key]s match
 * sashimi-apple#510 and sashimi-roku#157, so the three clients read the same.
 */
enum class VideoViewMode(
    val key: String,
    val label: String,
) {
    /** Fit the whole picture inside the screen, letterboxed or pillarboxed. */
    NORMAL("normal", "Normal"),

    /** Fill the screen at the picture's own shape, cropping the overflow. */
    ZOOM("zoom", "Zoom"),

    /** Fill the screen exactly, distorting the picture's shape. */
    STRETCH("stretch", "Stretch"),
    ;

    companion object {
        /** A fresh install uses Normal. */
        val DEFAULT = NORMAL

        /** The stored [key] back to a mode; unknown or missing is [DEFAULT]. */
        fun fromKey(key: String?): VideoViewMode = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** Where the saved default view mode lives. SharedPreferences in the app; a fake in tests. */
interface ViewModeDefaultPersistence {
    fun load(): String?

    fun save(key: String)
}

/**
 * The session-vs-default rule for [VideoViewMode], the port of Apple's
 * `VideoViewModeStore`:
 *
 * - A pick in the player lasts for the app session. It is held in memory only.
 * - With no session pick, the saved default applies.
 * - Setting the default, from "Use for All Videos" or from Settings, clears the
 *   session pick. Otherwise a Settings change made after a player pick would
 *   appear to do nothing until the app restarts.
 *
 * One instance lives for the process (it hangs off the app's settings), which is
 * what makes a player pick carry over to later videos.
 */
class VideoViewModeStore(
    private val persistence: ViewModeDefaultPersistence,
) {
    private val _defaultMode = MutableStateFlow(VideoViewMode.fromKey(persistence.load()))
    val defaultMode: StateFlow<VideoViewMode> = _defaultMode.asStateFlow()

    private val _sessionMode = MutableStateFlow<VideoViewMode?>(null)

    /** The player pick for this app session, or null when the default applies. */
    val sessionMode: StateFlow<VideoViewMode?> = _sessionMode.asStateFlow()

    /** The mode playback should use right now. */
    val activeMode: VideoViewMode get() = resolve(_sessionMode.value, _defaultMode.value)

    /** A pick from the player: applies now and to later videos this session, not persisted. */
    fun selectForSession(mode: VideoViewMode) {
        _sessionMode.value = mode
    }

    /** Save [mode] as the default and drop the session pick, so the newer decision wins. */
    fun setDefault(mode: VideoViewMode) {
        persistence.save(mode.key)
        _defaultMode.value = mode
        _sessionMode.value = null
    }

    /** "Use for All Videos": the active mode becomes the saved default. */
    fun useForAllVideos() = setDefault(activeMode)

    companion object {
        /** The session pick if there is one, otherwise the default. */
        fun resolve(
            sessionMode: VideoViewMode?,
            defaultMode: VideoViewMode,
        ): VideoViewMode = sessionMode ?: defaultMode
    }
}
