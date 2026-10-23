package com.onegallery.sync.ui.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onegallery.sync.OneGalleryApplication
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.data.local.MediaStorageAccess
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
    onOpenPlacesMap: (latitude: Double, longitude: Double) -> Unit = { _, _ -> },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    fun leaveDetail() {
        viewModel.notifyLocalDisplayChanged()
        onBack()
    }
    val liveItems = state.libraryItems
    var showMoveDialog by remember { mutableStateOf(false) }
    var detailsSettleFraction by remember { mutableFloatStateOf(0f) }
    var detailsDragFraction by remember { mutableFloatStateOf(0f) }
    var detailsDragging by remember { mutableStateOf(false) }
    /** true = oculta barras e carrossel (toque na foto alterna). */
    var immersive by remember { mutableStateOf(false) }
    var pagerScrollEnabled by remember { mutableStateOf(true) }
    var zoomActive by remember { mutableStateOf(false) }
    var activeZoomTransform by remember { mutableStateOf<ZoomTransformHolder?>(null) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showAllFilesForDelete by remember { mutableStateOf(false) }
    var showAllFilesForRelease by remember { mutableStateOf(false) }
    var pendingDeletePaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingDeleteOnRemoved by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingReleasePaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingReleaseOnDone by remember { mutableStateOf<(() -> Unit)?>(null) }
    val context = LocalContext.current
    var activePageError by remember { mutableStateOf<String?>(null) }
    var activePageRecalling by remember { mutableStateOf(false) }
    /** Evita recall automático na mesma sessão após “Liberar espaço” (reabrir a foto para baixar). */
    val suppressAutoRecallPaths = remember { mutableSetOf<String>() }
    var suppressAutoRecallRevision by remember { mutableIntStateOf(0) }
    fun markSuppressAutoRecall(paths: Collection<String>) {
        if (paths.isEmpty()) return
        suppressAutoRecallPaths.addAll(paths)
        suppressAutoRecallRevision++
    }

    val pagerScope = rememberCoroutineScope()

    ImmersiveSystemBarsEffect(
        immersive = immersive,
        edgeToEdgeWithVisibleBars = !immersive,
    )

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
            if (viewModel.state.value.libraryItems.isEmpty()) {
                leaveDetail()
            }
        }
        return
    }

    val galleryPreferences = (LocalContext.current.applicationContext as OneGalleryApplication)
        .galleryPreferences
    val timelineGrouping = timelineGroupingForGridScale(galleryPreferences.galleryGridZoom)

    val contextItems = remember(
        liveItems,
        state.albumFilter,
        state.locationFilter,
        state.placeBrowseMatchKey,
        state.geoItems,
        initialCanonicalPath,
        state.sortOrder,
        timelineGrouping,
    ) {
        detailContextItems(
            allItems = liveItems,
            albumFilter = state.albumFilter,
            locationFilter = state.locationFilter,
            placeMatchKey = state.placeBrowseMatchKey,
            anchorPath = initialCanonicalPath,
            geoItems = state.geoItems,
            sortOrder = state.sortOrder,
            timelineGrouping = timelineGrouping,
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

    // Mantém a fila completa durante seleção — evita crash/trava ao cancelar (pager encolhia).
    val pagerPaths = when {
        frozenPaths.isNotEmpty() -> frozenPaths.toList()
        else -> contextItems.map { it.canonicalPath }
    }
    val initialPage = pagerPaths.indexOf(initialCanonicalPath).coerceAtLeast(0)
    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, (pagerPaths.size - 1).coerceAtLeast(0)),
    ) { pagerPaths.size.coerceAtLeast(1) }

    LaunchedEffect(pagerPaths.size) {
        val lastIndex = (pagerPaths.size - 1).coerceAtLeast(0)
        if (pagerState.currentPage > lastIndex) {
            pagerState.scrollToPage(lastIndex)
        }
    }

    val currentPath = pagerPaths.getOrNull(pagerState.currentPage)
    var detailItemOverrides by remember { mutableStateOf(mapOf<String, GalleryDisplayItem>()) }
    val currentItem = currentPath?.let { path ->
        preferDisplayItemForCloudBadge(
            detailItemOverrides[path],
            itemsByPath[path],
            context,
        )
    }
    val currentSelected = currentPath != null && currentPath in state.selectedPaths
    val detailPaths = when {
        state.selectionMode && state.selectedPaths.isNotEmpty() -> state.selectedPaths.toList()
        currentPath != null -> listOf(currentPath)
        else -> emptyList()
    }

    val multiSelection = state.selectionMode && state.selectedPaths.size > 1
    val swipeUpForDetails = !multiSelection && currentPath != null

    LaunchedEffect(pagerState.currentPage) {
        detailsSettleFraction = 0f
        detailsDragFraction = 0f
        detailsDragging = false
        activePageError = null
        pagerScrollEnabled = true
        zoomActive = false
        activeZoomTransform = null
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
        resetKey = pagerState.currentPage,
    )
    var detailsContentLevel by remember {
        mutableStateOf(MediaDetailContentLevel.Simple)
    }
    LaunchedEffect(detailsDragging, detailsDragFraction, detailsSettleFraction) {
        val fraction = if (detailsDragging) detailsDragFraction else detailsSettleFraction
        detailsContentLevel = contentLevelForPanelFraction(
            fraction,
            detailsContentLevel,
        )
    }
    LaunchedEffect(pagerState.currentPage) {
        detailsContentLevel = MediaDetailContentLevel.Simple
        activePageRecalling = false
    }

    val chromeVisible = !immersive

    val filmstripItems = remember(pagerPaths, itemsByPath, contextItems) {
        val pool = if (liveItems.isNotEmpty()) itemsByPath else contextItems.associateBy { it.canonicalPath }
        pagerPaths.mapNotNull { path -> pool[path] }
    }
    val detailsPanelOpen = when {
        detailsDragging -> detailsDragFraction > 0.14f
        else -> detailsSettleFraction > 0.05f
    } && detailPaths.isNotEmpty()

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
    ) { _ ->
        val configuration = LocalConfiguration.current
        val screenHeightPx = with(LocalDensity.current) {
            configuration.screenHeightDp.dp.toPx()
        }
        val showDetailsSheet = detailPaths.isNotEmpty() &&
            (detailsDragging || detailsSettleFraction > 0.02f)
        val useSplitPhotoLayout = showDetailsSheet ||
            detailsDisplayFraction > 0.03f
        val photoHeightFraction = (1f - detailsDisplayFraction).coerceIn(0.12f, 1f)
        val gestureBaseFraction = remember { mutableFloatStateOf(0f) }
        gestureBaseFraction.floatValue = if (detailsDragging) {
            detailsDragFraction
        } else {
            detailsSettleFraction
        }
        val panelDragBaseFraction = gestureBaseFraction.floatValue
        val panelPullEnabled = swipeUpForDetails && !zoomActive && detailPaths.isNotEmpty()
        val onPanelDragStart = {
            detailsDragging = true
            detailsDragFraction = gestureBaseFraction.floatValue
        }
        val onPanelDragFraction: (Float) -> Unit = { fraction ->
            detailsDragging = true
            detailsDragFraction = fraction
        }
        val onPanelDragEnd: (Float) -> Unit = { velocityY ->
            snapDetailsPanel(velocityY)
            detailsDragging = false
        }
        fun panelDragModifier() = Modifier.draggableDetailPanelHeight(
            enabled = true,
            screenHeightPx = screenHeightPx,
            fractionBaseState = gestureBaseFraction,
            onDragStart = onPanelDragStart,
            onDragFraction = onPanelDragFraction,
            onDragEnd = onPanelDragEnd,
        )
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(if (useSplitPhotoLayout) photoHeightFraction else 1f)
                    .align(Alignment.TopCenter)
                    .then(
                        activeZoomTransform?.let { transform ->
                            Modifier.detailPhotoPinchZoom(
                                transform = transform,
                                onPinching = { pinching ->
                                    val zoomed = pinching || transform.scale > 1.02f
                                    zoomActive = zoomed
                                    pagerScrollEnabled = !zoomed
                                },
                                onTransformChanged = {
                                    val zoomed = transform.scale > 1.02f
                                    zoomActive = zoomed
                                    pagerScrollEnabled = !zoomed
                                },
                            )
                        } ?: Modifier,
                    )
                    .then(
                        if (panelPullEnabled) panelDragModifier() else Modifier,
                    ),
            ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = pagerScrollEnabled && !detailsDragging,
                key = { page -> pagerPaths[page] },
            ) { page ->
                val path = pagerPaths[page]
                val live = itemsByPath[path]
                MediaDetailPage(
                    canonicalPath = path,
                    liveItem = live,
                    isActivePage = page == pagerState.currentPage,
                    suppressAutoRecall = path in suppressAutoRecallPaths,
                    suppressAutoRecallRevision = suppressAutoRecallRevision,
                    syncCoordinator = syncCoordinator,
                    mediaCacheRepository = mediaCacheRepository,
                    onToggleImmersive = { immersive = !immersive },
                    onZoomActiveChanged = { active ->
                        zoomActive = active
                        pagerScrollEnabled = !active
                    },
                    onActivePageError = { err ->
                        if (page == pagerState.currentPage) {
                            activePageError = err
                        }
                    },
                    onLocalOriginalCached = { viewModel.notifyLocalDisplayChanged() },
                    onDisplayItemChanged = { updated ->
                        if (detailItemOverrides[path] != updated) {
                            detailItemOverrides = detailItemOverrides + (path to updated)
                        }
                    },
                    onRegisterZoomTransform = { activeZoomTransform = it },
                    onRecallingChanged = { recalling ->
                        if (page == pagerState.currentPage) {
                            activePageRecalling = recalling
                        }
                    },
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
                    belowActionsEnd = currentItem?.let { statusItem ->
                        {
                            MediaDetailTopBarCloudStatus(
                                item = statusItem,
                                recalling = activePageRecalling,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                when {
                                    detailsPanelOpen -> {
                                        detailsSettleFraction = 0f
                                        detailsDragFraction = 0f
                                        detailsDragging = false
                                    }
                                    state.selectionMode -> viewModel.clearSelection()
                                    else -> leaveDetail()
                                }
                            },
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Voltar")
                        }
                    },
                    actions = {
                        if (state.selectionMode) {
                            IconButton(onClick = { showMoveDialog = true }) {
                                Icon(Icons.Outlined.DriveFileMove, contentDescription = "Mover")
                            }
                            if (state.selectedPaths.isNotEmpty()) {
                                IconButton(onClick = { showDeleteConfirm = true }) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = "Excluir",
                                        tint = Color.White,
                                    )
                                }
                                MediaGalleryOverflowMenuButton(
                                    expanded = showOverflowMenu,
                                    onExpandedChange = { showOverflowMenu = it },
                                    showDetails = detailPaths.isNotEmpty(),
                                    onDetails = { openDetailsToSimple() },
                                    onFreeSpace = {
                                        val paths = state.selectedPaths.toList()
                                        markSuppressAutoRecall(paths)
                                        if (viewModel.canDeleteLocallyWithoutSystemPrompt()) {
                                            viewModel.releaseLocalSpaceSelected()
                                        } else {
                                            pendingReleasePaths = paths
                                            pendingReleaseOnDone = null
                                            showAllFilesForRelease = true
                                        }
                                    },
                                )
                            }
                            TextButton(onClick = { viewModel.clearSelection() }) {
                                Text("Cancelar", color = Color.White)
                            }
                        } else if (currentPath != null) {
                            IconButton(
                                onClick = {
                                    if (state.selectionMode) {
                                        if (currentSelected && state.selectedPaths.size == 1) {
                                            viewModel.clearSelection()
                                        } else {
                                            viewModel.toggleSelection(currentPath)
                                        }
                                    } else {
                                        viewModel.enterSelectionMode(currentPath)
                                    }
                                },
                            ) {
                                Icon(
                                    if (currentSelected) Icons.Outlined.CheckCircle
                                    else Icons.Outlined.RadioButtonUnchecked,
                                    contentDescription = "Selecionar foto",
                                )
                            }
                            IconButton(onClick = { showDeleteConfirm = true }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = "Excluir",
                                    tint = Color.White,
                                )
                            }
                            MediaGalleryOverflowMenuButton(
                                expanded = showOverflowMenu,
                                onExpandedChange = { showOverflowMenu = it },
                                showDetails = detailPaths.isNotEmpty(),
                                onDetails = { openDetailsToSimple() },
                                onFreeSpace = {
                                    markSuppressAutoRecall(listOf(currentPath))
                                    if (viewModel.canDeleteLocallyWithoutSystemPrompt()) {
                                        viewModel.releaseSingle(currentPath)
                                    } else {
                                        pendingReleasePaths = listOf(currentPath)
                                        pendingReleaseOnDone = null
                                        showAllFilesForRelease = true
                                    }
                                },
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

            if (showDetailsSheet) {
                MediaDetailPushDetailsSheet(
                    canonicalPaths = detailPaths,
                    displayItems = liveItems,
                    syncCoordinator = syncCoordinator,
                    mediaCacheRepository = mediaCacheRepository,
                    panelFraction = detailsDisplayFraction,
                    panelDragBaseFraction = panelDragBaseFraction,
                    contentLevel = detailsContentLevel,
                    onPanelDragStart = {
                        detailsDragging = true
                        detailsDragFraction = gestureBaseFraction.floatValue
                    },
                    onPanelFractionChange = { fraction ->
                        detailsDragging = true
                        detailsDragFraction = fraction
                    },
                    onPanelDragEnd = { velocityY ->
                        snapDetailsPanel(velocityY)
                        detailsDragging = false
                    },
                    deferBackToSelectionHandler = state.selectionMode,
                    onOpenPlacesMap = onOpenPlacesMap,
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
                    val paths = when {
                        state.selectionMode && state.selectedPaths.isNotEmpty() ->
                            state.selectedPaths.toList()
                        currentPath != null -> listOf(currentPath)
                        else -> emptyList()
                    }
                    if (paths.isEmpty()) return@DeleteSelectionDialog
                    val onRemoved = if (paths.size == 1 && currentPath != null) {
                        { leaveDetail() }
                    } else {
                        null
                    }
                    if (viewModel.canDeleteLocallyWithoutSystemPrompt()) {
                        if (onRemoved != null) {
                            viewModel.deleteSingle(paths.single(), onRemoved)
                        } else {
                            viewModel.deleteSelected()
                        }
                    } else {
                        pendingDeletePaths = paths
                        pendingDeleteOnRemoved = onRemoved
                        showAllFilesForDelete = true
                    }
                },
            )
        }
    }

    if (showAllFilesForDelete && pendingDeletePaths.isNotEmpty()) {
        AllFilesAccessForLocalRemovalDialog(
            count = pendingDeletePaths.size,
            purpose = LocalMediaRemovalPrompt.DELETE,
            onDismiss = { showAllFilesForDelete = false },
            onOpenSettings = {
                showAllFilesForDelete = false
                viewModel.queueDeleteAfterAllFilesAccess(
                    pendingDeletePaths,
                    pendingDeleteOnRemoved,
                )
                if (!MediaStorageAccess.openAllFilesAccessSettings(context)) {
                    viewModel.showMessage(
                        "Abra Configurações → Apps → One Gallery Sync → Permissões → Arquivos.",
                    )
                }
                pendingDeletePaths = emptyList()
                pendingDeleteOnRemoved = null
            },
            onContinueWithSystemPrompt = {
                showAllFilesForDelete = false
                val paths = pendingDeletePaths
                val onRemoved = pendingDeleteOnRemoved
                pendingDeletePaths = emptyList()
                pendingDeleteOnRemoved = null
                if (paths.size == 1 && onRemoved != null) {
                    viewModel.deleteSingle(paths.single(), onRemoved)
                } else {
                    viewModel.deleteSelected()
                }
            },
        )
    }

    if (showAllFilesForRelease && pendingReleasePaths.isNotEmpty()) {
        AllFilesAccessForLocalRemovalDialog(
            count = pendingReleasePaths.size,
            purpose = LocalMediaRemovalPrompt.RELEASE_SPACE,
            onDismiss = { showAllFilesForRelease = false },
            onOpenSettings = {
                showAllFilesForRelease = false
                viewModel.queueReleaseAfterAllFilesAccess(
                    pendingReleasePaths,
                    pendingReleaseOnDone,
                )
                if (!MediaStorageAccess.openAllFilesAccessSettings(context)) {
                    viewModel.showMessage(
                        "Abra Configurações → Apps → One Gallery Sync → Permissões → Arquivos.",
                    )
                }
                pendingReleasePaths = emptyList()
                pendingReleaseOnDone = null
            },
            onContinueWithSystemPrompt = {
                showAllFilesForRelease = false
                pendingReleasePaths = emptyList()
                pendingReleaseOnDone = null
            },
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

    // Prioridade sobre NavHost: voltar cancela seleção / fecha detalhes, não a listagem da galeria.
    BackHandler {
        when {
            showAllFilesForRelease -> showAllFilesForRelease = false
            showAllFilesForDelete -> showAllFilesForDelete = false
            showDeleteConfirm -> showDeleteConfirm = false
            showMoveDialog -> showMoveDialog = false
            state.selectionMode -> viewModel.clearSelection()
            detailsPanelOpen -> {
                detailsSettleFraction = 0f
                detailsDragFraction = 0f
                detailsDragging = false
            }
            else -> leaveDetail()
        }
    }
}

@Composable
private fun VideoPlayerWithZoom(
    playback: android.net.Uri,
    canonicalPath: String,
    zoomTransform: ZoomTransformHolder,
    onZoomActiveChanged: (Boolean) -> Unit,
    onToggleImmersive: () -> Unit,
) {
    ZoomableMediaContainer(
        resetKey = canonicalPath,
        transform = zoomTransform,
        modifier = Modifier.fillMaxSize(),
        interceptSingleTap = false,
        onZoomActiveChanged = onZoomActiveChanged,
        onSingleTap = onToggleImmersive,
    ) {
        VideoPlayerView(
            uri = playback,
            modifier = Modifier.fillMaxSize(),
            playWhenReady = true,
            usePlaybackControls = false,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaDetailPage(
    canonicalPath: String,
    liveItem: GalleryDisplayItem?,
    isActivePage: Boolean,
    suppressAutoRecall: Boolean,
    suppressAutoRecallRevision: Int,
    syncCoordinator: SyncCoordinator,
    mediaCacheRepository: MediaCacheRepository,
    onToggleImmersive: () -> Unit,
    onZoomActiveChanged: (Boolean) -> Unit,
    onActivePageError: (String?) -> Unit,
    onLocalOriginalCached: () -> Unit = {},
    onDisplayItemChanged: (GalleryDisplayItem) -> Unit = {},
    onRegisterZoomTransform: (ZoomTransformHolder) -> Unit = {},
    onRecallingChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val zoomTransform = remember(canonicalPath) { ZoomTransformHolder() }
    if (isActivePage) {
        SideEffect { onRegisterZoomTransform(zoomTransform) }
    }
    var item by remember(canonicalPath, liveItem?.canonicalPath) { mutableStateOf(liveItem) }
    var gridNotifiedForCache by remember(canonicalPath) { mutableStateOf(false) }
    var loading by remember(canonicalPath, liveItem?.canonicalPath) { mutableStateOf(liveItem == null) }
    var error by remember(canonicalPath) { mutableStateOf<String?>(null) }
    var loadingFull by remember(canonicalPath) { mutableStateOf(false) }
    var contentAspectRatio by remember(canonicalPath) { mutableStateOf<Float?>(null) }
    val viewContext = LocalContext.current
    val thumbnailCache = remember(viewContext) {
        (viewContext.applicationContext as OneGalleryApplication).galleryThumbnailCache
    }
    if (isActivePage) {
        SideEffect { onRecallingChanged(loadingFull) }
    }

    LaunchedEffect(item, isActivePage) {
        if (!isActivePage) return@LaunchedEffect
        val display = item ?: return@LaunchedEffect
        onDisplayItemChanged(display)
    }

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
        if (suppressAutoRecall) return
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
                item?.let { onDisplayItemChanged(it) }
                notifyGridIfNewlyCached(item)
            } else {
                error = result.exceptionOrNull()?.message
            }
        } finally {
            loadingFull = false
        }
    }

    LaunchedEffect(canonicalPath, isActivePage) {
        if (isActivePage) {
            withContext(Dispatchers.IO) {
                syncCoordinator.recordLocalMediaAccess(canonicalPath)
            }
        }
    }

    LaunchedEffect(
        canonicalPath,
        isActivePage,
        liveItem?.cachedPlaceholderThumb,
        liveItem?.cloudState,
        liveItem?.localReleasedForSpace,
        liveItem?.cachedOriginal?.absolutePath,
    ) {
        if (!isActivePage) {
            if (liveItem != null && item == null) {
                item = liveItem
                loading = false
            }
            return@LaunchedEffect
        }
        try {
            error = null
            val refreshed = loadDisplayItem() ?: liveItem
            if (refreshed != null) {
                val keepLocal = item?.hasViewableLocalOriginal(viewContext) == true
                val refreshedViewable = refreshed.hasViewableLocalOriginal(viewContext)
                item = when {
                    refreshedViewable || !keepLocal -> refreshed
                    else -> item
                }
                if (refreshedViewable) {
                    onDisplayItemChanged(item ?: refreshed)
                }
            }
            if (item != null) loading = false
            withContext(Dispatchers.IO) {
                thumbnailCache.ensureThumbnailCached(canonicalPath)
            }
            val afterThumb = loadDisplayItem() ?: item
            if (afterThumb != null) {
                item = afterThumb
                if (afterThumb.hasViewableLocalOriginal(viewContext)) {
                    onDisplayItemChanged(afterThumb)
                }
            }
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

    LaunchedEffect(
        canonicalPath,
        isActivePage,
        item,
        liveItem,
        suppressAutoRecall,
        suppressAutoRecallRevision,
    ) {
        if (!isActivePage) return@LaunchedEffect
        if (suppressAutoRecall) {
            loadingFull = false
            return@LaunchedEffect
        }
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
            } else {
                val previewItem = item ?: liveItem
                if (previewItem?.hasViewableLocalOriginal(viewContext) != true) {
                    val refreshed = loadDisplayItem()
                    if (refreshed != null) {
                        item = refreshed
                        onDisplayItemChanged(refreshed)
                        notifyGridIfNewlyCached(refreshed)
                    }
                }
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
    LaunchedEffect(isActivePage, canonicalPath) {
        if (!isActivePage) {
            zoomTransform.scale = 1f
            zoomTransform.offset = Offset.Zero
        }
    }
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
                    when {
                        display.kind == MediaKind.VIDEO && playback != null -> {
                            MediaDetailMediaShell(
                                modifier = mediaModifier,
                            ) {
                                VideoPlayerWithZoom(
                                    playback = playback,
                                    canonicalPath = canonicalPath,
                                    zoomTransform = zoomTransform,
                                    onZoomActiveChanged = onZoomActiveChanged,
                                    onToggleImmersive = onToggleImmersive,
                                )
                            }
                        }
                        display.kind == MediaKind.VIDEO -> {
                            MediaDetailMediaShell(
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
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier) {
        content()
    }
}
