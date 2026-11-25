package com.shrey.downloader.core.update

import com.shrey.downloader.core.engine.YtDlpArchiveInspector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

sealed interface EngineActivationResult {
    data class Activated(
        val newVersion: String,
        val ejsVersion: String?,
        val previousVersion: String?
    ) : EngineActivationResult

    data class RolledBack(
        val restoredVersion: String?,
        val reason: String
    ) : EngineActivationResult

    data class Rejected(
        val reason: String
    ) : EngineActivationResult

    data class Deferred(
        val reason: String
    ) : EngineActivationResult
}

/**
 * Security-first transactional engine updater for yt-dlp zipimport archives.
 *
 * Guarantees:
 * - Never activates an update without a cryptographically verified [SignedEngineManifest].
 * - Never replaces the active engine while any download is running.
 * - Downloads to an isolated staging directory and verifies byte size, SHA-256 hash,
 *   ZIP archive structure, and exact internal yt_dlp/yt_dlp_ejs version strings before touching
 *   the working engine.
 * - Preserves a last-known-good backup copy before atomic swap.
 * - Runs a post-swap initialization probe and automatically rolls back if initialization fails.
 * - Detects and recovers cleanly from interrupted updates on next app startup via marker file.
 */
class SafeEngineUpdater(
    private val activeEngineFile: File,
    private val stagingDir: File,
    private val backupDir: File,
    private val isDownloadActive: () -> Boolean,
    private val downloadPayload: suspend (url: String, destination: File) -> Unit,
    private val initializationProbe: suspend (engineFile: File) -> Boolean
) {
    private val activationMutex = Mutex()
    private val backupFile: File get() = File(backupDir, BACKUP_FILE_NAME)
    private val markerFile: File get() = File(backupDir, ACTIVATION_MARKER_NAME)

    /**
     * Recovers cleanly if the process crashed or was killed mid-activation.
     * Restores the last-known-good backup if an activation marker is left behind or if the active
     * engine file is missing/corrupted while a valid backup exists.
     */
    fun recoverInterruptedUpdateIfNeeded(): String? {
        stagingDir.listFiles()?.forEach { it.deleteRecursively() }

        val activeInfo = YtDlpArchiveInspector.inspect(activeEngineFile)
        val hasMarker = markerFile.exists()
        val activeCorrupt = activeEngineFile.exists() && activeInfo.ytDlpVersion == null
        val activeMissing = !activeEngineFile.exists()

        if ((hasMarker || activeCorrupt || activeMissing) && backupFile.exists()) {
            val backupInfo = YtDlpArchiveInspector.inspect(backupFile)
            if (backupInfo.ytDlpVersion != null) {
                replaceFileSafely(source = backupFile, target = activeEngineFile)
                markerFile.delete()
                return backupInfo.ytDlpVersion
            }
        }
        if (hasMarker) {
            markerFile.delete()
        }
        return activeInfo.ytDlpVersion
    }

    suspend fun installVerifiedUpdate(
        manifest: SignedEngineManifest
    ): EngineActivationResult = activationMutex.withLock {
        withContext(Dispatchers.IO) {
            if (isDownloadActive()) {
                return@withContext EngineActivationResult.Deferred(
                    "Engine update deferred while a media download is active."
                )
            }

            if (!UpdateManifestVerifier.isAllowedDownloadUrl(manifest.downloadUrl, UpdateTrustConfig.ALLOWED_ENGINE_HOSTS)) {
                return@withContext EngineActivationResult.Rejected("Untrusted engine download URL.")
            }

            stagingDir.mkdirs()
            backupDir.mkdirs()
            val stagedFile = File(stagingDir, "ytdlp-${manifest.ytDlpVersion}.tmp")
            stagedFile.delete()

            val previousInfo = YtDlpArchiveInspector.inspect(activeEngineFile)

            try {
                downloadPayload(manifest.downloadUrl, stagedFile)

                if (!stagedFile.exists() || stagedFile.length() != manifest.sizeBytes) {
                    return@withContext EngineActivationResult.Rejected(
                        "Downloaded archive size (${stagedFile.length()}) does not match signed manifest (${manifest.sizeBytes})."
                    )
                }

                val actualSha256 = computeSha256(stagedFile)
                if (!actualSha256.equals(manifest.sha256.trim(), ignoreCase = true)) {
                    return@withContext EngineActivationResult.Rejected(
                        "Downloaded archive SHA-256 mismatch."
                    )
                }

                val stagedInfo = YtDlpArchiveInspector.inspect(stagedFile)
                if (stagedInfo.ytDlpVersion != manifest.ytDlpVersion) {
                    return@withContext EngineActivationResult.Rejected(
                        "Archive internal yt-dlp version (${stagedInfo.ytDlpVersion}) does not match signed manifest (${manifest.ytDlpVersion})."
                    )
                }
                if (stagedInfo.ejsVersion != manifest.ejsVersion) {
                    return@withContext EngineActivationResult.Rejected(
                        "Archive internal yt-dlp-ejs version (${stagedInfo.ejsVersion}) does not match signed manifest (${manifest.ejsVersion})."
                    )
                }

                // Re-check active download guard right before swap
                if (isDownloadActive()) {
                    return@withContext EngineActivationResult.Deferred(
                        "Download started while staging update; activation deferred."
                    )
                }

                // Preserve last-known-good binary before replacing active file
                if (activeEngineFile.exists() && previousInfo.ytDlpVersion != null) {
                    replaceFileSafely(source = activeEngineFile, target = backupFile)
                }

                // Mark activation in progress so crashes mid-swap trigger automatic recovery
                markerFile.writeText(manifest.ytDlpVersion)

                activeEngineFile.parentFile?.mkdirs()
                replaceFileSafely(source = stagedFile, target = activeEngineFile)

                val probeSucceeded = runCatching { initializationProbe(activeEngineFile) }.getOrDefault(false)
                if (!probeSucceeded) {
                    val restoredVersion = rollbackFromBackup()
                    markerFile.delete()
                    return@withContext EngineActivationResult.RolledBack(
                        restoredVersion = restoredVersion,
                        reason = "New engine failed initialization verification; restored previous working version."
                    )
                }

                markerFile.delete()
                EngineActivationResult.Activated(
                    newVersion = stagedInfo.ytDlpVersion,
                    ejsVersion = stagedInfo.ejsVersion,
                    previousVersion = previousInfo.ytDlpVersion
                )
            } catch (t: Throwable) {
                val restoredVersion = if (markerFile.exists()) {
                    rollbackFromBackup().also { markerFile.delete() }
                } else {
                    previousInfo.ytDlpVersion
                }
                if (markerFile.exists()) markerFile.delete()
                EngineActivationResult.RolledBack(
                    restoredVersion = restoredVersion,
                    reason = "Engine activation interrupted (${t.message ?: t::class.java.simpleName}); preserved previous working engine."
                )
            } finally {
                stagedFile.delete()
            }
        }
    }

    private fun rollbackFromBackup(): String? {
        if (!backupFile.exists()) return null
        val backupInfo = YtDlpArchiveInspector.inspect(backupFile)
        if (backupInfo.ytDlpVersion != null) {
            replaceFileSafely(source = backupFile, target = activeEngineFile)
        }
        return backupInfo.ytDlpVersion
    }

    private fun replaceFileSafely(source: File, target: File) {
        target.parentFile?.mkdirs()
        val tempInTargetDir = File(target.parentFile, "${target.name}.swap")
        source.inputStream().use { input ->
            tempInTargetDir.outputStream().use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        if (!tempInTargetDir.renameTo(target)) {
            if (target.exists()) target.delete()
            check(tempInTargetDir.renameTo(target)) {
                "Failed to atomically move ${tempInTargetDir.name} into ${target.name}"
            }
        }
    }

    companion object {
        private const val BACKUP_FILE_NAME = "yt-dlp.lkg"
        private const val ACTIVATION_MARKER_NAME = "activation_in_progress.marker"

        fun computeSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
