package com.shrey.downloader.core.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.shrey.downloader.core.engine.CollectionExtractor
import com.shrey.downloader.core.engine.ErrorTranslator
import com.shrey.downloader.core.storage.MediaStoragePublisher
import com.shrey.downloader.core.validation.ClassifiedMediaUrl
import com.shrey.downloader.core.validation.FilenameSanitizer
import com.shrey.downloader.core.validation.MediaUrlClassifier
import com.shrey.downloader.data.local.SettingsDataStore
import com.shrey.downloader.data.repository.BatchRepository
import com.shrey.downloader.domain.model.AudioOutputFormat
import com.shrey.downloader.domain.model.BatchItem
import com.shrey.downloader.domain.model.BatchItemStatus
import com.shrey.downloader.domain.model.BatchStatus
import com.shrey.downloader.domain.model.BatchWithItems
import com.shrey.downloader.domain.model.BulkQualityPreset
import com.shrey.downloader.domain.model.ChannelContentTab
import com.shrey.downloader.domain.model.ChannelTabCheckpoint
import com.shrey.downloader.domain.model.CollectionEntry
import com.shrey.downloader.domain.model.CollectionPreview
import com.shrey.downloader.domain.model.DiscoveryStatus
import com.shrey.downloader.domain.model.DownloadBatch
import com.shrey.downloader.domain.model.DownloadProgress
import com.shrey.downloader.domain.model.DownloadRequest
import com.shrey.downloader.domain.model.MediaSourceType
import com.shrey.downloader.domain.model.ShreyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified orchestration layer for Playlist and YouTube Channel bulk downloads.
 *
 * Architecture guarantees:
 * - Extends the existing [DownloadQueueManager] + [YtDlpDownloadEngine] (no competing download engine).
 * - Enforces ONE global active download limit (concurrency = 1) across all batches and standalone
 *   single-video downloads.
 * - Supports automatic chunked channel-wide discovery (50 entries per chunk) across selected
 *   Videos, Shorts, and Completed Streams tabs with persistent per-tab Room checkpoints.
 * - Never loads an entire large channel into RAM; queues discovered chunks progressively.
 * - Deduplicates against queued, active, and completed records across all batches and standalone downloads.
 * - Supports independent cancellation of discovery vs. downloads and clean restart recovery.
 */
class BatchDownloadCoordinator(
    private val context: Context,
    private val batchRepository: BatchRepository,
    private val queueManager: DownloadQueueManager,
    private val storagePublisher: MediaStoragePublisher,
    private val settingsDataStore: SettingsDataStore,
    private val collectionExtractor: CollectionExtractor? = null,
    private val isUnmeteredNetworkProvider: () -> Boolean = { checkUnmeteredNetwork(context) },
    private val discoveryChunkSize: Int = CollectionExtractor.DEFAULT_PAGE_SIZE
) : DownloadLifecycleListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scheduleMutex = Mutex()
    private val activeDiscoveryJobs = ConcurrentHashMap<String, Job>()

    val batchesFlow: Flow<List<BatchWithItems>> = batchRepository.observeBatchesWithItems()

    init {
        queueManager.setLifecycleListener(this)
    }

    /**
     * Reconciles interrupted batch items and discovery loops after app process termination:
     * - Completed items remain COMPLETED and are never re-downloaded.
     * - Previously IN-FLIGHT (`ACTIVE`) items return cleanly to `QUEUED`.
     * - Previously RUNNING channel discovery transitions to `PAUSED` with per-tab checkpoints intact.
     * - Active/discovering batches transition to `PAUSED` until the user explicitly taps Resume.
     */
    suspend fun reconcileOnAppStart() = scheduleMutex.withLock {
        val resetCount = batchRepository.resetInFlightItemsToQueued()
        val activeBatches = batchRepository.getActiveBatches()
        for (batch in activeBatches) {
            activeDiscoveryJobs.remove(batch.batchId)?.cancel()
            val wasDiscovering = batch.discoveryStatus == DiscoveryStatus.RUNNING
            if (wasDiscovering) {
                batchRepository.updateDiscoveryStatus(
                    batchId = batch.batchId,
                    discoveryStatus = DiscoveryStatus.PAUSED,
                    discoveryError = "Discovery paused when app stopped. Tap Resume to continue from saved checkpoint."
                )
            }

            val hasQueued = batchRepository.getNextQueuedItem(batch.batchId) != null
            if (hasQueued || wasDiscovering || batch.discoveryStatus == DiscoveryStatus.PAUSED) {
                batchRepository.updateBatchStatus(
                    batchId = batch.batchId,
                    status = BatchStatus.PAUSED,
                    lastError = when {
                        wasDiscovering ->
                            "Interrupted during channel discovery. Tap Resume Queue to continue from saved checkpoint."
                        resetCount > 0 ->
                            "Interrupted when app stopped. Tap Resume Queue to continue remaining videos."
                        else ->
                            "Paused after restart. Tap Resume Queue to continue."
                    }
                )
                batchRepository.recomputeAndSaveBatchAggregates(batch.batchId)
            } else {
                batchRepository.recomputeAndSaveBatchAggregates(batch.batchId)
            }
        }
    }

    /**
     * Creates a persistent batch from user-confirmed playlist or pre-selected entries and schedules
     * downloads under the global concurrency limit.
     */
    suspend fun startBatch(
        preview: CollectionPreview,
        selectedEntries: List<CollectionEntry>,
        qualityPreset: BulkQualityPreset,
        audioOutputFormat: AudioOutputFormat,
        wifiOnly: Boolean
    ): Result<String> {
        if (selectedEntries.isEmpty()) {
            return Result.failure(IllegalArgumentException("Select at least one available video to download."))
        }

        if (!storagePublisher.checkAvailableSpace(MIN_BATCH_START_BYTES)) {
            return Result.failure(
                ShreyError.InsufficientStorage("Not enough free device storage to start this bulk download.")
            )
        }

        val now = System.currentTimeMillis()
        val batchId = "batch_${UUID.randomUUID()}"
        val existingMediaIds = batchRepository.findExistingMediaIds(selectedEntries.map { it.mediaId })
        val seenInBatch = LinkedHashSet<String>()
        var skippedCount = 0

        val batchItems = selectedEntries.mapIndexed { index, entry ->
            val isDuplicateInBatch = !seenInBatch.add(entry.mediaId)
            val alreadyKnown = !isDuplicateInBatch && entry.mediaId in existingMediaIds
            val initialStatus = when {
                !entry.availability.isDownloadable -> {
                    skippedCount++
                    BatchItemStatus.UNAVAILABLE
                }
                isDuplicateInBatch || alreadyKnown -> {
                    skippedCount++
                    BatchItemStatus.SKIPPED
                }
                else -> BatchItemStatus.QUEUED
            }
            buildBatchItem(
                batchId = batchId,
                entry = entry,
                position = if (entry.position > 0) entry.position else index + 1,
                initialStatus = initialStatus,
                now = now
            )
        }

        val hasQueued = batchItems.any { it.status == BatchItemStatus.QUEUED }
        val batch = DownloadBatch(
            batchId = batchId,
            sourceUrl = preview.sourceUrl,
            canonicalId = preview.canonicalId,
            sourceType = preview.sourceType,
            displayName = preview.title,
            creator = preview.creator,
            thumbnailUrl = preview.thumbnailUrl,
            status = if (hasQueued) BatchStatus.ACTIVE else BatchStatus.COMPLETED,
            qualityPreset = qualityPreset,
            downloadMode = qualityPreset.toDownloadMode(),
            audioOutputFormat = audioOutputFormat,
            wifiOnly = wifiOnly,
            discoveredCount = preview.declaredTotalCount ?: preview.entries.size,
            selectedCount = batchItems.size,
            completedCount = 0,
            failedCount = 0,
            skippedCount = skippedCount,
            activeCount = 0,
            totalBytesDownloaded = 0L,
            checkpointNextIndex = preview.nextStartIndex,
            createdAt = now,
            updatedAt = now,
            lastError = null,
            discoveryStatus = DiscoveryStatus.COMPLETED,
            targetLimit = null,
            knownTotalCount = preview.declaredTotalCount,
            discoveryError = null,
            selectedTabs = emptyList()
        )

        batchRepository.createBatchWithItems(batch, batchItems)
        if (hasQueued) {
            scheduleNextItem(batchId)
        }
        return Result.success(batchId)
    }

    /**
     * Starts an automatic channel-wide discovery & progressive batch download across [selectedTabs]
     * after explicit user confirmation.
     *
     * - Seeds any entries already fetched during initial preview without re-fetching page 1.
     * - Persists per-tab discovery checkpoints in Room.
     * - Continues discovering remaining chunks of 50 entries automatically until all selected tabs
     *   are exhausted (or [targetLimit] downloadable items are reached).
     * - Queues discovered chunks progressively so downloads begin immediately without loading
     *   large channels into RAM.
     */
    suspend fun startChannelAutoDiscoveryBatch(
        classified: ClassifiedMediaUrl,
        preview: CollectionPreview,
        selectedTabs: Set<ChannelContentTab>,
        targetLimit: Int?,
        qualityPreset: BulkQualityPreset,
        audioOutputFormat: AudioOutputFormat,
        wifiOnly: Boolean
    ): Result<String> {
        val orderedTabs = ChannelContentTab.entries.filter { it in selectedTabs }
            .ifEmpty { listOf(ChannelContentTab.VIDEOS) }

        if (!storagePublisher.checkAvailableSpace(MIN_BATCH_START_BYTES)) {
            return Result.failure(
                ShreyError.InsufficientStorage("Not enough free device storage to start this channel download.")
            )
        }

        val now = System.currentTimeMillis()
        val batchId = "batch_${UUID.randomUUID()}"
        val previewEntriesForTabs = preview.entries.filter { it.tab == null || it.tab in orderedTabs }
        val existingMediaIds = batchRepository.findExistingMediaIds(previewEntriesForTabs.map { it.mediaId })
        val seenInBatch = LinkedHashSet<String>()

        var skippedCount = 0
        var queuedDownloadable = 0
        var position = 0
        val initialItems = mutableListOf<BatchItem>()

        for (entry in previewEntriesForTabs) {
            if (targetLimit != null && queuedDownloadable >= targetLimit) break
            val isDupInChunk = !seenInBatch.add(entry.mediaId)
            val alreadyKnown = !isDupInChunk && entry.mediaId in existingMediaIds
            val itemStatus = when {
                !entry.availability.isDownloadable -> {
                    skippedCount++
                    BatchItemStatus.UNAVAILABLE
                }
                isDupInChunk || alreadyKnown -> {
                    skippedCount++
                    BatchItemStatus.SKIPPED
                }
                else -> {
                    queuedDownloadable++
                    BatchItemStatus.QUEUED
                }
            }
            position++
            initialItems += buildBatchItem(
                batchId = batchId,
                entry = entry,
                position = position,
                initialStatus = itemStatus,
                now = now
            )
        }

        val limitAlreadySatisfied = targetLimit != null && queuedDownloadable >= targetLimit
        val nextPageIndexAfterPreview = preview.nextStartIndex.coerceAtLeast(1 + discoveryChunkSize)

        val checkpoints = orderedTabs.map { tab ->
            val tabPreviewCount = previewEntriesForTabs.count { it.tab == tab }
            val note = preview.unavailableTabNotes[tab]
            val tabExhausted = when {
                limitAlreadySatisfied -> true
                note != null -> true
                previewEntriesForTabs.isEmpty() -> false
                tabPreviewCount == 0 && preview.availableTabs.isNotEmpty() -> true
                !preview.hasMoreEntries -> true
                else -> false
            }
            ChannelTabCheckpoint(
                batchId = batchId,
                tab = tab,
                nextStartIndex = if (tabPreviewCount > 0) nextPageIndexAfterPreview else 1,
                discoveredCount = tabPreviewCount,
                isExhausted = tabExhausted,
                lastError = note,
                updatedAt = now
            )
        }

        val canDiscoverMore = collectionExtractor != null && checkpoints.any { !it.isExhausted }
        val initialDiscoveryStatus = if (canDiscoverMore) DiscoveryStatus.RUNNING else DiscoveryStatus.COMPLETED
        val hasQueued = initialItems.any { it.status == BatchItemStatus.QUEUED }
        val initialStatus = when {
            hasQueued -> BatchStatus.ACTIVE
            canDiscoverMore -> BatchStatus.DISCOVERING
            else -> BatchStatus.COMPLETED
        }

        // Preserve known total ONLY when the source genuinely reported an exact count; never invent one.
        val knownTotal = if (orderedTabs.size == 1) preview.declaredTotalCount?.takeIf { it > 0 } else null

        val batch = DownloadBatch(
            batchId = batchId,
            sourceUrl = classified.channelBaseUrl ?: preview.sourceUrl,
            canonicalId = preview.canonicalId,
            sourceType = MediaSourceType.CHANNEL,
            displayName = preview.title,
            creator = preview.creator,
            thumbnailUrl = preview.thumbnailUrl,
            status = initialStatus,
            qualityPreset = qualityPreset,
            downloadMode = qualityPreset.toDownloadMode(),
            audioOutputFormat = audioOutputFormat,
            wifiOnly = wifiOnly,
            discoveredCount = initialItems.size,
            selectedCount = initialItems.size,
            completedCount = 0,
            failedCount = 0,
            skippedCount = skippedCount,
            activeCount = 0,
            totalBytesDownloaded = 0L,
            checkpointNextIndex = nextPageIndexAfterPreview,
            createdAt = now,
            updatedAt = now,
            lastError = null,
            discoveryStatus = initialDiscoveryStatus,
            targetLimit = targetLimit,
            knownTotalCount = knownTotal,
            discoveryError = null,
            selectedTabs = orderedTabs
        )

        batchRepository.createBatchWithItemsAndCheckpoints(batch, initialItems, checkpoints)

        if (hasQueued) {
            scheduleNextItem(batchId)
        }
        if (canDiscoverMore) {
            launchDiscoveryLoop(batchId)
        }

        return Result.success(batchId)
    }

    /**
     * Stops automatic channel discovery independently without cancelling already-queued or active downloads.
     */
    suspend fun stopDiscovery(batchId: String) {
        activeDiscoveryJobs.remove(batchId)?.cancel()
        val batch = batchRepository.getBatch(batchId) ?: return
        if (!batch.discoveryStatus.isTerminal) {
            batchRepository.updateDiscoveryStatus(
                batchId = batchId,
                discoveryStatus = DiscoveryStatus.STOPPED,
                discoveryError = "Discovery stopped — remaining queued downloads will continue."
            )
            batchRepository.recomputeAndSaveBatchAggregates(batchId)
        }
    }

    /**
     * Awaits completion of any running discovery coroutine for [batchId] (used by deterministic tests).
     */
    suspend fun awaitDiscoveryIdle(batchId: String) {
        activeDiscoveryJobs[batchId]?.join()
    }

    /**
     * Pauses scheduling of subsequent queued items (and pauses active channel discovery while
     * preserving per-tab checkpoints for resume).
     */
    suspend fun pauseBatchScheduling(batchId: String) = scheduleMutex.withLock {
        val batch = batchRepository.getBatch(batchId) ?: return@withLock
        if (batch.status.isTerminal) return@withLock
        activeDiscoveryJobs.remove(batchId)?.cancel()
        if (batch.discoveryStatus == DiscoveryStatus.RUNNING) {
            batchRepository.updateDiscoveryStatus(
                batchId = batchId,
                discoveryStatus = DiscoveryStatus.PAUSED,
                discoveryError = "Discovery paused — tap Resume Queue to continue from saved checkpoint."
            )
        }
        batchRepository.updateBatchStatus(
            batchId = batchId,
            status = BatchStatus.PAUSED,
            lastError = "Queue paused — remaining videos will wait until resumed."
        )
    }

    suspend fun resumeBatchScheduling(batchId: String) {
        var shouldResumeDiscovery = false
        scheduleMutex.withLock {
            val batch = batchRepository.getBatch(batchId) ?: return@withLock
            if (batch.status == BatchStatus.CANCELLED) return@withLock
            if (batch.discoveryStatus == DiscoveryStatus.PAUSED && collectionExtractor != null) {
                batchRepository.updateDiscoveryStatus(
                    batchId = batchId,
                    discoveryStatus = DiscoveryStatus.RUNNING,
                    discoveryError = null
                )
                shouldResumeDiscovery = true
            }
            val nextStatus = if (batchRepository.getNextQueuedItem(batchId) != null || !shouldResumeDiscovery) {
                BatchStatus.ACTIVE
            } else {
                BatchStatus.DISCOVERING
            }
            batchRepository.updateBatchStatus(batchId, nextStatus, lastError = null)
        }
        if (shouldResumeDiscovery) {
            launchDiscoveryLoop(batchId)
        }
        scheduleNextItem(batchId)
    }

    suspend fun cancelSingleItem(batchId: String, itemId: String) {
        val item = batchRepository.getItemById(itemId) ?: return
        item.downloadId?.let { dlId ->
            queueManager.cancel(dlId)
        }
        batchRepository.markItemCancelled(itemId)
        batchRepository.recomputeAndSaveBatchAggregates(batchId)
        scheduleNextItem(batchId)
    }

    suspend fun cancelBatch(batchId: String) {
        activeDiscoveryJobs.remove(batchId)?.cancel()
        val batch = batchRepository.getBatch(batchId)
        if (batch != null && !batch.discoveryStatus.isTerminal) {
            batchRepository.updateDiscoveryStatus(
                batchId = batchId,
                discoveryStatus = DiscoveryStatus.STOPPED,
                discoveryError = "Cancelled by user"
            )
        }
        batchRepository.getActiveItemsForBatch(batchId)
            .filter { !it.downloadId.isNullOrBlank() }
            .forEach { active ->
                queueManager.cancel(active.downloadId!!)
            }
        batchRepository.cancelPendingItemsInBatch(batchId)
        batchRepository.updateBatchStatus(batchId, BatchStatus.CANCELLED, lastError = "Cancelled by user")
        batchRepository.recomputeAndSaveBatchAggregates(batchId)
        scheduleNextItem(null)
    }

    suspend fun retryFailedItems(batchId: String) {
        val count = batchRepository.requeueFailedItemsInBatch(batchId)
        if (count > 0) {
            batchRepository.updateBatchStatus(batchId, BatchStatus.ACTIVE, lastError = null)
            batchRepository.recomputeAndSaveBatchAggregates(batchId)
            scheduleNextItem(batchId)
        }
    }

    suspend fun retrySingleItem(batchId: String, itemId: String) {
        batchRepository.requeueSingleItem(itemId)
        batchRepository.updateBatchStatus(batchId, BatchStatus.ACTIVE, lastError = null)
        batchRepository.recomputeAndSaveBatchAggregates(batchId)
        scheduleNextItem(batchId)
    }

    suspend fun removeBatch(batchId: String) {
        val batch = batchRepository.getBatch(batchId)
        if (batch != null && (!batch.status.isTerminal || !batch.discoveryStatus.isTerminal)) {
            cancelBatch(batchId)
        }
        batchRepository.deleteBatch(batchId)
    }

    suspend fun recordDiscoveryCheckpoint(batchId: String, nextIndex: Int, discoveredCount: Int) {
        batchRepository.updateBatchCheckpoint(batchId, nextIndex, discoveredCount)
    }

    /**
     * Enforces ONE global active download limit across all batches and standalone downloads.
     * Picks the next queued item from [preferredBatchId] if active, or otherwise from the oldest
     * active batch waiting for the global transfer slot.
     */
    suspend fun scheduleNextItem(preferredBatchId: String? = null) = scheduleMutex.withLock {
        // Global concurrency limit = 1 across ALL batches and standalone single-video downloads
        if (batchRepository.countGlobalActiveItems() >= MAX_GLOBAL_ACTIVE_DOWNLOADS) {
            return@withLock
        }
        if (queueManager.hasActiveOrPendingStandaloneDownload()) {
            return@withLock
        }

        val candidates = buildList {
            if (!preferredBatchId.isNullOrBlank()) {
                batchRepository.getBatch(preferredBatchId)?.let { add(it) }
            }
            batchRepository.getSchedulableActiveBatches().forEach { b ->
                if (none { it.batchId == b.batchId }) add(b)
            }
        }

        for (batch in candidates) {
            if (batch.status != BatchStatus.ACTIVE && batch.status != BatchStatus.DISCOVERING) continue

            val next = batchRepository.getNextQueuedItem(batch.batchId)
            if (next == null) {
                batchRepository.recomputeAndSaveBatchAggregates(batch.batchId)
                continue
            }

            // Enforce Wi-Fi-only bulk download safeguard
            if (batch.wifiOnly && !isUnmeteredNetworkProvider()) {
                batchRepository.updateBatchStatus(
                    batchId = batch.batchId,
                    status = BatchStatus.PAUSED,
                    lastError = "Waiting for Wi-Fi (Wi-Fi-only bulk mode enabled). Tap Resume Queue on Wi-Fi."
                )
                continue
            }

            // Guard available storage before dispatching each item
            if (!storagePublisher.checkAvailableSpace(MIN_ITEM_START_BYTES)) {
                batchRepository.updateBatchStatus(
                    batchId = batch.batchId,
                    status = BatchStatus.PAUSED,
                    lastError = "Paused — low device storage. Free up space and tap Resume Queue."
                )
                return@withLock
            }

            val currentSettings = settingsDataStore.settingsFlow.first()
            val downloadId = "dl_${UUID.randomUUID()}"
            val subfolder = buildRelativeSubfolder(batch)
            val formatSelector = batch.qualityPreset.toFormatSelector(batch.audioOutputFormat)
            val outputExt = batch.qualityPreset.toOutputExtension(batch.audioOutputFormat)
            val requiresMerge = !batch.qualityPreset.isAudioOnly

            batchRepository.updateItemProgress(
                itemId = next.itemId,
                downloadId = downloadId,
                status = BatchItemStatus.ACTIVE,
                progressPercent = 0f,
                downloadedBytes = null,
                totalBytes = next.totalBytes,
                speedText = null,
                etaSeconds = null
            )
            batchRepository.recomputeAndSaveBatchAggregates(batch.batchId)

            val request = DownloadRequest(
                downloadId = downloadId,
                sourceUrl = next.sourceUrl,
                mediaTitle = next.title,
                creator = batch.creator,
                thumbnailUrl = next.thumbnailUrl ?: batch.thumbnailUrl,
                mode = batch.downloadMode,
                selectedFormatId = batch.qualityPreset.name,
                formatSelector = formatSelector,
                outputExtension = outputExt,
                qualityLabel = "${batch.downloadMode.label} · ${batch.qualityPreset.label}",
                audioOutputFormat = batch.audioOutputFormat,
                requiresMerge = requiresMerge,
                destinationTreeUri = currentSettings.customDestinationUri,
                batchId = batch.batchId,
                mediaId = next.mediaId,
                relativeSubfolder = subfolder
            )
            queueManager.enqueue(request)
            return@withLock
        }
    }

    // ---- Automatic Incremental Channel Discovery Runner ----

    private fun launchDiscoveryLoop(batchId: String) {
        val extractor = collectionExtractor ?: return
        val existing = activeDiscoveryJobs[batchId]
        if (existing?.isActive == true) return

        val job = scope.launch {
            try {
                runChannelDiscoveryLoop(batchId, extractor)
            } finally {
                activeDiscoveryJobs.remove(batchId)
            }
        }
        activeDiscoveryJobs[batchId] = job
    }

    private suspend fun runChannelDiscoveryLoop(batchId: String, extractor: CollectionExtractor) {
        val initialBatch = batchRepository.getBatch(batchId) ?: return
        val classified = MediaUrlClassifier.classify(initialBatch.sourceUrl)
        val tabOrder = initialBatch.selectedTabs.ifEmpty { listOf(ChannelContentTab.VIDEOS) }

        val persistedCheckpoints = batchRepository.getCheckpointsForBatch(batchId)
            .associateBy { it.tab }
        val orderedCheckpoints = tabOrder.map { tab ->
            persistedCheckpoints[tab] ?: ChannelTabCheckpoint(
                batchId = batchId,
                tab = tab,
                nextStartIndex = 1,
                discoveredCount = 0,
                isExhausted = false,
                updatedAt = System.currentTimeMillis()
            )
        }

        var positionCursor = batchRepository.getMaxPositionForBatch(batchId)
        var downloadableSoFar = batchRepository.getDownloadableItemCount(batchId)
        var limitReached = false

        for (checkpoint in orderedCheckpoints) {
            if (limitReached) {
                if (!checkpoint.isExhausted) {
                    batchRepository.upsertCheckpoint(checkpoint.copy(isExhausted = true))
                }
                continue
            }

            var currentCp = checkpoint
            while (!currentCp.isExhausted) {
                if (!scope.isActive) return
                val latestBatch = batchRepository.getBatch(batchId) ?: return
                if (latestBatch.status == BatchStatus.CANCELLED ||
                    latestBatch.discoveryStatus != DiscoveryStatus.RUNNING
                ) {
                    return
                }

                val targetLimit = latestBatch.targetLimit
                if (targetLimit != null && downloadableSoFar >= targetLimit) {
                    limitReached = true
                    batchRepository.upsertCheckpoint(currentCp.copy(isExhausted = true))
                    break
                }

                // Respect Wi-Fi-only policy during discovery too
                if (latestBatch.wifiOnly && !isUnmeteredNetworkProvider()) {
                    batchRepository.updateDiscoveryStatus(
                        batchId = batchId,
                        discoveryStatus = DiscoveryStatus.PAUSED,
                        discoveryError = "Discovery paused — waiting for Wi-Fi. Tap Resume Queue on Wi-Fi."
                    )
                    batchRepository.recomputeAndSaveBatchAggregates(batchId)
                    return
                }

                val pageResult = extractor.extractChannelTabPage(
                    classified = classified,
                    tab = currentCp.tab,
                    startIndex = currentCp.nextStartIndex,
                    pageSize = discoveryChunkSize
                )

                if (pageResult.isFailure) {
                    val rawError = pageResult.exceptionOrNull()
                    if (rawError is CancellationException) throw rawError
                    val shreyErr = rawError as? ShreyError ?: ErrorTranslator.translate(rawError ?: IllegalStateException("Discovery failed"))
                    when (shreyErr) {
                        is ShreyError.ContentUnavailable -> {
                            currentCp = currentCp.copy(isExhausted = true, lastError = shreyErr.userMessage)
                            batchRepository.upsertCheckpoint(currentCp)
                            break
                        }
                        else -> {
                            // Rate limit, HTTP access restriction, or network error: pause discovery
                            // cleanly while keeping currentCp.nextStartIndex intact for resume.
                            batchRepository.updateDiscoveryStatus(
                                batchId = batchId,
                                discoveryStatus = DiscoveryStatus.PAUSED,
                                discoveryError = shreyErr.userMessage
                            )
                            batchRepository.recomputeAndSaveBatchAggregates(batchId)
                            return
                        }
                    }
                }

                val page = pageResult.getOrThrow()
                if (page.entries.isEmpty()) {
                    currentCp = currentCp.copy(isExhausted = true)
                    batchRepository.upsertCheckpoint(currentCp)
                    break
                }

                // Deduplicate ONLY this bounded 50-entry chunk against Room (queued, active, completed)
                val existingIds = batchRepository.findExistingMediaIds(page.entries.map { it.mediaId })
                val seenInChunk = HashSet<String>(page.entries.size)
                val now = System.currentTimeMillis()
                val chunkItems = ArrayList<BatchItem>(page.entries.size)

                for (entry in page.entries) {
                    if (targetLimit != null && downloadableSoFar >= targetLimit) {
                        limitReached = true
                        break
                    }
                    val isDupInChunk = !seenInChunk.add(entry.mediaId)
                    val alreadyKnown = !isDupInChunk && entry.mediaId in existingIds
                    val itemStatus = when {
                        !entry.availability.isDownloadable -> BatchItemStatus.UNAVAILABLE
                        isDupInChunk || alreadyKnown -> BatchItemStatus.SKIPPED
                        else -> {
                            downloadableSoFar++
                            BatchItemStatus.QUEUED
                        }
                    }
                    positionCursor++
                    chunkItems += buildBatchItem(
                        batchId = batchId,
                        entry = entry.copy(tab = entry.tab ?: currentCp.tab),
                        position = positionCursor,
                        initialStatus = itemStatus,
                        now = now
                    )
                }

                val nextStart = if (page.nextStartIndex > currentCp.nextStartIndex) {
                    page.nextStartIndex
                } else {
                    currentCp.nextStartIndex + discoveryChunkSize
                }
                val tabExhausted = !page.hasMoreEntries || limitReached
                currentCp = currentCp.copy(
                    nextStartIndex = nextStart,
                    discoveredCount = currentCp.discoveredCount + chunkItems.size,
                    isExhausted = tabExhausted,
                    lastError = null
                )

                val knownTotal = if (tabOrder.size == 1) page.declaredTotalCount?.takeIf { it > 0 } else null

                // Atomic checkpoint + bounded chunk insert in Room
                batchRepository.appendDiscoveredChunk(
                    batchId = batchId,
                    items = chunkItems,
                    checkpoint = currentCp,
                    addedDiscoveredCount = chunkItems.size,
                    knownTotalCount = knownTotal
                )
                batchRepository.recomputeAndSaveBatchAggregates(batchId)

                // Progressive scheduling: start downloading as soon as chunks are queued
                if (chunkItems.any { it.status == BatchItemStatus.QUEUED }) {
                    scheduleNextItem(batchId)
                }
            }
        }

        // All selected tabs exhausted (or target limit satisfied)
        val finalBatch = batchRepository.getBatch(batchId) ?: return
        if (finalBatch.discoveryStatus == DiscoveryStatus.RUNNING && finalBatch.status != BatchStatus.CANCELLED) {
            batchRepository.updateDiscoveryStatus(
                batchId = batchId,
                discoveryStatus = DiscoveryStatus.COMPLETED,
                discoveryError = null
            )
            batchRepository.recomputeAndSaveBatchAggregates(batchId)
            scheduleNextItem(batchId)
        }
    }

    private fun buildBatchItem(
        batchId: String,
        entry: CollectionEntry,
        position: Int,
        initialStatus: BatchItemStatus,
        now: Long
    ): BatchItem = BatchItem(
        itemId = "bi_${UUID.randomUUID()}",
        batchId = batchId,
        downloadId = null,
        mediaId = entry.mediaId,
        position = position,
        title = entry.title,
        sourceUrl = entry.sourceUrl,
        thumbnailUrl = entry.thumbnailUrl,
        durationSeconds = entry.durationSeconds,
        tab = entry.tab,
        status = initialStatus,
        progressPercent = if (initialStatus == BatchItemStatus.SKIPPED) 100f else 0f,
        downloadedBytes = null,
        totalBytes = entry.estimatedSizeBytes,
        speedText = null,
        etaSeconds = null,
        destinationUri = null,
        outputDisplayName = null,
        mimeType = null,
        errorMessage = when (initialStatus) {
            BatchItemStatus.SKIPPED -> "Already queued or downloaded (skipped duplicate)"
            BatchItemStatus.UNAVAILABLE -> entry.availability.label
            else -> null
        },
        technicalError = null,
        retryCount = 0,
        updatedAt = now
    )

    // ---- DownloadLifecycleListener callbacks from DownloadQueueManager ----

    override fun onDownloadProgress(progress: DownloadProgress) {
        scope.launch {
            val item = batchRepository.getItemByDownloadId(progress.downloadId) ?: return@launch
            batchRepository.updateItemProgress(
                itemId = item.itemId,
                downloadId = progress.downloadId,
                status = BatchItemStatus.ACTIVE,
                progressPercent = progress.progressPercent ?: item.progressPercent,
                downloadedBytes = progress.downloadedBytes,
                totalBytes = progress.totalBytes,
                speedText = progress.speedText,
                etaSeconds = progress.etaSeconds
            )
        }
    }

    override fun onDownloadCompleted(
        downloadId: String,
        destinationUri: String,
        displayName: String,
        mimeType: String,
        fileSizeBytes: Long
    ) {
        scope.launch {
            val item = batchRepository.getItemByDownloadId(downloadId)
            if (item != null) {
                batchRepository.markItemCompleted(
                    itemId = item.itemId,
                    destinationUri = destinationUri,
                    outputDisplayName = displayName,
                    mimeType = mimeType,
                    fileSizeBytes = fileSizeBytes
                )
                batchRepository.recomputeAndSaveBatchAggregates(item.batchId)
            }
            // Freeing the global slot allows either this batch or any waiting batch to proceed.
            scheduleNextItem(item?.batchId)
        }
    }

    override fun onDownloadFailed(downloadId: String, error: ShreyError) {
        scope.launch {
            val item = batchRepository.getItemByDownloadId(downloadId)
            if (item != null) {
                batchRepository.markItemFailed(
                    itemId = item.itemId,
                    errorMessage = error.userMessage,
                    technicalError = error.technicalDetails
                )

                // Pause remaining queue automatically on rate limits or exhausted storage
                if (error is ShreyError.RateLimited || error is ShreyError.InsufficientStorage) {
                    activeDiscoveryJobs.remove(item.batchId)?.cancel()
                    val currentBatch = batchRepository.getBatch(item.batchId)
                    if (currentBatch != null && currentBatch.discoveryStatus == DiscoveryStatus.RUNNING) {
                        batchRepository.updateDiscoveryStatus(
                            batchId = item.batchId,
                            discoveryStatus = DiscoveryStatus.PAUSED,
                            discoveryError = error.userMessage
                        )
                    }
                    batchRepository.updateBatchStatus(
                        batchId = item.batchId,
                        status = BatchStatus.PAUSED,
                        lastError = "${error.title}: ${error.userMessage}"
                    )
                    batchRepository.recomputeAndSaveBatchAggregates(item.batchId, explicitError = error.userMessage)
                    return@launch
                } else {
                    // Nonfatal per-item failure: continue cleanly with remaining items
                    batchRepository.recomputeAndSaveBatchAggregates(item.batchId)
                }
            }
            scheduleNextItem(item?.batchId)
        }
    }

    override fun onDownloadCancelled(downloadId: String) {
        scope.launch {
            val item = batchRepository.getItemByDownloadId(downloadId)
            if (item != null) {
                batchRepository.markItemCancelled(item.itemId)
                batchRepository.recomputeAndSaveBatchAggregates(item.batchId)
            }
            scheduleNextItem(item?.batchId)
        }
    }

    companion object {
        const val MAX_GLOBAL_ACTIVE_DOWNLOADS = 1
        private const val MIN_BATCH_START_BYTES = 64L * 1024L * 1024L // 64 MiB floor
        private const val MIN_ITEM_START_BYTES = 32L * 1024L * 1024L  // 32 MiB floor

        fun buildRelativeSubfolder(batch: DownloadBatch): String {
            val safeName = FilenameSanitizer.sanitizeBaseName(batch.displayName, fallback = "Collection").take(64)
            return when (batch.sourceType) {
                MediaSourceType.CHANNEL -> "Channels/$safeName"
                else -> "Playlists/$safeName"
            }
        }

        private fun checkUnmeteredNetwork(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
    }
}
