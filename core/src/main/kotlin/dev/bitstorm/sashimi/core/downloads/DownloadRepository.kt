package dev.bitstorm.sashimi.core.downloads

import kotlinx.coroutines.flow.Flow

/**
 * Thin persistence façade over [DownloadDao]. Keeps the [DownloadManager]
 * orchestration free of Room specifics and gives the UI one reactive stream.
 * Every single-row operation takes a [DownloadKey]: item ids repeat across
 * servers.
 */
class DownloadRepository(
    private val dao: DownloadDao,
) {
    val downloads: Flow<List<DownloadedItemEntity>> = dao.observeAll()

    suspend fun all(): List<DownloadedItemEntity> = dao.getAll()

    suspend fun get(key: DownloadKey): DownloadedItemEntity? = dao.get(key.serverId, key.itemId)

    suspend fun upsert(item: DownloadedItemEntity) = dao.upsert(item)

    suspend fun delete(key: DownloadKey) = dao.delete(key.serverId, key.itemId)

    suspend fun deleteAll() = dao.deleteAll()

    suspend fun updateProgress(
        key: DownloadKey,
        status: DownloadStatus,
        progress: Double,
        downloadedBytes: Long,
        totalBytes: Long,
    ) = dao.updateProgress(key.serverId, key.itemId, status.wireName, progress, downloadedBytes, totalBytes)

    suspend fun updateStatus(
        key: DownloadKey,
        status: DownloadStatus,
        error: String? = null,
    ) = dao.updateStatus(key.serverId, key.itemId, status.wireName, error)

    suspend fun savePlaybackPosition(
        key: DownloadKey,
        ticks: Long,
    ) = dao.savePlaybackPosition(key.serverId, key.itemId, ticks)

    suspend fun clearSyncFlag(key: DownloadKey) = dao.clearSyncFlag(key.serverId, key.itemId)
}
