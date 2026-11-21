package com.shrey.downloader.core.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlValidatorTest {

    @Test
    fun `accepts valid https public url and trims whitespace`() {
        val result = UrlValidator.normalizeAndValidate("   https://vimeo.com/22439234   ")
        assertTrue(result is UrlValidationResult.Valid)
        val valid = result as UrlValidationResult.Valid
        assertEquals("https://vimeo.com/22439234", valid.normalizedUrl)
        assertEquals("vimeo.com", valid.host)
    }

    @Test
    fun `extracts url embedded inside shared text`() {
        val shared = "Check out this documentary https://example.org/watch?v=12345!"
        val extracted = UrlValidator.extractFirstUrlFromSharedText(shared)
        assertEquals("https://example.org/watch?v=12345", extracted)

        val result = UrlValidator.normalizeAndValidate(shared)
        assertTrue(result is UrlValidationResult.Valid)
    }

    @Test
    fun `rejects non http schemes`() {
        assertTrue(UrlValidator.normalizeAndValidate("file:///etc/passwd") is UrlValidationResult.Invalid)
        assertTrue(UrlValidator.normalizeAndValidate("javascript:alert(1)") is UrlValidationResult.Invalid)
        assertTrue(UrlValidator.normalizeAndValidate("content://media/external/video/1") is UrlValidationResult.Invalid)
    }

    @Test
    fun `blocks loopback and private network hosts`() {
        val blocked = listOf(
            "http://localhost/video.mp4",
            "http://127.0.0.1:8080/video",
            "http://10.0.0.5/media",
            "http://172.16.4.1/stream",
            "http://192.168.1.10/file.mp4",
            "http://169.254.169.254/latest/meta-data",
            "http://[::1]/video"
        )
        blocked.forEach { url ->
            assertTrue("Expected $url to be rejected", UrlValidator.normalizeAndValidate(url) is UrlValidationResult.Invalid)
        }
    }

    @Test
    fun `rejects cli flag injection and embedded credentials`() {
        assertTrue(UrlValidator.normalizeAndValidate("--exec rm -rf /") is UrlValidationResult.Invalid)
        assertTrue(UrlValidator.normalizeAndValidate("https://user:secret@example.com/v") is UrlValidationResult.Invalid)
        assertTrue(UrlValidator.normalizeAndValidate("https://example.com/v\n--exec foo") is UrlValidationResult.Invalid)
    }

    @Test
    fun `validates safe format selectors strictly`() {
        assertTrue(UrlValidator.isSafeFormatSelector("137+140"))
        assertTrue(UrlValidator.isSafeFormatSelector("bestvideo[height<=1080]+bestaudio"))
        assertFalse(UrlValidator.isSafeFormatSelector("137; rm -rf /"))
        assertFalse(UrlValidator.isSafeFormatSelector("$(whoami)"))
        assertFalse(UrlValidator.isSafeFormatSelector(""))
    }
}
