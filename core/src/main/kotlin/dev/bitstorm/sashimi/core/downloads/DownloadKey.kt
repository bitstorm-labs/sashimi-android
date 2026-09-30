package dev.bitstorm.sashimi.core.downloads

/**
 * Identifies one download: the saved server it came from plus its Jellyfin
 * item id.
 *
 * Item id alone is not unique. Jellyfin derives item ids from file paths, so
 * two servers over the same media folders hand out the same id, and keyed by
 * item id alone their downloads shared one row, one directory and one
 * progress-sync target (#86).
 */
data class DownloadKey(
    val serverId: String,
    val itemId: String,
) {
    companion object {
        /**
         * The server id of a download no saved server could be attributed to.
         * Only migration 3 to 4 writes it, for a pre-server row on an install
         * with no saved server at all. It resolves to the active server, which
         * is what a null server id meant in schema 3.
         */
        const val UNKNOWN_SERVER = ""

        /** The key for [itemId] on [serverId]; a null server is [UNKNOWN_SERVER]. */
        fun of(
            serverId: String?,
            itemId: String,
        ): DownloadKey = DownloadKey(serverId ?: UNKNOWN_SERVER, itemId)
    }
}

/** This row's [DownloadKey]. */
val DownloadedItemEntity.key: DownloadKey get() = DownloadKey(serverId, itemId)

/**
 * The on-disk layout under `filesDir/downloads/`, as paths relative to that
 * root. Pure so the mapping, the relocation of schema-3 directories and the
 * orphan sweep are unit-testable without a filesystem.
 *
 * - Current: `servers/{serverId}/{itemId}/`
 * - Legacy (schema 3 and earlier): `{itemId}/`
 *
 * The server level sits under a fixed `servers/` directory rather than
 * directly under the root because server ids and item ids can both be 32-hex
 * strings: `downloads/{serverId}/` would be indistinguishable from a legacy
 * `downloads/{itemId}/`, and the orphan sweep would have to guess.
 */
object DownloadLayout {
    const val SERVERS_DIR = "servers"

    /** The directory name standing in for [DownloadKey.UNKNOWN_SERVER]. */
    const val UNKNOWN_SERVER_DIR = "_unknown"

    private val UNSAFE = Regex("[^A-Za-z0-9._-]")

    /** A single path segment safe to use as a directory name. */
    fun segment(raw: String): String {
        val cleaned = raw.replace(UNSAFE, "_")
        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "_" else cleaned
    }

    fun serverSegment(serverId: String): String = if (serverId == DownloadKey.UNKNOWN_SERVER) UNKNOWN_SERVER_DIR else segment(serverId)

    /** Where [key]'s files live. */
    fun itemPath(key: DownloadKey): String = "$SERVERS_DIR/${serverSegment(key.serverId)}/${segment(key.itemId)}"

    /** Where a schema-3 download of [itemId] lived. */
    fun legacyItemPath(itemId: String): String = itemId

    /** A directory move, both paths relative to the downloads root. */
    data class Move(
        val from: String,
        val to: String,
    )

    /**
     * Moves that bring schema-3 directories (`{itemId}/`) into the current
     * layout, given the legacy directory names and the current item paths
     * already on disk.
     *
     * A legacy directory goes to the row with its item id whose current
     * directory does not exist yet. When several rows qualify (the same item
     * later queued from a second server, before this ran), the oldest wins: the
     * directory was written for the one row schema 3 could hold, and every row
     * added since was created against the current layout.
     */
    fun relocations(
        rows: List<DownloadedItemEntity>,
        legacyDirs: Set<String>,
        itemPathsOnDisk: Set<String>,
    ): List<Move> =
        legacyDirs
            .filter { it != SERVERS_DIR }
            .mapNotNull { dir ->
                val owner =
                    rows
                        .filter { it.itemId == dir && itemPath(it.key) !in itemPathsOnDisk }
                        .minByOrNull { it.dateAdded }
                        ?: return@mapNotNull null
                Move(from = dir, to = itemPath(owner.key))
            }

    /**
     * Directories with no row to own them, for the orphan sweep. Run it after
     * [relocations] have been applied.
     *
     * A current-layout directory is an orphan when no row maps to it. A legacy
     * directory is an orphan only when no row at all has its item id: one that
     * failed to move still belongs to its row, which reads it as a fallback.
     * The `servers/` directory itself is never a candidate.
     */
    fun orphans(
        rows: List<DownloadedItemEntity>,
        legacyDirs: Set<String>,
        itemPathsOnDisk: Set<String>,
    ): List<String> {
        val ownedPaths = rows.map { itemPath(it.key) }.toSet()
        val ownedItemIds = rows.map { it.itemId }.toSet()
        val legacyOrphans = legacyDirs.filter { it != SERVERS_DIR && it !in ownedItemIds }
        val currentOrphans = itemPathsOnDisk.filter { it !in ownedPaths }
        return (legacyOrphans + currentOrphans).sorted()
    }
}

/** Which download a surface means when it asks for an item by id. */
object DownloadLookup {
    /**
     * The completed download to play for [itemId].
     *
     * With a [serverId], only that server's copy: a title opened from server A
     * must not play server B's download of the same item id and then sync its
     * progress to B. With none (a surface that has no server: the offline
     * library's series page, a deep link), the active server's copy first, else
     * the oldest completed copy.
     */
    fun playable(
        rows: List<DownloadedItemEntity>,
        itemId: String,
        serverId: String?,
        activeServerId: String?,
    ): DownloadedItemEntity? {
        val copies = rows.filter { it.itemId == itemId && it.isComplete }
        if (serverId != null) return copies.firstOrNull { it.serverId == serverId }
        return copies.firstOrNull { it.serverId == activeServerId } ?: copies.minByOrNull { it.dateAdded }
    }
}
