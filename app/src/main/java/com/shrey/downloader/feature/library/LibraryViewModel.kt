package com.shrey.downloader.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.shrey.downloader.core.service.DownloadQueueManager
import com.shrey.downloader.core.storage.MediaStoragePublisher
import com.shrey.downloader.domain.model.DownloadItem
import com.shrey.downloader.domain.model.DownloadMode
import com.shrey.downloader.domain.model.DownloadStage
import com.shrey.downloader.domain.model.LibraryFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryMediaEntry(
    val item: DownloadItem,
    val isAccessible: Boolean
)

data class LibraryUiState(
    val selectedFilter: LibraryFilter = LibraryFilter.ALL,
    val entries: List<LibraryMediaEntry> = emptyList()
)

class LibraryViewModel(
    private val queueManager: DownloadQueueManager,
    val storagePublisher: MediaStoragePublisher
) : ViewModel() {

    private val selectedFilter = MutableStateFlow(LibraryFilter.ALL)
    private val refreshTick = MutableStateFlow(0L)

    val uiState: StateFlow<LibraryUiState> = combine(
        queueManager.downloadsFlow,
        selectedFilter,
        refreshTick
    ) { allItems, filter, _ ->
        val completed = allItems.filter { it.stage == DownloadStage.COMPLETED }
        val filtered = when (filter) {
            LibraryFilter.ALL -> completed
            LibraryFilter.VIDEOS -> completed.filter {
                it.mode == DownloadMode.VIDEO_AUDIO || it.mode == DownloadMode.VIDEO_ONLY
            }
            LibraryFilter.AUDIO -> completed.filter { it.mode == DownloadMode.AUDIO_ONLY }
            LibraryFilter.IMAGES -> completed.filter { it.mode == DownloadMode.THUMBNAIL_ONLY }
        }
        val checked = withContext(Dispatchers.IO) {
            filtered.map { item ->
                LibraryMediaEntry(
                    item = item,
                    isAccessible = storagePublisher.isUriAccessible(item.destinationUri)
                )
            }
        }
        LibraryUiState(
            selectedFilter = filter,
            entries = checked
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState()
    )

    fun onFilterSelected(filter: LibraryFilter) {
        selectedFilter.value = filter
    }

    fun refreshAccessibility() {
        refreshTick.value = System.currentTimeMillis()
    }

    fun removeFromHistory(downloadId: String) {
        viewModelScope.launch {
            queueManager.removeFromHistory(downloadId)
        }
    }

    fun deleteFileAndRecord(downloadId: String) {
        viewModelScope.launch {
            queueManager.deleteDownloadedFileAndRecord(downloadId)
        }
    }

    class Factory(
        private val queueManager: DownloadQueueManager,
        private val storagePublisher: MediaStoragePublisher
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LibraryViewModel(queueManager, storagePublisher) as T
        }
    }
}
