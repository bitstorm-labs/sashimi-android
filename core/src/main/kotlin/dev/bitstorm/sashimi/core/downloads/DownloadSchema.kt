package dev.bitstorm.sashimi.core.downloads

/**
 * The SQL of the download database's migrations, kept apart from Room so it can
 * be read and tested as plain statements.
 */
object DownloadSchema {
    /** Every column of `downloaded_items`, in the order both schema 3 and 4 declare them. */
    val COLUMNS =
        listOf(
            "itemId", "name", "seriesName", "seriesId", "seasonId", "seasonNumber", "episodeNumber",
            "overview", "itemType", "runTimeTicks", "productionYear", "status", "quality", "progress",
            "totalBytes", "downloadedBytes", "errorMessage", "videoFileName", "posterFileName",
            "backdropFileName", "subtitlesJson", "localPositionTicks", "pendingProgressSync",
            "dateAdded", "dateCompleted", "serverId",
        )

    /**
     * Schema 4's table, exactly as Room generates it for [DownloadedItemEntity]
     * (Room validates the migrated table against the entity and throws on any
     * difference). `serverId` becomes NOT NULL and joins the primary key.
     */
    const val CREATE_V4_TABLE =
        "CREATE TABLE IF NOT EXISTS `downloaded_items_v4` (`itemId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
            "`seriesName` TEXT, `seriesId` TEXT, `seasonId` TEXT, `seasonNumber` INTEGER, `episodeNumber` INTEGER, " +
            "`overview` TEXT, `itemType` TEXT, `runTimeTicks` INTEGER, `productionYear` INTEGER, " +
            "`status` TEXT NOT NULL, `quality` TEXT NOT NULL, `progress` REAL NOT NULL, " +
            "`totalBytes` INTEGER NOT NULL, `downloadedBytes` INTEGER NOT NULL, `errorMessage` TEXT, " +
            "`videoFileName` TEXT, `posterFileName` TEXT, `backdropFileName` TEXT, `subtitlesJson` TEXT, " +
            "`localPositionTicks` INTEGER NOT NULL, `pendingProgressSync` INTEGER NOT NULL, " +
            "`dateAdded` INTEGER NOT NULL, `dateCompleted` INTEGER, `serverId` TEXT NOT NULL, " +
            "PRIMARY KEY(`serverId`, `itemId`))"

    /** One SQL statement and its bind arguments. */
    data class Statement(
        val sql: String,
        val args: List<Any> = emptyList(),
    )

    /**
     * Migration 3 to 4: re-key `downloaded_items` by (serverId, itemId).
     *
     * SQLite cannot change a primary key in place, so this copies every row
     * into a new table and swaps it in. A null `serverId` (a row from before
     * downloads carried a server) becomes [legacyServerId]: the server that was
     * active at migration time, which is where that row's progress sync was
     * already going. Nothing is dropped; the row count is unchanged. Files on
     * disk are not touched here; DownloadManager moves them on its next
     * recovery pass, outside the database transaction.
     */
    fun migration3To4(legacyServerId: String): List<Statement> {
        val cols = COLUMNS.joinToString(", ") { "`$it`" }
        val select = COLUMNS.joinToString(", ") { if (it == "serverId") "COALESCE(`serverId`, ?)" else "`$it`" }
        return listOf(
            Statement(CREATE_V4_TABLE),
            Statement("INSERT INTO `downloaded_items_v4` ($cols) SELECT $select FROM `downloaded_items`", listOf(legacyServerId)),
            Statement("DROP TABLE `downloaded_items`"),
            Statement("ALTER TABLE `downloaded_items_v4` RENAME TO `downloaded_items`"),
        )
    }

    /**
     * The server id migration 3 to 4 stamps on server-less rows: the saved
     * active server when it still exists, else the first saved server (the one
     * a session restore would activate), else [DownloadKey.UNKNOWN_SERVER].
     */
    fun legacyServerId(
        activeServerId: String?,
        savedServerIds: List<String>,
    ): String =
        activeServerId?.takeIf { it in savedServerIds }
            ?: savedServerIds.firstOrNull()
            ?: DownloadKey.UNKNOWN_SERVER
}
