package dev.bitstorm.sashimi.ui.util

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Formatting helpers ported from the Swift detail/card views (PhoneDetailView,
 * MobileContinueWatchingCard). Kept UI-agnostic so they read the same across
 * every surface.
 */
object Formatting {
    private const val TICKS_PER_SECOND = 10_000_000L

    /** "1h 23m" / "45 min". Port of formatRuntime. */
    fun runtime(ticks: Long): String {
        val seconds = ticks / TICKS_PER_SECOND
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) "${hours}h ${minutes}m" else "$minutes min"
    }

    /** Short "42 min" runtime used in episode rows. */
    fun runtimeMinutes(ticks: Long): String = "${ticks / TICKS_PER_SECOND / 60} min"

    /** "1h 5m left" / "23m left" from total + played ticks. Port of remainingTimeText. */
    fun remaining(
        totalTicks: Long,
        playedTicks: Long,
    ): String {
        val seconds = (totalTicks - playedTicks) / TICKS_PER_SECOND
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) "${hours}h ${minutes}m left" else "${minutes}m left"
    }

    /** "Ends at 9:41 PM" from now + runtime. Port of endsAtText (accent-colored in UI). */
    fun endsAt(ticks: Long): String? {
        if (ticks <= 0) return null
        val end = Instant.now().plusSeconds(ticks / TICKS_PER_SECOND).atZone(ZoneId.systemDefault())
        return "Ends at ${end.format(TIME_FORMAT)}"
    }

    /** "November 8, 2024" (long premiere date). Port of premiereDateLongText. */
    fun premiereDateLong(raw: String?): String? = parse(raw)?.format(LONG_DATE_FORMAT)

    /** "Nov 8, 2024" (short air date for episode rows). Port of shortAirDateText. */
    fun shortAirDate(raw: String?): String? = parse(raw)?.format(SHORT_DATE_FORMAT)

    /** "M-d-yyyy" for YouTube episode captions. Port of MobileContinueWatchingCard.formatDate. */
    fun numericDate(raw: String?): String? = parse(raw)?.format(NUMERIC_DATE_FORMAT)

    /** Codec wordmark used on media badges. Port of PhoneDetailView.formatCodec. */
    fun codec(codec: String): String =
        when (codec.uppercase(Locale.US)) {
            "HEVC", "H265" -> "HEVC"
            "H264", "AVC" -> "H.264"
            "AV1" -> "AV1"
            "AAC" -> "AAC"
            "AC3" -> "Dolby Digital"
            "EAC3" -> "Dolby Digital+"
            "TRUEHD" -> "Dolby TrueHD"
            "DTS" -> "DTS"
            "FLAC" -> "FLAC"
            else -> codec.uppercase(Locale.US)
        }

    /** "Stereo" / "5.1" / "7.1". Port of formatChannels. */
    fun channels(channels: Int): String =
        when (channels) {
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> "${channels}ch"
        }

    /**
     * Strips http(s) URLs from overview text (YouTube descriptions are full of
     * them). Port of PhoneDetailView.stripURLs.
     */
    fun stripUrls(text: String): String = URL_REGEX.replace(text, "").replace("  ", " ").trim()

    private fun parse(raw: String?): java.time.ZonedDateTime? {
        if (raw.isNullOrEmpty()) return null
        return runCatching { OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.systemDefault()) }
            .recoverCatching { Instant.parse(raw).atZone(ZoneId.systemDefault()) }
            .getOrNull()
    }

    private val URL_REGEX = Regex("https?://\\S+")
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val LONG_DATE_FORMAT = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US)
    private val SHORT_DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    private val NUMERIC_DATE_FORMAT = DateTimeFormatter.ofPattern("M-d-yyyy", Locale.US)
}
