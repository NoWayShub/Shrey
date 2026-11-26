package com.shrey.downloader

import android.content.Context
import com.shrey.downloader.core.database.ShreyDatabase
import com.shrey.downloader.core.engine.CollectionExtractor
import com.shrey.downloader.core.engine.EngineCoordinator
import com.shrey.downloader.core.engine.YtDlpCollectionExtractor
import com.shrey.downloader.core.engine.YtDlpDownloadEngine
import com.shrey.downloader.core.engine.YtDlpMediaExtractor
import com.shrey.downloader.core.notifications.DownloadNotificationManager
import com.shrey.downloader.core.service.BatchDownloadCoordinator
import com.shrey.downloader.core.service.DownloadQueueManager
import com.shrey.downloader.core.storage.MediaStoragePublisher
import com.shrey.downloader.core.update.ApkUpdateVerifier
import com.shrey.downloader.core.update.OkHttpReleaseMetadataSource
import com.shrey.downloader.core.update.ReleaseMetadataSource
import com.shrey.downloader.core.update.UpdateManager
import com.shrey.downloader.core.update.UpdateManifestVerifier
import com.shrey.downloader.core.update.UpdateStore
import com.shrey.downloader.data.local.SettingsDataStore
import com.shrey.downloader.data.repository.BatchRepository
import com.shrey.downloader.data.repository.RoomBatchRepository
import com.shrey.downloader.data.repository.RoomDownloadRepository
import com.shrey.downloader.domain.repository.MediaExtractor

class ShreyAppContainer(appContext: Context) {

    val database: ShreyDatabase = ShreyDatabase.create(appContext)
    val settingsDataStore: SettingsDataStore = SettingsDataStore(appContext)
    val updateStore: UpdateStore = UpdateStore(appContext)
    val storagePublisher: MediaStoragePublisher = MediaStoragePublisher(appContext)
    val notificationManager: DownloadNotificationManager = DownloadNotificationManager(appContext)
    val engineCoordinator: EngineCoordinator = EngineCoordinator(appContext)
    val mediaExtractor: MediaExtractor = YtDlpMediaExtractor(engineCoordinator)
    val collectionExtractor: CollectionExtractor = YtDlpCollectionExtractor(engineCoordinator)
    val downloadEngine: YtDlpDownloadEngine = YtDlpDownloadEngine(
        context = appContext,
        engineCoordinator = engineCoordinator,
        storagePublisher = storagePublisher
    )
    val downloadRepository: RoomDownloadRepository = RoomDownloadRepository(database.downloadDao())
    val batchRepository: BatchRepository = RoomBatchRepository(database.batchDao())
    val downloadQueueManager: DownloadQueueManager = DownloadQueueManager(
        context = appContext,
        repository = downloadRepository,
        downloadEngine = downloadEngine,
        storagePublisher = storagePublisher,
        settingsDataStore = settingsDataStore,
        notificationManager = notificationManager
    )
    val batchCoordinator: BatchDownloadCoordinator = BatchDownloadCoordinator(
        context = appContext,
        batchRepository = batchRepository,
        queueManager = downloadQueueManager,
        storagePublisher = storagePublisher,
        settingsDataStore = settingsDataStore,
        collectionExtractor = collectionExtractor
    )
    val releaseMetadataSource: ReleaseMetadataSource = OkHttpReleaseMetadataSource()
    val updateManifestVerifier: UpdateManifestVerifier = UpdateManifestVerifier()
    val apkUpdateVerifier: ApkUpdateVerifier = ApkUpdateVerifier()
    val updateManager: UpdateManager = UpdateManager(
        context = appContext,
        updateStore = updateStore,
        settingsDataStore = settingsDataStore,
        metadataSource = releaseMetadataSource,
        manifestVerifier = updateManifestVerifier,
        apkVerifier = apkUpdateVerifier,
        notificationManager = notificationManager,
        engineCoordinator = engineCoordinator
    )
}
