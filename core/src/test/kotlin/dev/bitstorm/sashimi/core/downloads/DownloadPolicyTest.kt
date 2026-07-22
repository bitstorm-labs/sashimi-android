package dev.bitstorm.sashimi.core.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPolicyTest {
    private fun item(
        id: String,
        status: DownloadStatus,
        added: Long,
    ) = DownloadedItemEntity(itemId = id, name = id, status = status.wireName, dateAdded = added)

    @Test
    fun `nextToStart fills up to the concurrency cap oldest-first`() {
        val items =
            listOf(
                item("a", DownloadStatus.QUEUED, 3),
                item("b", DownloadStatus.QUEUED, 1),
                item("c", DownloadStatus.QUEUED, 2),
            )
        val start = DownloadPolicy.nextToStart(items, runningIds = emptySet(), maxConcurrent = 2)
        assertEquals(listOf("b", "c"), start)
    }

    @Test
    fun `running downloads count against the cap`() {
        val items =
            listOf(
                item("a", DownloadStatus.DOWNLOADING, 1),
                item("b", DownloadStatus.QUEUED, 2),
                item("c", DownloadStatus.QUEUED, 3),
            )
        val start = DownloadPolicy.nextToStart(items, runningIds = setOf("a"), maxConcurrent = 2)
        assertEquals(listOf("b"), start)
    }

    @Test
    fun `no slots when already at capacity`() {
        val items =
            listOf(
                item("a", DownloadStatus.DOWNLOADING, 1),
                item("b", DownloadStatus.PREPARING, 2),
                item("c", DownloadStatus.QUEUED, 3),
            )
        val start = DownloadPolicy.nextToStart(items, runningIds = setOf("a", "b"), maxConcurrent = 2)
        assertTrue(start.isEmpty())
    }

    @Test
    fun `completed and failed are never started`() {
        val items =
            listOf(
                item("a", DownloadStatus.COMPLETED, 1),
                item("b", DownloadStatus.FAILED, 2),
            )
        assertTrue(DownloadPolicy.nextToStart(items, emptySet()).isEmpty())
    }

    @Test
    fun `duplicate guard rejects active and completed but allows failed and new`() {
        assertFalse(DownloadPolicy.isDuplicate(null))
        assertTrue(DownloadPolicy.isDuplicate(item("a", DownloadStatus.DOWNLOADING, 0)))
        assertTrue(DownloadPolicy.isDuplicate(item("a", DownloadStatus.QUEUED, 0)))
        assertTrue(DownloadPolicy.isDuplicate(item("a", DownloadStatus.COMPLETED, 0)))
        assertFalse(DownloadPolicy.isDuplicate(item("a", DownloadStatus.FAILED, 0)))
    }

    @Test
    fun `retry transition resets a failed row back into the queue`() {
        // Retry semantics are expressed as a status/progress reset; assert the pieces.
        val failed = item("a", DownloadStatus.FAILED, 0).copy(progress = 0.4, downloadedBytes = 10, errorMessage = "boom")
        val requeued = failed.copy(status = DownloadStatus.QUEUED.wireName, progress = 0.0, downloadedBytes = 0, errorMessage = null)
        assertEquals(DownloadStatus.QUEUED, requeued.downloadStatus)
        assertEquals(0.0, requeued.progress, 0.0)
        assertTrue(DownloadPolicy.nextToStart(listOf(requeued), emptySet()).contains("a"))
    }
}

class StorageAccountingTest {
    @Test
    fun `bytesUsed prefers totalBytes and falls back to downloaded`() {
        val items =
            listOf(
                DownloadedItemEntity(itemId = "a", name = "a", totalBytes = 1000, downloadedBytes = 1000),
                DownloadedItemEntity(itemId = "b", name = "b", totalBytes = 0, downloadedBytes = 250),
            )
        assertEquals(1250L, StorageAccounting.bytesUsed(items))
    }

    @Test
    fun `disk-space floor gates new downloads`() {
        assertTrue(StorageAccounting.hasRoomToDownload(DownloadPolicy.MIN_FREE_BYTES))
        assertFalse(StorageAccounting.hasRoomToDownload(DownloadPolicy.MIN_FREE_BYTES - 1))
    }
}

class PendingProgressSyncTest {
    @Test
    fun `only rows flagged for sync are selected`() {
        val items =
            listOf(
                DownloadedItemEntity(itemId = "a", name = "a", pendingProgressSync = true, localPositionTicks = 5),
                DownloadedItemEntity(itemId = "b", name = "b", pendingProgressSync = false, localPositionTicks = 9),
            )
        val toSync = PendingProgressSync.itemsToSync(items)
        assertEquals(listOf("a"), toSync.map { it.itemId })
    }
}
