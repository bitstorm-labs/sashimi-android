package dev.bitstorm.sashimi.core.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BandwidthProbeMathTest {
    @Test
    fun `steady-state window is used when long enough`() {
        // 4 s after warm-up, 2 MB: 4 Mbps, regardless of a fast burst before it.
        assertEquals(
            4_000_000,
            BandwidthProbeMath.bitsPerSecond(totalBytes = 9_000_000, totalMs = 5_000, steadyBytes = 2_000_000, steadyMs = 4_000),
        )
    }

    @Test
    fun `a slow link that never reaches the byte cap still yields a partial-result estimate`() {
        // 5 s probe on a ~1.5 Mbps link: about 940 KB received, about 750 KB after warm-up.
        assertEquals(
            1_500_000,
            BandwidthProbeMath.bitsPerSecond(totalBytes = 937_500, totalMs = 5_000, steadyBytes = 750_000, steadyMs = 4_000),
        )
    }

    @Test
    fun `a fast link that finishes inside the warm-up falls back to the overall rate`() {
        assertEquals(134_217_728, BandwidthProbeMath.bitsPerSecond(totalBytes = 16_777_216, totalMs = 1_000, steadyBytes = 0, steadyMs = 0))
    }

    @Test
    fun `nothing received is no measurement`() {
        assertNull(BandwidthProbeMath.bitsPerSecond(0, 0, 0, 0))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BandwidthMonitorTest {
    private var now = 0L

    @Test
    fun `a measurement is remembered per server and expires`() =
        runTest {
            val monitor = BandwidthMonitor(clock = { now }, maxAgeMs = 1_000, scope = this)
            monitor.record("http://a", 5_000_000)
            assertEquals(5_000_000, monitor.measured("http://a"))
            assertNull(monitor.measured("http://b"))
            now = 2_000
            assertNull(monitor.measured("http://a"))
        }

    @Test
    fun `a step down limits what Auto remembers`() =
        runTest {
            val monitor = BandwidthMonitor(clock = { now }, scope = this)
            monitor.record("http://a", 50_000_000)
            monitor.limitTo("http://a", 4_000_000)
            // Exactly what Auto needs to measure to choose that tier again: 4 Mbps / 0.85.
            assertEquals(4_000_000, AutoBitrate.cap(monitor.measured("http://a"), isLocalServer = false))
            // Never raises a lower memory.
            monitor.limitTo("http://a", 20_000_000)
            assertEquals(4_000_000, AutoBitrate.cap(monitor.measured("http://a"), isLocalServer = false))
        }

    @Test
    fun `measureOrWait returns null on timeout and the probe still lands for the next request`() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val monitor = BandwidthMonitor(clock = { now }, scope = scope)
        val gate = CompletableDeferred<Int?>()
        var waited: Int? = -1
        scope.launch {
            waited = monitor.measureOrWait("http://a", waitMs = 1_500) { gate.await() }
        }
        scope.advanceUntilIdle()
        assertNull(waited)
        gate.complete(3_000_000)
        scope.advanceUntilIdle()
        assertEquals(3_000_000, monitor.measured("http://a"))
    }

    @Test
    fun `local servers do not wait at all`() =
        runTest {
            val monitor = BandwidthMonitor(clock = { now }, scope = this)
            assertNull(monitor.measureOrWait("http://a", waitMs = 0) { 9_000_000 })
            advanceUntilIdle()
            assertEquals(9_000_000, monitor.measured("http://a"))
        }
}
