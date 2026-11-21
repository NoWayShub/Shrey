package com.shrey.downloader.domain.repository

import com.shrey.downloader.domain.model.DownloadItem
import com.shrey.downloader.domain.model.DownloadProgress
import com.shrey.downloader.domain.model.DownloadRequest
import com.shrey.downloader.domain.model.DownloadResult
import com.shrey.downloader.domain.model.MediaInfo
import kotlinx.coroutines.flow.Flow

interface MediaExtractor {
    suspend fun extract(url: String): Result<MediaInfo>
    suspend fun cancelExtraction(requestId: String)
}

interface DownloadEngine {
    suspend fun download(
        request: DownloadRequest,
        onProgress: (DownloadProgress) -> Unit
    ): DownloadResult

    suspend fun cancel(downloadId: String)
}

interface DownloadRepository {
    fun observeDownloads(): Flow<List<DownloadItem>>
    suspend fun enqueue(request: DownloadRequest): String
    suspend fun getDownload(downloadId: String): DownloadItem?
    suspend fun updateProgress(progress: DownloadProgress)
    suspend fun markCompleted(
        downloadId: String,
        destinationUri: String,
        displayName: String,
        mimeType: String,
        fileSizeBytes: Long
    )
    suspend fun markFailed(downloadId: String, userError: String, technicalError: String?)
    suspend fun markCancelled(downloadId: String)
    suspend fun prepareRetry(downloadId: String): DownloadRequest?
    suspend fun removeFromHistory(downloadId: String)
    suspend fun reconcileInterruptedJobs(): Int
}
