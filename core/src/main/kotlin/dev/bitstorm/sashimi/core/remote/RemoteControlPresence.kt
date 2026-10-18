package dev.bitstorm.sashimi.core.remote

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update

/**
 * Whether the session socket should be open: while the app is in the
 * foreground (a PiP window counts, since it keeps the activity started) or
 * something is playing. Platform-neutral: the app reports both facts, a TV
 * client would report the same two.
 *
 * Losing both does not disconnect at once. A quick trip to another app and
 * back would otherwise drop the session and make the dashboard flicker; the
 * socket closes only after [graceMs] with neither.
 */
class RemoteControlPresence(
    private val graceMs: Long = DEFAULT_GRACE_MS,
) {
    private val foreground = MutableStateFlow(false)
    private val playing = MutableStateFlow<Set<Any>>(emptySet())

    fun setForeground(value: Boolean) {
        foreground.value = value
    }

    /** Each player reports for itself; [owner] keeps two overlapping players from clearing each other. */
    fun setPlaying(
        owner: Any,
        value: Boolean,
    ) {
        playing.update { if (value) it + owner else it - owner }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val wanted: Flow<Boolean> =
        combine(foreground, playing) { fg, players -> shouldConnect(fg, players.isNotEmpty()) }
            .distinctUntilChanged()
            .transformLatest { want ->
                if (!want) delay(graceMs)
                emit(want)
            }.distinctUntilChanged()

    companion object {
        const val DEFAULT_GRACE_MS = 15_000L

        fun shouldConnect(
            foreground: Boolean,
            playing: Boolean,
        ): Boolean = foreground || playing
    }
}
