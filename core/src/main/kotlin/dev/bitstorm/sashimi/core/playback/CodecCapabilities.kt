package dev.bitstorm.sashimi.core.playback

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * Runtime video-decoder capability query. The Swift DeviceProfile always
 * advertised h264 + hevc, but Android hardware varies wildly — a device that
 * claims a codec in its profile but can't decode it produces a black screen, so
 * the DeviceProfile gates hevc/av1/vp9 on an actual [MediaCodecList] lookup
 * before offering them for direct play.
 *
 * Injected into [DeviceProfileBuilder] so the source-selection tests can
 * exercise every codec combination without a real device.
 */
interface CodecCapabilities {
    /** True when the device has a decoder for the given MIME (e.g. [MimeTypes.HEVC]). */
    fun canDecode(mimeType: String): Boolean

    object MimeTypes {
        const val H264 = MediaFormat.MIMETYPE_VIDEO_AVC
        const val HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC
        const val VP9 = MediaFormat.MIMETYPE_VIDEO_VP9
        const val AV1 = MediaFormat.MIMETYPE_VIDEO_AV1

        // Audio matters for the same reason video does, and for downloads it
        // matters more: ExoPlayer ships no software AC-3 decoder, so on a device
        // whose MediaCodecList lacks audio/ac3 an mp4/h264/ac3 source was
        // offered as "Original", downloaded in full, and then would not play --
        // with no fallback, because the file is already on disk.
        const val AAC = MediaFormat.MIMETYPE_AUDIO_AAC
        const val AC3 = MediaFormat.MIMETYPE_AUDIO_AC3
        const val EAC3 = MediaFormat.MIMETYPE_AUDIO_EAC3
    }

    companion object {
        /** Jellyfin codec token to the MIME type used to query the decoder. */
        fun audioMimeFor(codec: String): String? =
            when (codec) {
                "aac" -> MimeTypes.AAC
                "ac3" -> MimeTypes.AC3
                "eac3" -> MimeTypes.EAC3
                else -> null
            }
    }
}

/** Fixed capability set — used by tests and as a conservative fallback. */
class FixedCodecCapabilities(
    private val supported: Set<String>,
) : CodecCapabilities {
    override fun canDecode(mimeType: String): Boolean = mimeType in supported
}

/**
 * Real device capabilities via [MediaCodecList.REGULAR_CODECS]. Enumerates
 * hardware + software decoders once and caches the decodable MIME set.
 */
class AndroidCodecCapabilities : CodecCapabilities {
    private val decodableMimeTypes: Set<String> by lazy {
        buildSet {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info: MediaCodecInfo in list.codecInfos) {
                if (info.isEncoder) continue
                for (type in info.supportedTypes) add(type.lowercase())
            }
        }
    }

    override fun canDecode(mimeType: String): Boolean = mimeType.lowercase() in decodableMimeTypes
}
