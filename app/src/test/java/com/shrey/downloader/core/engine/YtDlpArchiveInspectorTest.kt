package com.shrey.downloader.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile

class YtDlpArchiveInspectorTest {

    private fun findBundledYtDlp(): File {
        val candidates = listOf(
            File("src/main/res/raw/ytdlp"),
            File("app/src/main/res/raw/ytdlp")
        )
        val bundledFile = candidates.firstOrNull { it.exists() }
        assertTrue("Expected bundled res/raw/ytdlp archive to exist", bundledFile != null)
        return bundledFile!!
    }

    @Test
    fun `bundled raw ytdlp archive matches expected 2026_10_26 release ejs 0_8_0 and Android QuickJS patches`() {
        val bundledFile = findBundledYtDlp()

        val info = YtDlpArchiveInspector.inspect(bundledFile)
        assertEquals(EngineCoordinator.BUNDLED_YT_DLP_VERSION, info.ytDlpVersion)
        assertEquals("2026.10.26", info.ytDlpVersion)
        assertEquals(EngineCoordinator.BUNDLED_EJS_VERSION, info.ejsVersion)
        assertEquals("0.8.0", info.ejsVersion)
        assertTrue("Expected bundled yt_dlp_ejs solver scripts to be present", info.hasEjsSolverScripts)

        val sha256 = YtDlpArchiveInspector.computeSha256(bundledFile)
        assertNotNull(sha256)
        assertEquals(64, sha256!!.length)

        ZipFile(bundledFile).use { zip ->
            val jsRuntimePy = zip.getInputStream(zip.getEntry("yt_dlp/utils/_jsruntime.py"))
                .bufferedReader().use { it.readText() }
            val quickJsProviderPy = zip.getInputStream(zip.getEntry("yt_dlp/extractor/youtube/jsc/_builtin/quickjs.py"))
                .bufferedReader().use { it.readText() }

            assertTrue(
                "Expected multiline QuickJS version regex in _jsruntime.py",
                jsRuntimePy.contains("(?m)^QuickJS")
            )
            assertTrue(
                "Expected Android LD_LIBRARY_PATH sanitization helper in _jsruntime.py",
                jsRuntimePy.contains("_quickjs_subprocess_env")
            )
            assertTrue(
                "Expected expanded QuickJS --stack-size flag in quickjs.py",
                quickJsProviderPy.contains("--stack-size")
            )
            assertTrue(
                "Expected sanitized env usage in quickjs.py",
                quickJsProviderPy.contains("_quickjs_subprocess_env")
            )
        }
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
    fun `compares semver ejs versions and computes sha256 accurately`() {
        assertTrue(YtDlpArchiveInspector.isSemverOlderThan("0.3.1", "0.8.0"))
        assertTrue(YtDlpArchiveInspector.isSemverOlderThan("0.7.9", "0.8.0"))
        assertTrue(YtDlpArchiveInspector.isSemverOlderThan(null, "0.8.0"))
        assertFalse(YtDlpArchiveInspector.isSemverOlderThan("0.8.0", "0.8.0"))
        assertFalse(YtDlpArchiveInspector.isSemverOlderThan("0.8.1", "0.8.0"))
        assertFalse(YtDlpArchiveInspector.isSemverOlderThan("1.0.0", "0.8.0"))

        val digest = YtDlpArchiveInspector.computeSha256(ByteArrayInputStream("shrey".toByteArray()))
        assertEquals(64, digest?.length)
    }

    @Test
    fun `parses QuickJS version even when Android linker prints leading warnings`() {
        val standard = "QuickJS version 2025-04-26\nusage: qjs [options] [file [args]]"
        assertEquals("2025-04-26", EngineCoordinator.parseQuickJsVersion(standard))

        val withLinkerWarning = """
            WARNING: linker: Warning: unable to normalize "" (ignoring)
            QuickJS version 2025-04-26
            usage: qjs [options] [file [args]]
        """.trimIndent()
        assertEquals("2025-04-26", EngineCoordinator.parseQuickJsVersion(withLinkerWarning))

        val quickJsNg = "QuickJS-ng version 0.10.1\nusage: qjs"
        assertEquals("0.10.1", EngineCoordinator.parseQuickJsVersion(quickJsNg))

        assertNull(EngineCoordinator.parseQuickJsVersion("permission denied"))
    }

    @Test
    fun `extractUserWarnings preserves genuine runtime solver execution challenge and SABR warnings`() {
        assertTrue(YtDlpMediaExtractor.extractUserWarnings("").isEmpty())

        val missingRuntimeWarnings = YtDlpMediaExtractor.extractUserWarnings(
            "WARNING: [youtube] No supported JavaScript runtime could be found."
        )
        assertEquals(1, missingRuntimeWarnings.size)
        assertTrue(missingRuntimeWarnings.first().contains("JavaScript runtime"))

        val missingSolverWarnings = YtDlpMediaExtractor.extractUserWarnings(
            "WARNING: [youtube] [jsc:quickjs] No usable challenge solver script found (remote component ejs:github disabled)"
        )
        assertEquals(1, missingSolverWarnings.size)
        assertTrue(missingSolverWarnings.first().contains("EJS challenge solver scripts"))

        val quickJsErrorWarnings = YtDlpMediaExtractor.extractUserWarnings(
            "WARNING: [youtube] [jsc:quickjs] Error running quickjs process (returncode: 1): InternalError: stack overflow"
        )
        assertEquals(1, quickJsErrorWarnings.size)
        assertTrue(quickJsErrorWarnings.first().contains("QuickJS"))

        val challengeWarnings = YtDlpMediaExtractor.extractUserWarnings(
            "WARNING: [youtube] n challenge solving failed: Some formats may be missing.\n" +
                "WARNING: [youtube] YouTube is forcing SABR streaming for this client."
        )
        assertEquals(2, challengeWarnings.size)
        assertTrue(challengeWarnings[0].contains("JavaScript challenge could not be solved locally"))
        assertTrue(challengeWarnings[1].contains("SABR"))
    }

    @Test
    fun `handles missing archive gracefully without throwing`() {
        val info = YtDlpArchiveInspector.inspect(File("nonexistent_ytdlp_archive.zip"))
        assertNull(info.ytDlpVersion)
        assertNull(info.ejsVersion)
        assertFalse(info.hasEjsSolverScripts)
    }
}

