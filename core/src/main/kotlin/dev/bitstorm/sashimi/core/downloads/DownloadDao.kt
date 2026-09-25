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

    @Query("SELECT * FROM downloaded_items WHERE itemId = :itemId")
    suspend fun getById(itemId: String): DownloadedItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: DownloadedItemEntity)

    @Query("DELETE FROM downloaded_items WHERE itemId = :itemId")
    suspend fun deleteById(itemId: String)

    @Query("DELETE FROM downloaded_items")
    suspend fun deleteAll()

    @Query(
        "UPDATE downloaded_items SET status = :status, progress = :progress, " +
            "downloadedBytes = :downloadedBytes, totalBytes = :totalBytes WHERE itemId = :itemId",
    )
    suspend fun updateProgress(
        itemId: String,
        status: String,
        progress: Double,
        downloadedBytes: Long,
        totalBytes: Long,
    )

    @Query("UPDATE downloaded_items SET status = :status, errorMessage = :error WHERE itemId = :itemId")
    suspend fun updateStatus(
        itemId: String,
        status: String,
        error: String?,
    )

    @Query(
        "UPDATE downloaded_items SET localPositionTicks = :ticks, pendingProgressSync = 1 WHERE itemId = :itemId",
    )
    suspend fun savePlaybackPosition(
        itemId: String,
        ticks: Long,
    )

    @Query("UPDATE downloaded_items SET pendingProgressSync = 0 WHERE itemId = :itemId")
    suspend fun clearSyncFlag(itemId: String)
}

@Database(entities = [DownloadedItemEntity::class], version = 3, exportSchema = false)
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
    }
}
