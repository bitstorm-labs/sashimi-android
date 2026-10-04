package dev.bitstorm.sashimi.core.playback

import dev.bitstorm.sashimi.core.model.MediaSourceInfo

/**
 * How the negotiated stream is being delivered. [reportedPlayMethod] is the
 * string sent to /Sessions reporting — the Swift client only ever reports
 * "Transcode" or "DirectStream" (a true direct play is still reported as
 * "DirectStream"), so DIRECT_PLAY and DIRECT_STREAM both map to "DirectStream".
 */
enum class PlayMethod(
    val reportedPlayMethod: String,
) {
    DIRECT_PLAY("DirectStream"),
    DIRECT_STREAM("DirectStream"),
    TRANSCODE("Transcode"),
}

/** Stream-info OSD chip classification. Colours applied in the UI layer. */
enum class StreamMethod {
    DIRECT_PLAY, // green
    DIRECT_STREAM, // yellow
    TRANSCODE, // orange
}

/**
 * The OSD chip data: a coloured label plus an optional bitrate/codec detail
 * ("1080p HEVC · 12 Mbps"). Ported from the Swift refreshStreamInfo chip; the
 * Android version classifies from the negotiation result rather than a live
 * /Sessions poll (a deliberate simplification — see PlaybackEngine).
 */
data class StreamInfo(
    val method: StreamMethod,
    val label: String,
    val detail: String?,
) {
    companion object {
        fun label(method: StreamMethod): String =
            when (method) {
                // Viewer-facing wording: DirectPlay vs DirectStream is server
                // plumbing — both deliver identical video bits, so both read
                // "Original". Only a transcode changes the picture.
                StreamMethod.DIRECT_PLAY -> "Original"
                StreamMethod.DIRECT_STREAM -> "Original"
                StreamMethod.TRANSCODE -> "Converted"
            }
    }
}

/** A selectable audio track (index into the media source's streams). */
data class AudioTrack(
    val index: Int,
    val displayName: String,
    val languageCode: String?,
)

/**
 * A selectable subtitle track. index -1 / isOff marks the "Off" option. How a
 * chosen track reaches the screen is its [delivery]: text tracks (external file
 * or embedded) are side-loaded as VTT via [PlaybackEngine.subtitleStreamUrl];
 * image tracks are burned in by the server on request. See [SubtitleDecisions].
 */
data class SubtitleTrack(
    val index: Int,
    val displayName: String,
    val languageCode: String?,
    val isExternal: Boolean,
    val isOff: Boolean = false,
    val delivery: SubtitleDelivery = if (isExternal) SubtitleDelivery.EXTERNAL_FILE else SubtitleDelivery.EMBEDDED_TEXT,
) {
    companion object {
        val OFF = SubtitleTrack(index = -1, displayName = "Off", languageCode = null, isExternal = false, isOff = true)
    }
}

/**
 * The fully-negotiated, ready-to-play result the player ViewModel consumes.
 * :core produces DATA only — the resolved URL(s), which method, the session id
 * for reporting/teardown, the resume position the player should seek to, and the
 * track lists. No Media3 player object crosses the module boundary.
 */
data class PlaybackSource(
    val streamUrl: String,
    val playMethod: PlayMethod,
    val mediaSourceId: String,
    val container: String?,
    val playSessionId: String?,
    /**
     * Position ExoPlayer should seek to on prepare, in milliseconds. Zero when a
     * transcode already baked the StartTimeTicks into its TranscodingUrl (the
     * stream itself starts there); the resume offset otherwise (direct play /
     * direct stream, where the player must seek).
     */
    val playerStartPositionMs: Long,
    /**
     * How far into the item the player's own timeline zero actually is, in
     * milliseconds.
     *
     * Non-zero only for a resumed transcode: the server bakes StartTimeTicks
     * into the TranscodingUrl, so the returned HLS timeline is 0-based *at the
     * resume point*. That makes `player.currentPosition` a RELATIVE value, and
     * every consumer that means "how far into the item are we" must add this
     * back: progress reporting, re-negotiation, media-segment matching, and the
     * scrubber. Zero for direct play and direct stream, whose timelines already
     * span the whole item.
     */
    val timelineOffsetMs: Long,
    val isTranscoding: Boolean,
    val streamInfo: StreamInfo,
    val audioTracks: List<AudioTrack>,
    val subtitleTracks: List<SubtitleTrack>,
    val transcodeReasons: List<String>,
    /**
     * What the stream runs at, in bits/second: the transcode's video + audio
     * target, else the source bitrate. What the recovery ladder steps down
     * from; null when the server reported neither.
     */
    val deliveredBitrate: Int? = null,
    /** The bitrate cap this stream was negotiated with. */
    val negotiatedCap: Int = 0,
)

sealed class PlaybackError(
    message: String,
) : Exception(message) {
    object NoMediaSource : PlaybackError("No playable media source found")

    object NoStreamUrl : PlaybackError("Could not generate stream URL")
}

/**
 * Pure stream-URL selection, factored out of the Swift `setupPlayer` branch so
 * it is unit-testable without a client. Branches on the PRESENCE of the server-
 * provided TranscodingUrl / DirectStreamUrl (not the Supports* booleans, which
 * the Swift code decodes but does not branch on):
 *  - TranscodingUrl present → [Transcode]
 *  - else DirectStreamUrl present → [DirectStream]
 *  - else → [DirectPlay] (build the static /Videos/{id}/stream URL).
 */
sealed interface SourceChoice {
    data class Transcode(
        val path: String,
    ) : SourceChoice

    data class DirectStream(
        val path: String,
    ) : SourceChoice

    object DirectPlay : SourceChoice
}

object SourceSelector {
    fun choose(source: MediaSourceInfo): SourceChoice {
        val transcodingUrl = source.transcodingUrl
        val directStreamUrl = source.directStreamUrl
        return when {
            !transcodingUrl.isNullOrEmpty() -> SourceChoice.Transcode(HlsVariantPin.pin(transcodingUrl, source))
            !directStreamUrl.isNullOrEmpty() -> SourceChoice.DirectStream(directStreamUrl)
            else -> SourceChoice.DirectPlay
        }
    }
}

/**
 * Why the server is transcoding. Current Jellyfin servers (verified on 12.1)
 * do not fill `MediaSource.TranscodeReasons` in the PlaybackInfo response;
 * the reasons ride only in the TranscodingUrl's `TranscodeReasons` query
 * parameter. Reading only the field left the OSD chip's reasons and Auto's
 * re-encode width pass with nothing to go on.
 */
object TranscodeReasons {
    fun of(source: MediaSourceInfo): List<String>? {
        source.transcodeReasons?.takeIf { it.isNotEmpty() }?.let { return it }
        val url = source.transcodingUrl ?: return null
        val raw = Regex("[?&]TranscodeReasons=([^&]*)").find(url)?.groupValues?.get(1) ?: return null
        return java.net.URLDecoder.decode(raw, "UTF-8").split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { null }
    }
}

/**
 * Pins an HDR transcode to its single variant playlist.
 *
 * When Jellyfin stream-copies an HDR video into HLS, its master playlist
 * (`DynamicHlsHelper`) appends an H.264 SDR variant, and HEVC/AV1 SDR variants
 * when the server may encode those, at the SAME `BANDWIDTH` ("HACK: Use the
 * same bitrate so that the client can choose by other attributes"). Each
 * extra variant is a full server re-encode. ExoPlayer's adaptive selection
 * treats them as equals and may pick or switch to one, which starts a fresh
 * ffmpeg mid-stream: the stall loop the Apple TV hit until 1.4.2 pinned
 * `main.m3u8`. `main.m3u8` is the variant endpoint for exactly the stream the
 * negotiation chose, with the same query.
 *
 * Applied only to HDR sources, where the extra variants exist; an SDR
 * transcode's master has one variant and is left alone.
 *
 * Defensive today: Android's transcoding profile is h264-only, so an HDR
 * (HEVC/AV1) source is always re-encoded rather than stream-copied, and the
 * server adds no fallback variants (verified: a 4K DV/HDR10+ source's master
 * had one variant). It matters the moment the transcoding codecs widen to
 * allow an HEVC copy, which is when the Apple TV hit the stall loop.
 */
object HlsVariantPin {
    private val HDR_RANGES = setOf("HDR", "HDR10", "HDR10PLUS", "HLG", "DOVI")

    fun isHdr(source: MediaSourceInfo): Boolean {
        val video = source.mediaStreams?.firstOrNull { it.type == "Video" } ?: return false
        val rangeType = video.videoRangeType?.uppercase().orEmpty()
        return rangeType in HDR_RANGES || rangeType.startsWith("DOVI") || video.videoRange?.uppercase() == "HDR"
    }

    fun pin(
        transcodingUrl: String,
        source: MediaSourceInfo,
    ): String {
        if (!isHdr(source)) return transcodingUrl
        val path = transcodingUrl.substringBefore('?')
        if (!path.endsWith("/master.m3u8")) return transcodingUrl
        return path.removeSuffix("master.m3u8") + "main.m3u8" + transcodingUrl.substring(path.length)
    }
}
