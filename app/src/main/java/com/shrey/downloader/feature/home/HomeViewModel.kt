package com.shrey.downloader.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.shrey.downloader.core.engine.CollectionExtractor
import com.shrey.downloader.core.engine.EngineCoordinator
import com.shrey.downloader.core.engine.FormatSelectionMapper
import com.shrey.downloader.core.service.BatchDownloadCoordinator
import com.shrey.downloader.core.service.DownloadQueueManager
import com.shrey.downloader.core.storage.MediaStoragePublisher
import com.shrey.downloader.core.update.UpdateManager
import com.shrey.downloader.core.update.UpdateSnapshot
import com.shrey.downloader.core.validation.ClassifiedMediaUrl
import com.shrey.downloader.core.validation.MediaUrlClassifier
import com.shrey.downloader.core.validation.UrlValidator
import com.shrey.downloader.data.local.SettingsDataStore
import com.shrey.downloader.domain.model.AudioOutputFormat
import com.shrey.downloader.domain.model.BulkQualityPreset
import com.shrey.downloader.domain.model.ChannelContentTab
import com.shrey.downloader.domain.model.ChannelScopePreset
import com.shrey.downloader.domain.model.CollectionEntry
import com.shrey.downloader.domain.model.CollectionPreview
import com.shrey.downloader.domain.model.DownloadMode
import com.shrey.downloader.domain.model.DownloadRequest
import com.shrey.downloader.domain.model.EngineState
import com.shrey.downloader.domain.model.FormatOption
import com.shrey.downloader.domain.model.MediaInfo
import com.shrey.downloader.domain.model.MediaSourceType
import com.shrey.downloader.domain.model.ShreyError
import com.shrey.downloader.domain.model.ShreySettings
import com.shrey.downloader.domain.model.ThumbnailOutputFormat
import com.shrey.downloader.domain.repository.MediaExtractor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

sealed interface HomeUiState {
    data object Idle : HomeUiState

    data class Analyzing(
        val url: String,
        val detectedType: MediaSourceType = MediaSourceType.SINGLE_VIDEO
    ) : HomeUiState

    data class AmbiguousUrl(
        val classified: ClassifiedMediaUrl
    ) : HomeUiState

    data class Ready(
        val media: MediaInfo,
        val selectedMode: DownloadMode,
        val selectedAudioFormat: AudioOutputFormat,
        val selectedThumbnailFormat: ThumbnailOutputFormat,
        val options: List<FormatOption>,
        val selectedOption: FormatOption?,
        val showTechnicalDetails: Boolean = false,
        val lastQueuedDownloadId: String? = null
    ) : HomeUiState

    data class PlaylistReady(
        val preview: CollectionPreview,
        val selectedMediaIds: Set<String>,
        val qualityPreset: BulkQualityPreset,
        val audioOutputFormat: AudioOutputFormat,
        val wifiOnly: Boolean,
        val isLoadingMore: Boolean = false,
        val availableStorageBytes: Long = 0L,
        val lastQueuedBatchId: String? = null,
        val actionError: String? = null
    ) : HomeUiState {
        val selectedDownloadableEntries: List<CollectionEntry>
            get() = preview.entries.filter { it.availability.isDownloadable && it.mediaId in selectedMediaIds }
    }

    data class ChannelReady(
        val classified: ClassifiedMediaUrl,
        val preview: CollectionPreview,
        val selectedTabs: Set<ChannelContentTab>,
        val scopePreset: ChannelScopePreset,
        val customLimit: Int = 25,
        val qualityPreset: BulkQualityPreset,
        val audioOutputFormat: AudioOutputFormat,
        val wifiOnly: Boolean,
        val isLoadingMore: Boolean = false,
        val isReloadingTabs: Boolean = false,
        val availableStorageBytes: Long = 0L,
        val showConfirmDialog: Boolean = false,
        val lastQueuedBatchId: String? = null,
        val actionError: String? = null
    ) : HomeUiState {
        val effectiveLimit: Int?
            get() = when (scopePreset) {
                ChannelScopePreset.CUSTOM -> customLimit.coerceIn(1, 500)
                else -> scopePreset.defaultLimit
            }

        val usesAutoDiscovery: Boolean
            get() = scopePreset == ChannelScopePreset.ALL ||
                (effectiveLimit != null && effectiveLimit!! > preview.downloadableCount && preview.hasMoreEntries)

        val scopedEntries: List<CollectionEntry>
            get() {
                val downloadable = preview.entries.filter { it.availability.isDownloadable }
                val limit = effectiveLimit ?: return downloadable
                return downloadable.take(limit)
            }
    }

    data class Error(val error: ShreyError) : HomeUiState
}

class HomeViewModel(
    private val mediaExtractor: MediaExtractor,
    private val engineCoordinator: EngineCoordinator,
    private val downloadQueueManager: DownloadQueueManager,
    private val settingsDataStore: SettingsDataStore,
    private val updateManager: UpdateManager? = null,
    private val collectionExtractor: CollectionExtractor? = null,
    private val batchCoordinator: BatchDownloadCoordinator? = null,
    private val storagePublisher: MediaStoragePublisher? = null
) : ViewModel() {

    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Idle)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val engineState: StateFlow<EngineState> = engineCoordinator.state

    val updateSnapshot: StateFlow<UpdateSnapshot>? = updateManager?.snapshot

    val settings: StateFlow<ShreySettings> = settingsDataStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ShreySettings()
    )

    private var extractionJob: Job? = null
    private val extractionToken = AtomicLong(0L)

    fun onUrlChanged(newValue: String) {
        _urlInput.value = newValue
        if (newValue.isBlank() && (_uiState.value is HomeUiState.Error || _uiState.value is HomeUiState.AmbiguousUrl)) {
            _uiState.value = HomeUiState.Idle
        }
    }

    fun onClearUrl() {
        extractionJob?.cancel()
        extractionToken.incrementAndGet()
        _urlInput.value = ""
        _uiState.value = HomeUiState.Idle
    }

    fun onPasteClipboardText(clipboardText: String?) {
        if (clipboardText.isNullOrBlank()) return
        val extracted = UrlValidator.extractFirstUrlFromSharedText(clipboardText) ?: clipboardText.trim()
        _urlInput.value = extracted
    }

    fun onSharedTextReceived(sharedText: String?) {
        val extracted = UrlValidator.extractFirstUrlFromSharedText(sharedText) ?: return
        _urlInput.value = extracted
        analyzeCurrentUrl()
    }

    fun analyzeCurrentUrl() {
        val raw = _urlInput.value.trim()
        val currentState = _uiState.value
        if (currentState is HomeUiState.Analyzing && currentState.url == raw) {
            return
        }

        val classified = MediaUrlClassifier.classify(raw)
        if (classified.error != null || classified.sourceType == MediaSourceType.UNSUPPORTED) {
            _uiState.value = HomeUiState.Error(
                classified.error ?: ShreyError.UnsupportedLink()
            )
            return
        }

        _urlInput.value = classified.normalizedUrl

        when (classified.sourceType) {
            MediaSourceType.AMBIGUOUS_VIDEO_OR_PLAYLIST -> {
                extractionJob?.cancel()
                extractionToken.incrementAndGet()
                _uiState.value = HomeUiState.AmbiguousUrl(classified)
            }

            MediaSourceType.PLAYLIST -> {
                extractPlaylist(classified.playlistUrl ?: classified.normalizedUrl)
            }

            MediaSourceType.CHANNEL -> {
                val initialTabs = classified.requestedChannelTab?.let { setOf(it) }
                    ?: setOf(ChannelContentTab.VIDEOS)
                extractChannel(classified, initialTabs)
            }

            MediaSourceType.SINGLE_VIDEO,
            MediaSourceType.GENERIC_SINGLE_MEDIA,
            MediaSourceType.UNSUPPORTED -> {
                extractSingleVideo(classified.singleVideoUrl ?: classified.normalizedUrl)
            }
        }
    }

    fun resolveAmbiguousAsSingleVideo() {
        val current = _uiState.value as? HomeUiState.AmbiguousUrl ?: return
        val target = current.classified.singleVideoUrl ?: current.classified.normalizedUrl
        extractSingleVideo(target)
    }

    fun resolveAmbiguousAsPlaylist() {
        val current = _uiState.value as? HomeUiState.AmbiguousUrl ?: return
        val target = current.classified.playlistUrl ?: current.classified.normalizedUrl
        extractPlaylist(target)
    }

    private fun extractSingleVideo(targetUrl: String) {
        extractionJob?.cancel()
        val myToken = extractionToken.incrementAndGet()
        _uiState.value = HomeUiState.Analyzing(targetUrl, MediaSourceType.SINGLE_VIDEO)

        extractionJob = viewModelScope.launch {
            val result = mediaExtractor.extract(targetUrl)
            if (extractionToken.get() != myToken) return@launch

            result.fold(
                onSuccess = { media ->
                    val currentSettings = settingsDataStore.settingsFlow.first()
                    val initialMode = resolveInitialMode(media, currentSettings.defaultDownloadMode)
                    val options = FormatSelectionMapper.buildOptions(
                        media = media,
                        mode = initialMode,
                        audioOutputFormat = currentSettings.defaultAudioFormat,
                        thumbnailOutputFormat = ThumbnailOutputFormat.ORIGINAL
                    )
                    val defaultOption = FormatSelectionMapper.selectDefaultOption(
                        options = options,
                        preferredVideoQuality = currentSettings.defaultVideoQuality
                    )
                    _uiState.value = HomeUiState.Ready(
                        media = media,
                        selectedMode = initialMode,
                        selectedAudioFormat = currentSettings.defaultAudioFormat,
                        selectedThumbnailFormat = ThumbnailOutputFormat.ORIGINAL,
                        options = options,
                        selectedOption = defaultOption,
                        showTechnicalDetails = false,
                        lastQueuedDownloadId = null
                    )
                },
                onFailure = { throwable ->
                    val err = throwable as? ShreyError
                        ?: ShreyError.ExtractorFailure(details = throwable.message)
                    _uiState.value = HomeUiState.Error(err)
                }
            )
        }
    }

    private fun extractPlaylist(playlistUrl: String) {
        val extractor = collectionExtractor
        if (extractor == null) {
            _uiState.value = HomeUiState.Error(
                ShreyError.ExtractorFailure("Playlist extractor is unavailable.")
            )
            return
        }

        extractionJob?.cancel()
        val myToken = extractionToken.incrementAndGet()
        _uiState.value = HomeUiState.Analyzing(playlistUrl, MediaSourceType.PLAYLIST)

        extractionJob = viewModelScope.launch {
            val result = extractor.extractPlaylistPage(
                url = playlistUrl,
                startIndex = 1,
                pageSize = CollectionExtractor.DEFAULT_PAGE_SIZE
            )
            if (extractionToken.get() != myToken) return@launch

            result.fold(
                onSuccess = { preview ->
                    val currentSettings = settingsDataStore.settingsFlow.first()
                    val selectableIds = preview.entries
                        .filter { it.availability.isDownloadable }
                        .map { it.mediaId }
                        .toSet()
                    _uiState.value = HomeUiState.PlaylistReady(
                        preview = preview,
                        selectedMediaIds = selectableIds,
                        qualityPreset = BulkQualityPreset.fromDefaultVideoQuality(currentSettings.defaultVideoQuality),
                        audioOutputFormat = currentSettings.defaultAudioFormat,
                        wifiOnly = currentSettings.wifiOnlyDownloads,
                        availableStorageBytes = storagePublisher?.availableStorageBytes() ?: 0L
                    )
                },
                onFailure = { throwable ->
                    val err = throwable as? ShreyError
                        ?: ShreyError.ExtractorFailure(details = throwable.message)
                    _uiState.value = HomeUiState.Error(err)
                }
            )
        }
    }

    private fun extractChannel(
        classified: ClassifiedMediaUrl,
        tabs: Set<ChannelContentTab>,
        preserveScope: ChannelScopePreset = ChannelScopePreset.LATEST_10,
        preserveCustomLimit: Int = 25,
        preserveQuality: BulkQualityPreset? = null,
        preserveAudioFormat: AudioOutputFormat? = null,
        preserveWifiOnly: Boolean? = null
    ) {
        val extractor = collectionExtractor
        if (extractor == null) {
            _uiState.value = HomeUiState.Error(
                ShreyError.ExtractorFailure("Channel extractor is unavailable.")
            )
            return
        }

        extractionJob?.cancel()
        val myToken = extractionToken.incrementAndGet()
        val currentChannel = _uiState.value as? HomeUiState.ChannelReady
        if (currentChannel != null && currentChannel.classified.channelBaseUrl == classified.channelBaseUrl) {
            _uiState.value = currentChannel.copy(
                selectedTabs = tabs,
                isReloadingTabs = true,
                actionError = null,
                lastQueuedBatchId = null
            )
        } else {
            _uiState.value = HomeUiState.Analyzing(classified.normalizedUrl, MediaSourceType.CHANNEL)
        }

        extractionJob = viewModelScope.launch {
            val result = extractor.extractChannelPreview(
                classified = classified,
                selectedTabs = tabs,
                perTabStartIndex = 1,
                pageSize = CollectionExtractor.DEFAULT_PAGE_SIZE
            )
            if (extractionToken.get() != myToken) return@launch

            result.fold(
                onSuccess = { preview ->
                    val currentSettings = settingsDataStore.settingsFlow.first()
                    _uiState.value = HomeUiState.ChannelReady(
                        classified = classified,
                        preview = preview,
                        selectedTabs = tabs,
                        scopePreset = preserveScope,
                        customLimit = preserveCustomLimit,
                        qualityPreset = preserveQuality
                            ?: BulkQualityPreset.fromDefaultVideoQuality(currentSettings.defaultVideoQuality),
                        audioOutputFormat = preserveAudioFormat ?: currentSettings.defaultAudioFormat,
                        wifiOnly = preserveWifiOnly ?: currentSettings.wifiOnlyDownloads,
                        availableStorageBytes = storagePublisher?.availableStorageBytes() ?: 0L
                    )
                },
                onFailure = { throwable ->
                    val err = throwable as? ShreyError
                        ?: ShreyError.ExtractorFailure(details = throwable.message)
                    val latest = _uiState.value
                    if (latest is HomeUiState.ChannelReady && latest.isReloadingTabs) {
                        _uiState.value = latest.copy(
                            isReloadingTabs = false,
                            actionError = err.userMessage
                        )
                    } else {
                        _uiState.value = HomeUiState.Error(err)
                    }
                }
            )
        }
    }

    // ---- Single Video Controls (preserved from v0.0.3) ----

    fun onModeSelected(mode: DownloadMode) {
        val current = _uiState.value as? HomeUiState.Ready ?: return
        if (current.selectedMode == mode) return
        val options = FormatSelectionMapper.buildOptions(
            media = current.media,
            mode = mode,
            audioOutputFormat = current.selectedAudioFormat,
            thumbnailOutputFormat = current.selectedThumbnailFormat
        )
        val defaultOption = FormatSelectionMapper.selectDefaultOption(
            options = options,
            preferredVideoQuality = settings.value.defaultVideoQuality
        )
        _uiState.value = current.copy(
            selectedMode = mode,
            options = options,
            selectedOption = defaultOption,
            lastQueuedDownloadId = null
        )
    }

    fun onOptionSelected(option: FormatOption) {
        val current = _uiState.value as? HomeUiState.Ready ?: return
        _uiState.value = current.copy(
            selectedOption = option,
            lastQueuedDownloadId = null
        )
    }

    fun onAudioFormatSelected(audioFormat: AudioOutputFormat) {
        val current = _uiState.value as? HomeUiState.Ready ?: return
        if (current.selectedAudioFormat == audioFormat) return
        val options = FormatSelectionMapper.buildOptions(
            media = current.media,
            mode = current.selectedMode,
            audioOutputFormat = audioFormat,
            thumbnailOutputFormat = current.selectedThumbnailFormat
        )
        _uiState.value = current.copy(
            selectedAudioFormat = audioFormat,
            options = options,
            selectedOption = options.firstOrNull(),
            lastQueuedDownloadId = null
        )
    }

    fun onThumbnailFormatSelected(thumbnailFormat: ThumbnailOutputFormat) {
        val current = _uiState.value as? HomeUiState.Ready ?: return
        if (current.selectedThumbnailFormat == thumbnailFormat) return
        val options = FormatSelectionMapper.buildOptions(
            media = current.media,
            mode = current.selectedMode,
            audioOutputFormat = current.selectedAudioFormat,
            thumbnailOutputFormat = thumbnailFormat
        )
        _uiState.value = current.copy(
            selectedThumbnailFormat = thumbnailFormat,
            options = options,
            selectedOption = options.firstOrNull(),
            lastQueuedDownloadId = null
        )
    }

    fun onToggleTechnicalDetails() {
        val current = _uiState.value as? HomeUiState.Ready ?: return
        _uiState.value = current.copy(showTechnicalDetails = !current.showTechnicalDetails)
    }

    fun startSelectedDownload() {
        val current = _uiState.value as? HomeUiState.Ready ?: return
        val chosen = current.selectedOption ?: return

        viewModelScope.launch {
            val currentSettings = settingsDataStore.settingsFlow.first()
            val downloadId = UUID.randomUUID().toString()
            val request = DownloadRequest(
                downloadId = downloadId,
                sourceUrl = current.media.canonicalUrl ?: current.media.originalUrl,
                mediaTitle = current.media.title,
                creator = current.media.creator,
                thumbnailUrl = current.media.thumbnailUrl,
                mode = current.selectedMode,
                selectedFormatId = chosen.id,
                formatSelector = chosen.formatSelector,
                outputExtension = chosen.outputExtension,
                qualityLabel = "${current.selectedMode.label} · ${chosen.label}",
                audioOutputFormat = current.selectedAudioFormat,
                thumbnailOutputFormat = current.selectedThumbnailFormat,
                requiresMerge = chosen.requiresMerge,
                destinationTreeUri = currentSettings.customDestinationUri,
                mediaId = current.media.id
            )
            downloadQueueManager.enqueue(request)
            val latest = _uiState.value
            if (latest is HomeUiState.Ready && latest.media.id == current.media.id) {
                _uiState.value = latest.copy(lastQueuedDownloadId = downloadId)
            }
        }
    }

    // ---- Playlist Controls ----

    fun togglePlaylistEntry(mediaId: String) {
        val current = _uiState.value as? HomeUiState.PlaylistReady ?: return
        val entry = current.preview.entries.firstOrNull { it.mediaId == mediaId } ?: return
        if (!entry.availability.isDownloadable) return
        val updated = if (mediaId in current.selectedMediaIds) {
            current.selectedMediaIds - mediaId
        } else {
            current.selectedMediaIds + mediaId
        }
        _uiState.value = current.copy(selectedMediaIds = updated, actionError = null)
    }

    fun selectAllPlaylistEntries() {
        val current = _uiState.value as? HomeUiState.PlaylistReady ?: return
        val allIds = current.preview.entries
            .filter { it.availability.isDownloadable }
            .map { it.mediaId }
            .toSet()
        _uiState.value = current.copy(selectedMediaIds = allIds, actionError = null)
    }

    fun deselectAllPlaylistEntries() {
        val current = _uiState.value as? HomeUiState.PlaylistReady ?: return
        _uiState.value = current.copy(selectedMediaIds = emptySet(), actionError = null)
    }

    fun loadMorePlaylistEntries() {
        val current = _uiState.value as? HomeUiState.PlaylistReady ?: return
        val extractor = collectionExtractor ?: return
        if (current.isLoadingMore || !current.preview.hasMoreEntries) return

        val myToken = extractionToken.get()
        _uiState.value = current.copy(isLoadingMore = true, actionError = null)

        viewModelScope.launch {
            val pageResult = extractor.extractPlaylistPage(
                url = current.preview.sourceUrl,
                startIndex = current.preview.nextStartIndex,
                pageSize = CollectionExtractor.DEFAULT_PAGE_SIZE
            )
            if (extractionToken.get() != myToken) return@launch
            val latest = _uiState.value as? HomeUiState.PlaylistReady ?: return@launch

            pageResult.fold(
                onSuccess = { nextPage ->
                    val existingIds = latest.preview.entries.map { it.mediaId }.toSet()
                    val appended = nextPage.entries.filter { it.mediaId !in existingIds }
                    val mergedEntries = latest.preview.entries + appended
                    val newSelectable = appended
                        .filter { it.availability.isDownloadable }
                        .map { it.mediaId }
                    val mergedPreview = latest.preview.copy(
                        entries = mergedEntries,
                        declaredTotalCount = nextPage.declaredTotalCount ?: latest.preview.declaredTotalCount,
                        hasMoreEntries = nextPage.hasMoreEntries && appended.isNotEmpty(),
                        nextStartIndex = nextPage.nextStartIndex,
                        warnings = (latest.preview.warnings + nextPage.warnings).distinct()
                    )
                    _uiState.value = latest.copy(
                        preview = mergedPreview,
                        selectedMediaIds = latest.selectedMediaIds + newSelectable,
                        isLoadingMore = false
                    )
                },
                onFailure = { err ->
                    val shreyErr = err as? ShreyError
                    _uiState.value = latest.copy(
                        isLoadingMore = false,
                        actionError = shreyErr?.userMessage ?: err.message ?: "Could not load additional videos."
                    )
                }
            )
        }
    }

    fun startPlaylistBatch() {
        val current = _uiState.value as? HomeUiState.PlaylistReady ?: return
        val coordinator = batchCoordinator ?: return
        val chosen = current.selectedDownloadableEntries
        if (chosen.isEmpty()) {
            _uiState.value = current.copy(actionError = "Select at least one available video to download.")
            return
        }

        viewModelScope.launch {
            val result = coordinator.startBatch(
                preview = current.preview,
                selectedEntries = chosen,
                qualityPreset = current.qualityPreset,
                audioOutputFormat = current.audioOutputFormat,
                wifiOnly = current.wifiOnly
            )
            val latest = _uiState.value as? HomeUiState.PlaylistReady ?: return@launch
            result.fold(
                onSuccess = { batchId ->
                    _uiState.value = latest.copy(lastQueuedBatchId = batchId, actionError = null)
                },
                onFailure = { err ->
                    val msg = (err as? ShreyError)?.userMessage ?: err.message ?: "Failed to queue playlist."
                    _uiState.value = latest.copy(actionError = msg)
                }
            )
        }
    }

    // ---- Shared Bulk Quality / Audio / Wi-Fi Controls ----

    fun setBulkQuality(preset: BulkQualityPreset) {
        when (val current = _uiState.value) {
            is HomeUiState.PlaylistReady -> _uiState.value = current.copy(
                qualityPreset = preset,
                lastQueuedBatchId = null,
                actionError = null
            )
            is HomeUiState.ChannelReady -> _uiState.value = current.copy(
                qualityPreset = preset,
                lastQueuedBatchId = null,
                actionError = null
            )
            else -> Unit
        }
    }

    fun setBulkAudioFormat(format: AudioOutputFormat) {
        when (val current = _uiState.value) {
            is HomeUiState.PlaylistReady -> _uiState.value = current.copy(
                audioOutputFormat = format,
                lastQueuedBatchId = null
            )
            is HomeUiState.ChannelReady -> _uiState.value = current.copy(
                audioOutputFormat = format,
                lastQueuedBatchId = null
            )
            else -> Unit
        }
    }

    fun setBulkWifiOnly(enabled: Boolean) {
        when (val current = _uiState.value) {
            is HomeUiState.PlaylistReady -> _uiState.value = current.copy(wifiOnly = enabled)
            is HomeUiState.ChannelReady -> _uiState.value = current.copy(wifiOnly = enabled)
            else -> Unit
        }
    }

    // ---- YouTube Channel Controls ----

    fun toggleChannelTab(tab: ChannelContentTab) {
        val current = _uiState.value as? HomeUiState.ChannelReady ?: return
        val nextTabs = if (tab in current.selectedTabs) {
            if (current.selectedTabs.size <= 1) return // Keep at least one tab selected
            current.selectedTabs - tab
        } else {
            current.selectedTabs + tab
        }
        extractChannel(
            classified = current.classified,
            tabs = nextTabs,
            preserveScope = current.scopePreset,
            preserveCustomLimit = current.customLimit,
            preserveQuality = current.qualityPreset,
            preserveAudioFormat = current.audioOutputFormat,
            preserveWifiOnly = current.wifiOnly
        )
    }

    fun setChannelScope(preset: ChannelScopePreset) {
        val current = _uiState.value as? HomeUiState.ChannelReady ?: return
        _uiState.value = current.copy(
            scopePreset = preset,
            lastQueuedBatchId = null,
            actionError = null
        )
    }

    fun setChannelCustomLimit(limit: Int) {
        val current = _uiState.value as? HomeUiState.ChannelReady ?: return
        _uiState.value = current.copy(
            scopePreset = ChannelScopePreset.CUSTOM,
            customLimit = limit.coerceIn(1, 500),
            lastQueuedBatchId = null,
            actionError = null
        )
    }

    fun loadMoreChannelEntries() {
        val current = _uiState.value as? HomeUiState.ChannelReady ?: return
        val extractor = collectionExtractor ?: return
        if (current.isLoadingMore || !current.preview.hasMoreEntries) return

        val myToken = extractionToken.get()
        _uiState.value = current.copy(isLoadingMore = true, actionError = null)

        viewModelScope.launch {
            val nextResult = extractor.extractChannelPreview(
                classified = current.classified,
                selectedTabs = current.selectedTabs,
                perTabStartIndex = current.preview.nextStartIndex,
                pageSize = CollectionExtractor.DEFAULT_PAGE_SIZE
            )
            if (extractionToken.get() != myToken) return@launch
            val latest = _uiState.value as? HomeUiState.ChannelReady ?: return@launch

            nextResult.fold(
                onSuccess = { nextPage ->
                    val existingIds = latest.preview.entries.map { it.mediaId }.toSet()
                    val appended = nextPage.entries.filter { it.mediaId !in existingIds }
                    val mergedEntries = latest.preview.entries + appended
                    val mergedPreview = latest.preview.copy(
                        entries = mergedEntries,
                        hasMoreEntries = nextPage.hasMoreEntries && appended.isNotEmpty(),
                        nextStartIndex = nextPage.nextStartIndex,
                        warnings = (latest.preview.warnings + nextPage.warnings).distinct()
                    )
                    _uiState.value = latest.copy(
                        preview = mergedPreview,
                        isLoadingMore = false
                    )
                },
                onFailure = { err ->
                    val msg = (err as? ShreyError)?.userMessage ?: err.message ?: "Could not load additional channel items."
                    _uiState.value = latest.copy(
                        isLoadingMore = false,
                        actionError = msg
                    )
                }
            )
        }
    }

    fun requestChannelConfirmation() {
        val current = _uiState.value as? HomeUiState.ChannelReady ?: return
        if (current.scopedEntries.isEmpty()) {
            _uiState.value = current.copy(actionError = "No downloadable items found for the selected tabs.")
            return
        }
        _uiState.value = current.copy(
            showConfirmDialog = true,
            availableStorageBytes = storagePublisher?.availableStorageBytes() ?: current.availableStorageBytes,
            actionError = null
        )
    }

    fun dismissChannelConfirmation() {
        val current = _uiState.value as? HomeUiState.ChannelReady ?: return
        _uiState.value = current.copy(showConfirmDialog = false)
    }

    fun confirmAndStartChannelBatch() {
        val current = _uiState.value as? HomeUiState.ChannelReady ?: return
        val coordinator = batchCoordinator ?: return
        val chosen = current.scopedEntries
        if (chosen.isEmpty() && !current.usesAutoDiscovery) {
            _uiState.value = current.copy(
                showConfirmDialog = false,
                actionError = "No downloadable videos selected."
            )
            return
        }

        _uiState.value = current.copy(showConfirmDialog = false, actionError = null)
        viewModelScope.launch {
            val result = coordinator.startChannelAutoDiscoveryBatch(
                classified = current.classified,
                preview = current.preview,
                selectedTabs = current.selectedTabs,
                targetLimit = current.effectiveLimit,
                qualityPreset = current.qualityPreset,
                audioOutputFormat = current.audioOutputFormat,
                wifiOnly = current.wifiOnly
            )
            val latest = _uiState.value as? HomeUiState.ChannelReady ?: return@launch
            result.fold(
                onSuccess = { batchId ->
                    _uiState.value = latest.copy(lastQueuedBatchId = batchId, actionError = null)
                },
                onFailure = { err ->
                    val msg = (err as? ShreyError)?.userMessage ?: err.message ?: "Failed to queue channel download."
                    _uiState.value = latest.copy(actionError = msg)
                }
            )
        }
    }

    fun retryEngineInit() {
        viewModelScope.launch {
            engineCoordinator.retryInitialization()
        }
    }

    fun checkEngineUpdatesFromError() {
        val manager = updateManager ?: return
        viewModelScope.launch {
            manager.checkForUpdates(forceManual = false, notifyIfAvailable = false)
        }
    }

    private fun resolveInitialMode(media: MediaInfo, preferred: DownloadMode): DownloadMode {
        val preferredOptions = FormatSelectionMapper.buildOptions(media, preferred)
        if (preferredOptions.isNotEmpty()) return preferred
        return DownloadMode.entries.firstOrNull { mode ->
            FormatSelectionMapper.buildOptions(media, mode).isNotEmpty()
        } ?: DownloadMode.VIDEO_AUDIO
    }

    class Factory(
        private val mediaExtractor: MediaExtractor,
        private val engineCoordinator: EngineCoordinator,
        private val downloadQueueManager: DownloadQueueManager,
        private val settingsDataStore: SettingsDataStore,
        private val updateManager: UpdateManager,
        private val collectionExtractor: CollectionExtractor? = null,
        private val batchCoordinator: BatchDownloadCoordinator? = null,
        private val storagePublisher: MediaStoragePublisher? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(
                mediaExtractor = mediaExtractor,
                engineCoordinator = engineCoordinator,
                downloadQueueManager = downloadQueueManager,
                settingsDataStore = settingsDataStore,
                updateManager = updateManager,
                collectionExtractor = collectionExtractor,
                batchCoordinator = batchCoordinator,
                storagePublisher = storagePublisher
            ) as T
        }
    }
}
