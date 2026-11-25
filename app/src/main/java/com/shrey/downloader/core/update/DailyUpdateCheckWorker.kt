package com.shrey.downloader.core.update

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.shrey.downloader.ShreyApplication
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Best-effort daily background update checker powered by Android WorkManager.
 *
 * - Runs approximately every 24 hours (with 3-hour flex window) only when network is connected
 *   and battery is not low.
 * - Never wakes the device on an exact alarm or runs continuous polling.
 * - Uses bounded exponential backoff (max 3 attempts) for transient network errors.
 */
class DailyUpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? ShreyApplication ?: return Result.success()
        val updateManager = app.container.updateManager
        val prefs = app.container.updateStore.recordFlow.first().preferences

        if (!prefs.autoCheckEnabled) {
            return Result.success()
        }

        val checkResult = updateManager.checkForUpdates(
            forceManual = false,
            notifyIfAvailable = prefs.notifyOnUpdates,
            cooldownMillis = DAILY_WORK_MIN_GAP_MILLIS
        )

        return if (checkResult.isSuccess) {
            Result.success()
        } else if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
            Result.retry()
        } else {
            Result.failure()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "shrey_daily_update_check"
        const val WORK_TAG = "shrey_update_check"
        const val MAX_RETRY_ATTEMPTS = 3
        private const val DAILY_WORK_MIN_GAP_MILLIS = 12L * 60L * 60L * 1000L // 12h guard
    }
}

object UpdateWorkScheduler {

    fun buildDailyRequest(): PeriodicWorkRequest {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        return PeriodicWorkRequestBuilder<DailyUpdateCheckWorker>(
            repeatInterval = 24L,
            repeatIntervalTimeUnit = TimeUnit.HOURS,
            flexTimeInterval = 3L,
            flexTimeIntervalUnit = TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.MINUTES)
            .addTag(DailyUpdateCheckWorker.WORK_TAG)
            .build()
    }

    fun scheduleDailyCheck(context: Context) {
        val request = buildDailyRequest()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DailyUpdateCheckWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancelDailyCheck(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(DailyUpdateCheckWorker.UNIQUE_WORK_NAME)
    }
}
