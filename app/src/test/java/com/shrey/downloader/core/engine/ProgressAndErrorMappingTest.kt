package com.shrey.downloader.core.engine

import com.shrey.downloader.core.utilities.Formatters
import com.shrey.downloader.domain.model.DownloadStage
import com.shrey.downloader.domain.model.ShreyError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ProgressAndErrorMappingTest {

    @Test
    fun `parses download stdout progress speed size and stage transitions`() {
        val downloading = ProgressParser.parseCallback(
            downloadId = "dl-1",
            progressFloat = 42.5f,
            etaSeconds = 9L,
            line = "[download]  42.5% of ~ 50.00MiB at  3.20MiB/s ETA 00:09",
            previousStage = DownloadStage.EXTRACTING
        )
        assertEquals(DownloadStage.DOWNLOADING, downloading.stage)
        assertEquals(42.5f, downloading.progressPercent)
        assertEquals("3.20MiB/s", downloading.speedText)
        assertEquals(9L, downloading.etaSeconds)
        assertTrue((downloading.totalBytes ?: 0L) > 50_000_000L)

        val merging = ProgressParser.parseCallback(
            downloadId = "dl-1",
            progressFloat = 100f,
            etaSeconds = -1L,
            line = "[Merger] Merging formats into \"media_output.mp4\"",
            previousStage = DownloadStage.DOWNLOADING
        )
        assertEquals(DownloadStage.MERGING, merging.stage)

        val converting = ProgressParser.parseCallback(
            downloadId = "dl-1",
            progressFloat = 100f,
            etaSeconds = -1L,
            line = "[ExtractAudio] Destination: media_output.mp3",
            previousStage = DownloadStage.DOWNLOADING
        )
        assertEquals(DownloadStage.CONVERTING, converting.stage)
    }

    @Test
    fun `translates common exceptions into typed ShreyErrors`() {
        assertTrue(ErrorTranslator.translate(SocketTimeoutException("timeout")) is ShreyError.ExtractionTimeout)
        assertTrue(ErrorTranslator.translate(UnknownHostException("no dns")) is ShreyError.NetworkUnavailable)
        assertTrue(
            ErrorTranslator.translate(RuntimeException("WARNING: [youtube] No supported JavaScript runtime could be found"))
                is ShreyError.MissingRuntimeDependency
        )
        assertTrue(
            ErrorTranslator.translate(RuntimeException("ERROR: Sign in to confirm you're not a bot"))
                is ShreyError.AuthenticationRequired
        )
        assertTrue(
            ErrorTranslator.translate(RuntimeException("ERROR: This video is DRM protected"))
                is ShreyError.ContentInaccessible
        )
        assertTrue(
            ErrorTranslator.translate(RuntimeException("ERROR: Video unavailable"))
                is ShreyError.ContentUnavailable
        )
        assertTrue(
            ErrorTranslator.translate(RuntimeException("ERROR: Postprocessing: FFmpeg conversion failed"))
                is ShreyError.ConversionFailed
        )
        assertTrue(
            ErrorTranslator.translate(RuntimeException("ERROR: HTTP Error 429: Too Many Requests"))
                is ShreyError.RateLimited
        )
        assertTrue(
            ErrorTranslator.translate(RuntimeException("WARNING: YouTube is forcing SABR streaming for this client"))
                is ShreyError.ExtractorCompatibilityError
        )
        assertTrue(
            ErrorTranslator.translate(RuntimeException("ERROR: [Errno 28] No space left on device (ENOSPC)"))
                is ShreyError.InsufficientStorage
        )
        assertTrue(
            ErrorTranslator.translate(RuntimeException("Failed to create MediaStore entry: Permission denied"))
                is ShreyError.StorageWriteFailed
        )
    }

    @Test
    fun `classifies confirmed real-device HTTP 403 and SABR log as MediaRequestForbidden`() {
        val realDeviceLog = """
            WARNING: Your yt-dlp version (2025.11.12) is older than 90 days!
            WARNING: Some web client https formats have been skipped as they are missing a URL. YouTube is forcing SABR streaming for this client.
            ERROR: unable to download video data: HTTP Error 403: Forbidden
        """.trimIndent()

        val translated = ErrorTranslator.translateMessage(realDeviceLog)
        assertTrue(translated is ShreyError.MediaRequestForbidden)
        assertFalse(translated is ShreyError.ExtractorFailure)
        assertTrue(translated.title.contains("403"))
        assertTrue(translated.userMessage.contains("403"))
    }

    @Test
    fun `redacts sensitive tokens and local paths from diagnostics`() {
        val raw = "Failed at /data/user/0/com.shrey.downloader/cache/out.mp4 with authorization=BearerSecret123 cookie=abc"
        val redacted = Formatters.redactTechnicalDiagnostics(raw).orEmpty()
        assertFalse(redacted.contains("BearerSecret123"))
        assertFalse(redacted.contains("/data/user/0"))
        assertTrue(redacted.contains("[REDACTED]"))
        assertTrue(redacted.contains("[LOCAL_PATH]"))
    }
}

