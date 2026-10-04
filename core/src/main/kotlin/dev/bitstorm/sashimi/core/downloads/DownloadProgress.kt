package dev.bitstorm.sashimi.core.downloads

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Progress for a download whose size may not be known.
 *
 * A transcoded download has no Content-Length (Jellyfin streams the encode as
 * it is produced), so the old row could only say "Downloading…" with an
 * indeterminate spinner for the whole of a long encode. The total is estimated
 * instead from what the tier asks the server for: (video + audio bitrate) ×
 * runtime, with the video part capped at the source's own video bitrate when
 * known (Jellyfin never encodes above the source). An estimate is marked "~",
 * never reads 100% before the file is actually complete, and grows when the
 * bytes received pass it.
 */
object DownloadProgress {
    /** The most an estimate-based download can read before it completes. */
    const val MAX_ESTIMATED_FRACTION = 0.99

    private const val TICKS_PER_SECOND = 10_000_000L

    /**
     * Estimated file size in bytes for a transcoded [quality], or null for
     * Original (which reports its real size) or when the runtime is unknown.
     */
    fun estimatedTotalBytes(
        quality: DownloadQuality,
        runTimeTicks: Long?,
        sourceVideoBitrate: Long? = null,
    ): Long? {
        val encode = quality.encode ?: return null
        val seconds = (runTimeTicks ?: return null) / TICKS_PER_SECOND.toDouble()
        if (seconds <= 0) return null
        val video = sourceVideoBitrate?.takeIf { it > 0 }?.coerceAtMost(encode.videoBitrate.toLong()) ?: encode.videoBitrate.toLong()
        val bits = (video + encode.audioBitrate) * seconds
        return (bits / 8).roundToLong()
    }

    data class Snapshot(
        /** 0..1; capped at [MAX_ESTIMATED_FRACTION] while [isEstimate]. */
        val fraction: Double,
        val totalBytes: Long,
        val isEstimate: Boolean,
    )

    /**
     * @param reportedTotal the real total from Content-Length, or <= 0 when unknown.
     * @param estimatedTotal from [estimatedTotalBytes]; null when there is none.
     * @return null when neither total exists (an honest indeterminate bar).
     */
    fun snapshot(
        receivedBytes: Long,
        reportedTotal: Long,
        estimatedTotal: Long?,
    ): Snapshot? {
        if (reportedTotal > 0) {
            return Snapshot((receivedBytes.toDouble() / reportedTotal).coerceIn(0.0, 1.0), reportedTotal, isEstimate = false)
        }
        val estimate = estimatedTotal?.takeIf { it > 0 } ?: return null
        // Received bytes passed the estimate: the encode is bigger than the
        // tier's nominal rate (VBR peaks, a long runtime). Grow the estimate
        // so the bar sits at the cap instead of running past it.
        val total = maxOf(estimate, ceil(receivedBytes / MAX_ESTIMATED_FRACTION).toLong())
        val fraction = (receivedBytes.toDouble() / total).coerceIn(0.0, MAX_ESTIMATED_FRACTION)
        return Snapshot(fraction, total, isEstimate = true)
    }

    /**
     * The Downloads row text: "43% · 182 MB of ~420 MB · 3.1 MB/s · about 1 min left".
     * Parts with no data are dropped; with no total at all it reads
     * "182 MB · 3.1 MB/s".
     */
    fun label(
        receivedBytes: Long,
        snapshot: Snapshot?,
        bytesPerSecond: Long?,
    ): String =
        buildList {
            if (snapshot != null) {
                add("${(snapshot.fraction * 100).toInt()}%")
                add("${formatBytes(receivedBytes)} of ${if (snapshot.isEstimate) "~" else ""}${formatBytes(snapshot.totalBytes)}")
            } else {
                add(formatBytes(receivedBytes))
            }
            bytesPerSecond?.takeIf { it > 0 }?.let { rate ->
                add("${formatBytes(rate)}/s")
                if (snapshot != null) {
                    val remaining = (snapshot.totalBytes - receivedBytes).coerceAtLeast(0)
                    add(timeLeft(remaining / rate))
                }
            }
        }.joinToString(" · ")

    fun timeLeft(seconds: Long): String =
        when {
            seconds < 60 -> "less than a minute left"
            seconds < 90 -> "about 1 min left"
            seconds < 3600 -> "about ${(seconds + 30) / 60} min left"
            else -> {
                val h = seconds / 3600
                val m = (seconds % 3600 + 30) / 60
                if (m == 0L) "about $h h left" else "about $h h $m min left"
            }
        }

    /** Decimal units, matching the app's formatBytes ("182 MB", "1.2 GB"). */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1000 && unit < units.lastIndex) {
            value /= 1000
            unit++
        }
        return String.format(Locale.US, if (value >= 100 || unit == 0) "%.0f %s" else "%.1f %s", value, units[unit])
    }
}

/**
 * Smoothed transfer rate from (bytes so far, time) samples. Exponentially
 * weighted so one slow or fast second does not swing the ETA. Clock-free: the
 * caller passes timestamps, so it is unit tested.
 */
class TransferRate(
    private val smoothing: Double = 0.3,
) {
    private var lastBytes = -1L
    private var lastMs = 0L
    private var rate: Double? = null

    fun sample(
        totalBytes: Long,
        nowMs: Long,
    ): Long? {
        if (lastBytes >= 0 && nowMs > lastMs && totalBytes >= lastBytes) {
            val instant = (totalBytes - lastBytes) * 1000.0 / (nowMs - lastMs)
            rate = rate?.let { it + smoothing * (instant - it) } ?: instant
        }
        lastBytes = totalBytes
        lastMs = nowMs
        return rate?.roundToLong()
    }
}

/** What a download is doing right now, beyond its persisted status. In memory only. */
enum class DownloadPhase {
    /** Queued or preparing while the device is offline: WorkManager is waiting for a network. */
    WAITING_FOR_NETWORK,

    /** A previous attempt was interrupted and this one is starting again. */
    RETRYING,

    /**
     * A transcoded download was interrupted and restarted from the beginning:
     * the server cannot resume a live encode by byte range, so the bytes
     * already received are discarded.
     */
    RESTARTING,
}

/** What an in-flight row in the Downloads screen says, from its row and live state. Pure. */
data class ActiveDownloadText(
    val status: String,
    /** A second line explaining a retry or restart; null when there is nothing to explain. */
    val note: String?,
    /** Determinate bar position, or null for an indeterminate bar (or none before the download starts). */
    val fraction: Float?,
) {
    companion object {
        fun of(
            row: DownloadedItemEntity,
            live: LiveTransfer?,
            online: Boolean,
        ): ActiveDownloadText {
            val known = row.progress.takeIf { it >= 0 }?.toFloat()
            val note =
                when (live?.phase) {
                    DownloadPhase.RESTARTING -> "Restarting from the beginning: converted downloads can't resume."
                    DownloadPhase.RETRYING -> "Retrying after the connection dropped."
                    else -> null
                }
            return when (row.downloadStatus) {
                DownloadStatus.QUEUED ->
                    ActiveDownloadText(if (online) "Queued" else "Waiting for network", note, known)
                DownloadStatus.PREPARING ->
                    ActiveDownloadText(
                        when {
                            !online -> "Waiting for network"
                            live?.phase == DownloadPhase.RETRYING -> "Retrying…"
                            else -> "Preparing…"
                        },
                        note,
                        known,
                    )
                else -> {
                    // A transcoded tier never has a real Content-Length, so
                    // its total is always the estimate (marked "~").
                    val snapshot =
                        if (known != null && row.totalBytes > 0) {
                            DownloadProgress.Snapshot(known.toDouble(), row.totalBytes, isEstimate = row.downloadQuality.encode != null)
                        } else {
                            null
                        }
                    val status =
                        if (!online) {
                            "Waiting for network · ${DownloadProgress.formatBytes(row.downloadedBytes)}"
                        } else {
                            DownloadProgress.label(row.downloadedBytes, snapshot, live?.bytesPerSecond)
                        }
                    ActiveDownloadText(status, note, snapshot?.fraction?.toFloat())
                }
            }
        }
    }
}

/** The live (not persisted) state the Downloads screen shows for an in-flight download. */
data class LiveTransfer(
    val bytesPerSecond: Long? = null,
    val phase: DownloadPhase? = null,
)
