package com.shrey.downloader.core.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilenameSanitizerTest {

    @Test
    fun `removes path separators and traversal sequences`() {
        val result = FilenameSanitizer.buildFilename("../../etc/passwd:my*video?", "mp4")
        assertFalse(result.contains("/"))
        assertFalse(result.contains("\\"))
        assertFalse(result.contains(".."))
        assertTrue(result.endsWith(".mp4"))
    }

    @Test
    fun `handles reserved device names safely`() {
        assertEquals("CON_media.mp4", FilenameSanitizer.buildFilename("CON", "mp4"))
        assertEquals("nul_media.mp3", FilenameSanitizer.buildFilename("nul", ".MP3"))
    }

    @Test
    fun `preserves unicode and handles empty titles`() {
        assertEquals("東京 Sunset Vlog.mp4", FilenameSanitizer.buildFilename("  東京   Sunset Vlog  ", "mp4"))
        assertEquals("shrey_media.mp4", FilenameSanitizer.buildFilename("   ", "mp4"))
    }

    @Test
    fun `truncates excessively long titles cleanly`() {
        val longTitle = "A".repeat(250)
        val result = FilenameSanitizer.buildFilename(longTitle, "mkv")
        assertTrue(result.length <= 120)
        assertTrue(result.endsWith(".mkv"))
    }

    @Test
    fun `deduplicates conflicting filenames incrementally`() {
        val existing = setOf("Clip.mp4", "Clip (1).mp4")
        assertEquals("Clip (2).mp4", FilenameSanitizer.deduplicateFilename("Clip.mp4", existing))
        assertEquals("Fresh.mp4", FilenameSanitizer.deduplicateFilename("Fresh.mp4", existing))
    }
}
