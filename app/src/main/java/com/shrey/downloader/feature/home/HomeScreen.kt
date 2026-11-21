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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.shrey.downloader.core.design.ShreyTopGradualBlur
import com.shrey.downloader.domain.model.EngineState

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    contentBottomPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val urlInput by viewModel.urlInput.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    HomeScreenContent(
        urlInput = urlInput,
        uiState = uiState,
        engineState = engineState,
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
        onModeSelected = viewModel::onModeSelected,
        onOptionSelected = viewModel::onOptionSelected,
        onAudioFormatSelected = viewModel::onAudioFormatSelected,
        onThumbnailFormatSelected = viewModel::onThumbnailFormatSelected,
        onToggleTechnicalDetails = viewModel::onToggleTechnicalDetails,
        onStartDownload = viewModel::startSelectedDownload,
        onOpenDownloads = onOpenDownloads,
        onOpenSettings = onOpenSettings,
        contentBottomPadding = contentBottomPadding,
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
    onModeSelected: (com.shrey.downloader.domain.model.DownloadMode) -> Unit,
    onOptionSelected: (com.shrey.downloader.domain.model.FormatOption) -> Unit,
    onAudioFormatSelected: (com.shrey.downloader.domain.model.AudioOutputFormat) -> Unit,
    onThumbnailFormatSelected: (com.shrey.downloader.domain.model.ThumbnailOutputFormat) -> Unit,
    onToggleTechnicalDetails: () -> Unit,
    onStartDownload: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    contentBottomPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val palette = ShreyThemeTokens.palette
    val scrollState = rememberScrollState()
    val topBlurProgress by remember {
        derivedStateOf { (scrollState.value / 96f).coerceIn(0f, 1f) }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .verticalScroll(scrollState)
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp, bottom = contentBottomPadding.calculateBottomPadding() + 8.dp),
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
                    placeholder = "Paste a media link",
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

            // Dynamic Content Area: Empty / Analyzing / Preview / Error
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
                            body = "Paste or share a supported link to inspect real available streams, choose your format, and save locally to your device.",
                            modifier = Modifier.testTag("home_empty_state")
                        )
                    }

                    is HomeUiState.Analyzing -> {
                        ShreyGlassCard(
                            modifier = Modifier.testTag("home_analyzing_card"),
                            contentPadding = PaddingValues(24.dp)
                        ) {
                            Text(
                                text = "Inspecting media streams",
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.textPrimary
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Running local yt-dlp metadata extraction without downloading media…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.textSecondary
                            )
                        }
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

                    is HomeUiState.Error -> {
                        ShreyErrorCard(
                            title = state.error.title,
                            message = state.error.userMessage,
                            actionHint = state.error.actionHint,
                            technicalDetails = state.error.technicalDetails,
                            onRetry = onAnalyzeClick,
                            modifier = Modifier.testTag("home_error_card")
                        )
                    }
                }
            }
        }

        ShreyTopGradualBlur(
            scrollProgress = topBlurProgress,
            modifier = Modifier.align(Alignment.TopCenter)
        )
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
