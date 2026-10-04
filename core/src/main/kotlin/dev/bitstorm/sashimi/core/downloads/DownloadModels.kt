package dev.bitstorm.sashimi.core.downloads

import kotlinx.serialization.Serializable

/**
 * A downloaded external/text subtitle track, ported from the Swift
 * `DownloadedSubtitle` SwiftData model. Persisted as a JSON list on
 * [DownloadedItemEntity.subtitlesJson] (a single nullable column keeps the Room
 * schema flat — no @Relation join table) and side-loaded as a Media3 VTT
 * [androidx.media3.common.MediaItem.SubtitleConfiguration] during offline
 * playback.
 *
 * [fileName] is relative to the item's `subtitles/` directory.
 */
@Serializable
data class DownloadedSubtitle(
    val subtitleIndex: Int,
    val language: String,
    val displayTitle: String,
    val fileName: String,
)

/**
 * Download quality tiers, ported from the Swift `DownloadQuality`
 * (Downloads/Models/DownloadModels.swift). Only [ORIGINAL] downloads the raw
 * file; the transcoded tiers hit /Videos/{id}/stream.mp4 for an h264/aac mp4
 * described by the tier's [encode].
 */
enum class DownloadQuality(
    val wireName: String,
    val displayName: String,
    val subtitle: String,
    /** What the server is asked to encode; null for [ORIGINAL] (raw file download). */
    val encode: TranscodeTarget?,
) {
    ORIGINAL("original", "Original", "Largest file size", null),
    HIGH("high", "High (1080p)", "Up to 20 Mbps", TranscodeTarget(20_000_000, 384_000, 6, 1920, 1080)),
    MEDIUM("medium", "Medium (720p)", "Up to 8 Mbps", TranscodeTarget(8_000_000, 192_000, 2, 1280, 720)),
    LOW("low", "Low (480p)", "Up to 4 Mbps", TranscodeTarget(4_000_000, 128_000, 2, 854, 480)),
    ;

    /**
     * One transcoded tier, in the terms the progressive stream endpoint reads.
     * [totalBitrate] is the figure the tier is labelled with; the video encode
     * gets what is left after audio, so the file as a whole stays at the label.
     */
    data class TranscodeTarget(
        val totalBitrate: Int,
        val audioBitrate: Int,
        val audioChannels: Int,
        val maxWidth: Int,
        val maxHeight: Int,
    ) {
        val videoBitrate: Int get() = totalBitrate - audioBitrate
    }

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
    COMPLETED("completed"),
    FAILED("failed"),
    ;

    companion object {
        fun fromWire(value: String?): DownloadStatus = entries.firstOrNull { it.wireName == value } ?: QUEUED
    }
}
