package com.shrey.downloader.core.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.shrey.downloader.core.service.ShreyDownloadService
import com.shrey.downloader.domain.model.DownloadProgress
import com.shrey.downloader.domain.model.DownloadStage

class DownloadNotificationManager(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_downloads),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_downloads_desc)
            setShowBadge(false)
        }
        val updatesChannel = NotificationChannel(
            UPDATES_CHANNEL_ID,
            context.getString(R.string.notification_channel_updates),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_updates_desc)
            setShowBadge(false)
        }
        val sysManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        sysManager.createNotificationChannel(channel)
        sysManager.createNotificationChannel(updatesChannel)
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun buildActiveNotification(
        downloadId: String,
        title: String,
        progress: DownloadProgress
    ): Notification {
        ensureChannel()
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = PendingIntent.getService(
            context,
            downloadId.hashCode(),
            Intent(context, ShreyDownloadService::class.java).apply {
                action = ShreyDownloadService.ACTION_CANCEL_DOWNLOAD
                putExtra(ShreyDownloadService.EXTRA_DOWNLOAD_ID, downloadId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pct = progress.progressPercent?.toInt()?.coerceIn(0, 100)
        val indeterminate = pct == null ||
            progress.stage == DownloadStage.PREPARING ||
            progress.stage == DownloadStage.EXTRACTING ||
            progress.stage == DownloadStage.MERGING ||
            progress.stage == DownloadStage.CONVERTING

        val contentText = buildString {
            append(progress.message ?: progress.stage.label)
            if (!indeterminate && pct != null) {
                append(" · $pct%")
            }
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title.ifBlank { context.getString(R.string.notification_downloading_title) })
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, pct ?: 0, indeterminate)
            .setContentIntent(openIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.action_cancel),
                cancelIntent
            )
            .build()
    }

    fun buildIdleForegroundNotification(): Notification {
        ensureChannel()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notification_downloading_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    fun updateActiveNotification(
        downloadId: String,
        title: String,
        progress: DownloadProgress,
        notificationsEnabled: Boolean
    ) {
        if (!notificationsEnabled || !hasNotificationPermission()) return
        runCatching {
            manager.notify(
                ACTIVE_NOTIFICATION_ID,
                buildActiveNotification(downloadId, title, progress)
            )
        }
    }

    fun showCompletedNotification(
        downloadId: String,
        title: String,
        notificationsEnabled: Boolean
    ) {
        if (!notificationsEnabled || !hasNotificationPermission()) return
        ensureChannel()
        val openIntent = PendingIntent.getActivity(
            context,
            downloadId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.notification_completed_title))
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()

        runCatching {
            manager.notify(COMPLETION_NOTIFICATION_BASE + (downloadId.hashCode() and 0x0FFF), notification)
        }
    }

    fun showFailedNotification(
        downloadId: String,
        title: String,
        reason: String,
        notificationsEnabled: Boolean
    ) {
        if (!notificationsEnabled || !hasNotificationPermission()) return
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.notification_failed_title))
            .setContentText("$title — $reason")
            .setAutoCancel(true)
            .build()
        runCatching {
            manager.notify(COMPLETION_NOTIFICATION_BASE + (downloadId.hashCode() and 0x0FFF), notification)
        }
    }

    fun cancelActiveNotification() {
        runCatching { manager.cancel(ACTIVE_NOTIFICATION_ID) }
    }

    fun showUpdateAvailableNotification(
        summary: String,
        notificationsEnabled: Boolean,
        title: String? = null
    ) {
        if (!notificationsEnabled || !hasNotificationPermission()) return
        ensureChannel()
        val openIntent = PendingIntent.getActivity(
            context,
            UPDATE_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val effectiveTitle = title?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.notification_update_available_title)
        val notification = NotificationCompat.Builder(context, UPDATES_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(effectiveTitle)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        runCatching {
            manager.notify(UPDATE_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val CHANNEL_ID = "shrey_downloads_channel"
        const val UPDATES_CHANNEL_ID = "shrey_updates_channel"
        const val ACTIVE_NOTIFICATION_ID = 4001
        const val UPDATE_NOTIFICATION_ID = 4099
        private const val COMPLETION_NOTIFICATION_BASE = 5000
    }
}
