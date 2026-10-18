package dev.bitstorm.sashimi.core.admin

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.MediaStream
import java.util.Locale

/**
 * The three play-mode badges. The labels and [PlayModes.of]'s truth table are a
 * cross-platform contract (plans/sashimi-android/2026-10-18-admin-dashboard-plan.md):
 * Apple and Roku show the same badge for the same session.
 */
enum class PlayMode(val label: String) {
    DIRECT_PLAY("Direct play"),
    CONVERTING_AUDIO("Converting audio"),
    CONVERTING_VIDEO("Converting video"),
}

object PlayModes {
    private const val BURN_IN = "SubtitleCodecNotSupported"

    /**
     * Truth table, in order: no TranscodingInfo → direct; subtitle burn-in →
     * video; both direct → direct (a remux); video direct → audio; else video.
     * A missing flag counts as converted: the badge never claims direct play
     * the server did not report.
     */
    fun of(info: TranscodingInfoDto?): PlayMode {
        if (info == null) return PlayMode.DIRECT_PLAY
        if (BURN_IN in info.transcodeReasons.orEmpty()) return PlayMode.CONVERTING_VIDEO
        val video = info.isVideoDirect == true
        val audio = info.isAudioDirect == true
        return when {
            video && audio -> PlayMode.DIRECT_PLAY
            video -> PlayMode.CONVERTING_AUDIO
            else -> PlayMode.CONVERTING_VIDEO
        }
    }

    /** Both streams copied into a new container: shown as Direct play with "Remux". */
    fun isRemux(info: TranscodingInfoDto?): Boolean = info != null && of(info) == PlayMode.DIRECT_PLAY

    /** "4K Dolby Vision", "1080p": the source's tier and HDR range (SDR is not named). */
    fun sourceFormat(item: BaseItemDto?): String? {
        val video = videoStream(item) ?: return null
        val tier = tier(video.width, video.height) ?: return null
        return listOfNotNull(tier, range(video.videoRangeType)).joinToString(" ")
    }

    /** The one-line detail: source format, reasons in plain words, then what changed. */
    fun detail(session: SessionInfoDto): String {
        val item = session.nowPlayingItem
        val info = session.transcodingInfo
        val parts = mutableListOf<String>()
        sourceFormat(item)?.let(parts::add)
        if (info == null) return parts.joinToString(" · ")
        if (isRemux(info)) {
            parts += "Remux"
            return parts.joinToString(" · ")
        }
        TranscodeReasonWords.line(info.transcodeReasons)?.let(parts::add)
        if (of(info) == PlayMode.CONVERTING_VIDEO) videoChange(item, info)?.let(parts::add)
        if (info.isAudioDirect != true) audioChange(session, info)?.let(parts::add)
        return parts.joinToString(" · ")
    }

    internal fun videoStream(item: BaseItemDto?): MediaStream? = item?.mediaStreams?.firstOrNull { it.type == "Video" }

    /** The audio stream playing: the reported index, else the default, else the first. */
    internal fun audioStream(session: SessionInfoDto): MediaStream? {
        val audio = session.nowPlayingItem?.mediaStreams.orEmpty().filter { it.type == "Audio" }
        val index = session.playState?.audioStreamIndex
        return audio.firstOrNull { index != null && it.index == index }
            ?: audio.firstOrNull { it.isDefault == true }
            ?: audio.firstOrNull()
    }

    private fun videoChange(
        item: BaseItemDto?,
        info: TranscodingInfoDto,
    ): String? {
        val source = videoStream(item)
        val from = source?.let { tier(it.width, it.height) }
        val to = tier(info.width, info.height)
        if (from != null && to != null && from != to) return "$from → $to"
        val fromCodec = source?.codec?.uppercase(Locale.US)
        val toCodec = info.videoCodec?.uppercase(Locale.US)
        return if (fromCodec != null && toCodec != null && fromCodec != toCodec) "$fromCodec → $toCodec" else null
    }

    private fun audioChange(
        session: SessionInfoDto,
        info: TranscodingInfoDto,
    ): String? {
        val from = audioStream(session)?.codec?.uppercase(Locale.US) ?: return null
        val to = info.audioCodec?.uppercase(Locale.US) ?: return null
        return if (from != to) "$from → $to" else null
    }

    private fun tier(
        width: Int?,
        height: Int?,
    ): String? {
        val w = width ?: 0
        val h = height ?: 0
        return when {
            w == 0 && h == 0 -> null
            w >= 3200 || h >= 2000 -> "4K"
            w >= 1800 || h >= 1000 -> "1080p"
            w >= 1200 || h >= 700 -> "720p"
            else -> "${h}p"
        }
    }

    private fun range(type: String?): String? =
        when {
            type == null -> null
            type.startsWith("DOVI") -> "Dolby Vision"
            type == "HDR10Plus" -> "HDR10+"
            type == "HDR10" -> "HDR10"
            type == "HLG" -> "HLG"
            else -> null
        }
}
