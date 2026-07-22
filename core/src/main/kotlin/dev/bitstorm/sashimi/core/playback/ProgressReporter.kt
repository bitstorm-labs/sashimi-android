package dev.bitstorm.sashimi.core.playback

import dev.bitstorm.sashimi.core.network.JellyfinClient

/**
 * Drives the /Sessions playback reporting cadence, ported from the Swift
 * PlayerViewModel: a start report, a periodic progress report every 5 seconds
 * (plus an immediate report on every play/pause transition — the caller invokes
 * [reportProgress] directly for those), and a stopped report on teardown.
 *
 * The subtle bit is the **quick-exit rule** ([stopPositionTicks]): if the user
 * exits within [QUICK_EXIT_MS] of the player starting AND there was a saved
 * resume position, the stopped report re-sends the ORIGINAL resume ticks so a
 * brief accidental open doesn't reset the user's progress to ~0.
 *
 * Timing is virtual-clock friendly: [clock] returns "now" in millis, so cadence
 * and quick-exit are deterministically testable. Compose-free; the player
 * ViewModel owns the actual coroutine timer and calls in.
 */
class ProgressReporter(
    private val client: JellyfinClient,
    private val itemId: String,
    private val playSessionId: String?,
    private val reportedPlayMethod: String,
    private val resumePositionTicks: Long,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private var startClockMs: Long = 0
    private var lastPeriodicMs: Long = 0

    /** Records the wall-clock start of playback (for the quick-exit + cadence timers). */
    fun begin() {
        val now = clock()
        startClockMs = now
        lastPeriodicMs = now
    }

    /** Records the wall-clock start and sends the initial /Sessions/Playing report. */
    suspend fun reportStart(positionTicks: Long) {
        begin()
        client.reportPlaybackStart(
            itemId = itemId,
            positionTicks = positionTicks,
            playSessionId = playSessionId,
            playMethod = reportedPlayMethod,
        )
    }

    /**
     * True when at least [PROGRESS_INTERVAL_MS] has elapsed since the last
     * periodic report. The caller checks this on its timer tick, reports, then
     * calls [markPeriodicReported]. Play/pause transitions bypass this and report
     * immediately.
     */
    fun periodicDue(nowMs: Long = clock()): Boolean = nowMs - lastPeriodicMs >= PROGRESS_INTERVAL_MS

    fun markPeriodicReported(nowMs: Long = clock()) {
        lastPeriodicMs = nowMs
    }

    suspend fun reportProgress(
        positionTicks: Long,
        isPaused: Boolean,
    ) {
        markPeriodicReported()
        client.reportPlaybackProgress(
            itemId = itemId,
            positionTicks = positionTicks,
            isPaused = isPaused,
            playSessionId = playSessionId,
        )
    }

    /**
     * The position to report on stop, applying the quick-exit rule. Pure, so it
     * is unit-tested with a virtual clock.
     */
    fun stopPositionTicks(currentPositionTicks: Long): Long {
        val elapsedMs = clock() - startClockMs
        return if (elapsedMs < QUICK_EXIT_MS && resumePositionTicks > 0) {
            resumePositionTicks
        } else {
            currentPositionTicks
        }
    }

    suspend fun reportStopped(currentPositionTicks: Long) {
        client.reportPlaybackStopped(
            itemId = itemId,
            positionTicks = stopPositionTicks(currentPositionTicks),
            playSessionId = playSessionId,
        )
    }

    /** End-of-item: report the end position (not quick-exit) and mark played. */
    suspend fun reportEndOfPlayback(durationTicks: Long) {
        client.reportPlaybackStopped(
            itemId = itemId,
            positionTicks = durationTicks,
            playSessionId = playSessionId,
        )
        runCatching { client.markPlayed(itemId) }
    }

    companion object {
        const val PROGRESS_INTERVAL_MS = 5_000L
        const val QUICK_EXIT_MS = 10_000L
    }
}
