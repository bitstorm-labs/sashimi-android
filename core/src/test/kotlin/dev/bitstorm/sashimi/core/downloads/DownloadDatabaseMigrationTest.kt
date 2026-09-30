package dev.bitstorm.sashimi.core.downloads

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Opens a real schema-3 database with Room at schema 4. Room validates the
 * migrated table against [DownloadedItemEntity] and throws on any difference,
 * so a migration that loses a column, a constraint or the key fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadDatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    @Before
    fun setUp() {
        context.deleteDatabase(name)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    /** The schema-3 table, exactly as Room 2.6 generated it for version 3. */
    private val v3Table =
        "CREATE TABLE IF NOT EXISTS `downloaded_items` (`itemId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
            "`seriesName` TEXT, `seriesId` TEXT, `seasonId` TEXT, `seasonNumber` INTEGER, `episodeNumber` INTEGER, " +
            "`overview` TEXT, `itemType` TEXT, `runTimeTicks` INTEGER, `productionYear` INTEGER, " +
            "`status` TEXT NOT NULL, `quality` TEXT NOT NULL, `progress` REAL NOT NULL, " +
            "`totalBytes` INTEGER NOT NULL, `downloadedBytes` INTEGER NOT NULL, `errorMessage` TEXT, " +
            "`videoFileName` TEXT, `posterFileName` TEXT, `backdropFileName` TEXT, `subtitlesJson` TEXT, " +
            "`localPositionTicks` INTEGER NOT NULL, `pendingProgressSync` INTEGER NOT NULL, " +
            "`dateAdded` INTEGER NOT NULL, `dateCompleted` INTEGER, `serverId` TEXT, PRIMARY KEY(`itemId`))"

    private fun createV3(seed: (SupportSQLiteDatabase) -> Unit) {
        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(name)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(3) {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                db.execSQL(v3Table)
                            }

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )
        seed(helper.writableDatabase)
        helper.close()
    }

    private fun SupportSQLiteDatabase.insertV3(
        itemId: String,
        serverId: String?,
        positionTicks: Long = 0,
        pendingSync: Boolean = false,
        subtitlesJson: String? = null,
    ) {
        execSQL(
            "INSERT INTO downloaded_items (itemId, name, seriesName, seasonNumber, episodeNumber, status, quality, " +
                "progress, totalBytes, downloadedBytes, videoFileName, subtitlesJson, localPositionTicks, " +
                "pendingProgressSync, dateAdded, dateCompleted, serverId) " +
                "VALUES (?, ?, 'Show', 1, 2, 'completed', 'medium', 1.0, 1000, 1000, 'video.mp4', ?, ?, ?, 7, 8, ?)",
            arrayOf<Any?>(itemId, "Name $itemId", subtitlesJson, positionTicks, if (pendingSync) 1 else 0, serverId),
        )
    }

    private fun openV4(legacyServerId: String): DownloadDatabase =
        Room.databaseBuilder(context, DownloadDatabase::class.java, name)
            .addMigrations(DownloadDatabase.MIGRATION_2_3, DownloadDatabase.migration3To4 { legacyServerId })
            .allowMainThreadQueries()
            .build()

    @Test
    fun `every row survives, and server-less rows are stamped with the legacy server`() =
        runBlocking {
            createV3 { db ->
                db.insertV3("legacy", serverId = null, positionTicks = 42, pendingSync = true, subtitlesJson = "[]")
                db.insertV3("stamped", serverId = "server-b")
            }

            val db = openV4(legacyServerId = "server-a")
            val rows = db.downloadDao().getAll().associateBy { it.itemId }
            db.close()

            assertEquals(setOf("legacy", "stamped"), rows.keys)
            val legacy = rows.getValue("legacy")
            assertEquals("server-a", legacy.serverId)
            assertEquals(42L, legacy.localPositionTicks)
            assertTrue(legacy.pendingProgressSync)
            assertEquals("[]", legacy.subtitlesJson)
            assertEquals(DownloadStatus.COMPLETED, legacy.downloadStatus)
            assertEquals(DownloadQuality.MEDIUM, legacy.downloadQuality)
            assertEquals("video.mp4", legacy.videoFileName)
            assertEquals(1000L, legacy.totalBytes)
            assertEquals(7L, legacy.dateAdded)
            assertEquals(8L, legacy.dateCompleted)
            assertEquals("Show", legacy.seriesName)
            assertEquals(2, legacy.episodeNumber)
            // A row that already knew its server keeps it.
            assertEquals("server-b", rows.getValue("stamped").serverId)
        }

    @Test
    fun `with no saved server, server-less rows get the unknown-server id`() =
        runBlocking {
            createV3 { db -> db.insertV3("legacy", serverId = null) }

            val db = openV4(legacyServerId = DownloadKey.UNKNOWN_SERVER)
            val row = db.downloadDao().get(DownloadKey.UNKNOWN_SERVER, "legacy")
            db.close()

            assertNotNull(row)
        }

    @Test
    fun `after migrating, one item id can be downloaded from two servers`() =
        runBlocking {
            createV3 { db -> db.insertV3("shared-id", serverId = "server-a") }

            val db = openV4(legacyServerId = "server-a")
            val dao = db.downloadDao()
            dao.upsert(DownloadedItemEntity(itemId = "shared-id", name = "Other copy", serverId = "server-b"))
            dao.savePlaybackPosition("server-b", "shared-id", 99)
            val a = dao.get("server-a", "shared-id")
            val b = dao.get("server-b", "shared-id")
            db.close()

            assertEquals("Name shared-id", a?.name)
            assertEquals(0L, a?.localPositionTicks)
            assertEquals("Other copy", b?.name)
            assertEquals(99L, b?.localPositionTicks)
        }
}
