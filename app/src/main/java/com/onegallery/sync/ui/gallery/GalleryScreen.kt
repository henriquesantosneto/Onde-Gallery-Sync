package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.sync.SyncCoordinator
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    syncCoordinator: SyncCoordinator,
    galleryPreferences: GalleryPreferences,
    onOpenItem: (String) -> Unit,
    onExitAlbumFilter: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val (gridZoom, pinchModifier) = rememberGalleryGridZoom(galleryPreferences.galleryGridZoom) { scale ->
        galleryPreferences.galleryGridZoom = scale
    }
    val mediaCellMin = gridAdaptiveMinSize(baseMinDp = 120f, zoomScale = gridZoom)
    val snackbarHostState = remember { SnackbarHostState() }
    var showSortMenu by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    LaunchedEffect(state.actionMessage) {
        state.actionMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearActionMessage()
        }
    }

    val title = when {
        state.selectionMode -> "${state.selectedPaths.size} selecionado(s)"
        state.albumFilter != null -> "Álbum: ${state.albumFilter}"
        else -> "Galeria espelhada"
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (!state.selectionMode && state.albumFilter != null) {
                        IconButton(
                            onClick = {
                                viewModel.setAlbumFilter(null)
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
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = mediaCellMin),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .then(pinchModifier),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.items, key = { it.canonicalPath }) { item ->
                        val selected = item.canonicalPath in state.selectedPaths
                        MediaThumbnail(
                            item = item,
                            syncCoordinator = syncCoordinator,
                            selectionMode = state.selectionMode,
                            selected = selected,
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
                }
            }
        }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaThumbnail(
    item: GalleryDisplayItem,
    syncCoordinator: SyncCoordinator,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
            )
        }
        CloudBadge(item.cloudState, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
        if (item.kind == MediaKind.VIDEO) {
            Text(
                "Vídeo",
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (!item.isOfflineAvailable && item.cloudState == CloudSyncState.CLOUD_ONLY) {
            Icon(
                Icons.Outlined.CloudOff,
                contentDescription = "Somente nuvem",
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
            )
        }
    }
}

@Composable
private fun CloudBadge(state: CloudSyncState, modifier: Modifier = Modifier) {
    val icon = when (state) {
        CloudSyncState.SYNCED -> Icons.Outlined.CloudDone
        CloudSyncState.CLOUD_ONLY, CloudSyncState.PENDING_DOWNLOAD -> Icons.Outlined.CloudDownload
        CloudSyncState.PENDING_UPLOAD -> Icons.Outlined.Cloud
        else -> Icons.Outlined.Cloud
    }
    Icon(icon, contentDescription = state.name, modifier = modifier)
}
