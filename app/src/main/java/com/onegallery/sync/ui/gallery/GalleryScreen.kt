package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.ui.albums.formatMediaCount
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.sync.SyncCoordinator
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    syncCoordinator: SyncCoordinator,
    mediaCacheRepository: MediaCacheRepository,
    galleryPreferences: GalleryPreferences,
    onOpenItem: (String) -> Unit,
    onExitAlbumFilter: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gridPinch = rememberGalleryGridZoom(galleryPreferences.galleryGridZoom) { scale ->
        galleryPreferences.galleryGridZoom = scale
    }
    val mediaCellMin = gridAdaptiveMinSize(baseMinDp = 120f, zoomScale = gridPinch.scale)
    val snackbarHostState = remember { SnackbarHostState() }
    var showSortMenu by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showMediaDetails by remember { mutableStateOf(false) }
    LaunchedEffect(state.actionMessage) {
        state.actionMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearActionMessage()
        }
    }

    val title = when {
        state.selectionMode -> "${state.selectedPaths.size} selecionado(s)"
        state.locationFilter != null -> state.locationFilter ?: "Local"
        state.albumFilter != null -> state.albumFilter ?: "Álbum"
        else -> "Fotos"
    }
    val filteredBrowse = state.locationFilter != null || state.albumFilter != null
    val browseMediaCountLine = if (filteredBrowse && !state.selectionMode) {
        val photos = state.items.count { it.kind == MediaKind.IMAGE }
        val videos = state.items.count { it.kind == MediaKind.VIDEO }
        formatMediaCount(photos, videos)
    } else {
        null
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title)
                        browseMediaCountLine?.let { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (!state.selectionMode && filteredBrowse) {
                        IconButton(
                            onClick = {
                                viewModel.clearBrowseFilters()
                                onExitAlbumFilter()
                            },
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "Voltar aos álbuns",
                            )
                        }
                    }
                },
                actions = {
                    if (state.selectionMode) {
                        if (state.selectedPaths.isNotEmpty()) {
                            IconButton(onClick = { showMediaDetails = true }) {
                                Icon(Icons.Outlined.Info, contentDescription = "Detalhes")
                            }
                        }
                        IconButton(onClick = { showMoveDialog = true }) {
                            Icon(Icons.Outlined.DriveFileMove, contentDescription = "Mover")
                        }
                        IconButton(onClick = { viewModel.releaseLocalSpaceSelected() }) {
                            Icon(Icons.Outlined.CloudOff, contentDescription = "Liberar espaço")
                        }
                        IconButton(onClick = { viewModel.deleteSelected() }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Excluir tudo")
                        }
                        TextButton(onClick = { viewModel.clearSelection() }) {
                            Text("Cancelar")
                        }
                    } else {
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Outlined.Sort, contentDescription = "Ordenar")
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                            ) {
                                GallerySortOrder.entries.forEach { order ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                order.label,
                                                fontWeight = if (order == state.sortOrder) {
                                                    MaterialTheme.typography.titleSmall.fontWeight
                                                } else {
                                                    MaterialTheme.typography.bodyLarge.fontWeight
                                                },
                                            )
                                        },
                                        onClick = {
                                            viewModel.setSortOrder(order)
                                            showSortMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.isLoading && state.items.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
            state.error != null && state.items.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { Text(state.error ?: "Erro") }
            }
            state.items.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { Text("Nenhuma mídia indexada. Conceda permissão e sincronize.") }
            }
            else -> {
                val dateTimeline = isDateTimelineSort(state.sortOrder)
                val timelineGrouping = timelineGroupingForGridScale(gridPinch.scale)
                val gridState = rememberLazyGridState()
                val gridEntries = remember(state.items, state.sortOrder, timelineGrouping) {
                    buildGalleryGridEntries(state.items, state.sortOrder, timelineGrouping)
                }
                val density = LocalDensity.current
                val stickyBarHeightPx = remember(density) {
                    with(density) { GalleryStickyTimelineBarHeight.roundToPx() }
                }
                val gridTopPadding = if (dateTimeline) GalleryStickyTimelineBarHeight else 4.dp
                val timelineSticky = rememberTimelineStickyUiState(
                    gridState = gridState,
                    entries = if (dateTimeline) gridEntries else emptyList(),
                    gridScale = gridPinch.scale,
                    enabled = dateTimeline,
                    stickyBarHeightPx = stickyBarHeightPx,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = mediaCellMin),
                        state = gridState,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(gridPinch.gridModifier),
                        contentPadding = PaddingValues(
                            start = 8.dp,
                            end = 8.dp,
                            top = gridTopPadding,
                            bottom = 4.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(
                            items = gridEntries,
                            key = { it.key },
                            span = { entry ->
                                when (entry) {
                                    is GalleryGridEntry.Header -> GridItemSpan(maxLineSpan)
                                    is GalleryGridEntry.Photo -> GridItemSpan(1)
                                }
                            },
                        ) { entry ->
                            when (entry) {
                                is GalleryGridEntry.Header -> {
                                    TimelineSectionHeader(
                                        title = entry.header.title,
                                        kind = entry.header.kind,
                                        hiddenInStickyZone = entry.header.id in timelineSticky.hiddenHeaderIds,
                                    )
                                }
                                is GalleryGridEntry.Photo -> {
                                    TimelinePhotoCell(
                                        item = entry.item,
                                        state = state,
                                        syncCoordinator = syncCoordinator,
                                        gridPinch = gridPinch,
                                        viewModel = viewModel,
                                        onOpenItem = onOpenItem,
                                    )
                                }
                            }
                        }
                    }
                    if (dateTimeline) {
                        GalleryStickyTimelineBar(
                            title = timelineSticky.label,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                }
            }
        }
    }

    if (showMediaDetails && state.selectedPaths.isNotEmpty()) {
        MediaDetailsBottomSheet(
            canonicalPaths = state.selectedPaths.toList(),
            displayItems = state.items,
            syncCoordinator = syncCoordinator,
            mediaCacheRepository = mediaCacheRepository,
            onDismiss = { showMediaDetails = false },
        )
    }

    if (showMoveDialog) {
        MoveToAlbumDialog(
            userAlbumTitles = state.userAlbums.map { it.title },
            onDismiss = { showMoveDialog = false },
            onMove = { album ->
                showMoveDialog = false
                viewModel.moveSelectedToAlbum(album)
            },
            onCreateAlbum = { name ->
                viewModel.createUserAlbum(name) { title ->
                    showMoveDialog = false
                    viewModel.moveSelectedToAlbum(title)
                }
            },
        )
    }

}

@Composable
private fun TimelinePhotoCell(
    item: GalleryDisplayItem,
    state: GalleryUiState,
    syncCoordinator: SyncCoordinator,
    gridPinch: GalleryGridPinchZoom,
    viewModel: GalleryViewModel,
    onOpenItem: (String) -> Unit,
) {
    val selected = item.canonicalPath in state.selectedPaths
    MediaThumbnail(
        item = item,
        syncCoordinator = syncCoordinator,
        selectionMode = state.selectionMode,
        selected = selected,
        gridZoomScale = gridPinch.scale,
        gesturesEnabled = !gridPinch.blockItemGestures,
        onClick = {
            if (state.selectionMode) {
                viewModel.toggleSelection(item.canonicalPath)
            } else {
                val encoded = URLEncoder.encode(
                    item.canonicalPath,
                    StandardCharsets.UTF_8.name(),
                )
                onOpenItem(encoded)
            }
        },
        onLongClick = {
            viewModel.enterSelectionMode(item.canonicalPath)
        },
    )
}

@Composable
private fun TimelineSectionHeader(
    title: String,
    kind: TimelineHeaderKind,
    hiddenInStickyZone: Boolean = false,
) {
    if (hiddenInStickyZone) {
        Box(Modifier.fillMaxWidth().height(0.dp))
        return
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (kind == TimelineHeaderKind.YEAR) 12.dp else 6.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Text(
            text = title,
            style = when (kind) {
                TimelineHeaderKind.YEAR -> MaterialTheme.typography.headlineSmall
                TimelineHeaderKind.MONTH, TimelineHeaderKind.DAY -> MaterialTheme.typography.titleMedium
            },
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaThumbnail(
    item: GalleryDisplayItem,
    syncCoordinator: SyncCoordinator,
    selectionMode: Boolean,
    selected: Boolean,
    gridZoomScale: Float,
    gesturesEnabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val overlayPad = gridOverlayPadding(gridZoomScale)
    val overlayIconSize = gridOverlayIconSize(gridZoomScale)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .then(
                if (gesturesEnabled) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier
                },
            ),
    ) {
        MediaThumbnailImage(
            item = item,
            syncCoordinator = syncCoordinator,
            contentDescription = item.displayName,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        if (selectionMode) {
            Icon(
                imageVector = if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(overlayPad)
                    .size(overlayIconSize),
            )
        }
        CloudBadge(
            state = item.cloudState,
            iconSize = overlayIconSize,
            modifier = Modifier.align(Alignment.TopEnd).padding(overlayPad),
        )
        if (item.kind == MediaKind.VIDEO) {
            VideoDurationOverlay(
                durationMs = item.videoDurationMs,
                gridZoomScale = gridZoomScale,
                modifier = Modifier.align(Alignment.BottomStart).padding(overlayPad),
            )
        }
        if (!item.isOfflineAvailable && item.cloudState == CloudSyncState.CLOUD_ONLY) {
            Icon(
                Icons.Outlined.CloudOff,
                contentDescription = "Somente nuvem",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(overlayPad)
                    .size(overlayIconSize),
            )
        }
    }
}

@Composable
private fun CloudBadge(
    state: CloudSyncState,
    iconSize: Dp,
    modifier: Modifier = Modifier,
) {
    val icon = when (state) {
        CloudSyncState.SYNCED -> Icons.Outlined.CloudDone
        CloudSyncState.CLOUD_ONLY, CloudSyncState.PENDING_DOWNLOAD -> Icons.Outlined.CloudDownload
        CloudSyncState.PENDING_UPLOAD -> Icons.Outlined.Cloud
        else -> Icons.Outlined.Cloud
    }
    Icon(
        icon,
        contentDescription = state.name,
        modifier = modifier.size(iconSize),
    )
}
