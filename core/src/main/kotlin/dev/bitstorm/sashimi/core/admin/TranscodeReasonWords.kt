package dev.bitstorm.sashimi.core.admin

import java.util.Locale

/**
 * Jellyfin's `TranscodeReason` names in plain words. A cross-platform contract:
 * the words are sashimi-roku's `AdminDash.reasonText` table, letter for letter.
 * A name added by a future server is split into words rather than shown raw.
 */
object TranscodeReasonWords {
    private val words =
        mapOf(
            "ContainerNotSupported" to "Container not supported",
            "VideoCodecNotSupported" to "Video format not supported",
            "AudioCodecNotSupported" to "Audio format not supported",
            "SubtitleCodecNotSupported" to "Subtitles burned in",
            "AudioIsExternal" to "External audio track",
            "SecondaryAudioNotSupported" to "Extra audio track not supported",
            "VideoProfileNotSupported" to "Video profile not supported",
            "VideoLevelNotSupported" to "Video level not supported",
            "VideoResolutionNotSupported" to "Resolution too high",
            "VideoBitDepthNotSupported" to "Bit depth not supported",
            "VideoFramerateNotSupported" to "Frame rate not supported",
            "RefFramesNotSupported" to "Too many reference frames",
            "AnamorphicVideoNotSupported" to "Anamorphic video not supported",
            "InterlacedVideoNotSupported" to "Interlaced video",
            "AudioChannelsNotSupported" to "Too many audio channels",
            "AudioProfileNotSupported" to "Audio profile not supported",
            "AudioSampleRateNotSupported" to "Sample rate not supported",
            "AudioBitDepthNotSupported" to "Audio bit depth not supported",
            "ContainerBitrateExceedsLimit" to "Bitrate cap",
            "VideoBitrateNotSupported" to "Video bitrate too high",
            "AudioBitrateNotSupported" to "Audio bitrate too high",
            "UnknownVideoStreamInfo" to "Unknown video stream",
            "UnknownAudioStreamInfo" to "Unknown audio stream",
            "DirectPlayError" to "Direct play failed",
            "VideoRangeTypeNotSupported" to "HDR → SDR",
            "VideoCodecTagNotSupported" to "Video codec tag not supported",
            "StreamCountExceedsLimit" to "Too many streams",
        ).mapKeys { it.key.lowercase(Locale.US) }

    /** Roku's lookup is case-insensitive (BrightScript AA keys), so this is too. */
    fun of(reason: String): String = words[reason.lowercase(Locale.US)] ?: humanize(reason)

    /** "SpatialAudioNotSupported" → "Spatial audio not supported" (Roku's `humanize`). */
    private fun humanize(name: String): String =
        buildString {
            name.forEachIndexed { i, c ->
                if (i > 0 && c in 'A'..'Z') append(' ').append(c.lowercaseChar()) else append(c)
            }
        }

    /** Every reason, joined; null when there are none. */
    fun line(reasons: List<String>?): String? = reasons?.takeIf { it.isNotEmpty() }?.joinToString(" · ") { of(it) }
}
