package com.shrey.downloader.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val id: String,
    val sourceUrl: String,
    val title: String,
    val creator: String?,
    val thumbnailUrl: String?,
    val selectedFormatId: String,
    val formatSelector: String,
    val qualityLabel: String,
    val requestedMode: String,
    val outputContainer: String,
    val audioOutputFormat: String,
    val thumbnailOutputFormat: String,
    val requiresMerge: Boolean,
    val status: String,
    val progress: Float,
    val downloadedBytes: Long?,
    val totalBytes: Long?,
    val speedText: String?,
    val etaSeconds: Long?,
    val statusMessage: String?,
    val destinationTreeUri: String?,
    val destinationUri: String?,
    val outputDisplayName: String?,
    val mimeType: String?,
    val createdAt: Long,
    val startedAt: Long?,
    val completedAt: Long?,
    val lastError: String?,
    val technicalError: String?,
    val retryCount: Int,
    val batchId: String? = null,
    val mediaId: String? = null,
    val relativeSubfolder: String? = null
)
