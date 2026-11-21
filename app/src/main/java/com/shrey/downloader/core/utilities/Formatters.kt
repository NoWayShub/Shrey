package com.shrey.downloader.core.utilities

import java.util.Locale
import kotlin.math.roundToInt

object Formatters {

    private val authHeaderRegex = Regex("""(?i)(authorization|cookie|token|api[_-]?key|password|secret)\s*[:=]\s*\S+""")
    private val localPathRegex = Regex("""/(data|storage|sdcard|mnt)/[^\s"']+""")

    fun formatDuration(totalSeconds: Long?): String {
        if (totalSeconds == null || totalSeconds < 0) return "—"
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    fun formatBytes(bytes: Long?): String {
        if (bytes == null || bytes < 0) return "—"
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    fun formatSizeWithEstimate(bytes: Long?, estimated: Boolean): String? {
        if (bytes == null || bytes <= 0) return null
        val formatted = formatBytes(bytes)
        return if (estimated) "Est. $formatted" else formatted
    }

    fun formatEta(etaSeconds: Long?): String? {
        if (etaSeconds == null || etaSeconds < 0) return null
        val hours = etaSeconds / 3600
        val minutes = (etaSeconds % 3600) / 60
        val seconds = etaSeconds % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    fun formatBitrate(kbps: Double?): String? {
        if (kbps == null || kbps <= 0.0) return null
        return if (kbps >= 1000.0) {
            String.format(Locale.US, "%.1f Mbps", kbps / 1000.0)
        } else {
            "${kbps.roundToInt()} kbps"
        }
    }

    fun formatFps(fps: Double?): String? {
        if (fps == null || fps <= 0.0) return null
        return "${fps.roundToInt()} fps"
    }

    fun mimeTypeForExtension(extension: String): String {
        return when (extension.lowercase().trimStart('.')) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "opus" -> "audio/opus"
            "ogg", "oga" -> "audio/ogg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
    }

    fun redactTechnicalDiagnostics(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw
            .replace(authHeaderRegex) { "${it.groupValues[1]}=[REDACTED]" }
            .replace(localPathRegex, "[LOCAL_PATH]")
            .trim()
            .take(1500)
    }
}
