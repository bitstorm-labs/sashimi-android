package dev.bitstorm.sashimi.core.playback

import dev.bitstorm.sashimi.core.model.BaseItemDto

/** What the player does when an item plays to the end. */
enum class EndOfItemAction {
    /** Leave the player: no next episode, it could not be found, or nothing should be shown. */
    EXIT,

    /** Start the next episode straight away, with no screen (picture-in-picture). */
    AUTO_ADVANCE,

    /** Show the full-screen Up Next screen. */
    SHOW_UP_NEXT,
}

/** The episode after the one the Up Next screen shows, which Skip moves to. */
sealed interface FollowingEpisode {
    /** Still being looked up: Skip is hidden until it is known. */
    data object Pending : FollowingEpisode

    /** There is none (the shown episode is the last), or the lookup failed. */
    data object None : FollowingEpisode

    data class Found(val episode: BaseItemDto) : FollowingEpisode
}

/**
 * The Up Next screen's state. Immutable; every change goes through [UpNext.reduce].
 *
 * [remainingMs] is null when there is no countdown: auto-play is off, or the
 * user cancelled it.
 */
data class UpNextState(
    val episode: BaseItemDto,
    val following: FollowingEpisode = FollowingEpisode.Pending,
    /** Auto-play was on when the screen opened: Skip restarts the countdown. */
    val autoPlay: Boolean,
    val totalMs: Long = UpNext.COUNTDOWN_MS,
    val remainingMs: Long?,
    /** The app is in the background: the countdown holds where it is. */
    val paused: Boolean = false,
    /** Cancel was pressed: no countdown, end options (Replay / Done) instead of Skip / Cancel. */
    val cancelled: Boolean = false,
) {
    val hasCountdown: Boolean get() = remainingMs != null && !cancelled

    val countdownRunning: Boolean get() = hasCountdown && !paused

    val canSkip: Boolean get() = !cancelled && following is FollowingEpisode.Found

    /**
     * Skip is on screen unless there is known to be nothing after (or the
     * countdown was cancelled). It stays up while the lookup runs, so the
     * buttons do not jump and D-pad focus on it is not lost after each Skip; a
     * press before the answer is simply ignored.
     */
    val showSkip: Boolean get() = !cancelled && following !is FollowingEpisode.None

    /** How far the Play button's fill has swept, 0..1. Zero without a countdown. */
    val progress: Float
        get() {
            val remaining = remainingMs ?: return 0f
            if (!hasCountdown || totalMs <= 0) return 0f
            return ((totalMs - remaining).toFloat() / totalMs).coerceIn(0f, 1f)
        }

    /** Whole seconds left, rounded up, so "10" shows for the first second and "1" for the last. */
    val secondsRemaining: Int?
        get() = remainingMs?.takeIf { hasCountdown }?.let { ((it + 999) / 1000).toInt() }
}

sealed interface UpNextEvent {
    /** [elapsedMs] of wall-clock time has passed since the last tick. */
    data class Tick(val elapsedMs: Long) : UpNextEvent

    /** The app went to the background. */
    data object Pause : UpNextEvent

    /** The app came back to the foreground. */
    data object Resume : UpNextEvent

    /** Cancel, or Back. */
    data object Cancel : UpNextEvent

    data object Skip : UpNextEvent

    /** The lookup of the episode after [forEpisodeId] finished; [next] null for none or a failure. */
    data class FollowingResolved(val forEpisodeId: String, val next: BaseItemDto?) : UpNextEvent

    /** The player went into picture-in-picture while the screen was up. */
    data object EnteredPip : UpNextEvent
}

/** The new state, and whether the shown episode should start now. */
data class UpNextStep(
    val state: UpNextState,
    val playNow: Boolean = false,
)

/**
 * The Up Next screen's rules and countdown, shared by every client surface.
 * Pure: time comes in as [UpNextEvent.Tick], so it runs the same on a phone, a
 * TV, or a unit test.
 */
object UpNext {
    const val COUNTDOWN_MS = 10_000L

    /**
     * What happens when an item ends.
     *
     * Auto-play on and off both show the screen (off has no countdown); it used
     * to start the next episode without a word, or leave the player when
     * auto-play was off. Picture-in-picture never shows it: the window is
     * thumbnail-sized, so auto-play advances directly and, with auto-play off,
     * the player leaves as it always did.
     */
    fun onEnded(
        hasNext: Boolean,
        autoPlay: Boolean,
        inPip: Boolean,
    ): EndOfItemAction =
        when {
            !hasNext -> EndOfItemAction.EXIT
            inPip -> if (autoPlay) EndOfItemAction.AUTO_ADVANCE else EndOfItemAction.EXIT
            else -> EndOfItemAction.SHOW_UP_NEXT
        }

    fun start(
        next: BaseItemDto,
        autoPlay: Boolean,
        countdownMs: Long = COUNTDOWN_MS,
    ): UpNextState =
        UpNextState(
            episode = next,
            autoPlay = autoPlay,
            totalMs = countdownMs,
            remainingMs = if (autoPlay) countdownMs else null,
        )

    fun reduce(
        state: UpNextState,
        event: UpNextEvent,
    ): UpNextStep =
        when (event) {
            is UpNextEvent.Tick -> {
                val remaining = state.remainingMs
                if (!state.countdownRunning || remaining == null || event.elapsedMs <= 0) {
                    UpNextStep(state)
                } else {
                    val left = (remaining - event.elapsedMs).coerceAtLeast(0)
                    UpNextStep(state.copy(remainingMs = left), playNow = left == 0L)
                }
            }
            UpNextEvent.Pause -> UpNextStep(state.copy(paused = true))
            UpNextEvent.Resume -> UpNextStep(state.copy(paused = false))
            UpNextEvent.Cancel -> UpNextStep(state.copy(cancelled = true, remainingMs = null))
            UpNextEvent.Skip -> {
                val following = state.following
                if (!state.canSkip || following !is FollowingEpisode.Found) {
                    UpNextStep(state)
                } else {
                    UpNextStep(
                        state.copy(
                            episode = following.episode,
                            following = FollowingEpisode.Pending,
                            // A fresh ten seconds for the new episode.
                            remainingMs = if (state.autoPlay) state.totalMs else null,
                        ),
                    )
                }
            }
            is UpNextEvent.FollowingResolved -> {
                // A lookup for an episode Skip has since moved past is stale.
                if (event.forEpisodeId != state.episode.id) {
                    UpNextStep(state)
                } else {
                    val following = event.next?.let { FollowingEpisode.Found(it) } ?: FollowingEpisode.None
                    UpNextStep(state.copy(following = following))
                }
            }
            // Auto-play keeps going in PiP, where the screen is never shown.
            UpNextEvent.EnteredPip -> UpNextStep(state, playNow = state.hasCountdown)
        }
}
