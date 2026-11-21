package com.shrey.downloader.core.engine

import com.shrey.downloader.domain.model.DownloadProgress
import com.shrey.downloader.domain.model.DownloadStage

object ProgressParser {

    // Matches e.g.: [download]  42.5% of ~ 52.30MiB at  3.20MiB/s ETA 00:09
    private val speedRegex = Regex("""at\s+([0-9.]+\s*[KMGTP]?i?B/s)""", RegexOption.IGNORE_CASE)
    private val sizeRegex = Regex("""of\s+~?\s*([0-9.]+)\s*([KMGTP]?i?B)""", RegexOption.IGNORE_CASE)
    private val percentRegex = Regex("""([0-9]{1,3}(?:\.[0-9]+)?)%""")

    fun parseCallback(
        downloadId: String,
        progressFloat: Float,
        etaSeconds: Long,
        line: String?,
        previousStage: DownloadStage
    ): DownloadProgress {
        val text = line.orEmpty()
        val stage = detectStage(text, previousStage)

        val percentFromLine = percentRegex.find(text)?.groupValues?.getOrNull(1)?.toFloatOrNull()
        val validPercent = when {
            stage == DownloadStage.MERGING || stage == DownloadStage.CONVERTING -> 96f
            progressFloat in 0f..100f -> progressFloat
            percentFromLine != null && percentFromLine in 0f..100f -> percentFromLine
            else -> null
        }

        val speedText = speedRegex.find(text)?.groupValues?.getOrNull(1)?.replace(" ", "")
        val totalBytes = parseSizeBytes(text)
        val downloadedBytes = if (totalBytes != null && validPercent != null) {
            ((totalBytes.toDouble() * validPercent.toDouble()) / 100.0).toLong()
        } else {
            null
        }

        val validEta = if (etaSeconds >= 0L && stage == DownloadStage.DOWNLOADING) etaSeconds else null

        val message = when (stage) {
            DownloadStage.MERGING -> "Merging video and audio with FFmpeg…"
            DownloadStage.CONVERTING -> "Converting audio format with FFmpeg…"
            DownloadStage.EXTRACTING -> "Fetching stream URLs…"
            DownloadStage.DOWNLOADING -> speedText?.let { "Downloading · $it" } ?: "Downloading stream…"
            else -> stage.label
        }

        return DownloadProgress(
            downloadId = downloadId,
            stage = stage,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            progressPercent = validPercent,
            speedText = speedText,
            etaSeconds = validEta,
            message = message
        )
    }

    internal fun detectStage(line: String, fallback: DownloadStage): DownloadStage {
        return when {
            line.contains("[Merger]", ignoreCase = true) ||
                line.contains("Merging formats", ignoreCase = true) -> DownloadStage.MERGING

            line.contains("[ExtractAudio]", ignoreCase = true) ||
                line.contains("[FFmpeg", ignoreCase = true) ||
                line.contains("Converting", ignoreCase = true) -> DownloadStage.CONVERTING

            line.contains("[download]", ignoreCase = true) -> DownloadStage.DOWNLOADING

            line.contains("[info]", ignoreCase = true) ||
                line.contains("Extracting", ignoreCase = true) -> DownloadStage.EXTRACTING

            else -> fallback
        }
    }

    internal fun parseSizeBytes(line: String): Long? {
        val match = sizeRegex.find(line) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].uppercase()
        val multiplier = when (unit) {
            "B" -> 1.0
            "KB" -> 1_000.0
            "KIB" -> 1_024.0
            "MB" -> 1_000_000.0
            "MIB" -> 1_048_576.0
            "GB" -> 1_000_000_000.0
            "GIB" -> 1_073_741_824.0
            else -> return null
        }
        return (value * multiplier).toLong()
    }
}
