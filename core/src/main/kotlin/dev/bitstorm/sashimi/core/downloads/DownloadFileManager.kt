package dev.bitstorm.sashimi.core.downloads

import android.content.Context
import android.os.storage.StorageManager
import java.io.File
import java.util.UUID

/**
 * On-disk layout + storage accounting for downloads, ported from the Swift
 * `DownloadFileManager`. Files live under app-private storage
 * (`filesDir/downloads/{itemId}/`) so they never touch shared storage and are
 * cleaned up on uninstall.
 */
class DownloadFileManager(context: Context) {
    private val appContext = context.applicationContext
    private val root: File = File(appContext.filesDir, "downloads")

    fun itemDirectory(itemId: String): File = File(root, itemId).apply { mkdirs() }

    fun videoFile(
        itemId: String,
        fileName: String,
    ): File = File(itemDirectory(itemId), fileName)

    /** The in-progress partial file a resumable download streams into. */
    fun partialFile(itemId: String): File = File(itemDirectory(itemId), PARTIAL_NAME)

    fun imageFile(
        itemId: String,
        fileName: String,
    ): File = File(itemDirectory(itemId), fileName)

    /** The per-item `subtitles/` directory (created on demand). */
    fun subtitlesDirectory(itemId: String): File = File(itemDirectory(itemId), "subtitles").apply { mkdirs() }

    fun subtitleFile(
        itemId: String,
        fileName: String,
    ): File = File(subtitlesDirectory(itemId), fileName)

    fun deleteItemDirectory(itemId: String) {
        File(root, itemId).deleteRecursively()
    }

    fun deleteAll() {
        root.deleteRecursively()
    }

    /** Recursive size of everything downloaded for one item (Swift itemSize). */
    fun itemSize(itemId: String): Long = directorySize(File(root, itemId))

    fun totalSize(): Long = directorySize(root)

    private fun directorySize(dir: File): Long {
        if (!dir.exists()) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * Free space available to the app, using the API 26+ allocatable-bytes API
     * (the closest analogue to iOS `volumeAvailableCapacityForImportantUsage`),
     * falling back to raw usable space.
     */
    fun availableDiskSpace(): Long =
        runCatching {
            val storageManager = appContext.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val uuid = storageManager.getUuidForPath(appContext.filesDir)
            storageManager.getAllocatableBytes(uuid)
        }.getOrElse { appContext.filesDir.usableSpace }

    companion object {
        const val PARTIAL_NAME = "video.part"
        const val POSTER_NAME = "poster.jpg"
        const val BACKDROP_NAME = "backdrop.jpg"
        const val SERIES_POSTER_NAME = "series_poster.jpg"

        /** Unique temp name is unused; kept for potential atomic-move needs. */
        fun tempName(): String = "tmp-${UUID.randomUUID()}"
    }
}
