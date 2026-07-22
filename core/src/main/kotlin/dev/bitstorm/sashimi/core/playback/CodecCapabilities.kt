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
