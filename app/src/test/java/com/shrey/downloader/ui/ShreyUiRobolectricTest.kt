package com.shrey.downloader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.shrey.downloader.core.design.ShreyGlassBottomBar
import com.shrey.downloader.core.design.ShreyNavDestination
import com.shrey.downloader.core.design.ShreyTheme
import com.shrey.downloader.core.engine.FormatSelectionMapper
import com.shrey.downloader.domain.model.AudioOutputFormat
import com.shrey.downloader.domain.model.DownloadItem
import com.shrey.downloader.domain.model.DownloadMode
import com.shrey.downloader.domain.model.DownloadStage
import com.shrey.downloader.domain.model.EngineDiagnostics
import com.shrey.downloader.domain.model.EngineState
import com.shrey.downloader.domain.model.MediaFormat
import com.shrey.downloader.domain.model.MediaInfo
import com.shrey.downloader.domain.model.ShreySettings
import com.shrey.downloader.domain.model.ThemeMode
import com.shrey.downloader.domain.model.ThumbnailCandidate
import com.shrey.downloader.domain.model.ThumbnailOutputFormat
import com.shrey.downloader.feature.downloads.DownloadsScreenContent
import com.shrey.downloader.feature.downloads.DownloadsUiState
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

    @Test
    fun `downloads completed actions render cleanly on narrow 360dp width and respond to clicks`() {
        var opened = false
        var shared = false
        var hidden = false
        val completedItem = DownloadItem(
            id = "job_4k",
            sourceUrl = "https://youtube.com/watch?v=demo4k",
            title = "4K Nature Documentary Sample",
            creator = "Studio Archive",
            thumbnailUrl = null,
            selectedFormatId = "313+251",
            formatSelector = "bestvideo+bestaudio",
            qualityLabel = "2160p",
            mode = DownloadMode.VIDEO_AUDIO,
            outputContainer = "mp4",
            audioOutputFormat = AudioOutputFormat.ORIGINAL,
            thumbnailOutputFormat = ThumbnailOutputFormat.ORIGINAL,
            requiresMerge = true,
            stage = DownloadStage.COMPLETED,
            progressPercent = 100f,
            downloadedBytes = 128_000_000L,
            totalBytes = 128_000_000L,
            speedText = null,
            etaSeconds = null,
            statusMessage = "Saved",
            destinationTreeUri = null,
            destinationUri = "content://media/external/video/media/42",
            outputDisplayName = "4K Nature Documentary Sample.mp4",
            mimeType = "video/mp4",
            createdAt = 1_700_000_000_000L,
            startedAt = 1_700_000_001_000L,
            completedAt = 1_700_000_010_000L,
            lastError = null,
            technicalError = null,
            retryCount = 0
        )

        composeRule.setContent {
            ShreyTheme {
                Box(modifier = Modifier.width(360.dp)) {
                    DownloadsScreenContent(
                        state = DownloadsUiState(completed = listOf(completedItem)),
                        onCancel = {},
                        onRetry = {},
                        onRemoveHistory = { hidden = true },
                        onDeleteFile = {},
                        onOpenFile = { opened = true },
                        onShareFile = { shared = true },
                        contentBottomPadding = PaddingValues(bottom = 124.dp)
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("top_gradual_blur").assertIsDisplayed()
        composeRule.onNodeWithTag("download_open_job_4k").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("download_share_job_4k").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Hide from history").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Delete file").assertIsDisplayed()
        assertTrue(opened)
        assertTrue(shared)
        assertTrue(hidden)
    }

    @Test
    fun `home and settings render subtle top gradual blur overlay`() {
        composeRule.setContent {
            ShreyTheme {
                HomeScreenContent(
                    urlInput = "",
                    uiState = HomeUiState.Idle,
                    engineState = readyEngine,
                    onUrlChanged = {},
                    onPasteClick = {},
                    onClearClick = {},
                    onAnalyzeClick = {},
                    onRetryEngine = {},
                    onModeSelected = {},
                    onOptionSelected = {},
                    onAudioFormatSelected = {},
                    onThumbnailFormatSelected = {},
                    onToggleTechnicalDetails = {},
                    onStartDownload = {},
                    onOpenDownloads = {},
                    onOpenSettings = {},
                    contentBottomPadding = PaddingValues(bottom = 124.dp)
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("top_gradual_blur").assertIsDisplayed()
    }

    @Test
    fun `top gradual blur renders in dark and light full and reduced modes without intercepting top touches`() {
        var topButtonClicked = false
        var modeState by mutableStateOf(com.shrey.downloader.domain.model.GlassEffectMode.FULL)
        var themeState by mutableStateOf(ThemeMode.DARK)

        composeRule.setContent {
            ShreyTheme(themeMode = themeState, glassEffectMode = modeState) {
                com.shrey.downloader.core.design.ShreyTopGradualBlurContainer(
                    scrollProgress = 0.85f
                ) {
                    androidx.compose.material3.TextButton(
                        onClick = { topButtonClicked = true },
                        modifier = Modifier.testTag("top_under_blur_button")
                    ) {
                        androidx.compose.material3.Text("Top Action")
                    }
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("top_gradual_blur")
            .assertIsDisplayed()
            .assert(androidx.compose.ui.test.hasStateDescription("Top progressive backdrop blur active"))

        // Clicking a control positioned at the top edge under the blur strip must work (zero touch interception)
        composeRule.onNodeWithTag("top_under_blur_button").performClick()
        assertTrue(topButtonClicked)

        // Switching to Light + Reduced mode transitions cleanly to honest fallback scrim
        themeState = ThemeMode.LIGHT
        modeState = com.shrey.downloader.domain.model.GlassEffectMode.REDUCED
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("top_gradual_blur")
            .assertIsDisplayed()
            .assert(androidx.compose.ui.test.hasStateDescription("Top atmospheric scrim fallback active"))
    }

    @Test
    fun `scaffold floating bottom bar never overlaps bottom settings item on narrow width and large font scale`() {
        val destinations = listOf(
            ShreyNavDestination("home", "Home", Icons.Outlined.Home, Icons.Filled.Home),
            ShreyNavDestination("downloads", "Downloads", Icons.Outlined.Download, Icons.Filled.Download, 2),
            ShreyNavDestination("library", "Library", Icons.Outlined.VideoLibrary, Icons.Filled.VideoLibrary),
            ShreyNavDestination("settings", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings)
        )
        val sampleUpdateSnapshot = com.shrey.downloader.core.update.UpdateSnapshot(
            checkState = com.shrey.downloader.core.update.UpdateCheckState.UP_TO_DATE,
            lastCheckedAtMillis = 1_750_000_000_000L,
            engine = com.shrey.downloader.core.update.EngineUpdateStatus(
                installedVersion = "2026.10.26",
                lastKnownGoodVersion = "2026.10.26",
                installedEjsVersion = "0.8.0",
                installedPythonVersion = "3.12.11",
                installedQuickJsVersion = "2025-04-26",
                availableVersion = "2026.10.26",
                compatibilityStatus = com.shrey.downloader.core.update.EngineCompatibilityStatus.COMPATIBLE
            ),
            app = com.shrey.downloader.core.update.AppUpdateStatus(
                installedVersionName = "0.0.3-dev",
                installedVersionCode = 3L,
                latestVersionName = "0.0.3-dev"
            )
        )

        composeRule.setContent {
            val baseDensity = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                    density = baseDensity.density,
                    fontScale = 1.4f
                )
            ) {
                ShreyTheme {
                    Box(modifier = Modifier.width(320.dp)) {
                        androidx.compose.material3.Scaffold(
                            containerColor = androidx.compose.ui.graphics.Color.Transparent,
                            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                            bottomBar = {
                                ShreyGlassBottomBar(
                                    destinations = destinations,
                                    selectedId = "settings",
                                    onSelect = {}
                                )
                            }
                        ) { innerPadding ->
                            SettingsScreenContent(
                                settings = ShreySettings(),
                                engineState = readyEngine,
                                updateSnapshot = sampleUpdateSnapshot,
                                onThemeSelected = {},
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
                                contentBottomPadding = innerPadding
                            )
                        }
                    }
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("shrey_floating_bottom_bar").assertIsDisplayed()
        // Scroll all the way to the bottom as a real user does so inner bottom clearance applies
        repeat(12) {
            composeRule.onNodeWithTag("settings_scroll_container").performTouchInput {
                swipeUp(durationMillis = 120)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_about_row").assertIsDisplayed()

        val aboutBounds = composeRule.onNodeWithTag("settings_about_row").fetchSemanticsNode().boundsInRoot
        val bottomBarBounds = composeRule.onNodeWithTag("shrey_floating_bottom_bar").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Expected last Settings item (bottom=${aboutBounds.bottom}) to sit above floating bottom bar (top=${bottomBarBounds.top})",
            aboutBounds.bottom <= bottomBarBounds.top + 1f
        )
    }

    @Test
    fun `settings updates card displays categories opens dialogs and handles actions`() {
        var checkedNow = false
        var downloadApkClicked = false
        val updateSnapshot = com.shrey.downloader.core.update.UpdateSnapshot(
            checkState = com.shrey.downloader.core.update.UpdateCheckState.UPDATE_AVAILABLE,
            lastCheckedAtMillis = 1_750_000_000_000L,
            engine = com.shrey.downloader.core.update.EngineUpdateStatus(
                installedVersion = "2026.10.26",
                lastKnownGoodVersion = "2026.10.26",
                installedEjsVersion = "0.8.0",
                installedPythonVersion = "3.12.11",
                installedQuickJsVersion = "2025-04-26",
                availableVersion = "2026.11.15",
                compatibilityStatus = com.shrey.downloader.core.update.EngineCompatibilityStatus.UNSIGNED_UPSTREAM_ONLY,
                updateAvailable = true
            ),
            app = com.shrey.downloader.core.update.AppUpdateStatus(
                installedVersionName = "0.0.3-dev",
                installedVersionCode = 3L,
                latestVersionName = "0.0.4",
                latestTagName = "v0.0.4",
                updateAvailable = true,
                releaseNotes = "Faster 4K remuxing and updated yt-dlp baseline.",
                releasePageUrl = "https://github.com/NoWayShub/Shrey/releases/tag/v0.0.4",
                apkDownloadUrl = "https://github.com/NoWayShub/Shrey/releases/download/v0.0.4/Shrey-v0.0.4.apk",
                apkFileName = "Shrey-v0.0.4.apk",
                apkSizeBytes = 248_000_000L
            )
        )

        composeRule.setContent {
            ShreyTheme {
                com.shrey.downloader.feature.settings.SettingsUpdatesCard(
                    snapshot = updateSnapshot,
                    onCheckNow = { checkedNow = true },
                    onDownloadAppApk = { downloadApkClicked = true },
                    onOpenReleasePage = {},
                    onOpenVerifiedApk = {},
                    onAutoCheckChanged = {},
                    onAutoInstallEngineChanged = {},
                    onNotifyOnUpdatesChanged = {},
                    onChannelSelected = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_updates_card").assertIsDisplayed()
        composeRule.onNodeWithText("Shrey").assertIsDisplayed()
        composeRule.onNodeWithText("yt-dlp").assertIsDisplayed()
        composeRule.onNodeWithText("FFmpeg").assertIsDisplayed()
        composeRule.onNodeWithTag("updates_check_now_button").performClick()
        assertTrue(checkedNow)

        composeRule.onNodeWithTag("updates_download_apk_button").performClick()
        assertTrue(downloadApkClicked)

        composeRule.onNodeWithTag("updates_diagnostics_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Update Diagnostics").assertIsDisplayed()
        composeRule.onNodeWithText("Close").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("updates_release_notes_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Shrey v0.0.4").assertIsDisplayed()
    }

    @Test
    fun `home error card shows installed yt-dlp version and engine update check button on http 403`() {
        var checkUpdatesTriggered = false
        composeRule.setContent {
            ShreyTheme {
                HomeScreenContent(
                    urlInput = "https://youtube.com/watch?v=test403",
                    uiState = HomeUiState.Error(
                        com.shrey.downloader.domain.model.ShreyError.MediaRequestForbidden(
                            details = "HTTP Error 403: Forbidden"
                        )
                    ),
                    engineState = readyEngine,
                    onUrlChanged = {},
                    onPasteClick = {},
                    onClearClick = {},
                    onAnalyzeClick = {},
                    onRetryEngine = {},
                    onCheckEngineUpdates = { checkUpdatesTriggered = true },
                    onModeSelected = {},
                    onOptionSelected = {},
                    onAudioFormatSelected = {},
                    onThumbnailFormatSelected = {},
                    onToggleTechnicalDetails = {},
                    onStartDownload = {},
                    onOpenDownloads = {},
                    onOpenSettings = {},
                    contentBottomPadding = PaddingValues(bottom = 124.dp)
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("error_engine_version").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("error_check_updates_button").performScrollTo().assertIsDisplayed().performClick()
        assertTrue(checkUpdatesTriggered)
    }
}

