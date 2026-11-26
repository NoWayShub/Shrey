package com.shrey.downloader.core.engine

import com.shrey.downloader.core.validation.ClassifiedMediaUrl
import com.shrey.downloader.core.validation.MediaUrlClassifier
import com.shrey.downloader.core.validation.UrlValidationResult
import com.shrey.downloader.core.validation.UrlValidator
import com.shrey.downloader.domain.model.ChannelContentTab
import com.shrey.downloader.domain.model.CollectionEntry
import com.shrey.downloader.domain.model.CollectionPreview
import com.shrey.downloader.domain.model.MediaSourceType
import com.shrey.downloader.domain.model.ShreyError
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

interface CollectionExtractor {
    suspend fun extractPlaylistPage(
        url: String,
        startIndex: Int = 1,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): Result<CollectionPreview>

    suspend fun extractChannelPreview(
        classified: ClassifiedMediaUrl,
        selectedTabs: Set<ChannelContentTab>,
        perTabStartIndex: Int = 1,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): Result<CollectionPreview>

    suspend fun extractChannelTabPage(
        classified: ClassifiedMediaUrl,
        tab: ChannelContentTab,
        startIndex: Int = 1,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): Result<CollectionPreview> = extractChannelPreview(
        classified = classified,
        selectedTabs = setOf(tab),
        perTabStartIndex = startIndex,
        pageSize = pageSize
    )

    suspend fun cancelExtraction(requestId: String = "")

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
    }
}

/**
 * Incremental, paginated playlist and YouTube channel metadata extractor backed by local `yt-dlp`
 * (`--flat-playlist --dump-single-json --playlist-start N --playlist-end M`).
 *
 * Never fetches thousands of full video metadata records at once.
 */
class YtDlpCollectionExtractor(
    private val engineCoordinator: EngineCoordinator
) : CollectionExtractor {

    private val activeExtractionIds = ConcurrentHashMap.newKeySet<String>()

    override suspend fun extractPlaylistPage(
        url: String,
        startIndex: Int,
        pageSize: Int
    ): Result<CollectionPreview> {
        val safeStart = startIndex.coerceAtLeast(1)
        val safePageSize = pageSize.coerceIn(1, MAX_PAGE_SIZE)
        val classified = MediaUrlClassifier.classify(url)
        if (classified.error != null) {
            return Result.failure(classified.error)
        }
        val targetUrl = classified.playlistUrl ?: classified.normalizedUrl
        return executeFlatPlaylistQuery(
            targetUrl = targetUrl,
            classified = classified.copy(sourceType = MediaSourceType.PLAYLIST),
            startIndex = safeStart,
            pageSize = safePageSize,
            tab = null
        )
    }

    override suspend fun extractChannelTabPage(
        classified: ClassifiedMediaUrl,
        tab: ChannelContentTab,
        startIndex: Int,
        pageSize: Int
    ): Result<CollectionPreview> {
        val baseUrl = classified.channelBaseUrl ?: classified.normalizedUrl
        val safeStart = startIndex.coerceAtLeast(1)
        val safePageSize = pageSize.coerceIn(1, MAX_PAGE_SIZE)
        val tabUrl = MediaUrlClassifier.buildChannelTabUrl(baseUrl, tab)
        return executeFlatPlaylistQuery(
            targetUrl = tabUrl,
            classified = classified.copy(
                sourceType = MediaSourceType.CHANNEL,
                requestedChannelTab = tab
            ),
            startIndex = safeStart,
            pageSize = safePageSize,
            tab = tab
        )
    }

    override suspend fun extractChannelPreview(
        classified: ClassifiedMediaUrl,
        selectedTabs: Set<ChannelContentTab>,
        perTabStartIndex: Int,
        pageSize: Int
    ): Result<CollectionPreview> {
        val baseUrl = classified.channelBaseUrl ?: classified.normalizedUrl
        val safeStart = perTabStartIndex.coerceAtLeast(1)
        val safePageSize = pageSize.coerceIn(1, MAX_PAGE_SIZE)
        val tabsToFetch = selectedTabs.ifEmpty { setOf(ChannelContentTab.VIDEOS) }

        val initResult = engineCoordinator.ensureInitialized()
        if (initResult.isFailure) {
            val err = initResult.exceptionOrNull() as? ShreyError
                ?: ShreyError.EngineInitializationFailed(initResult.exceptionOrNull()?.message)
            return Result.failure(err)
        }

        val mergedEntries = mutableListOf<CollectionEntry>()
        val seenIds = LinkedHashSet<String>()
        val availableTabs = mutableListOf<ChannelContentTab>()
        val unavailableNotes = LinkedHashMap<ChannelContentTab, String>()
        val allWarnings = mutableListOf<String>()

        var channelTitle: String? = null
        var channelCreator: String? = null
        var channelAvatar: String? = null
        var canonicalId: String? = null
        var anyHasMore = false
        var totalDeclared: Int? = 0
        var firstFatalError: Throwable? = null

        for (tab in tabsToFetch) {
            val tabUrl = MediaUrlClassifier.buildChannelTabUrl(baseUrl, tab)
            val tabResult = executeFlatPlaylistQuery(
                targetUrl = tabUrl,
                classified = classified.copy(
                    sourceType = MediaSourceType.CHANNEL,
                    requestedChannelTab = tab
                ),
                startIndex = safeStart,
                pageSize = safePageSize,
                tab = tab
            )

            tabResult.fold(
                onSuccess = { page ->
                    if (channelTitle == null) channelTitle = cleanChannelTitle(page.title)
                    if (channelCreator == null) channelCreator = page.creator
                    if (channelAvatar == null) channelAvatar = page.thumbnailUrl
                    if (canonicalId == null) canonicalId = page.canonicalId
                    allWarnings.addAll(page.warnings)

                    if (page.entries.isNotEmpty()) {
                        availableTabs.add(tab)
                        for (entry in page.entries) {
                            if (seenIds.add(entry.mediaId)) {
                                mergedEntries.add(entry.copy(position = mergedEntries.size + 1, tab = tab))
                            }
                        }
                        if (page.hasMoreEntries) anyHasMore = true
                        if (totalDeclared != null && page.declaredTotalCount != null) {
                            totalDeclared = totalDeclared + page.declaredTotalCount
                        } else {
                            totalDeclared = null
                        }
                    } else {
                        unavailableNotes[tab] = "No public ${tab.label.lowercase()} found on this channel."
                    }
                },
                onFailure = { err ->
                    val shreyErr = err as? ShreyError ?: ErrorTranslator.translate(err)
                    if (shreyErr is ShreyError.RateLimited || shreyErr is ShreyError.NetworkUnavailable) {
                        return Result.failure(shreyErr)
                    }
                    if (firstFatalError == null) firstFatalError = shreyErr
                    unavailableNotes[tab] = when (shreyErr) {
                        is ShreyError.ContentUnavailable -> "${tab.label} tab is not available on this channel."
                        else -> "${tab.label}: ${shreyErr.userMessage}"
                    }
                }
            )
        }

        if (availableTabs.isEmpty() && mergedEntries.isEmpty()) {
            val err = firstFatalError as? ShreyError
                ?: ShreyError.ContentUnavailable("No downloadable videos, Shorts, or completed streams were found on this channel.")
            return Result.failure(err)
        }

        return Result.success(
            CollectionPreview(
                sourceType = MediaSourceType.CHANNEL,
                sourceUrl = baseUrl,
                canonicalId = canonicalId ?: classified.channelIdentifier ?: baseUrl.hashCode().toString(),
                title = channelTitle ?: classified.channelIdentifier ?: "YouTube Channel",
                creator = channelCreator ?: channelTitle,
                thumbnailUrl = channelAvatar,
                declaredTotalCount = totalDeclared?.takeIf { it > 0 },
                entries = mergedEntries,
                hasMoreEntries = anyHasMore,
                nextStartIndex = safeStart + safePageSize,
                availableTabs = availableTabs,
                unavailableTabNotes = unavailableNotes,
                extractorKey = "YoutubeTab",
                warnings = allWarnings.distinct()
            )
        )
    }

    private suspend fun executeFlatPlaylistQuery(
        targetUrl: String,
        classified: ClassifiedMediaUrl,
        startIndex: Int,
        pageSize: Int,
        tab: ChannelContentTab?
    ): Result<CollectionPreview> {
        val validated = when (val check = UrlValidator.normalizeAndValidate(targetUrl)) {
            is UrlValidationResult.Invalid -> return Result.failure(check.error)
            is UrlValidationResult.Valid -> check
        }

        val initResult = engineCoordinator.ensureInitialized()
        if (initResult.isFailure) {
            val err = initResult.exceptionOrNull() as? ShreyError
                ?: ShreyError.EngineInitializationFailed(initResult.exceptionOrNull()?.message)
            return Result.failure(err)
        }

        val processId = "collect_${UUID.randomUUID()}"
        activeExtractionIds.add(processId)
        val endIndex = startIndex + pageSize - 1

        return try {
            withTimeout(COLLECTION_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    val request = YoutubeDLRequest(validated.normalizedUrl).apply {
                        addOption("--flat-playlist")
                        addOption("--dump-single-json")
                        addOption("--playlist-start", startIndex.toString())
                        addOption("--playlist-end", endIndex.toString())
                        addOption("--skip-download")
                        addOption("--no-exec")
                        addOption("--ignore-errors")
                        addOption("--socket-timeout", "25")
                    }

                    val response = runInterruptible {
                        YoutubeDL.getInstance().execute(request, processId, null)
                    }

                    val stdout = response.out.orEmpty()
                    val stderr = response.err.orEmpty()
                    if (stdout.isBlank()) {
                        return@withContext Result.failure(
                            ErrorTranslator.translateMessage(stderr, IllegalStateException("Empty collection response"))
                        )
                    }

                    val preview = CollectionJsonParser.parseCollectionPage(
                        rawJson = stdout,
                        classified = classified,
                        startIndex = startIndex,
                        pageSize = pageSize,
                        tab = tab,
                        warnings = extractWarnings(stderr)
                    )
                    Result.success(preview)
                }
            }
        } catch (ce: CancellationException) {
            runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
            throw ce
        } catch (t: Throwable) {
            runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
            Result.failure(ErrorTranslator.translate(t))
        } finally {
            activeExtractionIds.remove(processId)
        }
    }

    override suspend fun cancelExtraction(requestId: String) {
        withContext(Dispatchers.IO) {
            if (requestId.isBlank()) {
                activeExtractionIds.forEach { id ->
                    runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
                }
                activeExtractionIds.clear()
            } else {
                runCatching { YoutubeDL.getInstance().destroyProcessById(requestId) }
                activeExtractionIds.remove(requestId)
            }
        }
    }

    private fun cleanChannelTitle(raw: String): String {
        return raw
            .removeSuffix(" - Videos")
            .removeSuffix(" - Shorts")
            .removeSuffix(" - Live")
            .removeSuffix(" - Streams")
            .trim()
            .ifBlank { raw }
    }

    private fun extractWarnings(stderr: String): List<String> {
        if (stderr.isBlank()) return emptyList()
        val list = mutableListOf<String>()
        val lower = stderr.lowercase()
        if (lower.contains("private video") || lower.contains("unavailable")) {
            list += "Some private or deleted entries were skipped."
        }
        if (lower.contains("429") || lower.contains("too many requests")) {
            list += "The host applied rate limiting during discovery."
        }
        return list
    }

    companion object {
        private const val MAX_PAGE_SIZE = 100
        private const val COLLECTION_TIMEOUT_MS = 60_000L
    }
}
