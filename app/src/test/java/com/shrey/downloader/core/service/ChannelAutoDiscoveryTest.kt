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
import com.shrey.downloader.core.engine.CollectionExtractor
import com.shrey.downloader.core.notifications.DownloadNotificationManager
import com.shrey.downloader.core.storage.MediaStoragePublisher
import com.shrey.downloader.core.validation.ClassifiedMediaUrl
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
import com.shrey.downloader.domain.model.DiscoveryStatus
import com.shrey.downloader.domain.model.DownloadMode
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
import org.junit.Assert.assertNull
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChannelAutoDiscoveryTest {

    private class HoldingDownloadEngine : DownloadEngine {
        private val inflight = ConcurrentHashMap<String, CompletableDeferred<DownloadResult>>()
        val startedIds = CopyOnWriteArrayList<String>()

        override suspend fun download(
            request: DownloadRequest,
            onProgress: (DownloadProgress) -> Unit
        ): DownloadResult {
            startedIds.add(request.downloadId)
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

        fun complete(downloadId: String, sizeBytes: Long = 1_000_000L) {
            inflight.remove(downloadId)?.complete(
                DownloadResult.Success(
                    destinationUri = "content://media/external/video/media/$downloadId",
                    displayName = "$downloadId.mp4",
                    mimeType = "video/mp4",
                    fileSizeBytes = sizeBytes
                )
            )
        }

        fun releaseAllCancelled() {
            inflight.values.forEach { it.complete(DownloadResult.Cancelled) }
            inflight.clear()
        }
    }

    /**
     * Memory-light simulated channel extractor generating deterministic entries on the fly
     * in chunks of [pageSize] (default 50) without allocating full 10,000-element lists in advance.
     */
    private class SimulatedChannelExtractor(
        private val countsByTab: Map<ChannelContentTab, Int>,
        private val declaredTotalKnown: Boolean = false,
        private val onPageFetched: (suspend (ChannelContentTab, Int, Int) -> Result<CollectionPreview>?)? = null,
        private val entryBuilder: (ChannelContentTab, Int) -> CollectionEntry = { tab, idx ->
            val id = "${tab.pathSegment}_$idx"
            CollectionEntry(
                mediaId = id,
                position = idx,
                title = "${tab.label} Item #$idx",
                sourceUrl = "https://www.youtube.com/watch?v=$id",
                durationSeconds = 90L,
                availability = EntryAvailability.AVAILABLE,
                tab = tab
            )
        }
    ) : CollectionExtractor {

        val requestedPages = CopyOnWriteArrayList<Triple<ChannelContentTab, Int, Int>>()

        override suspend fun extractPlaylistPage(
            url: String,
            startIndex: Int,
            pageSize: Int
        ): Result<CollectionPreview> = Result.failure(UnsupportedOperationException())

        override suspend fun extractChannelPreview(
            classified: ClassifiedMediaUrl,
            selectedTabs: Set<ChannelContentTab>,
            perTabStartIndex: Int,
            pageSize: Int
        ): Result<CollectionPreview> {
            val entries = mutableListOf<CollectionEntry>()
            val availableTabs = mutableListOf<ChannelContentTab>()
            var anyHasMore = false
            for (tab in ChannelContentTab.entries.filter { it in selectedTabs }) {
                val page = extractChannelTabPage(classified, tab, perTabStartIndex, pageSize).getOrNull() ?: continue
                if (page.entries.isNotEmpty()) {
                    availableTabs += tab
                    entries += page.entries
                }
                if (page.hasMoreEntries) anyHasMore = true
            }
            return Result.success(
                CollectionPreview(
                    sourceType = MediaSourceType.CHANNEL,
                    sourceUrl = classified.channelBaseUrl ?: classified.normalizedUrl,
                    canonicalId = classified.channelIdentifier ?: "@Simulated",
                    title = "Simulated Channel",
                    creator = "Simulated Creator",
                    thumbnailUrl = null,
                    declaredTotalCount = if (declaredTotalKnown && selectedTabs.size == 1) {
                        countsByTab[selectedTabs.first()]
                    } else null,
                    entries = entries,
                    hasMoreEntries = anyHasMore,
                    nextStartIndex = perTabStartIndex + pageSize,
                    availableTabs = availableTabs
                )
            )
        }

        override suspend fun extractChannelTabPage(
            classified: ClassifiedMediaUrl,
            tab: ChannelContentTab,
            startIndex: Int,
            pageSize: Int
        ): Result<CollectionPreview> {
            requestedPages += Triple(tab, startIndex, pageSize)
            onPageFetched?.invoke(tab, startIndex, pageSize)?.let { return it }

            val total = countsByTab[tab] ?: 0
            if (total <= 0 || startIndex > total) {
                return Result.success(
                    CollectionPreview(
                        sourceType = MediaSourceType.CHANNEL,
                        sourceUrl = classified.channelBaseUrl ?: classified.normalizedUrl,
                        canonicalId = classified.channelIdentifier ?: "@Simulated",
                        title = "Simulated Channel",
                        creator = "Simulated Creator",
                        thumbnailUrl = null,
                        declaredTotalCount = if (declaredTotalKnown) total else null,
                        entries = emptyList(),
                        hasMoreEntries = false,
                        nextStartIndex = startIndex
                    )
                )
            }

            val end = (startIndex + pageSize - 1).coerceAtMost(total)
            val chunk = (startIndex..end).map { idx -> entryBuilder(tab, idx) }
            val hasMore = end < total
            return Result.success(
                CollectionPreview(
                    sourceType = MediaSourceType.CHANNEL,
                    sourceUrl = classified.channelBaseUrl ?: classified.normalizedUrl,
                    canonicalId = classified.channelIdentifier ?: "@Simulated",
                    title = "Simulated Channel",
                    creator = "Simulated Creator",
                    thumbnailUrl = null,
                    declaredTotalCount = if (declaredTotalKnown) total else null,
                    entries = chunk,
                    hasMoreEntries = hasMore,
                    nextStartIndex = end + 1
                )
            )
        }

        override suspend fun cancelExtraction(requestId: String) = Unit
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
        queueManager = DownloadQueueManager(
            context = context,
            repository = downloadRepository,
            downloadEngine = holdingEngine,
            storagePublisher = storagePublisher,
            settingsDataStore = settingsDataStore,
            notificationManager = DownloadNotificationManager(context)
        )
    }

    @After
    fun tearDown() {
        holdingEngine.releaseAllCancelled()
        database.close()
    }

    @Test
    fun `auto discovery handles 10 entries across all tabs without repeated taps`() = runBlocking {
        val extractor = SimulatedChannelExtractor(
            countsByTab = mapOf(
                ChannelContentTab.VIDEOS to 5,
                ChannelContentTab.SHORTS to 3,
                ChannelContentTab.STREAMS to 2
            ),
            declaredTotalKnown = false
        )
        val coordinator = newCoordinator(extractor)
        val classified = MediaUrlClassifier.classify("https://www.youtube.com/@SmallCreator")
        val tabs = setOf(ChannelContentTab.VIDEOS, ChannelContentTab.SHORTS, ChannelContentTab.STREAMS)
        val preview = extractor.extractChannelPreview(classified, tabs, perTabStartIndex = 1, pageSize = 50).getOrThrow()

        val batchId = coordinator.startChannelAutoDiscoveryBatch(
            classified = classified,
            preview = preview,
            selectedTabs = tabs,
            targetLimit = null,
            qualityPreset = BulkQualityPreset.P720,
            audioOutputFormat = AudioOutputFormat.M4A,
            wifiOnly = true
        ).getOrThrow()
        coordinator.awaitDiscoveryIdle(batchId)

        val batch = batchRepository.getBatch(batchId)!!
        assertEquals(DiscoveryStatus.COMPLETED, batch.discoveryStatus)
        assertEquals(10, batch.discoveredCount)
        assertEquals(10, batch.selectedCount)
        assertNull(batch.knownTotalCount)
        assertEquals("Discovered 10 videos", batch.discoveryProgressLabel)
        assertEquals(1, batchRepository.countGlobalActiveItems())
    }

    @Test
    fun `auto discovery chunks 500 entries by 50 across multiple tabs until exhaustion`() = runBlocking {
        val extractor = SimulatedChannelExtractor(
            countsByTab = mapOf(
                ChannelContentTab.VIDEOS to 300,
                ChannelContentTab.SHORTS to 150,
                ChannelContentTab.STREAMS to 50
            ),
            declaredTotalKnown = false
        )
        val coordinator = newCoordinator(extractor)
        val classified = MediaUrlClassifier.classify("https://www.youtube.com/@MidCreator")
        val tabs = setOf(ChannelContentTab.VIDEOS, ChannelContentTab.SHORTS, ChannelContentTab.STREAMS)
        val preview = extractor.extractChannelPreview(classified, tabs, perTabStartIndex = 1, pageSize = 50).getOrThrow()

        val batchId = coordinator.startChannelAutoDiscoveryBatch(
            classified = classified,
            preview = preview,
            selectedTabs = tabs,
            targetLimit = null,
            qualityPreset = BulkQualityPreset.P1080,
            audioOutputFormat = AudioOutputFormat.M4A,
            wifiOnly = true
        ).getOrThrow()
        coordinator.awaitDiscoveryIdle(batchId)

        val batch = batchRepository.getBatch(batchId)!!
        assertEquals(DiscoveryStatus.COMPLETED, batch.discoveryStatus)
        assertEquals(500, batch.discoveredCount)
        assertEquals(500, batch.selectedCount)
        assertEquals("Discovered 500 videos", batch.discoveryProgressLabel)

        // Verify all chunk requests were bounded to 50
        assertTrue(extractor.requestedPages.all { it.third == 50 })

        // Verify all 3 per-tab checkpoints persisted as exhausted
        val checkpoints = batchRepository.getCheckpointsForBatch(batchId)
        assertEquals(3, checkpoints.size)
        assertTrue(checkpoints.all { it.isExhausted })

        // Verify bounded UI observation never loads all 500 items into RAM
        val observed = batchRepository.observeBatchesWithItems().first().first { it.batch.batchId == batchId }
        assertEquals(RoomBatchRepository.UI_PREVIEW_ITEM_LIMIT, observed.items.size)
        assertEquals(500, observed.totalItemCount)
    }

    @Test
    fun `auto discovery scales cleanly to 10000 simulated entries with bounded RAM observer`() = runBlocking {
        val extractor = SimulatedChannelExtractor(
            countsByTab = mapOf(
                ChannelContentTab.VIDEOS to 6_000,
                ChannelContentTab.SHORTS to 3_000,
                ChannelContentTab.STREAMS to 1_000
            ),
            declaredTotalKnown = false
        )
        val coordinator = newCoordinator(extractor)
        val classified = MediaUrlClassifier.classify("https://www.youtube.com/@MassiveArchive")
        val tabs = setOf(ChannelContentTab.VIDEOS, ChannelContentTab.SHORTS, ChannelContentTab.STREAMS)
        val preview = extractor.extractChannelPreview(classified, tabs, perTabStartIndex = 1, pageSize = 50).getOrThrow()

        val batchId = coordinator.startChannelAutoDiscoveryBatch(
            classified = classified,
            preview = preview,
            selectedTabs = tabs,
            targetLimit = null,
            qualityPreset = BulkQualityPreset.P720,
            audioOutputFormat = AudioOutputFormat.M4A,
            wifiOnly = false
        ).getOrThrow()
        coordinator.awaitDiscoveryIdle(batchId)

        val batch = batchRepository.getBatch(batchId)!!
        assertEquals(DiscoveryStatus.COMPLETED, batch.discoveryStatus)
        assertEquals(10_000, batch.discoveredCount)
        assertEquals(10_000, batch.selectedCount)
        assertEquals("Discovered 10000 videos", batch.discoveryProgressLabel)
        assertEquals(1, batchRepository.countGlobalActiveItems())

        // Observer stays strictly bounded to 50 items per batch even at 10,000 rows in Room
        val observed = batchRepository.observeBatchesWithItems().first().first { it.batch.batchId == batchId }
        assertEquals(RoomBatchRepository.UI_PREVIEW_ITEM_LIMIT, observed.items.size)
        assertEquals(10_000, observed.totalItemCount)
    }

    @Test
    fun `stopDiscovery stops channel discovery independently while queued downloads continue`() = runBlocking {
        val pageGate = CompletableDeferred<Unit>()
        val page2Reached = CompletableDeferred<Unit>()
        val extractor = SimulatedChannelExtractor(
            countsByTab = mapOf(ChannelContentTab.VIDEOS to 300),
            onPageFetched = { _, startIndex, _ ->
                if (startIndex == 51) {
                    page2Reached.complete(Unit)
                    pageGate.await()
                }
                null
            }
        )
        val coordinator = newCoordinator(extractor)
        val classified = MediaUrlClassifier.classify("https://www.youtube.com/@Stoppable")
        val preview = extractor.extractChannelPreview(classified, setOf(ChannelContentTab.VIDEOS), 1, 50).getOrThrow()

        val batchId = coordinator.startChannelAutoDiscoveryBatch(
            classified = classified,
            preview = preview,
            selectedTabs = setOf(ChannelContentTab.VIDEOS),
            targetLimit = null,
            qualityPreset = BulkQualityPreset.P720,
            audioOutputFormat = AudioOutputFormat.M4A,
            wifiOnly = false
        ).getOrThrow()

        page2Reached.await()
        coordinator.stopDiscovery(batchId)
        pageGate.complete(Unit)
        coordinator.awaitDiscoveryIdle(batchId)

        val afterStop = batchRepository.getBatch(batchId)!!
        assertEquals(DiscoveryStatus.STOPPED, afterStop.discoveryStatus)
        assertEquals(BatchStatus.ACTIVE, afterStop.status)
        assertEquals(50, afterStop.selectedCount)
        assertEquals(1, batchRepository.countGlobalActiveItems())
    }

    @Test
    fun `process restart recovery resumes discovery from persisted checkpoint without duplicates`() = runBlocking {
        val failOnStartIndex = AtomicInteger(101)
        val extractor = SimulatedChannelExtractor(
            countsByTab = mapOf(
                ChannelContentTab.VIDEOS to 150,
                ChannelContentTab.SHORTS to 50
            ),
            onPageFetched = { tab, startIndex, _ ->
                if (tab == ChannelContentTab.VIDEOS && startIndex == failOnStartIndex.get()) {
                    Result.failure(ShreyError.NetworkUnavailable("Simulated interruption"))
                } else null
            }
        )
        val coordinator1 = newCoordinator(extractor)
        val classified = MediaUrlClassifier.classify("https://www.youtube.com/@RestartTest")
        val tabs = setOf(ChannelContentTab.VIDEOS, ChannelContentTab.SHORTS)
        val preview = extractor.extractChannelPreview(classified, tabs, 1, 50).getOrThrow()

        val batchId = coordinator1.startChannelAutoDiscoveryBatch(
            classified = classified,
            preview = preview,
            selectedTabs = tabs,
            targetLimit = null,
            qualityPreset = BulkQualityPreset.P720,
            audioOutputFormat = AudioOutputFormat.M4A,
            wifiOnly = false
        ).getOrThrow()
        coordinator1.awaitDiscoveryIdle(batchId)

        // Should have paused at checkpoint nextStartIndex=101 on VIDEOS with 150 items already persisted (50+50 preview + 50 page2)
        val midBatch = batchRepository.getBatch(batchId)!!
        assertEquals(DiscoveryStatus.PAUSED, midBatch.discoveryStatus)
        val midCheckpoints = batchRepository.getCheckpointsForBatch(batchId).associateBy { it.tab }
        assertEquals(101, midCheckpoints[ChannelContentTab.VIDEOS]!!.nextStartIndex)
        assertFalse(midCheckpoints[ChannelContentTab.VIDEOS]!!.isExhausted)

        // Simulate app process kill & restart with a brand new coordinator instance
        failOnStartIndex.set(-1)
        val coordinator2 = newCoordinator(extractor)
        coordinator2.reconcileOnAppStart()

        val reconciled = batchRepository.getBatch(batchId)!!
        assertEquals(BatchStatus.PAUSED, reconciled.status)
        assertEquals(0, batchRepository.countGlobalActiveItems())

        // User taps Resume Queue -> resumes from persisted checkpoint 101 and finishes remaining 50 videos
        coordinator2.resumeBatchScheduling(batchId)
        coordinator2.awaitDiscoveryIdle(batchId)

        val resumed = batchRepository.getBatch(batchId)!!
        assertEquals(DiscoveryStatus.COMPLETED, resumed.discoveryStatus)
        assertEquals(200, resumed.selectedCount)
        assertEquals(0, resumed.skippedCount) // zero duplicate insertions across restart!
        assertEquals(1, batchRepository.countGlobalActiveItems())
    }

    @Test
    fun `global concurrency limit enforces at most 1 active download across multiple batches and standalone downloads`() = runBlocking {
        val coordinator = newCoordinator(collectionExtractor = null)

        // Start a standalone single-video download first
        val standaloneId = "standalone_1"
        queueManager.enqueue(
            DownloadRequest(
                downloadId = standaloneId,
                sourceUrl = "https://www.youtube.com/watch?v=solo1",
                mediaTitle = "Solo Video",
                creator = "Solo",
                thumbnailUrl = null,
                mode = DownloadMode.VIDEO_AUDIO,
                selectedFormatId = "best",
                formatSelector = "best",
                outputExtension = "mp4",
                qualityLabel = "Best",
                audioOutputFormat = AudioOutputFormat.M4A,
                requiresMerge = false,
                destinationTreeUri = null,
                mediaId = "solo1"
            )
        )

        // Start Batch 1 & Batch 2 while standalone download is active
        val batch1 = coordinator.startBatch(
            preview = simplePreview("PL1", listOf("b1_v1", "b1_v2")),
            selectedEntries = listOf(
                CollectionEntry("b1_v1", 1, "B1 V1", "https://www.youtube.com/watch?v=b1_v1"),
                CollectionEntry("b1_v2", 2, "B1 V2", "https://www.youtube.com/watch?v=b1_v2")
            ),
            qualityPreset = BulkQualityPreset.P720,
            audioOutputFormat = AudioOutputFormat.M4A,
            wifiOnly = false
        ).getOrThrow()

        val batch2 = coordinator.startBatch(
            preview = simplePreview("PL2", listOf("b2_v1")),
            selectedEntries = listOf(
                CollectionEntry("b2_v1", 1, "B2 V1", "https://www.youtube.com/watch?v=b2_v1")
            ),
            qualityPreset = BulkQualityPreset.P720,
            audioOutputFormat = AudioOutputFormat.M4A,
            wifiOnly = false
        ).getOrThrow()

        // While standalone is active, 0 batch items should be dispatched
        assertEquals(0, batchRepository.countGlobalActiveItems())

        // Complete standalone download -> Batch 1 gets the single global slot, Batch 2 still waits
        holdingEngine.complete(standaloneId)
        delay(150)
        assertEquals(1, batchRepository.countGlobalActiveItems())
        assertEquals(1, batchRepository.getActiveItemsForBatch(batch1).size)
        assertEquals(0, batchRepository.getActiveItemsForBatch(batch2).size)
    }

    @Test
    fun `deduplication skips queued active completed standalone and unavailable entries across tabs`() = runBlocking {
        // Pre-seed a completed standalone download for mediaId = "dup_standalone"
        val soloReq = DownloadRequest(
            downloadId = "solo_done",
            sourceUrl = "https://www.youtube.com/watch?v=dup_standalone",
            mediaTitle = "Already Downloaded Solo",
            creator = "Creator",
            thumbnailUrl = null,
            mode = DownloadMode.VIDEO_AUDIO,
            selectedFormatId = "best",
            formatSelector = "best",
            outputExtension = "mp4",
            qualityLabel = "Best",
            audioOutputFormat = AudioOutputFormat.M4A,
            requiresMerge = false,
            destinationTreeUri = null,
            mediaId = "dup_standalone"
        )
        downloadRepository.enqueue(soloReq)
        downloadRepository.markCompleted("solo_done", "content://media/1", "solo.mp4", "video/mp4", 100L)

        // Create channel where Videos has dup_standalone, private item, live item, shared_id, and unique_1
        // and Shorts also contains shared_id (cross-tab duplicate!)
        val extractor = SimulatedChannelExtractor(
            countsByTab = mapOf(
                ChannelContentTab.VIDEOS to 5,
                ChannelContentTab.SHORTS to 2
            ),
            entryBuilder = { tab, idx ->
                when (tab) {
                    ChannelContentTab.VIDEOS -> when (idx) {
                        1 -> CollectionEntry("dup_standalone", 1, "Solo Dup", "https://youtube.com/watch?v=dup_standalone", tab = tab)
                        2 -> CollectionEntry("priv_1", 2, "[Private video]", "https://youtube.com/watch?v=priv_1", availability = EntryAvailability.PRIVATE_OR_MEMBERS, tab = tab)
                        3 -> CollectionEntry("live_1", 3, "Live Now", "https://youtube.com/watch?v=live_1", availability = EntryAvailability.LIVE_ACTIVE, tab = tab)
                        4 -> CollectionEntry("shared_id", 4, "Cross Tab Video", "https://youtube.com/watch?v=shared_id", tab = tab)
                        else -> CollectionEntry("unique_v", 5, "Unique Video", "https://youtube.com/watch?v=unique_v", tab = tab)
                    }
                    else -> when (idx) {
                        1 -> CollectionEntry("shared_id", 1, "Cross Tab Short", "https://youtube.com/watch?v=shared_id", tab = tab)
                        else -> CollectionEntry("unique_s", 2, "Unique Short", "https://youtube.com/watch?v=unique_s", tab = tab)
                    }
                }
            }
        )
        val coordinator = newCoordinator(extractor)
        val classified = MediaUrlClassifier.classify("https://www.youtube.com/@DedupChannel")
        val tabs = setOf(ChannelContentTab.VIDEOS, ChannelContentTab.SHORTS)
        val preview = extractor.extractChannelPreview(classified, tabs, 1, 50).getOrThrow()

        val batchId = coordinator.startChannelAutoDiscoveryBatch(
            classified = classified,
            preview = preview,
            selectedTabs = tabs,
            targetLimit = null,
            qualityPreset = BulkQualityPreset.P720,
            audioOutputFormat = AudioOutputFormat.M4A,
            wifiOnly = false
        ).getOrThrow()
        coordinator.awaitDiscoveryIdle(batchId)

        val items = batchRepository.getItemsForBatch(batchId)
        val downloadableStatuses = setOf(BatchItemStatus.QUEUED, BatchItemStatus.ACTIVE)
        val downloadableMediaIds = items.filter { it.status in downloadableStatuses }.map { it.mediaId }

        assertEquals(setOf("shared_id", "unique_v", "unique_s"), downloadableMediaIds.toSet())
        assertEquals(3, downloadableMediaIds.size) // no duplicates!
        assertTrue(items.any { it.mediaId == "dup_standalone" && it.status == BatchItemStatus.SKIPPED })
        assertTrue(items.any { it.mediaId == "priv_1" && it.status == BatchItemStatus.UNAVAILABLE })
        assertTrue(items.any { it.mediaId == "live_1" && it.status == BatchItemStatus.UNAVAILABLE })
    }

    @Test
    fun `wifi only pauses channel discovery on metered network`() = runBlocking {
        val extractor = SimulatedChannelExtractor(
            countsByTab = mapOf(ChannelContentTab.VIDEOS to 120)
        )
        val coordinator = newCoordinator(extractor, isUnmetered = { false })
        val classified = MediaUrlClassifier.classify("https://www.youtube.com/@WifiOnly")
        val preview = extractor.extractChannelPreview(classified, setOf(ChannelContentTab.VIDEOS), 1, 50).getOrThrow()

        val batchId = coordinator.startChannelAutoDiscoveryBatch(
            classified = classified,
            preview = preview,
            selectedTabs = setOf(ChannelContentTab.VIDEOS),
            targetLimit = null,
            qualityPreset = BulkQualityPreset.P720,
            audioOutputFormat = AudioOutputFormat.M4A,
            wifiOnly = true
        ).getOrThrow()
        coordinator.awaitDiscoveryIdle(batchId)

        val batch = batchRepository.getBatch(batchId)!!
        assertEquals(DiscoveryStatus.PAUSED, batch.discoveryStatus)
        assertEquals(BatchStatus.PAUSED, batch.status)
        assertTrue(batch.discoveryError.orEmpty().contains("Wi-Fi", ignoreCase = true))
    }

    @Test
    fun `room migration 2 to 3 preserves v2 batches and adds discovery checkpoints table`() {
        val dbName = "migration_2_3_test_${System.nanoTime()}.db"
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `downloads` (
                            `id` TEXT NOT NULL, `sourceUrl` TEXT NOT NULL, `title` TEXT NOT NULL,
                            `creator` TEXT, `thumbnailUrl` TEXT, `selectedFormatId` TEXT NOT NULL,
                            `formatSelector` TEXT NOT NULL, `qualityLabel` TEXT NOT NULL,
                            `requestedMode` TEXT NOT NULL, `outputContainer` TEXT NOT NULL,
                            `audioOutputFormat` TEXT NOT NULL, `thumbnailOutputFormat` TEXT NOT NULL,
                            `requiresMerge` INTEGER NOT NULL, `status` TEXT NOT NULL,
                            `progress` REAL NOT NULL, `downloadedBytes` INTEGER, `totalBytes` INTEGER,
                            `speedText` TEXT, `etaSeconds` INTEGER, `statusMessage` TEXT,
                            `destinationTreeUri` TEXT, `destinationUri` TEXT, `outputDisplayName` TEXT,
                            `mimeType` TEXT, `createdAt` INTEGER NOT NULL, `startedAt` INTEGER,
                            `completedAt` INTEGER, `lastError` TEXT, `technicalError` TEXT,
                            `retryCount` INTEGER NOT NULL, `batchId` TEXT, `mediaId` TEXT,
                            `relativeSubfolder` TEXT, PRIMARY KEY(`id`)
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `download_batches` (
                            `batchId` TEXT NOT NULL, `sourceUrl` TEXT NOT NULL, `canonicalId` TEXT NOT NULL,
                            `sourceType` TEXT NOT NULL, `displayName` TEXT NOT NULL, `creator` TEXT,
                            `thumbnailUrl` TEXT, `status` TEXT NOT NULL, `qualityPreset` TEXT NOT NULL,
                            `downloadMode` TEXT NOT NULL, `audioOutputFormat` TEXT NOT NULL,
                            `wifiOnly` INTEGER NOT NULL, `discoveredCount` INTEGER NOT NULL,
                            `selectedCount` INTEGER NOT NULL, `completedCount` INTEGER NOT NULL,
                            `failedCount` INTEGER NOT NULL, `skippedCount` INTEGER NOT NULL,
                            `activeCount` INTEGER NOT NULL, `totalBytesDownloaded` INTEGER NOT NULL,
                            `checkpointNextIndex` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL,
                            `updatedAt` INTEGER NOT NULL, `lastError` TEXT, PRIMARY KEY(`batchId`)
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `batch_items` (
                            `itemId` TEXT NOT NULL, `batchId` TEXT NOT NULL, `downloadId` TEXT,
                            `mediaId` TEXT NOT NULL, `position` INTEGER NOT NULL, `title` TEXT NOT NULL,
                            `sourceUrl` TEXT NOT NULL, `thumbnailUrl` TEXT, `durationSeconds` INTEGER,
                            `tab` TEXT, `status` TEXT NOT NULL, `progressPercent` REAL NOT NULL,
                            `downloadedBytes` INTEGER, `totalBytes` INTEGER, `speedText` TEXT,
                            `etaSeconds` INTEGER, `destinationUri` TEXT, `outputDisplayName` TEXT,
                            `mimeType` TEXT, `errorMessage` TEXT, `technicalError` TEXT,
                            `retryCount` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                            PRIMARY KEY(`itemId`)
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_batch_items_batchId` ON `batch_items` (`batchId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_batch_items_downloadId` ON `batch_items` (`downloadId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_batch_items_mediaId` ON `batch_items` (`mediaId`)")
                    db.execSQL(
                        """
                        INSERT INTO `download_batches` VALUES (
                            'v2_batch_1', 'https://youtube.com/playlist?list=PLv2', 'PLv2', 'PLAYLIST',
                            'V2 Saved Playlist', 'Creator', NULL, 'COMPLETED', 'P1080', 'VIDEO_AUDIO',
                            'M4A', 1, 2, 2, 2, 0, 0, 0, 1000, 3, 1700000000, 1700000010, NULL
                        )
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        helper.writableDatabase.close()
        helper.close()

        val migratedRoom = Room.databaseBuilder(context, ShreyDatabase::class.java, dbName)
            .addMigrations(ShreyDatabase.MIGRATION_1_2, ShreyDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()

        runBlocking {
            val repo = RoomBatchRepository(migratedRoom.batchDao())
            val batch = repo.getBatch("v2_batch_1")
            assertNotNull(batch)
            assertEquals("V2 Saved Playlist", batch!!.displayName)
            assertEquals(DiscoveryStatus.COMPLETED, batch.discoveryStatus)
            assertEquals(0, repo.getCheckpointsForBatch("v2_batch_1").size)
        }
        migratedRoom.close()
        context.deleteDatabase(dbName)
    }

    private fun newCoordinator(
        collectionExtractor: CollectionExtractor?,
        isUnmetered: () -> Boolean = { true }
    ): BatchDownloadCoordinator = BatchDownloadCoordinator(
        context = context,
        batchRepository = batchRepository,
        queueManager = queueManager,
        storagePublisher = storagePublisher,
        settingsDataStore = settingsDataStore,
        collectionExtractor = collectionExtractor,
        isUnmeteredNetworkProvider = isUnmetered,
        discoveryChunkSize = 50
    )

    private fun simplePreview(id: String, ids: List<String>): CollectionPreview = CollectionPreview(
        sourceType = MediaSourceType.PLAYLIST,
        sourceUrl = "https://www.youtube.com/playlist?list=$id",
        canonicalId = id,
        title = "Playlist $id",
        creator = "Tester",
        thumbnailUrl = null,
        declaredTotalCount = ids.size,
        entries = ids.mapIndexed { i, mediaId ->
            CollectionEntry(mediaId, i + 1, "Video $mediaId", "https://www.youtube.com/watch?v=$mediaId")
        },
        hasMoreEntries = false,
        nextStartIndex = ids.size + 1
    )
}
