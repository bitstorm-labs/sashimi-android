package dev.bitstorm.sashimi.ui.downloads

import dev.bitstorm.sashimi.core.downloads.DownloadFileManager
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
    fun localPoster(itemId: String): File? =
        firstExisting(
            files.imageFile(itemId, DownloadFileManager.SERIES_POSTER_NAME),
            files.imageFile(itemId, DownloadFileManager.POSTER_NAME),
        )

    fun localBackdrop(itemId: String): File? =
        firstExisting(
            files.imageFile(itemId, DownloadFileManager.BACKDROP_NAME),
            files.imageFile(itemId, DownloadFileManager.POSTER_NAME),
        )

    /** Coil model: the local poster file when present, else the server image URL. */
    fun posterModel(
        itemId: String,
        fallbackImageItemId: String = itemId,
    ): Any? = localPoster(itemId) ?: ServiceLocator.client.imageURL(fallbackImageItemId, "Primary", 400)

    private fun firstExisting(vararg candidates: File): File? = candidates.firstOrNull { it.exists() }
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
