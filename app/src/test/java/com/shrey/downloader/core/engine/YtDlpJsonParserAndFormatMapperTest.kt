package com.shrey.downloader.core.engine

import com.shrey.downloader.domain.model.AudioOutputFormat
import com.shrey.downloader.domain.model.DefaultVideoQuality
import com.shrey.downloader.domain.model.DownloadMode
import com.shrey.downloader.domain.model.ThumbnailOutputFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class YtDlpJsonParserAndFormatMapperTest {

    private val sampleJson = """
        {
          "id": "abc123",
          "webpage_url": "https://example.org/watch?v=abc123",
          "title": "Sample Architecture Film",
          "channel": "Studio Archive",
          "duration": 312,
          "extractor_key": "Vimeo",
          "is_live": false,
          "thumbnail": "https://cdn.example.org/thumb_hd.jpg",
          "thumbnails": [
            {"url": "https://cdn.example.org/thumb_sm.jpg", "width": 320, "height": 180},
            {"url": "https://cdn.example.org/thumb_hd.jpg", "width": 1280, "height": 720}
          ],
          "formats": [
            {"format_id": "sb0", "ext": "mhtml", "format_note": "storyboard"},
            {"format_id": "18", "ext": "mp4", "width": 640, "height": 360, "fps": 30, "vcodec": "avc1.42001E", "acodec": "mp4a.40.2", "filesize": 12000000},
            {"format_id": "137", "ext": "mp4", "width": 1920, "height": 1080, "fps": 60, "vcodec": "avc1.64002a", "acodec": "none", "filesize_approx": 95000000, "dynamic_range": "HDR10"},
            {"format_id": "248", "ext": "webm", "width": 1920, "height": 1080, "fps": 30, "vcodec": "vp9", "acodec": "none", "filesize": 70000000},
            {"format_id": "140", "ext": "m4a", "vcodec": "none", "acodec": "mp4a.40.2", "abr": 128.0, "filesize": 5000000},
            {"format_id": "251", "ext": "webm", "vcodec": "none", "acodec": "opus", "abr": 160.0, "filesize": 6200000}
          ]
        }
    """.trimIndent()

    @Test
    fun `parses real yt-dlp json and ignores storyboard formats`() {
        val info = YtDlpJsonParser.parseMediaInfo(sampleJson, "https://example.org/watch?v=abc123")
        assertEquals("abc123", info.id)
        assertEquals("Sample Architecture Film", info.title)
        assertEquals("Studio Archive", info.creator)
        assertEquals(312L, info.durationSeconds)
        assertEquals("Vimeo", info.platform)
        assertFalse(info.isLive)
        assertEquals(5, info.formats.size) // Storyboard excluded

        val fmt137 = info.formats.first { it.formatId == "137" }
        assertTrue(fmt137.isFilesizeEstimated)
        assertTrue(fmt137.isHdr)
        assertTrue(fmt137.requiresMerge)
    }

    @Test
    fun `video with audio maps only real extracted resolutions and pairs compatible audio for adaptive streams`() {
        val info = YtDlpJsonParser.parseMediaInfo(sampleJson, "https://example.org/watch?v=abc123")
        val options = FormatSelectionMapper.buildOptions(info, DownloadMode.VIDEO_AUDIO)

        assertTrue(options.isNotEmpty())
        // Must NEVER fabricate 4K or 8K when max extracted height is 1080p
        assertFalse(options.any { it.label.contains("2160p") || it.label.contains("4320p") })

        val best = options.first()
        assertTrue(best.isBestAvailable)
        assertEquals("137+140", best.formatSelector) // mp4 video pairs with compatible m4a audio
        assertEquals("mp4", best.outputExtension)
        assertTrue(best.requiresMerge)

        val direct360 = options.first { it.formatSelector == "18" }
        assertFalse(direct360.requiresMerge)
        assertFalse(direct360.isSizeEstimated)

        // Default quality preference <= 720p selects 360p (highest available <= 720p)
        val selected720 = FormatSelectionMapper.selectDefaultOption(options, DefaultVideoQuality.P720)
        assertEquals("18", selected720?.formatSelector)
    }

    @Test
    fun `audio only supports original and ffmpeg transcoding options truthfully`() {
        val info = YtDlpJsonParser.parseMediaInfo(sampleJson, "https://example.org/watch?v=abc123")
        val originalOpts = FormatSelectionMapper.buildOptions(info, DownloadMode.AUDIO_ONLY, AudioOutputFormat.ORIGINAL)
        val mp3Opts = FormatSelectionMapper.buildOptions(info, DownloadMode.AUDIO_ONLY, AudioOutputFormat.MP3)

        assertEquals("251", originalOpts.first().formatSelector) // Highest bitrate (160 kbps opus)
        assertEquals("webm", originalOpts.first().outputExtension)

        assertEquals("mp3", mp3Opts.first().outputExtension)
        assertTrue(mp3Opts.first().subtitle.contains("FFmpeg transcode"))
    }

    @Test
    fun `video only never attaches audio merge selector`() {
        val info = YtDlpJsonParser.parseMediaInfo(sampleJson, "https://example.org/watch?v=abc123")
        val videoOnlyOpts = FormatSelectionMapper.buildOptions(info, DownloadMode.VIDEO_ONLY)

        assertTrue(videoOnlyOpts.isNotEmpty())
        videoOnlyOpts.forEach { opt ->
            assertFalse(opt.requiresMerge)
            assertFalse(opt.formatSelector.contains("+"))
            assertTrue(opt.primaryFormat?.hasAudio == false)
        }
    }

    @Test
    fun `thumbnail only picks highest resolution thumbnail and honors output format`() {
        val info = YtDlpJsonParser.parseMediaInfo(sampleJson, "https://example.org/watch?v=abc123")
        val pngOpts = FormatSelectionMapper.buildOptions(
            media = info,
            mode = DownloadMode.THUMBNAIL_ONLY,
            thumbnailOutputFormat = ThumbnailOutputFormat.PNG
        )
        assertEquals(1, pngOpts.size)
        assertEquals("https://cdn.example.org/thumb_hd.jpg", pngOpts.first().formatSelector)
        assertEquals("png", pngOpts.first().outputExtension)
        assertEquals("1280 × 720", pngOpts.first().label)
    }

    @Test
    fun `handles missing optional fields and detects live streams`() {
        val liveJson = """{"id": "live1", "is_live": true, "formats": []}"""
        val info = YtDlpJsonParser.parseMediaInfo(liveJson, "https://example.org/live")
        assertTrue(info.isLive)
        assertEquals("Untitled Media", info.title)
        assertNotNull(info.platform)
    }
}
