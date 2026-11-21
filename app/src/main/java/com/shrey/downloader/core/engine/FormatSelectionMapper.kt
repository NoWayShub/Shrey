package com.shrey.downloader.core.engine

import com.shrey.downloader.core.utilities.Formatters
import com.shrey.downloader.core.validation.UrlValidator
import com.shrey.downloader.domain.model.AudioOutputFormat
import com.shrey.downloader.domain.model.DefaultVideoQuality
import com.shrey.downloader.domain.model.DownloadMode
import com.shrey.downloader.domain.model.FormatOption
import com.shrey.downloader.domain.model.MediaFormat
import com.shrey.downloader.domain.model.MediaInfo
import com.shrey.downloader.domain.model.ThumbnailCandidate
import com.shrey.downloader.domain.model.ThumbnailOutputFormat
import kotlin.math.roundToInt

object FormatSelectionMapper {

    fun buildOptions(
        media: MediaInfo,
        mode: DownloadMode,
        audioOutputFormat: AudioOutputFormat = AudioOutputFormat.ORIGINAL,
        thumbnailOutputFormat: ThumbnailOutputFormat = ThumbnailOutputFormat.ORIGINAL
    ): List<FormatOption> {
        return when (mode) {
            DownloadMode.VIDEO_AUDIO -> buildVideoWithAudioOptions(media)
            DownloadMode.AUDIO_ONLY -> buildAudioOnlyOptions(media, audioOutputFormat)
            DownloadMode.VIDEO_ONLY -> buildVideoOnlyOptions(media)
            DownloadMode.THUMBNAIL_ONLY -> buildThumbnailOptions(media, thumbnailOutputFormat)
        }
    }

    fun selectDefaultOption(
        options: List<FormatOption>,
        preferredVideoQuality: DefaultVideoQuality
    ): FormatOption? {
        if (options.isEmpty()) return null
        val cap = preferredVideoQuality.maxHeight ?: return options.first()

        // Find highest non-Best option at or below user's preferred resolution cap
        val matching = options
            .filter { !it.isBestAvailable && (it.primaryFormat?.height ?: Int.MAX_VALUE) <= cap }
            .maxByOrNull { it.primaryFormat?.height ?: 0 }

        return matching ?: options.first()
    }

    private fun buildVideoWithAudioOptions(media: MediaInfo): List<FormatOption> {
        val videoFormats = media.formats.filter { it.hasVideo && UrlValidator.isSafeFormatSelector(it.formatId) }
        if (videoFormats.isEmpty()) return emptyList()

        val audioOnlyFormats = media.formats
            .filter { it.hasAudio && !it.hasVideo && UrlValidator.isSafeFormatSelector(it.formatId) }
            .sortedByDescending { it.audioBitrateKbps ?: it.totalBitrateKbps ?: 0.0 }

        // Group by resolution + high-fps distinction so users see clean, real quality tiers
        data class QualityBucket(val height: Int, val isHighFps: Boolean)

        val buckets = videoFormats.groupBy { format ->
            val h = format.height ?: 0
            val highFps = (format.fps ?: 0.0) >= 48.0
            QualityBucket(h, highFps)
        }

        val perQualityOptions = buckets.entries
            .sortedWith(
                compareByDescending<Map.Entry<QualityBucket, List<MediaFormat>>> { it.key.height }
                    .thenByDescending { it.key.isHighFps }
            )
            .mapNotNull { (bucket, candidates) ->
                val chosenVideo = pickBestVideoCandidate(candidates) ?: return@mapNotNull null

                if (chosenVideo.hasAudio) {
                    val label = formatVideoLabel(chosenVideo, bucket.isHighFps)
                    val subtitleParts = buildList {
                        add(chosenVideo.ext.uppercase())
                        Formatters.formatSizeWithEstimate(
                            chosenVideo.filesizeBytes,
                            chosenVideo.isFilesizeEstimated
                        )?.let { add(it) }
                        add("Direct")
                    }
                    FormatOption(
                        id = "va_${chosenVideo.formatId}",
                        label = label,
                        subtitle = subtitleParts.joinToString(" · "),
                        formatSelector = chosenVideo.formatId,
                        outputExtension = chosenVideo.ext.ifBlank { "mp4" },
                        primaryFormat = chosenVideo,
                        companionAudioFormat = null,
                        requiresMerge = false,
                        isBestAvailable = false,
                        estimatedSizeBytes = chosenVideo.filesizeBytes,
                        isSizeEstimated = chosenVideo.isFilesizeEstimated
                    )
                } else {
                    val companionAudio = pickCompatibleAudio(chosenVideo, audioOnlyFormats)
                    if (companionAudio == null) {
                        // Cannot form video+audio without an audio stream
                        return@mapNotNull null
                    }
                    val mergedContainer = resolveMergedContainer(chosenVideo, companionAudio)
                    val combinedSize = combineFileSizes(chosenVideo, companionAudio)
                    val label = formatVideoLabel(chosenVideo, bucket.isHighFps)
                    val subtitleParts = buildList {
                        add(mergedContainer.uppercase())
                        if (chosenVideo.isHdr) add(chosenVideo.dynamicRange ?: "HDR")
                        Formatters.formatSizeWithEstimate(
                            combinedSize.first,
                            combinedSize.second
                        )?.let { add(it) }
                        add("Merged")
                    }
                    FormatOption(
                        id = "va_${chosenVideo.formatId}_${companionAudio.formatId}",
                        label = label,
                        subtitle = subtitleParts.joinToString(" · "),
                        formatSelector = "${chosenVideo.formatId}+${companionAudio.formatId}",
                        outputExtension = mergedContainer,
                        primaryFormat = chosenVideo,
                        companionAudioFormat = companionAudio,
                        requiresMerge = true,
                        isBestAvailable = false,
                        estimatedSizeBytes = combinedSize.first,
                        isSizeEstimated = combinedSize.second
                    )
                }
            }

        if (perQualityOptions.isEmpty()) return emptyList()

        val topOption = perQualityOptions.first()
        val bestOption = topOption.copy(
            id = "va_best",
            label = "Best Available (${topOption.label})",
            isBestAvailable = true
        )

        return listOf(bestOption) + perQualityOptions
    }

    private fun buildAudioOnlyOptions(
        media: MediaInfo,
        outputFormat: AudioOutputFormat
    ): List<FormatOption> {
        val pureAudio = media.formats
            .filter { it.hasAudio && !it.hasVideo && UrlValidator.isSafeFormatSelector(it.formatId) }
        val pool = pureAudio.ifEmpty {
            media.formats.filter { it.hasAudio && UrlValidator.isSafeFormatSelector(it.formatId) }
        }
        if (pool.isEmpty()) return emptyList()

        val sorted = pool.sortedWith(
            compareByDescending<MediaFormat> { it.audioBitrateKbps ?: it.totalBitrateKbps ?: 0.0 }
                .thenByDescending { if (it.ext == "m4a" || it.ext == "opus") 1 else 0 }
        )

        // Deduplicate very similar audio tracks by rounded bitrate + codec family
        val distinct = sorted.distinctBy { fmt ->
            val kbps = ((fmt.audioBitrateKbps ?: fmt.totalBitrateKbps ?: 0.0) / 8.0).roundToInt() * 8
            "${kbps}_${fmt.ext}_${fmt.audioCodec?.substringBefore('.')}"
        }

        val options = distinct.map { fmt ->
            val sourceBitrate = Formatters.formatBitrate(fmt.audioBitrateKbps ?: fmt.totalBitrateKbps)
            val resolvedExt = if (outputFormat == AudioOutputFormat.ORIGINAL) {
                fmt.ext.ifBlank { "m4a" }
            } else {
                outputFormat.extension
            }
            val label = buildString {
                append(sourceBitrate ?: fmt.ext.uppercase())
                if (outputFormat != AudioOutputFormat.ORIGINAL) {
                    append(" → ${outputFormat.label}")
                }
            }
            val subtitleParts = buildList {
                add("Source: ${fmt.ext.uppercase()}")
                fmt.audioCodec?.substringBefore('.')?.let { add(it) }
                if (outputFormat == AudioOutputFormat.ORIGINAL) {
                    Formatters.formatSizeWithEstimate(fmt.filesizeBytes, fmt.isFilesizeEstimated)?.let { add(it) }
                } else {
                    add("FFmpeg transcode")
                }
            }
            FormatOption(
                id = "ao_${fmt.formatId}_${outputFormat.name}",
                label = label,
                subtitle = subtitleParts.joinToString(" · "),
                formatSelector = fmt.formatId,
                outputExtension = resolvedExt,
                primaryFormat = fmt,
                companionAudioFormat = null,
                requiresMerge = false,
                isBestAvailable = false,
                estimatedSizeBytes = if (outputFormat == AudioOutputFormat.ORIGINAL) fmt.filesizeBytes else null,
                isSizeEstimated = fmt.isFilesizeEstimated || outputFormat != AudioOutputFormat.ORIGINAL
            )
        }

        val top = options.first()
        val bestOption = top.copy(
            id = "ao_best_${outputFormat.name}",
            label = "Best Audio (${top.label})",
            isBestAvailable = true
        )
        return listOf(bestOption) + options
    }

    private fun buildVideoOnlyOptions(media: MediaInfo): List<FormatOption> {
        val videoOnly = media.formats
            .filter { it.hasVideo && !it.hasAudio && UrlValidator.isSafeFormatSelector(it.formatId) }
        if (videoOnly.isEmpty()) return emptyList()

        data class Bucket(val height: Int, val isHighFps: Boolean)

        val grouped = videoOnly.groupBy { Bucket(it.height ?: 0, (it.fps ?: 0.0) >= 48.0) }
        val options = grouped.entries
            .sortedWith(
                compareByDescending<Map.Entry<Bucket, List<MediaFormat>>> { it.key.height }
                    .thenByDescending { it.key.isHighFps }
            )
            .mapNotNull { (bucket, candidates) ->
                val chosen = pickBestVideoCandidate(candidates) ?: return@mapNotNull null
                val label = formatVideoLabel(chosen, bucket.isHighFps)
                val subtitleParts = buildList {
                    add(chosen.ext.uppercase())
                    chosen.videoCodec?.substringBefore('.')?.let { add(it) }
                    if (chosen.isHdr) add(chosen.dynamicRange ?: "HDR")
                    Formatters.formatSizeWithEstimate(chosen.filesizeBytes, chosen.isFilesizeEstimated)?.let { add(it) }
                    add("No audio")
                }
                FormatOption(
                    id = "vo_${chosen.formatId}",
                    label = label,
                    subtitle = subtitleParts.joinToString(" · "),
                    formatSelector = chosen.formatId,
                    outputExtension = chosen.ext.ifBlank { "mp4" },
                    primaryFormat = chosen,
                    companionAudioFormat = null,
                    requiresMerge = false,
                    isBestAvailable = false,
                    estimatedSizeBytes = chosen.filesizeBytes,
                    isSizeEstimated = chosen.isFilesizeEstimated
                )
            }

        if (options.isEmpty()) return emptyList()
        val top = options.first()
        return listOf(
            top.copy(
                id = "vo_best",
                label = "Best Video Only (${top.label})",
                isBestAvailable = true
            )
        ) + options
    }

    private fun buildThumbnailOptions(
        media: MediaInfo,
        outputFormat: ThumbnailOutputFormat
    ): List<FormatOption> {
        val candidates = buildList {
            addAll(media.thumbnails)
            if (isEmpty() && !media.thumbnailUrl.isNullOrBlank()) {
                val ext = media.thumbnailUrl.substringBefore('?').substringAfterLast('.', "").lowercase()
                    .takeIf { it in setOf("jpg", "jpeg", "png", "webp") }
                add(ThumbnailCandidate(url = media.thumbnailUrl, extension = ext))
            }
        }
        if (candidates.isEmpty()) return emptyList()

        val sorted = candidates.sortedByDescending { (it.width ?: 0) * (it.height ?: 0) }
        val best = sorted.first()
        val ext = when (outputFormat) {
            ThumbnailOutputFormat.ORIGINAL -> best.extension ?: "jpg"
            ThumbnailOutputFormat.JPG -> "jpg"
            ThumbnailOutputFormat.PNG -> "png"
        }
        val dims = best.dimensionLabel ?: "Source resolution"
        val formatLabel = if (outputFormat == ThumbnailOutputFormat.ORIGINAL) {
            ext.uppercase()
        } else {
            "${outputFormat.label} converted"
        }

        return listOf(
            FormatOption(
                id = "thumb_${outputFormat.name}",
                label = dims,
                subtitle = "Thumbnail · $formatLabel",
                formatSelector = best.url,
                outputExtension = ext,
                primaryFormat = null,
                companionAudioFormat = null,
                requiresMerge = false,
                isBestAvailable = true,
                estimatedSizeBytes = null,
                isSizeEstimated = true
            )
        )
    }

    private fun pickBestVideoCandidate(candidates: List<MediaFormat>): MediaFormat? {
        return candidates.sortedWith(
            compareByDescending<MediaFormat> { if (it.ext == "mp4") 2 else if (it.ext == "webm") 1 else 0 }
                .thenByDescending { codecCompatibilityScore(it.videoCodec) }
                .thenByDescending { it.fps ?: 0.0 }
                .thenByDescending { it.totalBitrateKbps ?: 0.0 }
        ).firstOrNull()
    }

    private fun codecCompatibilityScore(vcodec: String?): Int {
        val c = vcodec?.lowercase().orEmpty()
        return when {
            c.startsWith("avc1") || c.startsWith("h264") -> 3
            c.startsWith("vp9") || c.startsWith("vp09") -> 2
            c.startsWith("av01") || c.startsWith("hvc1") || c.startsWith("hev1") -> 1
            else -> 0
        }
    }

    private fun pickCompatibleAudio(video: MediaFormat, audios: List<MediaFormat>): MediaFormat? {
        if (audios.isEmpty()) return null
        return if (video.ext == "mp4") {
            audios.firstOrNull { it.ext == "m4a" || it.audioCodec?.startsWith("mp4a", ignoreCase = true) == true }
                ?: audios.first()
        } else {
            audios.firstOrNull { it.ext == "webm" || it.audioCodec?.contains("opus", ignoreCase = true) == true }
                ?: audios.first()
        }
    }

    private fun resolveMergedContainer(video: MediaFormat, audio: MediaFormat): String {
        return when {
            video.ext == "mp4" && (audio.ext == "m4a" || audio.ext == "mp4") -> "mp4"
            video.ext == "webm" && (audio.ext == "webm" || audio.ext == "opus") -> "webm"
            else -> "mkv"
        }
    }

    private fun combineFileSizes(video: MediaFormat, audio: MediaFormat): Pair<Long?, Boolean> {
        val vSize = video.filesizeBytes
        val aSize = audio.filesizeBytes
        return when {
            vSize != null && aSize != null -> (vSize + aSize) to (video.isFilesizeEstimated || audio.isFilesizeEstimated)
            vSize != null -> vSize to true
            else -> null to true
        }
    }

    private fun formatVideoLabel(format: MediaFormat, isHighFps: Boolean): String {
        val base = format.resolutionLabel
        return if (isHighFps && format.fps != null) {
            "$base · ${format.fps.roundToInt()}fps"
        } else {
            base
        }
    }
}
