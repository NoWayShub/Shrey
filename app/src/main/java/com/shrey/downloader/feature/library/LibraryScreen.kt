package com.shrey.downloader.feature.library

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.shrey.downloader.core.design.ShreySegmentedControl
import com.shrey.downloader.core.design.ShreyThemeTokens
import com.shrey.downloader.core.design.ShreyTopGradualBlurContainer
import com.shrey.downloader.core.utilities.Formatters
import com.shrey.downloader.domain.model.DownloadItem
import com.shrey.downloader.domain.model.DownloadMode
import com.shrey.downloader.domain.model.LibraryFilter
import com.shrey.downloader.navigation.LocalShreyBottomBarPadding
import com.shrey.downloader.navigation.rememberShreyBottomScrollClearance

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    contentBottomPadding: PaddingValues? = null,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val effectiveBottomPadding = contentBottomPadding ?: LocalShreyBottomBarPadding.current

    LaunchedEffect(Unit) {
        viewModel.refreshAccessibility()
    }

    LibraryScreenContent(
        state = state,
        onFilterSelected = viewModel::onFilterSelected,
        onOpen = { item ->
            val uri = item.destinationUri
            if (uri.isNullOrBlank()) {
                Toast.makeText(context, "File is no longer accessible", Toast.LENGTH_SHORT).show()
            } else {
                runCatching {
                    context.startActivity(viewModel.storagePublisher.buildOpenIntent(uri, item.mimeType))
                }.onFailure {
                    Toast.makeText(context, "No app installed to open this file", Toast.LENGTH_SHORT).show()
                }
            }
        },
        onShare = { item ->
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
        onRemoveRecord = viewModel::removeFromHistory,
        onDeleteFile = viewModel::deleteFileAndRecord,
        contentBottomPadding = effectiveBottomPadding,
        modifier = modifier
    )
}

@Composable
fun LibraryScreenContent(
    state: LibraryUiState,
    onFilterSelected: (LibraryFilter) -> Unit,
    onOpen: (DownloadItem) -> Unit,
    onShare: (DownloadItem) -> Unit,
    onRemoveRecord: (String) -> Unit,
    onDeleteFile: (String) -> Unit,
    contentBottomPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val palette = ShreyThemeTokens.palette
    val density = LocalDensity.current
    val statusBarTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val listState = rememberLazyListState()
    val topBlurProgress by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / 72f).coerceIn(0f, 1f)
        }
    }
    var confirmDelete by remember { mutableStateOf<DownloadItem?>(null) }

    ShreyTopGradualBlurContainer(
        scrollProgress = topBlurProgress,
        scrollOffsetProvider = { listState.firstVisibleItemIndex * 100_000 + listState.firstVisibleItemScrollOffset },
        modifier = modifier
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .testTag("library_list"),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = statusBarTop + 16.dp,
                bottom = rememberShreyBottomScrollClearance(contentBottomPadding)
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column {
                    Text(
                        text = "Library",
                        style = MaterialTheme.typography.headlineMedium,
                        color = palette.textPrimary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Saved local media verified on this device",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary
                    )
                }
            }

            item {
                ShreySegmentedControl(
                    items = LibraryFilter.entries,
                    selectedItem = state.selectedFilter,
                    labelFor = { it.label },
                    onSelect = onFilterSelected,
                    testTagPrefix = "library_filter"
                )
            }

            if (state.entries.isEmpty()) {
                item {
                    ShreyEmptyState(
                        icon = Icons.Outlined.VideoLibrary,
                        title = "Your library is empty",
                        body = "Completed videos, audio tracks, and thumbnails saved on this device will appear here.",
                        modifier = Modifier.testTag("library_empty_state")
                    )
                }
            } else {
                items(state.entries, key = { it.item.id }) { entry ->
                    LibraryItemCard(
                        entry = entry,
                        onOpen = { onOpen(entry.item) },
                        onShare = { onShare(entry.item) },
                        onRemoveRecord = { onRemoveRecord(entry.item.id) },
                        onRequestDelete = { confirmDelete = entry.item }
                    )
                }
            }
        }
    }

    confirmDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete file from device?") },
            text = {
                Text("This permanently deletes \"${target.outputDisplayName ?: target.title}\" from device storage.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteFile(target.id)
                        confirmDelete = null
                    }
                ) {
                    Text("Delete File", color = palette.statusError)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun LibraryItemCard(
    entry: LibraryMediaEntry,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRemoveRecord: () -> Unit,
    onRequestDelete: () -> Unit
) {
    val palette = ShreyThemeTokens.palette
    val item = entry.item
    val fallbackIcon = when (item.mode) {
        DownloadMode.AUDIO_ONLY -> Icons.Outlined.AudioFile
        DownloadMode.THUMBNAIL_ONLY -> Icons.Outlined.Image
        else -> Icons.Outlined.Movie
    }

    ShreyGlassCard(
        contentPadding = PaddingValues(14.dp),
        modifier = Modifier.testTag("library_card_${item.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 76.dp, height = 56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.backgroundSecondary),
                contentAlignment = Alignment.Center
            ) {
                if (!entry.isAccessible) {
                    Icon(
                        imageVector = Icons.Outlined.BrokenImage,
                        contentDescription = null,
                        tint = palette.statusWarning,
                        modifier = Modifier.size(22.dp)
                    )
                } else if (!item.thumbnailUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = item.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = fallbackIcon,
                        contentDescription = null,
                        tint = palette.textSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.outputDisplayName ?: item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                val meta = buildList {
                    add(item.mode.label)
                    add(item.outputContainer.uppercase())
                    item.totalBytes?.let { add(Formatters.formatBytes(it)) }
                }.joinToString(" · ")
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary
                )
                if (!entry.isAccessible) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "File removed outside Shrey",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.statusWarning
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val fontScale = LocalDensity.current.fontScale
            val useStackedActions = maxWidth < 252.dp || fontScale > 1.2f

            if (entry.isAccessible) {
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
                                modifier = Modifier.weight(1f)
                            )
                            ShreyGlassButton(
                                text = "Share",
                                leadingIcon = Icons.Outlined.Share,
                                onClick = onShare,
                                height = 44.dp,
                                horizontalPadding = 12.dp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        ShreyGlassButton(
                            text = "Delete File",
                            leadingIcon = Icons.Outlined.DeleteOutline,
                            contentColor = palette.statusError,
                            onClick = onRequestDelete,
                            height = 42.dp,
                            modifier = Modifier.fillMaxWidth()
                        )
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
                            modifier = Modifier.weight(1f)
                        )
                        ShreyGlassButton(
                            text = "Share",
                            leadingIcon = Icons.Outlined.Share,
                            onClick = onShare,
                            height = 44.dp,
                            horizontalPadding = 10.dp,
                            modifier = Modifier.weight(1f)
                        )
                        ShreyGlassIconButton(
                            icon = Icons.Outlined.DeleteOutline,
                            contentDescription = "Delete file",
                            contentColor = palette.statusError,
                            onClick = onRequestDelete,
                            size = 44.dp
                        )
                    }
                }
            } else {
                ShreyGlassButton(
                    text = "Remove Record",
                    onClick = onRemoveRecord,
                    contentColor = palette.textSecondary,
                    height = 44.dp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
