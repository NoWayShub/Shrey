package com.shrey.downloader.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.shrey.downloader.core.service.DownloadQueueManager
import com.shrey.downloader.core.storage.MediaStoragePublisher
import com.shrey.downloader.domain.model.DownloadItem
import com.shrey.downloader.domain.model.DownloadStage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DownloadsUiState(
    val active: List<DownloadItem> = emptyList(),
    val queued: List<DownloadItem> = emptyList(),
    val completed: List<DownloadItem> = emptyList(),
    val failed: List<DownloadItem> = emptyList()
) {
    val isEmpty: Boolean
        get() = active.isEmpty() && queued.isEmpty() && completed.isEmpty() && failed.isEmpty()

    val inProgressCount: Int
        get() = active.size + queued.size
}

class DownloadsViewModel(
    private val queueManager: DownloadQueueManager,
    val storagePublisher: MediaStoragePublisher
) : ViewModel() {

    val uiState: StateFlow<DownloadsUiState> = queueManager.downloadsFlow
        .map { items ->
            DownloadsUiState(
                active = items.filter { it.stage.isActive },
                queued = items.filter { it.stage == DownloadStage.QUEUED },
                completed = items.filter { it.stage == DownloadStage.COMPLETED },
                failed = items.filter {
                    it.stage == DownloadStage.FAILED ||
                        it.stage == DownloadStage.CANCELLED ||
                        it.stage == DownloadStage.INTERRUPTED
                }
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DownloadsUiState()
        )

    fun cancelDownload(downloadId: String) {
        viewModelScope.launch { queueManager.cancel(downloadId) }
    }

    fun retryDownload(downloadId: String) {
        viewModelScope.launch { queueManager.retry(downloadId) }
    }

    fun removeFromHistory(downloadId: String) {
        viewModelScope.launch { queueManager.removeFromHistory(downloadId) }
    }

    fun deleteFileAndHistory(downloadId: String) {
        viewModelScope.launch { queueManager.deleteDownloadedFileAndRecord(downloadId) }
    }

    class Factory(
        private val queueManager: DownloadQueueManager,
        private val storagePublisher: MediaStoragePublisher
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DownloadsViewModel(queueManager, storagePublisher) as T
        }
    }
}
