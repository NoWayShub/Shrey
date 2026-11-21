package com.shrey.downloader.feature.downloads

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.shrey.downloader.core.design.ShreyEmptyState
import com.shrey.downloader.core.design.ShreyGlassButton
import com.shrey.downloader.core.design.ShreyGlassCard
import com.shrey.downloader.core.design.ShreyGlassIconButton
import com.shrey.downloader.core.design.ShreyProgressBar
import com.shrey.downloader.core.design.ShreyThemeTokens
import com.shrey.downloader.core.design.ShreyTopGradualBlur
import com.shrey.downloader.core.utilities.Formatters
import com.shrey.downloader.domain.model.DownloadItem
import com.shrey.downloader.domain.model.DownloadStage
import kotlin.math.roundToInt

@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    contentBottomPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    DownloadsScreenContent(
        state = state,
        onCancel = viewModel::cancelDownload,
        onRetry = viewModel::retryDownload,
        onRemoveHistory = viewModel::removeFromHistory,
        onDeleteFile = viewModel::deleteFileAndHistory,
        onOpenFile = { item ->
            val uri = item.destinationUri
            if (uri.isNullOrBlank()) {
                Toast.makeText(context, "File URI unavailable", Toast.LENGTH_SHORT).show()
            } else {
                runCatching {
                    context.startActivity(viewModel.storagePublisher.buildOpenIntent(uri, item.mimeType))
                }.onFailure {
                    Toast.makeText(context, "No installed app can open this file", Toast.LENGTH_SHORT).show()
                }
            }
        },
        onShareFile = { item ->
            val uri = item.destinationUri
            if (!uri.isNullOrBlank()) {
                runCatching {
                    context.startActivity(
                        viewModel.storagePublisher.buildShareIntent(uri, item.mimeType, item.title)
                    )
                }.onFailure {
                    Toast.makeText(context, "Unable to share this file", Toast.LENGTH_SHORT).show()
                }
            }
        },
        contentBottomPadding = contentBottomPadding,
        modifier = modifier
    )
}

@Composable
fun DownloadsScreenContent(
    state: DownloadsUiState,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onDeleteFile: (String) -> Unit,
    onOpenFile: (DownloadItem) -> Unit,
    onShareFile: (DownloadItem) -> Unit,
    contentBottomPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val palette = ShreyThemeTokens.palette
    val listState = rememberLazyListState()
    val topBlurProgress by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / 96f).coerceIn(0f, 1f)
        }
    }
    var confirmDeleteItem by remember { mutableStateOf<DownloadItem?>(null) }
    var technicalErrorItem by remember { mutableStateOf<DownloadItem?>(null) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .statusBarsPadding()
                .testTag("downloads_list"),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 16.dp,
                bottom = contentBottomPadding.calculateBottomPadding() + 8.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column {
                    Text(
                        text = "Downloads",
                        style = MaterialTheme.typography.headlineMedium,
                        color = palette.textPrimary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (state.inProgressCount > 0) {
                            "${state.inProgressCount} transfer${if (state.inProgressCount == 1) "" else "s"} in progress"
                        } else {
                            "Local queue & transfer history"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary
                    )
                }
            }

            if (state.isEmpty) {
                item {
                    ShreyEmptyState(
                        icon = Icons.Outlined.Download,
                        title = "No downloads yet",
                        body = "Analyze a media link on Home and choose Download to see live progress, speeds, and history here.",
                        modifier = Modifier.testTag("downloads_empty_state")
                    )
                }
            } else {
                if (state.active.isNotEmpty()) {
                    item { SectionLabel("Active") }
                    items(state.active, key = { it.id }) { item ->
                        DownloadJobCard(
                            item = item,
                            onCancel = { onCancel(item.id) },
                            onRetry = { onRetry(item.id) },
                            onOpen = { onOpenFile(item) },
                            onShare = { onShareFile(item) },
                            onRemoveHistory = { onRemoveHistory(item.id) },
                            onRequestDeleteFile = { confirmDeleteItem = item },
                            onViewTechnicalDetails = { technicalErrorItem = item }
                        )
                    }
                }

                if (state.queued.isNotEmpty()) {
                    item { SectionLabel("Queued") }
                    items(state.queued, key = { it.id }) { item ->
                        DownloadJobCard(
                            item = item,
                            onCancel = { onCancel(item.id) },
                            onRetry = { onRetry(item.id) },
                            onOpen = { onOpenFile(item) },
                            onShare = { onShareFile(item) },
                            onRemoveHistory = { onRemoveHistory(item.id) },
                            onRequestDeleteFile = { confirmDeleteItem = item },
                            onViewTechnicalDetails = { technicalErrorItem = item }
                        )
                    }
                }

                if (state.completed.isNotEmpty()) {
                    item { SectionLabel("Completed") }
                    items(state.completed, key = { it.id }) { item ->
                        DownloadJobCard(
                            item = item,
                            onCancel = { onCancel(item.id) },
                            onRetry = { onRetry(item.id) },
                            onOpen = { onOpenFile(item) },
                            onShare = { onShareFile(item) },
                            onRemoveHistory = { onRemoveHistory(item.id) },
                            onRequestDeleteFile = { confirmDeleteItem = item },
                            onViewTechnicalDetails = { technicalErrorItem = item }
                        )
                    }
                }

                if (state.failed.isNotEmpty()) {
                    item { SectionLabel("Failed & Cancelled") }
                    items(state.failed, key = { it.id }) { item ->
                        DownloadJobCard(
                            item = item,
                            onCancel = { onCancel(item.id) },
                            onRetry = { onRetry(item.id) },
                            onOpen = { onOpenFile(item) },
                            onShare = { onShareFile(item) },
                            onRemoveHistory = { onRemoveHistory(item.id) },
                            onRequestDeleteFile = { confirmDeleteItem = item },
                            onViewTechnicalDetails = { technicalErrorItem = item }
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

    confirmDeleteItem?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDeleteItem = null },
            title = { Text("Delete file from device?") },
            text = {
                Text(
                    "This permanently deletes \"${target.outputDisplayName ?: target.title}\" from device storage as well as removing it from Shrey history."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteFile(target.id)
                        confirmDeleteItem = null
                    }
                ) {
                    Text("Delete File", color = palette.statusError)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteItem = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    technicalErrorItem?.let { target ->
        AlertDialog(
            onDismissRequest = { technicalErrorItem = null },
            title = { Text("Technical Details") },
            text = {
                Text(
                    text = target.technicalError ?: target.lastError ?: "No additional diagnostics recorded.",
                    style = MaterialTheme.typography.labelSmall
                )
            },
            confirmButton = {
                TextButton(onClick = { technicalErrorItem = null }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = ShreyThemeTokens.palette.textTertiary,
        modifier = Modifier.padding(top = 6.dp, start = 4.dp)
    )
}

@Composable
private fun DownloadJobCard(
    item: DownloadItem,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRemoveHistory: () -> Unit,
    onRequestDeleteFile: () -> Unit,
    onViewTechnicalDetails: () -> Unit
) {
    val palette = ShreyThemeTokens.palette
    val statusColor = when (item.stage) {
        DownloadStage.COMPLETED -> palette.statusSuccess
        DownloadStage.FAILED, DownloadStage.INTERRUPTED -> palette.statusError
        DownloadStage.MERGING, DownloadStage.CONVERTING -> palette.statusWarning
        else -> palette.textSecondary
    }

    ShreyGlassCard(
        elevated = item.stage.isActive,
        contentPadding = PaddingValues(14.dp),
        modifier = Modifier.testTag("download_card_${item.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 74.dp, height = 54.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.backgroundSecondary),
                contentAlignment = Alignment.Center
            ) {
                if (!item.thumbnailUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = item.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Movie,
                        contentDescription = null,
                        tint = palette.textTertiary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "${item.qualityLabel} · ${item.outputContainer.uppercase()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = buildStatusSummary(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (item.stage.isActive) {
            Spacer(Modifier.height(12.dp))
            ShreyProgressBar(
                progressFraction = item.progressPercent / 100f,
                indeterminate = item.stage == DownloadStage.PREPARING ||
                    item.stage == DownloadStage.EXTRACTING
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${item.progressPercent.roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textPrimary
                )
                val telemetry = buildList {
                    item.speedText?.let { add(it) }
                    Formatters.formatEta(item.etaSeconds)?.let { add("ETA $it") }
                    if (item.totalBytes != null) {
                        add(Formatters.formatBytes(item.totalBytes))
                    }
                }.joinToString(" · ")
                Text(
                    text = telemetry.ifBlank { item.stage.label },
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val fontScale = LocalDensity.current.fontScale
            val useStackedActions = maxWidth < 276.dp || fontScale > 1.15f

            when {
                item.stage.isActive || item.stage == DownloadStage.QUEUED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ShreyGlassButton(
                            text = if (item.stage == DownloadStage.QUEUED) "Remove" else "Cancel",
                            leadingIcon = Icons.Outlined.Close,
                            contentColor = palette.statusError,
                            onClick = onCancel,
                            height = 44.dp,
                            horizontalPadding = 14.dp,
                            modifier = Modifier.testTag("cancel_download_${item.id}")
                        )
                    }
                }

                item.stage == DownloadStage.COMPLETED -> {
                    if (useStackedActions) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ShreyGlassButton(
                                    text = "Open",
                                    leadingIcon = Icons.AutoMirrored.Outlined.OpenInNew,
                                    onClick = onOpen,
                                    height = 44.dp,
                                    horizontalPadding = 12.dp,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("download_open_${item.id}")
                                )
                                ShreyGlassButton(
                                    text = "Share",
                                    leadingIcon = Icons.Outlined.Share,
                                    onClick = onShare,
                                    height = 44.dp,
                                    horizontalPadding = 12.dp,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("download_share_${item.id}")
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ShreyGlassButton(
                                    text = "Hide",
                                    leadingIcon = Icons.Outlined.VisibilityOff,
                                    onClick = onRemoveHistory,
                                    contentColor = palette.textSecondary,
                                    height = 42.dp,
                                    horizontalPadding = 12.dp,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("download_hide_${item.id}")
                                )
                                ShreyGlassButton(
                                    text = "Delete",
                                    leadingIcon = Icons.Outlined.DeleteOutline,
                                    contentColor = palette.statusError,
                                    onClick = onRequestDeleteFile,
                                    height = 42.dp,
                                    horizontalPadding = 12.dp,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("download_delete_${item.id}")
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ShreyGlassButton(
                                text = "Open",
                                leadingIcon = Icons.AutoMirrored.Outlined.OpenInNew,
                                onClick = onOpen,
                                height = 44.dp,
                                horizontalPadding = 10.dp,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("download_open_${item.id}")
                            )
                            ShreyGlassButton(
                                text = "Share",
                                leadingIcon = Icons.Outlined.Share,
                                onClick = onShare,
                                height = 44.dp,
                                horizontalPadding = 10.dp,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("download_share_${item.id}")
                            )
                            ShreyGlassIconButton(
                                icon = Icons.Outlined.VisibilityOff,
                                contentDescription = "Hide from history",
                                onClick = onRemoveHistory,
                                contentColor = palette.textSecondary,
                                size = 44.dp,
                                modifier = Modifier.testTag("download_hide_${item.id}")
                            )
                            ShreyGlassIconButton(
                                icon = Icons.Outlined.DeleteOutline,
                                contentDescription = "Delete file",
                                onClick = onRequestDeleteFile,
                                contentColor = palette.statusError,
                                size = 44.dp,
                                modifier = Modifier.testTag("download_delete_${item.id}")
                            )
                        }
                    }
                }

                else -> {
                    if (useStackedActions) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ShreyGlassButton(
                                text = "Retry",
                                leadingIcon = Icons.Outlined.Refresh,
                                onClick = onRetry,
                                height = 44.dp,
                                horizontalPadding = 12.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("download_retry_${item.id}")
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (!item.technicalError.isNullOrBlank()) {
                                    ShreyGlassButton(
                                        text = "Details",
                                        leadingIcon = Icons.Outlined.Info,
                                        contentColor = palette.textSecondary,
                                        onClick = onViewTechnicalDetails,
                                        height = 42.dp,
                                        horizontalPadding = 12.dp,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("download_details_${item.id}")
                                    )
                                }
                                ShreyGlassButton(
                                    text = "Remove",
                                    leadingIcon = Icons.Outlined.Close,
                                    contentColor = palette.textSecondary,
                                    onClick = onRemoveHistory,
                                    height = 42.dp,
                                    horizontalPadding = 12.dp,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("download_remove_${item.id}")
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ShreyGlassButton(
                                text = "Retry",
                                leadingIcon = Icons.Outlined.Refresh,
                                onClick = onRetry,
                                height = 44.dp,
                                horizontalPadding = 12.dp,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("download_retry_${item.id}")
                            )
                            if (!item.technicalError.isNullOrBlank()) {
                                ShreyGlassIconButton(
                                    icon = Icons.Outlined.Info,
                                    contentDescription = "Technical details",
                                    contentColor = palette.textSecondary,
                                    onClick = onViewTechnicalDetails,
                                    size = 44.dp,
                                    modifier = Modifier.testTag("download_details_${item.id}")
                                )
                            }
                            ShreyGlassIconButton(
                                icon = Icons.Outlined.Close,
                                contentDescription = "Remove from history",
                                contentColor = palette.textSecondary,
                                onClick = onRemoveHistory,
                                size = 44.dp,
                                modifier = Modifier.testTag("download_remove_${item.id}")
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun buildStatusSummary(item: DownloadItem): String {
    return when (item.stage) {
        DownloadStage.COMPLETED -> {
            val size = item.totalBytes?.let { Formatters.formatBytes(it) }
            if (size != null) "Saved · $size" else "Saved to device"
        }
        DownloadStage.FAILED, DownloadStage.INTERRUPTED -> {
            item.lastError ?: item.statusMessage ?: item.stage.label
        }
        else -> item.statusMessage ?: item.stage.label
    }
}
