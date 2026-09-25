package dev.bitstorm.sashimi.core.time

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.Locale

/**
 * The one wall-clock formatter for every user-visible time of day (detail
 * "Ends at", and any future player clock / "Finishes at" / guide times).
 * Honours the 24-Hour Time setting ([dev.bitstorm.sashimi.core.settings.AppSettings.use24HourTime]),
 * matching the other clients exactly: 12h -> "9:45 PM", 24h -> "21:45"
 * (zero-padded hour, "HH:mm", like sashimi-apple and sashimi-roku's TimeFormat).
 */
object ClockFormat {
    private const val TICKS_PER_SECOND = 10_000_000L

    private val TWELVE_HOUR = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val TWENTY_FOUR_HOUR = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    /** Time of day for [time] (anything carrying hour + minute). */
    fun time(
        time: TemporalAccessor,
        use24Hour: Boolean,
    ): String = (if (use24Hour) TWENTY_FOUR_HOUR else TWELVE_HOUR).format(time)

    /**
     * Wall-clock time [durationTicks] after [now], or null for a non-positive
     * duration. Used for "Ends at" / "Finishes at".
     */
    fun endTime(
        durationTicks: Long,
        use24Hour: Boolean,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        if (durationTicks <= 0) return null
        val end = now.plusSeconds(durationTicks / TICKS_PER_SECOND).atZone(zone)
        return time(end, use24Hour)
    }
}
