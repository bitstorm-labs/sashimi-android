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
import dev.bitstorm.sashimi.core.playback.SubtitleCodecs
import dev.bitstorm.sashimi.core.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
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
    /**
     * The client for a row's [DownloadedItemEntity.serverId]: a download always
     * talks to the server it came from, never to whichever server is active.
     * Null when that server is gone or signed out.
     */
    private val clientFor: (serverId: String?) -> JellyfinClient?,
    private val networkMonitor: NetworkMonitor,
    /**
     * Emits true once a session is restored. The first sync used to fire
     * eagerly from init, which runs BEFORE SessionManager.restoreSession()
     * completes, so JellyfinClient was still unconfigured and every attempt
     * failed. Nothing retried it, because the only other trigger is a
     * TRANSITION on isOnline and a device that is already online at process
     * start never emits one.
     */
    private val authenticated: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    /** Which transcoded downloads the fixed tier URL produced; see [TierEncodeLedger]. */
    private val tierEncodes: TierEncodeLedger,
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

    /**
     * The video body itself gets a much longer read timeout. A transcoded
     * download's first byte waits for the server's ffmpeg to start, and a
     * software 4K HEVC encode on a loaded server can take over a minute to get
     * going (and can run slower than the network afterwards), so the 60 s
     * timeout failed downloads that would have completed.
     */
    private val videoHttp = http.newBuilder().readTimeout(VIDEO_READ_TIMEOUT_MINUTES, TimeUnit.MINUTES).build()

    /** Reactive snapshot of every download row for the UI. */
    val downloads: StateFlow<List<DownloadedItemEntity>> =
        repository.downloads.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _live = MutableStateFlow<Map<DownloadKey, LiveTransfer>>(emptyMap())

    /** Transfer rate and retry/restart state for in-flight downloads (memory only). */
    val live: StateFlow<Map<DownloadKey, LiveTransfer>> = _live.asStateFlow()

    private fun setLive(
        key: DownloadKey,
        transfer: LiveTransfer?,
    ) {
        _live.update { if (transfer == null) it - key else it + (key to transfer) }
    }

    /**
     * Completed High / Medium / Low downloads made before the tier URL sent a
     * real video bitrate: bad files the UI offers to re-download.
     */
    val needingRedownload: StateFlow<Set<DownloadKey>> =
        combine(repository.downloads, tierEncodes.keys) { rows, fixed ->
            rows.filter { DownloadPolicy.needsRedownload(it, fixed) }.map { it.key }.toSet()
        }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    init {
        current = this
        scope.launch { recover() }
        // Sync stashed offline progress once a session actually exists, and
        // again whenever connectivity returns.
        scope.launch {
            authenticated.first { it }
            syncPendingProgress()
        }
        scope.launch {
            networkMonitor.isOnline.drop(1).collect { online ->
                if (online) {
                    syncPendingProgress()
                    promote()
                }
            }
        }
    }

    /** Every download row, for callers that need a one-shot read rather than the flow. */
    suspend fun allDownloads(): List<DownloadedItemEntity> = repository.all()

    /** Bytes actually consumed on disk by downloads, independent of the database. */
    fun bytesOnDisk(): Long = runCatching { fileManager.totalSize() }.getOrDefault(0)

    /**
     * Sync anything stashed while offline. Called on app foreground as well as
     * from init, because isOnline only fires on a TRANSITION: a device that was
     * already online when the process started never emits, so foregrounding is
     * the reliable second chance.
     */
    fun syncNow() {
        scope.launch {
            if (authenticated.value) syncPendingProgress()
        }
    }

    // MARK: - Enqueue

    /** Queues [item] from the saved server [serverId] (the active one, or the title's own). */
    fun enqueueDownload(
        item: BaseItemDto,
        quality: DownloadQuality,
        serverId: String?,
    ) {
        scope.launch {
            insertQueued(item, quality, serverId)
            promote()
        }
    }

    /**
     * Queues every item in [items] (a season, a whole series) at one quality,
     * skipping any already downloaded, queued or downloading.
     */
    fun enqueueDownloads(
        items: List<BaseItemDto>,
        quality: DownloadQuality,
        serverId: String?,
    ) {
        scope.launch {
            items.forEach { insertQueued(it, quality, serverId) }
            promote()
        }
    }

    private suspend fun insertQueued(
        item: BaseItemDto,
        quality: DownloadQuality,
        serverId: String?,
    ) {
        val key = DownloadKey.of(serverId, item.id)
        val existing = repository.get(key)
        if (DownloadPolicy.isDuplicate(existing)) return
        // A re-enqueue at a different quality must not resume onto the old
        // partial (different encoding) — drop it so the download restarts clean.
        if (DownloadPolicy.shouldDeletePartialOnReenqueue(existing, quality)) {
            fileManager.partialFile(key).delete()
        }
        repository.upsert(DownloadRecords.queued(item, quality, serverId, now = System.currentTimeMillis()))
    }

    // MARK: - Cancel / delete / retry

    fun cancel(key: DownloadKey) {
        scope.launch {
            workManager.cancelUniqueWork(DownloadWorker.uniqueName(key))
            setLive(key, null)
            fileManager.deleteItemDirectory(key)
            repository.delete(key)
            tierEncodes.unmark(key)
            // A download whose schema-3 directory could not be moved still owns
            // it; drop it too, unless another server's row has the same item id.
            if (repository.all().none { it.itemId == key.itemId }) fileManager.deleteLegacyItemDirectory(key.itemId)
            promote()
        }
    }

    /** Cancel and delete are the same operation (Swift semantics): wipe files + row. */
    fun delete(key: DownloadKey) = cancel(key)

    fun retry(key: DownloadKey) {
        scope.launch {
            val row = repository.get(key) ?: return@launch
            fileManager.partialFile(key).delete()
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

    /**
     * Fetches a pre-fix transcoded download again at the same quality. The old
     * file stays on disk until the new one replaces it in [finalize], but the
     * row is no longer COMPLETED, so it is not playable in the meantime.
     */
    fun redownload(key: DownloadKey) {
        scope.launch {
            val row = repository.get(key) ?: return@launch
            if (DownloadPolicy.needsRedownload(row, tierEncodes.keys.value)) retry(key)
        }
    }

    fun redownloadAll() {
        needingRedownload.value.forEach(::redownload)
    }

    fun retryAllFailed() {
        scope.launch {
            repository.all().filter { it.downloadStatus == DownloadStatus.FAILED }.forEach { retry(it.key) }
        }
    }

    fun deleteAll() {
        scope.launch {
            workManager.cancelAllWorkByTag(DownloadWorker.TAG)
            fileManager.deleteAll()
            repository.deleteAll()
            tierEncodes.clear()
        }
    }

    // MARK: - Scheduling

    /**
     * Re-run any orphaned in-flight rows after a process restart, bring
     * schema-3 directories into the current layout, then sweep orphans.
     */
    private suspend fun recover() {
        val rows = repository.all()
        rows.filter { it.isActive }.forEach {
            // Work enqueued before #86 is named by item id alone. Cancel it so it
            // cannot run beside the re-keyed work promote() enqueues below.
            workManager.cancelUniqueWork(DownloadWorker.legacyUniqueName(it.itemId))
        }
        rows
            .filter { it.downloadStatus == DownloadStatus.PREPARING || it.downloadStatus == DownloadStatus.DOWNLOADING }
            .forEach { repository.updateStatus(it.key, DownloadStatus.QUEUED) }
        relocateLegacyDirectories(rows)
        reconcileOrphanedFiles()
        promote()
    }

    /**
     * Moves schema-3 `downloads/{itemId}/` directories to
     * `downloads/servers/{serverId}/{itemId}/`. Idempotent: once moved there is
     * nothing left to move. A failed move leaves the directory where it was,
     * and [DownloadFileManager.localFile] still reads it from there.
     */
    private fun relocateLegacyDirectories(rows: List<DownloadedItemEntity>) {
        runCatching {
            val legacy = fileManager.legacyDirectoriesOnDisk()
            if (legacy.isEmpty()) return
            fileManager.relocate(DownloadLayout.relocations(rows, legacy, fileManager.itemPathsOnDisk()))
        }
    }

    /**
     * Delete media on disk that no longer has a database row.
     *
     * The database is built with fallbackToDestructiveMigration, so a schema
     * bump drops every row while leaving filesDir/downloads/ untouched. Nothing
     * reconciled the two, so the user's offline library silently vanished from
     * the UI while the bytes stayed on disk -- and the Downloads tab returns its
     * empty state BEFORE rendering "Delete All", so there was no way to reclaim
     * the space from inside the app.
     *
     * Deleting is the right resolution rather than adopting the directories
     * back: without a row there is no title, no runtime and no source metadata,
     * so an adopted entry could not be presented or played.
     *
     * Runs after [relocateLegacyDirectories], against both layouts; see
     * [DownloadLayout.orphans] for what counts as owned.
     */
    private suspend fun reconcileOrphanedFiles() {
        runCatchingCancellable {
            // Disk first, rows second: a download queued meanwhile writes its row
            // before any file, so every directory listed here has its row in the
            // read below and cannot be mistaken for an orphan.
            val legacy = fileManager.legacyDirectoriesOnDisk()
            val current = fileManager.itemPathsOnDisk()
            DownloadLayout.orphans(repository.all(), legacy, current).forEach(fileManager::deleteRelative)
        }
    }

    /** Called by a finishing worker so the freed slot is refilled. */
    suspend fun onWorkFinished() = promote()

    private suspend fun promote() {
        promoteMutex.withLock {
            val items = repository.all()
            val running =
                items.filter {
                    it.downloadStatus == DownloadStatus.PREPARING || it.downloadStatus == DownloadStatus.DOWNLOADING
                }.map { it.key }.toSet()
            val toStart = DownloadPolicy.nextToStart(items, running)
            for (key in toStart) {
                if (!StorageAccounting.hasRoomToDownload(fileManager.availableDiskSpace())) {
                    repository.updateStatus(key, DownloadStatus.FAILED, "Not enough disk space.")
                    continue
                }
                repository.updateStatus(key, DownloadStatus.PREPARING)
                enqueueWork(key)
            }
        }
    }

    private fun enqueueWork(key: DownloadKey) {
        val request =
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(DownloadWorker.KEY_ITEM_ID to key.itemId, DownloadWorker.KEY_SERVER_ID to key.serverId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(DownloadWorker.TAG)
                .addTag(DownloadWorker.itemTag(key))
                .build()
        workManager.enqueueUniqueWork(DownloadWorker.uniqueName(key), ExistingWorkPolicy.KEEP, request)
    }

    // MARK: - Download execution (invoked by DownloadWorker)

    suspend fun performDownload(
        key: DownloadKey,
        isStopped: () -> Boolean,
        /** WorkManager's run attempt: 0 for the first run, more after a retry. */
        attempt: Int = 0,
        onProgress: suspend (title: String, percent: Int) -> Unit = { _, _ -> },
    ): androidx.work.ListenableWorker.Result =
        withContext(Dispatchers.IO) {
            val itemId = key.itemId
            val row = repository.get(key) ?: return@withContext androidx.work.ListenableWorker.Result.success()
            val notifyTitle = row.displayTitle
            val client = clientFor(row.serverId)
            if (client == null) {
                fail(key, "This download's server is signed out. Reconnect it in Settings, then retry.")
                return@withContext androidx.work.ListenableWorker.Result.failure()
            }
            val quality = row.downloadQuality
            val spec = DownloadUrlBuilder.requestFor(client, itemId, quality)
            if (spec == null) {
                fail(key, if (client.isConfigured) "Could not build download URL" else "Not signed in")
                return@withContext androidx.work.ListenableWorker.Result.failure()
            }
            val partial = fileManager.partialFile(key)
            // A live transcode cannot be resumed by byte range: Jellyfin answers
            // a Range request on it with a fresh 200 from byte 0. Say so instead
            // of letting the bar silently jump back, and do not send a Range the
            // server ignores. Originals (a static file) do resume.
            val transcoded = quality.encode != null
            val restarting = transcoded && partial.exists() && partial.length() > 0
            if (restarting) partial.delete()
            val startOffset = if (partial.exists()) partial.length() else 0L
            val request = DownloadRequests.get(spec.url, spec.authorization, resumeFrom = startOffset)
            setLive(
                key,
                LiveTransfer(
                    phase =
                        when {
                            restarting -> DownloadPhase.RESTARTING
                            attempt > 0 -> DownloadPhase.RETRYING
                            else -> null
                        },
                ),
            )
            val estimatedTotal =
                if (transcoded) {
                    val sourceVideoBitrate =
                        runCatchingCancellable { client.getPlaybackInfo(itemId) }.getOrNull()
                            ?.mediaSources?.firstOrNull()?.mediaStreams?.firstOrNull { it.type == "Video" }?.bitRate?.toLong()
                    DownloadProgress.estimatedTotalBytes(quality, row.runTimeTicks, sourceVideoBitrate)
                } else {
                    null
                }
            val rate = TransferRate()

            try {
                repository.updateProgress(
                    key,
                    DownloadStatus.DOWNLOADING,
                    if (restarting) 0.0 else row.progress,
                    startOffset,
                    if (restarting) estimatedTotal ?: 0 else row.totalBytes,
                )
                videoHttp.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        fail(key, "Server error ${response.code}")
                        return@withContext androidx.work.ListenableWorker.Result.failure()
                    }
                    val resumed = response.code == 206
                    val body =
                        response.body ?: run {
                            fail(key, "Empty response")
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
                                    // A transcode has no Content-Length: estimate
                                    // from the tier and runtime rather than leave
                                    // the bar indeterminate for the whole encode.
                                    val snapshot = DownloadProgress.snapshot(written, total, estimatedTotal)
                                    val fraction = snapshot?.fraction ?: PROGRESS_UNKNOWN
                                    repository.updateProgress(key, DownloadStatus.DOWNLOADING, fraction, written, snapshot?.totalBytes ?: 0)
                                    val thisAttempt = written - (if (append) startOffset else 0)
                                    val phase = _live.value[key]?.phase?.takeIf { thisAttempt < PHASE_CLEAR_BYTES }
                                    setLive(key, LiveTransfer(bytesPerSecond = rate.sample(written, now), phase = phase))
                                    onProgress(notifyTitle, if (fraction >= 0) (fraction * 100).toInt() else -1)
                                }
                            }
                        }
                    }

                    // Recorded only when every byte came from this request. A
                    // resumed transcode (not something Jellyfin does, but not
                    // ours to assume) could begin with bytes the old URL made.
                    if (quality != DownloadQuality.ORIGINAL && !append) tierEncodes.mark(key)
                    setLive(key, null)
                    finalize(key, partial, client)
                    onWorkFinished()
                    androidx.work.ListenableWorker.Result.success()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                when {
                    isStopped() -> androidx.work.ListenableWorker.Result.retry()
                    // A dropped connection or a timeout mid-download is worth
                    // another go before it becomes a red "failed" row; the row
                    // stays in flight (and keeps its slot) while WorkManager
                    // backs off, and reads "Retrying…" when it runs again.
                    e is java.io.IOException && attempt < MAX_NETWORK_RETRIES -> {
                        setLive(key, LiveTransfer(phase = DownloadPhase.RETRYING))
                        repository.updateStatus(key, DownloadStatus.PREPARING)
                        androidx.work.ListenableWorker.Result.retry()
                    }
                    else -> {
                        setLive(key, null)
                        fail(key, e.message ?: "Download failed")
                        androidx.work.ListenableWorker.Result.failure()
                    }
                }
            }
        }

    private suspend fun finalize(
        key: DownloadKey,
        partial: File,
        client: JellyfinClient,
    ) {
        // Always mp4: the tiers transcode to it, and Original is only admitted
        // for mp4/m4v/mov sources (DeviceMediaCompatibility), never mkv.
        val videoName = "video.mp4"
        val target = fileManager.videoFile(key, videoName)
        target.delete()
        partial.renameTo(target)

        downloadImages(key, client)
        val subtitles = downloadSubtitles(key, client)

        val size = fileManager.itemSize(key)
        val row = repository.get(key)
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
                                key,
                                DownloadFileManager.POSTER_NAME,
                            ).exists()
                        ) {
                            DownloadFileManager.POSTER_NAME
                        } else {
                            row.posterFileName
                        },
                    backdropFileName =
                        if (fileManager.imageFile(
                                key,
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
    private suspend fun downloadImages(
        key: DownloadKey,
        client: JellyfinClient,
    ) {
        val itemId = key.itemId
        val token = client.currentAuthorization ?: return
        val row = repository.get(key)
        fetchImage(client.imageURL(itemId, "Primary", 400), token, fileManager.imageFile(key, DownloadFileManager.POSTER_NAME))
        fetchImage(client.imageURL(itemId, "Backdrop", 1280), token, fileManager.imageFile(key, DownloadFileManager.BACKDROP_NAME))
        if (row?.downloadItemType == dev.bitstorm.sashimi.core.model.ItemType.EPISODE) {
            row.seriesId?.let { seriesId ->
                fetchImage(
                    client.imageURL(seriesId, "Primary", 400),
                    token,
                    fileManager.imageFile(key, DownloadFileManager.SERIES_POSTER_NAME),
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
    private suspend fun downloadSubtitles(
        key: DownloadKey,
        client: JellyfinClient,
    ): List<DownloadedSubtitle> {
        val itemId = key.itemId
        val server = client.currentServerUrl ?: return emptyList()
        val token = client.currentAuthorization ?: return emptyList()
        val info = runCatching { client.getPlaybackInfo(itemId) }.getOrNull() ?: return emptyList()
        val source = info.mediaSources?.firstOrNull() ?: return emptyList()

        val results = mutableListOf<DownloadedSubtitle>()
        for (stream in source.subtitleStreams) {
            val index = stream.index ?: continue
            if (!isTextSubtitle(stream.codec)) continue
            val language = stream.language ?: stream.displayTitle ?: "und"
            val url = DownloadUrlBuilder.subtitleUrl(server, itemId, index) ?: continue
            val fileName = "${index}_$language.vtt"
            val target = fileManager.subtitleFile(key, fileName)
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
    private fun isTextSubtitle(codec: String?): Boolean = SubtitleCodecs.isText(codec)

    private fun fetchImage(
        url: String?,
        authorization: String,
        target: File,
    ): Boolean {
        url ?: return false
        return runCatching {
            val request = DownloadRequests.get(url, authorization)
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
        key: DownloadKey,
        message: String,
    ) {
        setLive(key, null)
        repository.updateStatus(key, DownloadStatus.FAILED, message)
        onWorkFinished()
    }

    /** Free space available to the app, for the Downloads storage bar. */
    fun availableDiskSpace(): Long = fileManager.availableDiskSpace()

    // MARK: - Offline playback bridge

    /**
     * The key of the completed download to play for [itemId], resolved by
     * [DownloadLookup.playable]; null when there is none or its video is gone.
     */
    suspend fun playableDownload(
        itemId: String,
        serverId: String?,
        activeServerId: String?,
    ): DownloadKey? {
        val row = DownloadLookup.playable(repository.all(), itemId, serverId, activeServerId) ?: return null
        return row.key.takeIf { localVideoFile(it) != null }
    }

    suspend fun localVideoFile(key: DownloadKey): File? {
        val row = repository.get(key) ?: return null
        if (!row.isComplete) return null
        val name = row.videoFileName ?: return null
        return fileManager.localFile(key, name)
    }

    suspend fun offlinePlaybackPositionTicks(key: DownloadKey): Long? = repository.get(key)?.localPositionTicks?.takeIf { it > 0 }

    /** The stored download row for an item (for offline title/metadata reconstruction). */
    suspend fun downloadedItem(key: DownloadKey): DownloadedItemEntity? = repository.get(key)

    fun savePlaybackPosition(
        key: DownloadKey,
        positionTicks: Long,
    ) {
        scope.launch { repository.savePlaybackPosition(key, positionTicks) }
    }

    /**
     * Saves the position and, when a session exists, posts it to the server
     * now rather than at the next foreground. Used when a downloaded item is
     * closed: watching a download online used to leave every other client's
     * Continue Watching stale until this app was next resumed.
     */
    fun savePlaybackPositionAndSync(
        key: DownloadKey,
        positionTicks: Long,
    ) {
        scope.launch {
            repository.savePlaybackPosition(key, positionTicks)
            if (authenticated.value && networkMonitor.isOnline.value) syncPendingProgress()
        }
    }

    // MARK: - Pending progress sync

    /** Posts stashed offline positions, each to the server its download came from. */
    suspend fun syncPendingProgress() {
        val synced =
            PendingProgressSync.sync(repository.all(), clientFor) { client, itemId, ticks ->
                client.reportPlaybackStopped(itemId, ticks)
            }
        synced.forEach { repository.clearSyncFlag(it) }
    }

    /** Absolute local file for a downloaded subtitle, or null if missing. */
    suspend fun localSubtitleFile(
        key: DownloadKey,
        fileName: String,
    ): File? = fileManager.localFile(key, "${DownloadFileManager.SUBTITLES_DIR}/$fileName")

    companion object {
        private const val PROGRESS_PERSIST_MS = 1_500L

        /** See [videoHttp]. */
        private const val VIDEO_READ_TIMEOUT_MINUTES = 5L

        /** Network failures retried (with WorkManager's backoff) before a download is marked failed. */
        private const val MAX_NETWORK_RETRIES = 3

        /** A "Retrying" / "Restarting" note stays up until this much has arrived on the new attempt. */
        private const val PHASE_CLEAR_BYTES = 4L * 1024 * 1024

        /** Sentinel written to [DownloadedItemEntity.progress] for unknown-size streams. */
        const val PROGRESS_UNKNOWN = -1.0

        /** Set at construction so [DownloadWorker] (built by WorkManager) can reach it. */
        @Volatile
        var current: DownloadManager? = null
            private set
    }
}
