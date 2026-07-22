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
) {
    /**
     * Negotiate playback for [itemId].
     *
     * @param resumeTicks resume position (100-ns ticks); 0 to start from the top.
     * @param maxBitrate effective cap (null = Auto → [AUTO_BITRATE_CAP]).
     * @param forceDirectPlay global Force Direct Play setting.
     * @param forceTranscode explicit Quality pick (overrides Force Direct Play).
     */
    suspend fun negotiate(
        itemId: String,
        resumeTicks: Long = 0,
        maxBitrate: Int? = null,
        forceDirectPlay: Boolean = false,
        forceTranscode: Boolean = false,
        audioStreamIndex: Int? = null,
        subtitleStreamIndex: Int? = null,
    ): PlaybackSource {
        val streamingBitrate = maxBitrate ?: AUTO_BITRATE_CAP
        val profile = profileBuilder.build(streamingBitrate)
        val response =
            client.postPlaybackInfo(
                itemId = itemId,
                deviceProfile = profile,
                maxStreamingBitrate = streamingBitrate,
                startTimeTicks = resumeTicks.takeIf { it > 0 },
                audioStreamIndex = audioStreamIndex,
                subtitleStreamIndex = subtitleStreamIndex,
                forceDirectPlay = forceDirectPlay,
                forceTranscode = forceTranscode,
            )
        val source = response.mediaSources?.firstOrNull() ?: throw PlaybackError.NoMediaSource
        return buildSource(itemId, source, response.playSessionId, resumeTicks)
    }

    private fun buildSource(
        itemId: String,
        source: MediaSourceInfo,
        playSessionId: String?,
        resumeTicks: Long,
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
        val playerStartMs = if (isTranscoding && resumeTicks > 0) 0L else resumeTicks / TICKS_PER_MS

        return PlaybackSource(
            streamUrl = url,
            playMethod = method,
            mediaSourceId = source.id,
            container = source.container,
            playSessionId = playSessionId,
            playerStartPositionMs = playerStartMs,
            isTranscoding = isTranscoding,
            streamInfo = streamInfo(method, source),
            audioTracks = audioTracks(source),
            subtitleTracks = subtitleTracks(source),
            transcodeReasons = source.transcodeReasons.orEmpty().map(::humanTranscodeReason),
        )
    }

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
                    ),
                )
            }
        }

    /**
     * Classifies the OSD chip from the negotiation result. The Swift app refines
     * this from a live /Sessions poll (e.g. a "Transcode" whose video is actually
     * being copied shows as Direct Stream); the Android version classifies
     * directly off the chosen delivery method — a deliberate simplification that
     * avoids an extra round-trip. The bitrate/codec detail comes off the source.
     */
    private fun streamInfo(
        method: PlayMethod,
        source: MediaSourceInfo,
    ): StreamInfo {
        val streamMethod =
            when (method) {
                PlayMethod.DIRECT_PLAY -> StreamMethod.DIRECT_PLAY
                PlayMethod.DIRECT_STREAM -> StreamMethod.DIRECT_STREAM
                PlayMethod.TRANSCODE -> StreamMethod.TRANSCODE
            }
        val detail =
            if (streamMethod == StreamMethod.TRANSCODE) {
                listOfNotNull(
                    source.videoResolution,
                    source.videoCodec?.uppercase(),
                    bitrateDetail(source),
                ).joinToString(" ").ifEmpty { null }
            } else {
                bitrateDetail(source)
            }
        return StreamInfo(method = streamMethod, label = StreamInfo.label(streamMethod), detail = detail)
    }

    /** "{n} Mbps" from the source bitrate (or its video stream's), else null. */
    private fun bitrateDetail(source: MediaSourceInfo): String? {
        val bps =
            source.bitrate?.takeIf { it > 0 }
                ?: source.mediaStreams?.firstOrNull { it.type == "Video" }?.bitRate?.takeIf { it > 0 }
                ?: return null
        return "${(bps / 1_000_000.0).roundToLong()} Mbps"
    }

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

        /**
         * Auto cap when no explicit bitrate is chosen — 20 Mbps, matching the
         * Swift autoBitrateCap() fallback. The reference measures real bandwidth
         * via a /Playback/BitrateTest probe first; that probe is not ported (the
         * fixed cap is a reasonable default and avoids an 8 MB test download).
         */
        const val AUTO_BITRATE_CAP = 20_000_000
    }
}
