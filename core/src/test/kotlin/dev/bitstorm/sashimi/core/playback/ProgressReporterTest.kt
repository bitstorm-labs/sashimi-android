package dev.bitstorm.sashimi.core.playback

import dev.bitstorm.sashimi.core.network.JellyfinClient
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressReporterTest {
    // An unconfigured client is fine: the timing methods under test never hit it.
    private val client = JellyfinClient(deviceId = "test-device")
    private var now = 1_000L

    private fun reporter(resumeTicks: Long) =
        ProgressReporter(
            client = client,
            itemId = "item1",
            playSessionId = "ps1",
            reportedPlayMethod = "DirectStream",
            resumePositionTicks = resumeTicks,
            clock = { now },
        )

    @Test
    fun `quick exit within 10s preserves original resume position`() {
        val resume = 300_000_000L // 30s in ticks
        val r = reporter(resume)
        r.begin() // startClockMs = 1000
        now = 1_000 + 9_999 // 9.999s elapsed
        assertEquals(resume, r.stopPositionTicks(currentPositionTicks = 123L))
    }

    @Test
    fun `at 10s the current position is reported`() {
        val resume = 300_000_000L
        val r = reporter(resume)
        r.begin()
        now = 1_000 + 10_000 // exactly 10s → no longer quick-exit
        assertEquals(123L, r.stopPositionTicks(currentPositionTicks = 123L))
    }

    @Test
    fun `no resume position means current is always reported`() {
        val r = reporter(resumeTicks = 0L)
        r.begin()
        now = 1_000 + 1 // even a near-instant exit
        assertEquals(456L, r.stopPositionTicks(currentPositionTicks = 456L))
    }
}
