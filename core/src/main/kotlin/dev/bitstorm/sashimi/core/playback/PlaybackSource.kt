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
 * A selectable subtitle track. index -1 / isOff marks the "Off" option. When
 * chosen, the app side-loads [SubtitleDelivery.External] tracks as VTT via
 * [PlaybackEngine.subtitleStreamUrl]; embedded tracks are selected in-player.
 */
data class SubtitleTrack(
    val index: Int,
    val displayName: String,
    val languageCode: String?,
    val isExternal: Boolean,
    val isOff: Boolean = false,
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
    val isTranscoding: Boolean,
    val streamInfo: StreamInfo,
    val audioTracks: List<AudioTrack>,
    val subtitleTracks: List<SubtitleTrack>,
    val transcodeReasons: List<String>,
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
            !transcodingUrl.isNullOrEmpty() -> SourceChoice.Transcode(transcodingUrl)
            !directStreamUrl.isNullOrEmpty() -> SourceChoice.DirectStream(directStreamUrl)
            else -> SourceChoice.DirectPlay
        }
    }
}
