package com.shrey.downloader.core.engine

import com.shrey.downloader.core.validation.ClassifiedMediaUrl
import com.shrey.downloader.core.validation.MediaUrlClassifier
import com.shrey.downloader.domain.model.ChannelContentTab
import com.shrey.downloader.domain.model.CollectionEntry
import com.shrey.downloader.domain.model.CollectionPreview
import com.shrey.downloader.domain.model.EntryAvailability
import com.shrey.downloader.domain.model.MediaSourceType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure, deterministic parser for `yt-dlp --flat-playlist --dump-single-json` output covering
 * YouTube playlists, YouTube channel tabs (`/videos`, `/shorts`, `/streams`), and verified
 * third-party extractor collections.
 */
object CollectionJsonParser {

    fun parseCollectionPage(
        rawJson: String,
        classified: ClassifiedMediaUrl,
        startIndex: Int,
        pageSize: Int,
        tab: ChannelContentTab? = classified.requestedChannelTab,
        warnings: List<String> = emptyList()
    ): CollectionPreview {
        val trimmed = rawJson.trim()
        val jsonStart = trimmed.indexOf('{')
        require(jsonStart >= 0) { "Collection extractor returned empty or non-JSON metadata" }
        val root = JSONObject(trimmed.substring(jsonStart))

        val ytType = root.optString("_type").takeIf { it.isNotBlank() }
        val extractorKey = root.optString("extractor_key").takeIf { it.isNotBlank() }
            ?: root.optString("extractor").takeIf { it.isNotBlank() }

        val rawEntries = root.optJSONArray("entries") ?: JSONArray()
        val refinedType = MediaUrlClassifier.refineWithExtractorMetadata(
            initial = classified,
            ytDlpType = ytType,
            extractorKey = extractorKey,
            hasEntries = rawEntries.length() > 0
        )

        val canonicalId = root.optString("id").takeIf { it.isNotBlank() }
            ?: classified.playlistId
            ?: classified.channelIdentifier
            ?: classified.normalizedUrl.hashCode().toString()

        val title = root.optString("title").takeIf { it.isNotBlank() }
            ?: root.optString("channel").takeIf { it.isNotBlank() }
            ?: root.optString("uploader").takeIf { it.isNotBlank() }
            ?: if (refinedType == MediaSourceType.CHANNEL) "YouTube Channel" else "Playlist"

        val creator = root.optString("channel").takeIf { it.isNotBlank() }
            ?: root.optString("uploader").takeIf { it.isNotBlank() }
            ?: root.optString("playlist_uploader").takeIf { it.isNotBlank() }

        val coverThumb = extractBestThumbnail(root)

        val declaredCount = root.optInt("playlist_count", -1).takeIf { it > 0 }
            ?: root.optInt("n_entries", -1).takeIf { it > 0 }

        val seenIds = LinkedHashSet<String>()
        val entries = mutableListOf<CollectionEntry>()

        for (i in 0 until rawEntries.length()) {
            val obj = rawEntries.optJSONObject(i) ?: continue
            // If yt-dlp returned nested tab playlists (e.g. root channel page containing Videos/Shorts/Streams tabs),
            // flatten only concrete video entries here; tab discovery is handled separately.
            val entryType = obj.optString("_type").lowercase()
            if (entryType == "playlist") continue

            val parsedEntry = parseEntry(
                obj = obj,
                position = startIndex + i,
                defaultTab = tab,
                defaultCreator = creator
            ) ?: continue

            if (seenIds.add(parsedEntry.mediaId)) {
                entries.add(parsedEntry)
            }
        }

        val effectiveCover = coverThumb ?: entries.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl

        val hasMore = when {
            declaredCount != null -> (startIndex + rawEntries.length() - 1) < declaredCount
            rawEntries.length() >= pageSize -> true
            else -> false
        }

        val discoveredTabs = detectNestedChannelTabs(root)

        return CollectionPreview(
            sourceType = refinedType,
            sourceUrl = root.optString("webpage_url").takeIf { it.isNotBlank() } ?: classified.normalizedUrl,
            canonicalId = canonicalId,
            title = title,
            creator = creator,
            thumbnailUrl = effectiveCover,
            declaredTotalCount = declaredCount,
            entries = entries,
            hasMoreEntries = hasMore,
            nextStartIndex = startIndex + rawEntries.length().coerceAtLeast(1),
            availableTabs = discoveredTabs,
            extractorKey = extractorKey,
            warnings = warnings
        )
    }

    private fun parseEntry(
        obj: JSONObject,
        position: Int,
        defaultTab: ChannelContentTab?,
        defaultCreator: String?
    ): CollectionEntry? {
        val rawId = obj.optString("id").takeIf { it.isNotBlank() }
        val rawUrl = obj.optString("url").takeIf { it.isNotBlank() }
            ?: obj.optString("webpage_url").takeIf { it.isNotBlank() }
            ?: rawId?.let { "https://www.youtube.com/watch?v=$it" }
            ?: return null

        val resolvedUrl = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            rawUrl
        } else if (rawId != null) {
            "https://www.youtube.com/watch?v=$rawId"
        } else {
            return null
        }

        val mediaId = rawId ?: resolvedUrl.hashCode().toString()
        val rawTitle = obj.optString("title").takeIf { it.isNotBlank() } ?: "Untitled media #$position"
        val duration = obj.optDouble("duration", -1.0).takeIf { !it.isNaN() && it > 0.0 }?.toLong()
        val thumb = extractBestThumbnail(obj)
        val uploader = obj.optString("channel").takeIf { it.isNotBlank() }
            ?: obj.optString("uploader").takeIf { it.isNotBlank() }
            ?: defaultCreator

        val availability = classifyAvailability(obj, rawTitle)
        val estSize = obj.optLong("filesize_approx", -1L).takeIf { it > 0L }
            ?: obj.optLong("filesize", -1L).takeIf { it > 0L }

        return CollectionEntry(
            mediaId = mediaId,
            position = position,
            title = rawTitle,
            sourceUrl = resolvedUrl,
            durationSeconds = duration,
            thumbnailUrl = thumb,
            availability = availability,
            tab = defaultTab,
            uploader = uploader,
            estimatedSizeBytes = estSize
        )
    }

    private fun classifyAvailability(obj: JSONObject, title: String): EntryAvailability {
        val liveStatus = obj.optString("live_status").lowercase()
        if (liveStatus == "is_live" || liveStatus == "is_upcoming" || obj.optBoolean("is_live", false)) {
            return EntryAvailability.LIVE_ACTIVE
        }

        val avail = obj.optString("availability").lowercase()
        val lowerTitle = title.lowercase().trim()
        return when {
            lowerTitle == "[private video]" ||
                avail.contains("private") ||
                avail.contains("subscriber") ||
                avail.contains("needs_auth") ||
                avail.contains("premium") -> EntryAvailability.PRIVATE_OR_MEMBERS
            lowerTitle == "[deleted video]" ||
                lowerTitle == "[unavailable video]" ||
                avail.contains("unavailable") ||
                avail.contains("removed") -> EntryAvailability.UNAVAILABLE
            else -> EntryAvailability.AVAILABLE
        }
    }

    private fun detectNestedChannelTabs(root: JSONObject): List<ChannelContentTab> {
        val entries = root.optJSONArray("entries") ?: return emptyList()
        val found = linkedSetOf<ChannelContentTab>()
        for (i in 0 until entries.length()) {
            val obj = entries.optJSONObject(i) ?: continue
            if (obj.optString("_type").lowercase() != "playlist") continue
            val url = (obj.optString("url").ifBlank { obj.optString("webpage_url") }).lowercase()
            val title = obj.optString("title").lowercase()
            when {
                url.endsWith("/shorts") || title.contains("shorts") -> found.add(ChannelContentTab.SHORTS)
                url.endsWith("/streams") || url.endsWith("/live") || title.contains("live") || title.contains("streams") ->
                    found.add(ChannelContentTab.STREAMS)
                url.endsWith("/videos") || title.contains("videos") -> found.add(ChannelContentTab.VIDEOS)
            }
        }
        return found.toList()
    }

    private fun extractBestThumbnail(obj: JSONObject): String? {
        val direct = obj.optString("thumbnail").takeIf { it.startsWith("http") }
        val thumbs = obj.optJSONArray("thumbnails")
        if (thumbs != null && thumbs.length() > 0) {
            var bestUrl: String? = null
            var bestArea = -1
            for (i in 0 until thumbs.length()) {
                val t = thumbs.optJSONObject(i) ?: continue
                val url = t.optString("url").takeIf { it.startsWith("http") } ?: continue
                val area = t.optInt("width", 0) * t.optInt("height", 0)
                if (area >= bestArea) {
                    bestArea = area
                    bestUrl = url
                }
            }
            if (bestUrl != null) return bestUrl
        }
        return direct
    }
}
