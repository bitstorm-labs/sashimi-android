package dev.bitstorm.sashimi.core.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The arithmetic behind the bandwidth probe, pure so it is unit tested.
 *
 * The probe streams the server's `/Playback/BitrateTest` response for up to
 * [MAX_DURATION_MS] or [MAX_BYTES], whichever comes first, and times only the
 * steady-state window after a [WARMUP_MS] warm-up, so TCP slow start and Wi-Fi
 * burst buffering do not inflate the reading (the Apple client's lesson: an
 * 8 MB burst read 77 Mbps on a link that could not hold 66).
 *
 * Slow links never reach the byte cap: at the deadline the bytes received so
 * far ARE the measurement (a 2 Mbps link yields ~1 MB in the window and reads
 * ~2 Mbps), which is the partial-result estimate the audit asked for. Fast
 * links finish before a steady-state window exists; they fall back to the
 * overall rate, which is a lower bound and still well above any tier.
 */
object BandwidthProbeMath {
    const val WARMUP_MS = 1_000L
    const val MAX_DURATION_MS = 5_000L
    const val MAX_BYTES = 16L * 1024 * 1024

    /** The smallest steady-state window worth trusting. */
    const val MIN_SAMPLE_MS = 500L

    /**
     * Bits per second from a probe, or null when nothing usable arrived.
     *
     * @param totalBytes everything received, from the first byte.
     * @param totalMs time from the first byte to the end of the probe.
     * @param steadyBytes bytes received after the warm-up window closed.
     * @param steadyMs time from the end of the warm-up to the end of the probe.
     */
    fun bitsPerSecond(
        totalBytes: Long,
        totalMs: Long,
        steadyBytes: Long,
        steadyMs: Long,
    ): Int? {
        if (steadyMs >= MIN_SAMPLE_MS && steadyBytes > 0) return rate(steadyBytes, steadyMs)
        if (totalBytes > 0 && totalMs > 0) return rate(totalBytes, totalMs)
        return null
    }

    private fun rate(
        bytes: Long,
        ms: Long,
    ): Int = (bytes * 8_000.0 / ms).toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/**
 * Per-server memory of the measured downstream bandwidth, shared by every
 * client bound to that server. Starts the probe on request, lets a caller wait
 * a bounded time for it, and keeps the result for [maxAgeMs].
 *
 * [limitTo] records what a step-down learned: a stream at a tier stalled, so
 * the link is not what the probe said. Until the next probe, Auto starts at or
 * below that tier instead of climbing straight back to the optimistic reading
 * on the next title.
 */
class BandwidthMonitor(
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = MAX_AGE_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private data class Measurement(
        val bitsPerSecond: Int,
        val at: Long,
    )

    private val measurements = ConcurrentHashMap<String, Measurement>()
    private val inFlight = ConcurrentHashMap<String, Deferred<Int?>>()

    /** The current measurement for [serverUrl], or null when none is fresh. */
    fun measured(serverUrl: String): Int? {
        val m = measurements[serverUrl] ?: return null
        return if (clock() - m.at <= maxAgeMs) m.bitsPerSecond else null
    }

    fun record(
        serverUrl: String,
        bitsPerSecond: Int,
    ) {
        measurements[serverUrl] = Measurement(bitsPerSecond, clock())
    }

    /**
     * A tier at [tierBitsPerSecond] stalled on this server: cap the memory at
     * what Auto would need to measure to pick that tier again.
     */
    fun limitTo(
        serverUrl: String,
        tierBitsPerSecond: Int,
    ) {
        val ceiling = kotlin.math.ceil(tierBitsPerSecond / AutoBitrate.MEASURED_HEADROOM).toInt()
        val current = measured(serverUrl)
        if (current == null || current > ceiling) record(serverUrl, ceiling)
    }

    /**
     * Start a probe for [serverUrl] unless one is running or a fresh
     * measurement exists, then wait up to [waitMs] for a result. Returns the
     * measurement if one is available in time, else null; the probe keeps
     * running in the background and its result serves the next request.
     */
    suspend fun measureOrWait(
        serverUrl: String,
        waitMs: Long,
        probe: suspend () -> Int?,
    ): Int? {
        measured(serverUrl)?.let { return it }
        val job = start(serverUrl, probe)
        if (waitMs <= 0) return null
        return withTimeoutOrNull(waitMs) { job.await() }
    }

    /** Kick off a probe in the background if none is running and nothing fresh is known. */
    fun refresh(
        serverUrl: String,
        probe: suspend () -> Int?,
    ) {
        if (measured(serverUrl) == null) start(serverUrl, probe)
    }

    private fun start(
        serverUrl: String,
        probe: suspend () -> Int?,
    ): Deferred<Int?> =
        inFlight.computeIfAbsent(serverUrl) {
            scope.async {
                try {
                    val result = runCatching { probe() }.getOrNull()
                    result?.let { record(serverUrl, it) }
                    result
                } finally {
                    inFlight.remove(serverUrl)
                }
            }
        }

    companion object {
        /** jellyfin-web caches its bitrate test for the same hour. */
        const val MAX_AGE_MS = 60L * 60 * 1000

        /** How long a remote Auto negotiation waits for an in-flight probe before starting conservatively. */
        const val PROBE_WAIT_MS = 1_500L
    }
}
