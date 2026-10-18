package dev.bitstorm.sashimi.core.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AttentionTest {
    private val now = Instant.parse("2026-10-18T20:00:00Z")

    private fun task(
        name: String,
        status: String?,
        end: String? = "2026-10-18T19:00:00Z",
        key: String = name,
    ) = ScheduledTaskDto(id = name, name = name, key = key, lastExecutionResult = status?.let { TaskResultDto(it, end) })

    private fun failedLogin(
        user: String,
        at: String,
        ip: String = "208.1.2.3",
    ) = ActivityLogEntryDto(
        name = "Failed login attempt from $user",
        type = "AuthenticationFailed",
        severity = "Error",
        date = at,
        shortOverview = "IP address: $ip",
    )

    @Test
    fun `failed and aborted tasks are red, completed and cancelled are not`() {
        val items =
            Attention.items(
                tasks =
                    listOf(
                        task("Scan Media Library", "Failed"),
                        task("Trickplay", "Aborted"),
                        task("Ok", "Completed"),
                        task("Stopped", "Cancelled"),
                        task("Never", null),
                    ),
                activity = emptyList(),
                storage = emptyList(),
                now = now,
            )
        assertEquals(listOf("Scan Media Library failed", "Trickplay failed"), items.map { it.text })
        assertTrue(items.all { it.severity == AttentionSeverity.ERROR })
        assertEquals("1 h", items[0].`when`)
    }

    @Test
    fun `failed sign-ins in the last day are grouped per user, newest time shown`() {
        val items =
            Attention.items(
                tasks = emptyList(),
                activity =
                    listOf(
                        failedLogin("mom", "2026-10-18T18:00:00Z"),
                        failedLogin("mom", "2026-10-18T17:00:00Z"),
                        failedLogin("admin", "2026-10-18T19:59:30Z"),
                        failedLogin("old", "2026-10-16T19:00:00Z"),
                        ActivityLogEntryDto(
                            name = "mom is online",
                            type = "SessionStarted",
                            severity = "Information",
                            date = "2026-10-18T19:00:00Z",
                        ),
                    ),
                storage = emptyList(),
                now = now,
            )
        assertEquals(listOf("2 failed sign-ins for “mom”", "Failed sign-in for “admin”"), items.map { it.text })
        assertEquals(listOf("2 h", "now"), items.map { it.`when` })
    }

    @Test
    fun `storage over the threshold is a warning`() {
        val items =
            Attention.items(
                tasks = emptyList(),
                activity = emptyList(),
                storage = listOf(StorageRow("Movies, TV", 905, 1000), StorageRow("Transcode & cache", 640, 1000)),
                now = now,
            )
        assertEquals(listOf("Movies, TV 91% full"), items.map { it.text })
        assertEquals(AttentionSeverity.WARNING, items.single().severity)
    }

    @Test
    fun `errors come before warnings`() {
        val items =
            Attention.items(
                tasks = listOf(task("Scan Media Library", "Failed")),
                activity = emptyList(),
                storage = listOf(StorageRow("Movies", 99, 100)),
                now = now,
            )
        assertEquals(listOf(AttentionSeverity.ERROR, AttentionSeverity.WARNING), items.map { it.severity })
    }
}
