package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.SyncCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val liveItems = state.items
    val snackbarHostState = remember { SnackbarHostState() }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showMediaDetails by remember { mutableStateOf(false) }
    var immersive by remember { mutableStateOf(false) }
    var pagerScrollEnabled by remember { mutableStateOf(true) }

    ImmersiveSystemBarsEffect(immersive)

    LaunchedEffect(state.actionMessage) {
        state.actionMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearActionMessage()
        }
    }

    if (liveItems.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val frozenPaths = remember { mutableStateListOf<String>() }
    LaunchedEffect(liveItems) {
        val paths = liveItems.map { it.canonicalPath }
        if (frozenPaths.isEmpty()) {
            frozenPaths.addAll(paths)
            return@LaunchedEffect
        }
        val known = frozenPaths.toSet()
        val liveSet = paths.toSet()
        frozenPaths.removeAll { it !in liveSet }
        paths.forEach { path ->
            if (path !in known) {
                frozenPaths.add(path)
            }
        }
    }

    val pagerPaths = if (frozenPaths.isNotEmpty()) frozenPaths.toList() else liveItems.map { it.canonicalPath }
    val initialPage = pagerPaths.indexOf(initialCanonicalPath).coerceAtLeast(0)
    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, (pagerPaths.size - 1).coerceAtLeast(0)),
    ) { pagerPaths.size.coerceAtLeast(1) }

    val currentPath = pagerPaths.getOrNull(pagerState.currentPage)
    val currentItem = currentPath?.let { path -> liveItems.find { it.canonicalPath == path } }
    val currentSelected = currentPath != null && currentPath in state.selectedPaths
    val detailPaths = when {
        state.selectionMode && state.selectedPaths.isNotEmpty() -> state.selectedPaths.toList()
        currentPath != null -> listOf(currentPath)
        else -> emptyList()
    }

    val chromeVisible = !immersive

    Scaffold(
        snackbarHost = { if (chromeVisible) SnackbarHost(snackbarHostState) },
        topBar = {
            if (chromeVisible) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                when {
                                    state.selectionMode -> "${state.selectedPaths.size} selecionado(s)"
                                    else -> currentItem?.displayName ?: "Mídia"
                                },
                                maxLines = 1,
                            )
                            if (!state.selectionMode) {
                                val dateLine = currentItem?.sortTimestamp?.let(::formatMediaCaptureDate).orEmpty()
                                val locationLine = currentItem?.locationLabel
                                val subtitle = listOfNotNull(locationLine, dateLine.takeIf { it.isNotBlank() })
                                    .joinToString(" · ")
                                    .ifBlank {
                                        "${pagerState.currentPage + 1} / ${pagerPaths.size} · deslize para navegar"
                                    }
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
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
                        if (detailPaths.isNotEmpty()) {
                            IconButton(onClick = { showMediaDetails = true }) {
                                Icon(Icons.Outlined.Info, contentDescription = "Detalhes")
                            }
                        }
                        if (!state.selectionMode && currentItem != null) {
                            IconButton(onClick = { immersive = true }) {
                                Icon(Icons.Outlined.Fullscreen, contentDescription = "Tela cheia")
                            }
                        }
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
                            IconButton(onClick = { viewModel.toggleSelection(currentPath) }) {
                                Icon(
                                    if (currentSelected) Icons.Outlined.CheckCircle
                                    else Icons.Outlined.RadioButtonUnchecked,
                                    contentDescription = "Selecionar",
                                )
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (chromeVisible) padding else PaddingValues()),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = pagerScrollEnabled,
                key = { page -> pagerPaths[page] },
            ) { page ->
                val path = pagerPaths[page]
                val live = liveItems.find { it.canonicalPath == path }
                MediaDetailPage(
                    canonicalPath = path,
                    liveItem = live,
                    syncCoordinator = syncCoordinator,
                    mediaCacheRepository = mediaCacheRepository,
                    viewModel = viewModel,
                    onRemoved = onBack,
                    immersive = immersive,
                    showChrome = chromeVisible,
                    onToggleImmersive = { immersive = !immersive },
                    onZoomActiveChanged = { active -> pagerScrollEnabled = !active },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (immersive) {
                IconButton(
                    onClick = { immersive = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.45f)),
                ) {
                    Icon(
                        Icons.Outlined.FullscreenExit,
                        contentDescription = "Sair da tela cheia",
                        tint = Color.White,
                    )
                }
                Text(
                    "Deslize para a esquerda ou direita · pinça com dois dedos para zoom",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                        .background(Color.Black.copy(alpha = 0.35f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }

    if (showMediaDetails && detailPaths.isNotEmpty()) {
        MediaDetailsBottomSheet(
            canonicalPaths = detailPaths,
            displayItems = liveItems,
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
private fun MediaDetailPage(
    canonicalPath: String,
    liveItem: GalleryDisplayItem?,
    syncCoordinator: SyncCoordinator,
    mediaCacheRepository: MediaCacheRepository,
    viewModel: GalleryViewModel,
    onRemoved: () -> Unit,
    immersive: Boolean,
    showChrome: Boolean,
    onToggleImmersive: () -> Unit,
    onZoomActiveChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var item by remember(canonicalPath) { mutableStateOf<GalleryDisplayItem?>(null) }
    var loading by remember(canonicalPath) { mutableStateOf(true) }
    var error by remember(canonicalPath) { mutableStateOf<String?>(null) }
    var loadingFull by remember(canonicalPath) { mutableStateOf(false) }

    suspend fun refreshDisplayItem() {
        item = syncCoordinator.getMetadata(canonicalPath)?.let {
            GalleryMapper.toDisplayItem(it, mediaCacheRepository)
        }
    }

    suspend fun loadFullOriginalForViewing() {
        if (!syncCoordinator.needsOriginalRecall(canonicalPath)) return
        withContext(Dispatchers.Main.immediate) {
            loadingFull = true
            error = null
        }
        val result = syncCoordinator.recallOriginalToDevice(canonicalPath)
        result.onSuccess {
            syncCoordinator.ensureThumbnailCached(canonicalPath)
            refreshDisplayItem()
        }
        withContext(Dispatchers.Main.immediate) {
            result.onFailure { err -> error = err.message }
            loadingFull = false
        }
    }

    LaunchedEffect(canonicalPath) {
        if (item == null) loading = true
        error = null
        refreshDisplayItem()
        syncCoordinator.ensureThumbnailCached(canonicalPath)
        refreshDisplayItem()
        loading = false
        if (syncCoordinator.needsOriginalRecall(canonicalPath)) {
            loadFullOriginalForViewing()
        } else {
            refreshDisplayItem()
        }
    }

    val display = item
    val cloudLabel = liveItem?.cloudState?.name ?: display?.cloudState?.name

    Box(
        modifier = modifier
            .padding(if (immersive) PaddingValues() else PaddingValues(horizontal = 8.dp, vertical = 4.dp)),
    ) {
        when {
            loading && display == null -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            display == null -> {
                Text("Item não encontrado.", modifier = Modifier.align(Alignment.Center))
            }
            else -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val mediaModifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                    val playback = display.playbackUri()
                    when {
                        display.kind == MediaKind.VIDEO && playback != null -> {
                            VideoPlayerView(
                                uri = playback,
                                modifier = mediaModifier,
                                playWhenReady = true,
                                onZoomActiveChanged = onZoomActiveChanged,
                            )
                        }
                        display.kind == MediaKind.VIDEO -> {
                            Box(modifier = mediaModifier, contentAlignment = Alignment.Center) {
                                ZoomableMediaContainer(
                                    resetKey = canonicalPath,
                                    modifier = Modifier.fillMaxSize(),
                                    onZoomActiveChanged = onZoomActiveChanged,
                                    onSingleTap = onToggleImmersive,
                                ) {
                                    MediaThumbnailImage(
                                        item = display,
                                        syncCoordinator = syncCoordinator,
                                        contentDescription = display.displayName,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit,
                                    )
                                }
                                if (loadingFull) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                        display.detailPreviewModel() != null -> {
                            ZoomableImageContent(
                                model = display.detailPreviewModel(),
                                contentDescription = display.displayName,
                                modifier = mediaModifier,
                                contentScale = ContentScale.Fit,
                                onZoomActiveChanged = onZoomActiveChanged,
                                onSingleTap = onToggleImmersive,
                            )
                        }
                        else -> {
                            Box(
                                modifier = mediaModifier,
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("Recuperando da nuvem…")
                            }
                        }
                    }

                    if (showChrome) {
                        Text(
                            cloudLabel ?: "",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                        if (loadingFull) {
                            Row(
                                modifier = Modifier.padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(strokeWidth = 2.dp)
                                Text("Recuperando da nuvem…")
                            }
                        }
                        if (error != null) {
                            Text(
                                error ?: "",
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
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
}

@Composable
private fun RowWithActions(onDeleteAll: () -> Unit, onFreeSpace: () -> Unit) {
    Row(
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
