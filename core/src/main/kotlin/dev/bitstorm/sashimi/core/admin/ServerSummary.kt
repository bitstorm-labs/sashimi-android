package dev.bitstorm.sashimi.core.admin

import java.util.Locale
import kotlin.math.roundToLong

/** The header counts: streams, conversions and the total outbound bitrate. */
data class Summary(
    val streaming: Int,
    val converting: Int,
    val totalBitrate: Long,
)

object ServerSummary {
    /** Sessions actually playing something; idle clients are not streams. */
    fun playing(sessions: List<SessionInfoDto>): List<SessionInfoDto> = sessions.filter { it.nowPlayingItem != null }

    /**
     * What one stream sends: the transcode's bitrate when the server reports
     * one, else the source video plus the audio track playing. Null when unknown.
     */
    fun bitrate(session: SessionInfoDto): Long? {
        session.transcodingInfo?.bitrate?.let { return it }
        val video = PlayModes.videoStream(session.nowPlayingItem)?.bitRate ?: return null
        val audio = PlayModes.audioStream(session)?.bitRate ?: 0
        return video.toLong() + audio
    }

    fun of(sessions: List<SessionInfoDto>): Summary {
        val live = playing(sessions)
        return Summary(
            streaming = live.size,
            converting = live.count { PlayModes.of(it.transcodingInfo) != PlayMode.DIRECT_PLAY },
            totalBitrate = live.sumOf { bitrate(it) ?: 0L },
        )
    }

    /** "3 streaming · 1 converting · 142 Mbps out", or "Nobody's watching". */
    fun headline(summary: Summary): String {
        if (summary.streaming == 0) return "Nobody's watching"
        val parts = mutableListOf("${summary.streaming} streaming", "${summary.converting} converting")
        if (summary.totalBitrate > 0) parts += "${mbps(summary.totalBitrate)} out"
        return parts.joinToString(" · ")
    }

    /** Whole Mbps from 10 up, one decimal below ("8.4 Mbps", "8 Mbps"). */
    fun mbps(bitsPerSecond: Long): String {
        val value = bitsPerSecond / 1_000_000.0
        if (value >= 10) return "${value.roundToLong()} Mbps"
        val tenths = (value * 10).roundToLong()
        val text = if (tenths % 10 == 0L) "${tenths / 10}" else String.format(Locale.US, "%.1f", tenths / 10.0)
        return "$text Mbps"
    }
}
