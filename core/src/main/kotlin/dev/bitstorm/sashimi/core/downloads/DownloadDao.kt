package dev.bitstorm.sashimi.core.downloads

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    /** Reactive stream of every download row, newest-added first (drives the UI). */
    @Query("SELECT * FROM downloaded_items ORDER BY dateAdded DESC")
    fun observeAll(): Flow<List<DownloadedItemEntity>>

    @Query("SELECT * FROM downloaded_items ORDER BY dateAdded DESC")
    suspend fun getAll(): List<DownloadedItemEntity>

    @Query("SELECT * FROM downloaded_items WHERE serverId = :serverId AND itemId = :itemId")
    suspend fun get(
        serverId: String,
        itemId: String,
    ): DownloadedItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: DownloadedItemEntity)

    @Query("DELETE FROM downloaded_items WHERE serverId = :serverId AND itemId = :itemId")
    suspend fun delete(
        serverId: String,
        itemId: String,
    )

    @Query("DELETE FROM downloaded_items")
    suspend fun deleteAll()

    @Query(
        "UPDATE downloaded_items SET status = :status, progress = :progress, " +
            "downloadedBytes = :downloadedBytes, totalBytes = :totalBytes " +
            "WHERE serverId = :serverId AND itemId = :itemId",
    )
    suspend fun updateProgress(
        serverId: String,
        itemId: String,
        status: String,
        progress: Double,
        downloadedBytes: Long,
        totalBytes: Long,
    )

    @Query(
        "UPDATE downloaded_items SET status = :status, errorMessage = :error " +
            "WHERE serverId = :serverId AND itemId = :itemId",
    )
    suspend fun updateStatus(
        serverId: String,
        itemId: String,
        status: String,
        error: String?,
    )

    @Query(
        "UPDATE downloaded_items SET localPositionTicks = :ticks, pendingProgressSync = 1 " +
            "WHERE serverId = :serverId AND itemId = :itemId",
    )
    suspend fun savePlaybackPosition(
        serverId: String,
        itemId: String,
        ticks: Long,
    )

    @Query("UPDATE downloaded_items SET pendingProgressSync = 0 WHERE serverId = :serverId AND itemId = :itemId")
    suspend fun clearSyncFlag(
        serverId: String,
        itemId: String,
    )
}

@Database(entities = [DownloadedItemEntity::class], version = 4, exportSchema = false)
abstract class DownloadDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        /**
         * Adds [DownloadedItemEntity.serverId]. A real migration, not a
         * destructive one: the database falls back to destructive migration,
         * which would drop every row, and the orphan sweep in DownloadManager
         * would then delete the user's downloaded files along with them.
         */
        val MIGRATION_2_3: Migration =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE downloaded_items ADD COLUMN serverId TEXT")
                }
            }

        /**
         * Re-keys downloads by server + item id (#86); see
         * [DownloadSchema.migration3To4]. [legacyServerId] is read when the
         * migration runs, not when it is built: it names the server stamped on
         * rows that predate downloads carrying one.
         */
        fun migration3To4(legacyServerId: () -> String): Migration =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    DownloadSchema.migration3To4(legacyServerId()).forEach { statement ->
                        if (statement.args.isEmpty()) {
                            db.execSQL(statement.sql)
                        } else {
                            db.execSQL(statement.sql, statement.args.toTypedArray())
                        }
                    }
                }
            }
    }
}
