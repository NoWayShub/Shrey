package com.shrey.downloader.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status = 'QUEUED' ORDER BY createdAt ASC LIMIT 1")
    suspend fun getNextQueued(): DownloadEntity?

    @Query(
        """
        SELECT COUNT(*) FROM downloads
        WHERE batchId IS NULL
          AND status IN ('QUEUED', 'PREPARING', 'EXTRACTING', 'DOWNLOADING', 'MERGING', 'CONVERTING', 'SAVING')
        """
    )
    suspend fun countActiveOrQueuedStandalone(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DownloadEntity)

    @Query(
        """
        UPDATE downloads
        SET status = :status,
            progress = COALESCE(:progress, progress),
            downloadedBytes = COALESCE(:downloadedBytes, downloadedBytes),
            totalBytes = COALESCE(:totalBytes, totalBytes),
            speedText = :speedText,
            etaSeconds = :etaSeconds,
            statusMessage = :statusMessage,
            startedAt = COALESCE(startedAt, :startedAt)
        WHERE id = :id
        """
    )
    suspend fun updateProgress(
        id: String,
        status: String,
        progress: Float?,
        downloadedBytes: Long?,
        totalBytes: Long?,
        speedText: String?,
        etaSeconds: Long?,
        statusMessage: String?,
        startedAt: Long?
    )

    @Query(
        """
        UPDATE downloads
        SET status = 'COMPLETED',
            progress = 100.0,
            downloadedBytes = :fileSizeBytes,
            totalBytes = :fileSizeBytes,
            speedText = NULL,
            etaSeconds = NULL,
            statusMessage = 'Saved to device',
            destinationUri = :destinationUri,
            outputDisplayName = :displayName,
            mimeType = :mimeType,
            completedAt = :completedAt,
            lastError = NULL,
            technicalError = NULL
        WHERE id = :id
        """
    )
    suspend fun markCompleted(
        id: String,
        destinationUri: String,
        displayName: String,
        mimeType: String,
        fileSizeBytes: Long,
        completedAt: Long
    )

    @Query(
        """
        UPDATE downloads
        SET status = 'FAILED',
            speedText = NULL,
            etaSeconds = NULL,
            statusMessage = :userError,
            lastError = :userError,
            technicalError = :technicalError
        WHERE id = :id
        """
    )
    suspend fun markFailed(id: String, userError: String, technicalError: String?)

    @Query(
        """
        UPDATE downloads
        SET status = 'CANCELLED',
            speedText = NULL,
            etaSeconds = NULL,
            statusMessage = 'Cancelled by user'
        WHERE id = :id
        """
    )
    suspend fun markCancelled(id: String)

    @Query(
        """
        UPDATE downloads
        SET status = 'QUEUED',
            progress = 0.0,
            downloadedBytes = NULL,
            speedText = NULL,
            etaSeconds = NULL,
            statusMessage = 'Queued for retry',
            lastError = NULL,
            technicalError = NULL,
            retryCount = retryCount + 1
        WHERE id = :id
        """
    )
    suspend fun markQueuedForRetry(id: String)

    @Query(
        """
        UPDATE downloads
        SET status = 'INTERRUPTED',
            speedText = NULL,
            etaSeconds = NULL,
            statusMessage = 'Interrupted before completion. Tap Retry to download again.',
            lastError = 'Download was interrupted when the app process stopped.'
        WHERE status IN ('PREPARING', 'EXTRACTING', 'DOWNLOADING', 'MERGING', 'CONVERTING', 'SAVING')
        """
    )
    suspend fun markInFlightAsInterrupted(): Int

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: String)
}
