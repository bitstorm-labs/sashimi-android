package dev.bitstorm.sashimi.herotrailer

import android.content.Context
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.bitstorm.sashimi.core.downloads.NetworkMonitor
import dev.bitstorm.sashimi.core.herotrailer.HeroTrailerCaps
import dev.bitstorm.sashimi.core.herotrailer.HeroTrailerClip
import dev.bitstorm.sashimi.core.herotrailer.HeroTrailerConditions
import dev.bitstorm.sashimi.core.herotrailer.HeroTrailerKind
import dev.bitstorm.sashimi.core.herotrailer.HeroTrailerOwners
import dev.bitstorm.sashimi.core.herotrailer.HeroTrailerPolicy
import dev.bitstorm.sashimi.core.herotrailer.HeroTrailerResolver
import dev.bitstorm.sashimi.core.herotrailer.HeroTrailerSource
import dev.bitstorm.sashimi.core.herotrailer.HeroTrailerStream
import dev.bitstorm.sashimi.core.herotrailer.HeroTrailerTheme
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.playback.PlaybackEngine
import dev.bitstorm.sashimi.core.settings.AppSettings
import dev.bitstorm.sashimi.core.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Plays a title's local trailer where its detail screen's backdrop is. The
 * Android port of the Apple `HeroTrailerController` (1.6.44 to 1.6.47).
 *
 * App-level, like [dev.bitstorm.sashimi.themesong.ThemeSongService]: screens
 * report intent ("shown", "hidden", "dismissed") and [HeroTrailerOwners] in
 * `:core` decides which one shows the single player. Every failure path is the
 * plain image; nothing here ever surfaces an error.
 *
 * The image is never removed. The hero draws the video *under* the image and
 * fades the image out and back in, so when the clip ends the backdrop is
 * simply faded back to full opacity over a layer that is still mounted. That
 * is the structural answer to the Apple "blank spot" bug, where removing the
 * video view left the image underneath undrawn.
 *
 * Every call runs on the main thread, which ExoPlayer requires and which makes
 * the plain [HeroTrailerOwners] and resolver maps safe.
 */
@OptIn(UnstableApi::class)
class HeroTrailerController(
    context: Context,
    /** The shared client; it always follows the active server. */
    private val client: () -> JellyfinClient,
    /** Negotiation for the active server. */
    private val engine: () -> PlaybackEngine,
    private val activeServerId: () -> String?,
    private val settings: AppSettings,
    private val network: NetworkMonitor,
    private val themeIsPlaying: StateFlow<Boolean>,
    /** Fires as a theme begins its natural end fade (see ThemeSongService.endingNaturally). */
    private val themeEnding: Flow<Unit>,
    private val appScope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(appScope.coroutineContext + Dispatchers.Main.immediate)
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    private val owners = HeroTrailerOwners()

    /** One resolver per server, so a trailer id is never looked up on the wrong one. */
    private val resolvers = mutableMapOf<String?, HeroTrailerResolver>()

    private val _activeOwner = MutableStateFlow<String?>(null)

    /** The screen whose hero shows [player]. */
    val activeOwner: StateFlow<String?> = _activeOwner.asStateFlow()

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    private val _isVisible = MutableStateFlow(false)

    /** True once the trailer is actually producing frames; drives the fade. */
    val isVisible: StateFlow<Boolean> = _isVisible.asStateFlow()

    private var playingSourceId: String? = null

    /** The running trailer's clip, and what the series theme has done while it ran. */
    private var playingClip: HeroTrailerClip? = null
    private var theme: HeroTrailerTheme = HeroTrailerTheme.None

    /** Which length rule each source follows, recorded when a screen for it shows. */
    private val sourceKinds = mutableMapOf<String, HeroTrailerKind>()
    private var playSessionId: String? = null

    /** The engine that opened the running encode, so the stop goes to the same server. */
    private var sessionEngine: PlaybackEngine? = null
    private var startJob: Job? = null
    private var clipJob: Job? = null
    private var finishJob: Job? = null
    private var isFinishing = false

    private val listener =
        object : Player.Listener {
            override fun onRenderedFirstFrame() {
                // Reveal only once frames are actually flowing, so a slow
                // transcode start never fades the image out to black. Not while
                // finishing, or the picture would snap back mid-fade.
                if (!isFinishing) _isVisible.value = true
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) finish()
            }

            override fun onPlayerError(error: PlaybackException) {
                finish()
            }
        }

    init {
        // A theme that starts after the trailer silences it; one that ends does
        // not bring the sound back, so audio never appears out of nowhere.
        // It also ties a series trailer's end to the theme: one heard while the
        // trailer runs carries the trailer until it ends (see clipEnd).
        scope.launch {
            themeIsPlaying.collect { playing ->
                if (playing) {
                    _player.value?.volume = 0f
                    if (_player.value != null && !isFinishing) theme = theme.started()
                } else {
                    // A theme cut short (focus loss, setting off) ends it too.
                    themeEnded()
                }
            }
        }
        scope.launch { themeEnding.collect { themeEnded() } }
        // Turning the setting off mid-play stops what is already playing.
        scope.launch {
            settings.heroTrailers.collect { stored -> if (stored == false) stop() }
        }
    }

    /** Transcode cap for the next start: set by the screen being shown. */
    private var caps = HeroTrailerCaps.COMPACT

    // MARK: - Intent from screens

    /**
     * [owner]'s detail screen for [item] is showing. [item] is null for a screen
     * with no trailer layer in its hero (it still covers, and so stops, the
     * trailer underneath). [screenServerId] is the route's pinned server
     * (null = the active one).
     */
    fun screenShown(
        owner: String,
        item: BaseItemDto?,
        screenServerId: String?,
        isCompactWidth: Boolean,
    ) {
        val eligible =
            HeroTrailerConditions(
                settingOn = HeroTrailerPolicy.settingOn(settings.heroTrailers.value, isCompactWidth),
                isOnline = network.isOnline.value,
                isMetered = network.isMetered.value,
                screenServerId = screenServerId,
                activeServerId = activeServerId(),
            ).isEligible
        val sourceId = if (eligible && item != null) HeroTrailerPolicy.trailerSourceId(item) else null
        if (sourceId != null && item != null) HeroTrailerPolicy.trailerKind(item)?.let { sourceKinds[sourceId] = it }
        // The newest screen decides the cap; a start it triggers uses it.
        caps = HeroTrailerCaps.forWidth(isCompactWidth)
        apply(owners.appeared(owner, sourceId, playingSourceId))
    }

    /** [owner]'s screen left the screen (something was pushed over it) but is still on the back stack. */
    fun screenHidden(owner: String) {
        apply(owners.hidden(owner))
    }

    /** [owner]'s screen left the back stack for good. */
    fun screenDismissed(owner: String) {
        apply(owners.dismissed(owner, playingSourceId))
    }

    /** Play, Trailer or Shuffle pressed: the trailer must be gone before the player's own audio starts. */
    fun stopForPlayback() = stop()

    fun onAppBackgrounded() = stop()

    // MARK: - Internals

    private fun apply(decision: HeroTrailerOwners.Decision) {
        when (decision) {
            is HeroTrailerOwners.Decision.Start -> {
                stop()
                _activeOwner.value = owners.topOwner
                scheduleStart(decision.sourceId)
            }
            HeroTrailerOwners.Decision.HandOff -> _activeOwner.value = owners.topOwner
            HeroTrailerOwners.Decision.Stop -> {
                stop()
                _activeOwner.value = owners.topOwner
            }
            HeroTrailerOwners.Decision.Ignore -> Unit
        }
    }

    /**
     * Not tied to the screen that asked: a screen for the same source pushed on
     * top during the delay takes the start over (a hand-off), and whatever ends
     * the visit instead goes through [stop], which cancels this job.
     */
    private fun scheduleStart(sourceId: String) {
        startJob =
            scope.launch {
                // The image shows first; the trailer only starts once the screen
                // has settled, so flicking past a title costs no transcode.
                delay(START_DELAY_MS)
                val serverId = activeServerId()
                val resolver =
                    resolvers.getOrPut(serverId) {
                        HeroTrailerResolver { id -> HeroTrailerSource.from(client().getLocalTrailers(id)) }
                    }
                val trailer = resolver.resolve(sourceId) ?: return@launch
                val streamEngine = engine()
                val stream =
                    runCatchingCancellable { streamEngine.heroTrailerStream(trailer.id, caps) }.getOrNull()
                        ?: return@launch
                if (activeServerId() != serverId) {
                    stream.playSessionId?.let { session -> appScope.launch { streamEngine.stopTranscode(session) } }
                    return@launch
                }
                val kind = sourceKinds[sourceId] ?: HeroTrailerKind.SERIES
                play(stream, sourceId, HeroTrailerPolicy.clip(trailer.seconds, kind), streamEngine)
            }
    }

    private fun play(
        stream: HeroTrailerStream,
        sourceId: String,
        clip: HeroTrailerClip,
        streamEngine: PlaybackEngine,
    ) {
        val created =
            ExoPlayer.Builder(appContext).build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    // handleAudioFocus = false: a background preview never takes
                    // audio focus away from anything. It is muted instead when
                    // something else is already playing.
                    false,
                )
                volume =
                    if (HeroTrailerPolicy.shouldMute(themeIsPlaying.value, audioManager?.isMusicActive == true)) 0f else 1f
                repeatMode = Player.REPEAT_MODE_OFF
                addListener(listener)
                setMediaItem(
                    MediaItem.Builder().setUri(stream.url).setMimeType(MimeTypes.APPLICATION_M3U8).build(),
                    (clip.start * 1000).toLong(),
                )
                prepare()
                play()
            }
        _player.value = created
        playingSourceId = sourceId
        playingClip = clip
        theme = if (themeIsPlaying.value) HeroTrailerTheme.Playing else HeroTrailerTheme.None
        playSessionId = stream.playSessionId
        sessionEngine = streamEngine

        // Fade out while the last moments still play, rather than on a frozen
        // frame with the sound cut mid-word. The end is re-read every poll
        // because a series theme moves it. The item's own end (or an error) is
        // the fallback for a trailer with no known end, or one shorter than the
        // server said.
        clipJob =
            scope.launch {
                while (true) {
                    delay(POSITION_POLL_MS)
                    if (_player.value !== created) return@launch
                    val end = HeroTrailerPolicy.clipEnd(clip, theme) ?: continue
                    val fadeAtMs = (maxOf(clip.start, end - CROSS_FADE_MS / 1000.0) * 1000).toLong()
                    if (created.currentPosition >= fadeAtMs) {
                        finish()
                        return@launch
                    }
                }
            }
    }

    /** The theme ended or began its end fade: a series trailer following it ends with it. */
    private fun themeEnded() {
        val running = _player.value ?: return
        if (isFinishing || playingClip == null) return
        theme = theme.ended(at = running.currentPosition / 1000.0)
    }

    /**
     * End of the clip: fade the image back in and ramp the sound down, then let
     * go of the player. Reached from the fade point, the item's end or an
     * error, whichever comes first; a later call finds the fade under way.
     */
    private fun finish() {
        val finishing = _player.value ?: return
        if (isFinishing) return
        isFinishing = true
        _isVisible.value = false
        finishJob =
            scope.launch {
                val startVolume = finishing.volume
                for (step in 1..FADE_STEPS) {
                    delay(CROSS_FADE_MS / FADE_STEPS)
                    if (_player.value !== finishing) return@launch
                    finishing.volume = startVolume * (FADE_STEPS - step) / FADE_STEPS
                }
                if (_player.value === finishing) teardown()
            }
    }

    private fun stop() {
        startJob?.cancel()
        startJob = null
        _isVisible.value = false
        teardown()
    }

    private fun teardown() {
        clipJob?.cancel()
        clipJob = null
        finishJob?.cancel()
        finishJob = null
        isFinishing = false
        _player.value?.let {
            it.removeListener(listener)
            it.stop()
            it.release()
        }
        _player.value = null
        playingSourceId = null
        playingClip = null
        theme = HeroTrailerTheme.None
        val session = playSessionId
        val stopper = sessionEngine
        playSessionId = null
        sessionEngine = null
        // The server keeps encoding until told otherwise; this must outlive any screen.
        if (session != null && stopper != null) appScope.launch { stopper.stopTranscode(session) }
    }

    companion object {
        const val START_DELAY_MS = 2_000L

        /** How long the hero cross-fades between image and video. */
        const val CROSS_FADE_MS = 600L
        private const val FADE_STEPS = 6
        private const val POSITION_POLL_MS = 250L
    }
}
