package com.shrey.downloader.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shrey.downloader.core.design.ShreyGlassButton
import com.shrey.downloader.core.design.ShreyGlassCard
import com.shrey.downloader.core.design.ShreyProgressBar
import com.shrey.downloader.core.design.ShreySegmentedControl
import com.shrey.downloader.core.design.ShreyThemeTokens
import com.shrey.downloader.core.update.UpdateChannel
import com.shrey.downloader.core.update.UpdateCheckState
import com.shrey.downloader.core.update.UpdateSnapshot
import com.shrey.downloader.core.utilities.Formatters
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsUpdatesCard(
    snapshot: UpdateSnapshot,
    onCheckNow: () -> Unit,
    onDownloadAppApk: () -> Unit,
    onOpenReleasePage: (String) -> Unit,
    onOpenVerifiedApk: (String) -> Unit,
    onAutoCheckChanged: (Boolean) -> Unit,
    onAutoInstallEngineChanged: (Boolean) -> Unit,
    onNotifyOnUpdatesChanged: (Boolean) -> Unit,
    onChannelSelected: (UpdateChannel) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = ShreyThemeTokens.palette
    var showReleaseNotesDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }

    val isChecking = snapshot.checkState == UpdateCheckState.CHECKING
    val statusBadgeColor = when (snapshot.checkState) {
        UpdateCheckState.UPDATE_AVAILABLE -> palette.statusWarning
        UpdateCheckState.UP_TO_DATE -> palette.statusSuccess
        UpdateCheckState.FAILED -> palette.statusError
        else -> palette.textSecondary
    }

    ShreyGlassCard(
        modifier = modifier.testTag("settings_updates_card"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "UPDATES",
                style = MaterialTheme.typography.labelSmall,
                color = palette.textTertiary
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(100.dp))
                    .background(palette.pillSelectedFill)
                    .border(0.75.dp, palette.glassBorderTop, RoundedCornerShape(100.dp))
                    .padding(horizontal = 9.dp, vertical = 3.dp)
                    .testTag("updates_status_badge")
            ) {
                Text(
                    text = snapshot.checkState.label,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = statusBadgeColor,
                    maxLines = 1
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // 1. Shrey App status
        UpdateCategoryHeader("Shrey")
        Spacer(Modifier.height(4.dp))
        UpdateMetaRow("Installed", snapshot.app.installedVersionName)
        Spacer(Modifier.height(3.dp))
        UpdateMetaRow(
            label = "Latest (${snapshot.preferences.channel.label})",
            value = snapshot.app.latestVersionName ?: "Not checked"
        )
        Spacer(Modifier.height(3.dp))
        UpdateMetaRow(
            label = "Availability",
            value = when {
                snapshot.app.updateAvailable -> {
                    val size = snapshot.app.apkSizeBytes?.takeIf { it > 0L }
                        ?.let { " · ${Formatters.formatBytes(it)}" }.orEmpty()
                    "Update available$size"
                }
                snapshot.lastCheckedAtMillis != null -> "Up to date"
                else -> "Check to inspect"
            },
            valueColor = if (snapshot.app.updateAvailable) palette.statusWarning else palette.textPrimary
        )

        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = palette.divider)
        Spacer(Modifier.height(10.dp))

        // 2. yt-dlp Engine status
        UpdateCategoryHeader("yt-dlp")
        Spacer(Modifier.height(4.dp))
        UpdateMetaRow(
            label = "Installed",
            value = "${snapshot.engine.installedVersion} · EJS ${snapshot.engine.installedEjsVersion}"
        )
        Spacer(Modifier.height(3.dp))
        UpdateMetaRow(
            label = "Available stable",
            value = snapshot.engine.availableVersion ?: "Not checked"
        )
        Spacer(Modifier.height(3.dp))
        UpdateMetaRow(
            label = "Compatibility",
            value = snapshot.engine.compatibilityStatus.label,
            valueColor = if (snapshot.engine.updateAvailable) palette.statusWarning else palette.statusSuccess
        )

        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = palette.divider)
        Spacer(Modifier.height(10.dp))

        // 3. FFmpeg bundled native status
        UpdateCategoryHeader("FFmpeg")
        Spacer(Modifier.height(4.dp))
        UpdateMetaRow("Bundled", snapshot.ffmpeg.bundledVersion)
        Spacer(Modifier.height(3.dp))
        UpdateMetaRow(
            label = "Delivery",
            value = if (snapshot.ffmpeg.availableViaAppUpdate) {
                "Newer signed Shrey APK available"
            } else {
                "Signed Shrey APK releases"
            },
            valueColor = if (snapshot.ffmpeg.availableViaAppUpdate) palette.statusWarning else palette.textSecondary
        )

        if (!snapshot.lastErrorMessage.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = snapshot.lastErrorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = palette.statusError
            )
        }

        // APK download progress & verification banner
        if (snapshot.app.isDownloadingApk) {
            Spacer(Modifier.height(10.dp))
            ShreyProgressBar(
                progressFraction = snapshot.app.apkDownloadProgress ?: 0f,
                indeterminate = snapshot.app.apkDownloadProgress == null
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = snapshot.app.verificationStatusMessage ?: "Downloading official Shrey APK…",
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary
            )
        } else if (!snapshot.app.verificationError.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = snapshot.app.verificationError,
                style = MaterialTheme.typography.bodySmall,
                color = palette.statusError
            )
        } else if (!snapshot.app.verificationStatusMessage.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = snapshot.app.verificationStatusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = palette.statusSuccess
            )
        }

        Spacer(Modifier.height(12.dp))

        // Primary compact actions row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ShreyGlassButton(
                text = if (isChecking) "Checking…" else "Check Now",
                leadingIcon = Icons.Outlined.Refresh,
                enabled = !isChecking,
                onClick = onCheckNow,
                height = 42.dp,
                horizontalPadding = 10.dp,
                modifier = Modifier
                    .weight(1f)
                    .testTag("updates_check_now_button")
            )
            ShreyGlassButton(
                text = "Diagnostics",
                leadingIcon = Icons.Outlined.Info,
                onClick = { showDiagnosticsDialog = true },
                contentColor = palette.textSecondary,
                height = 42.dp,
                horizontalPadding = 10.dp,
                modifier = Modifier
                    .weight(1f)
                    .testTag("updates_diagnostics_button")
            )
        }

        if (snapshot.app.updateAvailable || !snapshot.app.releaseNotes.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ShreyGlassButton(
                    text = "Release Notes",
                    leadingIcon = Icons.Outlined.Description,
                    onClick = { showReleaseNotesDialog = true },
                    contentColor = palette.textSecondary,
                    height = 42.dp,
                    horizontalPadding = 10.dp,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("updates_release_notes_button")
                )
                if (snapshot.app.verifiedApkUri != null) {
                    ShreyGlassButton(
                        text = "Install APK",
                        leadingIcon = Icons.AutoMirrored.Outlined.OpenInNew,
                        onClick = { onOpenVerifiedApk(snapshot.app.verifiedApkUri) },
                        height = 42.dp,
                        horizontalPadding = 10.dp,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("updates_install_apk_button")
                    )
                } else if (snapshot.app.updateAvailable && !snapshot.app.apkDownloadUrl.isNullOrBlank()) {
                    ShreyGlassButton(
                        text = "Download APK",
                        leadingIcon = Icons.Outlined.Download,
                        enabled = !snapshot.app.isDownloadingApk,
                        onClick = onDownloadAppApk,
                        height = 42.dp,
                        horizontalPadding = 10.dp,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("updates_download_apk_button")
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = palette.divider)
        Spacer(Modifier.height(10.dp))

        // Compact update preferences
        Text(
            text = "Release Channel",
            style = MaterialTheme.typography.labelMedium,
            color = palette.textSecondary
        )
        Spacer(Modifier.height(6.dp))
        ShreySegmentedControl(
            items = UpdateChannel.entries,
            selectedItem = snapshot.preferences.channel,
            labelFor = { it.label },
            onSelect = onChannelSelected,
            testTagPrefix = "update_channel",
            itemHeight = 38.dp
        )

        Spacer(Modifier.height(8.dp))
        UpdateToggleRow(
            title = "Daily update checks",
            subtitle = "Background check every 24h on connected network",
            checked = snapshot.preferences.autoCheckEnabled,
            onCheckedChange = onAutoCheckChanged,
            testTag = "toggle_auto_check_updates"
        )
        HorizontalDivider(color = palette.divider)
        UpdateToggleRow(
            title = "Update notifications",
            subtitle = "Alert when a new Shrey or yt-dlp release is found",
            checked = snapshot.preferences.notifyOnUpdates,
            onCheckedChange = onNotifyOnUpdatesChanged,
            testTag = "toggle_update_notifications"
        )
        HorizontalDivider(color = palette.divider)
        UpdateToggleRow(
            title = "Auto-install signed engine updates",
            subtitle = if (snapshot.engine.canSafelyActivateAtRuntime) {
                "Only activates maintainer-signed compatible yt-dlp manifests"
            } else {
                "Disabled — requires maintainer-signed manifest trust root"
            },
            checked = snapshot.preferences.autoInstallEngineUpdates,
            enabled = snapshot.engine.canSafelyActivateAtRuntime,
            onCheckedChange = onAutoInstallEngineChanged,
            testTag = "toggle_auto_install_engine"
        )
    }

    if (showReleaseNotesDialog) {
        val releaseUrl = snapshot.app.releasePageUrl ?: "https://github.com/NoWayShub/Shrey/releases"
        AlertDialog(
            onDismissRequest = { showReleaseNotesDialog = false },
            title = {
                Text(snapshot.app.latestTagName?.let { "Shrey $it" } ?: "Shrey Releases")
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = snapshot.app.releaseNotes?.takeIf { it.isNotBlank() }
                            ?: "No release notes cached yet. Tap Check Now or open the official GitHub Releases page.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showReleaseNotesDialog = false
                        onOpenReleasePage(releaseUrl)
                    }
                ) {
                    Text("Open Release Page")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReleaseNotesDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showDiagnosticsDialog) {
        val checkedLabel = snapshot.lastCheckedAtMillis?.let {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
        } ?: "Never"

        AlertDialog(
            onDismissRequest = { showDiagnosticsDialog = false },
            title = { Text("Update Diagnostics") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = buildString {
                            appendLine("Last checked: $checkedLabel")
                            appendLine("Shrey: ${snapshot.app.installedVersionName} (code ${snapshot.app.installedVersionCode})")
                            appendLine("yt-dlp installed: ${snapshot.engine.installedVersion}")
                            appendLine("yt-dlp last-known-good: ${snapshot.engine.lastKnownGoodVersion}")
                            appendLine("yt-dlp-ejs: ${snapshot.engine.installedEjsVersion}")
                            appendLine("Embedded Python: ${snapshot.engine.installedPythonVersion}")
                            appendLine("JS Runtime: QuickJS ${snapshot.engine.installedQuickJsVersion}")
                            appendLine("Bundled FFmpeg: ${snapshot.ffmpeg.bundledVersion}")
                            appendLine("Signed manifest key: ${if (snapshot.engine.canSafelyActivateAtRuntime) "Configured" else "Not configured (runtime auto-install locked OFF)"}")
                            appendLine()
                            append(snapshot.distributionPolicyNote)
                        },
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDiagnosticsDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun UpdateCategoryHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = ShreyThemeTokens.palette.textPrimary
    )
}

@Composable
private fun UpdateMetaRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = ShreyThemeTokens.palette.textPrimary
) {
    val palette = ShreyThemeTokens.palette
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
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
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.58f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun UpdateToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
    enabled: Boolean = true
) {
    val palette = ShreyThemeTokens.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) palette.textPrimary else palette.textSecondary
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
            enabled = enabled,
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
