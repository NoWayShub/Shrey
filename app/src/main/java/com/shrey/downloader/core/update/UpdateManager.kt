package com.shrey.downloader.core.update

import android.content.Context
import com.example.BuildConfig
import com.shrey.downloader.core.engine.EngineCoordinator
import com.shrey.downloader.core.notifications.DownloadNotificationManager
import com.shrey.downloader.data.local.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class UpdateManager(
    private val context: Context,
    private val updateStore: UpdateStore,
    private val settingsDataStore: SettingsDataStore,
    private val metadataSource: ReleaseMetadataSource,
    private val manifestVerifier: UpdateManifestVerifier,
    private val apkVerifier: ApkUpdateVerifier,
    private val notificationManager: DownloadNotificationManager,
    private val engineCoordinator: EngineCoordinator,
    private val installedAppVersionName: String = BuildConfig.VERSION_NAME,
    private val installedAppVersionCode: Long = BuildConfig.VERSION_CODE.toLong(),
    private val nowMillisProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkMutex = Mutex()
    private val apkDownloadInProgress = AtomicBoolean(false)

    private val _snapshot = MutableStateFlow(buildInitialSnapshot())
    val snapshot: StateFlow<UpdateSnapshot> = _snapshot.asStateFlow()

    init {
        scope.launch {
            updateStore.recordFlow.collect { record ->
                _snapshot.value = buildSnapshotFromRecord(
                    record = record,
                    previous = _snapshot.value,
                    preserveCheckingState = checkMutex.isLocked
                )
            }
        }
    }

    /**
     * Runs a single-flight update check across yt-dlp, FFmpeg compatibility, and Shrey GitHub releases.
     *
     * @param forceManual If false, skips redundant checks within [cooldownMillis].
     * @param notifyIfAvailable If true, posts a deduplicated notification when a new update is found.
     */
    suspend fun checkForUpdates(
        forceManual: Boolean = false,
        notifyIfAvailable: Boolean = false,
        cooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS
    ): Result<UpdateSnapshot> {
        // Prevent overlapping update checks
        if (!checkMutex.tryLock()) {
            return Result.success(_snapshot.value)
        }
        return try {
            val currentRecord = updateStore.recordFlow.first()
            val now = nowMillisProvider()
            val lastChecked = currentRecord.lastCheckedAtMillis
            if (!forceManual && lastChecked != null && (now - lastChecked) < cooldownMillis &&
                currentRecord.checkState != UpdateCheckState.FAILED
            ) {
                return Result.success(_snapshot.value)
            }

            _snapshot.value = _snapshot.value.copy(
                checkState = UpdateCheckState.CHECKING,
                lastErrorMessage = null
            )

            val ytDlpResult = metadataSource.fetchLatestYtDlpRelease()
            val shreyReleasesResult = metadataSource.fetchShreyReleases()

            if (ytDlpResult.isFailure && shreyReleasesResult.isFailure) {
                val errMsg = shreyReleasesResult.exceptionOrNull()?.message
                    ?: ytDlpResult.exceptionOrNull()?.message
                    ?: "Unable to reach GitHub release metadata."
                updateStore.saveFailedCheck(errMsg)
                val updated = _snapshot.value.copy(
                    checkState = UpdateCheckState.FAILED,
                    lastErrorMessage = errMsg
                )
                _snapshot.value = updated
                return Result.failure(IllegalStateException(errMsg))
            }

            val ytDlpRelease = ytDlpResult.getOrNull()
            val selectedAppRelease = shreyReleasesResult.getOrNull()?.let { releases ->
                selectLatestReleaseForChannel(releases, currentRecord.preferences.channel)
            }
            val apkAsset = selectedAppRelease?.assets?.firstOrNull {
                it.name.endsWith(".apk", ignoreCase = true)
            }

            val installedEngine = resolveInstalledEngineVersion(currentRecord)
            val availableYtDlp = ytDlpRelease?.tagName?.takeIf { it.isNotBlank() }
            val ytDlpNewer = availableYtDlp != null &&
                UpdateVersionComparator.isYtDlpVersionNewer(availableYtDlp, installedEngine)

            val latestAppVer = selectedAppRelease?.tagName?.let {
                UpdateVersionComparator.normalizeVersionLabel(it)
            }
            val appNewer = latestAppVer != null &&
                UpdateVersionComparator.isShreyVersionNewer(latestAppVer, installedAppVersionName)

            val checkState = if (ytDlpNewer || appNewer) {
                UpdateCheckState.UPDATE_AVAILABLE
            } else {
                UpdateCheckState.UP_TO_DATE
            }

            updateStore.saveSuccessfulCheck(
                checkedAtMillis = now,
                state = checkState,
                availableYtDlpVersion = availableYtDlp,
                availableYtDlpUrl = ytDlpRelease?.htmlUrl,
                latestAppRelease = selectedAppRelease,
                apkAsset = apkAsset
            )

            val freshRecord = updateStore.recordFlow.first()
            val freshSnapshot = buildSnapshotFromRecord(
                record = freshRecord,
                previous = _snapshot.value,
                preserveCheckingState = false
            )
            _snapshot.value = freshSnapshot

            if (notifyIfAvailable && freshRecord.preferences.notifyOnUpdates) {
                maybeNotifyUpdateAvailable(freshRecord, freshSnapshot)
            }

            Result.success(freshSnapshot)
        } finally {
            checkMutex.unlock()
        }
    }

    /**
     * Downloads the latest Shrey APK into an isolated cache folder, verifies package name,
     * versionCode, minSdk, ABI, ZIP integrity, and signing certificate identity, then exports
     * the verified APK into Downloads/Shrey for user-confirmed installation.
     */
    suspend fun downloadAndVerifyAppUpdate(): Result<String> {
        val currentApp = _snapshot.value.app
        val apkUrl = currentApp.apkDownloadUrl
            ?: return Result.failure(IllegalStateException("No APK asset URL available for this release."))

        if (!apkDownloadInProgress.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("An APK download is already in progress."))
        }

        val stagingDir = File(context.cacheDir, "shrey_apk_updates")
        val targetName = currentApp.apkFileName ?: "Shrey-${currentApp.latestVersionName ?: "update"}.apk"
        val stagedApk = File(stagingDir, targetName)

        return try {
            _snapshot.value = _snapshot.value.copy(
                app = currentApp.copy(
                    isDownloadingApk = true,
                    apkDownloadProgress = 0f,
                    verificationStatusMessage = "Downloading official APK…",
                    verificationError = null,
                    verifiedApkUri = null
                )
            )

            val downloadResult = metadataSource.downloadFile(
                url = apkUrl,
                allowedHosts = UpdateTrustConfig.ALLOWED_SHREY_APK_HOSTS,
                destination = stagedApk,
                onProgress = { progress ->
                    _snapshot.value = _snapshot.value.copy(
                        app = _snapshot.value.app.copy(
                            isDownloadingApk = true,
                            apkDownloadProgress = progress
                        )
                    )
                }
            )

            if (downloadResult.isFailure) {
                val err = downloadResult.exceptionOrNull()?.message ?: "APK download failed."
                _snapshot.value = _snapshot.value.copy(
                    app = _snapshot.value.app.copy(
                        isDownloadingApk = false,
                        apkDownloadProgress = null,
                        verificationError = err,
                        verificationStatusMessage = null
                    )
                )
                return Result.failure(IllegalStateException(err))
            }

            _snapshot.value = _snapshot.value.copy(
                app = _snapshot.value.app.copy(
                    verificationStatusMessage = "Verifying package, ABI & signing certificate…"
                )
            )

            when (val verify = apkVerifier.verifyApkFile(context, stagedApk, installedAppVersionCode)) {
                is ApkVerificationResult.Rejected -> {
                    stagedApk.delete()
                    _snapshot.value = _snapshot.value.copy(
                        app = _snapshot.value.app.copy(
                            isDownloadingApk = false,
                            apkDownloadProgress = null,
                            verificationStatusMessage = null,
                            verificationError = verify.reason
                        )
                    )
                    Result.failure(SecurityException(verify.reason))
                }

                is ApkVerificationResult.Verified -> {
                    val exported = apkVerifier.exportVerifiedApkToDownloads(context, stagedApk, targetName)
                    stagedApk.delete()
                    exported.fold(
                        onSuccess = { uri ->
                            val msg = "Verified ${verify.metadata.versionName ?: targetName} (signing match). Saved to Downloads/Shrey."
                            _snapshot.value = _snapshot.value.copy(
                                app = _snapshot.value.app.copy(
                                    isDownloadingApk = false,
                                    apkDownloadProgress = 1f,
                                    verifiedApkUri = uri.toString(),
                                    verificationStatusMessage = msg,
                                    verificationError = null
                                )
                            )
                            Result.success(uri.toString())
                        },
                        onFailure = { exportErr ->
                            val msg = "APK verified, but could not save to Downloads/Shrey: ${exportErr.message}"
                            _snapshot.value = _snapshot.value.copy(
                                app = _snapshot.value.app.copy(
                                    isDownloadingApk = false,
                                    apkDownloadProgress = null,
                                    verificationStatusMessage = null,
                                    verificationError = msg
                                )
                            )
                            Result.failure(exportErr)
                        }
                    )
                }
            }
        } finally {
            apkDownloadInProgress.set(false)
        }
    }

    suspend fun setAutoCheckEnabled(enabled: Boolean) {
        updateStore.setAutoCheckEnabled(enabled)
        if (enabled) {
            UpdateWorkScheduler.scheduleDailyCheck(context)
        } else {
            UpdateWorkScheduler.cancelDailyCheck(context)
        }
    }

    suspend fun setAutoInstallEngineUpdates(enabled: Boolean) {
        // Only persist true when a maintainer signing trust root is configured; otherwise keep safely OFF.
        val effective = enabled && manifestVerifier.isTrustRootConfigured
        updateStore.setAutoInstallEngineUpdates(effective)
    }

    suspend fun setNotifyOnUpdates(enabled: Boolean) {
        updateStore.setNotifyOnUpdates(enabled)
    }

    suspend fun setUpdateChannel(channel: UpdateChannel) {
        updateStore.setUpdateChannel(channel)
    }

    fun selectLatestReleaseForChannel(
        releases: List<GitHubReleaseInfo>,
        channel: UpdateChannel
    ): GitHubReleaseInfo? {
        val eligible = when (channel) {
            UpdateChannel.STABLE -> releases.filter { !it.isPrerelease }
            UpdateChannel.BETA -> releases
        }
        return eligible.maxWithOrNull { a, b ->
            UpdateVersionComparator.compareShreyVersions(a.tagName, b.tagName) ?: 0
        }
    }

    private suspend fun maybeNotifyUpdateAvailable(
        record: PersistedUpdateRecord,
        snapshot: UpdateSnapshot
    ) {
        val settings = settingsDataStore.settingsFlow.first()
        if (!settings.notificationsEnabled) return

        val newAppVer = snapshot.app.latestVersionName?.takeIf {
            snapshot.app.updateAvailable && it != record.lastNotifiedAppVersion
        }
        val newYtDlpVer = snapshot.engine.availableVersion?.takeIf {
            snapshot.engine.updateAvailable && it != record.lastNotifiedYtDlpVersion
        }
        if (newAppVer == null && newYtDlpVer == null) return

        val summary = buildList {
            if (newAppVer != null) add("Shrey $newAppVer")
            if (newYtDlpVer != null) add("yt-dlp $newYtDlpVer")
        }.joinToString(" · ")

        notificationManager.showUpdateAvailableNotification(
            summary = "$summary available — open Settings to review",
            notificationsEnabled = settings.notificationsEnabled
        )
        updateStore.markNotified(appVersion = newAppVer, ytDlpVersion = newYtDlpVer)
    }

    private fun resolveInstalledEngineVersion(record: PersistedUpdateRecord): String {
        val liveState = engineCoordinator.state.value
        val fromCoordinator = (liveState as? com.shrey.downloader.domain.model.EngineState.Ready)
            ?.diagnostics?.ytDlpVersion
            ?.substringBefore(" · ")
            ?.trim()
        return fromCoordinator ?: record.installedEngineVersion
    }

    private fun buildInitialSnapshot(): UpdateSnapshot {
        return UpdateSnapshot(
            engine = EngineUpdateStatus(
                installedVersion = EngineCoordinator.BUNDLED_YT_DLP_VERSION,
                lastKnownGoodVersion = EngineCoordinator.BUNDLED_YT_DLP_VERSION,
                installedEjsVersion = UpdateTrustConfig.BUNDLED_EJS_VERSION,
                installedPythonVersion = UpdateTrustConfig.BUNDLED_PYTHON_VERSION,
                installedQuickJsVersion = UpdateTrustConfig.BUNDLED_QUICKJS_VERSION,
                compatibilityStatus = EngineCompatibilityStatus.COMPATIBLE,
                compatibilityDetail = "Bundled yt-dlp ${EngineCoordinator.BUNDLED_YT_DLP_VERSION} verified for Python ${UpdateTrustConfig.BUNDLED_PYTHON_VERSION} and QuickJS ${UpdateTrustConfig.BUNDLED_QUICKJS_VERSION}."
            ),
            ffmpeg = FfmpegUpdateStatus(
                bundledVersion = UpdateTrustConfig.BUNDLED_FFMPEG_VERSION,
                supportedVersion = UpdateTrustConfig.BUNDLED_FFMPEG_VERSION
            ),
            app = AppUpdateStatus(
                installedVersionName = installedAppVersionName,
                installedVersionCode = installedAppVersionCode
            )
        )
    }

    private fun buildSnapshotFromRecord(
        record: PersistedUpdateRecord,
        previous: UpdateSnapshot,
        preserveCheckingState: Boolean = false
    ): UpdateSnapshot {
        val installedEngine = resolveInstalledEngineVersion(record)
        val availYtDlp = record.availableYtDlpVersion
        val ytDlpNewer = availYtDlp != null &&
            UpdateVersionComparator.isYtDlpVersionNewer(availYtDlp, installedEngine)

        val (compatStatus, compatDetail) = when {
            !ytDlpNewer -> EngineCompatibilityStatus.COMPATIBLE to
                "Running verified bundled yt-dlp $installedEngine (Python ${UpdateTrustConfig.BUNDLED_PYTHON_VERSION}, QuickJS ${UpdateTrustConfig.BUNDLED_QUICKJS_VERSION})."
            !manifestVerifier.isTrustRootConfigured -> EngineCompatibilityStatus.UNSIGNED_UPSTREAM_ONLY to
                "Upstream yt-dlp $availYtDlp detected. Automatic runtime install is disabled until a maintainer-signed manifest is published; update via signed Shrey APK."
            else -> EngineCompatibilityStatus.COMPATIBLE to
                "Compatible update $availYtDlp ready for signed manifest verification."
        }

        val appNewer = record.latestAppVersion != null &&
            UpdateVersionComparator.isShreyVersionNewer(record.latestAppVersion, installedAppVersionName)

        val preserveChecking = preserveCheckingState && previous.checkState == UpdateCheckState.CHECKING
        val effectiveState = when {
            preserveChecking -> UpdateCheckState.CHECKING
            record.checkState == UpdateCheckState.FAILED -> UpdateCheckState.FAILED
            record.lastCheckedAtMillis == null -> UpdateCheckState.IDLE
            ytDlpNewer || appNewer -> UpdateCheckState.UPDATE_AVAILABLE
            else -> UpdateCheckState.UP_TO_DATE
        }

        return UpdateSnapshot(
            preferences = record.preferences,
            lastCheckedAtMillis = record.lastCheckedAtMillis,
            checkState = effectiveState,
            lastErrorMessage = record.lastErrorMessage,
            engine = EngineUpdateStatus(
                installedVersion = installedEngine,
                lastKnownGoodVersion = record.lastKnownGoodEngineVersion,
                installedEjsVersion = UpdateTrustConfig.BUNDLED_EJS_VERSION,
                installedPythonVersion = UpdateTrustConfig.BUNDLED_PYTHON_VERSION,
                installedQuickJsVersion = UpdateTrustConfig.BUNDLED_QUICKJS_VERSION,
                availableVersion = availYtDlp,
                availableReleaseUrl = record.availableYtDlpUrl ?: UpdateTrustConfig.YTDLP_RELEASES_WEB_URL,
                compatibilityStatus = compatStatus,
                compatibilityDetail = compatDetail,
                updateAvailable = ytDlpNewer,
                signedManifestVerified = false,
                canSafelyActivateAtRuntime = false,
                lastActivationMessage = previous.engine.lastActivationMessage
            ),
            ffmpeg = FfmpegUpdateStatus(
                bundledVersion = UpdateTrustConfig.BUNDLED_FFMPEG_VERSION,
                supportedVersion = UpdateTrustConfig.BUNDLED_FFMPEG_VERSION,
                availableViaAppUpdate = appNewer,
                note = if (appNewer) {
                    "Newer Shrey release available — native FFmpeg updates are delivered only via signed Shrey APKs."
                } else {
                    "Bundled FFmpeg ${UpdateTrustConfig.BUNDLED_FFMPEG_VERSION} matches supported baseline. Updated via signed Shrey APKs."
                }
            ),
            app = AppUpdateStatus(
                installedVersionName = installedAppVersionName,
                installedVersionCode = installedAppVersionCode,
                latestVersionName = record.latestAppVersion,
                latestTagName = record.latestAppTag,
                isPrerelease = record.latestAppIsPrerelease,
                updateAvailable = appNewer,
                releaseNotes = record.latestAppNotes,
                releasePageUrl = record.latestAppReleaseUrl ?: UpdateTrustConfig.SHREY_RELEASES_WEB_URL,
                apkDownloadUrl = record.latestAppApkUrl,
                apkFileName = record.latestAppApkName,
                apkSizeBytes = record.latestAppApkSize,
                isDownloadingApk = previous.app.isDownloadingApk,
                apkDownloadProgress = previous.app.apkDownloadProgress,
                verifiedApkUri = previous.app.verifiedApkUri,
                verificationStatusMessage = previous.app.verificationStatusMessage,
                verificationError = previous.app.verificationError
            )
        )
    }

    companion object {
        const val DEFAULT_COOLDOWN_MILLIS = 15L * 60L * 1000L // 15 minutes for automatic/error-triggered checks
    }
}
