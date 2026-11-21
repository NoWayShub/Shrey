package com.shrey.downloader.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.shrey.downloader.core.design.ShreyGlassBottomBar
import com.shrey.downloader.core.design.ShreyNavDestination
import com.shrey.downloader.core.design.ShreyTheme
import com.shrey.downloader.core.engine.FormatSelectionMapper
import com.shrey.downloader.domain.model.AudioOutputFormat
import com.shrey.downloader.domain.model.DownloadMode
import com.shrey.downloader.domain.model.EngineDiagnostics
import com.shrey.downloader.domain.model.EngineState
import com.shrey.downloader.domain.model.MediaFormat
import com.shrey.downloader.domain.model.MediaInfo
import com.shrey.downloader.domain.model.ShreySettings
import com.shrey.downloader.domain.model.ThemeMode
import com.shrey.downloader.domain.model.ThumbnailCandidate
import com.shrey.downloader.domain.model.ThumbnailOutputFormat
import com.shrey.downloader.feature.home.HomeScreenContent
import com.shrey.downloader.feature.home.HomeUiState
import com.shrey.downloader.feature.home.MediaPreviewCard
import com.shrey.downloader.feature.library.LibraryScreenContent
import com.shrey.downloader.feature.library.LibraryUiState
import com.shrey.downloader.feature.settings.SettingsScreenContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp-xxhdpi")
class ShreyUiRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val readyEngine = EngineState.Ready(
        EngineDiagnostics(
            ytDlpVersion = "2026.01.01",
            ffmpegAvailable = true,
            jsRuntimeName = null,
            jsRuntimeNote = "No JS runtime bundled"
        )
    )

    private val sampleMedia = MediaInfo(
        id = "vid1",
        originalUrl = "https://example.org/watch?v=vid1",
        canonicalUrl = "https://example.org/watch?v=vid1",
        title = "Minimalist Glass Design Study",
        creator = "Design Archive",
        durationSeconds = 185L,
        thumbnailUrl = "https://cdn.example.org/thumb.jpg",
        thumbnails = listOf(ThumbnailCandidate("https://cdn.example.org/thumb.jpg", 1280, 720, "jpg")),
        formats = listOf(
            MediaFormat("137", "mp4", 1920, 1080, 60.0, "avc1", null, null, 4500.0, 42_000_000L, false, true, false, true),
            MediaFormat("18", "mp4", 640, 360, 30.0, "avc1", "mp4a", 96.0, 750.0, 9_500_000L, false, true, true, false),
            MediaFormat("140", "m4a", null, null, null, null, "mp4a", 128.0, 128.0, 3_200_000L, false, false, true, false)
        ),
        platform = "Vimeo",
        isLive = false,
        extractedAtMillis = 1_700_000_000_000L
    )

    @Test
    fun `home empty screen disables analyze until url entered`() {
        var analyzed = false
        composeRule.setContent {
            var url by remember { mutableStateOf("") }
            ShreyTheme {
                HomeScreenContent(
                    urlInput = url,
                    uiState = HomeUiState.Idle,
                    engineState = readyEngine,
                    onUrlChanged = { url = it },
                    onPasteClick = {},
                    onClearClick = { url = "" },
                    onAnalyzeClick = { analyzed = true },
                    onRetryEngine = {},
                    onModeSelected = {},
                    onOptionSelected = {},
                    onAudioFormatSelected = {},
                    onThumbnailFormatSelected = {},
                    onToggleTechnicalDetails = {},
                    onStartDownload = {},
                    onOpenDownloads = {},
                    onOpenSettings = {},
                    contentBottomPadding = PaddingValues(bottom = 96.dp)
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("shrey_wordmark").assertIsDisplayed()
        composeRule.onNodeWithTag("home_empty_state").assertIsDisplayed()
        composeRule.onNodeWithTag("analyze_link_button").assertIsNotEnabled()

        composeRule.onNodeWithTag("url_input_field").performTextInput("https://vimeo.com/12345")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("analyze_link_button").assertIsEnabled()
        composeRule.onNodeWithTag("analyze_link_button").performClick()
        assertTrue(analyzed)
    }

    @Test
    fun `preview renders real formats switches mode and triggers download`() {
        var downloadTriggered = false
        composeRule.setContent {
            var mode by remember { mutableStateOf(DownloadMode.VIDEO_AUDIO) }
            val options = FormatSelectionMapper.buildOptions(sampleMedia, mode)
            var selectedOption by remember(mode) { mutableStateOf(options.firstOrNull()) }

            ShreyTheme {
                MediaPreviewCard(
                    state = HomeUiState.Ready(
                        media = sampleMedia,
                        selectedMode = mode,
                        selectedAudioFormat = AudioOutputFormat.ORIGINAL,
                        selectedThumbnailFormat = ThumbnailOutputFormat.ORIGINAL,
                        options = options,
                        selectedOption = selectedOption
                    ),
                    onModeSelected = { mode = it },
                    onOptionSelected = { selectedOption = it },
                    onAudioFormatSelected = {},
                    onThumbnailFormatSelected = {},
                    onToggleTechnicalDetails = {},
                    onStartDownload = { downloadTriggered = true },
                    onOpenDownloads = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("preview_media_title").assertIsDisplayed()
        composeRule.onNodeWithText("Minimalist Glass Design Study").assertIsDisplayed()

        // Switch to Audio mode
        composeRule.onNodeWithTag("mode_audio").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("audio_format_mp3").assertIsDisplayed()

        // Trigger download
        composeRule.onNodeWithTag("start_download_button").performClick()
        assertTrue(downloadTriggered)
    }

    @Test
    fun `settings theme switching updates mode cleanly`() {
        var currentTheme = ThemeMode.DARK
        composeRule.setContent {
            var settings by remember { mutableStateOf(ShreySettings(themeMode = ThemeMode.DARK)) }
            ShreyTheme(themeMode = settings.themeMode) {
                SettingsScreenContent(
                    settings = settings,
                    engineState = readyEngine,
                    onThemeSelected = {
                        currentTheme = it
                        settings = settings.copy(themeMode = it)
                    },
                    onGlassModeSelected = {},
                    onDefaultModeSelected = {},
                    onDefaultVideoQualitySelected = {},
                    onDefaultAudioFormatSelected = {},
                    onChooseFolder = {},
                    onResetFolder = {},
                    onAllowMobileDataChanged = {},
                    onHapticsChanged = {},
                    onNotificationsChanged = {},
                    onRetryEngine = {},
                    onOpenLicenses = {},
                    onOpenAbout = {},
                    contentBottomPadding = PaddingValues(bottom = 96.dp)
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("theme_light").performClick()
        composeRule.waitForIdle()
        assertEquals(ThemeMode.LIGHT, currentTheme)
    }

    @Test
    fun `empty library shows intentional empty state`() {
        composeRule.setContent {
            ShreyTheme {
                LibraryScreenContent(
                    state = LibraryUiState(),
                    onFilterSelected = {},
                    onOpen = {},
                    onShare = {},
                    onRemoveRecord = {},
                    onDeleteFile = {},
                    contentBottomPadding = PaddingValues(bottom = 96.dp)
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("library_empty_state").assertIsDisplayed()
    }

    @Test
    fun `floating glass bottom bar switches destinations`() {
        var selected = "home"
        val destinations = listOf(
            ShreyNavDestination("home", "Home", Icons.Outlined.Home, Icons.Filled.Home),
            ShreyNavDestination("downloads", "Downloads", Icons.Outlined.Download, Icons.Filled.Download, 1),
            ShreyNavDestination("library", "Library", Icons.Outlined.VideoLibrary, Icons.Filled.VideoLibrary),
            ShreyNavDestination("settings", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings)
        )
        composeRule.setContent {
            ShreyTheme {
                ShreyGlassBottomBar(
                    destinations = destinations,
                    selectedId = selected,
                    onSelect = { selected = it }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_downloads").performClick()
        assertEquals("downloads", selected)
        composeRule.onNodeWithTag("nav_library").performClick()
        assertEquals("library", selected)
        composeRule.onNodeWithTag("nav_settings").performClick()
        assertEquals("settings", selected)
    }
}
