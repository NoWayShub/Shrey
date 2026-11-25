package com.shrey.downloader.core.update

import com.shrey.downloader.core.engine.YtDlpArchiveInspector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SafeEngineUpdaterAndRollbackTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createMockYtDlpZip(
        target: File,
        ytDlpVersion: String,
        ejsVersion: String
    ): File {
        target.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(target)).use { zos ->
            zos.putNextEntry(ZipEntry("yt_dlp/version.py"))
            zos.write("__version__ = '$ytDlpVersion'\n".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("yt_dlp_ejs/_version.py"))
            zos.write("__version__ = version = '$ejsVersion'\n".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return target
    }

    private fun buildManifestForArchive(
        archive: File,
        ytDlpVersion: String,
        ejsVersion: String,
        overrideSha256: String? = null
    ): SignedEngineManifest = SignedEngineManifest(
        schemaVersion = 1,
        ytDlpVersion = ytDlpVersion,
        ejsVersion = ejsVersion,
        minPythonVersion = "3.11",
        minQuickJsVersion = "2025-04-26",
        downloadUrl = "https://github.com/yt-dlp/yt-dlp/releases/download/$ytDlpVersion/yt-dlp",
        sha256 = overrideSha256 ?: SafeEngineUpdater.computeSha256(archive),
        sizeBytes = archive.length(),
        publishedAtIso = "2026-11-20T00:00:00Z"
    )

    @Test
    fun `staged verified engine activates atomically and preserves backup`() = runBlocking {
        val activeFile = File(tempFolder.root, "yt-dlp/yt-dlp")
        val stagingDir = File(tempFolder.root, "staging")
        val backupDir = File(tempFolder.root, "backup")
        val candidateSource = File(tempFolder.root, "remote_candidate.zip")

        createMockYtDlpZip(activeFile, "2026.10.26", "0.8.0")
        createMockYtDlpZip(candidateSource, "2026.11.20", "0.8.1")
        val manifest = buildManifestForArchive(candidateSource, "2026.11.20", "0.8.1")

        val updater = SafeEngineUpdater(
            activeEngineFile = activeFile,
            stagingDir = stagingDir,
            backupDir = backupDir,
            isDownloadActive = { false },
            downloadPayload = { _, dest -> candidateSource.copyTo(dest, overwrite = true) },
            initializationProbe = { file ->
                YtDlpArchiveInspector.inspect(file).ytDlpVersion == "2026.11.20"
            }
        )

        val result = updater.installVerifiedUpdate(manifest)
        assertTrue("Expected Activated, got $result", result is EngineActivationResult.Activated)
        val activated = result as EngineActivationResult.Activated
        assertEquals("2026.11.20", activated.newVersion)
        assertEquals("0.8.1", activated.ejsVersion)
        assertEquals("2026.10.26", activated.previousVersion)

        // Verify active engine is now 2026.11.20 and backup preserves 2026.10.26
        assertEquals("2026.11.20", YtDlpArchiveInspector.inspect(activeFile).ytDlpVersion)
        assertEquals("2026.10.26", YtDlpArchiveInspector.inspect(File(backupDir, "yt-dlp.lkg")).ytDlpVersion)
        assertFalse(File(backupDir, "activation_in_progress.marker").exists())
    }

    @Test
    fun `engine update is deferred while a download is active without touching active binary`() = runBlocking {
        val activeFile = File(tempFolder.root, "yt-dlp/yt-dlp")
        val stagingDir = File(tempFolder.root, "staging")
        val backupDir = File(tempFolder.root, "backup")
        val candidateSource = File(tempFolder.root, "remote_candidate.zip")

        createMockYtDlpZip(activeFile, "2026.10.26", "0.8.0")
        createMockYtDlpZip(candidateSource, "2026.11.20", "0.8.1")
        val manifest = buildManifestForArchive(candidateSource, "2026.11.20", "0.8.1")

        var downloadCalled = false
        val updater = SafeEngineUpdater(
            activeEngineFile = activeFile,
            stagingDir = stagingDir,
            backupDir = backupDir,
            isDownloadActive = { true },
            downloadPayload = { _, _ -> downloadCalled = true },
            initializationProbe = { true }
        )

        val result = updater.installVerifiedUpdate(manifest)
        assertTrue(result is EngineActivationResult.Deferred)
        assertFalse(downloadCalled)
        assertEquals("2026.10.26", YtDlpArchiveInspector.inspect(activeFile).ytDlpVersion)
    }

    @Test
    fun `sha256 mismatch or internal version mismatch rejects update and preserves working engine`() = runBlocking {
        val activeFile = File(tempFolder.root, "yt-dlp/yt-dlp")
        val stagingDir = File(tempFolder.root, "staging")
        val backupDir = File(tempFolder.root, "backup")
        val candidateSource = File(tempFolder.root, "remote_candidate.zip")

        createMockYtDlpZip(activeFile, "2026.10.26", "0.8.0")
        createMockYtDlpZip(candidateSource, "2026.11.20", "0.8.1")

        // Case 1: SHA-256 mismatch
        val badHashManifest = buildManifestForArchive(
            candidateSource,
            "2026.11.20",
            "0.8.1",
            overrideSha256 = "0".repeat(64)
        )
        val updater = SafeEngineUpdater(
            activeEngineFile = activeFile,
            stagingDir = stagingDir,
            backupDir = backupDir,
            isDownloadActive = { false },
            downloadPayload = { _, dest -> candidateSource.copyTo(dest, overwrite = true) },
            initializationProbe = { true }
        )

        val hashResult = updater.installVerifiedUpdate(badHashManifest)
        assertTrue(hashResult is EngineActivationResult.Rejected)
        assertEquals("2026.10.26", YtDlpArchiveInspector.inspect(activeFile).ytDlpVersion)

        // Case 2: Internal archive yt-dlp version does not match manifest claim
        val spoofedManifest = buildManifestForArchive(candidateSource, "2026.12.01", "0.8.1")
        val versionMismatchResult = updater.installVerifiedUpdate(spoofedManifest)
        assertTrue(versionMismatchResult is EngineActivationResult.Rejected)
        assertEquals("2026.10.26", YtDlpArchiveInspector.inspect(activeFile).ytDlpVersion)
    }

    @Test
    fun `failed post-swap initialization probe automatically rolls back to previous working engine`() = runBlocking {
        val activeFile = File(tempFolder.root, "yt-dlp/yt-dlp")
        val stagingDir = File(tempFolder.root, "staging")
        val backupDir = File(tempFolder.root, "backup")
        val candidateSource = File(tempFolder.root, "remote_candidate.zip")

        createMockYtDlpZip(activeFile, "2026.10.26", "0.8.0")
        createMockYtDlpZip(candidateSource, "2026.11.20", "0.8.1")
        val manifest = buildManifestForArchive(candidateSource, "2026.11.20", "0.8.1")

        val updater = SafeEngineUpdater(
            activeEngineFile = activeFile,
            stagingDir = stagingDir,
            backupDir = backupDir,
            isDownloadActive = { false },
            downloadPayload = { _, dest -> candidateSource.copyTo(dest, overwrite = true) },
            initializationProbe = { false } // Simulate init crash/failure on new engine
        )

        val result = updater.installVerifiedUpdate(manifest)
        assertTrue("Expected RolledBack, got $result", result is EngineActivationResult.RolledBack)
        val rolledBack = result as EngineActivationResult.RolledBack
        assertEquals("2026.10.26", rolledBack.restoredVersion)

        // Active engine must be restored cleanly to 2026.10.26
        val activeInfo = YtDlpArchiveInspector.inspect(activeFile)
        assertEquals("2026.10.26", activeInfo.ytDlpVersion)
        assertEquals("0.8.0", activeInfo.ejsVersion)
        assertFalse(File(backupDir, "activation_in_progress.marker").exists())
    }

    @Test
    fun `startup recovery restores last-known-good backup after simulated mid-activation crash`() {
        val activeFile = File(tempFolder.root, "yt-dlp/yt-dlp")
        val stagingDir = File(tempFolder.root, "staging")
        val backupDir = File(tempFolder.root, "backup")
        backupDir.mkdirs()
        stagingDir.mkdirs()
        activeFile.parentFile?.mkdirs()

        // Simulate crash mid-swap: corrupt active engine + leftover marker + valid backup
        createMockYtDlpZip(File(backupDir, "yt-dlp.lkg"), "2026.10.26", "0.8.0")
        activeFile.writeBytes(byteArrayOf(0x00, 0x01, 0x02)) // Truncated/corrupted active file
        File(backupDir, "activation_in_progress.marker").writeText("2026.11.20")
        File(stagingDir, "leftover.tmp").writeText("partial")

        val updater = SafeEngineUpdater(
            activeEngineFile = activeFile,
            stagingDir = stagingDir,
            backupDir = backupDir,
            isDownloadActive = { false },
            downloadPayload = { _, _ -> },
            initializationProbe = { true }
        )

        val recoveredVersion = updater.recoverInterruptedUpdateIfNeeded()
        assertEquals("2026.10.26", recoveredVersion)
        assertEquals("2026.10.26", YtDlpArchiveInspector.inspect(activeFile).ytDlpVersion)
        assertFalse(File(backupDir, "activation_in_progress.marker").exists())
        assertTrue(stagingDir.listFiles().isNullOrEmpty())
    }
}
