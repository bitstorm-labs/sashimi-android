package dev.bitstorm.sashimi.core.downloads

import android.content.Context
import android.os.storage.StorageManager
import java.io.File

/**
 * On-disk layout + storage accounting for downloads, ported from the Swift
 * `DownloadFileManager`. Files live under app-private storage so they never
 * touch shared storage and are cleaned up on uninstall. The layout itself
 * (`filesDir/downloads/servers/{serverId}/{itemId}/`, formerly
 * `filesDir/downloads/{itemId}/`) is defined by [DownloadLayout].
 *
 * Writes always go to the current layout. Reads ([localFile], [itemSize]) fall
 * back to the legacy directory, so a download whose move failed still plays.
 */
class DownloadFileManager(context: Context) {
    private val appContext = context.applicationContext
    private val root: File = File(appContext.filesDir, "downloads")

    /** The current-layout directory for [key], created on demand. */
    fun itemDirectory(key: DownloadKey): File = File(root, DownloadLayout.itemPath(key)).apply { mkdirs() }

    fun videoFile(
        key: DownloadKey,
        fileName: String,
    ): File = File(itemDirectory(key), fileName)

    /** The in-progress partial file a resumable download streams into. */
    fun partialFile(key: DownloadKey): File = File(itemDirectory(key), PARTIAL_NAME)

    fun imageFile(
        key: DownloadKey,
        fileName: String,
    ): File = File(itemDirectory(key), fileName)

    /** The per-item `subtitles/` directory (created on demand). */
    fun subtitlesDirectory(key: DownloadKey): File = File(itemDirectory(key), SUBTITLES_DIR).apply { mkdirs() }

    fun subtitleFile(
        key: DownloadKey,
        fileName: String,
    ): File = File(subtitlesDirectory(key), fileName)

    /**
     * An existing file of [key]'s download at [relativePath] (e.g. `video.mp4`,
     * `subtitles/2_eng.vtt`), from the current layout or, failing that, the
     * legacy one. Null when neither has it. Creates nothing.
     */
    fun localFile(
        key: DownloadKey,
        relativePath: String,
    ): File? =
        File(File(root, DownloadLayout.itemPath(key)), relativePath).takeIf { it.exists() }
            ?: File(File(root, DownloadLayout.legacyItemPath(key.itemId)), relativePath).takeIf { it.exists() }

    fun deleteItemDirectory(key: DownloadKey) {
        File(root, DownloadLayout.itemPath(key)).deleteRecursively()
    }

    /**
     * Deletes the legacy `{itemId}/` directory. Only for a caller that knows no
     * remaining row has [itemId]: the directory is not tied to a server.
     */
    fun deleteLegacyItemDirectory(itemId: String) {
        val dir = File(root, DownloadLayout.legacyItemPath(itemId))
        if (itemId != DownloadLayout.SERVERS_DIR && dir.isDirectory) dir.deleteRecursively()
    }

    fun deleteAll() {
        root.deleteRecursively()
    }

    /** Recursive size of everything downloaded for one item (Swift itemSize). */
    fun itemSize(key: DownloadKey): Long {
        val current = File(root, DownloadLayout.itemPath(key))
        return if (current.exists()) directorySize(current) else directorySize(File(root, DownloadLayout.legacyItemPath(key.itemId)))
    }

    fun totalSize(): Long = directorySize(root)

    /** Names of legacy `{itemId}/` directories still at the top of the root. */
    fun legacyDirectoriesOnDisk(): Set<String> =
        root.listFiles()
            ?.filter { it.isDirectory && it.name != DownloadLayout.SERVERS_DIR }
            ?.map { it.name }
            ?.toSet()
            .orEmpty()

    /** Every current-layout item directory, as `servers/{server}/{item}` paths. */
    fun itemPathsOnDisk(): Set<String> {
        val servers = File(root, DownloadLayout.SERVERS_DIR).listFiles()?.filter { it.isDirectory }.orEmpty()
        return servers.flatMap { server ->
            server.listFiles()
                ?.filter { it.isDirectory }
                ?.map { "${DownloadLayout.SERVERS_DIR}/${server.name}/${it.name}" }
                .orEmpty()
        }.toSet()
    }

    /**
     * Applies [moves] with a same-volume rename, so a file is never copied or
     * half-written, and returns the ones that succeeded. A move whose target
     * already exists is skipped: nothing is overwritten.
     */
    fun relocate(moves: List<DownloadLayout.Move>): List<DownloadLayout.Move> =
        moves.filter { move ->
            val from = File(root, move.from)
            val to = File(root, move.to)
            if (!from.isDirectory || to.exists()) return@filter false
            to.parentFile?.mkdirs()
            from.renameTo(to)
        }

    /** Deletes a directory at [relativePath] under the root (the orphan sweep). */
    fun deleteRelative(relativePath: String) {
        if (relativePath.isEmpty() || relativePath == DownloadLayout.SERVERS_DIR) return
        File(root, relativePath).deleteRecursively()
    }

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
        const val SUBTITLES_DIR = "subtitles"
    }
}
