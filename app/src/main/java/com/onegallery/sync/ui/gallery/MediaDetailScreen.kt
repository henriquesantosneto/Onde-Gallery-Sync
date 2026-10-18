package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.SyncCoordinator
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MediaDetailScreen(
    initialCanonicalPath: String,
    viewModel: GalleryViewModel,
    syncCoordinator: SyncCoordinator,
    mediaCacheRepository: MediaCacheRepository,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val items = state.items
    val snackbarHostState = remember { SnackbarHostState() }
    var showMoveDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.actionMessage) {
        state.actionMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearActionMessage()
        }
    }

    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val initialIndex = items.indexOfFirst { it.canonicalPath == initialCanonicalPath }
        .let { if (it < 0) 0 else it }
    val pagerState = rememberPagerState(initialPage = initialIndex) { items.size }

    LaunchedEffect(initialCanonicalPath, items) {
        val idx = items.indexOfFirst { it.canonicalPath == initialCanonicalPath }
        if (idx >= 0 && idx != pagerState.currentPage) {
            pagerState.scrollToPage(idx)
        }
    }

    val currentItem = items.getOrNull(pagerState.currentPage)
    val currentPath = currentItem?.canonicalPath
    val currentSelected = currentPath != null && currentPath in state.selectedPaths

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when {
                                state.selectionMode -> "${state.selectedPaths.size} selecionado(s)"
                                currentItem?.kind == MediaKind.VIDEO -> currentItem.displayName
                                else -> currentItem?.displayName ?: "Foto"
                            },
                            maxLines = 1,
                        )
                        if (!state.selectionMode) {
                            Text(
                                "${pagerState.currentPage + 1} / ${items.size} · deslize para navegar",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                "Deslize e toque ○ para incluir mais fotos",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Voltar")
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
                    } else if (currentPath != null) {
                        IconButton(
                            onClick = { viewModel.toggleSelection(currentPath) },
                        ) {
                            Icon(
                                if (currentSelected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                contentDescription = "Selecionar",
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            key = { page -> items[page].canonicalPath },
        ) { page ->
            MediaDetailPage(
                canonicalPath = items[page].canonicalPath,
                syncCoordinator = syncCoordinator,
                mediaCacheRepository = mediaCacheRepository,
                viewModel = viewModel,
                onRemoved = onBack,
                modifier = Modifier.fillMaxSize(),
            )
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

@Composable
private fun MediaDetailPage(
    canonicalPath: String,
    syncCoordinator: SyncCoordinator,
    mediaCacheRepository: MediaCacheRepository,
    viewModel: GalleryViewModel,
    onRemoved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var item by remember(canonicalPath) { mutableStateOf<GalleryDisplayItem?>(null) }
    var loading by remember(canonicalPath) { mutableStateOf(true) }
    var error by remember(canonicalPath) { mutableStateOf<String?>(null) }

    LaunchedEffect(canonicalPath) {
        loading = true
        error = null
        var display = syncCoordinator.getMetadata(canonicalPath)?.let {
            GalleryMapper.toDisplayItem(it, mediaCacheRepository)
        }
        if (display != null) {
            syncCoordinator.ensureThumbnailCached(canonicalPath)
            if (display.localUri == null && display.cachedOriginal == null) {
                syncCoordinator.downloadOriginalOnDemand(canonicalPath)
                    .onSuccess { file ->
                        display = display?.copy(cachedOriginal = file)
                        syncCoordinator.ensureThumbnailCached(canonicalPath)
                    }
                    .onFailure { error = it.message }
            }
        }
        item = display
        loading = false
    }

    Box(modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        when {
            loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            item == null -> {
                Text("Item não encontrado.", modifier = Modifier.align(Alignment.Center))
            }
            else -> {
                val display = item!!
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val playback = display.playbackUri()
                    when {
                        display.kind == MediaKind.VIDEO && playback != null -> {
                            VideoPlayerView(
                                uri = playback,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            )
                        }
                        display.kind == MediaKind.VIDEO -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            ) {
                                MediaThumbnailImage(
                                    item = display,
                                    syncCoordinator = syncCoordinator,
                                    contentDescription = display.displayName,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                        }
                        display.detailPreviewModel() != null -> {
                            AsyncImage(
                                model = display.detailPreviewModel(),
                                contentDescription = display.displayName,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentScale = ContentScale.Fit,
                            )
                        }
                        else -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("Original na nuvem — use o botão abaixo para baixar.")
                            }
                        }
                    }
                    Text(
                        display.cloudState.name,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    if (error != null) {
                        Text(error ?: "", color = MaterialTheme.colorScheme.error)
                    }
                    if (display.localUri == null && display.cachedOriginal == null) {
                        Button(
                            onClick = {
                                scope.launch {
                                    syncCoordinator.downloadOriginalOnDemand(canonicalPath)
                                        .onSuccess { file ->
                                            item = display.copy(cachedOriginal = file)
                                            syncCoordinator.ensureThumbnailCached(canonicalPath)
                                        }
                                        .onFailure { err -> error = err.message }
                                }
                            },
                        ) { Text("Baixar original") }
                    }
                    RowWithActions(
                        onDeleteAll = { viewModel.deleteSingle(canonicalPath, onRemoved) },
                        onFreeSpace = { viewModel.releaseSingle(canonicalPath) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowWithActions(onDeleteAll: () -> Unit, onFreeSpace: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onFreeSpace) {
            Icon(Icons.Outlined.CloudOff, contentDescription = null)
            Text(" Liberar espaço")
        }
        TextButton(onClick = onDeleteAll) {
            Icon(Icons.Outlined.Delete, contentDescription = null)
            Text(" Excluir tudo")
        }
    }
}
