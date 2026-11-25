package com.shrey.downloader.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class YtDlpArchiveInspectorTest {

    @Test
    fun `bundled raw ytdlp archive matches expected 2026_10_26 release and ejs 0_8_0`() {
        val candidates = listOf(
            File("src/main/res/raw/ytdlp"),
            File("app/src/main/res/raw/ytdlp")
        )
        val bundledFile = candidates.firstOrNull { it.exists() }
        assertTrue("Expected bundled res/raw/ytdlp archive to exist", bundledFile != null)

        val info = YtDlpArchiveInspector.inspect(bundledFile!!)
        assertEquals(EngineCoordinator.BUNDLED_YT_DLP_VERSION, info.ytDlpVersion)
        assertEquals("2026.10.26", info.ytDlpVersion)
        assertEquals("0.8.0", info.ejsVersion)
    }

    @Test
    fun `compares date-based yt-dlp versions accurately`() {
        assertTrue(YtDlpArchiveInspector.isVersionOlderThan("2025.11.12", "2026.10.26"))
        assertTrue(YtDlpArchiveInspector.isVersionOlderThan("2026.10.25", "2026.10.26"))
        assertTrue(YtDlpArchiveInspector.isVersionOlderThan(null, "2026.10.26"))
        assertTrue(YtDlpArchiveInspector.isVersionOlderThan("", "2026.10.26"))
        assertTrue(YtDlpArchiveInspector.isVersionOlderThan("invalid", "2026.10.26"))

        assertFalse(YtDlpArchiveInspector.isVersionOlderThan("2026.10.26", "2026.10.26"))
        assertFalse(YtDlpArchiveInspector.isVersionOlderThan("2026.10.27", "2026.10.26"))
        assertFalse(YtDlpArchiveInspector.isVersionOlderThan("2026.11.20.232810", "2026.10.26"))
        assertFalse(YtDlpArchiveInspector.isVersionOlderThan("2027.01.01", "2026.10.26"))
    }

    @Test
    fun `handles missing archive gracefully without throwing`() {
        val info = YtDlpArchiveInspector.inspect(File("nonexistent_ytdlp_archive.zip"))
        assertNull(info.ytDlpVersion)
        assertNull(info.ejsVersion)
    }
}
