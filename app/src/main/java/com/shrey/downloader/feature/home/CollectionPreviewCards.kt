package com.shrey.downloader.feature.home

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.shrey.downloader.core.design.ShreyGlassButton
import com.shrey.downloader.core.design.ShreyGlassCard
import com.shrey.downloader.core.design.ShreyGlassSurface
import com.shrey.downloader.core.design.ShreyPrimaryPillButton
import com.shrey.downloader.core.design.ShreyThemeTokens
import com.shrey.downloader.core.design.rememberShreyHaptics
import com.shrey.downloader.core.utilities.Formatters
import com.shrey.downloader.domain.model.AudioOutputFormat
import com.shrey.downloader.domain.model.BulkQualityPreset
import com.shrey.downloader.domain.model.ChannelContentTab
import com.shrey.downloader.domain.model.ChannelScopePreset
import com.shrey.downloader.domain.model.CollectionEntry

@Composable
fun AmbiguousUrlChoiceCard(
    state: HomeUiState.AmbiguousUrl,
    onChooseSingleVideo: () -> Unit,
    onChoosePlaylist: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = ShreyThemeTokens.palette
    ShreyGlassCard(
        elevated = true,
        contentPadding = PaddingValues(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("ambiguous_url_card")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.PlaylistPlay,
                contentDescription = null,
                tint = palette.accentSolid,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = "Video inside a playlist",
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "This link references both a single video and a YouTube playlist. Choose how you want Shrey to inspect it — nothing is downloaded automatically.",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary
        )

        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ShreyPrimaryPillButton(
                text = "Download This Video",
                leadingIcon = Icons.Outlined.PlayCircleOutline,
                onClick = onChooseSingleVideo,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ambiguous_choose_video_button")
            )
            ShreyGlassButton(
                text = "Download Entire Playlist",
                leadingIcon = Icons.AutoMirrored.Outlined.PlaylistPlay,
                onClick = onChoosePlaylist,
                height = 50.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ambiguous_choose_playlist_button")
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaylistPreviewCard(
    state: HomeUiState.PlaylistReady,
    onToggleEntry: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onLoadMore: () -> Unit,
    onQualitySelected: (BulkQualityPreset) -> Unit,
    onAudioFormatSelected: (AudioOutputFormat) -> Unit,
    onWifiOnlyChanged: (Boolean) -> Unit,
    onStartPlaylistDownload: () -> Unit,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = ShreyThemeTokens.palette
    val preview = state.preview
    val selectedCount = state.selectedDownloadableEntries.size
    var visibleLimit by remember(preview.canonicalId) { mutableIntStateOf(20) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("playlist_preview_section"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Playlist Header Card
        ShreyGlassCard(
            elevated = true,
            contentPadding = PaddingValues(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 86.dp, height = 62.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.backgroundSecondary),
                    contentAlignment = Alignment.Center
                ) {
                    if (!preview.thumbnailUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = preview.thumbnailUrl,
                            contentDescription = "Playlist cover for ${preview.title}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.PlaylistPlay,
                            contentDescription = null,
                            tint = palette.textTertiary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    BulkTypeBadge(label = "PLAYLIST")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = preview.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("playlist_preview_title")
                    )
                    if (!preview.creator.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = preview.creator,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = buildString {
                            append(preview.countDisplayLabel)
                            if (preview.unavailableCount > 0) {
                                append(" · ${preview.unavailableCount} unavailable")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textTertiary,
                        modifier = Modifier.testTag("playlist_count_label")
                    )
                }
            }

            if (preview.warnings.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                preview.warnings.forEach { warning ->
                    WarningStrip(text = warning)
                }
            }
        }

        // Quality & Network Settings Card
        BulkQualityAndSafeguardsCard(
            qualityPreset = state.qualityPreset,
            audioOutputFormat = state.audioOutputFormat,
            wifiOnly = state.wifiOnly,
            availableStorageBytes = state.availableStorageBytes,
            onQualitySelected = onQualitySelected,
            onAudioFormatSelected = onAudioFormatSelected,
            onWifiOnlyChanged = onWifiOnlyChanged
        )

        // Selectable Video List Card
        ShreyGlassCard(contentPadding = PaddingValues(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Videos ($selectedCount selected)",
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.textPrimary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShreyGlassButton(
                        text = "Select All",
                        onClick = onSelectAll,
                        height = 36.dp,
                        horizontalPadding = 10.dp,
                        modifier = Modifier.testTag("playlist_select_all_button")
                    )
                    ShreyGlassButton(
                        text = "None",
                        onClick = onDeselectAll,
                        height = 36.dp,
                        horizontalPadding = 10.dp,
                        modifier = Modifier.testTag("playlist_deselect_all_button")
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            val displayed = preview.entries.take(visibleLimit)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                displayed.forEach { entry ->
                    PlaylistEntryRow(
                        entry = entry,
                        checked = entry.mediaId in state.selectedMediaIds,
                        onToggle = { onToggleEntry(entry.mediaId) }
                    )
                }
            }

            if (preview.entries.size > visibleLimit) {
                Spacer(Modifier.height(10.dp))
                ShreyGlassButton(
                    text = "Show more loaded (${preview.entries.size - visibleLimit} remaining)",
                    onClick = { visibleLimit += 25 },
                    height = 42.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("playlist_expand_loaded_button")
                )
            }

            if (preview.hasMoreEntries) {
                Spacer(Modifier.height(10.dp))
                ShreyGlassButton(
                    text = if (state.isLoadingMore) "Loading next page…" else "Load Next 50 Videos",
                    onClick = onLoadMore,
                    enabled = !state.isLoadingMore,
                    height = 44.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("playlist_load_more_button")
                )
            }
        }

        state.actionError?.let { msg ->
            WarningStrip(text = msg)
        }

        if (state.lastQueuedBatchId != null) {
            BatchQueuedBanner(
                text = "Playlist queued for sequential download",
                onOpenDownloads = onOpenDownloads
            )
        }

        ShreyPrimaryPillButton(
            text = if (selectedCount > 0) {
                "Download $selectedCount Video${if (selectedCount == 1) "" else "s"} · ${state.qualityPreset.label}"
            } else {
                "Select Videos to Download"
            },
            leadingIcon = Icons.Outlined.Download,
            enabled = selectedCount > 0,
            onClick = onStartPlaylistDownload,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("start_playlist_download_button")
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChannelPreviewCard(
    state: HomeUiState.ChannelReady,
    onToggleTab: (ChannelContentTab) -> Unit,
    onScopeSelected: (ChannelScopePreset) -> Unit,
    onCustomLimitChanged: (Int) -> Unit,
    onLoadMore: () -> Unit,
    onQualitySelected: (BulkQualityPreset) -> Unit,
    onAudioFormatSelected: (AudioOutputFormat) -> Unit,
    onWifiOnlyChanged: (Boolean) -> Unit,
    onRequestConfirm: () -> Unit,
    onDismissConfirm: () -> Unit,
    onConfirmStart: () -> Unit,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = ShreyThemeTokens.palette
    val haptics = rememberShreyHaptics()
    val preview = state.preview
    val scopedCount = state.scopedEntries.size

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("channel_preview_section"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Channel Identity Header
        ShreyGlassCard(
            elevated = true,
            contentPadding = PaddingValues(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(palette.backgroundSecondary),
                    contentAlignment = Alignment.Center
                ) {
                    if (!preview.thumbnailUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = preview.thumbnailUrl,
                            contentDescription = "Channel avatar for ${preview.title}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Person,
                            contentDescription = null,
                            tint = palette.textTertiary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    BulkTypeBadge(label = "YOUTUBE CHANNEL")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = preview.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("channel_preview_title")
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = preview.sourceUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${preview.downloadableCount} downloadable discovered · Active live broadcasts excluded",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary
                    )
                }
            }

            if (preview.unavailableTabNotes.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                preview.unavailableTabNotes.values.forEach { note ->
                    WarningStrip(text = note)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        // Content Categories & Download Scope Card
        ShreyGlassCard(contentPadding = PaddingValues(16.dp)) {
            Text(
                text = "Content Categories",
                style = MaterialTheme.typography.labelMedium,
                color = palette.textSecondary
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChannelContentTab.entries.forEach { tab ->
                    val isSelected = tab in state.selectedTabs
                    BulkSelectableChip(
                        label = tab.label,
                        selected = isSelected,
                        onClick = {
                            haptics.tap()
                            onToggleTab(tab)
                        },
                        testTag = "channel_tab_${tab.name.lowercase()}"
                    )
                }
            }
            if (state.isReloadingTabs) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Refreshing selected channel tabs…",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.accentSolid
                )
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = palette.divider)
            Spacer(Modifier.height(14.dp))

            Text(
                text = "Download Scope",
                style = MaterialTheme.typography.labelMedium,
                color = palette.textSecondary
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChannelScopePreset.entries.forEach { scope ->
                    BulkSelectableChip(
                        label = if (scope == ChannelScopePreset.CUSTOM) "Custom (${state.customLimit})" else scope.label,
                        selected = state.scopePreset == scope,
                        onClick = {
                            haptics.tap()
                            onScopeSelected(scope)
                        },
                        testTag = "channel_scope_${scope.name.lowercase()}"
                    )
                }
            }

            if (state.scopePreset == ChannelScopePreset.CUSTOM) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Custom limit:",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary
                    )
                    listOf(5, 25, 75, 150).forEach { count ->
                        BulkSelectableChip(
                            label = "$count",
                            selected = state.customLimit == count,
                            onClick = { onCustomLimitChanged(count) },
                            testTag = "channel_custom_$count"
                        )
                    }
                }
            }

            if (preview.hasMoreEntries) {
                Spacer(Modifier.height(12.dp))
                ShreyGlassButton(
                    text = if (state.isLoadingMore) "Discovering more…" else "Discover More Channel Videos",
                    onClick = onLoadMore,
                    enabled = !state.isLoadingMore,
                    height = 42.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("channel_load_more_button")
                )
            }
        }

        // Quality & Safeguards Card
        BulkQualityAndSafeguardsCard(
            qualityPreset = state.qualityPreset,
            audioOutputFormat = state.audioOutputFormat,
            wifiOnly = state.wifiOnly,
            availableStorageBytes = state.availableStorageBytes,
            onQualitySelected = onQualitySelected,
            onAudioFormatSelected = onAudioFormatSelected,
            onWifiOnlyChanged = onWifiOnlyChanged
        )

        // Mobile Data & Storage Advisory
        WarningStrip(
            text = "Channel downloads can use significant storage and mobile data. Videos run 1 at a time in the persistent queue and are saved under Shrey/Channels/${preview.title.take(28)}."
        )

        state.actionError?.let { msg ->
            WarningStrip(text = msg)
        }

        if (state.lastQueuedBatchId != null) {
            BatchQueuedBanner(
                text = "Channel batch queued — monitor progress in Downloads",
                onOpenDownloads = onOpenDownloads
            )
        }

        ShreyPrimaryPillButton(
            text = when {
                state.scopePreset == ChannelScopePreset.ALL ->
                    "Review & Download All Discoverable Videos"
                state.usesAutoDiscovery && state.effectiveLimit != null ->
                    "Review & Download Up to ${state.effectiveLimit} Channel Items"
                scopedCount > 0 ->
                    "Review & Download $scopedCount Channel Item${if (scopedCount == 1) "" else "s"}"
                else ->
                    "No Downloadable Items Found"
            },
            leadingIcon = Icons.Outlined.Subscriptions,
            enabled = (scopedCount > 0 || state.usesAutoDiscovery) && !state.isReloadingTabs,
            onClick = onRequestConfirm,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("channel_review_download_button")
        )
    }

    if (state.showConfirmDialog) {
        val storageLabel = if (state.availableStorageBytes > 0L) {
            Formatters.formatBytes(state.availableStorageBytes)
        } else {
            "Unknown"
        }
        AlertDialog(
            onDismissRequest = onDismissConfirm,
            title = { Text("Confirm Channel Bulk Download") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = when {
                            state.scopePreset == ChannelScopePreset.ALL ->
                                "Automatically discover and download all available items from \"${preview.title}\" across the selected tabs?"
                            state.usesAutoDiscovery && state.effectiveLimit != null ->
                                "Automatically discover and download up to ${state.effectiveLimit} items from \"${preview.title}\"?"
                            else ->
                                "Queue $scopedCount item${if (scopedCount == 1) "" else "s"} from \"${preview.title}\"?"
                        }
                    )
                    Text(
                        text = "• Tabs: ${state.selectedTabs.joinToString { it.label }}\n" +
                            "• Discovery: Automatic in chunks of 50 (no repeated taps required; stops when listing ends)\n" +
                            "• Quality: ${state.qualityPreset.label}" +
                            (if (state.qualityPreset.isAudioOnly) " (${state.audioOutputFormat.label})" else "") + "\n" +
                            "• Network: ${if (state.wifiOnly) "Wi-Fi only" else "Wi-Fi or Mobile Data"}\n" +
                            "• Free storage: $storageLabel\n" +
                            "• Concurrency: 1 active download globally (duplicates & inaccessible media auto-skipped)\n" +
                            "• Controls: You can stop discovery and downloads independently in Downloads.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirmStart,
                    modifier = Modifier.testTag("channel_confirm_start_button")
                ) {
                    Text("Start Bulk Download")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismissConfirm,
                    modifier = Modifier.testTag("channel_confirm_cancel_button")
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BulkQualityAndSafeguardsCard(
    qualityPreset: BulkQualityPreset,
    audioOutputFormat: AudioOutputFormat,
    wifiOnly: Boolean,
    availableStorageBytes: Long,
    onQualitySelected: (BulkQualityPreset) -> Unit,
    onAudioFormatSelected: (AudioOutputFormat) -> Unit,
    onWifiOnlyChanged: (Boolean) -> Unit
) {
    val palette = ShreyThemeTokens.palette
    val haptics = rememberShreyHaptics()

    ShreyGlassCard(contentPadding = PaddingValues(16.dp)) {
        Text(
            text = "Quality for Batch",
            style = MaterialTheme.typography.labelMedium,
            color = palette.textSecondary
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BulkQualityPreset.entries.forEach { preset ->
                BulkSelectableChip(
                    label = preset.label,
                    selected = qualityPreset == preset,
                    onClick = {
                        haptics.tap()
                        onQualitySelected(preset)
                    },
                    testTag = "bulk_quality_${preset.name.lowercase()}"
                )
            }
        }

        if (qualityPreset.isAudioOnly) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Audio Format",
                style = MaterialTheme.typography.labelMedium,
                color = palette.textSecondary
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AudioOutputFormat.entries.forEach { fmt ->
                    BulkSelectableChip(
                        label = fmt.label,
                        selected = audioOutputFormat == fmt,
                        onClick = {
                            haptics.tap()
                            onAudioFormatSelected(fmt)
                        },
                        testTag = "bulk_audio_${fmt.name.lowercase()}"
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = palette.divider)
        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Wifi,
                    contentDescription = null,
                    tint = palette.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
                Column {
                    Text(
                        text = "Wi-Fi only for this batch",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textPrimary
                    )
                    Text(
                        text = "Pauses queue if device switches to mobile data",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textTertiary
                    )
                }
            }
            Switch(
                checked = wifiOnly,
                onCheckedChange = onWifiOnlyChanged,
                modifier = Modifier.testTag("bulk_wifi_only_switch")
            )
        }

        if (availableStorageBytes > 0L) {
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Storage,
                    contentDescription = null,
                    tint = palette.textTertiary,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "Available storage: ${Formatters.formatBytes(availableStorageBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary
                )
            }
        }
    }
}

@Composable
private fun PlaylistEntryRow(
    entry: CollectionEntry,
    checked: Boolean,
    onToggle: () -> Unit
) {
    val palette = ShreyThemeTokens.palette
    val enabled = entry.availability.isDownloadable

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (checked && enabled) palette.pillSelectedFill else Color.Transparent
            )
            .border(
                width = 0.75.dp,
                color = if (checked && enabled) palette.accentSolid.copy(alpha = 0.45f) else palette.divider,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(enabled = enabled, role = Role.Checkbox, onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("playlist_entry_${entry.mediaId}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (checked && enabled) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
            contentDescription = if (checked) "Selected" else "Not selected",
            tint = when {
                !enabled -> palette.textTertiary.copy(alpha = 0.45f)
                checked -> palette.accentSolid
                else -> palette.textTertiary
            },
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(width = 58.dp, height = 40.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(palette.backgroundSecondary),
            contentAlignment = Alignment.Center
        ) {
            if (!entry.thumbnailUrl.isNullOrBlank()) {
                AsyncImage(
                    model = entry.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Movie,
                    contentDescription = null,
                    tint = palette.textTertiary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${entry.position}. ${entry.title}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) palette.textPrimary else palette.textTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            val meta = buildList {
                entry.durationSeconds?.let { add(Formatters.formatDuration(it)) }
                if (!enabled) add(entry.availability.label)
                entry.tab?.let { add(it.label) }
            }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (enabled) palette.textSecondary else palette.statusWarning
                )
            }
        }
    }
}

@Composable
private fun BulkTypeBadge(label: String) {
    val palette = ShreyThemeTokens.palette
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(palette.accentSolid.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = palette.accentSolid
        )
    }
}

@Composable
private fun BulkSelectableChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val palette = ShreyThemeTokens.palette
    val bg by animateColorAsState(
        targetValue = if (selected) palette.accentSolid else palette.pillSelectedFill,
        label = "bulk_chip_bg"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) palette.accentOnSolid else palette.textSecondary,
        label = "bulk_chip_text"
    )
    val border = if (selected) Color.Transparent else palette.glassBorderTop

    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = 40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(0.75.dp, border, RoundedCornerShape(12.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .testTag(testTag)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            ),
            color = textColor
        )
    }
}

@Composable
private fun WarningStrip(text: String) {
    val palette = ShreyThemeTokens.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.statusWarning.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = palette.statusWarning,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(16.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary
        )
    }
}

@Composable
private fun BatchQueuedBanner(
    text: String,
    onOpenDownloads: () -> Unit
) {
    val palette = ShreyThemeTokens.palette
    ShreyGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("batch_queued_banner"),
        shape = RoundedCornerShape(20.dp),
        tintOverride = palette.statusSuccess.copy(alpha = 0.12f),
        borderHighlightOverride = palette.statusSuccess.copy(alpha = 0.45f),
        contentPadding = PaddingValues(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = palette.statusSuccess,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.textPrimary
                )
            }
            Spacer(Modifier.width(8.dp))
            ShreyGlassButton(
                text = "Downloads",
                onClick = onOpenDownloads,
                height = 40.dp,
                modifier = Modifier.testTag("view_queued_batch_button")
            )
        }
    }
}
