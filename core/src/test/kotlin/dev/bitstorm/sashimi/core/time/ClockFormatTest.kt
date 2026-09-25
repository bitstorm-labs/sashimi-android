package dev.bitstorm.sashimi.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

class ClockFormatTest {
    @Test
    fun `12-hour is the default style with AM PM and no leading zero`() {
        assertEquals("9:45 PM", ClockFormat.time(LocalTime.of(21, 45), use24Hour = false))
        assertEquals("9:05 AM", ClockFormat.time(LocalTime.of(9, 5), use24Hour = false))
    }

    @Test
    fun `12-hour renders midnight and noon as 12`() {
        assertEquals("12:00 AM", ClockFormat.time(LocalTime.of(0, 0), use24Hour = false))
        assertEquals("12:30 PM", ClockFormat.time(LocalTime.of(12, 30), use24Hour = false))
    }

    @Test
    fun `24-hour zero-pads and has no AM PM`() {
        assertEquals("21:45", ClockFormat.time(LocalTime.of(21, 45), use24Hour = true))
        assertEquals("09:05", ClockFormat.time(LocalTime.of(9, 5), use24Hour = true))
        assertEquals("00:00", ClockFormat.time(LocalTime.of(0, 0), use24Hour = true))
    }

    @Test
    fun `endTime adds the runtime to now in both styles`() {
        val now = Instant.parse("2026-09-25T20:15:00Z")
        val ninetyMinutes = 90L * 60 * 10_000_000L
        assertEquals("9:45 PM", ClockFormat.endTime(ninetyMinutes, false, now, ZoneOffset.UTC))
        assertEquals("21:45", ClockFormat.endTime(ninetyMinutes, true, now, ZoneOffset.UTC))
    }

    @Test
    fun `endTime crosses midnight`() {
        val now = Instant.parse("2026-09-25T23:30:00Z")
        val hour = 60L * 60 * 10_000_000L
        assertEquals("12:30 AM", ClockFormat.endTime(hour, false, now, ZoneOffset.UTC))
        assertEquals("00:30", ClockFormat.endTime(hour, true, now, ZoneOffset.UTC))
    }

    @Test
    fun `endTime is null for a missing runtime`() {
        assertNull(ClockFormat.endTime(0, false))
        assertNull(ClockFormat.endTime(-5, true))
    }
}
