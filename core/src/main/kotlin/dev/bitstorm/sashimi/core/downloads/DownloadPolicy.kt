package dev.bitstorm.sashimi.core.downloads

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.util.runCatchingCancellable

/**
 * Pure download-queue decisions, factored out of the Android [DownloadManager]
 * orchestration so the semantics are unit-testable without WorkManager or Room:
 * concurrency capping, retry transitions, and pending-sync selection.
 */
object DownloadPolicy {
    /** Max downloads running at once (the Android bump from the Swift serial=1). */
    const val MAX_CONCURRENT = 2

    /** Minimum free space required to start a download (Swift 500 MB floor). */
    const val MIN_FREE_BYTES = 500L * 1024 * 1024

    /**
     * The next item ids to promote to running, oldest-queued first, filling the
     * remaining concurrency slots. An item already active (queued-but-started,
     * preparing, downloading) counts against the cap.
     */
    fun nextToStart(
        items: List<DownloadedItemEntity>,
        runningIds: Set<String>,
        maxConcurrent: Int = MAX_CONCURRENT,
    ): List<String> {
        val activeCount = items.count { it.itemId in runningIds }
        val slots = (maxConcurrent - activeCount).coerceAtLeast(0)
        if (slots == 0) return emptyList()
        return items
            .filter { it.downloadStatus == DownloadStatus.QUEUED && it.itemId !in runningIds }
            .sortedBy { it.dateAdded }
            .take(slots)
            .map { it.itemId }
    }

    /**
     * Whether a fresh enqueue should be rejected as a duplicate. Matches the
     * Swift `insertQueuedRecord` guard: an existing completed/active record wins;
     * a stale failed record is replaced (returns false → allow, caller overwrites).
     */
    fun isDuplicate(existing: DownloadedItemEntity?): Boolean {
        if (existing == null) return false
        return existing.isComplete || existing.isActive
    }

    /**
     * Whether re-enqueuing over an existing (non-duplicate, i.e. FAILED)
     * row must delete its leftover partial file first.
     *
     * The partial (`video.part`) is quality-independent, so a re-enqueue at a
     * DIFFERENT quality would Range-resume by appending a differently-encoded
     * stream onto the old bytes and finalize a COMPLETED-but-corrupt file. When
     * the quality differs we drop the partial and restart from byte 0; a
     * same-quality re-enqueue keeps the partial so its resume is safe (identical
     * encoding). No existing row (fresh download) never has a stale partial.
     */
    fun shouldDeletePartialOnReenqueue(
        existing: DownloadedItemEntity?,
        newQuality: DownloadQuality,
    ): Boolean {
        existing ?: return false
        return existing.downloadQuality != newQuality
    }
}

/**
 * Storage accounting math, ported from `DownloadFileManager`. Pure so the sizing
 * arithmetic is testable; the actual disk enumeration and free-space probe live
 * in the Android [DownloadFileManager].
 */
object StorageAccounting {
    /**
     * Bytes attributable to a set of downloads: the known [DownloadedItemEntity.totalBytes]
     * when set (completed), else the bytes downloaded so far (in-flight). Mirrors
     * the Swift `formattedSize` fallback.
     */
    fun bytesUsed(items: List<DownloadedItemEntity>): Long = items.sumOf { if (it.totalBytes > 0) it.totalBytes else it.downloadedBytes }

    /** True when [freeBytes] leaves room to start another download. */
    fun hasRoomToDownload(freeBytes: Long): Boolean = freeBytes >= DownloadPolicy.MIN_FREE_BYTES
}

/**
 * Which downloads still owe the server a progress report, ported from the Swift
 * `syncPendingProgress` / `fetchPendingSync`. Pure decision; the actual POST and
 * flag-clear live in [DownloadManager].
 */
object PendingProgressSync {
    fun itemsToSync(items: List<DownloadedItemEntity>): List<DownloadedItemEntity> = items.filter { it.pendingProgressSync }

    /**
     * Reports each pending row's stashed position to the server that row was
     * downloaded from, and returns the item ids that were reported (the caller
     * clears their flags). A row whose server can't be resolved (removed, or
     * signed out) or whose report fails stays pending for a later attempt,
     * rather than being posted to whichever server happens to be active.
     */
    suspend fun <C : Any> sync(
        items: List<DownloadedItemEntity>,
        clientFor: (serverId: String?) -> C?,
        report: suspend (client: C, itemId: String, positionTicks: Long) -> Unit,
    ): List<String> =
        itemsToSync(items).mapNotNull { row ->
            val client = clientFor(row.serverId) ?: return@mapNotNull null
            val result = runCatchingCancellable { report(client, row.itemId, row.localPositionTicks) }
            if (result.isSuccess) row.itemId else null
        }
}

/** Builds download rows. Pure, so what a new download records is unit-testable. */
object DownloadRecords {
    /**
     * A freshly queued row for [item], stamped with the saved server it comes
     * from so the download and every later report go to that server.
     */
    fun queued(
        item: BaseItemDto,
        quality: DownloadQuality,
        serverId: String?,
        now: Long,
    ): DownloadedItemEntity =
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
            dateAdded = now,
            serverId = serverId,
        )
}
