package com.shrey.downloader.core.service

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import com.shrey.downloader.core.notifications.DownloadNotificationManager
import com.shrey.downloader.core.storage.MediaStoragePublisher
import com.shrey.downloader.data.local.SettingsDataStore
import com.shrey.downloader.data.repository.RoomDownloadRepository
import com.shrey.downloader.domain.model.DownloadItem
import com.shrey.downloader.domain.model.DownloadProgress
import com.shrey.downloader.domain.model.DownloadRequest
import com.shrey.downloader.domain.model.DownloadResult
import com.shrey.downloader.domain.model.DownloadStage
import com.shrey.downloader.domain.model.ShreyError
import com.shrey.downloader.domain.repository.DownloadEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ActiveTransferState(
    val downloadId: String,
    val title: String,
    val progress: DownloadProgress
)

interface DownloadLifecycleListener {
    fun onDownloadProgress(progress: DownloadProgress)
    fun onDownloadCompleted(
        downloadId: String,
        destinationUri: String,
        displayName: String,
        mimeType: String,
        fileSizeBytes: Long
    )
    fun onDownloadFailed(downloadId: String, error: ShreyError)
    fun onDownloadCancelled(downloadId: String)
}

class DownloadQueueManager(
    private val context: Context,
    private val repository: RoomDownloadRepository,
    private val downloadEngine: DownloadEngine,
    private val storagePublisher: MediaStoragePublisher,
    private val settingsDataStore: SettingsDataStore,
    private val notificationManager: DownloadNotificationManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processorMutex = Mutex()
    private var processorJob: Job? = null

    @Volatile
    private var activeDownloadId: String? = null
    @Volatile
    private var activeJob: Job? = null
    @Volatile
    private var lifecycleListener: DownloadLifecycleListener? = null

    private val _activeTransfer = MutableStateFlow<ActiveTransferState?>(null)
    val activeTransfer: StateFlow<ActiveTransferState?> = _activeTransfer.asStateFlow()

    val downloadsFlow: Flow<List<DownloadItem>> = repository.observeDownloads()

    fun setLifecycleListener(listener: DownloadLifecycleListener?) {
        lifecycleListener = listener
    }

    fun isTransferActive(): Boolean = activeDownloadId != null

    suspend fun hasActiveOrPendingStandaloneDownload(): Boolean =
        repository.countActiveOrQueuedStandalone() > 0

    fun initializeOnAppStart() {
        scope.launch {
            repository.reconcileInterruptedJobs()
            // Resume any cleanly QUEUED items if present
            if (repository.getNextQueued() != null) {
                triggerQueueProcessing()
            }
        }
    }

    suspend fun enqueue(request: DownloadRequest): String {
        val id = repository.enqueue(request)
        startForegroundServiceSafely()
        triggerQueueProcessing()
        return id
    }

    suspend fun retry(downloadId: String) {
        val retried = repository.prepareRetry(downloadId) ?: return
        startForegroundServiceSafely()
        triggerQueueProcessing()
    }

    suspend fun cancel(downloadId: String) {
        val isCurrent = activeDownloadId == downloadId
        if (isCurrent) {
            downloadEngine.cancel(downloadId)
            activeJob?.cancel()
        }
        repository.markCancelled(downloadId)
        lifecycleListener?.onDownloadCancelled(downloadId)
    }

    suspend fun removeFromHistory(downloadId: String) {
        if (activeDownloadId == downloadId) {
            cancel(downloadId)
        }
        repository.removeFromHistory(downloadId)
    }

    suspend fun deleteDownloadedFileAndRecord(downloadId: String): Boolean {
        val item = repository.getDownload(downloadId) ?: return false
        val deleted = storagePublisher.deletePublishedUri(item.destinationUri)
        repository.removeFromHistory(downloadId)
        return deleted
    }

    fun handleServiceTimeout() {
        val currentId = activeDownloadId
        if (currentId != null) {
            scope.launch {
                downloadEngine.cancel(currentId)
                activeJob?.cancel()
                repository.markFailed(
                    downloadId = currentId,
                    userError = "Background transfer time limit reached. Tap Retry to resume.",
                    technicalError = "Android foreground service dataSync timeout reached"
                )
            }
        }
    }

    private fun triggerQueueProcessing() {
        scope.launch {
            processorMutex.withLock {
                if (processorJob?.isActive == true) return@withLock
                processorJob = scope.launch {
                    processQueueLoop()
                }
            }
        }
    }

    private suspend fun processQueueLoop() {
        while (true) {
            val next = repository.getNextQueued() ?: break
            runSingleDownload(next)
        }
        _activeTransfer.value = null
        notificationManager.cancelActiveNotification()
        stopForegroundServiceSafely()
    }

    private suspend fun runSingleDownload(request: DownloadRequest) {
        activeDownloadId = request.downloadId
        val settings = settingsDataStore.settingsFlow.first()

        // Network & mobile-data guard
        val networkCheckError = checkNetworkPolicy(settings.allowMobileData)
        if (networkCheckError != null) {
            repository.markFailed(
                downloadId = request.downloadId,
                userError = networkCheckError.userMessage,
                technicalError = networkCheckError.technicalDetails
            )
            lifecycleListener?.onDownloadFailed(request.downloadId, networkCheckError)
            notificationManager.showFailedNotification(
                downloadId = request.downloadId,
                title = request.mediaTitle,
                reason = networkCheckError.title,
                notificationsEnabled = settings.notificationsEnabled
            )
            activeDownloadId = null
            return
        }

        var lastDbUpdateMs = 0L
        var lastStage: DownloadStage? = null

        val job = scope.launch {
            val result = downloadEngine.download(request) { progress ->
                _activeTransfer.value = ActiveTransferState(
                    downloadId = request.downloadId,
                    title = request.mediaTitle,
                    progress = progress
                )
                val now = System.currentTimeMillis()
                val stageChanged = progress.stage != lastStage
                if (stageChanged || now - lastDbUpdateMs >= 350L) {
                    lastDbUpdateMs = now
                    lastStage = progress.stage
                    scope.launch {
                        repository.updateProgress(progress)
                    }
                    lifecycleListener?.onDownloadProgress(progress)
                    notificationManager.updateActiveNotification(
                        downloadId = request.downloadId,
                        title = request.mediaTitle,
                        progress = progress,
                        notificationsEnabled = settings.notificationsEnabled
                    )
                }
            }

            when (result) {
                is DownloadResult.Success -> {
                    repository.markCompleted(
                        downloadId = request.downloadId,
                        destinationUri = result.destinationUri,
                        displayName = result.displayName,
                        mimeType = result.mimeType,
                        fileSizeBytes = result.fileSizeBytes
                    )
                    lifecycleListener?.onDownloadCompleted(
                        downloadId = request.downloadId,
                        destinationUri = result.destinationUri,
                        displayName = result.displayName,
                        mimeType = result.mimeType,
                        fileSizeBytes = result.fileSizeBytes
                    )
                    notificationManager.showCompletedNotification(
                        downloadId = request.downloadId,
                        title = request.mediaTitle,
                        notificationsEnabled = settings.notificationsEnabled
                    )
                }
                DownloadResult.Cancelled -> {
                    repository.markCancelled(request.downloadId)
                    lifecycleListener?.onDownloadCancelled(request.downloadId)
                }
                is DownloadResult.Failure -> {
                    repository.markFailed(
                        downloadId = request.downloadId,
                        userError = result.error.userMessage,
                        technicalError = result.error.technicalDetails
                    )
                    lifecycleListener?.onDownloadFailed(request.downloadId, result.error)
                    notificationManager.showFailedNotification(
                        downloadId = request.downloadId,
                        title = request.mediaTitle,
                        reason = result.error.title,
                        notificationsEnabled = settings.notificationsEnabled
                    )
                }
            }
        }

        activeJob = job
        job.join()
        activeJob = null
        activeDownloadId = null
    }

    private fun checkNetworkPolicy(allowMobileData: Boolean): ShreyError? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val network = cm.activeNetwork
            ?: return ShreyError.NetworkUnavailable("No active network connection")
        val caps = cm.getNetworkCapabilities(network)
            ?: return ShreyError.NetworkUnavailable("No network capabilities detected")
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return ShreyError.NetworkUnavailable("Network does not have internet capability")
        }
        if (!allowMobileData && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            return ShreyError.NetworkUnavailable(
                "Downloads over mobile/metered data are disabled in Settings"
            )
        }
        return null
    }

    private fun startForegroundServiceSafely() {
        runCatching {
            val intent = Intent(context, ShreyDownloadService::class.java).apply {
                action = ShreyDownloadService.ACTION_START_QUEUE
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private fun stopForegroundServiceSafely() {
        runCatching {
            context.stopService(Intent(context, ShreyDownloadService::class.java))
        }
    }
}
