package dev.bitstorm.sashimi.core.downloads

import dev.bitstorm.sashimi.core.model.MediaSourceInfo

/**
 * Port of the Swift `DeviceMediaCompatibility.canDirectPlayOnDevice`. Decides
 * whether a media source can be played back from its raw file on this device —
 * the gate behind the "Original" download tier (fail-closed to [DownloadQuality.HIGH]
 * otherwise). Pure, so the gate is unit-testable across codec/container combos.
 *
 * Allowlists mirror the DirectPlayProfiles the client advertises for MP4-family
 * containers. Fails closed everywhere: nil/empty/unknown container, empty
 * streams, or an unrecognised codec all return false.
 */
object DeviceMediaCompatibility {
    private val directPlayContainers = setOf("mp4", "m4v", "mov")
    private val directPlayVideoCodecs = setOf("h264", "hevc")
    private val directPlayAudioCodecs = setOf("aac", "ac3", "eac3")

    /** Normalises common codec aliases to the canonical allowlist tokens. */
    fun canonicalCodec(codec: String): String =
        when (val c = codec.lowercase().trim()) {
            "h265" -> "hevc"
            "avc", "x264", "mpeg4/avc" -> "h264"
            else -> c
        }

    /** True only for bare Dolby Vision Profile 5 (no cross-compatible fallback layer). */
    private fun isDolbyVisionWithoutFallback(videoRangeType: String?): Boolean = videoRangeType?.lowercase()?.trim() == "dovi"

    fun canDirectPlayOnDevice(
        source: MediaSourceInfo,
        deviceSupportsDolbyVision: Boolean = false,
    ): Boolean {
        val rawContainer = source.container
        if (rawContainer.isNullOrEmpty()) return false
        val containers =
            rawContainer.lowercase()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        // Every token must be an allowlisted container ("mkv,mp4" fails closed).
        if (containers.isEmpty() || !containers.all { it in directPlayContainers }) return false

        val streams = source.mediaStreams
        if (streams.isNullOrEmpty()) return false

        val hasCompatibleVideo =
            streams.filter { it.type == "Video" }.any { stream ->
                val codec = stream.codec
                if (codec.isNullOrEmpty()) {
                    false
                } else if (canonicalCodec(codec) !in directPlayVideoCodecs) {
                    false
                } else {
                    !(isDolbyVisionWithoutFallback(stream.videoRangeType) && !deviceSupportsDolbyVision)
                }
            }
        if (!hasCompatibleVideo) return false

        val hasCompatibleAudio =
            streams.filter { it.type == "Audio" }.any { stream ->
                val codec = stream.codec
                !codec.isNullOrEmpty() && canonicalCodec(codec) in directPlayAudioCodecs
            }
        return hasCompatibleAudio
    }
}
