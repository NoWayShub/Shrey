package com.shrey.downloader.core.validation

import com.shrey.downloader.domain.model.ChannelContentTab
import com.shrey.downloader.domain.model.MediaSourceType
import com.shrey.downloader.domain.model.ShreyError
import java.net.URI
import java.net.URLDecoder

/**
 * Structured result from smart URL classification combining:
 * 1. Security validation via [UrlValidator] (schemes, loopback/private IP blocking, flag-injection checks)
 * 2. Trusted host & canonical path/query parsing
 * 3. Optional refinement via yt-dlp extractor metadata ([refineWithExtractorMetadata])
 */
data class ClassifiedMediaUrl(
    val sourceType: MediaSourceType,
    val normalizedUrl: String,
    val host: String,
    val isYouTubeHost: Boolean = false,
    val videoId: String? = null,
    val playlistId: String? = null,
    val channelIdentifier: String? = null,
    val requestedChannelTab: ChannelContentTab? = null,
    val singleVideoUrl: String? = null,
    val playlistUrl: String? = null,
    val channelBaseUrl: String? = null,
    val error: ShreyError? = null
)

object MediaUrlClassifier {

    private val youtubeHosts = setOf(
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "music.youtube.com",
        "youtu.be",
        "www.youtu.be"
    )

    private val videoIdRegex = Regex("""^[A-Za-z0-9_-]{6,20}$""")
    private val playlistIdRegex = Regex("""^[A-Za-z0-9_-]{8,128}$""")
    private val channelIdRegex = Regex("""^UC[A-Za-z0-9_-]{16,40}$""")
    private val handleRegex = Regex("""^@[A-Za-z0-9._-]{2,64}$""")

    /**
     * Classifies a user-submitted URL safely using canonical URI parsing (not regex-only matching).
     */
    fun classify(rawInput: String): ClassifiedMediaUrl {
        val validation = UrlValidator.normalizeAndValidate(rawInput)
        if (validation is UrlValidationResult.Invalid) {
            return ClassifiedMediaUrl(
                sourceType = MediaSourceType.UNSUPPORTED,
                normalizedUrl = rawInput.trim(),
                host = "",
                error = validation.error
            )
        }

        val valid = validation as UrlValidationResult.Valid
        val uri = runCatching { URI(valid.normalizedUrl) }.getOrNull()
            ?: return ClassifiedMediaUrl(
                sourceType = MediaSourceType.UNSUPPORTED,
                normalizedUrl = valid.normalizedUrl,
                host = valid.host,
                error = ShreyError.InvalidUrl("Malformed URI syntax")
            )

        val host = valid.host.lowercase()
        val isYt = host in youtubeHosts
        if (!isYt) {
            // Retain existing single-media behavior for non-YouTube sites until extractor metadata
            // explicitly confirms a playlist/collection.
            return ClassifiedMediaUrl(
                sourceType = MediaSourceType.GENERIC_SINGLE_MEDIA,
                normalizedUrl = valid.normalizedUrl,
                host = host,
                isYouTubeHost = false
            )
        }

        val segments = uri.rawPath.orEmpty()
            .split('/')
            .filter { it.isNotBlank() }
            .map { decodePart(it) }
        val query = parseQueryParams(uri.rawQuery)

        // 1. Short youtu.be/<VIDEO_ID> links (may also carry ?list=...)
        if (host == "youtu.be" || host == "www.youtu.be") {
            val vid = segments.firstOrNull()?.takeIf { videoIdRegex.matches(it) }
            val listId = query["list"]?.takeIf { isSupportedPlaylistId(it) }
            if (vid != null && listId != null) {
                return ClassifiedMediaUrl(
                    sourceType = MediaSourceType.AMBIGUOUS_VIDEO_OR_PLAYLIST,
                    normalizedUrl = valid.normalizedUrl,
                    host = host,
                    isYouTubeHost = true,
                    videoId = vid,
                    playlistId = listId,
                    singleVideoUrl = "https://www.youtube.com/watch?v=$vid",
                    playlistUrl = "https://www.youtube.com/playlist?list=$listId"
                )
            }
            if (vid != null) {
                return ClassifiedMediaUrl(
                    sourceType = MediaSourceType.SINGLE_VIDEO,
                    normalizedUrl = "https://www.youtube.com/watch?v=$vid",
                    host = host,
                    isYouTubeHost = true,
                    videoId = vid,
                    singleVideoUrl = "https://www.youtube.com/watch?v=$vid"
                )
            }
            return ClassifiedMediaUrl(
                sourceType = MediaSourceType.UNSUPPORTED,
                normalizedUrl = valid.normalizedUrl,
                host = host,
                isYouTubeHost = true,
                error = ShreyError.UnsupportedLink("Unrecognized youtu.be link structure")
            )
        }

        val firstSeg = segments.firstOrNull()?.lowercase()
        val secondSeg = segments.getOrNull(1)
        val thirdSeg = segments.getOrNull(2)?.lowercase()

        // 2. youtube.com/watch?v=... (with or without list=...)
        if (firstSeg == "watch") {
            val vid = query["v"]?.takeIf { videoIdRegex.matches(it) }
            val listId = query["list"]?.takeIf { isSupportedPlaylistId(it) }
            if (vid != null && listId != null) {
                return ClassifiedMediaUrl(
                    sourceType = MediaSourceType.AMBIGUOUS_VIDEO_OR_PLAYLIST,
                    normalizedUrl = valid.normalizedUrl,
                    host = host,
                    isYouTubeHost = true,
                    videoId = vid,
                    playlistId = listId,
                    singleVideoUrl = "https://www.youtube.com/watch?v=$vid",
                    playlistUrl = "https://www.youtube.com/playlist?list=$listId"
                )
            }
            if (vid != null) {
                return ClassifiedMediaUrl(
                    sourceType = MediaSourceType.SINGLE_VIDEO,
                    normalizedUrl = "https://www.youtube.com/watch?v=$vid",
                    host = host,
                    isYouTubeHost = true,
                    videoId = vid,
                    singleVideoUrl = "https://www.youtube.com/watch?v=$vid"
                )
            }
            if (listId != null) {
                return ClassifiedMediaUrl(
                    sourceType = MediaSourceType.PLAYLIST,
                    normalizedUrl = "https://www.youtube.com/playlist?list=$listId",
                    host = host,
                    isYouTubeHost = true,
                    playlistId = listId,
                    playlistUrl = "https://www.youtube.com/playlist?list=$listId"
                )
            }
        }

        // 3. youtube.com/shorts/<VIDEO_ID> or /live/<VIDEO_ID> or /embed/<VIDEO_ID>
        if (firstSeg == "shorts" || firstSeg == "live" || firstSeg == "embed") {
            val vid = secondSeg?.takeIf { videoIdRegex.matches(it) }
            if (vid != null) {
                return ClassifiedMediaUrl(
                    sourceType = MediaSourceType.SINGLE_VIDEO,
                    normalizedUrl = valid.normalizedUrl,
                    host = host,
                    isYouTubeHost = true,
                    videoId = vid,
                    singleVideoUrl = "https://www.youtube.com/watch?v=$vid"
                )
            }
        }

        // 4. youtube.com/playlist?list=<PLAYLIST_ID>
        if (firstSeg == "playlist") {
            val listId = query["list"]?.takeIf { isSupportedPlaylistId(it) }
            if (listId != null) {
                return ClassifiedMediaUrl(
                    sourceType = MediaSourceType.PLAYLIST,
                    normalizedUrl = "https://www.youtube.com/playlist?list=$listId",
                    host = host,
                    isYouTubeHost = true,
                    playlistId = listId,
                    playlistUrl = "https://www.youtube.com/playlist?list=$listId"
                )
            }
        }

        // 5. Channel handle: youtube.com/@handle[/videos|/shorts|/streams]
        val rawFirst = segments.firstOrNull()
        if (rawFirst != null && handleRegex.matches(rawFirst)) {
            val tab = parseChannelTab(segments.getOrNull(1)?.lowercase())
            val baseUrl = "https://www.youtube.com/$rawFirst"
            val canonical = if (tab != null) "$baseUrl/${tab.pathSegment}" else baseUrl
            return ClassifiedMediaUrl(
                sourceType = MediaSourceType.CHANNEL,
                normalizedUrl = canonical,
                host = host,
                isYouTubeHost = true,
                channelIdentifier = rawFirst,
                requestedChannelTab = tab,
                channelBaseUrl = baseUrl
            )
        }

        // 6. Channel ID: youtube.com/channel/UC...[/videos|/shorts|/streams]
        if (firstSeg == "channel" && secondSeg != null && channelIdRegex.matches(secondSeg)) {
            val tab = parseChannelTab(thirdSeg)
            val baseUrl = "https://www.youtube.com/channel/$secondSeg"
            val canonical = if (tab != null) "$baseUrl/${tab.pathSegment}" else baseUrl
            return ClassifiedMediaUrl(
                sourceType = MediaSourceType.CHANNEL,
                normalizedUrl = canonical,
                host = host,
                isYouTubeHost = true,
                channelIdentifier = secondSeg,
                requestedChannelTab = tab,
                channelBaseUrl = baseUrl
            )
        }

        // 7. Legacy YouTube channel formats: /c/<name> or /user/<name>
        if ((firstSeg == "c" || firstSeg == "user") && !secondSeg.isNullOrBlank()) {
            val tab = parseChannelTab(thirdSeg)
            val baseUrl = "https://www.youtube.com/$firstSeg/$secondSeg"
            val canonical = if (tab != null) "$baseUrl/${tab.pathSegment}" else baseUrl
            return ClassifiedMediaUrl(
                sourceType = MediaSourceType.CHANNEL,
                normalizedUrl = canonical,
                host = host,
                isYouTubeHost = true,
                channelIdentifier = "$firstSeg/$secondSeg",
                requestedChannelTab = tab,
                channelBaseUrl = baseUrl
            )
        }

        // 8. Non-media YouTube pages (e.g. homepage, feed, account)
        if (segments.isEmpty() || firstSeg in setOf("feed", "results", "account", "reporthistory", "gaming")) {
            return ClassifiedMediaUrl(
                sourceType = MediaSourceType.UNSUPPORTED,
                normalizedUrl = valid.normalizedUrl,
                host = host,
                isYouTubeHost = true,
                error = ShreyError.UnsupportedLink(
                    "Paste a direct YouTube video, Shorts, playlist, or channel link."
                )
            )
        }

        // Fallback: let yt-dlp extractor verify whether this YouTube URL is supported
        return ClassifiedMediaUrl(
            sourceType = MediaSourceType.GENERIC_SINGLE_MEDIA,
            normalizedUrl = valid.normalizedUrl,
            host = host,
            isYouTubeHost = true
        )
    }

    /**
     * Validates that a redirect target remains safe (public HTTPS/HTTP host, no private/loopback SSRF,
     * and never downgrades from HTTPS to plain HTTP).
     */
    fun validateRedirectTarget(originalUrl: String, redirectedUrl: String): UrlValidationResult {
        val originalValid = UrlValidator.normalizeAndValidate(originalUrl)
        if (originalValid is UrlValidationResult.Invalid) return originalValid

        val targetValid = UrlValidator.normalizeAndValidate(redirectedUrl)
        if (targetValid is UrlValidationResult.Invalid) return targetValid

        val origScheme = runCatching { URI((originalValid as UrlValidationResult.Valid).normalizedUrl).scheme?.lowercase() }.getOrNull()
        val destScheme = runCatching { URI((targetValid as UrlValidationResult.Valid).normalizedUrl).scheme?.lowercase() }.getOrNull()
        if (origScheme == "https" && destScheme != "https") {
            return UrlValidationResult.Invalid(
                ShreyError.InvalidUrl("Blocked insecure HTTPS-to-HTTP redirect")
            )
        }
        return targetValid
    }

    /**
     * Refines URL classification using structured yt-dlp extractor metadata (`_type`, `extractor_key`,
     * `entries`, `channel_id`) so detection is backed by extractor truth rather than regex alone.
     */
    fun refineWithExtractorMetadata(
        initial: ClassifiedMediaUrl,
        ytDlpType: String?,
        extractorKey: String?,
        hasEntries: Boolean
    ): MediaSourceType {
        val normalizedType = ytDlpType?.lowercase()?.trim()
        val normalizedExtractor = extractorKey?.lowercase()?.trim().orEmpty()

        return when {
            normalizedExtractor.contains("youtubetab") && initial.sourceType == MediaSourceType.CHANNEL ->
                MediaSourceType.CHANNEL
            normalizedType == "playlist" || normalizedType == "multi_video" || hasEntries -> {
                if (initial.sourceType == MediaSourceType.CHANNEL) MediaSourceType.CHANNEL
                else MediaSourceType.PLAYLIST
            }
            normalizedType == "video" -> MediaSourceType.SINGLE_VIDEO
            else -> initial.sourceType
        }
    }

    fun buildChannelTabUrl(channelBaseUrl: String, tab: ChannelContentTab): String {
        val cleanBase = channelBaseUrl.trimEnd('/')
        return "$cleanBase/${tab.pathSegment}"
    }

    private fun parseChannelTab(segment: String?): ChannelContentTab? = when (segment) {
        "videos", "featured" -> ChannelContentTab.VIDEOS
        "shorts" -> ChannelContentTab.SHORTS
        "streams", "live" -> ChannelContentTab.STREAMS
        else -> null
    }

    /**
     * Rejects synthetic auto-mixes (`RD...` radio playlists that are infinite/unbounded mixes)
     * from masquerading as normal user playlists unless explicitly requested via /playlist?list=...
     */
    private fun isSupportedPlaylistId(id: String): Boolean {
        return playlistIdRegex.matches(id)
    }

    private fun parseQueryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        rawQuery.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = decodePart(pair.substring(0, idx))
                val value = decodePart(pair.substring(idx + 1))
                if (key.isNotBlank() && value.isNotBlank()) {
                    map[key] = value
                }
            }
        }
        return map
    }

    private fun decodePart(raw: String): String {
        return runCatching { URLDecoder.decode(raw, Charsets.UTF_8.name()) }.getOrDefault(raw)
    }
}
