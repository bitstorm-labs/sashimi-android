package dev.bitstorm.sashimi.core.downloads

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Room mirror of the Swift `DownloadedItem` SwiftData model. One row per
 * downloaded (or downloading) Jellyfin item, keyed by its item id. Enums are
 * stored as their lowercase wire strings so a schema stays legible and forgiving.
 *
 * [progress] carries the Swift `-1` sentinel meaning "in progress, total size
 * unknown" (transcoded streams report no Content-Length) — the UI must render
 * that as an indeterminate spinner rather than 0 %.
 */
@Entity(tableName = "downloaded_items")
data class DownloadedItemEntity(
    @PrimaryKey val itemId: String,
    val name: String,
    val seriesName: String? = null,
    val seriesId: String? = null,
    val seasonId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val overview: String? = null,
    val itemType: String? = null,
    val runTimeTicks: Long? = null,
    val productionYear: Int? = null,
    val status: String = DownloadStatus.QUEUED.wireName,
    val quality: String = DownloadQuality.HIGH.wireName,
    val progress: Double = 0.0,
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val errorMessage: String? = null,
    val videoFileName: String? = null,
    val posterFileName: String? = null,
    val backdropFileName: String? = null,
    /** JSON-encoded list of [DownloadedSubtitle] side-loaded during offline playback. */
    val subtitlesJson: String? = null,
    /** Locally-saved resume position (100-ns ticks); synced to the server later. */
    val localPositionTicks: Long = 0,
    /** True when [localPositionTicks] moved while offline and needs a server report. */
    val pendingProgressSync: Boolean = false,
    val dateAdded: Long = 0,
    val dateCompleted: Long? = null,
) {
    val downloadStatus: DownloadStatus get() = DownloadStatus.fromWire(status)
    val downloadQuality: DownloadQuality get() = DownloadQuality.fromWire(quality)
    val isComplete: Boolean get() = downloadStatus == DownloadStatus.COMPLETED

    /** Decoded side-loaded subtitle tracks (empty when none downloaded). */
    val subtitles: List<DownloadedSubtitle>
        get() =
            subtitlesJson?.let { json ->
                runCatching { SUBTITLE_JSON.decodeFromString<List<DownloadedSubtitle>>(json) }.getOrDefault(emptyList())
            } ?: emptyList()

    /** True while queued, preparing, or actively downloading. */
    val isActive: Boolean
        get() = downloadStatus in ACTIVE_STATUSES

    /** "{series} S{n}:E{n}" for episodes, else the plain name (Swift displayTitle). */
    val displayTitle: String
        get() {
            val s = seasonNumber
            val e = episodeNumber
            return if (seriesName != null && s != null && e != null) "$seriesName S$s:E$e" else name
        }

    companion object {
        val ACTIVE_STATUSES =
            setOf(DownloadStatus.QUEUED, DownloadStatus.PREPARING, DownloadStatus.DOWNLOADING)

        val SUBTITLE_JSON = Json { ignoreUnknownKeys = true }

        fun encodeSubtitles(subtitles: List<DownloadedSubtitle>): String? =
            if (subtitles.isEmpty()) null else SUBTITLE_JSON.encodeToString(subtitles)
    }
}
