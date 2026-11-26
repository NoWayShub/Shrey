package com.shrey.downloader.feature.downloads

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.shrey.downloader.core.design.ShreyGlassButton
import com.shrey.downloader.core.design.ShreyGlassCard
import com.shrey.downloader.core.design.ShreyGlassIconButton
import com.shrey.downloader.core.design.ShreyProgressBar
import com.shrey.downloader.core.design.ShreyThemeTokens
import com.shrey.downloader.core.utilities.Formatters
import com.shrey.downloader.domain.model.BatchItem
import com.shrey.downloader.domain.model.BatchItemStatus
import com.shrey.downloader.domain.model.BatchStatus
import com.shrey.downloader.domain.model.BatchWithItems
import com.shrey.downloader.domain.model.DiscoveryStatus
import com.shrey.downloader.domain.model.MediaSourceType
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BatchDownloadCard(
    batchWithItems: BatchWithItems,
    onPauseBatch: () -> Unit,
    onResumeBatch: () -> Unit,
    onStopDiscovery: () -> Unit = {},
    onCancelBatch: () -> Unit,
    onRetryFailed: () -> Unit,
    onRemoveBatch: () -> Unit,
    onCancelItem: (String) -> Unit,
    onRetryItem: (String) -> Unit,
    onOpenItem: (BatchItem) -> Unit,
    onShareItem: (BatchItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = ShreyThemeTokens.palette
    val batch = batchWithItems.batch
    val items = batchWithItems.items
    var expanded by rememberSaveable(batch.batchId) {
        mutableStateOf(batch.status == BatchStatus.ACTIVE)
    }
    var visibleItemsLimit by remember(batch.batchId) { mutableIntStateOf(20) }

    val statusColor = when (batch.status) {
        BatchStatus.COMPLETED -> palette.statusSuccess
        BatchStatus.FAILED, BatchStatus.CANCELLED -> palette.statusError
        BatchStatus.PAUSED, BatchStatus.COMPLETED_WITH_ERRORS -> palette.statusWarning
        BatchStatus.ACTIVE, BatchStatus.DISCOVERING -> palette.accentSolid
    }

    ShreyGlassCard(
        elevated = batch.status == BatchStatus.ACTIVE,
        contentPadding = PaddingValues(15.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("batch_card_${batch.batchId}")
    ) {
        // Header Row: Cover + Metadata + Status
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 72.dp, height = 54.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.backgroundSecondary),
                contentAlignment = Alignment.Center
            ) {
                if (!batch.thumbnailUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = batch.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = if (batch.sourceType == MediaSourceType.CHANNEL) {
                            Icons.Outlined.Subscriptions
                        } else {
                            Icons.AutoMirrored.Outlined.PlaylistPlay
                        },
                        contentDescription = null,
                        tint = palette.textTertiary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (batch.sourceType == MediaSourceType.CHANNEL) "CHANNEL" else "PLAYLIST",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = palette.accentSolid
                    )
                    Text(
                        text = "· ${batch.status.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        modifier = Modifier.testTag("batch_status_${batch.batchId}")
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = batch.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append(batch.qualityPreset.label)
                        if (batch.wifiOnly) append(" · Wi-Fi only")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Separate Discovery Progress Section (for automatic channel discovery batches)
        if (batch.isAutoDiscovery || batch.discoveryStatus != DiscoveryStatus.COMPLETED) {
            Spacer(Modifier.height(12.dp))
            val discoveryColor = when (batch.discoveryStatus) {
                DiscoveryStatus.COMPLETED -> palette.statusSuccess
                DiscoveryStatus.FAILED -> palette.statusError
                DiscoveryStatus.PAUSED, DiscoveryStatus.STOPPED -> palette.statusWarning
                DiscoveryStatus.RUNNING -> palette.accentSolid
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = batch.discoveryProgressLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textPrimary,
                    modifier = Modifier.testTag("batch_discovery_label_${batch.batchId}")
                )
                Text(
                    text = batch.discoveryStatus.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = discoveryColor,
                    modifier = Modifier.testTag("batch_discovery_status_${batch.batchId}")
                )
            }
            Spacer(Modifier.height(4.dp))
            ShreyProgressBar(
                progressFraction = batch.discoveryProgressFraction ?: if (batch.discoveryStatus == DiscoveryStatus.COMPLETED) 1f else 0f,
                indeterminate = batch.discoveryStatus == DiscoveryStatus.RUNNING && batch.discoveryProgressFraction == null
            )
            if (!batch.discoveryError.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = batch.discoveryError,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.statusWarning
                )
            }
        }

        // Separate Download Progress Bar + Byte Counter
        Spacer(Modifier.height(12.dp))
        ShreyProgressBar(
            progressFraction = batch.progressFraction,
            indeterminate = batch.status == BatchStatus.DISCOVERING && batch.selectedCount == 0
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = batch.overallProgressLabel,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textPrimary,
                modifier = Modifier.testTag("batch_progress_label_${batch.batchId}")
            )
            if (batch.totalBytesDownloaded > 0L) {
                Text(
                    text = Formatters.formatBytes(batch.totalBytesDownloaded),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                    modifier = Modifier.testTag("batch_bytes_${batch.batchId}")
                )
            }
        }

        if (!batch.lastError.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = batch.lastError,
                style = MaterialTheme.typography.bodySmall,
                color = if (batch.status == BatchStatus.FAILED) palette.statusError else palette.statusWarning
            )
        }

        // Batch-level Action Row (with independent Discovery vs Download controls)
        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (batch.canStopDiscovery) {
                ShreyGlassButton(
                    text = "Stop Discovery",
                    leadingIcon = Icons.Outlined.Close,
                    contentColor = palette.statusWarning,
                    onClick = onStopDiscovery,
                    height = 40.dp,
                    horizontalPadding = 12.dp,
                    modifier = Modifier.testTag("batch_stop_discovery_${batch.batchId}")
                )
            }
            if (batch.status == BatchStatus.ACTIVE || batch.status == BatchStatus.DISCOVERING) {
                ShreyGlassButton(
                    text = "Pause Queue",
                    leadingIcon = Icons.Outlined.Pause,
                    onClick = onPauseBatch,
                    height = 40.dp,
                    horizontalPadding = 12.dp,
                    modifier = Modifier.testTag("batch_pause_${batch.batchId}")
                )
            }
            if (batch.status == BatchStatus.PAUSED) {
                ShreyGlassButton(
                    text = "Resume Queue",
                    leadingIcon = Icons.Outlined.PlayArrow,
                    onClick = onResumeBatch,
                    height = 40.dp,
                    horizontalPadding = 12.dp,
                    modifier = Modifier.testTag("batch_resume_${batch.batchId}")
                )
            }
            if (batch.failedCount > 0) {
                ShreyGlassButton(
                    text = "Retry Failed (${batch.failedCount})",
                    leadingIcon = Icons.Outlined.Refresh,
                    onClick = onRetryFailed,
                    height = 40.dp,
                    horizontalPadding = 12.dp,
                    modifier = Modifier.testTag("batch_retry_failed_${batch.batchId}")
                )
            }
            if (!batch.status.isTerminal || batch.isDiscoveryActive) {
                ShreyGlassButton(
                    text = "Cancel Batch",
                    leadingIcon = Icons.Outlined.Close,
                    contentColor = palette.statusError,
                    onClick = onCancelBatch,
                    height = 40.dp,
                    horizontalPadding = 12.dp,
                    modifier = Modifier.testTag("batch_cancel_${batch.batchId}")
                )
            } else {
                ShreyGlassButton(
                    text = "Remove",
                    leadingIcon = Icons.Outlined.DeleteOutline,
                    contentColor = palette.textSecondary,
                    onClick = onRemoveBatch,
                    height = 40.dp,
                    horizontalPadding = 12.dp,
                    modifier = Modifier.testTag("batch_remove_${batch.batchId}")
                )
            }
        }

        // Expand/Collapse Toggle for Per-Item Level
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = palette.divider)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 44.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 4.dp, vertical = 6.dp)
                .testTag("batch_expand_toggle_${batch.batchId}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val totalItems = batchWithItems.totalItemCount
            val showingSubset = totalItems > items.size
            Text(
                text = if (expanded) {
                    if (showingSubset) "Hide videos (showing ${items.size} of $totalItems)" else "Hide individual videos ($totalItems)"
                } else {
                    "Show individual videos ($totalItems)"
                },
                style = MaterialTheme.typography.labelMedium,
                color = palette.textSecondary
            )
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "Collapse batch items" else "Expand batch items",
                tint = palette.textTertiary,
                modifier = Modifier.size(18.dp)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items.take(visibleItemsLimit).forEach { item ->
                    BatchItemRow(
                        item = item,
                        onCancel = { onCancelItem(item.itemId) },
                        onRetry = { onRetryItem(item.itemId) },
                        onOpen = { onOpenItem(item) },
                        onShare = { onShareItem(item) }
                    )
                }
                if (items.size > visibleItemsLimit) {
                    ShreyGlassButton(
                        text = "Show more items (${items.size - visibleItemsLimit} remaining)",
                        onClick = { visibleItemsLimit += 25 },
                        height = 40.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun BatchItemRow(
    item: BatchItem,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit
) {
    val palette = ShreyThemeTokens.palette
    val statusColor = when (item.status) {
        BatchItemStatus.COMPLETED -> palette.statusSuccess
        BatchItemStatus.FAILED -> palette.statusError
        BatchItemStatus.ACTIVE -> palette.accentSolid
        BatchItemStatus.SKIPPED, BatchItemStatus.UNAVAILABLE -> palette.statusWarning
        else -> palette.textSecondary
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.backgroundPrimary.copy(alpha = 0.45f))
            .border(0.7.dp, palette.divider, RoundedCornerShape(14.dp))
            .padding(10.dp)
            .testTag("batch_item_${item.itemId}"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${item.position}. ${item.title}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = when {
                        item.status == BatchItemStatus.FAILED && !item.errorMessage.isNullOrBlank() ->
                            "Failed · ${item.errorMessage}"
                        item.status == BatchItemStatus.COMPLETED && item.totalBytes != null ->
                            "Saved · ${Formatters.formatBytes(item.totalBytes)}"
                        else -> item.status.label
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(8.dp))

            when (item.status) {
                BatchItemStatus.COMPLETED -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (!item.destinationUri.isNullOrBlank()) {
                            ShreyGlassIconButton(
                                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                                contentDescription = "Open ${item.title}",
                                onClick = onOpen,
                                size = 40.dp,
                                modifier = Modifier.testTag("batch_item_open_${item.itemId}")
                            )
                            ShreyGlassIconButton(
                                icon = Icons.Outlined.Share,
                                contentDescription = "Share ${item.title}",
                                onClick = onShare,
                                size = 40.dp,
                                modifier = Modifier.testTag("batch_item_share_${item.itemId}")
                            )
                        }
                    }
                }

                BatchItemStatus.ACTIVE, BatchItemStatus.QUEUED -> {
                    ShreyGlassIconButton(
                        icon = Icons.Outlined.Close,
                        contentDescription = "Cancel ${item.title}",
                        contentColor = palette.statusError,
                        onClick = onCancel,
                        size = 40.dp,
                        modifier = Modifier.testTag("batch_item_cancel_${item.itemId}")
                    )
                }

                BatchItemStatus.FAILED, BatchItemStatus.CANCELLED -> {
                    ShreyGlassIconButton(
                        icon = Icons.Outlined.Refresh,
                        contentDescription = "Retry ${item.title}",
                        onClick = onRetry,
                        size = 40.dp,
                        modifier = Modifier.testTag("batch_item_retry_${item.itemId}")
                    )
                }

                else -> Unit
            }
        }

        if (item.status == BatchItemStatus.ACTIVE) {
            ShreyProgressBar(
                progressFraction = (item.progressPercent / 100f).coerceIn(0f, 1f),
                indeterminate = item.progressPercent <= 0f
            )
            val telemetry = buildList {
                add("${item.progressPercent.roundToInt()}%")
                item.speedText?.let { add(it) }
                Formatters.formatEta(item.etaSeconds)?.let { add("ETA $it") }
            }.joinToString(" · ")
            Text(
                text = telemetry,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary
            )
        }
    }
}
