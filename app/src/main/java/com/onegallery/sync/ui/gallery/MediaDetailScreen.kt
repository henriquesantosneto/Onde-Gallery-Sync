package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DriveFileMove
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import kotlin.math.max
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.SyncCoordinator
import com.onegallery.sync.ui.theme.ZeroScaffoldContentInsets
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    fun leaveDetail() {
        viewModel.notifyLocalDisplayChanged()
        onBack()
    }
    val liveItems = state.items
    val snackbarHostState = remember { SnackbarHostState() }
    var showMoveDialog by remember { mutableStateOf(false) }
    var detailsSettleFraction by remember { mutableFloatStateOf(0f) }
    var detailsDragFraction by remember { mutableFloatStateOf(0f) }
    var detailsDragging by remember { mutableStateOf(false) }
    /** true = oculta barras e carrossel (toque na foto alterna). */
    var immersive by remember { mutableStateOf(false) }
    var pagerScrollEnabled by remember { mutableStateOf(true) }
    var multiTouchOnPhoto by remember { mutableStateOf(false) }
    var photoZoomActive by remember { mutableStateOf(false) }
    var activePageZoomTransform by remember { mutableStateOf<ZoomTransformHolder?>(null) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var activePageError by remember { mutableStateOf<String?>(null) }

    val pagerScope = rememberCoroutineScope()

    ImmersiveSystemBarsEffect(
        immersive = immersive,
        edgeToEdgeWithVisibleBars = !immersive,
    )

    LaunchedEffect(state.actionMessage) {
        state.actionMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearActionMessage()
        }
    }

    val itemsByPath = remember(liveItems) {
        liveItems.associateBy { it.canonicalPath }
    }

    if (liveItems.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = Color.White)
        }
        LaunchedEffect(initialCanonicalPath) {
            delay(600)
            if (viewModel.state.value.items.isEmpty()) {
                leaveDetail()
            }
        }
        return
    }

    val contextItems = remember(
        liveItems,
        state.albumFilter,
        state.locationFilter,
        state.geoItems,
        initialCanonicalPath,
    ) {
        detailContextItems(
            allItems = liveItems,
            albumFilter = state.albumFilter,
            locationFilter = state.locationFilter,
            anchorPath = initialCanonicalPath,
            geoItems = state.geoItems,
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
    val currentItem = currentPath?.let { path -> itemsByPath[path] }
    val currentSelected = currentPath != null && currentPath in state.selectedPaths
    val detailPaths = when {
        state.selectionMode && state.selectedPaths.isNotEmpty() -> state.selectedPaths.toList()
        currentPath != null -> listOf(currentPath)
        else -> emptyList()
    }

    val multiSelection = state.selectionMode && state.selectedPaths.size > 1
    val swipeUpForDetails = !immersive && !multiSelection && currentPath != null

    LaunchedEffect(pagerState.currentPage) {
        detailsSettleFraction = 0f
        detailsDragFraction = 0f
        detailsDragging = false
        activePageError = null
    }

    fun openDetailsToSimple() {
        if (detailPaths.isNotEmpty()) {
            detailsSettleFraction = MediaDetailSheetAnchors.SIMPLE
            detailsDragFraction = MediaDetailSheetAnchors.SIMPLE
        }
    }

    fun snapDetailsPanel(velocityYPxPerSec: Float = 0f) {
        val snapped = MediaDetailSheetAnchors.snap(detailsDragFraction, velocityYPxPerSec)
        detailsSettleFraction = snapped
        detailsDragFraction = snapped
    }

    val detailsDisplayFraction = rememberMediaDetailSheetFraction(
        settleTarget = detailsSettleFraction,
        dragFraction = detailsDragFraction,
        dragging = detailsDragging,
    )
    var detailsContentLevel by remember {
        mutableStateOf(MediaDetailContentLevel.Simple)
    }
    LaunchedEffect(detailsDragging, detailsSettleFraction) {
        if (!detailsDragging) {
            detailsContentLevel = contentLevelForPanelFraction(detailsSettleFraction)
        }
    }

    val chromeVisible = !immersive

    val filmstripItems = remember(pagerPaths, itemsByPath, contextItems) {
        val pool = if (liveItems.isNotEmpty()) itemsByPath else contextItems.associateBy { it.canonicalPath }
        pagerPaths.mapNotNull { path -> pool[path] }
    }
    val detailsPanelOpen = detailsDisplayFraction > 0.05f && detailPaths.isNotEmpty()
    val showFilmstrip = chromeVisible &&
        !state.selectionMode &&
        !detailsPanelOpen &&
        filmstripItems.size > 1

    val barTitle = when {
        state.selectionMode -> "${state.selectedPaths.size} selecionado(s)"
        else -> currentItem?.displayName ?: "Mídia"
    }
    val barSubtitle = if (!state.selectionMode && pagerPaths.size > 1) {
        "${pagerState.currentPage + 1} / ${pagerPaths.size}"
    } else {
        null
    }

    Scaffold(
        contentWindowInsets = ZeroScaffoldContentInsets,
        containerColor = Color.Black,
        snackbarHost = {
            if (chromeVisible) {
                SnackbarHost(snackbarHostState)
            }
        },
    ) { _ ->
        val configuration = LocalConfiguration.current
        val screenHeightPx = with(LocalDensity.current) {
            configuration.screenHeightDp.dp.toPx()
        }
        val detailsVisible = detailsDisplayFraction > 0.02f
        val photoHeightFraction = (1f - detailsDisplayFraction).coerceIn(0.12f, 1f)
        val gestureBaseFraction = remember { mutableFloatStateOf(0f) }
        gestureBaseFraction.floatValue = max(
            detailsSettleFraction,
            max(detailsDisplayFraction, if (detailsDragging) detailsDragFraction else 0f),
        )
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(if (detailsVisible) photoHeightFraction else 1f)
                    .align(Alignment.TopCenter)
                    .observeMultiTouchPinch { active ->
                        multiTouchOnPhoto = active
                        if (active) pagerScrollEnabled = false
                    }
                    .then(
                        activePageZoomTransform?.let { zoomTransform ->
                            Modifier.detailPhotoPinchZoom(
                                transform = zoomTransform,
                                onPinching = { pinching ->
                                    if (pinching) {
                                        multiTouchOnPhoto = true
                                        pagerScrollEnabled = false
                                    }
                                },
                                onTransformChanged = {
                                    val zoomed = zoomTransform.scale > 1.02f
                                    photoZoomActive = zoomed
                                    pagerScrollEnabled = !zoomed
                                },
                            )
                        } ?: Modifier,
                    )
                    .draggableDetailPanelHeight(
                        enabled = swipeUpForDetails &&
                            !photoZoomActive &&
                            !multiTouchOnPhoto,
                        screenHeightPx = screenHeightPx,
                        fractionBaseState = gestureBaseFraction,
                        onDragStart = {
                            detailsDragging = true
                            detailsDragFraction = gestureBaseFraction.floatValue
                        },
                        onDragFraction = { fraction ->
                            detailsDragging = true
                            detailsDragFraction = fraction
                        },
                        onDragEnd = { velocityY ->
                            snapDetailsPanel(velocityY)
                            detailsDragging = false
                        },
                    ),
            ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = pagerScrollEnabled && !multiTouchOnPhoto,
                key = { page -> pagerPaths[page] },
            ) { page ->
                val path = pagerPaths[page]
                val live = itemsByPath[path]
                MediaDetailPage(
                    canonicalPath = path,
                    liveItem = live,
                    isActivePage = page == pagerState.currentPage,
                    syncCoordinator = syncCoordinator,
                    mediaCacheRepository = mediaCacheRepository,
                    onToggleImmersive = { immersive = !immersive },
                    onZoomActiveChanged = { active ->
                        photoZoomActive = active
                        pagerScrollEnabled = !active
                    },
                    onActivePageError = { err ->
                        if (page == pagerState.currentPage) {
                            activePageError = err
                        }
                    },
                    onLocalOriginalCached = { viewModel.notifyLocalDisplayChanged() },
                    onActiveZoomTransform = { activePageZoomTransform = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (showFilmstrip) {
                MediaDetailFilmstrip(
                    items = filmstripItems,
                    currentCanonicalPath = currentPath,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
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

            if (chromeVisible) {
                MediaDetailTransparentTopBar(
                    modifier = Modifier.align(Alignment.TopCenter),
                    title = barTitle,
                    subtitle = barSubtitle,
                    navigationIcon = {
                        IconButton(onClick = { leaveDetail() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Voltar")
                        }
                    },
                    actions = {
                        if (state.selectionMode) {
                            IconButton(onClick = { showMoveDialog = true }) {
                                Icon(Icons.Outlined.DriveFileMove, contentDescription = "Mover")
                            }
                            if (state.selectedPaths.isNotEmpty()) {
                                MediaGalleryOverflowMenuButton(
                                    expanded = showOverflowMenu,
                                    onExpandedChange = { showOverflowMenu = it },
                                    showDetails = detailPaths.isNotEmpty(),
                                    onDetails = { openDetailsToSimple() },
                                    onFreeSpace = { viewModel.releaseLocalSpaceSelected() },
                                    onDelete = { showDeleteConfirm = true },
                                    deleteLabel = "Excluir tudo",
                                )
                            }
                            TextButton(onClick = { viewModel.clearSelection() }) {
                                Text("Cancelar", color = Color.White)
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
                                onDetails = { openDetailsToSimple() },
                                onFreeSpace = { viewModel.releaseSingle(currentPath) },
                                onDelete = { showDeleteConfirm = true },
                            )
                        }
                    },
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
                        contentDescription = "Mostrar barras",
                        tint = Color.White,
                    )
                }
            }
            }

            if (detailsVisible && detailPaths.isNotEmpty()) {
                MediaDetailPushDetailsSheet(
                    canonicalPaths = detailPaths,
                    displayItems = liveItems,
                    syncCoordinator = syncCoordinator,
                    mediaCacheRepository = mediaCacheRepository,
                    panelFraction = detailsDisplayFraction,
                    contentLevel = detailsContentLevel,
                    onPanelFractionChange = { fraction ->
                        detailsDragging = true
                        detailsDragFraction = fraction
                    },
                    onPanelDragEnd = { velocityY ->
                        snapDetailsPanel(velocityY)
                        detailsDragging = false
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(
                            detailsDisplayFraction.coerceIn(0.08f, MediaDetailSheetAnchors.FULL),
                        ),
                )
            }
        }
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
    onToggleImmersive: () -> Unit,
    onZoomActiveChanged: (Boolean) -> Unit,
    onActivePageError: (String?) -> Unit,
    onLocalOriginalCached: () -> Unit = {},
    onActiveZoomTransform: (ZoomTransformHolder?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val zoomTransform = remember(canonicalPath) { ZoomTransformHolder() }
    DisposableEffect(isActivePage, canonicalPath) {
        if (isActivePage) {
            onActiveZoomTransform(zoomTransform)
        }
        onDispose {
            if (isActivePage) {
                onActiveZoomTransform(null)
            }
        }
    }
    var item by remember(canonicalPath, liveItem?.canonicalPath) { mutableStateOf(liveItem) }
    var gridNotifiedForCache by remember(canonicalPath) { mutableStateOf(false) }
    var loading by remember(canonicalPath, liveItem?.canonicalPath) { mutableStateOf(liveItem == null) }
    var error by remember(canonicalPath) { mutableStateOf<String?>(null) }
    var loadingFull by remember(canonicalPath) { mutableStateOf(false) }
    var contentAspectRatio by remember(canonicalPath) { mutableStateOf<Float?>(null) }
    val viewContext = LocalContext.current

    suspend fun loadDisplayItem(includeOriginalFile: File? = null): GalleryDisplayItem? =
        withContext(Dispatchers.Default) {
            syncCoordinator.getMetadata(canonicalPath)?.let { entity ->
                val mapped = GalleryMapper.toDisplayItem(entity, mediaCacheRepository)
                if (includeOriginalFile != null) {
                    mapped.copy(cachedOriginal = includeOriginalFile)
                } else {
                    mapped
                }
            }
        }

    fun notifyGridIfNewlyCached(display: GalleryDisplayItem?) {
        if (gridNotifiedForCache || display == null) return
        if (!display.hasViewableLocalOriginal(viewContext)) return
        val liveHadCache = liveItem?.hasViewableLocalOriginal(viewContext) == true
        if (liveHadCache) return
        gridNotifiedForCache = true
        onLocalOriginalCached()
    }

    suspend fun loadFullOriginalForViewing() {
        val preview = item ?: liveItem
        if (preview?.hasViewableLocalOriginal(viewContext) == true) return
        if (!syncCoordinator.needsOriginalRecall(canonicalPath)) return
        loadingFull = true
        error = null
        try {
            val result = syncCoordinator.recallOriginalToDevice(canonicalPath)
            if (result.isSuccess) {
                val file = result.getOrNull()
                item = loadDisplayItem(includeOriginalFile = file)
                notifyGridIfNewlyCached(item)
            } else {
                error = result.exceptionOrNull()?.message
            }
        } finally {
            loadingFull = false
        }
    }

    LaunchedEffect(canonicalPath, isActivePage, liveItem?.cachedPlaceholderThumb) {
        if (!isActivePage) {
            if (liveItem != null && item == null) {
                item = liveItem
                loading = false
            }
            return@LaunchedEffect
        }
        try {
            error = null
            if (liveItem != null) {
                item = liveItem
                loading = false
            }
            item = loadDisplayItem() ?: liveItem ?: item
            if (item != null) loading = false
            withContext(Dispatchers.IO) {
                syncCoordinator.ensureThumbnailCached(canonicalPath)
            }
            item = loadDisplayItem() ?: item
            notifyGridIfNewlyCached(item)
            loading = false
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            error = failure.message ?: failure.javaClass.simpleName
            loading = false
            loadingFull = false
        }
    }

    LaunchedEffect(canonicalPath, isActivePage, item, liveItem) {
        if (!isActivePage) return@LaunchedEffect
        try {
            val previewItem = item ?: liveItem
            if (previewItem?.hasViewableLocalOriginal(viewContext) == true) {
                return@LaunchedEffect
            }
            val needsRecall = withContext(Dispatchers.Default) {
                syncCoordinator.needsOriginalRecall(canonicalPath)
            }
            if (needsRecall) {
                loadFullOriginalForViewing()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            error = failure.message ?: failure.javaClass.simpleName
            loadingFull = false
        }
    }

    LaunchedEffect(error, isActivePage) {
        if (isActivePage) {
            onActivePageError(error)
        }
    }

    val display = item
    val statusItem = liveItem ?: display

    Box(modifier = modifier) {
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
                                contentAspectRatio = contentAspectRatio,
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
                                contentAspectRatio = contentAspectRatio,
                                modifier = mediaModifier,
                            ) {
                                ZoomableMediaContainer(
                                    resetKey = canonicalPath,
                                    transform = zoomTransform,
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
                                        onContentAspectRatio = { contentAspectRatio = it },
                                    )
                                }
                            }
                        }
                        display.detailPreviewModel() != null -> {
                            MediaDetailMediaShell(
                                item = overlayItem,
                                recalling = loadingFull,
                                contentAspectRatio = contentAspectRatio,
                                modifier = mediaModifier,
                            ) {
                                ZoomableImageContent(
                                    model = display.detailPreviewModel(),
                                    contentDescription = display.displayName,
                                    transform = zoomTransform,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit,
                                    onZoomActiveChanged = onZoomActiveChanged,
                                    onSingleTap = onToggleImmersive,
                                    onContentAspectRatio = { contentAspectRatio = it },
                                )
                            }
                        }
                        else -> {
                            MediaDetailMediaShell(
                                item = overlayItem,
                                recalling = loadingFull,
                                contentAspectRatio = contentAspectRatio,
                                modifier = mediaModifier,
                            ) {
                                ZoomableMediaContainer(
                                    resetKey = canonicalPath,
                                    transform = zoomTransform,
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
                                        onContentAspectRatio = { contentAspectRatio = it },
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
    contentAspectRatio: Float?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier) {
        content()
        MediaDetailCloudOverlay(
            item = item,
            recalling = recalling,
            contentAspectRatio = contentAspectRatio,
        )
    }
}
