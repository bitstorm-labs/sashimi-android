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
import dev.bitstorm.sashimi.core.downloads.DownloadManager
import dev.bitstorm.sashimi.core.downloads.OfflineReconstruction
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
import dev.bitstorm.sashimi.core.playback.ResumeTimeline
import dev.bitstorm.sashimi.core.playback.SegmentSkipTracker
import dev.bitstorm.sashimi.core.playback.StreamInfo
import dev.bitstorm.sashimi.core.playback.StreamMethod
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
    private val downloads: DownloadManager,
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
    private var watchdogJob: Job? = null
    private var isHandlingEnd = false

    /** Set when playing a completed local download — drives local position save + skips server reporting. */
    private var isLocalPlayback = false

    // Desired track selections, (re)applied whenever the player's track list
    // changes (tracks aren't known until after prepare).
    private var desiredAudioLanguage: String? = null
    private var desiredSubtitleIndex: Int = PlayerUiState.OFF_SUBTITLE

    /**
     * True once the user picks a subtitle for the CURRENT item, so a
     * re-negotiation does not overwrite the choice with the settings default.
     * Reset when the item changes, because stream indices are per-item.
     */
    private var userChoseSubtitle: Boolean = false

    /** Jellyfin audio-stream index the user explicitly chose, if any. */
    private var desiredAudioIndex: Int? = null

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
        // Prefer a completed local download whenever one exists — even online
        // (matches the Swift MobilePlayerView localFileURL gate). Trailers never
        // play locally.
        val localFile = if (trailerItemId == null) runCatching { downloads.localVideoFile(playbackTargetId) }.getOrNull() else null

        if (localFile != null) {
            prepareLocal(playbackTargetId, localFile)
            return
        }

        // Online path: a 5s watchdog surfaces the offline hint if the server never
        // answers (port of the Swift connect-timeout error).
        startWatchdog()
        val fresh = runCatching { client.getItem(playbackTargetId) }.getOrNull()
        if (fresh == null) {
            watchdogJob?.cancel()
            _state.update {
                if (it.error != null) {
                    it
                } else {
                    it.copy(isLoading = false, error = "Could not load item.")
                }
            }
            return
        }
        currentItem = fresh
        // Trailers always play from the beginning.
        val fromBeginning = startFromBeginning || trailerItemId != null
        // Apply the user's preferred-language defaults before the first negotiate.
        desiredAudioLanguage = settings.preferredAudioLanguage.value.takeIf { it.isNotEmpty() }
        prepare(fresh, resumeTicksFor(fresh, fromBeginning), QualityOption.AUTO, forceTranscode = false)
        watchdogJob?.cancel()
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob =
            viewModelScope.launch {
                delay(CONNECT_WATCHDOG_MS)
                if (_state.value.isLoading && currentSource == null) {
                    _state.update {
                        it.copy(isLoading = false, error = "Can't connect to server. Download this item to watch offline.")
                    }
                }
            }
    }

    /**
     * Plays a completed download from local storage: no negotiation, restore the
     * locally-saved position (preferring it over the server's when larger), and
     * defer all progress reporting to the offline sync path.
     */
    private suspend fun prepareLocal(
        playbackItemId: String,
        localFile: java.io.File,
    ) {
        isLocalPlayback = true
        // Reconstruct the item from the server when reachable, else from the store.
        val serverItem = runCatching { client.getItem(playbackItemId) }.getOrNull()
        val item =
            serverItem
                ?: downloads.downloadedItem(playbackItemId)?.let { OfflineReconstruction.asBaseItemDto(it) }
                ?: run {
                    _state.update { it.copy(isLoading = false, error = "Could not load download.") }
                    return
                }
        currentItem = item

        val serverTicks = if (startFromBeginning) 0 else item.userData?.playbackPositionTicks ?: 0
        val localTicks = downloads.offlinePlaybackPositionTicks(playbackItemId) ?: 0
        val startTicks = if (!startFromBeginning && localTicks > serverTicks) localTicks else serverTicks

        // Side-load any subtitles that were downloaded alongside the video as
        // local VTT tracks (Swift MobilePlayerView local subtitle configs).
        val entity = downloads.downloadedItem(playbackItemId)
        val subConfigs = mutableListOf<MediaItem.SubtitleConfiguration>()
        val subTracks = mutableListOf<SubtitleTrack>()
        for (sub in entity?.subtitles.orEmpty()) {
            val file = downloads.localSubtitleFile(playbackItemId, sub.fileName) ?: continue
            subConfigs.add(
                MediaItem.SubtitleConfiguration.Builder(android.net.Uri.fromFile(file))
                    .setMimeType(MimeTypes.TEXT_VTT)
                    .setLanguage(sub.language)
                    .setId(subtitleTrackId(sub.subtitleIndex))
                    .build(),
            )
            subTracks.add(
                SubtitleTrack(
                    index = sub.subtitleIndex,
                    displayName = sub.displayTitle,
                    languageCode = sub.language,
                    isExternal = true,
                ),
            )
        }

        val mediaItem =
            MediaItem.Builder()
                .setUri(android.net.Uri.fromFile(localFile))
                .setSubtitleConfigurations(subConfigs)
                .build()
        player.setMediaItem(mediaItem, startTicks / TICKS_PER_MS)
        player.prepare()

        // Resolve the initial subtitle selection from the user's preferences.
        desiredSubtitleIndex =
            if (subTracks.isEmpty() || !settings.subtitlesEnabled.value) {
                PlayerUiState.OFF_SUBTITLE
            } else {
                val pref = settings.preferredSubtitleLanguage.value
                val match =
                    pref.takeIf { it.isNotEmpty() }?.let {
                            p ->
                        subTracks.firstOrNull { LanguageMatcher.matches(it.languageCode, p) }
                    }
                (match ?: subTracks.first()).index
            }
        applyTrackSelections()
        player.playWhenReady = true

        _state.update {
            it.copy(
                isLoading = false,
                error = null,
                title = titleFor(item),
                subtitle = subtitleFor(item),
                streamInfo = StreamInfo(StreamMethod.DIRECT_PLAY, "Downloaded", null),
                audioTracks = emptyList(),
                subtitleTracks = if (subTracks.isEmpty()) emptyList() else listOf(SubtitleTrack.OFF) + subTracks,
                selectedSubtitleIndex = desiredSubtitleIndex,
                selectedQuality = QualityOption.AUTO,
            )
        }
        loadSegments(item)
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
                    // The bitrate cap alone never changed resolution; this is
                    // what makes a "720p" pick actually deliver 720p.
                    maxWidth = quality.maxWidth,
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
        // Only fall back to the settings-derived default when the user has not
        // chosen for THIS item. This used to be unconditional, so any
        // re-negotiation -- a quality change, an audio change on a transcode --
        // silently reverted the user's subtitle pick: off with default settings,
        // or back to the first matching-language track.
        if (!userChoseSubtitle) {
            desiredSubtitleIndex = initialSubtitleSelection(item, source)
        }

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
                // Retract any error the connect watchdog stamped while we were
                // negotiating. The watchdog fires at 5s on (isLoading &&
                // currentSource == null), which a slow-but-successful transcode
                // negotiation satisfies -- without this, "Can't connect to
                // server" stayed painted over video that was playing fine, with
                // no way to dismiss it.
                error = null,
                title = titleFor(item),
                subtitle = subtitleFor(item),
                streamInfo = source.streamInfo,
                audioTracks = source.audioTracks,
                subtitleTracks = source.subtitleTracks,
                // An explicit pick wins. Re-deriving by language put the
                // checkmark on the FIRST same-language track regardless of which
                // one the server actually baked in, and showed no checkmark at
                // all for an untagged track.
                selectedAudioIndex =
                    desiredAudioIndex
                        ?: source.audioTracks.firstOrNull { t ->
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
        // Audio. Language is only the fallback: it cannot pick between several
        // same-language tracks, and it does nothing at all for an untagged
        // commentary track. Prefer an explicit override on the chosen index,
        // matched the same way subtitles are.
        desiredAudioLanguage?.let { builder.setPreferredAudioLanguage(it) }
        //
        // Matched by ordinal rather than by id: unlike subtitles, which we
        // side-load with ids we choose, audio tracks come from the container and
        // carry whatever id the extractor assigned. Jellyfin's MediaStream.index
        // is an absolute index across all streams, so it cannot be compared to
        // an ExoPlayer group index directly -- but the Nth audio stream Jellyfin
        // reports is the Nth audio group ExoPlayer exposes.
        val desiredOrdinal = desiredAudioIndex?.let { wanted -> _state.value.audioTracks.indexOfFirst { it.index == wanted } }
        if (desiredOrdinal != null && desiredOrdinal >= 0) {
            val audioGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            audioGroups.getOrNull(desiredOrdinal)?.let { group ->
                builder.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup, 0))
            }
        }

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

    // MARK: - Absolute position

    /**
     * How far into the ITEM playback is, in milliseconds.
     *
     * `player.currentPosition` is relative to the stream's timeline, and for a
     * resumed transcode that timeline starts at the resume point rather than at
     * zero (see PlaybackSource.timelineOffsetMs). Everything that means "how far
     * into the item" -- progress reports, re-negotiation, segment matching, the
     * scrubber -- must go through this, never through the raw player position.
     *
     * Getting this wrong silently destroyed progress: resuming a transcode at
     * 1:30:00 and watching five minutes reported five minutes to the server,
     * discarding 85 minutes for every client.
     */
    val absolutePositionMs: Long
        get() = ResumeTimeline.absoluteMs(timelineOffsetMs, player.currentPosition)

    /**
     * Runtime of the ITEM in milliseconds. For an offset transcode
     * `player.duration` is only the remaining runtime, so prefer the item's own
     * RunTimeTicks and fall back to the player timeline plus the offset.
     */
    val absoluteDurationMs: Long
        get() {
            currentItem?.runTimeTicks?.takeIf { it > 0 }?.let { return it / TICKS_PER_MS }
            val playerDuration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return 0
            return timelineOffsetMs + playerDuration
        }

    /** Absolute item position -> a position on the current stream's timeline. */
    private val timelineOffsetMs: Long
        get() = currentSource?.timelineOffsetMs ?: 0L

    /** Absolute item position -> a position on the current stream's timeline. */
    private fun toTimelineMs(absoluteMs: Long): Long = ResumeTimeline.timelineMs(timelineOffsetMs, absoluteMs)

    /**
     * Seek to an absolute position in the item, e.g. from the scrubber.
     *
     * A resumed transcode's stream physically begins at the resume point, so a
     * target before that point does not exist on the current timeline and has to
     * be re-negotiated. Previously the scrubber seeked the raw player timeline,
     * which silently clamped any backward scrub to the resume point.
     */
    fun seekToAbsolute(absoluteMs: Long) {
        val target = absoluteMs.coerceAtLeast(0)
        val item = currentItem
        if (item != null && ResumeTimeline.requiresRenegotiation(timelineOffsetMs, target)) {
            viewModelScope.launch {
                prepare(item, target * TICKS_PER_MS, _state.value.selectedQuality, forceTranscode = false)
            }
            return
        }
        player.seekTo(toTimelineMs(target))
    }

    // MARK: - Public actions (from the player chrome)

    fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        _state.update { it.copy(speed = speed) }
    }

    fun selectQuality(quality: QualityOption) {
        val item = currentItem ?: return
        val posTicks = absolutePositionMs * TICKS_PER_MS
        viewModelScope.launch {
            prepare(item, posTicks, quality, forceTranscode = quality.forcesTranscode)
        }
    }

    fun selectAudioTrack(track: AudioTrack) {
        desiredAudioLanguage = track.languageCode
        // Language alone cannot distinguish stereo / 5.1 / commentary, which are
        // routinely all tagged "eng" -- and a commentary track often has no
        // language tag at all, which made the tap a literal no-op. Remember the
        // index and override on it.
        desiredAudioIndex = track.index
        _state.update { it.copy(selectedAudioIndex = track.index) }
        val source = currentSource
        val item = currentItem
        if (source != null && item != null && source.isTranscoding) {
            // A transcode bakes the audio track server-side → re-negotiate.
            val posTicks = absolutePositionMs * TICKS_PER_MS
            viewModelScope.launch {
                prepare(item, posTicks, _state.value.selectedQuality, forceTranscode = true, audioStreamIndex = track.index)
            }
        } else {
            applyTrackSelections()
        }
    }

    fun selectSubtitle(index: Int) {
        userChoseSubtitle = true
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
        val durationMs = absoluteDurationMs
        val endMs = (segment.endSeconds * 1000).toLong()
        // A credit-skip that lands within 2s of the end doesn't fire STATE_ENDED
        // on a seek, so run the end flow directly (Swift skipCurrentSegment).
        if (durationMs > 0 && endMs >= durationMs - 2_000) {
            onPlaybackEnded()
        } else {
            // Media segments are absolute item times; seekTo works on the
            // stream's timeline, so an offset transcode needs the conversion.
            player.seekTo(toTimelineMs(endMs))
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
        val posTicks = absolutePositionMs * TICKS_PER_MS
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
        val posSeconds = absolutePositionMs / 1000.0
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
            // Absolute item runtime: for an offset transcode player.duration is
            // only the REMAINING runtime, which would report a resumed item as
            // having finished far short of its real length.
            val durationTicks = absoluteDurationMs * TICKS_PER_MS
            runCatching { reporter?.reportEndOfPlayback(durationTicks) }

            val next = if (settings.autoPlayNextEpisode.value && trailerItemId == null && item != null) resolveNextEpisode(item) else null
            if (next != null) {
                isHandlingEnd = false
                currentItem = next
                // Stream indices are per-item, so a choice made on the previous
                // episode is meaningless here. The preferred LANGUAGE is kept,
                // since that is a standing preference rather than a per-item pick.
                userChoseSubtitle = false
                desiredAudioIndex = null
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
        watchdogJob?.cancel()
        val posTicks = absolutePositionMs * TICKS_PER_MS
        // Local playback: stash the position for later server sync (Swift
        // savePlaybackPosition → syncPendingProgress). Trailers are never saved.
        //
        // Keyed on currentItem, NOT the constructor's itemId: auto-play-next
        // advances currentItem while itemId stays pinned to the episode the user
        // originally opened. Saving against itemId wrote the NEXT episode's
        // position onto the PREVIOUS episode's row, and since savePlaybackPosition
        // also sets pendingProgressSync, that wrong position was then POSTed to
        // the server as the previous episode's stopped position, clobbering its
        // correct finished state.
        if (isLocalPlayback && trailerItemId == null) {
            downloads.savePlaybackPosition(currentItem?.id ?: itemId, posTicks)
        }
        // Fire the stopped report + transcode teardown on a detached scope so it
        // survives the ViewModel being cleared, then release the player.
        val r = reporter
        val source = currentSource
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
                downloads = ServiceLocator.downloadManager,
                itemId = itemId,
                startFromBeginning = startFromBeginning,
                trailerItemId = trailerItemId,
            ) as T
    }

    companion object {
        private const val TICKS_PER_MS = 10_000L
        private const val TICKS_PER_SECOND = 10_000_000L
        private const val SEGMENT_POLL_MS = 500L
        private const val CONNECT_WATCHDOG_MS = 5_000L
        private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun subtitleTrackId(index: Int): String = "sub-$index"
    }
}
