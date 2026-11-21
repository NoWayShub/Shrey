package com.shrey.downloader.core.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.shrey.downloader.ShreyApplication
import com.shrey.downloader.core.notifications.DownloadNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ShreyDownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as? ShreyApplication ?: run {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val container = app.container

        when (intent?.action) {
            ACTION_CANCEL_DOWNLOAD -> {
                val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID)
                if (!downloadId.isNullOrBlank()) {
                    serviceScope.launch {
                        container.downloadQueueManager.cancel(downloadId)
                    }
                }
            }
            else -> {
                val active = container.downloadQueueManager.activeTransfer.value
                val notification = if (active != null) {
                    container.notificationManager.buildActiveNotification(
                        downloadId = active.downloadId,
                        title = active.title,
                        progress = active.progress
                    )
                } else {
                    container.notificationManager.buildIdleForegroundNotification()
                }
                runCatching {
                    ServiceCompat.startForeground(
                        this,
                        DownloadNotificationManager.ACTIVE_NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val app = application as? ShreyApplication
        app?.container?.downloadQueueManager?.handleServiceTimeout()
        stopSelf(startId)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_QUEUE = "com.shrey.downloader.action.START_QUEUE"
        const val ACTION_CANCEL_DOWNLOAD = "com.shrey.downloader.action.CANCEL_DOWNLOAD"
        const val EXTRA_DOWNLOAD_ID = "extra_download_id"
    }
}
