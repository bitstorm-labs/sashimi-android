package dev.bitstorm.sashimi.core.downloads

/**
 * Download quality tiers, ported 1:1 from the Swift `DownloadQuality`
 * (Downloads/Models/DownloadModels.swift). The resolution strings ("1080p" …)
 * are cosmetic — the server is only ever given a bitrate ceiling. Only
 * [ORIGINAL] downloads the raw file; the transcoded tiers hit
 * /Videos/{id}/stream.mp4 with h264/aac/mp4 at the tier's [maxBitrate].
 */
enum class DownloadQuality(
    val wireName: String,
    val displayName: String,
    val subtitle: String,
    /** bits/sec cap; null for [ORIGINAL] (raw file download). */
    val maxBitrate: Int?,
) {
    ORIGINAL("original", "Original", "Largest file size", null),
    HIGH("high", "High (1080p)", "Up to 20 Mbps", 20_000_000),
    MEDIUM("medium", "Medium (720p)", "Up to 8 Mbps", 8_000_000),
    LOW("low", "Low (480p)", "Up to 4 Mbps", 4_000_000),
    ;

    companion object {
        /** Unknown raw values decode to [HIGH], matching the Swift getter fallback. */
        fun fromWire(value: String?): DownloadQuality = entries.firstOrNull { it.wireName == value } ?: HIGH

        /**
         * The fail-closed Original gate (Swift `effectiveQuality`): a request for
         * [ORIGINAL] is only honoured when the source can direct-play on this
         * device; otherwise it degrades to [HIGH]. All transcoded tiers pass
         * through unchanged.
         */
        fun effectiveQuality(
            requested: DownloadQuality,
            sourceIsCompatible: Boolean,
        ): DownloadQuality =
            if (requested != ORIGINAL) {
                requested
            } else if (sourceIsCompatible) {
                ORIGINAL
            } else {
                HIGH
            }
    }
}

/**
 * Persisted download lifecycle. The Swift enum also has `paused`, which is never
 * produced (it is retained here for parity but likewise unused — cancel deletes
 * outright). The normal cycle is QUEUED → DOWNLOADING → COMPLETED | FAILED, with
 * PREPARING flagged in-flight before the first byte arrives.
 */
enum class DownloadStatus(
    val wireName: String,
) {
    QUEUED("queued"),
    PREPARING("preparing"),
    DOWNLOADING("downloading"),
    PAUSED("paused"),
    COMPLETED("completed"),
    FAILED("failed"),
    ;

    companion object {
        fun fromWire(value: String?): DownloadStatus = entries.firstOrNull { it.wireName == value } ?: QUEUED
    }
}
