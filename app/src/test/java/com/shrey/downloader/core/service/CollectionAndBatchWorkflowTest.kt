package com.shrey.downloader.core.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.shrey.downloader.core.database.ShreyDatabase
import com.shrey.downloader.core.engine.CollectionJsonParser
import com.shrey.downloader.core.notifications.DownloadNotificationManager
import com.shrey.downloader.core.storage.MediaStoragePublisher
import com.shrey.downloader.core.validation.MediaUrlClassifier
import com.shrey.downloader.data.local.SettingsDataStore
import com.shrey.downloader.data.repository.RoomBatchRepository
import com.shrey.downloader.data.repository.RoomDownloadRepository
import com.shrey.downloader.domain.model.AudioOutputFormat
import com.shrey.downloader.domain.model.BatchItemStatus
import com.shrey.downloader.domain.model.BatchStatus
import com.shrey.downloader.domain.model.BulkQualityPreset
import com.shrey.downloader.domain.model.ChannelContentTab
import com.shrey.downloader.domain.model.CollectionEntry
import com.shrey.downloader.domain.model.CollectionPreview
import com.shrey.downloader.domain.model.DownloadProgress
import com.shrey.downloader.domain.model.DownloadRequest
import com.shrey.downloader.domain.model.DownloadResult
import com.shrey.downloader.domain.model.EntryAvailability
import com.shrey.downloader.domain.model.MediaSourceType
import com.shrey.downloader.domain.model.ShreyError
import com.shrey.downloader.domain.repository.DownloadEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowStatFs
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CollectionAndBatchWorkflowTest {

    private class HoldingDownloadEngine : DownloadEngine {
        private val inflight = ConcurrentHashMap<String, CompletableDeferred<DownloadResult>>()

        override suspend fun download(
            request: DownloadRequest,
            onProgress: (DownloadProgress) -> Unit
        ): DownloadResult {
            val gate = CompletableDeferred<DownloadResult>()
            inflight[request.downloadId] = gate
            return try {
                gate.await()
            } finally {
                inflight.remove(request.downloadId)
            }
        }

        override suspend fun cancel(downloadId: String) {
            inflight.remove(downloadId)?.complete(DownloadResult.Cancelled)
        }

        fun releaseAllCancelled() {
            inflight.values.forEach { it.complete(DownloadResult.Cancelled) }
            inflight.clear()
        }
    }

    private lateinit var context: Context
    private lateinit var database: ShreyDatabase
    private lateinit var downloadRepository: RoomDownloadRepository
    private lateinit var batchRepository: RoomBatchRepository
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var storagePublisher: MediaStoragePublisher
    private lateinit var holdingEngine: HoldingDownloadEngine
    private lateinit var queueManager: DownloadQueueManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ShadowStatFs.registerStats(context.cacheDir.absolutePath, 1_000_000, 500_000, 500_000)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        if (network != null) {
            val caps = ShadowNetworkCapabilities.newInstance()
            Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            Shadows.shadowOf(cm).setNetworkCapabilities(network, caps)
        }
        database = Room.inMemoryDatabaseBuilder(context, ShreyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        downloadRepository = RoomDownloadRepository(database.downloadDao())
        batchRepository = RoomBatchRepository(database.batchDao())
        settingsDataStore = SettingsDataStore(context)
        storagePublisher = MediaStoragePublisher(context)
        holdingEngine = HoldingDownloadEngine()
        val notificationManager = DownloadNotificationManager(context)
        queueManager = DownloadQueueManager(
            context = context,
            repository = downloadRepository,
            downloadEngine = holdingEngine,
            storagePublisher = storagePublisher,
            settingsDataStore = settingsDataStore,
            notificationManager = notificationManager
        )
    }

    @After
    fun tearDown() {
        holdingEngine.releaseAllCancelled()
        database.close()
    }

    @Test
    fun `collection json parser classifies available private live and deduplicates entries`() {
        val rawJson = """
            {
              "_type": "playlist",
              "id": "PLdemo123456",
              "title": "Public Science Lectures",
              "uploader": "Open Archive",
              "playlist_count": 6,
              "entries": [
                {"id": "vid1", "title": "Lecture 1", "duration": 620, "url": "vid1", "thumbnails": [{"url": "https://img.example/1.jpg"}]},
                {"id": "vid1", "title": "Lecture 1 Duplicate", "duration": 620, "url": "vid1"},
                {"id": "vid2", "title": "[Private video]", "url": "vid2"},
                {"id": "vid3", "title": "[Deleted video]", "url": "vid3"},
                {"id": "vid4", "title": "Live Q&A Stream", "live_status": "is_live", "url": "vid4"},
                {"id": "vid5", "title": "Lecture 2", "duration": 940, "url": "https://www.youtube.com/watch?v=vid5"}
              ]
            }
        """.trimIndent()

        val classified = MediaUrlClassifier.classify("https://www.youtube.com/playlist?list=PLdemo123456")
        val preview = CollectionJsonParser.parseCollectionPage(
            rawJson = rawJson,
            classified = classified,
            startIndex = 1,
            pageSize = 50
        )

        assertEquals("Public Science Lectures", preview.title)
        assertEquals("Open Archive", preview.creator)
        assertEquals(6, preview.declaredTotalCount)
        assertEquals(5, preview.entries.size) // duplicate vid1 filtered
        assertEquals(EntryAvailability.AVAILABLE, preview.entries[0].availability)
        assertEquals(EntryAvailability.PRIVATE_OR_MEMBERS, preview.entries[1].availability)
        assertEquals(EntryAvailability.UNAVAILABLE, preview.entries[2].availability)
        assertEquals(EntryAvailability.LIVE_ACTIVE, preview.entries[3].availability)
        assertEquals(EntryAvailability.AVAILABLE, preview.entries[4].availability)
        assertEquals(2, preview.downloadableCount)
        assertEquals(3, preview.unavailableCount)
    }

    @Test
    fun `playlist batch enforces sequential concurrency skips duplicates continues after nonfatal failure and completes`() = runBlocking {
        val coordinator = BatchDownloadCoordinator(
            context = context,
            batchRepository = batchRepository,
            queueManager = queueManager,
            storagePublisher = storagePublisher,
            settingsDataStore = settingsDataStore,
            isUnmeteredNetworkProvider = { true }
        )

        val entries = listOf(
            CollectionEntry("m1", 1, "Video One", "https://www.youtube.com/watch?v=m1", 120L),
            CollectionEntry("m1", 2, "Video One Duplicate", "https://www.youtube.com/watch?v=m1", 120L),
            CollectionEntry("m2", 3, "Video Two Fails", "https://www.youtube.com/watch?v=m2", 180L),
            CollectionEntry("m3", 4, "Video Three Succeeds", "https://www.youtube.com/watch?v=m3", 240L)
        )
        val preview = CollectionPreview(
            sourceType = MediaSourceType.PLAYLIST,
            sourceUrl = "https://www.youtube.com/playlist?list=PLtest12345678",
            canonicalId = "PLtest12345678",
            title = "My Study Playlist",
            creator = "Instructor",
            thumbnailUrl = null,
            declaredTotalCount = 4,
            entries = entries,
            hasMoreEntries = false,
            nextStartIndex = 5
        )

        val batchId = coordinator.startBatch(
            preview = preview,
            selectedEntries = entries,
            qualityPreset = BulkQualityPreset.P1080,
            audioOutputFormat = AudioOutputFormat.M4A,
            wifiOnly = true
        ).getOrThrow()

        // Verify subfolder path format
        val initialBatch = batchRepository.getBatch(batchId)!!
        assertEquals("Playlists/My Study Playlist", BatchDownloadCoordinator.buildRelativeSubfolder(initialBatch))

        // Only 1 item ACTIVE at a time (bounded sequential concurrency = 1), duplicate skipped immediately
        val items1 = batchRepository.getItemsForBatch(batchId)
        assertEquals(1, items1.count { it.status == BatchItemStatus.ACTIVE })
        assertEquals(1, items1.count { it.status == BatchItemStatus.SKIPPED })
        assertEquals(2, items1.count { it.status == BatchItemStatus.QUEUED })

        val activeItem1 = items1.first { it.status == BatchItemStatus.ACTIVE }
        assertNotNull(activeItem1.downloadId)

        // Complete item 1 -> advances cleanly to item 2 (m2)
        coordinator.onDownloadCompleted(
            downloadId = activeItem1.downloadId!!,
            destinationUri = "content://media/external/video/media/101",
            displayName = "Video One.mp4",
            mimeType = "video/mp4",
            fileSizeBytes = 15_000_000L
        )
        delay(120)

        val items2 = batchRepository.getItemsForBatch(batchId)
        val activeItem2 = items2.first { it.status == BatchItemStatus.ACTIVE }
        assertEquals("m2", activeItem2.mediaId)

        // Fail item 2 with nonfatal extractor error -> must continue to item 3 (m3)
        coordinator.onDownloadFailed(
            downloadId = activeItem2.downloadId!!,
            error = ShreyError.ContentUnavailable("Video unavailable in your region")
        )
        delay(120)

        val items3 = batchRepository.getItemsForBatch(batchId)
        val activeItem3 = items3.first { it.status == BatchItemStatus.ACTIVE }
        assertEquals("m3", activeItem3.mediaId)

        // Complete item 3 -> batch finishes with COMPLETED_WITH_ERRORS (2 completed, 1 failed, 1 skipped)
        coordinator.onDownloadCompleted(
            downloadId = activeItem3.downloadId!!,
            destinationUri = "content://media/external/video/media/103",
            displayName = "Video Three Succeeds.mp4",
            mimeType = "video/mp4",
            fileSizeBytes = 25_000_000L
        )
        delay(120)

        val finishedBatch = batchRepository.getBatch(batchId)!!
        assertEquals(BatchStatus.COMPLETED_WITH_ERRORS, finishedBatch.status)
        assertEquals(2, finishedBatch.completedCount)
        assertEquals(1, finishedBatch.failedCount)
        assertEquals(1, finishedBatch.skippedCount)
        assertEquals(40_000_000L, finishedBatch.totalBytesDownloaded)
        assertEquals("2 of 4 completed · 1 skipped · 1 failed", finishedBatch.overallProgressLabel)

        // Retry failed item requeues m2 and sets batch back to ACTIVE
        coordinator.retryFailedItems(batchId)
        val afterRetry = batchRepository.getBatch(batchId)!!
        assertEquals(BatchStatus.ACTIVE, afterRetry.status)
    }

    @Test
    fun `pause resume cancel and restart reconciliation preserve completed items safely`() = runBlocking {
        val coordinator = BatchDownloadCoordinator(
            context = context,
            batchRepository = batchRepository,
            queueManager = queueManager,
            storagePublisher = storagePublisher,
            settingsDataStore = settingsDataStore,
            isUnmeteredNetworkProvider = { true }
        )

        val entries = listOf(
            CollectionEntry("c1", 1, "Short 1", "https://www.youtube.com/watch?v=c1", 45L, tab = ChannelContentTab.SHORTS),
            CollectionEntry("c2", 2, "Stream VOD 1", "https://www.youtube.com/watch?v=c2", 3600L, tab = ChannelContentTab.STREAMS),
            CollectionEntry("c3", 3, "Video 3", "https://www.youtube.com/watch?v=c3", 300L, tab = ChannelContentTab.VIDEOS)
        )
        val preview = CollectionPreview(
            sourceType = MediaSourceType.CHANNEL,
            sourceUrl = "https://www.youtube.com/@CreatorDemo",
            canonicalId = "@CreatorDemo",
            title = "Creator Demo",
            creator = "Creator Demo",
            thumbnailUrl = null,
            declaredTotalCount = 3,
            entries = entries,
            hasMoreEntries = true,
            nextStartIndex = 51
        )

        val batchId = coordinator.startBatch(
            preview = preview,
            selectedEntries = entries,
            qualityPreset = BulkQualityPreset.AUDIO_ONLY,
            audioOutputFormat = AudioOutputFormat.MP3,
            wifiOnly = false
        ).getOrThrow()

        val channelBatch = batchRepository.getBatch(batchId)!!
        assertEquals("Channels/Creator Demo", BatchDownloadCoordinator.buildRelativeSubfolder(channelBatch))

        // Pause scheduling explicitly marks batch PAUSED
        coordinator.pauseBatchScheduling(batchId)
        assertEquals(BatchStatus.PAUSED, batchRepository.getBatch(batchId)!!.status)

        // Resume restores ACTIVE
        coordinator.resumeBatchScheduling(batchId)
        assertEquals(BatchStatus.ACTIVE, batchRepository.getBatch(batchId)!!.status)

        // Simulate OS process restart reconciliation: in-flight ACTIVE item resets to QUEUED and batch becomes PAUSED
        coordinator.reconcileOnAppStart()
        val reconciledBatch = batchRepository.getBatch(batchId)!!
        val reconciledItems = batchRepository.getItemsForBatch(batchId)
        assertEquals(BatchStatus.PAUSED, reconciledBatch.status)
        assertEquals(0, reconciledItems.count { it.status == BatchItemStatus.ACTIVE })
        assertEquals(3, reconciledItems.count { it.status == BatchItemStatus.QUEUED })

        // Cancel entire batch transitions all remaining items to CANCELLED
        coordinator.cancelBatch(batchId)
        val cancelledBatch = batchRepository.getBatch(batchId)!!
        assertEquals(BatchStatus.CANCELLED, cancelledBatch.status)
        assertTrue(batchRepository.getItemsForBatch(batchId).all { it.status == BatchItemStatus.CANCELLED })

        // Checkpoint persistence updates cleanly
        coordinator.recordDiscoveryCheckpoint(batchId, nextIndex = 101, discoveredCount = 100)
        assertEquals(101, batchRepository.getBatch(batchId)!!.checkpointNextIndex)
    }

    @Test
    fun `wifi only safeguard pauses batch automatically on metered network`() = runBlocking {
        val coordinator = BatchDownloadCoordinator(
            context = context,
            batchRepository = batchRepository,
            queueManager = queueManager,
            storagePublisher = storagePublisher,
            settingsDataStore = settingsDataStore,
            isUnmeteredNetworkProvider = { false } // Metered mobile network
        )

        val entries = listOf(
            CollectionEntry("w1", 1, "Heavy 4K Video", "https://www.youtube.com/watch?v=w1", 600L)
        )
        val preview = CollectionPreview(
            sourceType = MediaSourceType.PLAYLIST,
            sourceUrl = "https://www.youtube.com/playlist?list=PLwifi12345678",
            canonicalId = "PLwifi12345678",
            title = "4K Demos",
            creator = "Creator",
            thumbnailUrl = null,
            declaredTotalCount = 1,
            entries = entries,
            hasMoreEntries = false,
            nextStartIndex = 2
        )

        val batchId = coordinator.startBatch(
            preview = preview,
            selectedEntries = entries,
            qualityPreset = BulkQualityPreset.P2160,
            audioOutputFormat = AudioOutputFormat.M4A,
            wifiOnly = true
        ).getOrThrow()

        val batch = batchRepository.getBatch(batchId)!!
        assertEquals(BatchStatus.PAUSED, batch.status)
        assertTrue(batch.lastError.orEmpty().contains("Wi-Fi", ignoreCase = true))
    }

    @Test
    fun `room migration 1 to 2 preserves existing single video rows and creates batch tables`() {
        val dbName = "migration_test_${System.nanoTime()}.db"
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `downloads` (
                            `id` TEXT NOT NULL,
                            `sourceUrl` TEXT NOT NULL,
                            `title` TEXT NOT NULL,
                            `creator` TEXT,
                            `thumbnailUrl` TEXT,
                            `selectedFormatId` TEXT NOT NULL,
                            `formatSelector` TEXT NOT NULL,
                            `qualityLabel` TEXT NOT NULL,
                            `requestedMode` TEXT NOT NULL,
                            `outputContainer` TEXT NOT NULL,
                            `audioOutputFormat` TEXT NOT NULL,
                            `thumbnailOutputFormat` TEXT NOT NULL,
                            `requiresMerge` INTEGER NOT NULL,
                            `status` TEXT NOT NULL,
                            `progress` REAL NOT NULL,
                            `downloadedBytes` INTEGER,
                            `totalBytes` INTEGER,
                            `speedText` TEXT,
                            `etaSeconds` INTEGER,
                            `statusMessage` TEXT,
                            `destinationTreeUri` TEXT,
                            `destinationUri` TEXT,
                            `outputDisplayName` TEXT,
                            `mimeType` TEXT,
                            `createdAt` INTEGER NOT NULL,
                            `startedAt` INTEGER,
                            `completedAt` INTEGER,
                            `lastError` TEXT,
                            `technicalError` TEXT,
                            `retryCount` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        INSERT INTO `downloads` VALUES (
                            'legacy_1', 'https://youtube.com/watch?v=abc', 'Legacy 4K Video', 'Creator', NULL,
                            '313+251', 'bestvideo+bestaudio', '2160p', 'VIDEO_AUDIO', 'mp4', 'ORIGINAL', 'ORIGINAL',
                            1, 'COMPLETED', 100.0, 500000, 500000, NULL, NULL, 'Saved', NULL,
                            'content://media/1', 'Legacy 4K Video.mp4', 'video/mp4', 1700000000, 1700000001,
                            1700000010, NULL, NULL, 0
                        )
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val sqliteDb = helper.writableDatabase
        sqliteDb.close()
        helper.close()

        val migratedRoom = Room.databaseBuilder(context, ShreyDatabase::class.java, dbName)
            .addMigrations(ShreyDatabase.MIGRATION_1_2, ShreyDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()

        runBlocking {
            val legacyRows = migratedRoom.downloadDao().observeAll().first()
            assertEquals(1, legacyRows.size)
            assertEquals("Legacy 4K Video", legacyRows.first().title)
            assertFalse(legacyRows.first().batchId != null)
            assertEquals(0, migratedRoom.batchDao().observeAllBatches().first().size)
        }
        migratedRoom.close()
        context.deleteDatabase(dbName)
    }
}
