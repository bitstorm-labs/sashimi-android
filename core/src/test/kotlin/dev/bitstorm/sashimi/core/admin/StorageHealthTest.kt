package dev.bitstorm.sashimi.core.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageHealthTest {
    private fun folder(
        used: Long,
        free: Long,
        device: String = "/d",
    ) = FolderStorageDto(freeSpace = free, usedSpace = used, deviceId = device)

    @Test
    fun `warning is strictly above 85 percent full`() {
        assertFalse(StorageHealth.isWarning(0.85))
        assertTrue(StorageHealth.isWarning(0.851))
        assertFalse(StorageHealth.isWarning(0.5))
        assertFalse(StorageHealth.isWarning(null))
    }

    @Test
    fun `fraction is used over used plus free`() {
        val row = StorageRow("x", usedBytes = 90, totalBytes = 100)
        assertEquals(0.9, row.fraction!!, 1e-9)
        assertTrue(row.isWarning)
    }

    @Test
    fun `a zero-size filesystem gives no row and no warning`() {
        assertEquals(null, StorageRow("x", 0, 0).fraction)
        assertFalse(StorageRow("x", 0, 0).isWarning)
        val s = SystemStorageDto(transcodingTempFolder = folder(0, 0))
        assertEquals(emptyList<StorageRow>(), StorageHealth.rows(s))
    }

    @Test
    fun `rows are one per filesystem, libraries labelled by name, cache labelled Transcode and cache`() {
        val media = folder(used = 44_351, free = 4_641, device = "/media")
        val cache = folder(used = 325, free = 186, device = "/cache")
        val config = folder(used = 325, free = 186, device = "/config")
        val s =
            SystemStorageDto(
                cacheFolder = cache,
                transcodingTempFolder = cache,
                libraries =
                    listOf(
                        LibraryStorageDto("Movies", listOf(media, media)),
                        LibraryStorageDto("TV", listOf(media)),
                        LibraryStorageDto("Collections", listOf(config)),
                    ),
            )
        val rows = StorageHealth.rows(s)
        assertEquals(listOf("Movies, TV", "Transcode & cache"), rows.map { it.label })
        assertTrue(rows[0].isWarning)
        assertFalse(rows[1].isWarning)
        assertEquals(44_351L + 4_641L, rows[0].totalBytes)
    }

    @Test
    fun `no storage response gives no rows`() {
        assertEquals(emptyList<StorageRow>(), StorageHealth.rows(null))
    }
}
