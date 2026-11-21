package com.shrey.downloader

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ShreyApplication : Application() {

    lateinit var container: ShreyAppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = ShreyAppContainer(this)
        container.notificationManager.ensureChannel()
        container.downloadQueueManager.initializeOnAppStart()

        // Warm up yt-dlp / FFmpeg engine off the main thread so first Analyze is snappy
        appScope.launch {
            container.engineCoordinator.ensureInitialized()
        }
    }
}
