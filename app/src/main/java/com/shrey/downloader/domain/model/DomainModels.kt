package com.shrey.downloader.domain.model

enum class DownloadMode(val label: String) {
    VIDEO_AUDIO("Video"),
    AUDIO_ONLY("Audio"),
    VIDEO_ONLY("Video Only"),
    THUMBNAIL_ONLY("Thumbnail")
}

enum class AudioOutputFormat(
    val label: String,
    val extension: String,
    val ytDlpAudioFormat: String?,
    val description: String
) {
    ORIGINAL("Original", "", null, "Keep source audio stream without re-encoding"),
    M4A("M4A", "m4a", "m4a", "AAC audio container via FFmpeg"),
    MP3("MP3", "mp3", "mp3", "MP3 audio transcoded via FFmpeg"),
    OPUS("Opus", "opus", "opus", "Opus audio transcoded via FFmpeg")
}

enum class ThumbnailOutputFormat(
    val label: String,
    val extension: String
) {
    ORIGINAL("Original", ""),
    JPG("JPG", "jpg"),
    PNG("PNG", "png")
}

enum class DownloadStage(val label: String, val isTerminal: Boolean, val isActive: Boolean) {
    QUEUED("Queued", isTerminal = false, isActive = false),
    PREPARING("Preparing", isTerminal = false, isActive = true),
    EXTRACTING("Extracting", isTerminal = false, isActive = true),
    DOWNLOADING("Downloading", isTerminal = false, isActive = true),
    MERGING("Merging streams", isTerminal = false, isActive = true),
    CONVERTING("Converting media", isTerminal = false, isActive = true),
    SAVING("Saving to device", isTerminal = false, isActive = true),
    COMPLETED("Completed", isTerminal = true, isActive = false),
    FAILED("Failed", isTerminal = true, isActive = false),
    CANCELLED("Cancelled", isTerminal = true, isActive = false),
    INTERRUPTED("Interrupted", isTerminal = true, isActive = false)
}

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    DARK("Dark"),
    LIGHT("Light")
}

enum class GlassEffectMode(val label: String) {
    FULL("Full"),
    REDUCED("Reduced")
}

enum class DefaultVideoQuality(val label: String, val maxHeight: Int?) {
    BEST("Best Available", null),
    P2160("2160p (4K)", 2160),
    P1440("1440p", 1440),
    P1080("1080p", 1080),
    P720("720p", 720),
    P480("480p", 480)
}

enum class LibraryFilter(val label: String) {
    ALL("All"),
    VIDEOS("Videos"),
    AUDIO("Audio"),
    IMAGES("Images")
}

data class ThumbnailCandidate(
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
    val extension: String? = null
) {
    val dimensionLabel: String?
        get() = if (width != null && height != null && width > 0 && height > 0) {
            "${width} × ${height}"
        } else {
            null
        }
}

data class MediaFormat(
    val formatId: String,
    val ext: String,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Double? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val audioBitrateKbps: Double? = null,
    val totalBitrateKbps: Double? = null,
    val filesizeBytes: Long? = null,
    val isFilesizeEstimated: Boolean = false,
    val hasVideo: Boolean = false,
    val hasAudio: Boolean = false,
    val requiresMerge: Boolean = false,
    val dynamicRange: String? = null,
    val formatNote: String? = null
) {
    val resolutionLabel: String
        get() = when {
            height != null && height >= 4320 -> "${height}p 8K"
            height != null && height >= 2160 -> "${height}p 4K"
            height != null && height > 0 -> "${height}p"
            !formatNote.isNullOrBlank() -> formatNote
            hasAudio && !hasVideo -> audioBitrateKbps?.let { "${it.toInt()} kbps" } ?: "Audio"
            else -> ext.uppercase()
        }

    val isHdr: Boolean
        get() = !dynamicRange.isNullOrBlank() && !dynamicRange.equals("SDR", ignoreCase = true)
}

data class MediaInfo(
    val id: String,
    val originalUrl: String,
    val canonicalUrl: String?,
    val title: String,
    val creator: String?,
    val durationSeconds: Long?,
    val thumbnailUrl: String?,
    val thumbnails: List<ThumbnailCandidate>,
    val formats: List<MediaFormat>,
    val platform: String,
    val isLive: Boolean,
    val extractedAtMillis: Long,
    val warnings: List<String> = emptyList()
)

data class FormatOption(
    val id: String,
    val label: String,
    val subtitle: String,
    val formatSelector: String,
    val outputExtension: String,
    val primaryFormat: MediaFormat?,
    val companionAudioFormat: MediaFormat? = null,
    val requiresMerge: Boolean = false,
    val isBestAvailable: Boolean = false,
    val estimatedSizeBytes: Long? = null,
    val isSizeEstimated: Boolean = true
)

data class DownloadRequest(
    val downloadId: String,
    val sourceUrl: String,
    val mediaTitle: String,
    val creator: String?,
    val thumbnailUrl: String?,
    val mode: DownloadMode,
    val selectedFormatId: String,
    val formatSelector: String,
    val outputExtension: String,
    val qualityLabel: String,
    val audioOutputFormat: AudioOutputFormat = AudioOutputFormat.ORIGINAL,
    val thumbnailOutputFormat: ThumbnailOutputFormat = ThumbnailOutputFormat.ORIGINAL,
    val requiresMerge: Boolean = false,
    val destinationTreeUri: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val batchId: String? = null,
    val mediaId: String? = null,
    val relativeSubfolder: String? = null
)

data class DownloadProgress(
    val downloadId: String,
    val stage: DownloadStage,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val progressPercent: Float? = null,
    val speedText: String? = null,
    val etaSeconds: Long? = null,
    val message: String? = null,
    val timestampMillis: Long = System.currentTimeMillis()
)

data class DownloadItem(
    val id: String,
    val sourceUrl: String,
    val title: String,
    val creator: String?,
    val thumbnailUrl: String?,
    val selectedFormatId: String,
    val formatSelector: String,
    val qualityLabel: String,
    val mode: DownloadMode,
    val outputContainer: String,
    val audioOutputFormat: AudioOutputFormat,
    val thumbnailOutputFormat: ThumbnailOutputFormat,
    val requiresMerge: Boolean,
    val stage: DownloadStage,
    val progressPercent: Float,
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

sealed interface DownloadResult {
    data class Success(
        val destinationUri: String,
        val displayName: String,
        val mimeType: String,
        val fileSizeBytes: Long
    ) : DownloadResult

    data object Cancelled : DownloadResult

    data class Failure(val error: ShreyError) : DownloadResult
}

sealed class ShreyError(
    val title: String,
    val userMessage: String,
    val actionHint: String,
    val technicalDetails: String? = null
) : Exception(userMessage) {

    class InvalidUrl(details: String? = null) : ShreyError(
        title = "Invalid link",
        userMessage = "Enter a valid public http:// or https:// media URL.",
        actionHint = "Check the pasted link and try again.",
        technicalDetails = details
    )

    class UnsupportedLink(details: String? = null) : ShreyError(
        title = "Unsupported link",
        userMessage = "This site or media link is not supported by the current local yt-dlp extractors.",
        actionHint = "Try a direct link to a supported public video or audio page.",
        technicalDetails = details
    )

    class NetworkUnavailable(details: String? = null) : ShreyError(
        title = "No internet connection",
        userMessage = "Connect to a network or adjust your mobile data preference in Settings.",
        actionHint = "Check connection",
        technicalDetails = details
    )

    class ExtractionTimeout(details: String? = null) : ShreyError(
        title = "Extraction timed out",
        userMessage = "The source took too long to respond while reading media metadata.",
        actionHint = "Retry",
        technicalDetails = details
    )

    class AuthenticationRequired(details: String? = null) : ShreyError(
        title = "Sign-in required",
        userMessage = "This content requires account authentication, which Shrey does not bypass.",
        actionHint = "Use publicly accessible media you have permission to download.",
        technicalDetails = details
    )

    class ContentInaccessible(details: String? = null) : ShreyError(
        title = "Protected or restricted media",
        userMessage = "This media is DRM-protected, private, or blocked by the source platform.",
        actionHint = "Shrey only downloads accessible, non-DRM media.",
        technicalDetails = details
    )

    class ContentUnavailable(details: String? = null) : ShreyError(
        title = "Media unavailable",
        userMessage = "The video or audio could not be found or has been removed.",
        actionHint = "Verify the link still opens in a browser.",
        technicalDetails = details
    )

    class GeoRestricted(details: String? = null) : ShreyError(
        title = "Region restricted",
        userMessage = "The publisher has restricted this media in your region.",
        actionHint = "This restriction is enforced by the source host.",
        technicalDetails = details
    )

    class LiveContentUnsupported(details: String? = null) : ShreyError(
        title = "Live stream unsupported",
        userMessage = "Active live streams are not currently supported in Shrey.",
        actionHint = "Wait until the broadcast finishes and is archived.",
        technicalDetails = details
    )

    class MediaRequestForbidden(details: String? = null) : ShreyError(
        title = "Media request denied (HTTP 403)",
        userMessage = "The media host rejected the stream download request with HTTP 403 Forbidden. Stream URLs may have expired or been restricted by the host for this client.",
        actionHint = "Re-analyze the link to refresh stream URLs, try another quality, or view technical details.",
        technicalDetails = details
    )

    class RateLimited(details: String? = null) : ShreyError(
        title = "Rate limited (HTTP 429)",
        userMessage = "The media host temporarily throttled requests from this network (HTTP 429 Too Many Requests).",
        actionHint = "Wait a few minutes before retrying.",
        technicalDetails = details
    )

    class ExtractorCompatibilityError(details: String? = null) : ShreyError(
        title = "Extractor compatibility issue",
        userMessage = "The media host changed its streaming protocol or format handshake (such as SABR-only delivery) and the local extractor could not obtain a downloadable stream.",
        actionHint = "Try a different quality or format, or inspect technical details.",
        technicalDetails = details
    )

    class MissingRuntimeDependency(details: String? = null) : ShreyError(
        title = "Runtime limitation",
        userMessage = "This platform requires an external JavaScript challenge runtime (yt-dlp-ejs) that is not bundled on this device.",
        actionHint = "View technical details or try another available format.",
        technicalDetails = details
    )

    class MissingFormats(details: String? = null) : ShreyError(
        title = "No compatible formats",
        userMessage = "No downloadable streams matched the selected mode or quality.",
        actionHint = "Choose another quality or mode.",
        technicalDetails = details
    )

    class InsufficientStorage(details: String? = null) : ShreyError(
        title = "Insufficient storage",
        userMessage = "Free up storage space on your device before saving this file.",
        actionHint = "Clear space or choose another destination.",
        technicalDetails = details
    )

    class ConversionFailed(details: String? = null) : ShreyError(
        title = "Conversion failed",
        userMessage = "FFmpeg could not merge or convert the downloaded stream into the requested format.",
        actionHint = "Try Original format or another quality.",
        technicalDetails = details
    )

    class DownloadInterrupted(details: String? = null) : ShreyError(
        title = "Download interrupted",
        userMessage = "The transfer was interrupted before finishing.",
        actionHint = "Retry",
        technicalDetails = details
    )

    class StorageWriteFailed(details: String? = null) : ShreyError(
        title = "Could not save file",
        userMessage = "Shrey could not write the finished file to the selected storage destination.",
        actionHint = "Change destination in Settings or retry.",
        technicalDetails = details
    )

    class EngineInitializationFailed(details: String? = null) : ShreyError(
        title = "Engine initialization failed",
        userMessage = "The local yt-dlp or FFmpeg runtime could not start on this device.",
        actionHint = "Retry engine initialization.",
        technicalDetails = details
    )

    class ExtractorFailure(
        message: String = "The local extractor could not read this link.",
        details: String? = null
    ) : ShreyError(
        title = "Extraction failed",
        userMessage = message,
        actionHint = "Retry or view technical details.",
        technicalDetails = details
    )
}

data class EngineDiagnostics(
    val ytDlpVersion: String?,
    val ffmpegAvailable: Boolean,
    val jsRuntimeName: String?,
    val jsRuntimeNote: String,
    val pythonVersion: String? = null,
    val ejsVersion: String? = null,
    val ffmpegVersion: String = "7.1.1"
)

sealed interface EngineState {
    data object Idle : EngineState
    data object Initializing : EngineState
    data class Ready(val diagnostics: EngineDiagnostics) : EngineState
    data class Failed(val error: ShreyError) : EngineState
}

data class ShreySettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val glassEffectMode: GlassEffectMode = GlassEffectMode.FULL,
    val defaultVideoQuality: DefaultVideoQuality = DefaultVideoQuality.BEST,
    val defaultAudioFormat: AudioOutputFormat = AudioOutputFormat.ORIGINAL,
    val defaultDownloadMode: DownloadMode = DownloadMode.VIDEO_AUDIO,
    val customDestinationUri: String? = null,
    val customDestinationLabel: String? = null,
    val allowMobileData: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val notificationsEnabled: Boolean = true
) {
    val wifiOnlyDownloads: Boolean
        get() = !allowMobileData
}
