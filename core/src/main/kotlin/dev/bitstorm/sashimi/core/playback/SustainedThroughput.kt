package dev.bitstorm.sashimi.core.playback

import android.content.Context
import android.content.SharedPreferences

/** Where [SustainedThroughputStore] keeps its readings: SharedPreferences in the app, a map in tests. */
interface ThroughputPersistence {
    fun read(key: String): List<Int>

    fun write(
        key: String,
        readings: List<Int>,
    )
}

class InMemoryThroughputPersistence : ThroughputPersistence {
    private val values = HashMap<String, List<Int>>()

    @Synchronized
    override fun read(key: String): List<Int> = values[key].orEmpty()

    @Synchronized
    override fun write(
        key: String,
        readings: List<Int>,
    ) {
        values[key] = readings
    }
}

/** One comma-separated string per key; survives launches. */
class PrefsThroughputPersistence(
    context: Context,
) : ThroughputPersistence {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(key: String): List<Int> = prefs.getString(key, null).orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }

    override fun write(
        key: String,
        readings: List<Int>,
    ) {
        prefs.edit().putString(key, readings.joinToString(",")).apply()
    }

    private companion object {
        const val PREFS_NAME = "sashimi_sustained_throughput"
    }
}

/**
 * What playback itself has downloaded at, per server and per [LinkKind]
 * (Apple's `BandwidthMeasurementStore` sustained readings).
 *
 * Few and recent: the last [SAMPLE_LIMIT] readings, each over at least
 * [MINIMUM_BYTES] of video (the first segments of a stream arrive in a burst
 * that says little about the link). The link's sustained rate is the LOWEST
 * of them, not the average: one slow stretch, a stall, is the evidence that
 * matters, and it keeps counting until five newer readings replace it.
 */
class SustainedThroughputStore(
    private val persistence: ThroughputPersistence,
) {
    fun record(
        serverUrl: String,
        linkKind: LinkKind,
        bitsPerSecond: Int,
        bytesTransferred: Long,
    ) {
        if (bitsPerSecond <= 0 || bytesTransferred < MINIMUM_BYTES) return
        val key = key(serverUrl, linkKind)
        synchronized(this) {
            persistence.write(key, (persistence.read(key) + bitsPerSecond).takeLast(SAMPLE_LIMIT))
        }
    }

    /** The lowest recent reading for this server over this kind of link, or null when there is none. */
    fun sustainedBitrate(
        serverUrl: String?,
        linkKind: LinkKind,
    ): Int? {
        serverUrl ?: return null
        return synchronized(this) { persistence.read(key(serverUrl, linkKind)).minOrNull() }
    }

    companion object {
        const val SAMPLE_LIMIT = 5
        const val MINIMUM_BYTES = 16_000_000L

        /**
         * One key per server: scheme, host and port plus any base path, case-
         * insensitive in scheme and host, a trailing slash ignored.
         */
        fun key(
            serverUrl: String,
            linkKind: LinkKind,
        ): String {
            val trimmed = serverUrl.trim().trimEnd('/')
            val scheme = trimmed.substringBefore("://", "").lowercase()
            val rest = trimmed.substringAfter("://")
            val authority = rest.substringBefore('/').lowercase()
            val path = if ('/' in rest) "/" + rest.substringAfter('/') else ""
            return "$scheme://$authority$path|${linkKind.key}"
        }
    }
}

/**
 * Turns the player's network transfers into sustained-throughput readings,
 * with an injected clock so the accounting is unit tested.
 *
 * Only time the link spends delivering counts:
 *  - a transfer's clock starts when its response has opened, so the server's
 *    time to first byte (a transcode waiting for its segment) is not the link;
 *  - a gap longer than [idleGapNanos] between reads is the player not asking,
 *    and the bytes that end it may be a socket buffer draining in a burst, so
 *    neither counts;
 *  - with [cutOnLoadPause] (progressive direct play, which holds one transfer
 *    open for the whole file and stops reading whenever the buffer is full),
 *    a transfer stops counting once the player's load control has paused
 *    loading since it opened. Its later bytes arrive at the playback rate, not
 *    the link's. HLS segments are fetched whole, so they never need the cut.
 *
 * A reading is produced each time at least [minimumBytes] have been counted.
 */
class ThroughputMeter(
    private val minimumBytes: Long = SustainedThroughputStore.MINIMUM_BYTES,
    private val idleGapNanos: Long = IDLE_GAP_NANOS,
) {
    data class Reading(
        val bitsPerSecond: Int,
        val bytes: Long,
    )

    private class Transfer(
        var lastAt: Long,
        val epoch: Long,
    )

    private val transfers = java.util.IdentityHashMap<Any, Transfer>()
    private var loadPauseEpoch = 0L
    private var bytes = 0L
    private var activeNanos = 0L

    @Volatile
    var cutOnLoadPause: Boolean = false

    @Synchronized
    fun transferStarted(
        transfer: Any,
        nowNanos: Long,
    ) {
        transfers[transfer] = Transfer(nowNanos, loadPauseEpoch)
    }

    /** Returns a reading when this transfer completed one, else null. */
    @Synchronized
    fun bytesTransferred(
        transfer: Any,
        count: Int,
        nowNanos: Long,
    ): Reading? {
        val t = transfers[transfer] ?: return null
        if (cutOnLoadPause && t.epoch != loadPauseEpoch) return null
        val gap = nowNanos - t.lastAt
        t.lastAt = nowNanos
        if (count <= 0 || gap < 0 || gap > idleGapNanos) return null
        bytes += count
        activeNanos += gap
        if (bytes < minimumBytes || activeNanos <= 0) return null
        val rate = (bytes * 8 * NANOS_PER_SECOND.toDouble() / activeNanos).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
        val reading = Reading(rate, bytes)
        bytes = 0
        activeNanos = 0
        return reading
    }

    @Synchronized
    fun transferEnded(transfer: Any) {
        transfers.remove(transfer)
    }

    /** The load control declined to load more: the buffer is full. */
    @Synchronized
    fun loadingPaused() {
        loadPauseEpoch++
    }

    /** A new stream: nothing from the last one counts. */
    @Synchronized
    fun reset(cutOnLoadPause: Boolean) {
        transfers.clear()
        bytes = 0
        activeNanos = 0
        this.cutOnLoadPause = cutOnLoadPause
    }

    companion object {
        /** Longer than this between reads is the player idle, not the link slow (16 KB in 0.5 s is 256 kbps). */
        const val IDLE_GAP_NANOS = 500_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
