package dev.bitstorm.sashimi.core.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The on-disk layout, the schema-3 relocation and the orphan sweep (#86). */
class DownloadLayoutTest {
    private fun row(
        itemId: String,
        serverId: String,
        added: Long = 0,
    ) = DownloadedItemEntity(itemId = itemId, name = itemId, serverId = serverId, dateAdded = added)

    @Test
    fun `one item id on two servers maps to two directories`() {
        val a = DownloadLayout.itemPath(DownloadKey("server-a", "item"))
        val b = DownloadLayout.itemPath(DownloadKey("server-b", "item"))
        assertEquals("servers/server-a/item", a)
        assertEquals("servers/server-b/item", b)
        assertNotEquals(a, b)
    }

    @Test
    fun `the unknown server and unsafe ids map to safe single segments`() {
        assertEquals("servers/_unknown/item", DownloadLayout.itemPath(DownloadKey(DownloadKey.UNKNOWN_SERVER, "item")))
        assertEquals("servers/a_b_.._c/_", DownloadLayout.itemPath(DownloadKey("a/b/../c", "..")))
    }

    @Test
    fun `legacy directories move to their row's server`() {
        val rows = listOf(row("m1", "server-a"), row("m2", "server-b"))
        val moves = DownloadLayout.relocations(rows, legacyDirs = setOf("m1", "m2"), itemPathsOnDisk = emptySet())
        assertEquals(
            listOf(
                DownloadLayout.Move("m1", "servers/server-a/m1"),
                DownloadLayout.Move("m2", "servers/server-b/m2"),
            ),
            moves,
        )
    }

    @Test
    fun `relocation is idempotent and never targets an existing directory`() {
        val rows = listOf(row("m1", "server-a"))
        val moves = DownloadLayout.relocations(rows, legacyDirs = setOf("m1"), itemPathsOnDisk = setOf("servers/server-a/m1"))
        assertTrue(moves.isEmpty())
    }

    @Test
    fun `a shared item id relocates to the oldest row, the one schema 3 held`() {
        val rows = listOf(row("m1", "server-b", added = 20), row("m1", "server-a", added = 10))
        val moves = DownloadLayout.relocations(rows, legacyDirs = setOf("m1"), itemPathsOnDisk = emptySet())
        assertEquals(listOf(DownloadLayout.Move("m1", "servers/server-a/m1")), moves)
    }

    @Test
    fun `a legacy directory without a row is not relocated`() {
        val moves = DownloadLayout.relocations(emptyList(), legacyDirs = setOf("gone"), itemPathsOnDisk = emptySet())
        assertTrue(moves.isEmpty())
    }

    @Test
    fun `relocated downloads are not orphans`() {
        // The disk after an upgrade: two directories already moved, the
        // servers/ directory at the top of the root.
        val rows = listOf(row("m1", "server-a"), row("m2", "server-b"))
        val orphans =
            DownloadLayout.orphans(
                rows,
                legacyDirs = setOf(DownloadLayout.SERVERS_DIR),
                itemPathsOnDisk = setOf("servers/server-a/m1", "servers/server-b/m2"),
            )
        assertTrue(orphans.toString(), orphans.isEmpty())
    }

    @Test
    fun `a legacy directory that failed to move still belongs to its row`() {
        val orphans = DownloadLayout.orphans(listOf(row("m1", "server-a")), legacyDirs = setOf("m1"), itemPathsOnDisk = emptySet())
        assertTrue(orphans.isEmpty())
    }

    @Test
    fun `directories without a row are orphans in either layout`() {
        val rows = listOf(row("m1", "server-a"))
        val orphans =
            DownloadLayout.orphans(
                rows,
                legacyDirs = setOf("stale"),
                // m1 on server-b has no row even though m1 on server-a does.
                itemPathsOnDisk = setOf("servers/server-a/m1", "servers/server-b/m1"),
            )
        assertEquals(listOf("servers/server-b/m1", "stale"), orphans)
    }

    @Test
    fun `the whole flow leaves every file of a pre-upgrade install in place`() {
        // A schema-3 install: downloads/{itemId}/ for two rows plus one orphan.
        val rows = listOf(row("m1", "server-a"), row("m2", "server-a"))
        val legacy = setOf("m1", "m2", "orphan")
        val moves = DownloadLayout.relocations(rows, legacy, itemPathsOnDisk = emptySet())
        val movedFrom = moves.map { it.from }.toSet()
        val legacyAfter = legacy - movedFrom + DownloadLayout.SERVERS_DIR
        val pathsAfter = moves.map { it.to }.toSet()

        assertEquals(listOf("orphan"), DownloadLayout.orphans(rows, legacyAfter, pathsAfter))
    }

    @Test
    fun `migration stamps the saved active server, else the first saved one`() {
        assertEquals("a", DownloadSchema.legacyServerId("a", listOf("b", "a")))
        assertEquals("b", DownloadSchema.legacyServerId("removed", listOf("b", "a")))
        assertEquals("b", DownloadSchema.legacyServerId(null, listOf("b")))
        assertEquals(DownloadKey.UNKNOWN_SERVER, DownloadSchema.legacyServerId(null, emptyList()))
    }

    @Test
    fun `migration 3 to 4 copies every column and fills a null server`() {
        val statements = DownloadSchema.migration3To4("server-a")
        val insert = statements.single { it.sql.startsWith("INSERT") }
        DownloadSchema.COLUMNS.forEach { assertTrue(it, insert.sql.contains("`$it`")) }
        assertTrue(insert.sql.contains("COALESCE(`serverId`, ?)"))
        assertEquals(listOf<Any>("server-a"), insert.args)
        assertTrue(statements.first().sql.contains("PRIMARY KEY(`serverId`, `itemId`)"))
        assertTrue(statements.first().sql.contains("`serverId` TEXT NOT NULL"))
    }
}

/** Which download of an item a surface plays (#86). */
class DownloadLookupTest {
    private fun done(
        serverId: String,
        added: Long,
        itemId: String = "ep",
    ) = DownloadedItemEntity(
        itemId = itemId,
        name = itemId,
        serverId = serverId,
        status = DownloadStatus.COMPLETED.wireName,
        dateAdded = added,
    )

    @Test
    fun `a server-scoped surface plays only that server's copy`() {
        val rows = listOf(done("b", 1))
        // Keyed by item id alone, a title opened from server A played server
        // B's download and synced its progress to B.
        assertEquals(null, DownloadLookup.playable(rows, "ep", serverId = "a", activeServerId = "a"))
        assertEquals("b", DownloadLookup.playable(rows, "ep", serverId = "b", activeServerId = "a")?.serverId)
    }

    @Test
    fun `an unscoped surface prefers the active server, else the oldest copy`() {
        val rows = listOf(done("c", 5), done("b", 2), done("a", 9))
        assertEquals("a", DownloadLookup.playable(rows, "ep", serverId = null, activeServerId = "a")?.serverId)
        assertEquals("b", DownloadLookup.playable(rows, "ep", serverId = null, activeServerId = "z")?.serverId)
    }

    @Test
    fun `an incomplete download is never playable`() {
        val rows = listOf(done("a", 1).copy(status = DownloadStatus.DOWNLOADING.wireName))
        assertEquals(null, DownloadLookup.playable(rows, "ep", serverId = "a", activeServerId = "a"))
    }
}
