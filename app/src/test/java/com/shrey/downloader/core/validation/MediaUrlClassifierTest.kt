package com.shrey.downloader.core.validation

import com.shrey.downloader.domain.model.ChannelContentTab
import com.shrey.downloader.domain.model.MediaSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUrlClassifierTest {

    @Test
    fun `detects standard youtube single video watch url`() {
        val result = MediaUrlClassifier.classify("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertEquals(MediaSourceType.SINGLE_VIDEO, result.sourceType)
        assertEquals("dQw4w9WgXcQ", result.videoId)
        assertNull(result.playlistId)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", result.singleVideoUrl)
    }

    @Test
    fun `detects short youtu be single video url`() {
        val result = MediaUrlClassifier.classify("https://youtu.be/dQw4w9WgXcQ?t=42")
        assertEquals(MediaSourceType.SINGLE_VIDEO, result.sourceType)
        assertEquals("dQw4w9WgXcQ", result.videoId)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", result.singleVideoUrl)
    }

    @Test
    fun `detects youtube shorts single video url`() {
        val result = MediaUrlClassifier.classify("https://www.youtube.com/shorts/abcDEF12345")
        assertEquals(MediaSourceType.SINGLE_VIDEO, result.sourceType)
        assertEquals("abcDEF12345", result.videoId)
        assertEquals("https://www.youtube.com/watch?v=abcDEF12345", result.singleVideoUrl)
    }

    @Test
    fun `detects mobile and music youtube single video urls`() {
        val mobile = MediaUrlClassifier.classify("https://m.youtube.com/watch?v=abcDEF12345")
        val music = MediaUrlClassifier.classify("https://music.youtube.com/watch?v=abcDEF12345")
        assertEquals(MediaSourceType.SINGLE_VIDEO, mobile.sourceType)
        assertEquals(MediaSourceType.SINGLE_VIDEO, music.sourceType)
    }

    @Test
    fun `detects pure playlist url`() {
        val result = MediaUrlClassifier.classify("https://www.youtube.com/playlist?list=PL1234567890abcdef")
        assertEquals(MediaSourceType.PLAYLIST, result.sourceType)
        assertEquals("PL1234567890abcdef", result.playlistId)
        assertEquals("https://www.youtube.com/playlist?list=PL1234567890abcdef", result.playlistUrl)
    }

    @Test
    fun `detects ambiguous watch url containing both video id and playlist id`() {
        val result = MediaUrlClassifier.classify("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1234567890abcdef&index=3")
        assertEquals(MediaSourceType.AMBIGUOUS_VIDEO_OR_PLAYLIST, result.sourceType)
        assertEquals("dQw4w9WgXcQ", result.videoId)
        assertEquals("PL1234567890abcdef", result.playlistId)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", result.singleVideoUrl)
        assertEquals("https://www.youtube.com/playlist?list=PL1234567890abcdef", result.playlistUrl)
    }

    @Test
    fun `detects ambiguous short youtu be url containing playlist id`() {
        val result = MediaUrlClassifier.classify("https://youtu.be/dQw4w9WgXcQ?list=PL1234567890abcdef")
        assertEquals(MediaSourceType.AMBIGUOUS_VIDEO_OR_PLAYLIST, result.sourceType)
        assertEquals("dQw4w9WgXcQ", result.videoId)
        assertEquals("PL1234567890abcdef", result.playlistId)
    }

    @Test
    fun `detects youtube channel handle and tabs`() {
        val base = MediaUrlClassifier.classify("https://www.youtube.com/@NoWayShub")
        assertEquals(MediaSourceType.CHANNEL, base.sourceType)
        assertEquals("@NoWayShub", base.channelIdentifier)
        assertNull(base.requestedChannelTab)
        assertEquals("https://www.youtube.com/@NoWayShub", base.channelBaseUrl)

        val videosTab = MediaUrlClassifier.classify("https://www.youtube.com/@NoWayShub/videos")
        assertEquals(MediaSourceType.CHANNEL, videosTab.sourceType)
        assertEquals(ChannelContentTab.VIDEOS, videosTab.requestedChannelTab)

        val shortsTab = MediaUrlClassifier.classify("https://www.youtube.com/@NoWayShub/shorts")
        assertEquals(MediaSourceType.CHANNEL, shortsTab.sourceType)
        assertEquals(ChannelContentTab.SHORTS, shortsTab.requestedChannelTab)

        val streamsTab = MediaUrlClassifier.classify("https://www.youtube.com/@NoWayShub/streams")
        assertEquals(MediaSourceType.CHANNEL, streamsTab.sourceType)
        assertEquals(ChannelContentTab.STREAMS, streamsTab.requestedChannelTab)
    }

    @Test
    fun `detects channel id and legacy c and user formats`() {
        val channelId = MediaUrlClassifier.classify("https://www.youtube.com/channel/UC1234567890abcdefghij")
        assertEquals(MediaSourceType.CHANNEL, channelId.sourceType)
        assertEquals("UC1234567890abcdefghij", channelId.channelIdentifier)
        assertEquals("https://www.youtube.com/channel/UC1234567890abcdefghij", channelId.channelBaseUrl)

        val legacyC = MediaUrlClassifier.classify("https://www.youtube.com/c/GoogleDevelopers/videos")
        assertEquals(MediaSourceType.CHANNEL, legacyC.sourceType)
        assertEquals(ChannelContentTab.VIDEOS, legacyC.requestedChannelTab)

        val legacyUser = MediaUrlClassifier.classify("https://www.youtube.com/user/AndroidDevelopers/streams")
        assertEquals(MediaSourceType.CHANNEL, legacyUser.sourceType)
        assertEquals(ChannelContentTab.STREAMS, legacyUser.requestedChannelTab)
    }

    @Test
    fun `classifies non-youtube url as generic single media`() {
        val result = MediaUrlClassifier.classify("https://vimeo.com/123456789")
        assertEquals(MediaSourceType.GENERIC_SINGLE_MEDIA, result.sourceType)
        assertNull(result.error)
    }

    @Test
    fun `rejects invalid malformed non-media and unsafe urls`() {
        val cases = listOf(
            "",
            "not a url at all",
            "javascript:alert(1)",
            "http://127.0.0.1/watch?v=abcDEF12345",
            "https://www.youtube.com/",
            "https://www.youtube.com/feed/subscriptions",
            "https://youtu.be/",
            "--exec rm -rf /"
        )
        cases.forEach { raw ->
            val classified = MediaUrlClassifier.classify(raw)
            assertEquals("Expected UNSUPPORTED for '$raw'", MediaSourceType.UNSUPPORTED, classified.sourceType)
            assertNotNull("Expected error for '$raw'", classified.error)
        }
    }

    @Test
    fun `validates redirect targets and blocks protocol downgrade or loopback ssrf`() {
        val ok = MediaUrlClassifier.validateRedirectTarget(
            originalUrl = "https://youtu.be/dQw4w9WgXcQ",
            redirectedUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        )
        assertTrue(ok is UrlValidationResult.Valid)

        val downgrade = MediaUrlClassifier.validateRedirectTarget(
            originalUrl = "https://youtu.be/dQw4w9WgXcQ",
            redirectedUrl = "http://www.youtube.com/watch?v=dQw4w9WgXcQ"
        )
        assertTrue(downgrade is UrlValidationResult.Invalid)

        val ssrf = MediaUrlClassifier.validateRedirectTarget(
            originalUrl = "https://youtu.be/dQw4w9WgXcQ",
            redirectedUrl = "https://127.0.0.1/admin"
        )
        assertTrue(ssrf is UrlValidationResult.Invalid)
    }

    @Test
    fun `refines generic classification using yt-dlp extractor metadata`() {
        val initial = MediaUrlClassifier.classify("https://archive.org/details/some-collection")
        assertEquals(MediaSourceType.GENERIC_SINGLE_MEDIA, initial.sourceType)

        val refinedPlaylist = MediaUrlClassifier.refineWithExtractorMetadata(
            initial = initial,
            ytDlpType = "playlist",
            extractorKey = "ArchiveOrg",
            hasEntries = true
        )
        assertEquals(MediaSourceType.PLAYLIST, refinedPlaylist)

        val refinedSingle = MediaUrlClassifier.refineWithExtractorMetadata(
            initial = initial,
            ytDlpType = "video",
            extractorKey = "Vimeo",
            hasEntries = false
        )
        assertEquals(MediaSourceType.SINGLE_VIDEO, refinedSingle)
    }
}
