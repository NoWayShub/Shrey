package com.shrey.downloader.data.repository

import com.shrey.downloader.core.database.DownloadDao
import com.shrey.downloader.data.mapper.DownloadMapper
import com.shrey.downloader.domain.model.DownloadItem
import com.shrey.downloader.domain.model.DownloadProgress
import com.shrey.downloader.domain.model.DownloadRequest
import com.shrey.downloader.domain.repository.DownloadRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomDownloadRepository(
    private val dao: DownloadDao
) : DownloadRepository {

    override fun observeDownloads(): Flow<List<DownloadItem>> =
        dao.observeAll().map { entities -> entities.map(DownloadMapper::toDomain) }

    override suspend fun enqueue(request: DownloadRequest): String {
        dao.insert(DownloadMapper.fromRequest(request))
        return request.downloadId
    }

    override suspend fun getDownload(downloadId: String): DownloadItem? =
        dao.getById(downloadId)?.let(DownloadMapper::toDomain)

    suspend fun getNextQueued(): DownloadRequest? =
        dao.getNextQueued()?.let(DownloadMapper::toDomain)?.let(DownloadMapper::toRequest)

    suspend fun countActiveOrQueuedStandalone(): Int =
        dao.countActiveOrQueuedStandalone()

    override suspend fun updateProgress(progress: DownloadProgress) {
        dao.updateProgress(
            id = progress.downloadId,
            status = progress.stage.name,
            progress = progress.progressPercent,
            downloadedBytes = progress.downloadedBytes,
            totalBytes = progress.totalBytes,
            speedText = progress.speedText,
            etaSeconds = progress.etaSeconds,
            statusMessage = progress.message,
            startedAt = if (progress.stage.isActive) progress.timestampMillis else null
        )
    }

    override suspend fun markCompleted(
        downloadId: String,
        destinationUri: String,
        displayName: String,
        mimeType: String,
        fileSizeBytes: Long
    ) {
        dao.markCompleted(
            id = downloadId,
            destinationUri = destinationUri,
            displayName = displayName,
            mimeType = mimeType,
            fileSizeBytes = fileSizeBytes,
            completedAt = System.currentTimeMillis()
        )
    }

    override suspend fun markFailed(downloadId: String, userError: String, technicalError: String?) {
        dao.markFailed(downloadId, userError, technicalError)
    }

    override suspend fun markCancelled(downloadId: String) {
        dao.markCancelled(downloadId)
    }

    override suspend fun prepareRetry(downloadId: String): DownloadRequest? {
        val existing = getDownload(downloadId) ?: return null
        dao.markQueuedForRetry(downloadId)
        return DownloadMapper.toRequest(existing)
    }

    override suspend fun removeFromHistory(downloadId: String) {
        dao.deleteById(downloadId)
    }

    override suspend fun reconcileInterruptedJobs(): Int = dao.markInFlightAsInterrupted()
}
