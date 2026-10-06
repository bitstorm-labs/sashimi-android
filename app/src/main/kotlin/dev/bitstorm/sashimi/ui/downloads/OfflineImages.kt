package dev.bitstorm.sashimi.ui.downloads

import dev.bitstorm.sashimi.core.downloads.DownloadFileManager
import dev.bitstorm.sashimi.core.downloads.DownloadKey
import dev.bitstorm.sashimi.di.ServiceLocator
import java.io.File
import java.util.Locale

/**
 * Resolves Coil image models for downloaded content, preferring locally-cached
 * files (so posters render offline) and falling back to a server URL when
 * online. Mirrors the Swift `OfflineImageHelper` resolution order.
 */
object OfflineImages {
    private val files: DownloadFileManager get() = ServiceLocator.downloadFileManager

    /** Local poster for a downloaded item (series poster wins for episodes), or null. */
    fun localPoster(key: DownloadKey): File? =
        files.localFile(key, DownloadFileManager.SERIES_POSTER_NAME)
            ?: files.localFile(key, DownloadFileManager.POSTER_NAME)

    /** The item's own landscape art (an episode's still), for the Up Next card; else its backdrop. */
    fun localThumbnail(key: DownloadKey): File? =
        files.localFile(key, DownloadFileManager.POSTER_NAME)
            ?: files.localFile(key, DownloadFileManager.BACKDROP_NAME)

    fun localBackdrop(key: DownloadKey): File? =
        files.localFile(key, DownloadFileManager.BACKDROP_NAME)
            ?: files.localFile(key, DownloadFileManager.POSTER_NAME)

    /**
     * Coil model: the local poster file when present, else the image URL on
     * the server the download came from.
     */
    fun posterModel(
        key: DownloadKey,
        fallbackImageItemId: String = key.itemId,
    ): Any? =
        localPoster(key)
            ?: ServiceLocator.serverClients.forRecord(key.serverId)?.imageURL(fallbackImageItemId, "Primary", 400)
}

/** Human-readable file size (decimal units), matching the iOS ByteCountFormatter .file style. */
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
