package com.shrey.downloader.feature.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.shrey.downloader.core.design.ShreyGlassButton
import com.shrey.downloader.core.design.ShreyGlassCard
import com.shrey.downloader.core.design.ShreySegmentedControl
import com.shrey.downloader.core.design.ShreyThemeTokens
import com.shrey.downloader.core.design.ShreyTopGradualBlurContainer
import com.shrey.downloader.core.design.rememberShreyHaptics
import com.shrey.downloader.core.update.UpdateChannel
import com.shrey.downloader.core.update.UpdateSnapshot
import com.shrey.downloader.domain.model.AudioOutputFormat
import com.shrey.downloader.domain.model.DefaultVideoQuality
import com.shrey.downloader.domain.model.DownloadMode
import com.shrey.downloader.domain.model.EngineState
import com.shrey.downloader.domain.model.GlassEffectMode
import com.shrey.downloader.domain.model.ShreySettings
import com.shrey.downloader.domain.model.ThemeMode
import com.shrey.downloader.navigation.LocalShreyBottomBarPadding
import com.shrey.downloader.navigation.rememberShreyBottomScrollClearance

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenAbout: () -> Unit,
    onOpenLicenses: () -> Unit,
    contentBottomPadding: PaddingValues? = null,
    modifier: Modifier = Modifier
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val updateSnapshot by viewModel.updateSnapshot.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val effectiveBottomPadding = contentBottomPadding ?: LocalShreyBottomBarPadding.current

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val label = DocumentFile.fromTreeUri(context, treeUri)?.name ?: "Custom Folder"
            viewModel.setCustomDestination(treeUri.toString(), label)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.setNotificationsEnabled(granted)
    }

    SettingsScreenContent(
        settings = settings,
        engineState = engineState,
        updateSnapshot = updateSnapshot,
        onThemeSelected = viewModel::setThemeMode,
        onGlassModeSelected = viewModel::setGlassEffectMode,
        onDefaultModeSelected = viewModel::setDefaultDownloadMode,
        onDefaultVideoQualitySelected = viewModel::setDefaultVideoQuality,
        onDefaultAudioFormatSelected = viewModel::setDefaultAudioFormat,
        onChooseFolder = { folderPicker.launch(null) },
        onResetFolder = { viewModel.setCustomDestination(null, null) },
        onAllowMobileDataChanged = viewModel::setAllowMobileData,
        onHapticsChanged = viewModel::setHapticsEnabled,
        onNotificationsChanged = { enable ->
            if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.setNotificationsEnabled(enable)
            }
        },
        onRetryEngine = viewModel::retryEngineInit,
        onCheckUpdatesNow = viewModel::checkForUpdatesNow,
        onDownloadAppApk = viewModel::downloadAndVerifyAppUpdate,
        onOpenReleasePage = { url ->
            runCatching {
                context.startActivity(viewModel.apkUpdateVerifier.buildReleasePageIntent(url))
            }.onFailure {
                Toast.makeText(context, "Unable to open release page", Toast.LENGTH_SHORT).show()
            }
        },
        onOpenVerifiedApk = { uriString ->
            runCatching {
                context.startActivity(viewModel.apkUpdateVerifier.buildOpenVerifiedApkIntent(uriString))
            }.onFailure {
                Toast.makeText(
                    context,
                    "Verified APK saved in Downloads/Shrey. Open it from Files to install.",
                    Toast.LENGTH_LONG
                ).show()
            }
        },
        onAutoCheckUpdatesChanged = viewModel::setAutoCheckUpdates,
        onAutoInstallEngineChanged = viewModel::setAutoInstallEngineUpdates,
        onNotifyOnUpdatesChanged = viewModel::setNotifyOnUpdates,
        onUpdateChannelSelected = viewModel::setUpdateChannel,
        onOpenLicenses = onOpenLicenses,
        onOpenAbout = onOpenAbout,
        contentBottomPadding = effectiveBottomPadding,
        modifier = modifier
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreenContent(
    settings: ShreySettings,
    engineState: EngineState,
    onThemeSelected: (ThemeMode) -> Unit,
    onGlassModeSelected: (GlassEffectMode) -> Unit,
    onDefaultModeSelected: (DownloadMode) -> Unit,
    onDefaultVideoQualitySelected: (DefaultVideoQuality) -> Unit,
    onDefaultAudioFormatSelected: (AudioOutputFormat) -> Unit,
    onChooseFolder: () -> Unit,
    onResetFolder: () -> Unit,
    onAllowMobileDataChanged: (Boolean) -> Unit,
    onHapticsChanged: (Boolean) -> Unit,
    onNotificationsChanged: (Boolean) -> Unit,
    onRetryEngine: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenAbout: () -> Unit,
    contentBottomPadding: PaddingValues,
    modifier: Modifier = Modifier,
    updateSnapshot: UpdateSnapshot? = null,
    onCheckUpdatesNow: () -> Unit = {},
    onDownloadAppApk: () -> Unit = {},
    onOpenReleasePage: (String) -> Unit = {},
    onOpenVerifiedApk: (String) -> Unit = {},
    onAutoCheckUpdatesChanged: (Boolean) -> Unit = {},
    onAutoInstallEngineChanged: (Boolean) -> Unit = {},
    onNotifyOnUpdatesChanged: (Boolean) -> Unit = {},
    onUpdateChannelSelected: (UpdateChannel) -> Unit = {}
) {
    val palette = ShreyThemeTokens.palette
    val haptics = rememberShreyHaptics()
    val density = LocalDensity.current
    val statusBarTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val bottomScrollClearance = rememberShreyBottomScrollClearance(contentBottomPadding)
    val scrollState = rememberScrollState()
    val topBlurProgress by remember {
        derivedStateOf { (scrollState.value / 72f).coerceIn(0f, 1f) }
    }
    val cardPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)

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
                .padding(start = 18.dp, end = 18.dp, top = statusBarTop + 16.dp)
                .testTag("settings_scroll_container"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 2.dp)) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    color = palette.textPrimary
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Appearance, defaults, storage & local engine",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary
                )
            }

            // Appearance
            ShreyGlassCard(contentPadding = cardPadding) {
                SettingGroupTitle("Appearance")
                Spacer(Modifier.height(10.dp))
                SettingSubLabel("Theme")
                Spacer(Modifier.height(6.dp))
                ShreySegmentedControl(
                    items = ThemeMode.entries,
                    selectedItem = settings.themeMode,
                    labelFor = { it.label },
                    onSelect = onThemeSelected,
                    testTagPrefix = "theme",
                    itemHeight = 40.dp
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = palette.divider)
                Spacer(Modifier.height(10.dp))
                SettingSubLabel("Glass Effects")
                Spacer(Modifier.height(6.dp))
                ShreySegmentedControl(
                    items = GlassEffectMode.entries,
                    selectedItem = settings.glassEffectMode,
                    labelFor = { it.label },
                    onSelect = onGlassModeSelected,
                    testTagPrefix = "glass",
                    itemHeight = 40.dp
                )
            }

            // Download Defaults
            ShreyGlassCard(contentPadding = cardPadding) {
                SettingGroupTitle("Download Defaults")
                Spacer(Modifier.height(10.dp))

                SettingSubLabel("Default Mode")
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DownloadMode.entries.forEach { mode ->
                        SettingPillChip(
                            label = mode.label,
                            selected = settings.defaultDownloadMode == mode,
                            onClick = {
                                haptics.tap()
                                onDefaultModeSelected(mode)
                            }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = palette.divider)
                Spacer(Modifier.height(10.dp))

                SettingSubLabel("Preferred Video Quality")
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DefaultVideoQuality.entries.forEach { quality ->
                        SettingPillChip(
                            label = quality.label,
                            selected = settings.defaultVideoQuality == quality,
                            onClick = {
                                haptics.tap()
                                onDefaultVideoQualitySelected(quality)
                            }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = palette.divider)
                Spacer(Modifier.height(10.dp))

                SettingSubLabel("Default Audio Format")
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AudioOutputFormat.entries.forEach { fmt ->
                        SettingPillChip(
                            label = fmt.label,
                            selected = settings.defaultAudioFormat == fmt,
                            onClick = {
                                haptics.tap()
                                onDefaultAudioFormatSelected(fmt)
                            }
                        )
                    }
                }
            }

            // Storage Destination
            ShreyGlassCard(contentPadding = cardPadding) {
                SettingGroupTitle("Storage Destination")
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (settings.customDestinationUri != null) {
                        "Custom folder: ${settings.customDestinationLabel ?: "Selected Folder"}"
                    } else {
                        "Default Android MediaStore (Movies/Shrey, Music/Shrey, Pictures/Shrey)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ShreyGlassButton(
                        text = "Choose Folder",
                        leadingIcon = Icons.Outlined.FolderOpen,
                        onClick = onChooseFolder,
                        height = 42.dp,
                        horizontalPadding = 12.dp,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("choose_folder_button")
                    )
                    if (settings.customDestinationUri != null) {
                        ShreyGlassButton(
                            text = "Use Default",
                            onClick = onResetFolder,
                            contentColor = palette.textSecondary,
                            height = 42.dp,
                            horizontalPadding = 12.dp
                        )
                    }
                }
            }

            // Transfers, Feedback & Notifications
            ShreyGlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                SettingGroupTitle("Behavior")
                Spacer(Modifier.height(4.dp))
                SettingToggleRow(
                    title = "Download over mobile data",
                    subtitle = "Allow transfers on metered cellular networks",
                    checked = settings.allowMobileData,
                    onCheckedChange = onAllowMobileDataChanged,
                    testTag = "toggle_mobile_data"
                )
                HorizontalDivider(color = palette.divider)
                SettingToggleRow(
                    title = "Haptic feedback",
                    subtitle = "Subtle tactile response on primary actions",
                    checked = settings.hapticsEnabled,
                    onCheckedChange = onHapticsChanged,
                    testTag = "toggle_haptics"
                )
                HorizontalDivider(color = palette.divider)
                SettingToggleRow(
                    title = "Download notifications",
                    subtitle = "Show progress and completion alerts",
                    checked = settings.notificationsEnabled,
                    onCheckedChange = onNotificationsChanged,
                    testTag = "toggle_notifications"
                )
            }

            // Local Engine Diagnostics
            ShreyGlassCard(contentPadding = cardPadding) {
                SettingGroupTitle("Local Extraction Engine")
                Spacer(Modifier.height(10.dp))
                when (engineState) {
                    EngineState.Idle, EngineState.Initializing -> {
                        Text(
                            text = "Initializing local yt-dlp and FFmpeg binaries…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.textSecondary
                        )
                    }
                    is EngineState.Ready -> {
                        val diag = engineState.diagnostics
                        DiagnosticRow("yt-dlp", diag.ytDlpVersion ?: "Bundled")
                        Spacer(Modifier.height(6.dp))
                        DiagnosticRow(
                            "FFmpeg",
                            if (diag.ffmpegAvailable) "${diag.ffmpegVersion} (merge & transcode)" else "Unavailable"
                        )
                        Spacer(Modifier.height(6.dp))
                        DiagnosticRow(
                            "JS Runtime (EJS)",
                            diag.jsRuntimeName ?: "Not detected"
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = diag.jsRuntimeNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textTertiary
                        )
                    }
                    is EngineState.Failed -> {
                        Text(
                            text = engineState.error.userMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.statusError
                        )
                        Spacer(Modifier.height(10.dp))
                        ShreyGlassButton(
                            text = "Retry Initialization",
                            leadingIcon = Icons.Outlined.Refresh,
                            onClick = onRetryEngine,
                            height = 42.dp
                        )
                    }
                }
            }

            // Intelligent Updates
            if (updateSnapshot != null) {
                SettingsUpdatesCard(
                    snapshot = updateSnapshot,
                    onCheckNow = onCheckUpdatesNow,
                    onDownloadAppApk = onDownloadAppApk,
                    onOpenReleasePage = onOpenReleasePage,
                    onOpenVerifiedApk = onOpenVerifiedApk,
                    onAutoCheckChanged = onAutoCheckUpdatesChanged,
                    onAutoInstallEngineChanged = onAutoInstallEngineChanged,
                    onNotifyOnUpdatesChanged = onNotifyOnUpdatesChanged,
                    onChannelSelected = onUpdateChannelSelected
                )
            }

            // About & Legal
            ShreyGlassCard(contentPadding = PaddingValues(vertical = 4.dp, horizontal = 16.dp)) {
                NavigationRow(
                    icon = Icons.Outlined.Description,
                    title = "Open-Source Licenses",
                    subtitle = "yt-dlp, youtubedl-android, FFmpeg & AndroidX",
                    onClick = onOpenLicenses,
                    testTag = "settings_licenses_row"
                )
                HorizontalDivider(color = palette.divider)
                NavigationRow(
                    icon = Icons.Outlined.Info,
                    title = "About Shrey",
                    subtitle = "Version ${BuildConfig.VERSION_NAME} · Local privacy statement",
                    onClick = onOpenAbout,
                    testTag = "settings_about_row"
                )
            }

            Spacer(
                modifier = Modifier
                    .height(bottomScrollClearance)
                    .testTag("settings_bottom_scroll_spacer")
            )
        }
    }
}

@Composable
private fun SettingGroupTitle(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = ShreyThemeTokens.palette.textTertiary
    )
}

@Composable
private fun SettingSubLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = ShreyThemeTokens.palette.textSecondary
    )
}

@Composable
private fun SettingPillChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val palette = ShreyThemeTokens.palette
    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = 34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) palette.accentSolid else palette.pillSelectedFill)
            .border(
                0.75.dp,
                if (selected) Color.Transparent else palette.glassBorderTop,
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            ),
            color = if (selected) palette.accentOnSolid else palette.textSecondary,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    val palette = ShreyThemeTokens.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 50.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = palette.textPrimary
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = palette.textTertiary
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
            colors = SwitchDefaults.colors(
                checkedThumbColor = palette.accentOnSolid,
                checkedTrackColor = palette.accentSolid,
                uncheckedThumbColor = palette.textSecondary,
                uncheckedTrackColor = palette.progressTrack
            )
        )
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    val palette = ShreyThemeTokens.palette
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary,
            modifier = Modifier.weight(0.42f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.58f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun NavigationRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    testTag: String
) {
    val palette = ShreyThemeTokens.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clickable(onClick = onClick)
            .testTag(testTag)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = palette.textPrimary,
            modifier = Modifier.size(19.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = palette.textPrimary
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = palette.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = palette.textTertiary,
            modifier = Modifier.size(20.dp)
        )
    }
}
