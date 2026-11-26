package com.shrey.downloader.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

data class BatchAggregateProjection(
    val totalCount: Int,
    val completedCount: Int,
    val failedCount: Int,
    val skippedCount: Int,
    val activeCount: Int,
    val queuedCount: Int,
    val cancelledCount: Int,
    val downloadableCount: Int,
    val totalBytesDownloaded: Long
)

@Dao
interface BatchDao {

    @Query("SELECT * FROM download_batches ORDER BY createdAt DESC")
    fun observeAllBatches(): Flow<List<DownloadBatchEntity>>

    @Query("SELECT * FROM batch_items ORDER BY position ASC")
    fun observeAllItems(): Flow<List<BatchItemEntity>>

    /**
     * Bounded window observation: returns at most [perBatchLimit] prioritized items per batch
     * (ACTIVE -> FAILED -> QUEUED -> COMPLETED/other) so UI never loads 10,000+ rows into RAM.
     */
    @Query(
        """
        SELECT itemId, batchId, downloadId, mediaId, position, title, sourceUrl, thumbnailUrl,
               durationSeconds, tab, status, progressPercent, downloadedBytes, totalBytes,
               speedText, etaSeconds, destinationUri, outputDisplayName, mimeType,
               errorMessage, technicalError, retryCount, updatedAt
        FROM (
            SELECT *,
                   ROW_NUMBER() OVER (
                       PARTITION BY batchId
                       ORDER BY CASE status
                           WHEN 'ACTIVE' THEN 0
                           WHEN 'FAILED' THEN 1
                           WHEN 'QUEUED' THEN 2
                           ELSE 3
                       END, position ASC
                   ) AS rn
            FROM batch_items
        )
        WHERE rn <= :perBatchLimit
        ORDER BY position ASC
        """
    )
    fun observeBoundedPreviewItems(perBatchLimit: Int = 50): Flow<List<BatchItemEntity>>

    @Query("SELECT * FROM download_batches WHERE batchId = :batchId LIMIT 1")
    suspend fun getBatchById(batchId: String): DownloadBatchEntity?

    @Query("SELECT * FROM batch_items WHERE batchId = :batchId ORDER BY position ASC")
    suspend fun getItemsForBatch(batchId: String): List<BatchItemEntity>

    @Query("SELECT * FROM batch_items WHERE batchId = :batchId AND status = 'ACTIVE'")
    suspend fun getActiveItemsForBatch(batchId: String): List<BatchItemEntity>

    @Query("SELECT * FROM batch_items WHERE itemId = :itemId LIMIT 1")
    suspend fun getItemById(itemId: String): BatchItemEntity?

    @Query("SELECT * FROM batch_items WHERE downloadId = :downloadId LIMIT 1")
    suspend fun getItemByDownloadId(downloadId: String): BatchItemEntity?

    @Query("SELECT * FROM batch_items WHERE batchId = :batchId AND status = 'QUEUED' ORDER BY position ASC LIMIT 1")
    suspend fun getNextQueuedItemForBatch(batchId: String): BatchItemEntity?

    @Query("SELECT COUNT(*) FROM batch_items WHERE status = 'ACTIVE'")
    suspend fun countGlobalActiveItems(): Int

    @Query("SELECT COUNT(*) FROM batch_items WHERE batchId = :batchId AND status = 'QUEUED'")
    suspend fun countQueuedItemsForBatch(batchId: String): Int

    @Query("SELECT COALESCE(MAX(position), 0) FROM batch_items WHERE batchId = :batchId")
    suspend fun getMaxPositionForBatch(batchId: String): Int

    @Query("SELECT * FROM batch_items WHERE status = 'COMPLETED' AND mediaId = :mediaId LIMIT 1")
    suspend fun findCompletedByMediaId(mediaId: String): BatchItemEntity?

    /**
     * Cross-table deduplication: returns all mediaIds from [mediaIds] that already exist in
     * queued, active, or completed state across BOTH `batch_items` and standalone `downloads`.
     */
    @Query(
        """
        SELECT DISTINCT mediaId FROM batch_items
        WHERE mediaId IN (:mediaIds)
          AND status IN ('QUEUED', 'ACTIVE', 'COMPLETED')
        UNION
        SELECT DISTINCT mediaId FROM downloads
        WHERE mediaId IN (:mediaIds)
          AND status IN ('QUEUED', 'PREPARING', 'EXTRACTING', 'DOWNLOADING', 'MERGING', 'CONVERTING', 'SAVING', 'COMPLETED')
        """
    )
    suspend fun findExistingMediaIds(mediaIds: List<String>): List<String>

    @Query(
        """
        SELECT
            COUNT(*) AS totalCount,
            COALESCE(SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END), 0) AS completedCount,
            COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failedCount,
            COALESCE(SUM(CASE WHEN status IN ('SKIPPED', 'UNAVAILABLE') THEN 1 ELSE 0 END), 0) AS skippedCount,
            COALESCE(SUM(CASE WHEN status = 'ACTIVE' THEN 1 ELSE 0 END), 0) AS activeCount,
            COALESCE(SUM(CASE WHEN status = 'QUEUED' THEN 1 ELSE 0 END), 0) AS queuedCount,
            COALESCE(SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END), 0) AS cancelledCount,
            COALESCE(SUM(CASE WHEN status IN ('QUEUED', 'ACTIVE', 'COMPLETED', 'FAILED') THEN 1 ELSE 0 END), 0) AS downloadableCount,
            COALESCE(SUM(CASE WHEN downloadedBytes > 0 THEN downloadedBytes ELSE 0 END), 0) AS totalBytesDownloaded
        FROM batch_items
        WHERE batchId = :batchId
        """
    )
    suspend fun getBatchAggregateStats(batchId: String): BatchAggregateProjection

    @Query("SELECT * FROM download_batches WHERE status IN ('ACTIVE', 'DISCOVERING') OR discoveryStatus = 'RUNNING' ORDER BY createdAt ASC")
    suspend fun getActiveOrDiscoveringBatches(): List<DownloadBatchEntity>

    @Query("SELECT * FROM download_batches WHERE status = 'ACTIVE' ORDER BY createdAt ASC")
    suspend fun getSchedulableActiveBatches(): List<DownloadBatchEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(batch: DownloadBatchEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<BatchItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCheckpoints(checkpoints: List<ChannelDiscoveryCheckpointEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCheckpoint(checkpoint: ChannelDiscoveryCheckpointEntity)

    @Query("SELECT * FROM channel_discovery_checkpoints WHERE batchId = :batchId")
    suspend fun getCheckpointsForBatch(batchId: String): List<ChannelDiscoveryCheckpointEntity>

    @Query("DELETE FROM channel_discovery_checkpoints WHERE batchId = :batchId")
    suspend fun deleteCheckpointsByBatchId(batchId: String)

    @Transaction
    suspend fun insertBatchWithItems(batch: DownloadBatchEntity, items: List<BatchItemEntity>) {
        insertBatch(batch)
        if (items.isNotEmpty()) {
            insertItems(items)
        }
    }

    @Transaction
    suspend fun insertBatchWithItemsAndCheckpoints(
        batch: DownloadBatchEntity,
        items: List<BatchItemEntity>,
        checkpoints: List<ChannelDiscoveryCheckpointEntity>
    ) {
        insertBatch(batch)
        if (items.isNotEmpty()) {
            insertItems(items)
        }
        if (checkpoints.isNotEmpty()) {
            upsertCheckpoints(checkpoints)
        }
    }

    @Transaction
    suspend fun appendDiscoveredChunk(
        batchId: String,
        items: List<BatchItemEntity>,
        checkpoint: ChannelDiscoveryCheckpointEntity,
        addedDiscoveredCount: Int,
        knownTotalCount: Int?,
        updatedAt: Long
    ) {
        if (items.isNotEmpty()) {
            insertItems(items)
        }
        upsertCheckpoint(checkpoint)
        updateBatchDiscoveryProgress(
            batchId = batchId,
            addedDiscovered = addedDiscoveredCount,
            addedSelected = items.size,
            nextIndex = checkpoint.nextStartIndex,
            knownTotalCount = knownTotalCount,
            updatedAt = updatedAt
        )
    }

    @Query(
        """
        UPDATE download_batches
        SET discoveredCount = discoveredCount + :addedDiscovered,
            selectedCount = selectedCount + :addedSelected,
            checkpointNextIndex = :nextIndex,
            knownTotalCount = COALESCE(:knownTotalCount, knownTotalCount),
            updatedAt = :updatedAt
        WHERE batchId = :batchId
        """
    )
    suspend fun updateBatchDiscoveryProgress(
        batchId: String,
        addedDiscovered: Int,
        addedSelected: Int,
        nextIndex: Int,
        knownTotalCount: Int?,
        updatedAt: Long
    )

    @Query("UPDATE download_batches SET status = :status, lastError = :lastError, updatedAt = :updatedAt WHERE batchId = :batchId")
    suspend fun updateBatchStatus(batchId: String, status: String, lastError: String?, updatedAt: Long)

    @Query(
        """
        UPDATE download_batches
        SET discoveryStatus = :discoveryStatus,
            discoveryError = :discoveryError,
            updatedAt = :updatedAt
        WHERE batchId = :batchId
        """
    )
    suspend fun updateDiscoveryStatus(
        batchId: String,
        discoveryStatus: String,
        discoveryError: String?,
        updatedAt: Long
    )

    @Query(
        """
        UPDATE download_batches
        SET status = :status,
            selectedCount = :selectedCount,
            completedCount = :completedCount,
            failedCount = :failedCount,
            skippedCount = :skippedCount,
            activeCount = :activeCount,
            totalBytesDownloaded = :totalBytesDownloaded,
            updatedAt = :updatedAt,
            lastError = COALESCE(:lastError, lastError)
        WHERE batchId = :batchId
        """
    )
    suspend fun updateBatchCounters(
        batchId: String,
        status: String,
        selectedCount: Int,
        completedCount: Int,
        failedCount: Int,
        skippedCount: Int,
        activeCount: Int,
        totalBytesDownloaded: Long,
        updatedAt: Long,
        lastError: String?
    )

    @Query("UPDATE download_batches SET checkpointNextIndex = :nextIndex, discoveredCount = :discoveredCount, updatedAt = :updatedAt WHERE batchId = :batchId")
    suspend fun updateBatchCheckpoint(batchId: String, nextIndex: Int, discoveredCount: Int, updatedAt: Long)

    @Query(
        """
        UPDATE batch_items
        SET downloadId = :downloadId,
            status = :status,
            progressPercent = :progressPercent,
            downloadedBytes = COALESCE(:downloadedBytes, downloadedBytes),
            totalBytes = COALESCE(:totalBytes, totalBytes),
            speedText = :speedText,
            etaSeconds = :etaSeconds,
            updatedAt = :updatedAt
        WHERE itemId = :itemId
        """
    )
    suspend fun updateItemProgress(
        itemId: String,
        downloadId: String?,
        status: String,
        progressPercent: Float,
        downloadedBytes: Long?,
        totalBytes: Long?,
        speedText: String?,
        etaSeconds: Long?,
        updatedAt: Long
    )

    @Query(
        """
        UPDATE batch_items
        SET status = 'COMPLETED',
            progressPercent = 100.0,
            downloadedBytes = :fileSizeBytes,
            totalBytes = :fileSizeBytes,
            speedText = NULL,
            etaSeconds = NULL,
            destinationUri = :destinationUri,
            outputDisplayName = :outputDisplayName,
            mimeType = :mimeType,
            errorMessage = NULL,
            technicalError = NULL,
            updatedAt = :updatedAt
        WHERE itemId = :itemId
        """
    )
    suspend fun markItemCompleted(
        itemId: String,
        destinationUri: String,
        outputDisplayName: String,
        mimeType: String,
        fileSizeBytes: Long,
        updatedAt: Long
    )

    @Query(
        """
        UPDATE batch_items
        SET status = 'FAILED',
            speedText = NULL,
            etaSeconds = NULL,
            errorMessage = :errorMessage,
            technicalError = :technicalError,
            retryCount = retryCount + 1,
            updatedAt = :updatedAt
        WHERE itemId = :itemId
        """
    )
    suspend fun markItemFailed(
        itemId: String,
        errorMessage: String,
        technicalError: String?,
        updatedAt: Long
    )

    @Query(
        """
        UPDATE batch_items
        SET status = 'CANCELLED',
            speedText = NULL,
            etaSeconds = NULL,
            updatedAt = :updatedAt
        WHERE itemId = :itemId
        """
    )
    suspend fun markItemCancelled(itemId: String, updatedAt: Long)

    @Query(
        """
        UPDATE batch_items
        SET status = 'CANCELLED',
            speedText = NULL,
            etaSeconds = NULL,
            updatedAt = :updatedAt
        WHERE batchId = :batchId AND status IN ('QUEUED', 'ACTIVE')
        """
    )
    suspend fun cancelPendingItemsInBatch(batchId: String, updatedAt: Long)

    @Query(
        """
        UPDATE batch_items
        SET status = 'QUEUED',
            downloadId = NULL,
            progressPercent = 0.0,
            speedText = NULL,
            etaSeconds = NULL,
            errorMessage = NULL,
            technicalError = NULL,
            updatedAt = :updatedAt
        WHERE batchId = :batchId AND status IN ('FAILED', 'CANCELLED')
        """
    )
    suspend fun requeueFailedItemsInBatch(batchId: String, updatedAt: Long): Int

    @Query(
        """
        UPDATE batch_items
        SET status = 'QUEUED',
            downloadId = NULL,
            progressPercent = 0.0,
            speedText = NULL,
            etaSeconds = NULL,
            errorMessage = NULL,
            technicalError = NULL,
            updatedAt = :updatedAt
        WHERE itemId = :itemId
        """
    )
    suspend fun requeueSingleItem(itemId: String, updatedAt: Long)

    @Query(
        """
        UPDATE batch_items
        SET status = 'QUEUED',
            downloadId = NULL,
            progressPercent = 0.0,
            speedText = NULL,
            etaSeconds = NULL,
            updatedAt = :updatedAt
        WHERE status = 'ACTIVE'
        """
    )
    suspend fun resetInFlightItemsToQueued(updatedAt: Long): Int

    @Query("DELETE FROM batch_items WHERE batchId = :batchId")
    suspend fun deleteItemsByBatchId(batchId: String)

    @Query("DELETE FROM download_batches WHERE batchId = :batchId")
    suspend fun deleteBatchById(batchId: String)

    @Transaction
    suspend fun deleteBatchAndItems(batchId: String) {
        deleteCheckpointsByBatchId(batchId)
        deleteItemsByBatchId(batchId)
        deleteBatchById(batchId)
    }
}
