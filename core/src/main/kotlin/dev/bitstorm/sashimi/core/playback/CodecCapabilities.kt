package dev.bitstorm.sashimi.core.playback

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Display

/**
 * Runtime decoder capability query. Android hardware varies wildly: a device
 * that is offered a stream it cannot decode fails at the decoder (or shows a
 * green picture), while the server would happily have transcoded it. So the
 * DeviceProfile advertises only what this device's decoders report.
 *
 * Injected into [DeviceProfileBuilder] so the profile tests can exercise every
 * capability combination without a real device.
 */
interface CodecCapabilities {
    /** True when the device has a decoder for the given MIME (e.g. [MimeTypes.HEVC]). */
    fun canDecode(mimeType: String): Boolean

    /**
     * What the decoders for a video [mimeType] can handle, or null when there
     * is none. The default (used by fixed test capabilities) reports presence
     * only, with the conservative answers: no size limit known, 8-bit SDR.
     */
    fun videoSupport(mimeType: String): VideoDecodeSupport? = if (canDecode(mimeType)) VideoDecodeSupport.BASELINE else null

    object MimeTypes {
        const val H264 = MediaFormat.MIMETYPE_VIDEO_AVC
        const val HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC
        const val VP9 = MediaFormat.MIMETYPE_VIDEO_VP9
        const val AV1 = MediaFormat.MIMETYPE_VIDEO_AV1
        const val DOLBY_VISION = MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION

        // Audio matters for the same reason video does: ExoPlayer ships no
        // software AC-3/E-AC-3/DTS/TrueHD decoder, so on a device whose
        // MediaCodecList lacks the MIME, a source with that audio fails to play.
        const val AAC = MediaFormat.MIMETYPE_AUDIO_AAC
        const val AC3 = MediaFormat.MIMETYPE_AUDIO_AC3
        const val EAC3 = MediaFormat.MIMETYPE_AUDIO_EAC3
        const val MP3 = MediaFormat.MIMETYPE_AUDIO_MPEG
        const val OPUS = MediaFormat.MIMETYPE_AUDIO_OPUS
        const val FLAC = MediaFormat.MIMETYPE_AUDIO_FLAC
        const val VORBIS = MediaFormat.MIMETYPE_AUDIO_VORBIS
        const val DTS = "audio/vnd.dts"
        const val DTS_HD = "audio/vnd.dts.hd"
        const val TRUEHD = "audio/true-hd"
    }

    companion object {
        /** Jellyfin codec token to the MIME type used to query the decoder. */
        fun audioMimeFor(codec: String): String? =
            when (codec) {
                "aac" -> MimeTypes.AAC
                "ac3" -> MimeTypes.AC3
                "eac3" -> MimeTypes.EAC3
                "mp3" -> MimeTypes.MP3
                "opus" -> MimeTypes.OPUS
                "flac" -> MimeTypes.FLAC
                "vorbis" -> MimeTypes.VORBIS
                "dts" -> MimeTypes.DTS
                "truehd" -> MimeTypes.TRUEHD
                else -> null
            }
    }
}

/**
 * One video codec's decode limits.
 *
 * @param maxWidth / [maxHeight] the largest frame any (preferably hardware)
 *   decoder for the codec accepts; null when unknown.
 * @param tenBit a 10-bit profile is supported (HEVC Main10, VP9 Profile 2, AV1 Main10).
 * @param rangeTypes the Jellyfin `VideoRangeType` values that can be both
 *   decoded and shown: always SDR; HDR10/HLG/HDR10+ only when the decoder has
 *   the HDR profile AND the display reports that HDR type; DOVI only with a
 *   Dolby Vision decoder and display.
 */
data class VideoDecodeSupport(
    val maxWidth: Int?,
    val maxHeight: Int?,
    val tenBit: Boolean,
    val rangeTypes: Set<String>,
) {
    companion object {
        val BASELINE = VideoDecodeSupport(maxWidth = null, maxHeight = null, tenBit = false, rangeTypes = setOf(VideoRanges.SDR))
    }
}

/** Jellyfin `VideoRangeType` names (Jellyfin.Data/Enums/VideoRangeType.cs). */
object VideoRanges {
    const val SDR = "SDR"
    const val HDR10 = "HDR10"
    const val HDR10_PLUS = "HDR10Plus"
    const val HLG = "HLG"
    const val DOVI = "DOVI"
    const val DOVI_WITH_HDR10 = "DOVIWithHDR10"
    const val DOVI_WITH_HLG = "DOVIWithHLG"
    const val DOVI_WITH_SDR = "DOVIWithSDR"
    const val DOVI_WITH_HDR10_PLUS = "DOVIWithHDR10Plus"
    const val DOVI_WITH_EL = "DOVIWithEL"
    const val DOVI_WITH_EL_HDR10_PLUS = "DOVIWithELHDR10Plus"

    /**
     * The range types playable given what the decoder and display support.
     *
     * Dolby Vision with a cross-compatible base layer (profile 8.x) plays as
     * its base layer: Media3 falls back to the HEVC/AV1 decoder for it. So
     * DOVIWithSDR rides on SDR, DOVIWithHDR10 on HDR10, and so on. Bare DV
     * (profile 5, no fallback) and profile 7 (enhancement layer) need a real
     * Dolby Vision decoder.
     */
    fun playable(
        hdr10: Boolean,
        hdr10Plus: Boolean,
        hlg: Boolean,
        dolbyVision: Boolean,
    ): Set<String> =
        buildSet {
            add(SDR)
            add(DOVI_WITH_SDR)
            if (hdr10) {
                add(HDR10)
                add(DOVI_WITH_HDR10)
            }
            if (hdr10Plus) {
                add(HDR10_PLUS)
                add(DOVI_WITH_HDR10_PLUS)
            }
            if (hlg) {
                add(HLG)
                add(DOVI_WITH_HLG)
            }
            if (dolbyVision) {
                add(DOVI)
                add(DOVI_WITH_EL)
                add(DOVI_WITH_EL_HDR10_PLUS)
            }
        }
}

/** Fixed capability set: used by tests and as a conservative fallback. */
class FixedCodecCapabilities(
    private val supported: Set<String>,
    private val video: Map<String, VideoDecodeSupport> = emptyMap(),
) : CodecCapabilities {
    override fun canDecode(mimeType: String): Boolean = mimeType in supported || mimeType in video

    override fun videoSupport(mimeType: String): VideoDecodeSupport? = video[mimeType] ?: super.videoSupport(mimeType)
}

/**
 * Real device capabilities via [MediaCodecList.REGULAR_CODECS] and, when a
 * [context] is given, the default display's HDR capabilities. Enumerated once
 * and cached.
 *
 * Where the capability API is known to be unreliable, this errs towards
 * "transcode": a size limit is taken from hardware decoders when any exist
 * (a software decoder will claim 4K and then drop every frame), HDR needs the
 * display to say so (a phone without an HDR panel would show HDR10 washed
 * out), and 10-bit needs an explicit 10-bit profile in the decoder's list.
 */
class AndroidCodecCapabilities(
    private val context: Context? = null,
) : CodecCapabilities {
    private val decoders: List<MediaCodecInfo> by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
    }

    private val decodableMimeTypes: Set<String> by lazy {
        decoders.flatMap { info -> info.supportedTypes.map { it.lowercase() } }.toSet()
    }

    private val displayHdrTypes: Set<Int> by lazy { readDisplayHdrTypes() }

    private val videoCache = HashMap<String, VideoDecodeSupport?>()

    override fun canDecode(mimeType: String): Boolean = mimeType.lowercase() in decodableMimeTypes

    @Synchronized
    override fun videoSupport(mimeType: String): VideoDecodeSupport? = videoCache.getOrPut(mimeType) { computeVideoSupport(mimeType) }

    private fun computeVideoSupport(mimeType: String): VideoDecodeSupport? {
        val candidates = decoders.filter { info -> info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } }
        if (candidates.isEmpty()) return null
        val hardware =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) candidates.filter { it.isHardwareAccelerated } else emptyList()
        val preferred = hardware.ifEmpty { candidates }
        val caps = preferred.mapNotNull { info -> runCatching { info.getCapabilitiesForType(mimeType) }.getOrNull() }
        val maxWidth = caps.mapNotNull { runCatching { it.videoCapabilities?.supportedWidths?.upper }.getOrNull() }.maxOrNull()
        val maxHeight = caps.mapNotNull { runCatching { it.videoCapabilities?.supportedHeights?.upper }.getOrNull() }.maxOrNull()
        val profiles = caps.flatMap { c -> c.profileLevels.orEmpty().map { it.profile } }.toSet()

        val tenBitProfiles = TEN_BIT_PROFILES[mimeType].orEmpty()
        val hdr10Profiles = HDR10_PROFILES[mimeType].orEmpty()
        val hdr10PlusProfiles = HDR10_PLUS_PROFILES[mimeType].orEmpty()
        val tenBit = profiles.any { it in tenBitProfiles }
        val decoderHdr10 = profiles.any { it in hdr10Profiles }
        val decoderHdr10Plus = profiles.any { it in hdr10PlusProfiles }
        // HLG is carried in a 10-bit stream with no special profile; the
        // decoder needs only 10-bit, the display needs to show HLG.
        val ranges =
            VideoRanges.playable(
                hdr10 = decoderHdr10 && HDR_TYPE_HDR10 in displayHdrTypes,
                hdr10Plus = decoderHdr10Plus && HDR_TYPE_HDR10_PLUS in displayHdrTypes,
                hlg = tenBit && HDR_TYPE_HLG in displayHdrTypes,
                dolbyVision = canDecode(CodecCapabilities.MimeTypes.DOLBY_VISION) && HDR_TYPE_DOLBY_VISION in displayHdrTypes,
            )
        return VideoDecodeSupport(maxWidth = maxWidth, maxHeight = maxHeight, tenBit = tenBit, rangeTypes = ranges)
    }

    @Suppress("DEPRECATION")
    private fun readDisplayHdrTypes(): Set<Int> {
        val ctx = context ?: return emptySet()
        return runCatching {
            val display =
                (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
                    ?: return emptySet()
            display.hdrCapabilities?.supportedHdrTypes?.toSet().orEmpty()
        }.getOrDefault(emptySet())
    }

    private companion object {
        // Display.HdrCapabilities constants, inlined so they read on API 26.
        const val HDR_TYPE_DOLBY_VISION = 1
        const val HDR_TYPE_HDR10 = 2
        const val HDR_TYPE_HLG = 3
        const val HDR_TYPE_HDR10_PLUS = 4

        val TEN_BIT_PROFILES =
            mapOf(
                CodecCapabilities.MimeTypes.H264 to setOf(CodecProfileLevel.AVCProfileHigh10),
                CodecCapabilities.MimeTypes.HEVC to
                    setOf(
                        CodecProfileLevel.HEVCProfileMain10,
                        CodecProfileLevel.HEVCProfileMain10HDR10,
                        CodecProfileLevel.HEVCProfileMain10HDR10Plus,
                    ),
                CodecCapabilities.MimeTypes.VP9 to
                    setOf(
                        CodecProfileLevel.VP9Profile2,
                        CodecProfileLevel.VP9Profile2HDR,
                        CodecProfileLevel.VP9Profile2HDR10Plus,
                    ),
                CodecCapabilities.MimeTypes.AV1 to
                    setOf(
                        CodecProfileLevel.AV1ProfileMain10,
                        CodecProfileLevel.AV1ProfileMain10HDR10,
                        CodecProfileLevel.AV1ProfileMain10HDR10Plus,
                    ),
            )
        val HDR10_PROFILES =
            mapOf(
                CodecCapabilities.MimeTypes.HEVC to
                    setOf(CodecProfileLevel.HEVCProfileMain10HDR10, CodecProfileLevel.HEVCProfileMain10HDR10Plus),
                CodecCapabilities.MimeTypes.VP9 to
                    setOf(CodecProfileLevel.VP9Profile2HDR, CodecProfileLevel.VP9Profile2HDR10Plus),
                CodecCapabilities.MimeTypes.AV1 to
                    setOf(CodecProfileLevel.AV1ProfileMain10HDR10, CodecProfileLevel.AV1ProfileMain10HDR10Plus),
            )
        val HDR10_PLUS_PROFILES =
            mapOf(
                CodecCapabilities.MimeTypes.HEVC to setOf(CodecProfileLevel.HEVCProfileMain10HDR10Plus),
                CodecCapabilities.MimeTypes.VP9 to setOf(CodecProfileLevel.VP9Profile2HDR10Plus),
                CodecCapabilities.MimeTypes.AV1 to setOf(CodecProfileLevel.AV1ProfileMain10HDR10Plus),
            )
    }
}
