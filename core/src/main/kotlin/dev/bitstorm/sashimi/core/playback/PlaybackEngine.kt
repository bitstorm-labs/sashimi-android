package dev.bitstorm.sashimi.core.playback

import dev.bitstorm.sashimi.core.model.MediaSourceInfo
import dev.bitstorm.sashimi.core.network.JellyfinClient
import kotlin.math.roundToLong

/**
 * The :core playback negotiation façade. Given an item and the user's playback
 * preferences it builds a [DeviceProfile], POSTs getPlaybackInfo, selects the
 * stream URL (direct play / direct stream / HLS transcode) and returns a
 * [PlaybackSource] of pure DATA — URL, method, session id, resume position, and
 * track lists. No Media3 player object crosses this boundary; the :app layer
 * turns the data into a MediaItem.
 *
 * Port of the Swift PlayerViewModel `setupPlayer` negotiation, minus the live
 * /Sessions bandwidth probe and stream-info poll (see notes below).
 */
class PlaybackEngine(
    private val client: JellyfinClient,
    private val profileBuilder: DeviceProfileBuilder,
    /** Shared across every engine (and so every server's client); keyed by server URL. */
    private val bandwidth: BandwidthMonitor = BandwidthMonitor(),
) {
    /**
     * Negotiate playback for [itemId].
     *
     * @param resumeTicks resume position (100-ns ticks); 0 to start from the top.
     * @param maxBitrate effective cap (null = Auto, see [autoCap]).
     * @param forceDirectPlay global Force Direct Play setting.
     * @param forceTranscode explicit Quality pick, a recovery step, or a
     *   burned-in subtitle (overrides Force Direct Play).
     * @param subtitleStreamIndex the subtitle to burn in, or
     *   [SubtitleDecisions.NO_SERVER_SUBTITLE]. Never null on the wire: a null
     *   lets the server apply its own default and burn in a track the app
     *   shows as "Off".
     */
    suspend fun negotiate(
        itemId: String,
        resumeTicks: Long = 0,
        maxBitrate: Int? = null,
        maxWidth: Int? = null,
        forceDirectPlay: Boolean = false,
        forceTranscode: Boolean = false,
        audioStreamIndex: Int? = null,
        subtitleStreamIndex: Int = SubtitleDecisions.NO_SERVER_SUBTITLE,
    ): PlaybackSource {
        val streamingBitrate = maxBitrate ?: autoCap()
        val first =
            post(itemId, streamingBitrate, maxWidth, resumeTicks, forceDirectPlay, forceTranscode, audioStreamIndex, subtitleStreamIndex)
        // Auto's first request carries no width: Jellyfin applies a Width
        // condition to direct play too, and would push a 4K source that fits
        // under the cap down to 1080p. When the answer is a video re-encode,
        // ask again with the width the cap can carry, or the server encodes at
        // the source resolution (a 4K encode at a remote link's few Mbps).
        // PlaybackInfo starts no ffmpeg; the transcode starts when the
        // playlist is fetched, so the first answer costs nothing to discard.
        val (response, source) =
            if (maxBitrate == null && maxWidth == null) {
                val reencodeWidth =
                    AutoBitrate.reencodeWidth(
                        streamingBitrate,
                        first.second.mediaStreams?.firstOrNull { it.type == "Video" }?.width,
                        first.second.transcodeReasons.takeIf { !first.second.transcodingUrl.isNullOrEmpty() },
                    )
                if (reencodeWidth != null) {
                    post(
                        itemId,
                        streamingBitrate,
                        reencodeWidth,
                        resumeTicks,
                        forceDirectPlay,
                        forceTranscode,
                        audioStreamIndex,
                        subtitleStreamIndex,
                    )
                } else {
                    first
                }
            } else {
                first
            }
        return buildSource(itemId, source, response.playSessionId, resumeTicks, streamingBitrate)
    }

    private suspend fun post(
        itemId: String,
        bitrate: Int,
        maxWidth: Int?,
        resumeTicks: Long,
        forceDirectPlay: Boolean,
        forceTranscode: Boolean,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int,
    ): Pair<dev.bitstorm.sashimi.core.model.PlaybackInfoResponse, MediaSourceInfo> {
        val response =
            client.postPlaybackInfo(
                itemId = itemId,
                deviceProfile = profileBuilder.build(bitrate, maxWidth),
                maxStreamingBitrate = bitrate,
                startTimeTicks = resumeTicks.takeIf { it > 0 },
                audioStreamIndex = audioStreamIndex,
                subtitleStreamIndex = subtitleStreamIndex,
                forceDirectPlay = forceDirectPlay,
                forceTranscode = forceTranscode,
            )
        val source = response.mediaSources?.firstOrNull() ?: throw PlaybackError.NoMediaSource
        return response to source
    }

    /**
     * The Auto cap for this engine's server: the measured link with headroom,
     * else 4 Mbps for a remote server and 100 Mbps on the LAN. A remote server
     * with no measurement waits briefly for the probe ([BandwidthMonitor.PROBE_WAIT_MS])
     * before settling for the conservative default.
     */
    suspend fun autoCap(): Int {
        val server = client.currentServerUrl ?: return AutoBitrate.UNMEASURED_REMOTE_CAP
        val local = ServerLocality.isLocal(server)
        val measured =
            bandwidth.measureOrWait(server, if (local) 0 else BandwidthMonitor.PROBE_WAIT_MS) { client.measureBandwidth() }
        return AutoBitrate.cap(measured, local)
    }

    /** Start measuring this server's link in the background, if nothing fresh is known. */
    fun prewarmBandwidth() {
        val server = client.currentServerUrl ?: return
        bandwidth.refresh(server) { client.measureBandwidth() }
    }

    /**
     * A stream at [bitsPerSecond] could not be sustained on this server's link:
     * Auto must not climb back above it on the next title.
     */
    fun noteLinkLimit(bitsPerSecond: Int) {
        val server = client.currentServerUrl ?: return
        bandwidth.limitTo(server, bitsPerSecond)
    }

    private fun buildSource(
        itemId: String,
        source: MediaSourceInfo,
        playSessionId: String?,
        resumeTicks: Long,
        negotiatedCap: Int,
    ): PlaybackSource {
        val (url, method) =
            when (val choice = SourceSelector.choose(source)) {
                is SourceChoice.Transcode ->
                    (client.buildURL(choice.path) ?: throw PlaybackError.NoStreamUrl) to PlayMethod.TRANSCODE
                is SourceChoice.DirectStream ->
                    (client.buildURL(choice.path) ?: throw PlaybackError.NoStreamUrl) to PlayMethod.DIRECT_STREAM
                SourceChoice.DirectPlay ->
                    (
                        client.getPlaybackURL(itemId, source.id, source.container)
                            ?: throw PlaybackError.NoStreamUrl
                    ) to PlayMethod.DIRECT_PLAY
            }

        val isTranscoding = method == PlayMethod.TRANSCODE
        // A transcode bakes StartTimeTicks into its TranscodingUrl, so the stream
        // itself starts at the resume point → player starts at 0. Direct play /
        // stream serve from the top, so the player seeks.
        // The two halves of the same fact, kept together in ResumeTimeline: a
        // resumed transcode's stream already starts at the resume point, so the
        // player starts at 0 AND callers need that offset back to recover an
        // absolute position. See PlaybackSource.timelineOffsetMs.
        val playerStartMs = ResumeTimeline.playerStartMs(isTranscoding, resumeTicks)
        val timelineOffsetMs = ResumeTimeline.timelineOffsetMs(isTranscoding, resumeTicks)

        return PlaybackSource(
            streamUrl = url,
            playMethod = method,
            mediaSourceId = source.id,
            container = source.container,
            playSessionId = playSessionId,
            playerStartPositionMs = playerStartMs,
            timelineOffsetMs = timelineOffsetMs,
            isTranscoding = isTranscoding,
            streamInfo = streamInfo(method, source, url),
            audioTracks = audioTracks(source),
            subtitleTracks = subtitleTracks(source),
            transcodeReasons = source.transcodeReasons.orEmpty().map(::humanTranscodeReason),
            deliveredBitrate = deliveredBitrate(method, source, url)?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
            negotiatedCap = negotiatedCap,
        )
    }

    /**
     * The same negotiation bound to [other]: a title from a non-active server
     * negotiates, streams, and tears down its transcode on that server. The
     * device profile is the device's, so it is shared, as is the bandwidth memory.
     */
    fun withClient(other: JellyfinClient): PlaybackEngine = if (other === client) this else PlaybackEngine(other, profileBuilder, bandwidth)

    fun subtitleStreamUrl(
        itemId: String,
        subtitleStreamIndex: Int,
        mediaSourceId: String? = null,
    ): String? = client.subtitleStreamUrl(itemId, subtitleStreamIndex, mediaSourceId)

    suspend fun stopTranscode(playSessionId: String) {
        runCatching { client.stopActiveEncoding(playSessionId) }
    }

    private fun audioTracks(source: MediaSourceInfo): List<AudioTrack> =
        source.audioStreams.mapIndexed { i, stream ->
            AudioTrack(
                index = stream.index ?: i,
                displayName = stream.displayTitle ?: stream.language ?: "Audio ${i + 1}",
                languageCode = stream.language,
            )
        }

    private fun subtitleTracks(source: MediaSourceInfo): List<SubtitleTrack> =
        buildList {
            add(SubtitleTrack.OFF)
            source.subtitleStreams.forEachIndexed { i, stream ->
                add(
                    SubtitleTrack(
                        index = stream.index ?: i,
                        displayName = stream.displayTitle ?: stream.language ?: "Subtitle ${i + 1}",
                        languageCode = stream.language,
                        isExternal = stream.isExternal == true,
                        delivery = SubtitleDelivery.of(stream.codec, stream.isExternal == true),
                    ),
                )
            }
        }

    /**
     * Classifies the OSD chip from the negotiation result. The Swift app refines
     * this from a live /Sessions poll (e.g. a "Transcode" whose video is actually
     * being copied shows as Direct Stream); the Android version classifies
     * directly off the chosen delivery method — a deliberate simplification that
     * avoids an extra round-trip. The bitrate detail reflects what's DELIVERED.
     */
    private fun streamInfo(
        method: PlayMethod,
        source: MediaSourceInfo,
        streamUrl: String,
    ): StreamInfo {
        val streamMethod =
            when (method) {
                PlayMethod.DIRECT_PLAY -> StreamMethod.DIRECT_PLAY
                PlayMethod.DIRECT_STREAM -> StreamMethod.DIRECT_STREAM
                PlayMethod.TRANSCODE -> StreamMethod.TRANSCODE
            }
        val bitrate =
            if (streamMethod == StreamMethod.TRANSCODE) {
                deliveredBitrateDetail(source, streamUrl)
            } else {
                bitrateDetail(sourceBitrate(source))
            }
        val detail =
            if (streamMethod == StreamMethod.TRANSCODE) {
                listOfNotNull(source.videoResolution, source.videoCodec?.uppercase(), bitrate)
                    .joinToString(" ").ifEmpty { null }
            } else {
                bitrate
            }
        return StreamInfo(method = streamMethod, label = StreamInfo.label(streamMethod), detail = detail)
    }

    /** Source total bitrate (or its video stream's) in bps, else null. */
    private fun sourceBitrate(source: MediaSourceInfo): Long? =
        (
            source.bitrate?.takeIf { it > 0 }
                ?: source.mediaStreams?.firstOrNull { it.type == "Video" }?.bitRate?.takeIf { it > 0 }
        )?.toLong()

    private fun bitrateDetail(bps: Long?): String? = bps?.let { "${(it / 1_000_000.0).roundToLong()} Mbps" }

    /**
     * A transcode delivers the TARGET rate, not the source. Delivered video =
     * min(source, target) — the min covers both an audio-only transcode (video
     * COPIED at the source rate) and a real re-encode (video capped at the lower
     * target, e.g. burn-in subtitles) — plus the transcoded audio target. Read
     * off the TranscodingUrl's VideoBitrate/AudioBitrate params (no extra call).
     * Falls back to the source rate when the URL omits them.
     */
    private fun deliveredBitrateDetail(
        source: MediaSourceInfo,
        streamUrl: String,
    ): String? = bitrateDetail(transcodeBitrate(source, streamUrl))

    private fun transcodeBitrate(
        source: MediaSourceInfo,
        streamUrl: String,
    ): Long? {
        val target = urlIntParam(streamUrl, "VideoBitrate") ?: return sourceBitrate(source)
        val srcVideo = source.mediaStreams?.firstOrNull { it.type == "Video" }?.bitRate?.takeIf { it > 0 }?.toLong()
        val deliveredVideo = if (srcVideo != null && srcVideo < target) srcVideo else target
        val audio = urlIntParam(streamUrl, "AudioBitrate") ?: 0L
        return deliveredVideo + audio
    }

    /** What the stream runs at, for the recovery ladder; see [PlaybackSource.deliveredBitrate]. */
    private fun deliveredBitrate(
        method: PlayMethod,
        source: MediaSourceInfo,
        streamUrl: String,
    ): Long? = if (method == PlayMethod.TRANSCODE) transcodeBitrate(source, streamUrl) else sourceBitrate(source)

    /** Integer query-string parameter (e.g. VideoBitrate) from a URL, else null. */
    private fun urlIntParam(
        url: String,
        key: String,
    ): Long? = Regex("[?&]$key=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()

    private fun humanTranscodeReason(reason: String): String =
        when (reason) {
            "ContainerNotSupported" -> "container"
            "ContainerBitrateExceedsLimit" -> "bitrate limit"
            "VideoCodecNotSupported" -> "video codec"
            "AudioCodecNotSupported" -> "audio codec"
            "SubtitleCodecNotSupported" -> "subtitles"
            "VideoResolutionNotSupported" -> "resolution"
            "AudioChannelsNotSupported" -> "audio channels"
            "UnknownVideoStreamInfo", "UnknownAudioStreamInfo" -> "stream info"
            else -> reason
        }

    companion object {
        /** Ticks per millisecond (100-ns ticks → ms). */
        const val TICKS_PER_MS = 10_000L
    }
}
