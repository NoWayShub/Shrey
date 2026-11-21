package com.shrey.downloader.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.shrey.downloader.core.design.ShreyGlassButton
import com.shrey.downloader.core.design.ShreyGlassCard
import com.shrey.downloader.core.design.ShreyGlassSurface
import com.shrey.downloader.core.design.ShreyPrimaryPillButton
import com.shrey.downloader.core.design.ShreySegmentedControl
import com.shrey.downloader.core.design.ShreyThemeTokens
import com.shrey.downloader.core.design.rememberShreyHaptics
import com.shrey.downloader.core.utilities.Formatters
import com.shrey.downloader.domain.model.AudioOutputFormat
import com.shrey.downloader.domain.model.DownloadMode
import com.shrey.downloader.domain.model.FormatOption
import com.shrey.downloader.domain.model.ThumbnailOutputFormat

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaPreviewCard(
    state: HomeUiState.Ready,
    onModeSelected: (DownloadMode) -> Unit,
    onOptionSelected: (FormatOption) -> Unit,
    onAudioFormatSelected: (AudioOutputFormat) -> Unit,
    onThumbnailFormatSelected: (ThumbnailOutputFormat) -> Unit,
    onToggleTechnicalDetails: () -> Unit,
    onStartDownload: () -> Unit,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = ShreyThemeTokens.palette
    val haptics = rememberShreyHaptics()
    val media = state.media

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("media_preview_section"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Floating Hero Glass Preview Card
        ShreyGlassCard(
            elevated = true,
            contentPadding = PaddingValues(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.backgroundSecondary)
            ) {
                if (!media.thumbnailUrl.isNullOrBlank()) {
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(media.thumbnailUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Thumbnail for ${media.title}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        loading = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(palette.backgroundSecondary)
                            )
                        },
                        error = {
                            ThumbnailFallback()
                        }
                    )
                } else {
                    ThumbnailFallback()
                }

                // Bottom scrim for duration & platform pills
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color(0xBB08090B))
                            )
                        )
                )

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassMetaBadge(text = media.platform)
                    media.durationSeconds?.let { seconds ->
                        GlassMetaBadge(text = Formatters.formatDuration(seconds))
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = media.title,
                style = MaterialTheme.typography.titleMedium,
                color = palette.textPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("preview_media_title")
            )

            if (!media.creator.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Person,
                        contentDescription = null,
                        tint = palette.textTertiary,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = media.creator,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (media.warnings.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                media.warnings.forEach { warning ->
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
                            text = warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary
                        )
                    }
                }
            }
        }

        // Mode Segmented Control
        ShreySegmentedControl(
            items = DownloadMode.entries,
            selectedItem = state.selectedMode,
            labelFor = { it.label },
            onSelect = onModeSelected,
            testTagPrefix = "mode"
        )

        // Format & Quality Configuration Card
        ShreyGlassCard(
            contentPadding = PaddingValues(18.dp)
        ) {
            // Audio output format selector
            if (state.selectedMode == DownloadMode.AUDIO_ONLY) {
                Text(
                    text = "Output Format",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.textSecondary
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AudioOutputFormat.entries.forEach { fmt ->
                        SelectableGlassChip(
                            label = fmt.label,
                            selected = state.selectedAudioFormat == fmt,
                            onClick = {
                                haptics.tap()
                                onAudioFormatSelected(fmt)
                            },
                            testTag = "audio_format_${fmt.name.lowercase()}"
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = state.selectedAudioFormat.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textTertiary
                )
                Spacer(Modifier.height(16.dp))
            }

            // Thumbnail format selector
            if (state.selectedMode == DownloadMode.THUMBNAIL_ONLY) {
                Text(
                    text = "Image Format",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.textSecondary
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThumbnailOutputFormat.entries.forEach { fmt ->
                        SelectableGlassChip(
                            label = fmt.label,
                            selected = state.selectedThumbnailFormat == fmt,
                            onClick = {
                                haptics.tap()
                                onThumbnailFormatSelected(fmt)
                            },
                            testTag = "thumb_format_${fmt.name.lowercase()}"
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // Available qualities / streams
            Text(
                text = when (state.selectedMode) {
                    DownloadMode.VIDEO_AUDIO -> "Available Video Qualities"
                    DownloadMode.AUDIO_ONLY -> "Source Audio Stream"
                    DownloadMode.VIDEO_ONLY -> "Video-Only Streams"
                    DownloadMode.THUMBNAIL_ONLY -> "Extracted Thumbnail"
                },
                style = MaterialTheme.typography.labelMedium,
                color = palette.textSecondary
            )

            Spacer(Modifier.height(10.dp))

            if (state.options.isEmpty()) {
                Text(
                    text = when (state.selectedMode) {
                        DownloadMode.VIDEO_ONLY -> "This source does not expose separate video-only tracks."
                        DownloadMode.AUDIO_ONLY -> "No audio streams were exposed by this source."
                        DownloadMode.THUMBNAIL_ONLY -> "No thumbnail URL was returned by the extractor."
                        DownloadMode.VIDEO_AUDIO -> "No combined or mergeable video streams are available for this link."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textTertiary
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.options.forEach { option ->
                        FormatOptionRow(
                            option = option,
                            selected = state.selectedOption?.id == option.id,
                            onClick = {
                                haptics.tap()
                                onOptionSelected(option)
                            }
                        )
                    }
                }
            }

            // Expandable Technical Details
            if (state.selectedOption?.primaryFormat != null) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = palette.divider)
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            haptics.tap()
                            onToggleTechnicalDetails()
                        }
                        .testTag("technical_details_toggle")
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            tint = palette.textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Technical Details",
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.textSecondary
                        )
                    }
                    Icon(
                        imageVector = if (state.showTechnicalDetails) {
                            Icons.Outlined.ExpandLess
                        } else {
                            Icons.Outlined.ExpandMore
                        },
                        contentDescription = if (state.showTechnicalDetails) "Collapse details" else "Expand details",
                        tint = palette.textTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                AnimatedVisibility(
                    visible = state.showTechnicalDetails,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    TechnicalDetailsGrid(option = state.selectedOption)
                }
            }
        }

        // Download Primary Action + Confirmation Banner
        if (state.lastQueuedDownloadId != null) {
            ShreyGlassSurface(
                modifier = Modifier.fillMaxWidth(),
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
                            text = "Added to Downloads queue",
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.textPrimary
                        )
                    }
                    ShreyGlassButton(
                        text = "View",
                        onClick = onOpenDownloads,
                        height = 40.dp,
                        modifier = Modifier.testTag("view_queued_download_button")
                    )
                }
            }
        }

        ShreyPrimaryPillButton(
            text = if (state.selectedOption != null) {
                "Download · ${state.selectedOption.label}"
            } else {
                "Select a Format"
            },
            onClick = onStartDownload,
            enabled = state.selectedOption != null,
            leadingIcon = Icons.Outlined.Download,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("start_download_button")
        )
    }
}

@Composable
private fun ThumbnailFallback() {
    val palette = ShreyThemeTokens.palette
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.backgroundSecondary),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Movie,
            contentDescription = null,
            tint = palette.textTertiary,
            modifier = Modifier.size(36.dp)
        )
    }
}

@Composable
private fun GlassMetaBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xAA0A0C10))
            .border(0.7.dp, Color(0x33FFFFFF), RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFF7F8FA),
            maxLines = 1
        )
    }
}

@Composable
private fun SelectableGlassChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val palette = ShreyThemeTokens.palette
    val bg by animateColorAsState(
        targetValue = if (selected) palette.accentSolid else palette.pillSelectedFill,
        label = "chip_bg"
    )
    val content by animateColorAsState(
        targetValue = if (selected) palette.accentOnSolid else palette.textSecondary,
        label = "chip_text"
    )
    val borderColor = if (selected) Color.Transparent else palette.glassBorderTop

    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = 40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(0.75.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .testTag(testTag)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            ),
            color = content
        )
    }
}

@Composable
private fun FormatOptionRow(
    option: FormatOption,
    selected: Boolean,
    onClick: () -> Unit
) {
    val palette = ShreyThemeTokens.palette
    val bg by animateColorAsState(
        targetValue = if (selected) palette.pillSelectedFill else Color.Transparent,
        label = "opt_bg"
    )
    val border by animateColorAsState(
        targetValue = if (selected) palette.accentSolid.copy(alpha = 0.55f) else palette.divider,
        label = "opt_border"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(0.85.dp, border, RoundedCornerShape(16.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .testTag("format_option_${option.id}")
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = option.label,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                ),
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = option.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(
                    width = if (selected) 5.dp else 1.5.dp,
                    color = if (selected) palette.accentSolid else palette.textTertiary,
                    shape = RoundedCornerShape(10.dp)
                )
        )
    }
}

@Composable
private fun TechnicalDetailsGrid(option: FormatOption) {
    val palette = ShreyThemeTokens.palette
    val fmt = option.primaryFormat ?: return
    val companion = option.companionAudioFormat

    val rows = buildList {
        add("Format ID" to option.formatSelector)
        if (fmt.width != null && fmt.height != null) {
            add("Resolution" to "${fmt.width} × ${fmt.height}")
        }
        Formatters.formatFps(fmt.fps)?.let { add("Frame Rate" to it) }
        add("Container" to option.outputExtension.uppercase())
        fmt.videoCodec?.let { add("Video Codec" to it) }
        (fmt.audioCodec ?: companion?.audioCodec)?.let { add("Audio Codec" to it) }
        Formatters.formatBitrate(fmt.totalBitrateKbps ?: fmt.audioBitrateKbps)?.let {
            add("Stream Bitrate" to it)
        }
        if (fmt.isHdr) {
            add("Dynamic Range" to (fmt.dynamicRange ?: "HDR"))
        }
        Formatters.formatSizeWithEstimate(option.estimatedSizeBytes, option.isSizeEstimated)?.let {
            add("File Size" to it)
        }
        add(
            "Pipeline" to if (option.requiresMerge) {
                "Separate streams merged via FFmpeg"
            } else {
                "Single stream"
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(palette.backgroundPrimary.copy(alpha = 0.55f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        rows.forEach { (label, value) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textTertiary
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
