package dev.bitstorm.sashimi.core.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which completed downloads are offered for re-download: the High / Medium /
 * Low files made before the tier URL sent a real video bitrate.
 */
class TierEncodeLedgerTest {
    private fun row(
        quality: DownloadQuality,
        status: DownloadStatus = DownloadStatus.COMPLETED,
        id: String = "a",
        serverId: String = "s",
    ) = DownloadedItemEntity(itemId = id, name = id, status = status.wireName, quality = quality.wireName, serverId = serverId)

    @Test
    fun `a completed tier download the fixed code did not make needs re-downloading`() {
        listOf(DownloadQuality.HIGH, DownloadQuality.MEDIUM, DownloadQuality.LOW).forEach {
            assertTrue(it.wireName, DownloadPolicy.needsRedownload(row(it), fixedEncodes = emptySet()))
        }
    }

    @Test
    fun `a download the fixed code made does not`() {
        assertFalse(DownloadPolicy.needsRedownload(row(DownloadQuality.LOW), fixedEncodes = setOf(DownloadKey("s", "a"))))
    }

    @Test
    fun `original is a raw copy and was never affected`() {
        assertFalse(DownloadPolicy.needsRedownload(row(DownloadQuality.ORIGINAL), fixedEncodes = emptySet()))
    }

    @Test
    fun `only completed rows are flagged`() {
        DownloadStatus.entries.filter { it != DownloadStatus.COMPLETED }.forEach {
            assertFalse(it.wireName, DownloadPolicy.needsRedownload(row(DownloadQuality.HIGH, it), fixedEncodes = emptySet()))
        }
    }

    @Test
    fun `the record is per server, so the same item fixed on another server still needs it`() {
        val fixedOnT = setOf(DownloadKey("t", "a"))
        assertTrue(DownloadPolicy.needsRedownload(row(DownloadQuality.HIGH, serverId = "s"), fixedOnT))
        assertFalse(DownloadPolicy.needsRedownload(row(DownloadQuality.HIGH, serverId = "t"), fixedOnT))
    }

    @Test
    fun `mark, unmark and clear move a key in and out of the ledger`() {
        val ledger = InMemoryTierEncodeLedger()
        val a = DownloadKey("s", "a")
        val b = DownloadKey("s", "b")
        ledger.mark(a)
        ledger.mark(b)
        assertEquals(setOf(a, b), ledger.keys.value)
        ledger.unmark(a)
        assertEquals(setOf(b), ledger.keys.value)
        ledger.clear()
        assertEquals(emptySet<DownloadKey>(), ledger.keys.value)
    }

    @Test
    fun `the stored form round-trips, including the unknown server and a server id with a slash`() {
        val keys =
            setOf(
                DownloadKey("server-1", "0123abcd"),
                DownloadKey(DownloadKey.UNKNOWN_SERVER, "0123abcd"),
                DownloadKey("odd/server", "ffff"),
            )
        assertEquals(keys, TierEncodeLedgerCodec.decode(TierEncodeLedgerCodec.encode(keys)))
    }

    @Test
    fun `a stored entry with no separator is dropped rather than misread`() {
        assertEquals(emptySet<DownloadKey>(), TierEncodeLedgerCodec.decode(setOf("garbage")))
    }
}
