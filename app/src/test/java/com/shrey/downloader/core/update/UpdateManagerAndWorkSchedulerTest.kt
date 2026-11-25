package com.shrey.downloader.core.update

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.shrey.downloader.core.engine.EngineCoordinator
import com.shrey.downloader.core.notifications.DownloadNotificationManager
import com.shrey.downloader.data.local.SettingsDataStore
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpdateManagerAndWorkSchedulerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    private class FakeReleaseMetadataSource(
        var ytDlpResult: Result<GitHubReleaseInfo>,
        var shreyReleasesResult: Result<List<GitHubReleaseInfo>>,
        var artificialDelayMs: Long = 0L
    ) : ReleaseMetadataSource {
        val ytDlpCallCount = AtomicInteger(0)
        val shreyCallCount = AtomicInteger(0)

        override suspend fun fetchLatestYtDlpRelease(): Result<GitHubReleaseInfo> {
            ytDlpCallCount.incrementAndGet()
            if (artificialDelayMs > 0L) delay(artificialDelayMs)
            return ytDlpResult
        }

        override suspend fun fetchShreyReleases(): Result<List<GitHubReleaseInfo>> {
            shreyCallCount.incrementAndGet()
            if (artificialDelayMs > 0L) delay(artificialDelayMs)
            return shreyReleasesResult
        }

        override suspend fun downloadFile(
            url: String,
            allowedHosts: Set<String>,
            destination: File,
            onProgress: (Float) -> Unit
        ): Result<File> = Result.failure(UnsupportedOperationException("Not used in metadata tests"))
    }

    private fun buildManager(
        source: ReleaseMetadataSource,
        installedVersion: String = "0.0.3-dev",
        installedCode: Long = 3L,
        nowMillis: () -> Long = { 1_750_000_000_000L }
    ): Pair<UpdateManager, UpdateStore> {
        val updateStore = UpdateStore(context)
        val settingsStore = SettingsDataStore(context)
        val manager = UpdateManager(
            context = context,
            updateStore = updateStore,
            settingsDataStore = settingsStore,
            metadataSource = source,
            manifestVerifier = UpdateManifestVerifier(publicKeySpkiBase64 = ""),
            apkVerifier = ApkUpdateVerifier(),
            notificationManager = DownloadNotificationManager(context),
            engineCoordinator = EngineCoordinator(context),
            installedAppVersionName = installedVersion,
            installedAppVersionCode = installedCode,
            nowMillisProvider = nowMillis
        )
        return manager to updateStore
    }

    @Test
    fun `mocked github check detects updates and respects stable vs beta channels`() = runBlocking {
        val releases = listOf(
            GitHubReleaseInfo(
                tagName = "v0.0.4-beta",
                name = "Shrey v0.0.4 Beta",
                body = "Beta improvements",
                htmlUrl = "https://github.com/NoWayShub/Shrey/releases/tag/v0.0.4-beta",
                isPrerelease = true,
                publishedAt = "2026-11-21T00:00:00Z",
                assets = listOf(
                    GitHubReleaseAsset(
                        name = "Shrey-v0.0.4-beta.apk",
                        downloadUrl = "https://github.com/NoWayShub/Shrey/releases/download/v0.0.4-beta/Shrey-v0.0.4-beta.apk",
                        sizeBytes = 250_000_000L
                    )
                )
            ),
            GitHubReleaseInfo(
                tagName = "v0.0.3",
                name = "Shrey v0.0.3",
                body = "Stable release notes",
                htmlUrl = "https://github.com/NoWayShub/Shrey/releases/tag/v0.0.3",
                isPrerelease = false,
                publishedAt = "2026-11-20T00:00:00Z",
                assets = listOf(
                    GitHubReleaseAsset(
                        name = "Shrey-v0.0.3.apk",
                        downloadUrl = "https://github.com/NoWayShub/Shrey/releases/download/v0.0.3/Shrey-v0.0.3.apk",
                        sizeBytes = 248_000_000L
                    )
                )
            )
        )
        val ytDlpRelease = GitHubReleaseInfo(
            tagName = "2026.11.15",
            name = "yt-dlp 2026.11.15",
            body = "Extractor fixes",
            htmlUrl = "https://github.com/yt-dlp/yt-dlp/releases/tag/2026.11.15",
            isPrerelease = false,
            publishedAt = "2026-11-15T00:00:00Z",
            assets = emptyList()
        )
        val source = FakeReleaseMetadataSource(
            ytDlpResult = Result.success(ytDlpRelease),
            shreyReleasesResult = Result.success(releases)
        )

        val (manager, _) = buildManager(source = source, installedVersion = "0.0.3-dev")

        // Stable channel selects v0.0.3 (not v0.0.4-beta)
        manager.setUpdateChannel(UpdateChannel.STABLE)
        val stableSnap = manager.checkForUpdates(forceManual = true).getOrThrow()
        assertEquals(UpdateCheckState.UPDATE_AVAILABLE, stableSnap.checkState)
        assertEquals("0.0.3", stableSnap.app.latestVersionName)
        assertTrue(stableSnap.app.updateAvailable)
        assertTrue(stableSnap.engine.updateAvailable)
        assertEquals("2026.11.15", stableSnap.engine.availableVersion)
        assertEquals(EngineCompatibilityStatus.UNSIGNED_UPSTREAM_ONLY, stableSnap.engine.compatibilityStatus)
        assertFalse(stableSnap.engine.canSafelyActivateAtRuntime)
        assertNotNull(stableSnap.lastCheckedAtMillis)

        // Beta channel selects v0.0.4-beta
        manager.setUpdateChannel(UpdateChannel.BETA)
        val betaSnap = manager.checkForUpdates(forceManual = true).getOrThrow()
        assertEquals("0.0.4-beta", betaSnap.app.latestVersionName)
        assertTrue(betaSnap.app.isPrerelease)
    }

    @Test
    fun `up to date check persists timestamp and respects cooldown while manual check overrides`() = runBlocking {
        var clock = 1_750_000_000_000L
        val source = FakeReleaseMetadataSource(
            ytDlpResult = Result.success(
                GitHubReleaseInfo("2026.10.26", "2026.10.26", "", "https://github.com/yt-dlp/yt-dlp/releases/tag/2026.10.26", false, null, emptyList())
            ),
            shreyReleasesResult = Result.success(
                listOf(
                    GitHubReleaseInfo("v0.0.2-beta", "v0.0.2-beta", "", "https://github.com/NoWayShub/Shrey/releases/tag/v0.0.2-beta", true, null, emptyList())
                )
            )
        )
        val (manager, store) = buildManager(source = source, installedVersion = "0.0.3-dev", nowMillis = { clock })

        val first = manager.checkForUpdates(forceManual = true).getOrThrow()
        assertEquals(UpdateCheckState.UP_TO_DATE, first.checkState)
        assertEquals(1, source.ytDlpCallCount.get())

        // Non-manual check inside cooldown skips network call
        clock += 60_000L
        manager.checkForUpdates(forceManual = false, cooldownMillis = 15L * 60L * 1000L).getOrThrow()
        assertEquals(1, source.ytDlpCallCount.get())

        // Manual check bypasses cooldown
        manager.checkForUpdates(forceManual = true).getOrThrow()
        assertEquals(2, source.ytDlpCallCount.get())

        // Auto-install toggle stays safely false when manifest public key is blank
        manager.setAutoInstallEngineUpdates(true)
        assertFalse(store.recordFlow.first().preferences.autoInstallEngineUpdates)
    }

    @Test
    fun `overlapping checks are coalesced and network failures are persisted safely`() = runBlocking {
        val source = FakeReleaseMetadataSource(
            ytDlpResult = Result.failure(IOException("Offline")),
            shreyReleasesResult = Result.failure(IOException("Offline")),
            artificialDelayMs = 150L
        )
        val (manager, _) = buildManager(source = source)

        val firstJob = async { manager.checkForUpdates(forceManual = true) }
        delay(20L)
        val overlappingJob = async { manager.checkForUpdates(forceManual = true) }

        val firstResult = firstJob.await()
        overlappingJob.await()

        assertTrue(firstResult.isFailure)
        assertEquals(1, source.ytDlpCallCount.get())
        assertEquals(UpdateCheckState.FAILED, manager.snapshot.value.checkState)
    }

    @Test
    fun `work manager schedules unique daily periodic update check with network and battery constraints`() {
        UpdateWorkScheduler.scheduleDailyCheck(context)

        val workManager = WorkManager.getInstance(context)
        val infos = workManager.getWorkInfosForUniqueWork(DailyUpdateCheckWorker.UNIQUE_WORK_NAME).get()
        assertEquals(1, infos.size)

        val info = infos.first()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertTrue(info.tags.contains(DailyUpdateCheckWorker.WORK_TAG))
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertTrue(info.constraints.requiresBatteryNotLow())
        assertEquals(TimeUnit.HOURS.toMillis(24), info.periodicityInfo?.repeatIntervalMillis)

        // Scheduling again with KEEP policy does not duplicate
        UpdateWorkScheduler.scheduleDailyCheck(context)
        val afterSecondSchedule = workManager.getWorkInfosForUniqueWork(DailyUpdateCheckWorker.UNIQUE_WORK_NAME).get()
        assertEquals(1, afterSecondSchedule.size)

        // Cancellation transitions unique work cleanly to CANCELLED
        UpdateWorkScheduler.cancelDailyCheck(context)
        val afterCancel = workManager.getWorkInfosForUniqueWork(DailyUpdateCheckWorker.UNIQUE_WORK_NAME).get()
        assertEquals(WorkInfo.State.CANCELLED, afterCancel.first().state)
    }
}
