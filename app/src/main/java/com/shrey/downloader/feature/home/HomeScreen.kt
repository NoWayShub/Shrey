package com.shrey.downloader.feature.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.downloader.core.design.ShreyEmptyState
import com.shrey.downloader.core.design.ShreyErrorCard
import com.shrey.downloader.core.design.ShreyGlassCard
import com.shrey.downloader.core.design.ShreyGlassTextField
import com.shrey.downloader.core.design.ShreyPrimaryPillButton
import com.shrey.downloader.core.design.ShreyThemeTokens
import com.shrey.downloader.core.design.ShreyTopGradualBlurContainer
import com.shrey.downloader.core.engine.EngineCoordinator
import com.shrey.downloader.core.update.UpdateCheckState
import com.shrey.downloader.core.update.UpdateSnapshot
import com.shrey.downloader.domain.model.AudioOutputFormat
import com.shrey.downloader.domain.model.BulkQualityPreset
import com.shrey.downloader.domain.model.ChannelContentTab
import com.shrey.downloader.domain.model.ChannelScopePreset
import com.shrey.downloader.domain.model.DownloadMode
import com.shrey.downloader.domain.model.EngineState
import com.shrey.downloader.domain.model.FormatOption
import com.shrey.downloader.domain.model.MediaSourceType
import com.shrey.downloader.domain.model.ShreyError
import com.shrey.downloader.domain.model.ThumbnailOutputFormat
import com.shrey.downloader.navigation.LocalShreyBottomBarPadding
import com.shrey.downloader.navigation.rememberShreyBottomScrollClearance

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    contentBottomPadding: PaddingValues? = null,
    modifier: Modifier = Modifier
) {
    val urlInput by viewModel.urlInput.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val updateSnapshot = viewModel.updateSnapshot?.collectAsStateWithLifecycle()?.value
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val effectiveBottomPadding = contentBottomPadding ?: LocalShreyBottomBarPadding.current

    HomeScreenContent(
        urlInput = urlInput,
        uiState = uiState,
        engineState = engineState,
        updateSnapshot = updateSnapshot,
        onUrlChanged = viewModel::onUrlChanged,
        onPasteClick = {
            val text = clipboard.getText()?.text
            viewModel.onPasteClipboardText(text)
        },
        onClearClick = viewModel::onClearUrl,
        onAnalyzeClick = {
            keyboard?.hide()
            viewModel.analyzeCurrentUrl()
        },
        onRetryEngine = viewModel::retryEngineInit,
        onCheckEngineUpdates = viewModel::checkEngineUpdatesFromError,
        onModeSelected = viewModel::onModeSelected,
        onOptionSelected = viewModel::onOptionSelected,
        onAudioFormatSelected = viewModel::onAudioFormatSelected,
        onThumbnailFormatSelected = viewModel::onThumbnailFormatSelected,
        onToggleTechnicalDetails = viewModel::onToggleTechnicalDetails,
        onStartDownload = viewModel::startSelectedDownload,
        onOpenDownloads = onOpenDownloads,
        onOpenSettings = onOpenSettings,
        contentBottomPadding = effectiveBottomPadding,
        onChooseAmbiguousVideo = viewModel::resolveAmbiguousAsSingleVideo,
        onChooseAmbiguousPlaylist = viewModel::resolveAmbiguousAsPlaylist,
        onTogglePlaylistEntry = viewModel::togglePlaylistEntry,
        onSelectAllPlaylist = viewModel::selectAllPlaylistEntries,
        onDeselectAllPlaylist = viewModel::deselectAllPlaylistEntries,
        onLoadMorePlaylist = viewModel::loadMorePlaylistEntries,
        onStartPlaylistBatch = viewModel::startPlaylistBatch,
        onBulkQualitySelected = viewModel::setBulkQuality,
        onBulkAudioFormatSelected = viewModel::setBulkAudioFormat,
        onBulkWifiOnlyChanged = viewModel::setBulkWifiOnly,
        onToggleChannelTab = viewModel::toggleChannelTab,
        onChannelScopeSelected = viewModel::setChannelScope,
        onChannelCustomLimitChanged = viewModel::setChannelCustomLimit,
        onLoadMoreChannel = viewModel::loadMoreChannelEntries,
        onRequestChannelConfirm = viewModel::requestChannelConfirmation,
        onDismissChannelConfirm = viewModel::dismissChannelConfirmation,
        onConfirmChannelBatch = viewModel::confirmAndStartChannelBatch,
        modifier = modifier
    )
}

@Composable
fun HomeScreenContent(
    urlInput: String,
    uiState: HomeUiState,
    engineState: EngineState,
    onUrlChanged: (String) -> Unit,
    onPasteClick: () -> Unit,
    onClearClick: () -> Unit,
    onAnalyzeClick: () -> Unit,
    onRetryEngine: () -> Unit,
    onModeSelected: (DownloadMode) -> Unit,
    onOptionSelected: (FormatOption) -> Unit,
    onAudioFormatSelected: (AudioOutputFormat) -> Unit,
    onThumbnailFormatSelected: (ThumbnailOutputFormat) -> Unit,
    onToggleTechnicalDetails: () -> Unit,
    onStartDownload: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    contentBottomPadding: PaddingValues,
    modifier: Modifier = Modifier,
    updateSnapshot: UpdateSnapshot? = null,
    onCheckEngineUpdates: (() -> Unit)? = null,
    onChooseAmbiguousVideo: () -> Unit = {},
    onChooseAmbiguousPlaylist: () -> Unit = {},
    onTogglePlaylistEntry: (String) -> Unit = {},
    onSelectAllPlaylist: () -> Unit = {},
    onDeselectAllPlaylist: () -> Unit = {},
    onLoadMorePlaylist: () -> Unit = {},
    onStartPlaylistBatch: () -> Unit = {},
    onBulkQualitySelected: (BulkQualityPreset) -> Unit = {},
    onBulkAudioFormatSelected: (AudioOutputFormat) -> Unit = {},
    onBulkWifiOnlyChanged: (Boolean) -> Unit = {},
    onToggleChannelTab: (ChannelContentTab) -> Unit = {},
    onChannelScopeSelected: (ChannelScopePreset) -> Unit = {},
    onChannelCustomLimitChanged: (Int) -> Unit = {},
    onLoadMoreChannel: () -> Unit = {},
    onRequestChannelConfirm: () -> Unit = {},
    onDismissChannelConfirm: () -> Unit = {},
    onConfirmChannelBatch: () -> Unit = {}
) {
    val palette = ShreyThemeTokens.palette
    val density = LocalDensity.current
    val statusBarTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val bottomScrollClearance = rememberShreyBottomScrollClearance(contentBottomPadding)
    val scrollState = rememberScrollState()
    val topBlurProgress by remember {
        derivedStateOf { (scrollState.value / 72f).coerceIn(0f, 1f) }
    }

    ShreyTopGradualBlurContainer(
        scrollProgress = topBlurProgress,
        scrollOffsetProvider = { scrollState.value },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .verticalScroll(scrollState)
                .padding(start = 20.dp, end = 20.dp, top = statusBarTop + 16.dp)
                .testTag("home_scroll_container"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top Brand Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        text = "SHREY",
                        style = MaterialTheme.typography.displaySmall.copy(
                            letterSpacing = 3.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = palette.textPrimary,
                        modifier = Modifier.testTag("shrey_wordmark")
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Your media. Your way.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary
                    )
                }

                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(CircleShape)
                        .background(palette.glassFillPrimary)
                        .border(0.75.dp, palette.glassBorderTop, CircleShape)
                        .testTag("home_settings_shortcut")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Open Settings",
                        tint = palette.textPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Engine Readiness Status Pill
            EngineStatusPill(
                engineState = engineState,
                onRetry = onRetryEngine
            )

            // URL Input & Primary Analyze Action
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ShreyGlassTextField(
                    value = urlInput,
                    onValueChange = onUrlChanged,
                    placeholder = "Paste video, playlist, or channel link",
                    onPasteClick = onPasteClick,
                    onClearClick = onClearClick,
                    onSubmit = onAnalyzeClick,
                    enabled = uiState !is HomeUiState.Analyzing
                )

                val isAnalyzing = uiState is HomeUiState.Analyzing
                ShreyPrimaryPillButton(
                    text = if (isAnalyzing) "Analyzing…" else "Analyze Link",
                    onClick = onAnalyzeClick,
                    enabled = urlInput.isNotBlank() && !isAnalyzing,
                    loading = isAnalyzing,
                    leadingIcon = Icons.Outlined.Search,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("analyze_link_button")
                )
            }

            // Dynamic Content Area: Empty / Analyzing / Ambiguous / Single / Playlist / Channel / Error
            AnimatedContent(
                targetState = uiState,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "home_state_transition"
            ) { state ->
                when (state) {
                    HomeUiState.Idle -> {
                        ShreyEmptyState(
                            icon = Icons.Outlined.AutoAwesome,
                            title = "Ready when you are",
                            body = "Paste or share a video, playlist, or YouTube channel link to inspect real streams, choose your format, and save locally to your device.",
                            modifier = Modifier.testTag("home_empty_state")
                        )
                    }

                    is HomeUiState.Analyzing -> {
                        val (title, subtitle) = when (state.detectedType) {
                            MediaSourceType.PLAYLIST ->
                                "Inspecting playlist entries" to "Reading playlist items via flat yt-dlp extraction without downloading media…"
                            MediaSourceType.CHANNEL ->
                                "Inspecting YouTube channel" to "Discovering channel videos, Shorts, and completed streams without downloading media…"
                            else ->
                                "Inspecting media streams" to "Running local yt-dlp metadata extraction without downloading media…"
                        }
                        ShreyGlassCard(
                            modifier = Modifier.testTag("home_analyzing_card"),
                            contentPadding = PaddingValues(24.dp)
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.textPrimary
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.textSecondary
                            )
                        }
                    }

                    is HomeUiState.AmbiguousUrl -> {
                        AmbiguousUrlChoiceCard(
                            state = state,
                            onChooseSingleVideo = onChooseAmbiguousVideo,
                            onChoosePlaylist = onChooseAmbiguousPlaylist
                        )
                    }

                    is HomeUiState.Ready -> {
                        MediaPreviewCard(
                            state = state,
                            onModeSelected = onModeSelected,
                            onOptionSelected = onOptionSelected,
                            onAudioFormatSelected = onAudioFormatSelected,
                            onThumbnailFormatSelected = onThumbnailFormatSelected,
                            onToggleTechnicalDetails = onToggleTechnicalDetails,
                            onStartDownload = onStartDownload,
                            onOpenDownloads = onOpenDownloads
                        )
                    }

                    is HomeUiState.PlaylistReady -> {
                        PlaylistPreviewCard(
                            state = state,
                            onToggleEntry = onTogglePlaylistEntry,
                            onSelectAll = onSelectAllPlaylist,
                            onDeselectAll = onDeselectAllPlaylist,
                            onLoadMore = onLoadMorePlaylist,
                            onQualitySelected = onBulkQualitySelected,
                            onAudioFormatSelected = onBulkAudioFormatSelected,
                            onWifiOnlyChanged = onBulkWifiOnlyChanged,
                            onStartPlaylistDownload = onStartPlaylistBatch,
                            onOpenDownloads = onOpenDownloads
                        )
                    }

                    is HomeUiState.ChannelReady -> {
                        ChannelPreviewCard(
                            state = state,
                            onToggleTab = onToggleChannelTab,
                            onScopeSelected = onChannelScopeSelected,
                            onCustomLimitChanged = onChannelCustomLimitChanged,
                            onLoadMore = onLoadMoreChannel,
                            onQualitySelected = onBulkQualitySelected,
                            onAudioFormatSelected = onBulkAudioFormatSelected,
                            onWifiOnlyChanged = onBulkWifiOnlyChanged,
                            onRequestConfirm = onRequestChannelConfirm,
                            onDismissConfirm = onDismissChannelConfirm,
                            onConfirmStart = onConfirmChannelBatch,
                            onOpenDownloads = onOpenDownloads
                        )
                    }

                    is HomeUiState.Error -> {
                        val isExtractorRelated = state.error is ShreyError.MediaRequestForbidden ||
                            state.error is ShreyError.ExtractorCompatibilityError ||
                            state.error is ShreyError.MissingRuntimeDependency ||
                            state.error is ShreyError.ExtractorFailure
                        val installedEngineVer = (engineState as? EngineState.Ready)?.diagnostics?.ytDlpVersion
                            ?: updateSnapshot?.engine?.installedVersion
                            ?: EngineCoordinator.BUNDLED_YT_DLP_VERSION
                        val updateFeedback = if (isExtractorRelated && updateSnapshot != null) {
                            when {
                                updateSnapshot.checkState == UpdateCheckState.CHECKING ->
                                    "Checking official release metadata…"
                                updateSnapshot.engine.updateAvailable && updateSnapshot.engine.availableVersion != null ->
                                    "yt-dlp ${updateSnapshot.engine.availableVersion} detected — review in Settings > Updates."
                                updateSnapshot.lastCheckedAtMillis != null ->
                                    "Engine is up to date ($installedEngineVer)."
                                else -> null
                            }
                        } else {
                            null
                        }

                        ShreyErrorCard(
                            title = state.error.title,
                            message = state.error.userMessage,
                            actionHint = state.error.actionHint,
                            technicalDetails = state.error.technicalDetails,
                            onRetry = onAnalyzeClick,
                            engineVersionLabel = if (isExtractorRelated) "yt-dlp $installedEngineVer" else null,
                            updateCheckStatusLabel = updateFeedback,
                            onCheckEngineUpdates = if (isExtractorRelated) onCheckEngineUpdates else null,
                            isCheckingUpdates = updateSnapshot?.checkState == UpdateCheckState.CHECKING,
                            modifier = Modifier.testTag("home_error_card")
                        )
                    }
                }
            }

            Spacer(
                modifier = Modifier
                    .height(bottomScrollClearance)
                    .testTag("home_bottom_scroll_spacer")
            )
        }
    }
}

@Composable
private fun EngineStatusPill(
    engineState: EngineState,
    onRetry: () -> Unit
) {
    val palette = ShreyThemeTokens.palette
    val (dotColor, label, clickable) = when (engineState) {
        EngineState.Idle, EngineState.Initializing -> Triple(
            palette.statusWarning,
            "Preparing local yt-dlp & FFmpeg…",
            false
        )
        is EngineState.Ready -> Triple(
            palette.statusSuccess,
            if (engineState.diagnostics.ffmpegAvailable) {
                "Local engine ready · yt-dlp + FFmpeg"
            } else {
                "Local engine ready · yt-dlp"
            },
            false
        )
        is EngineState.Failed -> Triple(
            palette.statusError,
            "Engine init failed · Tap to retry",
            true
        )
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(palette.glassFillPrimary)
            .border(0.75.dp, palette.glassBorderTop, RoundedCornerShape(100.dp))
            .then(if (clickable) Modifier.clickable(onClick = onRetry) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .testTag("engine_status_pill"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary
        )
    }
}
