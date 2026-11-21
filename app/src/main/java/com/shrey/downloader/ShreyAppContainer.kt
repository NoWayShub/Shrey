package com.shrey.downloader

import android.content.Context
import com.shrey.downloader.core.database.ShreyDatabase
import com.shrey.downloader.core.engine.EngineCoordinator
import com.shrey.downloader.core.engine.YtDlpDownloadEngine
import com.shrey.downloader.core.engine.YtDlpMediaExtractor
import com.shrey.downloader.core.notifications.DownloadNotificationManager
import com.shrey.downloader.core.service.DownloadQueueManager
import com.shrey.downloader.core.storage.MediaStoragePublisher
import com.shrey.downloader.data.local.SettingsDataStore
import com.shrey.downloader.data.repository.RoomDownloadRepository
import com.shrey.downloader.domain.repository.MediaExtractor

class ShreyAppContainer(appContext: Context) {

    val database: ShreyDatabase = ShreyDatabase.create(appContext)
    val settingsDataStore: SettingsDataStore = SettingsDataStore(appContext)
    val storagePublisher: MediaStoragePublisher = MediaStoragePublisher(appContext)
    val notificationManager: DownloadNotificationManager = DownloadNotificationManager(appContext)
    val engineCoordinator: EngineCoordinator = EngineCoordinator(appContext)
    val mediaExtractor: MediaExtractor = YtDlpMediaExtractor(engineCoordinator)
    val downloadEngine: YtDlpDownloadEngine = YtDlpDownloadEngine(
        context = appContext,
        engineCoordinator = engineCoordinator,
        storagePublisher = storagePublisher
    )
    val downloadRepository: RoomDownloadRepository = RoomDownloadRepository(database.downloadDao())
    val downloadQueueManager: DownloadQueueManager = DownloadQueueManager(
        context = appContext,
        repository = downloadRepository,
        downloadEngine = downloadEngine,
        storagePublisher = storagePublisher,
        settingsDataStore = settingsDataStore,
        notificationManager = notificationManager
    )
}
