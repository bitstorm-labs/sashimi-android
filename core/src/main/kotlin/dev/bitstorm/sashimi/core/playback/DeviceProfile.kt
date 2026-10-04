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
    /**
     * How a resolution cap is actually expressed to Jellyfin.
     *
     * MaxStreamingBitrate is a BITRATE ceiling and nothing more: it never
     * constrains resolution. A "720p" option that only lowers the bitrate keeps
     * delivering 1080p, just softer. The server sizes its transcode from a
     * Video CodecProfile carrying a Width condition, which is what jellyfin-web
     * sends and what this list is for.
     */
    @SerialName("CodecProfiles") val codecProfiles: List<CodecProfile> = emptyList(),
)

@Serializable
data class CodecProfile(
    @SerialName("Type") val type: String = "Video",
    @SerialName("Conditions") val conditions: List<ProfileCondition>,
    /** The codec the conditions apply to; null applies them to every video codec. */
    @SerialName("Codec") val codec: String? = null,
)

@Serializable
data class ProfileCondition(
    @SerialName("Condition") val condition: String,
    @SerialName("Property") val property: String,
    @SerialName("Value") val value: String,
    // Advisory rather than mandatory: the server should downscale to satisfy
    // this, not refuse the item outright when it cannot.
    @SerialName("IsRequired") val isRequired: Boolean = false,
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
    /**
     * Required for [audioStreamIndex] and [subtitleStreamIndex] to mean
     * anything: Jellyfin's MediaInfoHelper copies both into the stream
     * options only when the request names the media source it is
     * negotiating. Without it the server silently used its defaults: the
     * audio track picked on a transcode was ignored, and SubtitleStreamIndex
     * (-1 or a burn-in) did nothing.
     */
    @SerialName("MediaSourceId") val mediaSourceId: String? = null,
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
 * Builds the Android DeviceProfile from what this device can actually decode.
 *
 * - Video: h264 always; hevc/vp9/av1 when a decoder exists. Each codec carries
 *   a CodecProfile with what its decoders really support (max frame size, 8 or
 *   10 bit, the HDR/DV range types the decoder AND display can show), so a
 *   source beyond that is transcoded instead of failing at the decoder.
 * - Audio: only codecs with a decoder. ExoPlayer has no software AC-3, E-AC-3,
 *   DTS or TrueHD decoder, so advertising them statically made a device without
 *   one direct-play audio it could not decode.
 * - Transcode: HLS h264 + aac, which every device decodes. Audio is not copied
 *   into the transcode even when the device could decode it: a copied 640 kbps
 *   AC-3 track is most of a low tier's budget.
 * - Subtitles: text formats External (the app side-loads them as VTT); image
 *   formats Encode, which the server applies only to the index the app sends
 *   (PlaybackEngine always sends one, -1 for none).
 */
class DeviceProfileBuilder(
    private val codecs: CodecCapabilities,
) {
    /**
     * @param maxWidth caps the transcode's output width. Null means no cap
     *   (Auto). This is the ONLY thing that actually changes resolution; the
     *   bitrate argument cannot.
     */
    fun build(
        maxStreamingBitrate: Int,
        maxWidth: Int? = null,
    ): DeviceProfile {
        val video = videoCodecs()
        val videoCodecs = video.keys.joinToString(",")
        val audio = directAudioCodecs()

        val codecProfiles =
            buildList {
                // Height is deliberately left unconstrained on the tier cap:
                // pinning both would letterbox or refuse non-16:9 sources.
                // Jellyfin scales to the width and preserves aspect ratio.
                maxWidth?.let {
                    add(
                        CodecProfile(
                            conditions =
                                listOf(
                                    // Jellyfin's ProfileConditionType spells it
                                    // LessThanEqual. "LessThanOrEqual" is not a
                                    // member: the server's enum converter throws
                                    // and the whole PlaybackInfo POST returns 400.
                                    ProfileCondition(condition = "LessThanEqual", property = "Width", value = it.toString()),
                                ),
                        ),
                    )
                }
                video.forEach { (codec, support) -> add(capabilityProfile(codec, support)) }
                // Jellyfin sizes transcode audio from the TOTAL cap and
                // channel count, not the tier: a 720 kbps request was given
                // 384 kbps AAC and 336 kbps video (verified on the server).
                // Capping AAC on the low tiers hands the bits back to the
                // picture. Only on the low tiers, because this condition also
                // gates direct play of an AAC source above it.
                lowTierAudioBitrate(maxStreamingBitrate)?.let { audioCap ->
                    add(
                        CodecProfile(
                            type = "VideoAudio",
                            codec = "aac",
                            conditions = listOf(ProfileCondition("LessThanEqual", "AudioBitrate", audioCap.toString())),
                        ),
                    )
                }
            }

        return DeviceProfile(
            maxStreamingBitrate = maxStreamingBitrate,
            codecProfiles = codecProfiles,
            directPlayProfiles =
                listOf(
                    DirectPlayProfile(container = "mp4,m4v,mov", videoCodec = videoCodecs, audioCodec = audio.joinToString(",")),
                    DirectPlayProfile(container = "mkv,webm", videoCodec = videoCodecs, audioCodec = audio.joinToString(",")),
                ),
            transcodingProfiles =
                listOf(
                    TranscodingProfile(
                        container = "ts",
                        videoCodec = "h264",
                        audioCodec = "aac",
                        protocol = "hls",
                        // Stereo on the low tiers: a 5.1 AAC track costs
                        // bandwidth a weak link does not have.
                        maxAudioChannels = if (maxStreamingBitrate <= STEREO_AT_OR_BELOW) "2" else "6",
                    ),
                ),
            subtitleProfiles =
                TEXT_SUBTITLE_FORMATS.map { SubtitleProfile(format = it, method = "External") } +
                    IMAGE_SUBTITLE_FORMATS.map { SubtitleProfile(format = it, method = "Encode") },
        )
    }

    /** The decodable video codecs, in preference order, with their limits. */
    private fun videoCodecs(): LinkedHashMap<String, VideoDecodeSupport> =
        linkedMapOf<String, VideoDecodeSupport>().apply {
            put("h264", codecs.videoSupport(CodecCapabilities.MimeTypes.H264) ?: VideoDecodeSupport.BASELINE)
            codecs.videoSupport(CodecCapabilities.MimeTypes.HEVC)?.let { put("hevc", it) }
            codecs.videoSupport(CodecCapabilities.MimeTypes.VP9)?.let { put("vp9", it) }
            codecs.videoSupport(CodecCapabilities.MimeTypes.AV1)?.let { put("av1", it) }
        }

    /**
     * Audio codecs offered for direct play: AAC always (every Android device
     * decodes it), the rest only with a decoder in MediaCodecList. The same
     * check the Original download gate uses (DeviceMediaCompatibility).
     */
    fun directAudioCodecs(): List<String> =
        buildList {
            add("aac")
            GATED_AUDIO.forEach { codec ->
                val mime = CodecCapabilities.audioMimeFor(codec) ?: return@forEach
                if (codecs.canDecode(mime)) add(codec)
            }
        }

    private fun capabilityProfile(
        codec: String,
        support: VideoDecodeSupport,
    ): CodecProfile =
        CodecProfile(
            codec = codec,
            conditions =
                buildList {
                    support.maxWidth?.let { add(ProfileCondition("LessThanEqual", "Width", it.toString())) }
                    support.maxHeight?.let { add(ProfileCondition("LessThanEqual", "Height", it.toString())) }
                    add(ProfileCondition("LessThanEqual", "VideoBitDepth", if (support.tenBit) "10" else "8"))
                    add(
                        ProfileCondition(
                            "EqualsAny",
                            "VideoRangeType",
                            support.rangeTypes.sortedBy { RANGE_ORDER.indexOf(it) }.joinToString("|"),
                        ),
                    )
                },
        )

    companion object {
        /** At or below this cap the transcode is downmixed to stereo. */
        const val STEREO_AT_OR_BELOW = 4_000_000

        /** The AAC bitrate ceiling for a low tier, or null above them (Jellyfin's own choice stands). */
        fun lowTierAudioBitrate(maxStreamingBitrate: Int): Int? =
            when {
                maxStreamingBitrate <= 1_000_000 -> 96_000
                maxStreamingBitrate <= STEREO_AT_OR_BELOW -> 128_000
                else -> null
            }

        /** Everything but AAC, in the order listed on the wire. */
        private val GATED_AUDIO = listOf("mp3", "ac3", "eac3", "opus", "flac", "vorbis", "dts", "truehd")

        /** Text formats the server converts to VTT for the app to side-load. */
        val TEXT_SUBTITLE_FORMATS = listOf("vtt", "srt", "subrip", "ass", "ssa", "mov_text", "ttml")

        /** Image formats: burned in, and only when the app asks for one by index. */
        val IMAGE_SUBTITLE_FORMATS = listOf("pgssub", "dvdsub", "dvbsub")

        private val RANGE_ORDER =
            listOf(
                VideoRanges.SDR, VideoRanges.HDR10, VideoRanges.HDR10_PLUS, VideoRanges.HLG,
                VideoRanges.DOVI, VideoRanges.DOVI_WITH_SDR, VideoRanges.DOVI_WITH_HDR10, VideoRanges.DOVI_WITH_HDR10_PLUS,
                VideoRanges.DOVI_WITH_HLG, VideoRanges.DOVI_WITH_EL, VideoRanges.DOVI_WITH_EL_HDR10_PLUS,
            )
    }
}

/**
 * The three PlaybackInfo Enable* flags, derived from the two force settings.
 * Ported from the Swift getPlaybackInfo body construction:
 * `effectiveForceDirectPlay = forceDirectPlay && !forceTranscode` — an explicit
 * Quality pick (which sets forceTranscode) overrides the global Force Direct
 * Play setting so the bitrate cap visibly takes effect.
 *
 * Roku lesson (ported): a burned-in subtitle selection has to defeat Force
 * Direct Play, so [forceTranscode] is also set when the user picks an image
 * subtitle (the only kind Android burns in; text tracks are side-loaded VTT).
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
