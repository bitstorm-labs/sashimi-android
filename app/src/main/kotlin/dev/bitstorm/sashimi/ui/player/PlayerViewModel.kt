package dev.bitstorm.sashimi.ui.player

import android.app.Application
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.model.MediaSegmentDto
import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.playback.AudioTrack
import dev.bitstorm.sashimi.core.playback.AutoPlayNextResolver
import dev.bitstorm.sashimi.core.playback.BitrateResolver
import dev.bitstorm.sashimi.core.playback.LanguageMatcher
import dev.bitstorm.sashimi.core.playback.PlaybackEngine
import dev.bitstorm.sashimi.core.playback.PlaybackSource
import dev.bitstorm.sashimi.core.playback.ProgressReporter
import dev.bitstorm.sashimi.core.playback.QualityOption
import dev.bitstorm.sashimi.core.playback.SegmentSkipTracker
import dev.bitstorm.sashimi.core.playback.StreamInfo
import dev.bitstorm.sashimi.core.playback.SubtitleTrack
import dev.bitstorm.sashimi.core.settings.AppSettings
import dev.bitstorm.sashimi.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val title: String = "",
    val subtitle: String? = null,
    val streamInfo: StreamInfo? = null,
    val audioTracks: List<AudioTrack> = emptyList(),
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val selectedAudioIndex: Int? = null,
    val selectedSubtitleIndex: Int = OFF_SUBTITLE,
    val selectedQuality: QualityOption = QualityOption.AUTO,
    val speed: Float = 1f,
    val skipSegment: MediaSegmentDto? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val playbackEnded: Boolean = false,
) {
    companion object {
        const val OFF_SUBTITLE = -1
    }
}

/**
 * The Media3 player brain. Owns the [ExoPlayer] instance (which lives in :app —
 * :core stays Compose/Media3-player-free and hands over pure [PlaybackSource]
 * data). Ports the Swift PlayerViewModel: resume-threshold negotiation, progress
 * reporting (start/5s/pause/stop + quick-exit), external-VTT subtitle side-load,
 * skip-intro/credits with auto-skip, quality re-negotiation preserving position,
 * and auto-play-next with season rollover.
 */
@OptIn(UnstableApi::class)
class PlayerViewModel(
    app: Application,
    private val client: JellyfinClient,
    private val engine: PlaybackEngine,
    private val settings: AppSettings,
    private val itemId: String,
    private val startFromBeginning: Boolean,
    private val trailerItemId: String?,
) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    val player: ExoPlayer =
        ExoPlayer.Builder(app)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    // handleAudioFocus =
                    true,
                )
            }

    private var currentItem: BaseItemDto? = null
    private var currentSource: PlaybackSource? = null
    private var reporter: ProgressReporter? = null
    private var segmentTracker: SegmentSkipTracker? = null

    private var progressJob: Job? = null
    private var tickJob: Job? = null
    private var isHandlingEnd = false

    // Desired track selections, (re)applied whenever the player's track list
    // changes (tracks aren't known until after prepare).
    private var desiredAudioLanguage: String? = null
    private var desiredSubtitleIndex: Int = PlayerUiState.OFF_SUBTITLE

    private val playerListener =
        object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Immediate progress report on any play/pause transition (Swift rateObserver).
                reportProgressNow()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onPlaybackEnded()
            }

            override fun onTracksChanged(tracks: Tracks) {
                applyTrackSelections()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                _state.update { it.copy(videoWidth = videoSize.width, videoHeight = videoSize.height) }
            }

            override fun onPlayerError(error: PlaybackException) {
                _state.update { it.copy(isLoading = false, error = error.errorCodeName) }
            }
        }

    init {
        player.addListener(playerListener)
        viewModelScope.launch { loadInitial() }
        startTickLoop()
    }

    private suspend fun loadInitial() {
        val playbackTargetId = trailerItemId ?: itemId
        val fresh = runCatching { client.getItem(playbackTargetId) }.getOrNull()
        if (fresh == null) {
            _state.update { it.copy(isLoading = false, error = "Could not load item.") }
            return
        }
        currentItem = fresh
        // Trailers always play from the beginning.
        val fromBeginning = startFromBeginning || trailerItemId != null
        // Apply the user's preferred-language defaults before the first negotiate.
        desiredAudioLanguage = settings.preferredAudioLanguage.value.takeIf { it.isNotEmpty() }
        prepare(fresh, resumeTicksFor(fresh, fromBeginning), QualityOption.AUTO, forceTranscode = false)
    }

    /** Resume threshold: only auto-resume when saved position exceeds the setting. */
    private fun resumeTicksFor(
        item: BaseItemDto,
        fromBeginning: Boolean,
    ): Long {
        if (fromBeginning) return 0
        val saved = item.userData?.playbackPositionTicks ?: 0
        val thresholdTicks = settings.resumeThresholdSeconds.value.toLong() * TICKS_PER_SECOND
        return if (saved > thresholdTicks) saved else 0
    }

    /**
     * Negotiate + prepare the player at [startTicks]. Shared by initial load,
     * quality change, audio change (when transcoding), and next-episode.
     */
    private suspend fun prepare(
        item: BaseItemDto,
        startTicks: Long,
        quality: QualityOption,
        forceTranscode: Boolean,
        audioStreamIndex: Int? = null,
    ) {
        _state.update { it.copy(isLoading = true, error = null, playbackEnded = false) }
        stopProgressLoop()
        // Tear down any prior server transcode before re-negotiating (Swift teardown).
        currentSource?.let { prior ->
            if (prior.isTranscoding) prior.playSessionId?.let { engine.stopTranscode(it) }
        }

        val maxBitrate = BitrateResolver.effectiveMaxBitrate(quality.maxBitrate, settings.maxBitrate.value)

        val source =
            runCatching {
                engine.negotiate(
                    itemId = item.id,
                    resumeTicks = startTicks,
                    maxBitrate = maxBitrate,
                    forceDirectPlay = settings.forceDirectPlay.value,
                    forceTranscode = forceTranscode,
                    audioStreamIndex = audioStreamIndex,
                    // Subtitles are always delivered as external VTT side-loads and
                    // rendered by the player, so we never ask the server to burn one
                    // into a transcode — SubtitleStreamIndex stays null.
                    subtitleStreamIndex = null,
                )
            }.getOrElse {
                _state.update { s -> s.copy(isLoading = false, error = "Playback failed: ${it.message}") }
                return
            }
        currentSource = source

        // Resolve the subtitle selection to apply once tracks are known.
        desiredSubtitleIndex = initialSubtitleSelection(item, source)

        val mediaItem = buildMediaItem(item.id, source)
        player.setMediaItem(mediaItem, source.playerStartPositionMs)
        player.prepare()
        applyTrackSelections()
        player.playWhenReady = true

        reporter =
            ProgressReporter(
                client = client,
                itemId = item.id,
                playSessionId = source.playSessionId,
                reportedPlayMethod = source.playMethod.reportedPlayMethod,
                resumePositionTicks = startTicks,
            )
        runCatching { reporter?.reportStart(startTicks) }

        loadSegments(item)
        startProgressLoop()

        _state.update {
            it.copy(
                isLoading = false,
                title = titleFor(item),
                subtitle = subtitleFor(item),
                streamInfo = source.streamInfo,
                audioTracks = source.audioTracks,
                subtitleTracks = source.subtitleTracks,
                selectedAudioIndex =
                    source.audioTracks.firstOrNull {
                            t ->
                        t.languageCode != null && LanguageMatcher.matches(t.languageCode, desiredAudioLanguage)
                    }?.index,
                selectedSubtitleIndex = desiredSubtitleIndex,
                selectedQuality = quality,
            )
        }
    }

    private fun initialSubtitleSelection(
        item: BaseItemDto,
        source: PlaybackSource,
    ): Int {
        val streams = source.subtitleTracks.filterNot { it.isOff }
        if (streams.isEmpty()) return PlayerUiState.OFF_SUBTITLE
        if (!settings.subtitlesEnabled.value) return PlayerUiState.OFF_SUBTITLE
        val pref = settings.preferredSubtitleLanguage.value
        val match = pref.takeIf { it.isNotEmpty() }?.let { streams.firstOrNull { s -> LanguageMatcher.matches(s.languageCode, pref) } }
        return (match ?: streams.firstOrNull())?.index ?: PlayerUiState.OFF_SUBTITLE
    }

    /** Sideloads every external subtitle as a selectable VTT track (id "sub-<index>"). */
    private fun buildMediaItem(
        playbackItemId: String,
        source: PlaybackSource,
    ): MediaItem {
        val subConfigs =
            source.subtitleTracks
                .filter { !it.isOff && it.isExternal }
                .mapNotNull { track ->
                    val url = engine.subtitleStreamUrl(playbackItemId, track.index, source.mediaSourceId) ?: return@mapNotNull null
                    MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(url))
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setLanguage(track.languageCode)
                        .setId(subtitleTrackId(track.index))
                        .build()
                }
        return MediaItem.Builder()
            .setUri(source.streamUrl)
            .setSubtitleConfigurations(subConfigs)
            .build()
    }

    /**
     * (Re)applies the desired audio-language and subtitle selections against the
     * player's current track list. Called on every onTracksChanged because tracks
     * aren't populated until after prepare.
     */
    private fun applyTrackSelections() {
        val builder = player.trackSelectionParameters.buildUpon()
        // Audio: best-effort by preferred language (covers direct play).
        desiredAudioLanguage?.let { builder.setPreferredAudioLanguage(it) }

        // Subtitles.
        if (desiredSubtitleIndex == PlayerUiState.OFF_SUBTITLE) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            val targetId = subtitleTrackId(desiredSubtitleIndex)
            val group =
                player.currentTracks.groups.firstOrNull { g ->
                    g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { i -> g.getTrackFormat(i).id == targetId }
                }
            if (group != null) {
                builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
                builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup, 0))
            } else {
                // Embedded track (direct play): fall back to language preference.
                _state.value.subtitleTracks.firstOrNull { it.index == desiredSubtitleIndex }?.languageCode
                    ?.let { builder.setPreferredTextLanguage(it) }
            }
        }
        player.trackSelectionParameters = builder.build()
    }

    // MARK: - Public actions (from the player chrome)

    fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        _state.update { it.copy(speed = speed) }
    }

    fun selectQuality(quality: QualityOption) {
        val item = currentItem ?: return
        val posTicks = player.currentPosition * TICKS_PER_MS
        viewModelScope.launch {
            prepare(item, posTicks, quality, forceTranscode = quality.forcesTranscode)
        }
    }

    fun selectAudioTrack(track: AudioTrack) {
        desiredAudioLanguage = track.languageCode
        _state.update { it.copy(selectedAudioIndex = track.index) }
        val source = currentSource
        val item = currentItem
        if (source != null && item != null && source.isTranscoding) {
            // A transcode bakes the audio track server-side → re-negotiate.
            val posTicks = player.currentPosition * TICKS_PER_MS
            viewModelScope.launch {
                prepare(item, posTicks, _state.value.selectedQuality, forceTranscode = true, audioStreamIndex = track.index)
            }
        } else {
            applyTrackSelections()
        }
    }

    fun selectSubtitle(index: Int) {
        desiredSubtitleIndex = index
        _state.update { it.copy(selectedSubtitleIndex = index) }
        applyTrackSelections()
    }

    /** Manual Skip Intro/Credits button. */
    fun skipCurrentSegment() {
        val segment = _state.value.skipSegment ?: return
        performSkip(segment)
    }

    private fun performSkip(segment: MediaSegmentDto) {
        segmentTracker?.markSkipped(segment.id)
        _state.update { it.copy(skipSegment = null) }
        val durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0
        val endMs = (segment.endSeconds * 1000).toLong()
        // A credit-skip that lands within 2s of the end doesn't fire STATE_ENDED
        // on a seek, so run the end flow directly (Swift skipCurrentSegment).
        if (durationMs > 0 && endMs >= durationMs - 2_000) {
            onPlaybackEnded()
        } else {
            player.seekTo(endMs)
        }
    }

    // MARK: - Loops & reporting

    private fun startTickLoop() {
        tickJob?.cancel()
        tickJob =
            viewModelScope.launch {
                while (isActive) {
                    delay(SEGMENT_POLL_MS)
                    if (player.isPlaying) checkSegments()
                }
            }
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob =
            viewModelScope.launch {
                while (isActive) {
                    delay(ProgressReporter.PROGRESS_INTERVAL_MS)
                    reportProgressNow()
                }
            }
    }

    private fun stopProgressLoop() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun reportProgressNow() {
        val r = reporter ?: return
        val posTicks = player.currentPosition * TICKS_PER_MS
        viewModelScope.launch { runCatching { r.reportProgress(posTicks, isPaused = !player.isPlaying) } }
    }

    private suspend fun loadSegments(item: BaseItemDto) {
        segmentTracker = null
        _state.update { it.copy(skipSegment = null) }
        if (item.type != ItemType.EPISODE) return
        val segments = runCatching { client.getMediaSegments(item.id) }.getOrDefault(emptyList())
        segmentTracker = SegmentSkipTracker(segments)
    }

    private fun checkSegments() {
        val tracker = segmentTracker ?: return
        val posSeconds = player.currentPosition / 1000.0
        val autoTarget = tracker.autoSkipTarget(posSeconds, settings.autoSkipIntro.value, settings.autoSkipCredits.value)
        if (autoTarget != null) {
            performSkip(autoTarget)
            return
        }
        val active = tracker.activeSegment(posSeconds)
        if (active?.id != _state.value.skipSegment?.id) {
            _state.update { it.copy(skipSegment = active) }
        }
    }

    private fun onPlaybackEnded() {
        if (isHandlingEnd) return
        isHandlingEnd = true
        stopProgressLoop()
        viewModelScope.launch {
            val item = currentItem
            val durationTicks = player.duration.takeIf { it != C.TIME_UNSET }?.let { it * TICKS_PER_MS } ?: 0
            runCatching { reporter?.reportEndOfPlayback(durationTicks) }

            val next = if (settings.autoPlayNextEpisode.value && trailerItemId == null && item != null) resolveNextEpisode(item) else null
            if (next != null) {
                isHandlingEnd = false
                currentItem = next
                prepare(next, startTicks = 0, QualityOption.AUTO, forceTranscode = false)
            } else {
                _state.update { it.copy(playbackEnded = true) }
            }
        }
    }

    private suspend fun resolveNextEpisode(current: BaseItemDto): BaseItemDto? {
        if (current.type != ItemType.EPISODE) return null
        val seriesId = current.seriesId ?: return null
        val seasonId = current.seasonId
        val episodes = runCatching { client.getEpisodes(seriesId, seasonId) }.getOrDefault(emptyList())
        AutoPlayNextResolver.nextInList(current, episodes)?.let { return it }
        val seasons = runCatching { client.getSeasons(seriesId) }.getOrDefault(emptyList())
        val nextSeason = AutoPlayNextResolver.nextSeasonId(seasonId, seasons) ?: return null
        val nextEps = runCatching { client.getEpisodes(seriesId, nextSeason) }.getOrDefault(emptyList())
        return nextEps.firstOrNull()
    }

    // MARK: - Titles

    private fun titleFor(item: BaseItemDto): String = if (item.type == ItemType.EPISODE) item.seriesName ?: item.name else item.name

    private fun subtitleFor(item: BaseItemDto): String? =
        when (item.type) {
            ItemType.EPISODE -> {
                val s = item.parentIndexNumber
                val e = item.indexNumber
                val prefix = if (s != null && e != null) "S$s:E$e" else null
                listOfNotNull(prefix, item.name).joinToString(" · ").ifEmpty { null }
            }
            else -> item.productionYear?.toString()
        }

    override fun onCleared() {
        super.onCleared()
        // Fire the stopped report + transcode teardown on a detached scope so it
        // survives the ViewModel being cleared, then release the player.
        val r = reporter
        val source = currentSource
        val posTicks = player.currentPosition * TICKS_PER_MS
        if (r != null) {
            teardownScope.launch {
                runCatching { r.reportStopped(posTicks) }
                if (source?.isTranscoding == true) source.playSessionId?.let { runCatching { engine.stopTranscode(it) } }
            }
        }
        player.removeListener(playerListener)
        player.release()
    }

    class Factory(
        private val app: Application,
        private val itemId: String,
        private val startFromBeginning: Boolean,
        private val trailerItemId: String?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
            PlayerViewModel(
                app = app,
                client = ServiceLocator.client,
                engine = ServiceLocator.playbackEngine,
                settings = ServiceLocator.appSettings,
                itemId = itemId,
                startFromBeginning = startFromBeginning,
                trailerItemId = trailerItemId,
            ) as T
    }

    companion object {
        private const val TICKS_PER_MS = 10_000L
        private const val TICKS_PER_SECOND = 10_000_000L
        private const val SEGMENT_POLL_MS = 500L
        private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun subtitleTrackId(index: Int): String = "sub-$index"
    }
}
