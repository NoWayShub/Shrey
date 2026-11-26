package com.shrey.downloader.domain.model

enum class MediaSourceType(val label: String) {
    SINGLE_VIDEO("Video"),
    PLAYLIST("Playlist"),
    CHANNEL("Channel"),
    AMBIGUOUS_VIDEO_OR_PLAYLIST("Video or Playlist"),
    GENERIC_SINGLE_MEDIA("Media"),
    UNSUPPORTED("Unsupported")
}

enum class ChannelContentTab(
    val label: String,
    val pathSegment: String
) {
    VIDEOS("Videos", "videos"),
    SHORTS("Shorts", "shorts"),
    STREAMS("Completed Streams", "streams")
}

enum class ChannelScopePreset(
    val label: String,
    val defaultLimit: Int?
) {
    LATEST_10("Latest 10", 10),
    LATEST_50("Latest 50", 50),
    LATEST_100("Latest 100", 100),
    CUSTOM("Custom", 25),
    ALL("All discoverable", null)
}

enum class BulkQualityPreset(
    val label: String,
    val maxHeight: Int?,
    val isAudioOnly: Boolean = false
) {
    BEST("Best available", null),
    P2160("2160p (4K)", 2160),
    P1440("1440p", 1440),
    P1080("1080p", 1080),
    P720("720p", 720),
    P480("480p", 480),
    P360("360p", 360),
    AUDIO_ONLY("Audio only", null, isAudioOnly = true);

    fun toFormatSelector(audioOutputFormat: AudioOutputFormat): String {
        if (isAudioOnly) {
            return when (audioOutputFormat) {
                AudioOutputFormat.M4A -> "bestaudio[ext=m4a]/bestaudio/best"
                AudioOutputFormat.OPUS -> "bestaudio[acodec*=opus]/bestaudio/best"
                else -> "bestaudio/best"
            }
        }
        val h = maxHeight ?: return "bestvideo+bestaudio/best"
        return "bestvideo[height<=$h]+bestaudio/best[height<=$h]/best"
    }

    fun toOutputExtension(audioOutputFormat: AudioOutputFormat): String {
        if (isAudioOnly) {
            return audioOutputFormat.extension.ifBlank { "m4a" }
        }
        return "mp4"
    }

    fun toDownloadMode(): DownloadMode =
        if (isAudioOnly) DownloadMode.AUDIO_ONLY else DownloadMode.VIDEO_AUDIO

    companion object {
        fun fromDefaultVideoQuality(quality: DefaultVideoQuality): BulkQualityPreset = when (quality) {
            DefaultVideoQuality.BEST -> BEST
            DefaultVideoQuality.P2160 -> P2160
            DefaultVideoQuality.P1440 -> P1440
            DefaultVideoQuality.P1080 -> P1080
            DefaultVideoQuality.P720 -> P720
            DefaultVideoQuality.P480 -> P480
        }
    }
}

enum class EntryAvailability(val label: String, val isDownloadable: Boolean) {
    AVAILABLE("Available", true),
    UNAVAILABLE("Unavailable", false),
    PRIVATE_OR_MEMBERS("Private / Restricted", false),
    LIVE_ACTIVE("Live now (excluded)", false)
}

data class CollectionEntry(
    val mediaId: String,
    val position: Int,
    val title: String,
    val sourceUrl: String,
    val durationSeconds: Long? = null,
    val thumbnailUrl: String? = null,
    val availability: EntryAvailability = EntryAvailability.AVAILABLE,
    val tab: ChannelContentTab? = null,
    val uploader: String? = null,
    val estimatedSizeBytes: Long? = null
)

data class CollectionPreview(
    val sourceType: MediaSourceType,
    val sourceUrl: String,
    val canonicalId: String,
    val title: String,
    val creator: String?,
    val thumbnailUrl: String?,
    val declaredTotalCount: Int?,
    val entries: List<CollectionEntry>,
    val hasMoreEntries: Boolean,
    val nextStartIndex: Int,
    val availableTabs: List<ChannelContentTab> = emptyList(),
    val unavailableTabNotes: Map<ChannelContentTab, String> = emptyMap(),
    val extractorKey: String? = null,
    val warnings: List<String> = emptyList()
) {
    val downloadableCount: Int
        get() = entries.count { it.availability.isDownloadable }

    val unavailableCount: Int
        get() = entries.count { !it.availability.isDownloadable }

    val countDisplayLabel: String
        get() = when {
            declaredTotalCount != null && declaredTotalCount > 0 ->
                if (entries.size < declaredTotalCount) {
                    "${entries.size} loaded of $declaredTotalCount videos"
                } else {
                    "$declaredTotalCount videos"
                }
            entries.isNotEmpty() -> "${entries.size} discovered${if (hasMoreEntries) "+" else ""}"
            else -> "Count unavailable"
        }
}

enum class BatchStatus(val label: String, val isTerminal: Boolean) {
    DISCOVERING("Discovering", false),
    ACTIVE("Downloading", false),
    PAUSED("Queue Paused", false),
    COMPLETED("Completed", true),
    COMPLETED_WITH_ERRORS("Completed with errors", true),
    CANCELLED("Cancelled", true),
    FAILED("Failed", true)
}

enum class BatchItemStatus(val label: String, val isTerminal: Boolean) {
    QUEUED("Queued", false),
    ACTIVE("Downloading", false),
    COMPLETED("Completed", true),
    FAILED("Failed", true),
    SKIPPED("Skipped (duplicate)", true),
    CANCELLED("Cancelled", true),
    UNAVAILABLE("Unavailable", true)
}

enum class DiscoveryStatus(val label: String, val isTerminal: Boolean) {
    RUNNING("Discovering", false),
    PAUSED("Discovery paused", false),
    STOPPED("Discovery stopped", true),
    COMPLETED("Discovery complete", true),
    FAILED("Discovery failed", true)
}

data class ChannelTabCheckpoint(
    val batchId: String,
    val tab: ChannelContentTab,
    val nextStartIndex: Int,
    val discoveredCount: Int,
    val isExhausted: Boolean,
    val lastError: String? = null,
    val updatedAt: Long = 0L
)

data class DownloadBatch(
    val batchId: String,
    val sourceUrl: String,
    val canonicalId: String,
    val sourceType: MediaSourceType,
    val displayName: String,
    val creator: String?,
    val thumbnailUrl: String?,
    val status: BatchStatus,
    val qualityPreset: BulkQualityPreset,
    val downloadMode: DownloadMode,
    val audioOutputFormat: AudioOutputFormat,
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
    val lastError: String? = null,
    val discoveryStatus: DiscoveryStatus = DiscoveryStatus.COMPLETED,
    val targetLimit: Int? = null,
    val knownTotalCount: Int? = null,
    val discoveryError: String? = null,
    val selectedTabs: List<ChannelContentTab> = emptyList()
) {
    val isAutoDiscovery: Boolean
        get() = sourceType == MediaSourceType.CHANNEL && selectedTabs.isNotEmpty()

    val isDiscoveryActive: Boolean
        get() = discoveryStatus == DiscoveryStatus.RUNNING

    val canStopDiscovery: Boolean
        get() = discoveryStatus == DiscoveryStatus.RUNNING || discoveryStatus == DiscoveryStatus.PAUSED

    val settledCount: Int
        get() = (completedCount + skippedCount).coerceAtMost(selectedCount)

    val progressFraction: Float
        get() = if (selectedCount <= 0) 0f else (settledCount.toFloat() / selectedCount.toFloat()).coerceIn(0f, 1f)

    val discoveryProgressFraction: Float?
        get() {
            val denom = targetLimit ?: knownTotalCount?.takeIf { it > 0 } ?: return null
            return (discoveredCount.toFloat() / denom.toFloat()).coerceIn(0f, 1f)
        }

    val discoveryProgressLabel: String
        get() = when {
            knownTotalCount != null && knownTotalCount > 0 ->
                "Discovered $discoveredCount of $knownTotalCount videos"
            targetLimit != null && targetLimit > 0 ->
                "Discovered $discoveredCount of $targetLimit target videos"
            else ->
                "Discovered $discoveredCount video${if (discoveredCount == 1) "" else "s"}"
        }

    val overallProgressLabel: String
        get() = buildString {
            append("$completedCount of $selectedCount completed")
            if (skippedCount > 0) append(" · $skippedCount skipped")
            if (failedCount > 0) append(" · $failedCount failed")
        }
}

data class BatchItem(
    val itemId: String,
    val batchId: String,
    val downloadId: String?,
    val mediaId: String,
    val position: Int,
    val title: String,
    val sourceUrl: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val tab: ChannelContentTab?,
    val status: BatchItemStatus,
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

data class BatchWithItems(
    val batch: DownloadBatch,
    val items: List<BatchItem>,
    val totalItemCount: Int = batch.selectedCount.coerceAtLeast(items.size)
)
