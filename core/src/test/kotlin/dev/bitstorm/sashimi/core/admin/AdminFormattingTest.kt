package dev.bitstorm.sashimi.core.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AdminFormattingTest {
    private val now = Instant.parse("2026-10-18T20:00:00Z")

    @Test
    fun `relative times are short`() {
        assertEquals("now", RelativeTime.short("2026-10-18T19:59:01Z", now))
        assertEquals("5 m", RelativeTime.short("2026-10-18T19:55:00Z", now))
        assertEquals("2 h", RelativeTime.short("2026-10-18T17:30:00Z", now))
        assertEquals("3 d", RelativeTime.short("2026-10-15T19:00:00Z", now))
        // Jellyfin sends 7 fractional digits.
        assertEquals("1 h", RelativeTime.short("2026-10-18T18:56:16.375795Z", now))
        assertEquals("", RelativeTime.short(null, now))
        assertEquals("", RelativeTime.short("garbage", now))
        assertEquals("now", RelativeTime.short("2026-10-18T20:00:30Z", now))
    }

    @Test
    fun `activity rows are redacted and failures flagged`() {
        val rows =
            ActivityRows.of(
                listOf(
                    ActivityLogEntryDto(
                        id = 1,
                        name = "Failed login attempt from mom",
                        type = "AuthenticationFailed",
                        severity = "Error",
                        date = "2026-10-18T19:00:00Z",
                        shortOverview = "IP address: 208.1.2.3",
                    ),
                    ActivityLogEntryDto(
                        id = 2,
                        name = "ozzy is online from 72.1.2.3",
                        type = "SessionStarted",
                        severity = "Information",
                        date = "2026-10-18T19:59:59Z",
                    ),
                ),
                now,
            )
        assertEquals("IP address: 208.x.x.x", rows[0].detail)
        assertTrue(rows[0].isFailure)
        assertEquals("ozzy is online from 72.x.x.x", rows[1].text)
        assertFalse(rows[1].isFailure)
        assertEquals(listOf("1 h", "now"), rows.map { it.`when` })
    }

    @Test
    fun `hardware transcoding gets a readable name`() {
        assertEquals("Intel Quick Sync", HardwareTranscoding.label("qsv"))
        assertEquals("NVIDIA NVENC", HardwareTranscoding.label("nvenc"))
        assertEquals("VA-API", HardwareTranscoding.label("vaapi"))
        assertEquals("Off", HardwareTranscoding.label("none"))
        assertEquals("Off", HardwareTranscoding.label(null))
        assertEquals("Rkmpp", HardwareTranscoding.label("rkmpp"))
        assertTrue(HardwareTranscoding.isOn("qsv"))
        assertFalse(HardwareTranscoding.isOn("none"))
        assertFalse(HardwareTranscoding.isOn(""))
    }

    @Test
    fun `library scan result reads from the RefreshLibrary task`() {
        val tasks =
            listOf(
                ScheduledTaskDto(name = "Other", key = "X", lastExecutionResult = TaskResultDto("Failed", "2026-10-18T19:00:00Z")),
                ScheduledTaskDto(
                    name = "Scan Media Library",
                    key = "RefreshLibrary",
                    lastExecutionResult = TaskResultDto("Completed", "2026-10-18T19:00:00Z"),
                ),
            )
        assertEquals("Completed · 1 h ago", LibraryScan.summary(tasks, now))
        assertEquals("Never run", LibraryScan.summary(listOf(ScheduledTaskDto(key = "RefreshLibrary")), now))
        assertEquals(null, LibraryScan.summary(emptyList(), now))
    }
}
