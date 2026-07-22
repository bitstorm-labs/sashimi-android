package dev.bitstorm.sashimi.core.playback

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Jellyfin DeviceProfile sent in the getPlaybackInfo POST body. Mirrors the
 * SHAPE of the Swift `JellyfinClient.getPlaybackInfo` inline profile dictionary,
 * but the codec/container lists are Android-appropriate and wider than iOS:
 * Android decoders routinely handle mkv/webm/vp9/av1 that AVPlayer cannot, so
 * [DeviceProfileBuilder] advertises those when the device actually has a decoder
 * (queried at runtime via [CodecCapabilities] / MediaCodecList). h264 is always
 * offered; hevc/vp9/av1 are gated. Declaring a codec the hardware can't decode
 * produces a black screen, hence the gate.
 *
 * `MaxStreamingBitrate` is intentionally carried both here and at the top level
 * of the POST body — which one a given server honours is version-dependent
 * (ported note from the Swift client).
 */
@Serializable
data class DeviceProfile(
    @SerialName("MaxStreamingBitrate") val maxStreamingBitrate: Int,
    @SerialName("MaxStaticBitrate") val maxStaticBitrate: Int = 100_000_000,
    @SerialName("MusicStreamingTranscodingBitrate") val musicStreamingTranscodingBitrate: Int = 384_000,
    @SerialName("DirectPlayProfiles") val directPlayProfiles: List<DirectPlayProfile>,
    @SerialName("TranscodingProfiles") val transcodingProfiles: List<TranscodingProfile>,
    @SerialName("SubtitleProfiles") val subtitleProfiles: List<SubtitleProfile>,
    @SerialName("ContainerProfiles") val containerProfiles: List<String> = emptyList(),
)

@Serializable
data class DirectPlayProfile(
    @SerialName("Container") val container: String,
    @SerialName("Type") val type: String = "Video",
    @SerialName("VideoCodec") val videoCodec: String,
    @SerialName("AudioCodec") val audioCodec: String,
)

@Serializable
data class TranscodingProfile(
    @SerialName("Container") val container: String,
    @SerialName("Type") val type: String = "Video",
    @SerialName("VideoCodec") val videoCodec: String,
    @SerialName("AudioCodec") val audioCodec: String,
    @SerialName("Protocol") val protocol: String,
    @SerialName("Context") val context: String = "Streaming",
    // Strings on the wire, matching the Swift dictionary literal.
    @SerialName("MaxAudioChannels") val maxAudioChannels: String = "6",
    @SerialName("MinSegments") val minSegments: String = "2",
    @SerialName("BreakOnNonKeyFrames") val breakOnNonKeyFrames: Boolean = true,
)

@Serializable
data class SubtitleProfile(
    @SerialName("Format") val format: String,
    @SerialName("Method") val method: String,
)

/**
 * The getPlaybackInfo POST body. Mirrors the Swift request, with two Android
 * additions the reference did client-side: [startTimeTicks] (so a transcode
 * bakes the resume offset into its TranscodingUrl instead of streaming from 0
 * and seeking) and [audioStreamIndex] / [subtitleStreamIndex] (so the server
 * transcodes the chosen audio track). MaxStreamingBitrate is sent both here and
 * inside [deviceProfile] deliberately (server-version dependent).
 */
@Serializable
data class PlaybackInfoRequest(
    @SerialName("UserId") val userId: String,
    @SerialName("MaxStreamingBitrate") val maxStreamingBitrate: Int,
    @SerialName("StartTimeTicks") val startTimeTicks: Long? = null,
    @SerialName("AudioStreamIndex") val audioStreamIndex: Int? = null,
    @SerialName("SubtitleStreamIndex") val subtitleStreamIndex: Int? = null,
    @SerialName("DeviceProfile") val deviceProfile: DeviceProfile,
    @SerialName("EnableDirectPlay") val enableDirectPlay: Boolean,
    @SerialName("EnableDirectStream") val enableDirectStream: Boolean,
    @SerialName("EnableTranscoding") val enableTranscoding: Boolean,
    @SerialName("AllowVideoStreamCopy") val allowVideoStreamCopy: Boolean = true,
    @SerialName("AllowAudioStreamCopy") val allowAudioStreamCopy: Boolean = true,
    @SerialName("AutoOpenLiveStream") val autoOpenLiveStream: Boolean = true,
)

/**
 * Builds the Android DeviceProfile with runtime codec detection. h264 direct
 * play is always offered; hevc/vp9/av1 only when the device reports a decoder.
 * The HLS transcode fallback targets h264 + aac (universally decodable).
 */
class DeviceProfileBuilder(
    private val codecs: CodecCapabilities,
) {
    fun build(maxStreamingBitrate: Int): DeviceProfile {
        val videoCodecs =
            buildList {
                add("h264")
                if (codecs.canDecode(CodecCapabilities.MimeTypes.HEVC)) add("hevc")
                if (codecs.canDecode(CodecCapabilities.MimeTypes.VP9)) add("vp9")
                if (codecs.canDecode(CodecCapabilities.MimeTypes.AV1)) add("av1")
            }.joinToString(",")

        return DeviceProfile(
            maxStreamingBitrate = maxStreamingBitrate,
            directPlayProfiles =
                listOf(
                    DirectPlayProfile(container = "mp4,m4v,mov", videoCodec = videoCodecs, audioCodec = DIRECT_AUDIO),
                    DirectPlayProfile(container = "mkv,webm", videoCodec = videoCodecs, audioCodec = DIRECT_AUDIO),
                ),
            transcodingProfiles =
                listOf(
                    TranscodingProfile(
                        container = "ts",
                        videoCodec = "h264",
                        audioCodec = "aac",
                        protocol = "hls",
                    ),
                ),
            subtitleProfiles =
                listOf(
                    SubtitleProfile(format = "vtt", method = "External"),
                    SubtitleProfile(format = "srt", method = "External"),
                ),
        )
    }

    companion object {
        private const val DIRECT_AUDIO = "aac,ac3,eac3,mp3,opus,flac"
    }
}

/**
 * The three PlaybackInfo Enable* flags, derived from the two force settings.
 * Ported from the Swift getPlaybackInfo body construction:
 * `effectiveForceDirectPlay = forceDirectPlay && !forceTranscode` — an explicit
 * Quality pick (which sets forceTranscode) overrides the global Force Direct
 * Play setting so the bitrate cap visibly takes effect.
 *
 * Roku lesson (ported): on iOS a burned-in subtitle selection had to defeat
 * Force Direct Play. On Android subtitles are ALWAYS delivered as external VTT
 * side-loads, so a subtitle choice never forces a transcode — Force Direct Play
 * is only ever overridden by an explicit Quality pick, never by subtitles.
 */
data class NegotiationFlags(
    val enableDirectPlay: Boolean,
    val enableDirectStream: Boolean,
    val enableTranscoding: Boolean,
) {
    companion object {
        fun derive(
            forceDirectPlay: Boolean,
            forceTranscode: Boolean,
        ): NegotiationFlags {
            val effectiveForceDirectPlay = forceDirectPlay && !forceTranscode
            return NegotiationFlags(
                enableDirectPlay = !forceTranscode,
                enableDirectStream = !effectiveForceDirectPlay && !forceTranscode,
                enableTranscoding = !effectiveForceDirectPlay,
            )
        }
    }
}
