package com.shrey.downloader.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DownloadEntity::class,
        DownloadBatchEntity::class,
        BatchItemEntity::class,
        ChannelDiscoveryCheckpointEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class ShreyDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun batchDao(): BatchDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN batchId TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN mediaId TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN relativeSubfolder TEXT")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `download_batches` (
                        `batchId` TEXT NOT NULL,
                        `sourceUrl` TEXT NOT NULL,
                        `canonicalId` TEXT NOT NULL,
                        `sourceType` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `creator` TEXT,
                        `thumbnailUrl` TEXT,
                        `status` TEXT NOT NULL,
                        `qualityPreset` TEXT NOT NULL,
                        `downloadMode` TEXT NOT NULL,
                        `audioOutputFormat` TEXT NOT NULL,
                        `wifiOnly` INTEGER NOT NULL,
                        `discoveredCount` INTEGER NOT NULL,
                        `selectedCount` INTEGER NOT NULL,
                        `completedCount` INTEGER NOT NULL,
                        `failedCount` INTEGER NOT NULL,
                        `skippedCount` INTEGER NOT NULL,
                        `activeCount` INTEGER NOT NULL,
                        `totalBytesDownloaded` INTEGER NOT NULL,
                        `checkpointNextIndex` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `lastError` TEXT,
                        PRIMARY KEY(`batchId`)
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `batch_items` (
                        `itemId` TEXT NOT NULL,
                        `batchId` TEXT NOT NULL,
                        `downloadId` TEXT,
                        `mediaId` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `sourceUrl` TEXT NOT NULL,
                        `thumbnailUrl` TEXT,
                        `durationSeconds` INTEGER,
                        `tab` TEXT,
                        `status` TEXT NOT NULL,
                        `progressPercent` REAL NOT NULL,
                        `downloadedBytes` INTEGER,
                        `totalBytes` INTEGER,
                        `speedText` TEXT,
                        `etaSeconds` INTEGER,
                        `destinationUri` TEXT,
                        `outputDisplayName` TEXT,
                        `mimeType` TEXT,
                        `errorMessage` TEXT,
                        `technicalError` TEXT,
                        `retryCount` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`itemId`)
                    )
                    """.trimIndent()
                )

                db.execSQL("CREATE INDEX IF NOT EXISTS `index_batch_items_batchId` ON `batch_items` (`batchId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_batch_items_downloadId` ON `batch_items` (`downloadId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_batch_items_mediaId` ON `batch_items` (`mediaId`)")
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `download_batches` ADD COLUMN `discoveryStatus` TEXT NOT NULL DEFAULT 'COMPLETED'")
                db.execSQL("ALTER TABLE `download_batches` ADD COLUMN `targetLimit` INTEGER")
                db.execSQL("ALTER TABLE `download_batches` ADD COLUMN `knownTotalCount` INTEGER")
                db.execSQL("ALTER TABLE `download_batches` ADD COLUMN `discoveryError` TEXT")
                db.execSQL("ALTER TABLE `download_batches` ADD COLUMN `selectedTabsCsv` TEXT NOT NULL DEFAULT ''")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `channel_discovery_checkpoints` (
                        `batchId` TEXT NOT NULL,
                        `tab` TEXT NOT NULL,
                        `nextStartIndex` INTEGER NOT NULL,
                        `discoveredCount` INTEGER NOT NULL,
                        `isExhausted` INTEGER NOT NULL,
                        `lastError` TEXT,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`batchId`, `tab`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_channel_discovery_checkpoints_batchId` ON `channel_discovery_checkpoints` (`batchId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_batch_items_batchId_status_position` ON `batch_items` (`batchId`, `status`, `position`)")
            }
        }

        fun create(context: Context): ShreyDatabase = Room.databaseBuilder(
            context.applicationContext,
            ShreyDatabase::class.java,
            "shrey_downloads.db"
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
    }
}
