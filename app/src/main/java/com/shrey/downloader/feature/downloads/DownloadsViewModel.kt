package com.shrey.downloader.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.shrey.downloader.core.service.BatchDownloadCoordinator
import com.shrey.downloader.core.service.DownloadQueueManager
import com.shrey.downloader.core.storage.MediaStoragePublisher
import com.shrey.downloader.domain.model.BatchItemStatus
import com.shrey.downloader.domain.model.BatchWithItems
import com.shrey.downloader.domain.model.DownloadItem
import com.shrey.downloader.domain.model.DownloadStage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DownloadsUiState(
    val batches: List<BatchWithItems> = emptyList(),
    val active: List<DownloadItem> = emptyList(),
    val queued: List<DownloadItem> = emptyList(),
    val completed: List<DownloadItem> = emptyList(),
    val failed: List<DownloadItem> = emptyList()
) {
    val isEmpty: Boolean
        get() = batches.isEmpty() && active.isEmpty() && queued.isEmpty() && completed.isEmpty() && failed.isEmpty()

    val inProgressCount: Int
        get() {
            val singleInProgress = active.size + queued.size
            val batchInProgress = batches.sumOf { bw ->
                val b = bw.batch
                if (b.status.isTerminal && !b.isDiscoveryActive) {
                    0
                } else {
                    val remaining = (b.selectedCount - b.completedCount - b.skippedCount - b.failedCount).coerceAtLeast(0)
                    if (remaining == 0 && b.isDiscoveryActive) 1 else remaining
                }
            }
            return singleInProgress + batchInProgress
        }
}

class DownloadsViewModel(
    private val queueManager: DownloadQueueManager,
    val storagePublisher: MediaStoragePublisher,
    private val batchCoordinator: BatchDownloadCoordinator? = null
) : ViewModel() {

    private val batchesSource = batchCoordinator?.batchesFlow ?: flowOf(emptyList())

    val uiState: StateFlow<DownloadsUiState> = combine(
        queueManager.downloadsFlow,
        batchesSource
    ) { items, batches ->
        // Keep standalone single-video items separate from batch-owned rows so batch videos
        // are displayed inside their expandable batch overview card rather than duplicated.
        val standalone = if (batches.isEmpty()) items else items.filter { it.batchId == null }
        DownloadsUiState(
            batches = batches,
            active = standalone.filter { it.stage.isActive },
            queued = standalone.filter { it.stage == DownloadStage.QUEUED },
            completed = standalone.filter { it.stage == DownloadStage.COMPLETED },
            failed = standalone.filter {
                it.stage == DownloadStage.FAILED ||
                    it.stage == DownloadStage.CANCELLED ||
                    it.stage == DownloadStage.INTERRUPTED
            }
        )
    }.stateIn(
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

    // ---- Batch Controls ----

    fun pauseBatch(batchId: String) {
        val coordinator = batchCoordinator ?: return
        viewModelScope.launch { coordinator.pauseBatchScheduling(batchId) }
    }

    fun resumeBatch(batchId: String) {
        val coordinator = batchCoordinator ?: return
        viewModelScope.launch { coordinator.resumeBatchScheduling(batchId) }
    }

    fun stopBatchDiscovery(batchId: String) {
        val coordinator = batchCoordinator ?: return
        viewModelScope.launch { coordinator.stopDiscovery(batchId) }
    }

    fun cancelBatch(batchId: String) {
        val coordinator = batchCoordinator ?: return
        viewModelScope.launch { coordinator.cancelBatch(batchId) }
    }

    fun cancelBatchItem(batchId: String, itemId: String) {
        val coordinator = batchCoordinator ?: return
        viewModelScope.launch { coordinator.cancelSingleItem(batchId, itemId) }
    }

    fun retryFailedBatchItems(batchId: String) {
        val coordinator = batchCoordinator ?: return
        viewModelScope.launch { coordinator.retryFailedItems(batchId) }
    }

    fun retryBatchItem(batchId: String, itemId: String) {
        val coordinator = batchCoordinator ?: return
        viewModelScope.launch { coordinator.retrySingleItem(batchId, itemId) }
    }

    fun removeBatch(batchId: String) {
        val coordinator = batchCoordinator ?: return
        viewModelScope.launch { coordinator.removeBatch(batchId) }
    }

    class Factory(
        private val queueManager: DownloadQueueManager,
        private val storagePublisher: MediaStoragePublisher,
        private val batchCoordinator: BatchDownloadCoordinator? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DownloadsViewModel(queueManager, storagePublisher, batchCoordinator) as T
        }
    }
}
