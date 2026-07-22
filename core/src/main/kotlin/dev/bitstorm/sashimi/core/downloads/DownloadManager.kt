package dev.bitstorm.sashimi.core.downloads

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.network.JellyfinClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Orchestrates the downloads engine: enqueue/cancel/retry/delete, the max-2
 * concurrency cap, WorkManager scheduling, storage guarding, pending-progress
 * sync, and the actual OkHttp streaming (Range-resumable). The Android analogue
 * of the Swift `DownloadManager` singleton, minus its background-URLSession
 * plumbing (WorkManager owns that here).
 *
 * Concurrency is capped in [promote]: only queued items up to the free slots are
 * ever enqueued, and each finishing worker calls [onWorkFinished] to fill the
 * next slot — no reliance on WorkManager's own (unbounded for CoroutineWorker)
 * scheduling.
 */
class DownloadManager(
    context: Context,
    private val repository: DownloadRepository,
    private val fileManager: DownloadFileManager,
    private val client: JellyfinClient,
    private val networkMonitor: NetworkMonitor,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val promoteMutex = Mutex()

    private val http =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()

    /** Reactive snapshot of every download row for the UI. */
    val downloads: StateFlow<List<DownloadedItemEntity>> =
        repository.downloads.stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        current = this
        scope.launch { recover() }
        // Sync stashed offline progress on start and whenever connectivity returns.
        scope.launch { syncPendingProgress() }
        scope.launch {
            networkMonitor.isOnline.drop(1).collect { online ->
                if (online) {
                    syncPendingProgress()
                    promote()
                }
            }
        }
    }

    // MARK: - Enqueue

    fun enqueueDownload(
        item: BaseItemDto,
        quality: DownloadQuality,
    ) {
        scope.launch {
            insertQueued(item, quality)
            promote()
        }
    }

    fun downloadSeason(
        episodes: List<BaseItemDto>,
        quality: DownloadQuality,
    ) {
        scope.launch {
            episodes.forEach { insertQueued(it, quality) }
            promote()
        }
    }

    private suspend fun insertQueued(
        item: BaseItemDto,
        quality: DownloadQuality,
    ) {
        val existing = repository.get(item.id)
        if (DownloadPolicy.isDuplicate(existing)) return
        repository.upsert(
            DownloadedItemEntity(
                itemId = item.id,
                name = item.name,
                seriesName = item.seriesName,
                seriesId = item.seriesId,
                seasonId = item.seasonId,
                seasonNumber = item.parentIndexNumber,
                episodeNumber = item.indexNumber,
                overview = item.overview,
                itemType = item.type?.wireName,
                runTimeTicks = item.runTimeTicks,
                productionYear = item.productionYear,
                status = DownloadStatus.QUEUED.wireName,
                quality = quality.wireName,
                dateAdded = System.currentTimeMillis(),
            ),
        )
    }

    // MARK: - Cancel / delete / retry

    fun cancel(itemId: String) {
        scope.launch {
            workManager.cancelUniqueWork(DownloadWorker.uniqueName(itemId))
            fileManager.deleteItemDirectory(itemId)
            repository.delete(itemId)
            promote()
        }
    }

    /** Cancel and delete are the same operation (Swift semantics): wipe files + row. */
    fun delete(itemId: String) = cancel(itemId)

    fun retry(itemId: String) {
        scope.launch {
            val row = repository.get(itemId) ?: return@launch
            fileManager.partialFile(itemId).delete()
            repository.upsert(
                row.copy(
                    status = DownloadStatus.QUEUED.wireName,
                    progress = 0.0,
                    downloadedBytes = 0,
                    errorMessage = null,
                ),
            )
            promote()
        }
    }

    fun retryAllFailed() {
        scope.launch {
            repository.all().filter { it.downloadStatus == DownloadStatus.FAILED }.forEach { retry(it.itemId) }
        }
    }

    fun deleteAll() {
        scope.launch {
            workManager.cancelAllWorkByTag(DownloadWorker.TAG)
            fileManager.deleteAll()
            repository.deleteAll()
        }
    }

    // MARK: - Scheduling

    /** Re-run any orphaned in-flight rows after a process restart. */
    private suspend fun recover() {
        repository.all()
            .filter { it.downloadStatus == DownloadStatus.PREPARING || it.downloadStatus == DownloadStatus.DOWNLOADING }
            .forEach { repository.updateStatus(it.itemId, DownloadStatus.QUEUED) }
        promote()
    }

    /** Called by a finishing worker so the freed slot is refilled. */
    suspend fun onWorkFinished() = promote()

    private suspend fun promote() {
        promoteMutex.withLock {
            val items = repository.all()
            val runningIds =
                items.filter {
                    it.downloadStatus == DownloadStatus.PREPARING || it.downloadStatus == DownloadStatus.DOWNLOADING
                }.map { it.itemId }.toSet()
            val toStart = DownloadPolicy.nextToStart(items, runningIds)
            for (id in toStart) {
                if (!StorageAccounting.hasRoomToDownload(fileManager.availableDiskSpace())) {
                    repository.updateStatus(id, DownloadStatus.FAILED, "Not enough disk space.")
                    continue
                }
                repository.updateStatus(id, DownloadStatus.PREPARING)
                enqueueWork(id)
            }
        }
    }

    private fun enqueueWork(itemId: String) {
        val request =
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(DownloadWorker.KEY_ITEM_ID to itemId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(DownloadWorker.TAG)
                .addTag(DownloadWorker.itemTag(itemId))
                .build()
        workManager.enqueueUniqueWork(DownloadWorker.uniqueName(itemId), ExistingWorkPolicy.KEEP, request)
    }

    // MARK: - Download execution (invoked by DownloadWorker)

    suspend fun performDownload(
        itemId: String,
        isStopped: () -> Boolean,
        onProgress: suspend (title: String, percent: Int) -> Unit = { _, _ -> },
    ): androidx.work.ListenableWorker.Result =
        withContext(Dispatchers.IO) {
            val row = repository.get(itemId) ?: return@withContext androidx.work.ListenableWorker.Result.success()
            val notifyTitle = row.displayTitle
            val server = client.currentServerUrl
            val token = client.currentAccessToken
            if (server == null || token == null) {
                fail(itemId, "Not signed in")
                return@withContext androidx.work.ListenableWorker.Result.failure()
            }
            val quality = row.downloadQuality
            val url = DownloadUrlBuilder.downloadUrl(server, itemId, client.currentDeviceId, quality)
            if (url == null) {
                fail(itemId, "Could not build download URL")
                return@withContext androidx.work.ListenableWorker.Result.failure()
            }

            val partial = fileManager.partialFile(itemId)
            val startOffset = if (partial.exists()) partial.length() else 0L
            val request =
                Request.Builder()
                    .url(url)
                    .header("X-Emby-Token", token)
                    .apply { if (startOffset > 0) header("Range", "bytes=$startOffset-") }
                    .build()

            try {
                repository.updateProgress(itemId, DownloadStatus.DOWNLOADING, row.progress, startOffset, row.totalBytes)
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        fail(itemId, "Server error ${response.code}")
                        return@withContext androidx.work.ListenableWorker.Result.failure()
                    }
                    val resumed = response.code == 206
                    val body =
                        response.body ?: run {
                            fail(itemId, "Empty response")
                            return@withContext androidx.work.ListenableWorker.Result.failure()
                        }
                    // Total = already-on-disk (if resumed) + reported remaining; -1 when unknown.
                    val reported = body.contentLength()
                    val total =
                        if (reported < 0) {
                            -1L
                        } else if (resumed) {
                            startOffset + reported
                        } else {
                            reported
                        }

                    val append = resumed && startOffset > 0
                    var written = if (append) startOffset else 0L
                    var lastPersist = 0L

                    body.byteStream().use { input ->
                        java.io.FileOutputStream(partial, append).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (isStopped()) {
                                    // Leave the partial file for a later Range resume.
                                    return@withContext androidx.work.ListenableWorker.Result.retry()
                                }
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                written += read
                                val now = System.currentTimeMillis()
                                if (now - lastPersist >= PROGRESS_PERSIST_MS) {
                                    lastPersist = now
                                    val fraction = if (total > 0) written.toDouble() / total.toDouble() else PROGRESS_UNKNOWN
                                    repository.updateProgress(itemId, DownloadStatus.DOWNLOADING, fraction, written, total.coerceAtLeast(0))
                                    onProgress(notifyTitle, if (fraction >= 0) (fraction * 100).toInt() else -1)
                                }
                            }
                        }
                    }

                    finalize(itemId, partial, quality)
                    onWorkFinished()
                    androidx.work.ListenableWorker.Result.success()
                }
            } catch (e: Exception) {
                if (isStopped()) {
                    androidx.work.ListenableWorker.Result.retry()
                } else {
                    fail(itemId, e.message ?: "Download failed")
                    androidx.work.ListenableWorker.Result.failure()
                }
            }
        }

    private suspend fun finalize(
        itemId: String,
        partial: File,
        quality: DownloadQuality,
    ) {
        val ext = if (quality == DownloadQuality.ORIGINAL) "mkv" else "mp4"
        val videoName = "video.$ext"
        val target = fileManager.videoFile(itemId, videoName)
        target.delete()
        partial.renameTo(target)

        downloadImages(itemId)
        val subtitles = downloadSubtitles(itemId)

        val size = fileManager.itemSize(itemId)
        val row = repository.get(itemId)
        if (row != null) {
            repository.upsert(
                row.copy(
                    status = DownloadStatus.COMPLETED.wireName,
                    progress = 1.0,
                    downloadedBytes = size,
                    totalBytes = size,
                    videoFileName = videoName,
                    subtitlesJson = DownloadedItemEntity.encodeSubtitles(subtitles),
                    posterFileName =
                        if (fileManager.imageFile(
                                itemId,
                                DownloadFileManager.POSTER_NAME,
                            ).exists()
                        ) {
                            DownloadFileManager.POSTER_NAME
                        } else {
                            row.posterFileName
                        },
                    backdropFileName =
                        if (fileManager.imageFile(
                                itemId,
                                DownloadFileManager.BACKDROP_NAME,
                            ).exists()
                        ) {
                            DownloadFileManager.BACKDROP_NAME
                        } else {
                            row.backdropFileName
                        },
                    dateCompleted = System.currentTimeMillis(),
                    errorMessage = null,
                ),
            )
        }
    }

    /** Best-effort poster/backdrop/series-poster fetch (Swift OfflineImageHelper). */
    private suspend fun downloadImages(itemId: String) {
        val token = client.currentAccessToken ?: return
        val row = repository.get(itemId)
        fetchImage(client.imageURL(itemId, "Primary", 400), token, fileManager.imageFile(itemId, DownloadFileManager.POSTER_NAME))
        fetchImage(client.imageURL(itemId, "Backdrop", 1280), token, fileManager.imageFile(itemId, DownloadFileManager.BACKDROP_NAME))
        if (row?.downloadItemType == dev.bitstorm.sashimi.core.model.ItemType.EPISODE) {
            row.seriesId?.let { seriesId ->
                fetchImage(
                    client.imageURL(seriesId, "Primary", 400),
                    token,
                    fileManager.imageFile(itemId, DownloadFileManager.SERIES_POSTER_NAME),
                )
            }
        }
    }

    /**
     * Fetches every external-deliverable **text** subtitle stream as WebVTT
     * alongside the video (Swift `downloadSubtitles`), storing each under the
     * item's `subtitles/` directory. Image-based tracks (PGS/VOBSUB/DVD) are
     * skipped — they can't be rendered as text and the VTT endpoint can't extract
     * them. Returns the persisted descriptors for the completed row.
     */
    private suspend fun downloadSubtitles(itemId: String): List<DownloadedSubtitle> {
        val server = client.currentServerUrl ?: return emptyList()
        val token = client.currentAccessToken ?: return emptyList()
        val info = runCatching { client.getPlaybackInfo(itemId) }.getOrNull() ?: return emptyList()
        val source = info.mediaSources?.firstOrNull() ?: return emptyList()

        val results = mutableListOf<DownloadedSubtitle>()
        for (stream in source.subtitleStreams) {
            val index = stream.index ?: continue
            if (!isTextSubtitle(stream.codec)) continue
            val language = stream.language ?: stream.displayTitle ?: "und"
            val url = DownloadUrlBuilder.subtitleUrl(server, itemId, index) ?: continue
            val fileName = "${index}_$language.vtt"
            val target = fileManager.subtitleFile(itemId, fileName)
            val ok = fetchImage(url, token, target)
            if (ok && target.length() > 0) {
                results.add(
                    DownloadedSubtitle(
                        subtitleIndex = index,
                        language = language,
                        displayTitle = stream.displayTitle ?: language,
                        fileName = fileName,
                    ),
                )
            }
        }
        return results
    }

    /** True for text-based subtitle codecs (renderable as VTT); false for image tracks. */
    private fun isTextSubtitle(codec: String?): Boolean {
        val c = codec?.lowercase() ?: return true // unknown → assume text (server will VTT it)
        return c !in IMAGE_SUBTITLE_CODECS
    }

    private fun fetchImage(
        url: String?,
        token: String,
        target: File,
    ): Boolean {
        url ?: return false
        return runCatching {
            val request = Request.Builder().url(url).header("X-Emby-Token", token).build()
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.byteStream()?.use { input ->
                        java.io.FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                    true
                } else {
                    false
                }
            }
        }.getOrDefault(false)
    }

    private suspend fun fail(
        itemId: String,
        message: String,
    ) {
        repository.updateStatus(itemId, DownloadStatus.FAILED, message)
        onWorkFinished()
    }

    /** Free space available to the app, for the Downloads storage bar. */
    fun availableDiskSpace(): Long = fileManager.availableDiskSpace()

    // MARK: - Offline playback bridge

    suspend fun localVideoFile(itemId: String): File? {
        val row = repository.get(itemId) ?: return null
        if (!row.isComplete) return null
        val name = row.videoFileName ?: return null
        val file = fileManager.videoFile(itemId, name)
        return file.takeIf { it.exists() }
    }

    suspend fun offlinePlaybackPositionTicks(itemId: String): Long? = repository.get(itemId)?.localPositionTicks?.takeIf { it > 0 }

    /** The stored download row for an item (for offline title/metadata reconstruction). */
    suspend fun downloadedItem(itemId: String): DownloadedItemEntity? = repository.get(itemId)

    fun savePlaybackPosition(
        itemId: String,
        positionTicks: Long,
    ) {
        scope.launch { repository.savePlaybackPosition(itemId, positionTicks) }
    }

    // MARK: - Pending progress sync

    suspend fun syncPendingProgress() {
        val pending = PendingProgressSync.itemsToSync(repository.all())
        for (item in pending) {
            val ok = runCatching { client.reportPlaybackStopped(item.itemId, item.localPositionTicks) }.isSuccess
            if (ok) repository.clearSyncFlag(item.itemId)
        }
    }

    /** Absolute local file for a downloaded subtitle, or null if missing. */
    suspend fun localSubtitleFile(
        itemId: String,
        fileName: String,
    ): File? = fileManager.subtitleFile(itemId, fileName).takeIf { it.exists() }

    companion object {
        private const val PROGRESS_PERSIST_MS = 1_500L

        /** Image-based subtitle codecs that can't be delivered as text VTT. */
        private val IMAGE_SUBTITLE_CODECS =
            setOf("pgssub", "hdmv_pgs_subtitle", "pgs", "dvbsub", "dvb_subtitle", "dvdsub", "dvd_subtitle", "vobsub", "xsub")

        /** Sentinel written to [DownloadedItemEntity.progress] for unknown-size streams. */
        const val PROGRESS_UNKNOWN = -1.0

        /** Set at construction so [DownloadWorker] (built by WorkManager) can reach it. */
        @Volatile
        var current: DownloadManager? = null
            private set
    }
}
