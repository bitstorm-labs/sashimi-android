package dev.bitstorm.sashimi.ui.player

import android.app.Application
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import dev.bitstorm.sashimi.core.downloads.DownloadKey
import dev.bitstorm.sashimi.core.downloads.DownloadLookup
import dev.bitstorm.sashimi.core.downloads.DownloadManager
import dev.bitstorm.sashimi.core.downloads.OfflineReconstruction
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.model.MediaSegmentDto
import dev.bitstorm.sashimi.core.network.JellyfinClient
import dev.bitstorm.sashimi.core.playback.AudioTrack
import dev.bitstorm.sashimi.core.playback.AutoBitrate
import dev.bitstorm.sashimi.core.playback.AutoPlayNextResolver
import dev.bitstorm.sashimi.core.playback.BitrateLabel
import dev.bitstorm.sashimi.core.playback.BitrateResolver
import dev.bitstorm.sashimi.core.playback.LanguageMatcher
import dev.bitstorm.sashimi.core.playback.PlayMethod
import dev.bitstorm.sashimi.core.playback.PlaybackEngine
import dev.bitstorm.sashimi.core.playback.PlaybackFailure
import dev.bitstorm.sashimi.core.playback.PlaybackFailureMapping
import dev.bitstorm.sashimi.core.playback.PlaybackRecoveryPlan
import dev.bitstorm.sashimi.core.playback.PlaybackSource
import dev.bitstorm.sashimi.core.playback.ProgressReporter
import dev.bitstorm.sashimi.core.playback.QualityOption
import dev.bitstorm.sashimi.core.playback.RecoveryDecision
import dev.bitstorm.sashimi.core.playback.ResumeTimeline
import dev.bitstorm.sashimi.core.playback.SegmentSkipTracker
import dev.bitstorm.sashimi.core.playback.StallDetector
import dev.bitstorm.sashimi.core.playback.StreamInfo
import dev.bitstorm.sashimi.core.playback.StreamMethod
import dev.bitstorm.sashimi.core.playback.SubtitleChange
import dev.bitstorm.sashimi.core.playback.SubtitleDecisions
import dev.bitstorm.sashimi.core.playback.SubtitleDelivery
import dev.bitstorm.sashimi.core.playback.SubtitleTrack
import dev.bitstorm.sashimi.core.settings.AppSettings
import dev.bitstorm.sashimi.core.trickplay.TrickplayMath
import dev.bitstorm.sashimi.core.trickplay.TrickplayTrack
import dev.bitstorm.sashimi.di.ServiceLocator
import dev.bitstorm.sashimi.ui.util.ImageUrlBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

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
    /** Media3's Util.shouldShowPlayButton: true while paused, ended or idle. */
    val showPlayButton: Boolean = true,
    /** The error offers a Retry button (recovery was exhausted, or the server could not be reached). */
    val errorRetryable: Boolean = false,
    /** The quality in force, e.g. "Auto · 4 Mbps" or "720p · 2 Mbps"; null for a downloaded file. */
    val activeQuality: String? = null,
    /** Shown over the spinner while a quality change is applied: "Switching to 480p · 4 Mbps…". */
    val switchingTo: String? = null,
    /** A short-lived banner: "Lowering quality for your connection · 480p · 1 Mbps". */
    val notice: String? = null,
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
    /**
     * The item's server: the shared client for the active server, or a
     * dedicated one for a title opened from another server. Everything the
     * player asks of a server (the item, PlaybackInfo, stream and subtitle
     * URLs, progress/start/stop, segments, next episode, trickplay) goes here.
     */
    val client: JellyfinClient,
    private val engine: PlaybackEngine,
    private val settings: AppSettings,
    private val downloads: DownloadManager,
    private val itemId: String,
    private val startFromBeginning: Boolean,
    private val trailerItemId: String?,
    /**
     * The server whose download of the item to play, when one exists. Null
     * when the route has no server context (the offline library's series
     * page, a deep link); see [DownloadLookup.playable].
     */
    private val downloadServerId: String?,
    /** The active server when the route opened, for an unscoped lookup. */
    private val activeServerId: () -> String?,
) : AndroidViewModel(app) {
    /** Artwork for the system media surfaces, from the item's own server. */
    private val images = ImageUrlBuilder { client }

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    val player: ExoPlayer =
        ExoPlayer.Builder(app)
            .setHandleAudioBecomingNoisy(true)
            // The chrome's seek icons are Replay10/Forward10, and the same
            // increments back the PiP actions and the MediaSession's seek
            // commands. The Media3 defaults are 5s back and 15s forward.
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
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

    /**
     * Exposes the player to the system: Bluetooth/headset media buttons, the
     * output switcher, and on API 33+ the PiP window's controls. There is
     * deliberately no MediaSessionService: playback pauses when the app is
     * backgrounded (#17), so there is nothing to keep alive or notify about.
     * Ids must be unique per process, and two player VMs can briefly coexist
     * (one route replacing another), hence the counter.
     */
    private val mediaSession: MediaSession =
        MediaSession.Builder(app, player)
            .setId("sashimi-player-${sessionCounter.incrementAndGet()}")
            .build()

    private val _trickplay = MutableStateFlow<TrickplayTrack?>(null)

    /** Scrub-thumbnail geometry for the current item, or null (show nothing). */
    val trickplay: StateFlow<TrickplayTrack?> = _trickplay.asStateFlow()

    private var currentItem: BaseItemDto? = null
        set(value) {
            field = value
            // Every item change (initial load, local playback, auto-play-next)
            // re-resolves the scrub thumbnails; null when it has none.
            _trickplay.value = value?.let { TrickplayMath.select(it.id, it.trickplay, preferredMediaSourceId = it.id) }
        }

    private var currentSource: PlaybackSource? = null
    private var reporter: ProgressReporter? = null
    private var segmentTracker: SegmentSkipTracker? = null

    private var progressJob: Job? = null
    private var tickJob: Job? = null
    private var watchdogJob: Job? = null
    private var isHandlingEnd = false

    /**
     * The download being played from local storage, or null when streaming.
     * Drives the local position save and skips server reporting. Positions are
     * saved against this key, never a bare item id: the same item id can be
     * downloaded from two servers (#86).
     */
    private var localKey: DownloadKey? = null

    private val isLocalPlayback: Boolean get() = localKey != null

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

    /** The single in-flight re-negotiation, so a second one cannot race it. */
    private var prepareJob: Job? = null

    // MARK: Recovery state (see PlaybackRecoveryPlan)

    /**
     * The current stream was negotiated with direct play disabled: an explicit
     * quality pick, a recovery step, or a burned-in subtitle. Preserved by every
     * re-negotiation that is not itself changing it (a backward scrub used to
     * drop it, silently turning a 720p pick back into the original file).
     */
    private var forceTranscodeActive = false

    /** The user picked an image subtitle on a transcode: the server burns it in. */
    private var burnInSubtitle = false

    private var recoveryAttempts = 0
    private var sameQualityRetries = 0
    private var healthyPlayingMs = 0L
    private var lastFailure = PlaybackFailure.OTHER
    private val stallDetector = StallDetector(clock = { android.os.SystemClock.elapsedRealtime() })

    /** Where in the item playback last was while a stream was up, for recovery after it has gone. */
    private var lastGoodPositionMs = 0L
    private var noticeJob: Job? = null

    private val playerListener =
        object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Immediate progress report on any play/pause transition (Swift rateObserver).
                reportProgressNow()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onPlaybackEnded()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) stallDetector.noteSeek()
            }

            override fun onTracksChanged(tracks: Tracks) {
                applyTrackSelections()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                _state.update { it.copy(videoWidth = videoSize.width, videoHeight = videoSize.height) }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Used to paint error.errorCodeName ("ERROR_CODE_DECODING_FAILED")
                // over a dead player. Now the recovery ladder runs first.
                recover(PlaybackFailureMapping.fromMedia3ErrorCode(error.errorCode))
            }

            override fun onEvents(
                player: Player,
                events: Player.Events,
            ) {
                val showPlay = Util.shouldShowPlayButton(player)
                if (showPlay != _state.value.showPlayButton) _state.update { it.copy(showPlayButton = showPlay) }
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
        val localFile = if (trailerItemId == null) runCatching { localDownload(playbackTargetId) }.getOrNull() else null

        if (localFile != null) {
            prepareLocal(localFile.first, localFile.second)
            return
        }

        // Measure the link while the item loads, so Auto can use the result
        // (a remote server waits briefly for it; see PlaybackEngine.autoCap).
        if (settings.maxBitrate.value == 0) engine.prewarmBandwidth()

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
                    it.copy(isLoading = false, error = "Could not load item.", errorRetryable = true)
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

    /** The download to play for [id] and its video file, or null to stream. */
    private suspend fun localDownload(id: String): Pair<DownloadKey, java.io.File>? {
        val key = downloads.playableDownload(id, downloadServerId, activeServerId()) ?: return null
        val file = downloads.localVideoFile(key) ?: return null
        return key to file
    }

    /**
     * Plays a completed download from local storage: no negotiation, restore the
     * locally-saved position (preferring it over the server's when larger), and
     * defer all progress reporting to the offline sync path.
     */
    private suspend fun prepareLocal(
        key: DownloadKey,
        localFile: java.io.File,
    ) {
        val playbackItemId = key.itemId
        localKey = key
        // Reconstruct the item from the server when reachable, else from the store.
        val serverItem = runCatching { client.getItem(playbackItemId) }.getOrNull()
        val item =
            serverItem
                ?: downloads.downloadedItem(key)?.let { OfflineReconstruction.asBaseItemDto(it) }
                ?: run {
                    _state.update { it.copy(isLoading = false, error = "Could not load download.") }
                    return
                }
        currentItem = item
        // Auto-play-next from a stream onto a download: the stream's server
        // transcode is no longer anyone's, so end it now.
        currentSource?.let { prior -> if (prior.isTranscoding) prior.playSessionId?.let { engine.stopTranscode(it) } }
        currentSource = null
        stallDetector.reset()

        val serverTicks = if (startFromBeginning) 0 else item.userData?.playbackPositionTicks ?: 0
        val localTicks = downloads.offlinePlaybackPositionTicks(key) ?: 0
        val startTicks = if (!startFromBeginning && localTicks > serverTicks) localTicks else serverTicks

        // Side-load any subtitles that were downloaded alongside the video as
        // local VTT tracks (Swift MobilePlayerView local subtitle configs).
        val entity = downloads.downloadedItem(key)
        val subConfigs = mutableListOf<MediaItem.SubtitleConfiguration>()
        val subTracks = mutableListOf<SubtitleTrack>()
        for (sub in entity?.subtitles.orEmpty()) {
            val file = downloads.localSubtitleFile(key, sub.fileName) ?: continue
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
                .setMediaMetadata(mediaMetadataFor(item))
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

        // Watching a download while online: report to the server live, as a
        // stream does, so other clients' Continue Watching keeps up. Only
        // when the server answered just now; offline the stash-and-sync path
        // below is all there is.
        reporter =
            if (serverItem != null && trailerItemId == null) {
                ProgressReporter(
                    client = client,
                    itemId = item.id,
                    playSessionId = null,
                    reportedPlayMethod = PlayMethod.DIRECT_PLAY.reportedPlayMethod,
                    resumePositionTicks = startTicks,
                )
            } else {
                null
            }
        runCatching { reporter?.reportStart(startTicks) }

        _state.update {
            it.copy(
                isLoading = false,
                error = null,
                errorRetryable = false,
                activeQuality = null,
                switchingTo = null,
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
        // Local playback needs the checkpoint loop too. It was never started
        // here, so a downloaded item's position was persisted exactly once, in
        // onCleared.
        startProgressLoop()
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
        audioStreamIndex: Int? = desiredAudioIndex,
    ) {
        _state.update { it.copy(isLoading = true, error = null, errorRetryable = false, playbackEnded = false) }
        stopProgressLoop()
        stallDetector.reset()
        // Where a recovery restarts if this stream fails before it plays a
        // frame: the position asked for, not zero.
        lastGoodPositionMs = startTicks / TICKS_PER_MS
        // Tear down any prior server transcode before re-negotiating (Swift
        // teardown). Captured and cleared here rather than read again later, so
        // the source being torn down is unambiguously the one this call saw.
        val priorSource = currentSource
        currentSource = null
        priorSource?.let { prior ->
            if (prior.isTranscoding) prior.playSessionId?.let { engine.stopTranscode(it) }
        }

        val settingsCap = settings.maxBitrate.value
        val maxBitrate = BitrateResolver.effectiveMaxBitrate(quality.maxBitrate, settingsCap)
        // A Settings cap with no resolution tier still needs a width: with
        // none the server re-encodes at the source resolution, a blocky 4K
        // encode at a few Mbps. (Auto picks its width after measuring, in
        // PlaybackEngine; Unlimited never downscales.)
        val maxWidth =
            quality.maxWidth
                ?: maxBitrate?.takeIf { it != BitrateResolver.NO_CAP }?.let { AutoBitrate.maxWidth(it) }

        // An image subtitle can only be shown on a transcode by burning it in,
        // and a burn-in must defeat Force Direct Play (the Roku lesson).
        val wantsBurnIn =
            burnInSubtitle && desiredSubtitleTrack()?.delivery == SubtitleDelivery.BURN_IN
        val transcodeForced = forceTranscode || wantsBurnIn
        forceTranscodeActive = forceTranscode

        val source =
            runCatching {
                engine.negotiate(
                    itemId = item.id,
                    resumeTicks = startTicks,
                    maxBitrate = maxBitrate,
                    // The bitrate cap alone never changed resolution; this is
                    // what makes a "720p" pick actually deliver 720p.
                    maxWidth = maxWidth,
                    forceDirectPlay = settings.forceDirectPlay.value,
                    forceTranscode = transcodeForced,
                    audioStreamIndex = audioStreamIndex,
                    // Always explicit: -1 unless the user asked for an image
                    // track to be burned in. A null let the server apply its
                    // own default and burn in a track the app showed as "Off".
                    subtitleStreamIndex = SubtitleDecisions.serverIndex(desiredSubtitleTrack(), transcoding = transcodeForced),
                )
            }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { s ->
                    s.copy(
                        isLoading = false,
                        switchingTo = null,
                        errorRetryable = true,
                        error = "Couldn't reach the server to start playback. ${e.message ?: ""}".trim(),
                    )
                }
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
            desiredSubtitleIndex = initialSubtitleSelection(source)
        }

        val mediaItem = buildMediaItem(item, source)
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
                errorRetryable = false,
                switchingTo = null,
                activeQuality = BitrateLabel.active(quality, source.negotiatedCap),
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

    /**
     * The default subtitle for this item: the preferred language among text
     * tracks. An image track is never auto-selected: showing one on a
     * transcode means a burn-in the user did not ask for.
     */
    private fun initialSubtitleSelection(source: PlaybackSource): Int =
        SubtitleDecisions.initialSelection(
            tracks = source.subtitleTracks,
            subtitlesEnabled = settings.subtitlesEnabled.value,
            preferredLanguage = settings.preferredSubtitleLanguage.value,
            matches = LanguageMatcher::matches,
        )?.index ?: PlayerUiState.OFF_SUBTITLE

    private fun desiredSubtitleTrack(): SubtitleTrack? =
        _state.value.subtitleTracks.firstOrNull { !it.isOff && it.index == desiredSubtitleIndex }

    private fun embeddedSubtitleFile(
        itemId: String,
        index: Int,
    ): java.io.File? =
        java.io.File(getApplication<Application>().cacheDir, "subtitles/$itemId-$index.vtt").takeIf { it.isFile && it.length() > 0 }

    private var subtitleFetchJob: Job? = null
    private var subtitleFetchIndex: Int? = null

    /**
     * Fetches an embedded text track as VTT into the cache, then rebuilds the
     * media item at the same position with it side-loaded from the file.
     *
     * The server extracts an embedded track by reading the whole media file the
     * first time it is asked (68 s for one 1080p MKV, measured), far beyond the
     * player's 8 s HTTP read timeout, so it cannot be side-loaded by URL.
     */
    private fun fetchEmbeddedSubtitle(
        item: BaseItemDto,
        source: PlaybackSource,
        index: Int,
    ) {
        if (subtitleFetchJob?.isActive == true && subtitleFetchIndex == index) return
        subtitleFetchJob?.cancel()
        subtitleFetchIndex = index
        showNotice("Loading subtitles…")
        subtitleFetchJob =
            viewModelScope.launch {
                val bytes = engine.fetchSubtitleVtt(item.id, index, source.mediaSourceId)
                val target = java.io.File(getApplication<Application>().cacheDir, "subtitles/${item.id}-$index.vtt")
                val saved =
                    bytes != null &&
                        runCatching {
                            target.parentFile?.mkdirs()
                            target.writeBytes(bytes)
                        }.isSuccess
                // Still the same stream and still the wanted track: reload with it.
                if (saved && currentSource === source && desiredSubtitleIndex == index) {
                    val position = player.currentPosition
                    player.setMediaItem(buildMediaItem(item, source), position)
                    player.prepare()
                    applyTrackSelections()
                } else if (!saved) {
                    showNotice("Couldn't load these subtitles.")
                }
            }
    }

    /** A direct play hands the player the original container, embedded tracks and all. */
    private val PlaybackSource.carriesEmbeddedSubtitles: Boolean
        get() = playMethod == PlayMethod.DIRECT_PLAY

    /**
     * Side-loads, as selectable VTT tracks (id "sub-<index>"), every external
     * subtitle file plus the selected embedded text track when the stream does
     * not carry it (see [SubtitleDecisions.sideLoaded]).
     */
    private fun buildMediaItem(
        item: BaseItemDto,
        source: PlaybackSource,
    ): MediaItem {
        val playbackItemId = item.id
        val subConfigs =
            SubtitleDecisions.sideLoaded(source.subtitleTracks, desiredSubtitleIndex, source.carriesEmbeddedSubtitles)
                .mapNotNull { track ->
                    val uri =
                        if (track.delivery == SubtitleDelivery.EMBEDDED_TEXT) {
                            // Only from a local copy; see fetchEmbeddedSubtitle.
                            embeddedSubtitleFile(item.id, track.index)?.let(android.net.Uri::fromFile) ?: run {
                                fetchEmbeddedSubtitle(item, source, track.index)
                                return@mapNotNull null
                            }
                        } else {
                            android.net.Uri.parse(
                                engine.subtitleStreamUrl(playbackItemId, track.index, source.mediaSourceId) ?: return@mapNotNull null,
                            )
                        }
                    MediaItem.SubtitleConfiguration.Builder(uri)
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setLanguage(track.languageCode)
                        .setId(subtitleTrackId(track.index))
                        .build()
                }
        return MediaItem.Builder()
            .setUri(source.streamUrl)
            .setSubtitleConfigurations(subConfigs)
            .setMediaMetadata(mediaMetadataFor(item))
            .build()
    }

    /** What the MediaSession, and so every system media surface, shows. */
    private fun mediaMetadataFor(item: BaseItemDto): MediaMetadata {
        val now = NowPlaying.from(item) { images.cardPoster(it, ARTWORK_WIDTH) }
        return MediaMetadata.Builder()
            .setTitle(now.title)
            .setDisplayTitle(now.title)
            .setSubtitle(now.subtitle)
            // Most system surfaces render the artist line, not the subtitle.
            .setArtist(now.subtitle)
            .setArtworkUri(now.artworkUrl?.let(android.net.Uri::parse))
            .setMediaType(
                when {
                    trailerItemId != null -> MediaMetadata.MEDIA_TYPE_TRAILER
                    item.type == ItemType.EPISODE -> MediaMetadata.MEDIA_TYPE_TV_SHOW
                    item.type == ItemType.MOVIE -> MediaMetadata.MEDIA_TYPE_MOVIE
                    else -> MediaMetadata.MEDIA_TYPE_VIDEO
                },
            )
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

        // Subtitles. A burned-in track is in the picture, not a player track.
        val burnedIn =
            currentSource?.carriesEmbeddedSubtitles == false && desiredSubtitleTrack()?.delivery == SubtitleDelivery.BURN_IN
        if (desiredSubtitleIndex == PlayerUiState.OFF_SUBTITLE || burnedIn) {
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
            renegotiate {
                // Keep whatever forced the transcode (a quality pick, a
                // recovery step). This used to pass false, so a backward scrub
                // quietly dropped a 720p pick back to the original file while
                // the menu still said 720p.
                prepare(item, target * TICKS_PER_MS, _state.value.selectedQuality, forceTranscode = forceTranscodeActive)
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
        // A manual pick starts the ladder afresh from the new tier.
        resetRecovery()
        _state.update { it.copy(switchingTo = "Switching to ${quality.menuLabel}…") }
        renegotiate {
            prepare(item, posTicks, quality, forceTranscode = quality.forcesTranscode)
            if (currentSource != null) _state.value.activeQuality?.let { showNotice("Quality: $it") }
        }
    }

    /** The Retry button on an error: start again at the same place, with a fresh recovery budget. */
    fun retryPlayback() {
        resetRecovery()
        val item = currentItem
        if (item == null) {
            _state.update { it.copy(isLoading = true, error = null, errorRetryable = false) }
            viewModelScope.launch { loadInitial() }
            return
        }
        val local = localKey
        if (local != null) {
            viewModelScope.launch {
                _state.update { it.copy(isLoading = true, error = null, errorRetryable = false) }
                val file = downloads.localVideoFile(local)
                if (file != null) {
                    prepareLocal(local, file)
                } else {
                    _state.update { it.copy(isLoading = false, error = "Could not load download.") }
                }
            }
            return
        }
        val posTicks = lastGoodPositionMs * TICKS_PER_MS
        renegotiate { prepare(item, posTicks, _state.value.selectedQuality, forceTranscode = forceTranscodeActive) }
    }

    // MARK: - Recovery

    private fun resetRecovery() {
        recoveryAttempts = 0
        sameQualityRetries = 0
        healthyPlayingMs = 0
    }

    /**
     * Runs one step of the recovery ladder at the same position: retry, fall
     * back to a transcode, step down a tier, or give up with a readable error
     * and Retry. See [PlaybackRecoveryPlan] for the policy.
     */
    private fun recover(failure: PlaybackFailure) {
        lastFailure = failure
        val item = currentItem
        val source = currentSource
        if (isLocalPlayback || item == null || source == null) {
            // A file on disk has no ladder: there is nothing to lower.
            _state.update {
                it.copy(isLoading = false, errorRetryable = true, error = PlaybackRecoveryPlan.exhaustedMessage(failure))
            }
            return
        }
        // A step already in flight owns the next decision.
        if (prepareJob?.isActive == true) return

        val decision =
            PlaybackRecoveryPlan.decide(
                failure = failure,
                isTranscoding = source.isTranscoding,
                currentBitrate = source.deliveredBitrate ?: source.negotiatedCap.takeIf { it in 1 until BitrateResolver.NO_CAP },
                sameQualityRetries = sameQualityRetries,
                attempts = recoveryAttempts,
            )
        if (decision == RecoveryDecision.GiveUp) {
            player.stop()
            _state.update {
                it.copy(
                    isLoading = false,
                    switchingTo = null,
                    errorRetryable = true,
                    error = PlaybackRecoveryPlan.exhaustedMessage(failure),
                )
            }
            return
        }
        recoveryAttempts++
        healthyPlayingMs = 0
        PlaybackRecoveryPlan.notice(decision)?.let(::showNotice)
        val posTicks = lastGoodPositionMs * TICKS_PER_MS
        val quality = _state.value.selectedQuality
        when (decision) {
            RecoveryDecision.Retry -> {
                sameQualityRetries++
                renegotiate { prepare(item, posTicks, quality, forceTranscode = forceTranscodeActive) }
            }
            RecoveryDecision.ForceTranscode -> {
                sameQualityRetries = 0
                renegotiate { prepare(item, posTicks, quality, forceTranscode = true) }
            }
            is RecoveryDecision.StepDown -> {
                // Remember what the link could not carry, so the next title's
                // Auto does not start above it again.
                if (failure == PlaybackFailure.STALL || failure == PlaybackFailure.NETWORK) {
                    source.deliveredBitrate?.let(engine::noteLinkLimit) ?: engine.noteLinkLimit(decision.to.maxBitrate!! * 2)
                }
                // The step becomes the selected quality, so the menu and the
                // next episode match what is actually playing.
                renegotiate { prepare(item, posTicks, decision.to, forceTranscode = true) }
            }
            RecoveryDecision.GiveUp -> Unit
        }
    }

    private fun showNotice(text: String) {
        noticeJob?.cancel()
        _state.update { it.copy(notice = text) }
        noticeJob =
            viewModelScope.launch {
                delay(NOTICE_MS)
                _state.update { if (it.notice == text) it.copy(notice = null) else it }
            }
    }

    /** Called from the tick loop: watches for stalls and for a long enough healthy stretch to reset the budget. */
    private fun watchHealth() {
        if (isLocalPlayback || currentSource == null || prepareJob?.isActive == true) return
        if (player.isPlaying) {
            lastGoodPositionMs = absolutePositionMs
            healthyPlayingMs += SEGMENT_POLL_MS
            if (healthyPlayingMs >= PlaybackRecoveryPlan.HEALTHY_PLAYBACK_MS) resetRecovery()
        }
        val stalled =
            stallDetector.update(
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                wantsToPlay = player.playWhenReady,
                isPlaying = player.isPlaying,
            )
        if (stalled) recover(PlaybackFailure.STALL)
    }

    /**
     * Runs a re-negotiation as the ONLY one in flight.
     *
     * Every re-negotiation mints a fresh playSessionId server-side, and prepare()
     * tears down whatever currentSource happens to be at entry. Two overlapping
     * calls therefore both tore down the ORIGINAL source, both started a
     * transcode, and whichever playSessionId was not currentSource at teardown
     * was never passed to stopTranscode -- an orphaned ffmpeg process on the
     * server until it timed out, plus two overlapping sessions on the dashboard.
     *
     * That was easy to trigger because the settings sheet does not dismiss on
     * selection and the loading spinner is hidden behind it, so tapping a second
     * quality is the natural response to the first appearing to do nothing.
     */
    private fun renegotiate(block: suspend () -> Unit) {
        val previous = prepareJob
        prepareJob =
            viewModelScope.launch {
                // cancelAndJoin, not cancel: the prior call must be fully
                // unwound before this one reads currentSource, or we are back to
                // two coroutines tearing down the same source.
                previous?.cancelAndJoin()
                block()
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
            renegotiate {
                prepare(item, posTicks, _state.value.selectedQuality, forceTranscode = true, audioStreamIndex = track.index)
            }
        } else {
            applyTrackSelections()
        }
    }

    fun selectSubtitle(index: Int) {
        val tracks = _state.value.subtitleTracks
        val from = desiredSubtitleTrack()
        val to = tracks.firstOrNull { !it.isOff && it.index == index }
        val source = currentSource
        val item = currentItem
        userChoseSubtitle = true
        desiredSubtitleIndex = index
        _state.update { it.copy(selectedSubtitleIndex = index) }
        val change =
            if (source == null || isLocalPlayback) {
                SubtitleChange.IN_PLAYER
            } else {
                SubtitleDecisions.change(from, to, source.carriesEmbeddedSubtitles)
            }
        when (change) {
            SubtitleChange.IN_PLAYER -> applyTrackSelections()
            SubtitleChange.RELOAD -> {
                // An embedded text track on a transcode: side-load it from
                // the server's VTT extraction. Same stream URL, so the server
                // transcode carries on; only the player's sources change.
                if (source != null && item != null) {
                    if (embeddedSubtitleFile(item.id, index) != null) {
                        val position = player.currentPosition
                        player.setMediaItem(buildMediaItem(item, source), position)
                        player.prepare()
                        applyTrackSelections()
                    } else {
                        // Reloads by itself once the track has been fetched.
                        fetchEmbeddedSubtitle(item, source, index)
                    }
                }
            }
            SubtitleChange.RENEGOTIATE -> {
                // Into or out of a burn-in, which only the server can do.
                burnInSubtitle = to?.delivery == SubtitleDelivery.BURN_IN
                if (item != null) {
                    val posTicks = absolutePositionMs * TICKS_PER_MS
                    if (burnInSubtitle) showNotice("Adding subtitles to the video…")
                    renegotiate { prepare(item, posTicks, _state.value.selectedQuality, forceTranscode = forceTranscodeActive) }
                }
            }
        }
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
                    watchHealth()
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
        val posTicks = absolutePositionMs * TICKS_PER_MS
        // Local playback builds no ProgressReporter, so this loop used to be a
        // complete no-op for downloaded items: the ONLY persistence point was
        // onCleared. Streaming checkpointed every 5s while offline viewing had
        // no safety net at all, so an OS reclaim or a crash mid-item lost the
        // position entirely and the item restarted from zero.
        val local = localKey
        if (local != null && trailerItemId == null) {
            downloads.savePlaybackPosition(local, posTicks)
            // Fall through: a download watched online also reports live (the
            // reporter only exists when the server answered at start).
        }
        val r = reporter ?: return
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
            val local = localKey
            if (local != null && trailerItemId == null) {
                // No ProgressReporter exists for local playback, so markPlayed is
                // unreachable. Stash the full runtime instead: the pending-sync
                // path posts it as the stopped position, which the server scores
                // as watched.
                downloads.savePlaybackPosition(local, durationTicks)
            }
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
                burnInSubtitle = false
                resetRecovery()
                // Mirror loadInitial: prefer a completed download. Without this,
                // auto-play-next always server-negotiated -- so with a whole
                // season downloaded it could not fire on a plane at all, and
                // online it streamed over cellular an episode already on disk.
                val nextLocal = runCatching { localDownload(next.id) }.getOrNull()
                if (nextLocal != null) {
                    prepareLocal(nextLocal.first, nextLocal.second)
                } else {
                    // Streaming now: positions must stop going to the previous
                    // episode's download row.
                    localKey = null
                    // The quality pick carries on to the next episode, as on
                    // Apple and Roku: a step down for a weak link still holds.
                    val quality = _state.value.selectedQuality
                    prepare(next, startTicks = 0, quality, forceTranscode = quality.forcesTranscode)
                }
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
        return nextEps.firstOrNull() ?: nextDownloadedEpisode(current, seriesId)
    }

    /**
     * Next episode resolved from the downloads database rather than the server.
     *
     * Every branch above needs getEpisodes/getSeasons, so offline they all
     * return empty and auto-play-next simply never fired -- with a whole season
     * downloaded, on the one occasion the feature matters most.
     */
    private suspend fun nextDownloadedEpisode(
        current: BaseItemDto,
        seriesId: String,
    ): BaseItemDto? {
        val all = runCatching { downloads.allDownloads() }.getOrNull() ?: return null
        // Stay on the server whose download is playing.
        val rows = localKey?.let { key -> all.filter { it.serverId == key.serverId } } ?: all
        val episodes = OfflineReconstruction.episodesForSeries(rows, seriesId, current.seriesName)
        val position = episodes.indexOfFirst { it.itemId == current.id }
        if (position < 0) return null
        return episodes.getOrNull(position + 1)?.let(OfflineReconstruction::asBaseItemDto)
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
        // Keyed on localKey, which follows currentItem, NOT the constructor's itemId: auto-play-next
        // advances currentItem while itemId stays pinned to the episode the user
        // originally opened. Saving against itemId wrote the NEXT episode's
        // position onto the PREVIOUS episode's row, and since savePlaybackPosition
        // also sets pendingProgressSync, that wrong position was then POSTed to
        // the server as the previous episode's stopped position, clobbering its
        // correct finished state.
        val local = localKey
        if (local != null && trailerItemId == null) {
            // Saved, then posted to the server right away when online: the
            // stopped report for a download goes through the pending-sync path
            // (one report, not two), and no longer waits for the next foreground.
            downloads.savePlaybackPositionAndSync(local, posTicks)
        }
        // Fire the stopped report + transcode teardown on a detached scope so it
        // survives the ViewModel being cleared, then release the player.
        val r = reporter.takeIf { local == null }
        val source = currentSource
        if (r != null) {
            teardownScope.launch {
                runCatching { r.reportStopped(posTicks) }
                if (source?.isTranscoding == true) source.playSessionId?.let { runCatching { engine.stopTranscode(it) } }
            }
        }
        player.removeListener(playerListener)
        mediaSession.release()
        player.release()
    }

    class Factory(
        private val app: Application,
        private val client: JellyfinClient,
        private val itemId: String,
        private val startFromBeginning: Boolean,
        private val trailerItemId: String?,
        private val downloadServerId: String?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
            PlayerViewModel(
                app = app,
                client = client,
                engine = ServiceLocator.playbackEngine.withClient(client),
                settings = ServiceLocator.appSettings,
                downloads = ServiceLocator.downloadManager,
                itemId = itemId,
                startFromBeginning = startFromBeginning,
                trailerItemId = trailerItemId,
                downloadServerId = downloadServerId,
                activeServerId = { ServiceLocator.session.activeServerId.value },
            ) as T
    }

    companion object {
        private const val TICKS_PER_MS = 10_000L
        private const val TICKS_PER_SECOND = 10_000_000L
        private const val SEGMENT_POLL_MS = 500L
        private const val CONNECT_WATCHDOG_MS = 5_000L
        private const val SEEK_INCREMENT_MS = 10_000L
        private const val ARTWORK_WIDTH = 300
        private const val NOTICE_MS = 4_000L
        private val sessionCounter = AtomicLong()
        private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun subtitleTrackId(index: Int): String = "sub-$index"
    }
}
