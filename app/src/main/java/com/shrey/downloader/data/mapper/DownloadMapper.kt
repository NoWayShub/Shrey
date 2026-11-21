package com.shrey.downloader.data.mapper

import com.shrey.downloader.core.database.DownloadEntity
import com.shrey.downloader.domain.model.AudioOutputFormat
import com.shrey.downloader.domain.model.DownloadItem
import com.shrey.downloader.domain.model.DownloadMode
import com.shrey.downloader.domain.model.DownloadRequest
import com.shrey.downloader.domain.model.DownloadStage
import com.shrey.downloader.domain.model.ThumbnailOutputFormat

object DownloadMapper {

    fun fromRequest(request: DownloadRequest): DownloadEntity = DownloadEntity(
        id = request.downloadId,
        sourceUrl = request.sourceUrl,
        title = request.mediaTitle,
        creator = request.creator,
        thumbnailUrl = request.thumbnailUrl,
        selectedFormatId = request.selectedFormatId,
        formatSelector = request.formatSelector,
        qualityLabel = request.qualityLabel,
        requestedMode = request.mode.name,
        outputContainer = request.outputExtension,
        audioOutputFormat = request.audioOutputFormat.name,
        thumbnailOutputFormat = request.thumbnailOutputFormat.name,
        requiresMerge = request.requiresMerge,
        status = DownloadStage.QUEUED.name,
        progress = 0f,
        downloadedBytes = null,
        totalBytes = null,
        speedText = null,
        etaSeconds = null,
        statusMessage = "Waiting in queue",
        destinationTreeUri = request.destinationTreeUri,
        destinationUri = null,
        outputDisplayName = null,
        mimeType = null,
        createdAt = request.createdAtMillis,
        startedAt = null,
        completedAt = null,
        lastError = null,
        technicalError = null,
        retryCount = 0
    )

    fun toDomain(entity: DownloadEntity): DownloadItem = DownloadItem(
        id = entity.id,
        sourceUrl = entity.sourceUrl,
        title = entity.title,
        creator = entity.creator,
        thumbnailUrl = entity.thumbnailUrl,
        selectedFormatId = entity.selectedFormatId,
        formatSelector = entity.formatSelector,
        qualityLabel = entity.qualityLabel,
        mode = runCatching { DownloadMode.valueOf(entity.requestedMode) }
            .getOrDefault(DownloadMode.VIDEO_AUDIO),
        outputContainer = entity.outputContainer,
        audioOutputFormat = runCatching { AudioOutputFormat.valueOf(entity.audioOutputFormat) }
            .getOrDefault(AudioOutputFormat.ORIGINAL),
        thumbnailOutputFormat = runCatching { ThumbnailOutputFormat.valueOf(entity.thumbnailOutputFormat) }
            .getOrDefault(ThumbnailOutputFormat.ORIGINAL),
        requiresMerge = entity.requiresMerge,
        stage = runCatching { DownloadStage.valueOf(entity.status) }
            .getOrDefault(DownloadStage.FAILED),
        progressPercent = entity.progress.coerceIn(0f, 100f),
        downloadedBytes = entity.downloadedBytes,
        totalBytes = entity.totalBytes,
        speedText = entity.speedText,
        etaSeconds = entity.etaSeconds,
        statusMessage = entity.statusMessage,
        destinationTreeUri = entity.destinationTreeUri,
        destinationUri = entity.destinationUri,
        outputDisplayName = entity.outputDisplayName,
        mimeType = entity.mimeType,
        createdAt = entity.createdAt,
        startedAt = entity.startedAt,
        completedAt = entity.completedAt,
        lastError = entity.lastError,
        technicalError = entity.technicalError,
        retryCount = entity.retryCount
    )

    fun toRequest(item: DownloadItem): DownloadRequest = DownloadRequest(
        downloadId = item.id,
        sourceUrl = item.sourceUrl,
        mediaTitle = item.title,
        creator = item.creator,
        thumbnailUrl = item.thumbnailUrl,
        mode = item.mode,
        selectedFormatId = item.selectedFormatId,
        formatSelector = item.formatSelector,
        outputExtension = item.outputContainer,
        qualityLabel = item.qualityLabel,
        audioOutputFormat = item.audioOutputFormat,
        thumbnailOutputFormat = item.thumbnailOutputFormat,
        requiresMerge = item.requiresMerge,
        destinationTreeUri = item.destinationTreeUri,
        createdAtMillis = item.createdAt
    )
}
