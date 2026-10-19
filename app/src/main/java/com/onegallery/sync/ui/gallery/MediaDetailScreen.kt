package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
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
    var showDetailsSheet by remember { mutableStateOf(false) }
    var immersive by remember { mutableStateOf(false) }
    var pagerScrollEnabled by remember { mutableStateOf(true) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var activePageError by remember { mutableStateOf<String?>(null) }

    val pagerScope = rememberCoroutineScope()

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

    val contextItems = remember(
        liveItems,
        state.albumFilter,
        state.locationFilter,
        initialCanonicalPath,
    ) {
        detailContextItems(
            allItems = liveItems,
            albumFilter = state.albumFilter,
            locationFilter = state.locationFilter,
            anchorPath = initialCanonicalPath,
        )
    }

    val frozenPaths = remember { mutableStateListOf<String>() }
    LaunchedEffect(contextItems, state.selectionMode) {
        if (state.selectionMode) return@LaunchedEffect
        val paths = contextItems.map { it.canonicalPath }
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

    val pagerPaths = when {
        state.selectionMode && state.selectedPaths.isNotEmpty() ->
            state.selectedPaths.toList()
        frozenPaths.isNotEmpty() -> frozenPaths.toList()
        else -> contextItems.map { it.canonicalPath }
    }
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

    val multiSelection = state.selectionMode && state.selectedPaths.size > 1
    val swipeUpForDetails = !immersive && !multiSelection && currentPath != null && pagerScrollEnabled

    LaunchedEffect(pagerState.currentPage) {
        showDetailsSheet = false
        activePageError = null
    }

    fun openDetailsSheet() {
        if (detailPaths.isNotEmpty()) {
            showDetailsSheet = true
        }
    }

    val chromeVisible = !immersive

    val filmstripItems = remember(pagerPaths, liveItems, contextItems) {
        val pool = liveItems.ifEmpty { contextItems }
        pagerPaths.mapNotNull { path -> pool.find { it.canonicalPath == path } }
    }
    val showFilmstrip = chromeVisible && !state.selectionMode && filmstripItems.size > 1

    val topBar: @Composable () -> Unit = {
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
                            Text(
                                "${pagerState.currentPage + 1} / ${pagerPaths.size}",
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
                    if (!state.selectionMode && currentItem != null) {
                        IconButton(onClick = { immersive = true }) {
                            Icon(Icons.Outlined.Fullscreen, contentDescription = "Tela cheia")
                        }
                    }
                    if (state.selectionMode) {
                        IconButton(onClick = { showMoveDialog = true }) {
                            Icon(Icons.Outlined.DriveFileMove, contentDescription = "Mover")
                        }
                        if (state.selectedPaths.isNotEmpty()) {
                            MediaGalleryOverflowMenuButton(
                                expanded = showOverflowMenu,
                                onExpandedChange = { showOverflowMenu = it },
                                showDetails = detailPaths.isNotEmpty(),
                                onDetails = { openDetailsSheet() },
                                onFreeSpace = { viewModel.releaseLocalSpaceSelected() },
                                onDelete = { showDeleteConfirm = true },
                                deleteLabel = "Excluir tudo",
                            )
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
                        MediaGalleryOverflowMenuButton(
                            expanded = showOverflowMenu,
                            onExpandedChange = { showOverflowMenu = it },
                            showDetails = detailPaths.isNotEmpty(),
                            onDetails = { openDetailsSheet() },
                            onFreeSpace = { viewModel.releaseSingle(currentPath) },
                            onDelete = { showDeleteConfirm = true },
                        )
                    }
                },
            )
        }
    }

    val pagerContent: @Composable (PaddingValues) -> Unit = { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (chromeVisible) padding else PaddingValues()),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .openDetailsOnSwipeUp(
                        enabled = swipeUpForDetails,
                        onSwipeUp = { openDetailsSheet() },
                    ),
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
                        isActivePage = page == pagerState.currentPage,
                        syncCoordinator = syncCoordinator,
                        mediaCacheRepository = mediaCacheRepository,
                        immersive = immersive,
                        onToggleImmersive = { immersive = !immersive },
                        onZoomActiveChanged = { active -> pagerScrollEnabled = !active },
                        onActivePageError = { err ->
                            if (page == pagerState.currentPage) {
                                activePageError = err
                            }
                        },
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
            if (showFilmstrip) {
                MediaDetailFilmstrip(
                    items = filmstripItems,
                    currentCanonicalPath = currentPath,
                    modifier = Modifier.navigationBarsPadding(),
                    onSelectPath = { path ->
                        val index = pagerPaths.indexOf(path)
                        if (index >= 0) {
                            pagerScope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        }
                    },
                    syncCoordinator = syncCoordinator,
                )
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { if (chromeVisible) SnackbarHost(snackbarHostState) },
        topBar = topBar,
    ) { padding ->
        pagerContent(padding)
    }

    if (showDetailsSheet && detailPaths.isNotEmpty()) {
        MediaDetailsBottomSheet(
            canonicalPaths = detailPaths,
            displayItems = liveItems,
            syncCoordinator = syncCoordinator,
            mediaCacheRepository = mediaCacheRepository,
            onDismiss = { showDetailsSheet = false },
        )
    }

    if (showDeleteConfirm) {
        val deleteCount = if (state.selectionMode && state.selectedPaths.isNotEmpty()) {
            state.selectedPaths.size
        } else if (currentPath != null) {
            1
        } else {
            0
        }
        if (deleteCount > 0) {
            DeleteSelectionDialog(
                count = deleteCount,
                onDismiss = { showDeleteConfirm = false },
                onConfirm = {
                    showDeleteConfirm = false
                    if (state.selectionMode && state.selectedPaths.isNotEmpty()) {
                        viewModel.deleteSelected()
                    } else if (currentPath != null) {
                        viewModel.deleteSingle(currentPath, onBack)
                    }
                },
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
    liveItem: GalleryDisplayItem?,
    isActivePage: Boolean,
    syncCoordinator: SyncCoordinator,
    mediaCacheRepository: MediaCacheRepository,
    immersive: Boolean,
    onToggleImmersive: () -> Unit,
    onZoomActiveChanged: (Boolean) -> Unit,
    onActivePageError: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var item by remember(canonicalPath) { mutableStateOf<GalleryDisplayItem?>(null) }
    var loading by remember(canonicalPath) { mutableStateOf(true) }
    var error by remember(canonicalPath) { mutableStateOf<String?>(null) }
    var loadingFull by remember(canonicalPath) { mutableStateOf(false) }

    suspend fun refreshDisplayItem() {
        item = withContext(Dispatchers.Default) {
            syncCoordinator.getMetadata(canonicalPath)?.let {
                GalleryMapper.toDisplayItem(it, mediaCacheRepository)
            }
        }
    }

    suspend fun loadFullOriginalForViewing() {
        if (!syncCoordinator.needsOriginalRecall(canonicalPath)) return
        withContext(Dispatchers.Main.immediate) {
            loadingFull = true
            error = null
        }
        val result = syncCoordinator.recallOriginalToDevice(canonicalPath)
        if (result.isSuccess) {
            val file = result.getOrNull()
            withContext(Dispatchers.Main.immediate) {
                refreshDisplayItem()
                if (file != null) {
                    item = item?.copy(cachedOriginal = file)
                        ?: syncCoordinator.getMetadata(canonicalPath)?.let {
                            GalleryMapper.toDisplayItem(it, mediaCacheRepository)
                                .copy(cachedOriginal = file)
                        }
                }
            }
        }
        withContext(Dispatchers.Main.immediate) {
            result.onFailure { err -> error = err.message }
            loadingFull = false
        }
    }

    LaunchedEffect(canonicalPath) {
        try {
            if (item == null) loading = true
            error = null
            refreshDisplayItem()
            syncCoordinator.ensureThumbnailCached(canonicalPath)
            refreshDisplayItem()
            loading = false
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            error = failure.message ?: failure.javaClass.simpleName
            loading = false
            loadingFull = false
        }
    }

    LaunchedEffect(canonicalPath, isActivePage) {
        if (!isActivePage) return@LaunchedEffect
        try {
            if (syncCoordinator.needsOriginalRecall(canonicalPath)) {
                loadFullOriginalForViewing()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            withContext(Dispatchers.Main.immediate) {
                error = failure.message ?: failure.javaClass.simpleName
                loadingFull = false
            }
        }
    }

    LaunchedEffect(error, isActivePage) {
        if (isActivePage) {
            onActivePageError(error)
        }
    }

    val display = item
    val statusItem = liveItem ?: display

    Box(
        modifier = modifier
            .padding(if (immersive) PaddingValues() else PaddingValues(horizontal = 4.dp)),
    ) {
        when {
            loading && display == null -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            display == null -> {
                Text("Item não encontrado.", modifier = Modifier.align(Alignment.Center))
            }
            else -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    val mediaModifier = Modifier.fillMaxSize()
                    val playback = display.playbackUri()
                    val overlayItem = statusItem ?: display
                    when {
                        display.kind == MediaKind.VIDEO && playback != null -> {
                            MediaDetailMediaShell(
                                item = overlayItem,
                                recalling = loadingFull,
                                modifier = mediaModifier,
                            ) {
                                VideoPlayerView(
                                    uri = playback,
                                    modifier = Modifier.fillMaxSize(),
                                    playWhenReady = true,
                                    onZoomActiveChanged = onZoomActiveChanged,
                                )
                            }
                        }
                        display.kind == MediaKind.VIDEO -> {
                            MediaDetailMediaShell(
                                item = overlayItem,
                                recalling = loadingFull,
                                modifier = mediaModifier,
                            ) {
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
                            }
                        }
                        display.detailPreviewModel() != null -> {
                            MediaDetailMediaShell(
                                item = overlayItem,
                                recalling = loadingFull,
                                modifier = mediaModifier,
                            ) {
                                ZoomableImageContent(
                                    model = display.detailPreviewModel(),
                                    contentDescription = display.displayName,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit,
                                    onZoomActiveChanged = onZoomActiveChanged,
                                    onSingleTap = onToggleImmersive,
                                )
                            }
                        }
                        else -> {
                            MediaDetailMediaShell(
                                item = overlayItem,
                                recalling = loadingFull,
                                modifier = mediaModifier,
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
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
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaDetailMediaShell(
    item: GalleryDisplayItem,
    recalling: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier) {
        content()
        MediaDetailCloudOverlay(item = item, recalling = recalling)
    }
}
