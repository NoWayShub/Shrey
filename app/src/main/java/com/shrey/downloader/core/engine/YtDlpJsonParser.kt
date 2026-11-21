package com.shrey.downloader.core.engine

import com.shrey.downloader.domain.model.MediaFormat
import com.shrey.downloader.domain.model.MediaInfo
import com.shrey.downloader.domain.model.ThumbnailCandidate
import org.json.JSONArray
import org.json.JSONObject

object YtDlpJsonParser {

    fun parseMediaInfo(
        rawJson: String,
        originalUrl: String,
        warnings: List<String> = emptyList(),
        nowMillis: Long = System.currentTimeMillis()
    ): MediaInfo {
        val json = extractJsonObject(rawJson)

        val id = json.optString("id").takeIf { it.isNotBlank() }
            ?: originalUrl.hashCode().toUInt().toString(16)
        val canonicalUrl = json.optString("webpage_url").takeIf { it.isNotBlank() }
            ?: json.optString("original_url").takeIf { it.isNotBlank() }
        val title = json.optString("title").takeIf { it.isNotBlank() }
            ?: json.optString("fulltitle").takeIf { it.isNotBlank() }
            ?: "Untitled Media"
        val creator = json.optString("channel").takeIf { it.isNotBlank() }
            ?: json.optString("uploader").takeIf { it.isNotBlank() }
            ?: json.optString("creator").takeIf { it.isNotBlank() }
            ?: json.optString("artist").takeIf { it.isNotBlank() }
        val durationSeconds = when {
            json.has("duration") && !json.isNull("duration") -> {
                val d = json.optDouble("duration", -1.0)
                if (d >= 0.0 && !d.isNaN()) d.toLong() else null
            }
            else -> null
        }

        val platform = json.optString("extractor_key").takeIf { it.isNotBlank() }
            ?: json.optString("extractor").takeIf { it.isNotBlank() }
            ?: "Web Media"

        val liveStatus = json.optString("live_status").lowercase()
        val isLive = json.optBoolean("is_live", false) ||
            liveStatus == "is_live" ||
            liveStatus == "is_upcoming"

        val thumbnails = parseThumbnails(json.optJSONArray("thumbnails"))
        val directThumb = json.optString("thumbnail").takeIf { it.isNotBlank() }
        val bestThumb = directThumb ?: thumbnails.maxByOrNull { (it.width ?: 0) * (it.height ?: 0) }?.url

        val formats = parseFormats(json.optJSONArray("formats"))

        val parsedWarnings = warnings.toMutableList()
        if (formats.isEmpty() && json.has("url") && !json.isNull("url")) {
            val singleExt = json.optString("ext").ifBlank { "mp4" }
            val singleFormat = MediaFormat(
                formatId = json.optString("format_id").ifBlank { "best" },
                ext = singleExt,
                width = json.optIntOrNull("width"),
                height = json.optIntOrNull("height"),
                fps = json.optDoubleOrNull("fps"),
                videoCodec = cleanCodec(json.optString("vcodec")),
                audioCodec = cleanCodec(json.optString("acodec")),
                audioBitrateKbps = json.optDoubleOrNull("abr"),
                totalBitrateKbps = json.optDoubleOrNull("tbr"),
                filesizeBytes = json.optLongOrNull("filesize") ?: json.optLongOrNull("filesize_approx"),
                isFilesizeEstimated = json.optLongOrNull("filesize") == null && json.optLongOrNull("filesize_approx") != null,
                hasVideo = cleanCodec(json.optString("vcodec")) != null || singleExt in videoExtensions,
                hasAudio = cleanCodec(json.optString("acodec")) != null || singleExt in audioExtensions || singleExt in videoExtensions,
                requiresMerge = false,
                dynamicRange = json.optString("dynamic_range").takeIf { it.isNotBlank() },
                formatNote = json.optString("format_note").takeIf { it.isNotBlank() }
            )
            return MediaInfo(
                id = id,
                originalUrl = originalUrl,
                canonicalUrl = canonicalUrl,
                title = title,
                creator = creator,
                durationSeconds = durationSeconds,
                thumbnailUrl = bestThumb,
                thumbnails = thumbnails,
                formats = listOf(singleFormat),
                platform = platform,
                isLive = isLive,
                extractedAtMillis = nowMillis,
                warnings = parsedWarnings
            )
        }

        return MediaInfo(
            id = id,
            originalUrl = originalUrl,
            canonicalUrl = canonicalUrl,
            title = title,
            creator = creator,
            durationSeconds = durationSeconds,
            thumbnailUrl = bestThumb,
            thumbnails = thumbnails,
            formats = formats,
            platform = platform,
            isLive = isLive,
            extractedAtMillis = nowMillis,
            warnings = parsedWarnings
        )
    }

    private fun extractJsonObject(rawOutput: String): JSONObject {
        val trimmed = rawOutput.trim()
        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        if (firstBrace < 0 || lastBrace <= firstBrace) {
            throw IllegalArgumentException("No JSON object found in extractor output")
        }
        return JSONObject(trimmed.substring(firstBrace, lastBrace + 1))
    }

    private fun parseThumbnails(array: JSONArray?): List<ThumbnailCandidate> {
        if (array == null) return emptyList()
        val result = mutableListOf<ThumbnailCandidate>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val url = item.optString("url").takeIf { it.startsWith("http", ignoreCase = true) } ?: continue
            val width = item.optIntOrNull("width")
            val height = item.optIntOrNull("height")
            val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
                .takeIf { it in setOf("jpg", "jpeg", "png", "webp") }
            result += ThumbnailCandidate(url = url, width = width, height = height, extension = ext)
        }
        return result.distinctBy { it.url }
    }

    private fun parseFormats(array: JSONArray?): List<MediaFormat> {
        if (array == null) return emptyList()
        val result = mutableListOf<MediaFormat>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val formatId = obj.optString("format_id").takeIf { it.isNotBlank() } ?: continue
            val ext = obj.optString("ext").lowercase().ifBlank { "mp4" }

            // Skip non-media storyboard / image-sheet formats
            if (ext == "mhtml" || ext == "jpg" || ext == "jpeg" || ext == "png" || ext == "webp") continue
            val formatNote = obj.optString("format_note").takeIf { it.isNotBlank() }
            if (formatNote?.contains("storyboard", ignoreCase = true) == true) continue

            val vcodec = cleanCodec(obj.optString("vcodec"))
            val acodec = cleanCodec(obj.optString("acodec"))
            val width = obj.optIntOrNull("width")
            val height = obj.optIntOrNull("height")
            val fps = obj.optDoubleOrNull("fps")
            val abr = obj.optDoubleOrNull("abr")
            val tbr = obj.optDoubleOrNull("tbr")

            val exactSize = obj.optLongOrNull("filesize")
            val approxSize = obj.optLongOrNull("filesize_approx")
            val filesize = exactSize ?: approxSize
            val isEstimated = exactSize == null && approxSize != null

            val hasVideo = vcodec != null || (height != null && height > 0)
            val hasAudio = acodec != null || (abr != null && abr > 0.0) || (!hasVideo && ext in audioExtensions)

            if (!hasVideo && !hasAudio) continue

            val dynamicRange = obj.optString("dynamic_range").takeIf { it.isNotBlank() }

            result += MediaFormat(
                formatId = formatId,
                ext = ext,
                width = width,
                height = height,
                fps = fps,
                videoCodec = vcodec,
                audioCodec = acodec,
                audioBitrateKbps = abr,
                totalBitrateKbps = tbr,
                filesizeBytes = filesize,
                isFilesizeEstimated = isEstimated,
                hasVideo = hasVideo,
                hasAudio = hasAudio,
                requiresMerge = hasVideo && !hasAudio,
                dynamicRange = dynamicRange,
                formatNote = formatNote
            )
        }
        return result
    }

    private fun cleanCodec(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed.equals("none", ignoreCase = true) || trimmed.equals("null", ignoreCase = true)) {
            return null
        }
        return trimmed
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        val value = optInt(key, -1)
        return if (value > 0) value else null
    }

    private fun JSONObject.optLongOrNull(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        val value = optLong(key, -1L)
        return if (value > 0L) value else null
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val value = optDouble(key, Double.NaN)
        return if (!value.isNaN() && value > 0.0) value else null
    }

    private val videoExtensions = setOf("mp4", "mkv", "webm", "mov", "flv", "3gp")
    private val audioExtensions = setOf("m4a", "mp3", "opus", "ogg", "webm", "aac", "wav", "flac")
}
