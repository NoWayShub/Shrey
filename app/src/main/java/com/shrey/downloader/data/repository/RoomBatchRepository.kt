package com.shrey.downloader.data.repository

import com.shrey.downloader.core.database.BatchDao
import com.shrey.downloader.core.database.BatchItemEntity
import com.shrey.downloader.core.database.ChannelDiscoveryCheckpointEntity
import com.shrey.downloader.core.database.DownloadBatchEntity
import com.shrey.downloader.domain.model.AudioOutputFormat
import com.shrey.downloader.domain.model.BatchItem
import com.shrey.downloader.domain.model.BatchItemStatus
import com.shrey.downloader.domain.model.BatchStatus
import com.shrey.downloader.domain.model.BatchWithItems
import com.shrey.downloader.domain.model.BulkQualityPreset
import com.shrey.downloader.domain.model.ChannelContentTab
import com.shrey.downloader.domain.model.ChannelTabCheckpoint
import com.shrey.downloader.domain.model.DiscoveryStatus
import com.shrey.downloader.domain.model.DownloadBatch
import com.shrey.downloader.domain.model.DownloadMode
import com.shrey.downloader.domain.model.MediaSourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

interface BatchRepository {
    fun observeBatchesWithItems(): Flow<List<BatchWithItems>>
    suspend fun createBatchWithItems(batch: DownloadBatch, items: List<BatchItem>)
    suspend fun createBatchWithItemsAndCheckpoints(
        batch: DownloadBatch,
        items: List<BatchItem>,
        checkpoints: List<ChannelTabCheckpoint>
    )
    suspend fun appendDiscoveredChunk(
        batchId: String,
        items: List<BatchItem>,
        checkpoint: ChannelTabCheckpoint,
        addedDiscoveredCount: Int,
        knownTotalCount: Int?
    )
    suspend fun getCheckpointsForBatch(batchId: String): List<ChannelTabCheckpoint>
    suspend fun upsertCheckpoint(checkpoint: ChannelTabCheckpoint)
    suspend fun getBatch(batchId: String): DownloadBatch?
    suspend fun getItemsForBatch(batchId: String): List<BatchItem>
    suspend fun getActiveItemsForBatch(batchId: String): List<BatchItem>
    suspend fun getItemById(itemId: String): BatchItem?
    suspend fun getItemByDownloadId(downloadId: String): BatchItem?
    suspend fun getNextQueuedItem(batchId: String): BatchItem?
    suspend fun countGlobalActiveItems(): Int
    suspend fun getMaxPositionForBatch(batchId: String): Int
    suspend fun getDownloadableItemCount(batchId: String): Int
    suspend fun hasCompletedMediaId(mediaId: String): Boolean
    suspend fun findExistingMediaIds(mediaIds: List<String>): Set<String>
    suspend fun getActiveBatches(): List<DownloadBatch>
    suspend fun getSchedulableActiveBatches(): List<DownloadBatch>
    suspend fun updateBatchStatus(batchId: String, status: BatchStatus, lastError: String? = null)
    suspend fun updateDiscoveryStatus(batchId: String, discoveryStatus: DiscoveryStatus, discoveryError: String? = null)
    suspend fun updateBatchCheckpoint(batchId: String, nextIndex: Int, discoveredCount: Int)
    suspend fun recomputeAndSaveBatchAggregates(batchId: String, explicitError: String? = null): DownloadBatch?
    suspend fun updateItemProgress(
        itemId: String,
        downloadId: String?,
        status: BatchItemStatus,
        progressPercent: Float,
        downloadedBytes: Long?,
        totalBytes: Long?,
        speedText: String?,
        etaSeconds: Long?
    )
    suspend fun markItemCompleted(
        itemId: String,
        destinationUri: String,
        outputDisplayName: String,
        mimeType: String,
        fileSizeBytes: Long
    )
    suspend fun markItemFailed(itemId: String, errorMessage: String, technicalError: String?)
    suspend fun markItemCancelled(itemId: String)
    suspend fun cancelPendingItemsInBatch(batchId: String)
    suspend fun requeueFailedItemsInBatch(batchId: String): Int
    suspend fun requeueSingleItem(itemId: String)
    suspend fun resetInFlightItemsToQueued(): Int
    suspend fun deleteBatch(batchId: String)
}

class RoomBatchRepository(
    private val batchDao: BatchDao
) : BatchRepository {

    override fun observeBatchesWithItems(): Flow<List<BatchWithItems>> =
        combine(
            batchDao.observeAllBatches(),
            batchDao.observeBoundedPreviewItems(perBatchLimit = UI_PREVIEW_ITEM_LIMIT)
        ) { batches, boundedItems ->
            val grouped = boundedItems.groupBy { it.batchId }
            batches.map { batchEntity ->
                val domainBatch = toDomainBatch(batchEntity)
                val items = grouped[batchEntity.batchId].orEmpty().map(::toDomainItem)
                BatchWithItems(
                    batch = domainBatch,
                    items = items,
                    totalItemCount = domainBatch.selectedCount.coerceAtLeast(items.size)
                )
            }
        }

    override suspend fun createBatchWithItems(batch: DownloadBatch, items: List<BatchItem>) {
        batchDao.insertBatchWithItems(
            batch = toEntityBatch(batch),
            items = items.map(::toEntityItem)
        )
    }

    override suspend fun createBatchWithItemsAndCheckpoints(
        batch: DownloadBatch,
        items: List<BatchItem>,
        checkpoints: List<ChannelTabCheckpoint>
    ) {
        batchDao.insertBatchWithItemsAndCheckpoints(
            batch = toEntityBatch(batch),
            items = items.map(::toEntityItem),
            checkpoints = checkpoints.map(::toEntityCheckpoint)
        )
    }

    override suspend fun appendDiscoveredChunk(
        batchId: String,
        items: List<BatchItem>,
        checkpoint: ChannelTabCheckpoint,
        addedDiscoveredCount: Int,
        knownTotalCount: Int?
    ) {
        val now = System.currentTimeMillis()
        batchDao.appendDiscoveredChunk(
            batchId = batchId,
            items = items.map(::toEntityItem),
            checkpoint = toEntityCheckpoint(checkpoint.copy(updatedAt = now)),
            addedDiscoveredCount = addedDiscoveredCount,
            knownTotalCount = knownTotalCount,
            updatedAt = now
        )
    }

    override suspend fun getCheckpointsForBatch(batchId: String): List<ChannelTabCheckpoint> =
        batchDao.getCheckpointsForBatch(batchId).map(::toDomainCheckpoint)

    override suspend fun upsertCheckpoint(checkpoint: ChannelTabCheckpoint) {
        batchDao.upsertCheckpoint(toEntityCheckpoint(checkpoint.copy(updatedAt = System.currentTimeMillis())))
    }

    override suspend fun getBatch(batchId: String): DownloadBatch? =
        batchDao.getBatchById(batchId)?.let(::toDomainBatch)

    override suspend fun getItemsForBatch(batchId: String): List<BatchItem> =
        batchDao.getItemsForBatch(batchId).map(::toDomainItem)

    override suspend fun getActiveItemsForBatch(batchId: String): List<BatchItem> =
        batchDao.getActiveItemsForBatch(batchId).map(::toDomainItem)

    override suspend fun getItemById(itemId: String): BatchItem? =
        batchDao.getItemById(itemId)?.let(::toDomainItem)

    override suspend fun getItemByDownloadId(downloadId: String): BatchItem? =
        batchDao.getItemByDownloadId(downloadId)?.let(::toDomainItem)

    override suspend fun getNextQueuedItem(batchId: String): BatchItem? =
        batchDao.getNextQueuedItemForBatch(batchId)?.let(::toDomainItem)

    override suspend fun countGlobalActiveItems(): Int =
        batchDao.countGlobalActiveItems()

    override suspend fun getMaxPositionForBatch(batchId: String): Int =
        batchDao.getMaxPositionForBatch(batchId)

    override suspend fun getDownloadableItemCount(batchId: String): Int =
        batchDao.getBatchAggregateStats(batchId).downloadableCount

    override suspend fun hasCompletedMediaId(mediaId: String): Boolean =
        mediaId.isNotBlank() && batchDao.findExistingMediaIds(listOf(mediaId)).isNotEmpty()

    override suspend fun findExistingMediaIds(mediaIds: List<String>): Set<String> {
        val cleaned = mediaIds.asSequence().filter { it.isNotBlank() }.distinct().toList()
        if (cleaned.isEmpty()) return emptySet()
        val found = HashSet<String>()
        cleaned.chunked(SQL_IN_CHUNK_SIZE).forEach { chunk ->
            found.addAll(batchDao.findExistingMediaIds(chunk))
        }
        return found
    }

    override suspend fun getActiveBatches(): List<DownloadBatch> =
        batchDao.getActiveOrDiscoveringBatches().map(::toDomainBatch)

    override suspend fun getSchedulableActiveBatches(): List<DownloadBatch> =
        batchDao.getSchedulableActiveBatches().map(::toDomainBatch)

    override suspend fun updateBatchStatus(batchId: String, status: BatchStatus, lastError: String?) {
        batchDao.updateBatchStatus(
            batchId = batchId,
            status = status.name,
            lastError = lastError,
            updatedAt = System.currentTimeMillis()
        )
    }

    override suspend fun updateDiscoveryStatus(
        batchId: String,
        discoveryStatus: DiscoveryStatus,
        discoveryError: String?
    ) {
        batchDao.updateDiscoveryStatus(
            batchId = batchId,
            discoveryStatus = discoveryStatus.name,
            discoveryError = discoveryError,
            updatedAt = System.currentTimeMillis()
        )
    }

    override suspend fun updateBatchCheckpoint(batchId: String, nextIndex: Int, discoveredCount: Int) {
        batchDao.updateBatchCheckpoint(
            batchId = batchId,
            nextIndex = nextIndex,
            discoveredCount = discoveredCount,
            updatedAt = System.currentTimeMillis()
        )
    }

    override suspend fun recomputeAndSaveBatchAggregates(batchId: String, explicitError: String?): DownloadBatch? {
        val current = batchDao.getBatchById(batchId) ?: return null
        val stats = batchDao.getBatchAggregateStats(batchId)
        val completed = stats.completedCount
        val failed = stats.failedCount
        val skipped = stats.skippedCount
        val active = stats.activeCount
        val queued = stats.queuedCount
        val cancelled = stats.cancelledCount
        val total = stats.totalCount
        val totalBytes = stats.totalBytesDownloaded.coerceAtLeast(0L)

        val currentStatus = runCatching { BatchStatus.valueOf(current.status) }.getOrDefault(BatchStatus.ACTIVE)
        val discoveryStatus = runCatching { DiscoveryStatus.valueOf(current.discoveryStatus) }
            .getOrDefault(DiscoveryStatus.COMPLETED)

        val nextStatus = when {
            currentStatus == BatchStatus.CANCELLED && active == 0 && queued == 0 -> BatchStatus.CANCELLED
            active > 0 -> BatchStatus.ACTIVE
            queued > 0 && currentStatus == BatchStatus.PAUSED -> BatchStatus.PAUSED
            queued > 0 -> BatchStatus.ACTIVE
            discoveryStatus == DiscoveryStatus.RUNNING ->
                if (currentStatus == BatchStatus.PAUSED) BatchStatus.PAUSED else BatchStatus.DISCOVERING
            discoveryStatus == DiscoveryStatus.PAUSED -> BatchStatus.PAUSED
            total == 0 -> BatchStatus.COMPLETED
            cancelled == total || (cancelled > 0 && completed == 0 && failed == 0 && skipped == 0) -> BatchStatus.CANCELLED
            failed > 0 && completed > 0 -> BatchStatus.COMPLETED_WITH_ERRORS
            failed > 0 && completed == 0 && skipped == 0 -> BatchStatus.FAILED
            failed > 0 -> BatchStatus.COMPLETED_WITH_ERRORS
            else -> BatchStatus.COMPLETED
        }

        val now = System.currentTimeMillis()
        batchDao.updateBatchCounters(
            batchId = batchId,
            status = nextStatus.name,
            selectedCount = total,
            completedCount = completed,
            failedCount = failed,
            skippedCount = skipped,
            activeCount = active,
            totalBytesDownloaded = totalBytes,
            updatedAt = now,
            lastError = explicitError
        )
        return batchDao.getBatchById(batchId)?.let(::toDomainBatch)
    }

    override suspend fun updateItemProgress(
        itemId: String,
        downloadId: String?,
        status: BatchItemStatus,
        progressPercent: Float,
        downloadedBytes: Long?,
        totalBytes: Long?,
        speedText: String?,
        etaSeconds: Long?
    ) {
        batchDao.updateItemProgress(
            itemId = itemId,
            downloadId = downloadId,
            status = status.name,
            progressPercent = progressPercent.coerceIn(0f, 100f),
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            speedText = speedText,
            etaSeconds = etaSeconds,
            updatedAt = System.currentTimeMillis()
        )
    }

    override suspend fun markItemCompleted(
        itemId: String,
        destinationUri: String,
        outputDisplayName: String,
        mimeType: String,
        fileSizeBytes: Long
    ) {
        batchDao.markItemCompleted(
            itemId = itemId,
            destinationUri = destinationUri,
            outputDisplayName = outputDisplayName,
            mimeType = mimeType,
            fileSizeBytes = fileSizeBytes,
            updatedAt = System.currentTimeMillis()
        )
    }

    override suspend fun markItemFailed(itemId: String, errorMessage: String, technicalError: String?) {
        batchDao.markItemFailed(
            itemId = itemId,
            errorMessage = errorMessage,
            technicalError = technicalError,
            updatedAt = System.currentTimeMillis()
        )
    }

    override suspend fun markItemCancelled(itemId: String) {
        batchDao.markItemCancelled(itemId, System.currentTimeMillis())
    }

    override suspend fun cancelPendingItemsInBatch(batchId: String) {
        batchDao.cancelPendingItemsInBatch(batchId, System.currentTimeMillis())
    }

    override suspend fun requeueFailedItemsInBatch(batchId: String): Int =
        batchDao.requeueFailedItemsInBatch(batchId, System.currentTimeMillis())

    override suspend fun requeueSingleItem(itemId: String) {
        batchDao.requeueSingleItem(itemId, System.currentTimeMillis())
    }

    override suspend fun resetInFlightItemsToQueued(): Int =
        batchDao.resetInFlightItemsToQueued(System.currentTimeMillis())

    override suspend fun deleteBatch(batchId: String) {
        batchDao.deleteBatchAndItems(batchId)
    }

    private fun toEntityBatch(b: DownloadBatch): DownloadBatchEntity = DownloadBatchEntity(
        batchId = b.batchId,
        sourceUrl = b.sourceUrl,
        canonicalId = b.canonicalId,
        sourceType = b.sourceType.name,
        displayName = b.displayName,
        creator = b.creator,
        thumbnailUrl = b.thumbnailUrl,
        status = b.status.name,
        qualityPreset = b.qualityPreset.name,
        downloadMode = b.downloadMode.name,
        audioOutputFormat = b.audioOutputFormat.name,
        wifiOnly = b.wifiOnly,
        discoveredCount = b.discoveredCount,
        selectedCount = b.selectedCount,
        completedCount = b.completedCount,
        failedCount = b.failedCount,
        skippedCount = b.skippedCount,
        activeCount = b.activeCount,
        totalBytesDownloaded = b.totalBytesDownloaded,
        checkpointNextIndex = b.checkpointNextIndex,
        createdAt = b.createdAt,
        updatedAt = b.updatedAt,
        lastError = b.lastError,
        discoveryStatus = b.discoveryStatus.name,
        targetLimit = b.targetLimit,
        knownTotalCount = b.knownTotalCount,
        discoveryError = b.discoveryError,
        selectedTabsCsv = b.selectedTabs.joinToString(",") { it.name }
    )

    private fun toDomainBatch(e: DownloadBatchEntity): DownloadBatch = DownloadBatch(
        batchId = e.batchId,
        sourceUrl = e.sourceUrl,
        canonicalId = e.canonicalId,
        sourceType = runCatching { MediaSourceType.valueOf(e.sourceType) }.getOrDefault(MediaSourceType.PLAYLIST),
        displayName = e.displayName,
        creator = e.creator,
        thumbnailUrl = e.thumbnailUrl,
        status = runCatching { BatchStatus.valueOf(e.status) }.getOrDefault(BatchStatus.PAUSED),
        qualityPreset = runCatching { BulkQualityPreset.valueOf(e.qualityPreset) }.getOrDefault(BulkQualityPreset.BEST),
        downloadMode = runCatching { DownloadMode.valueOf(e.downloadMode) }.getOrDefault(DownloadMode.VIDEO_AUDIO),
        audioOutputFormat = runCatching { AudioOutputFormat.valueOf(e.audioOutputFormat) }.getOrDefault(AudioOutputFormat.ORIGINAL),
        wifiOnly = e.wifiOnly,
        discoveredCount = e.discoveredCount,
        selectedCount = e.selectedCount,
        completedCount = e.completedCount,
        failedCount = e.failedCount,
        skippedCount = e.skippedCount,
        activeCount = e.activeCount,
        totalBytesDownloaded = e.totalBytesDownloaded,
        checkpointNextIndex = e.checkpointNextIndex,
        createdAt = e.createdAt,
        updatedAt = e.updatedAt,
        lastError = e.lastError,
        discoveryStatus = runCatching { DiscoveryStatus.valueOf(e.discoveryStatus) }
            .getOrDefault(DiscoveryStatus.COMPLETED),
        targetLimit = e.targetLimit,
        knownTotalCount = e.knownTotalCount,
        discoveryError = e.discoveryError,
        selectedTabs = e.selectedTabsCsv
            .split(",")
            .mapNotNull { token -> token.trim().takeIf { it.isNotEmpty() } }
            .mapNotNull { name -> runCatching { ChannelContentTab.valueOf(name) }.getOrNull() }
    )

    private fun toEntityItem(i: BatchItem): BatchItemEntity = BatchItemEntity(
        itemId = i.itemId,
        batchId = i.batchId,
        downloadId = i.downloadId,
        mediaId = i.mediaId,
        position = i.position,
        title = i.title,
        sourceUrl = i.sourceUrl,
        thumbnailUrl = i.thumbnailUrl,
        durationSeconds = i.durationSeconds,
        tab = i.tab?.name,
        status = i.status.name,
        progressPercent = i.progressPercent,
        downloadedBytes = i.downloadedBytes,
        totalBytes = i.totalBytes,
        speedText = i.speedText,
        etaSeconds = i.etaSeconds,
        destinationUri = i.destinationUri,
        outputDisplayName = i.outputDisplayName,
        mimeType = i.mimeType,
        errorMessage = i.errorMessage,
        technicalError = i.technicalError,
        retryCount = i.retryCount,
        updatedAt = i.updatedAt
    )

    private fun toDomainItem(e: BatchItemEntity): BatchItem = BatchItem(
        itemId = e.itemId,
        batchId = e.batchId,
        downloadId = e.downloadId,
        mediaId = e.mediaId,
        position = e.position,
        title = e.title,
        sourceUrl = e.sourceUrl,
        thumbnailUrl = e.thumbnailUrl,
        durationSeconds = e.durationSeconds,
        tab = e.tab?.let { runCatching { ChannelContentTab.valueOf(it) }.getOrNull() },
        status = runCatching { BatchItemStatus.valueOf(e.status) }.getOrDefault(BatchItemStatus.QUEUED),
        progressPercent = e.progressPercent.coerceIn(0f, 100f),
        downloadedBytes = e.downloadedBytes,
        totalBytes = e.totalBytes,
        speedText = e.speedText,
        etaSeconds = e.etaSeconds,
        destinationUri = e.destinationUri,
        outputDisplayName = e.outputDisplayName,
        mimeType = e.mimeType,
        errorMessage = e.errorMessage,
        technicalError = e.technicalError,
        retryCount = e.retryCount,
        updatedAt = e.updatedAt
    )

    private fun toEntityCheckpoint(c: ChannelTabCheckpoint): ChannelDiscoveryCheckpointEntity =
        ChannelDiscoveryCheckpointEntity(
            batchId = c.batchId,
            tab = c.tab.name,
            nextStartIndex = c.nextStartIndex,
            discoveredCount = c.discoveredCount,
            isExhausted = c.isExhausted,
            lastError = c.lastError,
            updatedAt = c.updatedAt
        )

    private fun toDomainCheckpoint(e: ChannelDiscoveryCheckpointEntity): ChannelTabCheckpoint =
        ChannelTabCheckpoint(
            batchId = e.batchId,
            tab = runCatching { ChannelContentTab.valueOf(e.tab) }.getOrDefault(ChannelContentTab.VIDEOS),
            nextStartIndex = e.nextStartIndex,
            discoveredCount = e.discoveredCount,
            isExhausted = e.isExhausted,
            lastError = e.lastError,
            updatedAt = e.updatedAt
        )

    companion object {
        const val UI_PREVIEW_ITEM_LIMIT = 50
        private const val SQL_IN_CHUNK_SIZE = 400
    }
}
