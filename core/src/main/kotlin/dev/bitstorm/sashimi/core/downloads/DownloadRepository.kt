package dev.bitstorm.sashimi.core.downloads

import kotlinx.coroutines.flow.Flow

/**
 * Thin persistence façade over [DownloadDao]. Keeps the [DownloadManager]
 * orchestration free of Room specifics and gives the UI one reactive stream.
 */
class DownloadRepository(
    private val dao: DownloadDao,
) {
    val downloads: Flow<List<DownloadedItemEntity>> = dao.observeAll()

    suspend fun all(): List<DownloadedItemEntity> = dao.getAll()

    suspend fun get(itemId: String): DownloadedItemEntity? = dao.getById(itemId)

    suspend fun upsert(item: DownloadedItemEntity) = dao.upsert(item)

    suspend fun delete(itemId: String) = dao.deleteById(itemId)

    suspend fun deleteAll() = dao.deleteAll()

    suspend fun updateProgress(
        itemId: String,
        status: DownloadStatus,
        progress: Double,
        downloadedBytes: Long,
        totalBytes: Long,
    ) = dao.updateProgress(itemId, status.wireName, progress, downloadedBytes, totalBytes)

    suspend fun updateStatus(
        itemId: String,
        status: DownloadStatus,
        error: String? = null,
    ) = dao.updateStatus(itemId, status.wireName, error)

    suspend fun savePlaybackPosition(
        itemId: String,
        ticks: Long,
    ) = dao.savePlaybackPosition(itemId, ticks)

    suspend fun clearSyncFlag(itemId: String) = dao.clearSyncFlag(itemId)
}
