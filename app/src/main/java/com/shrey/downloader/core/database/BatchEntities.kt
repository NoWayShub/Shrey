package com.shrey.downloader.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "download_batches")
data class DownloadBatchEntity(
    @PrimaryKey val batchId: String,
    val sourceUrl: String,
    val canonicalId: String,
    val sourceType: String,
    val displayName: String,
    val creator: String?,
    val thumbnailUrl: String?,
    val status: String,
    val qualityPreset: String,
    val downloadMode: String,
    val audioOutputFormat: String,
    val wifiOnly: Boolean,
    val discoveredCount: Int,
    val selectedCount: Int,
    val completedCount: Int,
    val failedCount: Int,
    val skippedCount: Int,
    val activeCount: Int,
    val totalBytesDownloaded: Long,
    val checkpointNextIndex: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val lastError: String?,
    val discoveryStatus: String = "COMPLETED",
    val targetLimit: Int? = null,
    val knownTotalCount: Int? = null,
    val discoveryError: String? = null,
    val selectedTabsCsv: String = ""
)

@Entity(
    tableName = "batch_items",
    indices = [
        Index(value = ["batchId"]),
        Index(value = ["downloadId"]),
        Index(value = ["mediaId"]),
        Index(value = ["batchId", "status", "position"])
    ]
)
data class BatchItemEntity(
    @PrimaryKey val itemId: String,
    val batchId: String,
    val downloadId: String?,
    val mediaId: String,
    val position: Int,
    val title: String,
    val sourceUrl: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val tab: String?,
    val status: String,
    val progressPercent: Float,
    val downloadedBytes: Long?,
    val totalBytes: Long?,
    val speedText: String?,
    val etaSeconds: Long?,
    val destinationUri: String?,
    val outputDisplayName: String?,
    val mimeType: String?,
    val errorMessage: String?,
    val technicalError: String?,
    val retryCount: Int,
    val updatedAt: Long
)

@Entity(
    tableName = "channel_discovery_checkpoints",
    primaryKeys = ["batchId", "tab"],
    indices = [Index(value = ["batchId"])]
)
data class ChannelDiscoveryCheckpointEntity(
    val batchId: String,
    val tab: String,
    val nextStartIndex: Int,
    val discoveredCount: Int,
    val isExhausted: Boolean,
    val lastError: String?,
    val updatedAt: Long
)
