package dev.bitstorm.sashimi.themesong

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.settings.AppSettings
import dev.bitstorm.sashimi.core.themesong.ThemeVisitAction
import dev.bitstorm.sashimi.core.themesong.ThemeVisitState
import dev.bitstorm.sashimi.core.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Plays a show's theme song while its detail screen is on the back stack.
 *
 * This is an app-level singleton (see
 * [dev.bitstorm.sashimi.di.ServiceLocator.themeSongs]) and it owns **all** of
 * the decisions: the ExoPlayer, the visit bookkeeping, the resolved-id cache
 * and the fades. Screens only report intent — "a detail screen for show X
 * became current" / "…went away" — and never start or stop playback
 * themselves. That is the same shape as the tvOS, iOS and Roku clients, and it
 * is what keeps the interesting logic in one testable place
 * ([ThemeVisitState], in `:core`).
 *
 * Behaviour, matched to the other clients:
 *  - one play per show-visit, keyed on series id;
 *  - [START_DELAY_MS] before anything is heard, so flicking through a row of
 *    shows does not blip audio;
 *  - fades: in [FADE_IN_MS], show-change [FADE_SHOW_CHANGE_MS], end-of-track
 *    [FADE_END_MS] started *before* the track runs out, and a
 *    [FADE_HARD_CUT_MS] cut when video or a trailer starts — deliberately the
 *    fastest of the four, because the theme has to be gone before the player's
 *    own audio begins;
 *  - [TARGET_VOLUME] of system volume;
 *  - every failure path is silence. No toasts, no snackbars, no dialogs.
 *
 * Commands are serialised through [submit] and every one of them runs on the
 * main dispatcher, which is both what ExoPlayer requires and what makes the
 * plain (unsynchronised) [ThemeVisitState] and cache safe.
 */
@OptIn(UnstableApi::class)
class ThemeSongService(
    context: Context,
    private val client: JellyfinClient,
    private val settings: AppSettings,
    appScope: CoroutineScope,
) {
    private val appContext = context.applicationContext

    private val scope = CoroutineScope(appScope.coroutineContext + Dispatchers.Main.immediate)

    private val visits = ThemeVisitState()

    /**
     * Resolved theme item id per series for the process lifetime, **including
     * misses** (a present key with a null value). Without caching the misses,
     * the ~41% of a library that has no theme would be re-queried on every
     * single visit. A failed *request* is deliberately not cached — that is
     * "could not ask", not "there is none".
     */
    private val resolved = mutableMapOf<String, String?>()

    private var player: ExoPlayer? = null

    /** The single in-flight command, so a later one cannot race an earlier one. */
    private var command: Job? = null

    private val listener =
        object : Player.Listener {
            override fun onPlayWhenReadyChanged(
                playWhenReady: Boolean,
                reason: Int,
            ) {
                // Audio focus: a call, an alarm or another app's media took over.
                // A theme song yields, and yields for good — silently resuming a
                // show's title music after a three-minute phone call would be
                // worse than never playing it.
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                    submit { cut(0) }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                submit { cut(0) }
            }
        }

    init {
        // Turning the setting off mid-play stops what is already playing.
        scope.launch {
            settings.themeSongsEnabled.collect { enabled ->
                if (!enabled) submit { cut(FADE_HARD_CUT_MS) }
            }
        }
    }

    // MARK: - Intent reported by screens

    /** A detail screen for [seriesId] joined the back stack. */
    fun detailAppeared(seriesId: String) {
        val action = visits.appeared(seriesId)
        if (action is ThemeVisitAction.Start) {
            submit { play(action.seriesId, replacing = action.replacing != null) }
        }
    }

    /** A detail screen for [seriesId] left the back stack for good. */
    fun detailDisappeared(seriesId: String) {
        if (visits.disappeared(seriesId) is ThemeVisitAction.Stop) {
            submit { cut(FADE_SHOW_CHANGE_MS) }
        }
    }

    // MARK: - Intent reported by the app

    /**
     * Video playback or a trailer is starting. The fastest fade of the four on
     * purpose: the theme must be silent before the player's audio begins.
     */
    fun stopForPlayback() {
        submit { cut(FADE_HARD_CUT_MS) }
    }

    /** The app left the foreground. Nothing ambient should outlive that. */
    fun onAppBackgrounded() {
        submit { cut(0) }
    }

    // MARK: - Execution

    /**
     * Runs [block] after the previous command has fully unwound. Serialising
     * this way means every command can assume it is the only one touching the
     * player, and that a fade it interrupts left the player in a state the next
     * command handles (each one either fades from wherever the volume is, or
     * replaces the player outright).
     */
    private fun submit(block: suspend () -> Unit) {
        val previous = command
        command =
            scope.launch {
                previous?.cancelAndJoin()
                block()
            }
    }

    private suspend fun play(
        seriesId: String,
        replacing: Boolean,
    ) {
        if (!settings.themeSongsEnabled.value) return
        // Whatever the previous show was playing goes first.
        if (player != null) cut(if (replacing) FADE_SHOW_CHANGE_MS else FADE_HARD_CUT_MS)

        delay(START_DELAY_MS)

        val themeId = resolveThemeId(seriesId) ?: return
        val url = client.themeAudioStreamUrl(themeId) ?: return
        // The visit can have moved on while the lookup was in flight.
        if (visits.currentKey != seriesId) return

        val active = obtainPlayer()
        active.volume = 0f
        active.setMediaItem(MediaItem.fromUri(url))
        active.prepare()
        active.play()
        fadeTo(TARGET_VOLUME, FADE_IN_MS)
        awaitEnd(active)
    }

    /**
     * The theme item id for [seriesId], from cache when known. Only a definite
     * answer from the server is cached; a thrown request is left uncached so a
     * transient failure does not permanently mute a show.
     */
    private suspend fun resolveThemeId(seriesId: String): String? {
        if (resolved.containsKey(seriesId)) return resolved[seriesId]
        val result = runCatchingCancellable { client.getThemeSongItemId(seriesId) }
        if (result.isFailure) return null
        val id = result.getOrNull()
        resolved[seriesId] = id
        return id
    }

    /**
     * Waits out the track, starting the long fade [FADE_END_MS] *before* the end
     * rather than after it — fading a track that has already finished is
     * silence fading into silence.
     */
    private suspend fun awaitEnd(active: ExoPlayer) {
        while (true) {
            delay(END_POLL_MS)
            if (active.playbackState == Player.STATE_ENDED) break
            val duration = active.duration
            if (duration != C.TIME_UNSET && duration > 0 && duration - active.currentPosition <= FADE_END_MS) {
                fadeTo(0f, FADE_END_MS)
                break
            }
        }
        release()
    }

    /** Fades out over [fadeMs] and tears the player down. A no-op when silent. */
    private suspend fun cut(fadeMs: Long) {
        if (player == null) return
        fadeTo(0f, fadeMs)
        release()
    }

    private suspend fun fadeTo(
        target: Float,
        durationMs: Long,
    ) {
        val active = player ?: return
        if (durationMs <= 0L) {
            active.volume = target
            return
        }
        val from = active.volume
        val steps = (durationMs / FADE_STEP_MS).toInt().coerceAtLeast(1)
        for (step in 1..steps) {
            delay(FADE_STEP_MS)
            active.volume = from + (target - from) * (step.toFloat() / steps)
        }
        active.volume = target
    }

    private fun obtainPlayer(): ExoPlayer {
        player?.let { return it }
        val created =
            ExoPlayer.Builder(appContext)
                .build()
                .apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build(),
                        // handleAudioFocus = true: requesting focus is what makes
                        // the platform tell us when a call or another app takes
                        // over, which the listener above turns into a stop.
                        true,
                    )
                    repeatMode = Player.REPEAT_MODE_OFF
                    addListener(listener)
                }
        player = created
        return created
    }

    /**
     * Released rather than kept idle: a theme plays rarely and briefly, and
     * releasing is what guarantees the audio focus request is handed back
     * instead of leaving the user's music paused.
     */
    private fun release() {
        player?.let {
            it.removeListener(listener)
            it.stop()
            it.release()
        }
        player = null
    }

    companion object {
        /** Long enough that flicking through a row of shows never blips audio. */
        const val START_DELAY_MS = 750L
        const val FADE_IN_MS = 1000L
        const val FADE_SHOW_CHANGE_MS = 400L
        const val FADE_END_MS = 1500L
        const val FADE_HARD_CUT_MS = 250L
        const val TARGET_VOLUME = 0.6f

        private const val FADE_STEP_MS = 40L
        private const val END_POLL_MS = 200L
    }
}
