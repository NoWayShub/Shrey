package com.shrey.downloader.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.shrey.downloader.core.engine.EngineCoordinator
import com.shrey.downloader.core.engine.FormatSelectionMapper
import com.shrey.downloader.core.service.DownloadQueueManager
import com.shrey.downloader.core.update.UpdateManager
import com.shrey.downloader.core.update.UpdateSnapshot
import com.shrey.downloader.core.validation.UrlValidationResult
import com.shrey.downloader.core.validation.UrlValidator
import com.shrey.downloader.data.local.SettingsDataStore
import com.shrey.downloader.domain.model.AudioOutputFormat
import com.shrey.downloader.domain.model.DownloadMode
import com.shrey.downloader.domain.model.DownloadRequest
import com.shrey.downloader.domain.model.EngineState
import com.shrey.downloader.domain.model.FormatOption
import com.shrey.downloader.domain.model.MediaInfo
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

    data class Analyzing(val url: String) : HomeUiState

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

    data class Error(val error: ShreyError) : HomeUiState
}

class HomeViewModel(
    private val mediaExtractor: MediaExtractor,
    private val engineCoordinator: EngineCoordinator,
    private val downloadQueueManager: DownloadQueueManager,
    private val settingsDataStore: SettingsDataStore,
    private val updateManager: UpdateManager? = null
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
        if (newValue.isBlank() && _uiState.value is HomeUiState.Error) {
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

        val validation = UrlValidator.normalizeAndValidate(raw)
        if (validation is UrlValidationResult.Invalid) {
            _uiState.value = HomeUiState.Error(validation.error)
            return
        }

        val normalizedUrl = (validation as UrlValidationResult.Valid).normalizedUrl
        _urlInput.value = normalizedUrl

        extractionJob?.cancel()
        val myToken = extractionToken.incrementAndGet()
        _uiState.value = HomeUiState.Analyzing(normalizedUrl)

        extractionJob = viewModelScope.launch {
            val result = mediaExtractor.extract(normalizedUrl)
            // Ignore stale results if a newer analysis or clear occurred
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
                destinationTreeUri = currentSettings.customDestinationUri
            )
            downloadQueueManager.enqueue(request)
            val latest = _uiState.value
            if (latest is HomeUiState.Ready && latest.media.id == current.media.id) {
                _uiState.value = latest.copy(lastQueuedDownloadId = downloadId)
            }
        }
    }

    fun retryEngineInit() {
        viewModelScope.launch {
            engineCoordinator.retryInitialization()
        }
    }

    /**
     * Triggered from an extractor failure card. Respects UpdateManager cooldown so repeated
     * taps never hammer upstream GitHub release APIs.
     */
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
        private val updateManager: UpdateManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(
                mediaExtractor = mediaExtractor,
                engineCoordinator = engineCoordinator,
                downloadQueueManager = downloadQueueManager,
                settingsDataStore = settingsDataStore,
                updateManager = updateManager
            ) as T
        }
    }
}
