package dev.bitstorm.sashimi.core.admin

import java.util.Locale

/**
 * Jellyfin's `TranscodeReason` names in plain words. A cross-platform contract
 * (see [PlayMode]); a name added by a future server is split into words rather
 * than shown raw.
 */
object TranscodeReasonWords {
    private val words =
        mapOf(
            "ContainerNotSupported" to "Container",
            "VideoCodecNotSupported" to "Video codec",
            "AudioCodecNotSupported" to "Audio codec",
            "SubtitleCodecNotSupported" to "Subtitle burn-in",
            "AudioIsExternal" to "External audio",
            "SecondaryAudioNotSupported" to "Second audio track",
            "VideoProfileNotSupported" to "Video profile",
            "VideoLevelNotSupported" to "Video level",
            "VideoResolutionNotSupported" to "Resolution",
            "VideoBitDepthNotSupported" to "Bit depth",
            "VideoFramerateNotSupported" to "Frame rate",
            "RefFramesNotSupported" to "Reference frames",
            "AnamorphicVideoNotSupported" to "Anamorphic video",
            "InterlacedVideoNotSupported" to "Interlaced video",
            "AudioChannelsNotSupported" to "Audio channels",
            "AudioProfileNotSupported" to "Audio profile",
            "AudioSampleRateNotSupported" to "Sample rate",
            "AudioBitDepthNotSupported" to "Audio bit depth",
            "ContainerBitrateExceedsLimit" to "Bitrate cap",
            "VideoBitrateNotSupported" to "Video bitrate",
            "AudioBitrateNotSupported" to "Audio bitrate",
            "UnknownVideoStreamInfo" to "Unknown video",
            "UnknownAudioStreamInfo" to "Unknown audio",
            "DirectPlayError" to "Direct play failed",
            "VideoRangeTypeNotSupported" to "HDR → SDR",
            "VideoCodecTagNotSupported" to "Video codec tag",
            "StreamCountExceedsLimit" to "Too many streams",
            "VideoRotationNotSupported" to "Rotated video",
        )

    private val camelBoundary = Regex("(?<=[a-z0-9])(?=[A-Z])")

    fun of(reason: String): String {
        words[reason]?.let { return it }
        val split = reason.removeSuffix("NotSupported").split(camelBoundary).filter { it.isNotEmpty() }
        if (split.isEmpty()) return reason
        val sentence = split.joinToString(" ").lowercase(Locale.US)
        return sentence.replaceFirstChar { it.titlecase(Locale.US) }
    }

    /** Every reason, joined; null when there are none. */
    fun line(reasons: List<String>?): String? = reasons?.takeIf { it.isNotEmpty() }?.joinToString(" · ") { of(it) }
}
