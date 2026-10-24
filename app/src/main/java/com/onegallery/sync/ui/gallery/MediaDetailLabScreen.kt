package com.onegallery.sync.ui.gallery

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onegallery.sync.OneGalleryApplication
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.data.local.MediaStorageAccess
import com.onegallery.sync.sync.SyncCoordinator
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Visualizador experimental (estilo Samsung). Rota separada — o [MediaDetailScreen] padrão
 * permanece intacto até você decidir trocar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaDetailLabScreen(
    initialCanonicalPath: String,
    viewModel: GalleryViewModel,
    syncCoordinator: SyncCoordinator,
    mediaCacheRepository: MediaCacheRepository,
    onBack: () -> Unit,
    onOpenStandardDetail: (canonicalPath: String) -> Unit,
    onOpenPlacesMap: (latitude: Double, longitude: Double) -> Unit = { _, _ -> },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val liveItems = state.libraryItems
    val context = LocalContext.current
    val galleryPreferences = remember(context) {
        (context.applicationContext as OneGalleryApplication).galleryPreferences
    }
    val pagerScope = rememberCoroutineScope()
    var immersive by remember { mutableStateOf(false) }

    ImmersiveSystemBarsEffect(
        immersive = immersive,
        edgeToEdgeWithVisibleBars = !immersive,
    )

    if (liveItems.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(600)
            if (viewModel.state.value.libraryItems.isEmpty()) onBack()
        }
        return
    }

    val itemsByPath = remember(liveItems) { liveItems.associateBy { it.canonicalPath } }
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
    val pagerPaths = remember(contextItems) { contextItems.map { it.canonicalPath } }
    val initialPage = pagerPaths.indexOf(initialCanonicalPath).coerceAtLeast(0)
    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, (pagerPaths.size - 1).coerceAtLeast(0)),
    ) { pagerPaths.size.coerceAtLeast(1) }

    val currentPath = pagerPaths.getOrNull(pagerState.currentPage)
    val currentItem = currentPath?.let { path ->
        preferDisplayItemForCloudBadge(null, itemsByPath[path], context)
    }
    val currentSelected = currentPath != null && currentPath in state.selectedPaths
    val barTitle = currentItem?.displayName ?: "Mídia"
    val barSubtitle = if (pagerPaths.size > 1) {
        "${pagerState.currentPage + 1} / ${pagerPaths.size}"
    } else {
        null
    }

    var showOverflowMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showAllFilesForDelete by remember { mutableStateOf(false) }
    var showAllFilesForRelease by remember { mutableStateOf(false) }
    var pendingDeletePaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingDeleteOnRemoved by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingReleasePaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingReleaseOnDone by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun leaveDetail() {
        viewModel.notifyLocalDisplayChanged()
        onBack()
    }

    val filmstripItems = remember(pagerPaths, itemsByPath, contextItems) {
        val pool = itemsByPath.ifEmpty { contextItems.associateBy { it.canonicalPath } }
        pagerPaths.mapNotNull { path -> pool[path] }
    }

    var activeVideoPath by remember(pagerPaths) {
        mutableStateOf(pagerPaths.getOrNull(pagerState.currentPage))
    }
    LaunchedEffect(pagerState.currentPage, pagerPaths) {
        activeVideoPath = pagerPaths.getOrNull(pagerState.currentPage)
    }

    var detailsSettleFraction by remember { mutableFloatStateOf(0f) }
    var detailsDragFraction by remember { mutableFloatStateOf(0f) }
    var detailsDragging by remember { mutableStateOf(false) }
    var detailsContentLevel by remember { mutableStateOf(MediaDetailContentLevel.Simple) }

    LaunchedEffect(pagerState.currentPage) {
        detailsSettleFraction = 0f
        detailsDragFraction = 0f
        detailsDragging = false
        detailsContentLevel = MediaDetailContentLevel.Simple
        immersive = false
    }

    fun snapDetailsPanel(velocityYPxPerSec: Float = 0f) {
        val snapped = MediaDetailSheetAnchors.snap(detailsDragFraction, velocityYPxPerSec)
        detailsSettleFraction = snapped
        detailsDragFraction = snapped
    }

    fun openDetailsToSimple() {
        if (currentPath == null) return
        detailsSettleFraction = MediaDetailSheetAnchors.SIMPLE
        detailsDragFraction = MediaDetailSheetAnchors.SIMPLE
    }

    val detailsDisplayFraction = rememberMediaDetailSheetFraction(
        settleTarget = detailsSettleFraction,
        dragFraction = detailsDragFraction,
        dragging = detailsDragging,
        resetKey = pagerState.currentPage,
    )

    LaunchedEffect(detailsDragging, detailsDragFraction, detailsSettleFraction) {
        val fraction = if (detailsDragging) detailsDragFraction else detailsSettleFraction
        detailsContentLevel = contentLevelForPanelFraction(fraction, detailsContentLevel)
    }

    val detailPaths = currentPath?.let { listOf(it) } ?: emptyList()
    val detailsPanelOpen = when {
        detailsDragging -> detailsDragFraction > 0.14f
        else -> detailsSettleFraction > 0.05f
    } && detailPaths.isNotEmpty()

    BackHandler {
        when {
            showAllFilesForRelease -> showAllFilesForRelease = false
            showAllFilesForDelete -> showAllFilesForDelete = false
            showDeleteConfirm -> showDeleteConfirm = false
            showMoveDialog -> showMoveDialog = false
            state.selectionMode -> viewModel.clearSelection()
            immersive -> immersive = false
            detailsPanelOpen -> {
                detailsSettleFraction = 0f
                detailsDragFraction = 0f
                detailsDragging = false
            }
            else -> leaveDetail()
        }
    }

    val filmstripVisible = filmstripItems.size > 1 && !detailsPanelOpen && !immersive
    val playerBottomChromeInset = when {
        immersive -> 32.dp
        filmstripVisible -> 100.dp
        else -> 36.dp
    }

    val configuration = LocalConfiguration.current
    val screenHeightPx = with(LocalDensity.current) {
        configuration.screenHeightDp.dp.toPx()
    }
    val showDetailsSheet = detailPaths.isNotEmpty() &&
        (detailsDragging || detailsSettleFraction > 0.02f)
    val useSplitPhotoLayout = showDetailsSheet || detailsDisplayFraction > 0.03f
    val photoHeightFraction = (1f - detailsDisplayFraction).coerceIn(0.12f, 1f)
    val gestureBaseFraction = remember { mutableFloatStateOf(0f) }
    gestureBaseFraction.floatValue = if (detailsDragging) {
        detailsDragFraction
    } else {
        detailsSettleFraction
    }
    val panelDragBaseFraction = gestureBaseFraction.floatValue
    val panelPullEnabled = currentPath != null && detailPaths.isNotEmpty()
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
    val panelDragModifier = Modifier.draggableDetailPanelHeight(
        enabled = panelPullEnabled,
        screenHeightPx = screenHeightPx,
        fractionBaseState = gestureBaseFraction,
        onDragStart = onPanelDragStart,
        onDragFraction = onPanelDragFraction,
        onDragEnd = onPanelDragEnd,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(if (useSplitPhotoLayout) photoHeightFraction else 1f)
                    .align(Alignment.TopCenter)
                    .then(panelDragModifier),
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = !detailsDragging,
                    key = { page -> pagerPaths.getOrElse(page) { "empty-$page" } },
                ) { page ->
                    val path = pagerPaths.getOrNull(page) ?: return@HorizontalPager
                    val liveItem = itemsByPath[path]
                    val pageActive = pagerState.currentPage == page
                    LabMediaDetailPage(
                        canonicalPath = path,
                        liveItem = liveItem,
                        isActivePage = pageActive,
                        shouldPlayVideo = activeVideoPath == path,
                        bottomChromeInset = playerBottomChromeInset,
                        syncCoordinator = syncCoordinator,
                        mediaCacheRepository = mediaCacheRepository,
                        viewModel = viewModel,
                        onToggleImmersive = { immersive = !immersive },
                        onOpenStandardDetail = { onOpenStandardDetail(path) },
                    )
                }

                if (filmstripVisible) {
                    MediaDetailFilmstrip(
                        items = filmstripItems,
                        currentCanonicalPath = currentPath,
                        onSelectPath = { path ->
                            val index = pagerPaths.indexOf(path)
                            if (index >= 0) {
                                activeVideoPath = path
                                pagerScope.launch { pagerState.scrollToPage(index) }
                            }
                        },
                        syncCoordinator = syncCoordinator,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding(),
                    )
                }

                if (!immersive) {
                MediaDetailTransparentTopBar(
                    modifier = Modifier.align(Alignment.TopCenter),
                    title = barTitle,
                    subtitle = barSubtitle,
                    belowActionsEnd = currentItem?.let { statusItem ->
                        {
                            MediaDetailTopBarCloudStatus(
                                item = statusItem,
                                recalling = false,
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
                                    contentDescription = "Selecionar",
                                )
                            }
                            IconButton(onClick = { showDeleteConfirm = true }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = "Excluir",
                                    tint = Color.White,
                                )
                            }
                            if (detailPaths.isNotEmpty()) {
                                IconButton(onClick = { openDetailsToSimple() }) {
                                    Icon(
                                        Icons.Outlined.Info,
                                        contentDescription = "Detalhes",
                                        tint = Color.White,
                                    )
                                }
                            }
                            MediaGalleryOverflowMenuButton(
                                expanded = showOverflowMenu,
                                onExpandedChange = { showOverflowMenu = it },
                                showDetails = detailPaths.isNotEmpty(),
                                onDetails = { openDetailsToSimple() },
                                onFreeSpace = {
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
}

@Composable
private fun LabMediaDetailPage(
    canonicalPath: String,
    liveItem: GalleryDisplayItem?,
    isActivePage: Boolean,
    shouldPlayVideo: Boolean,
    bottomChromeInset: Dp,
    syncCoordinator: SyncCoordinator,
    mediaCacheRepository: MediaCacheRepository,
    viewModel: GalleryViewModel,
    onToggleImmersive: () -> Unit,
    onOpenStandardDetail: () -> Unit,
) {
    val viewContext = LocalContext.current
    val app = viewContext.applicationContext as OneGalleryApplication
    val localMediaRepository = app.localMediaRepository
    val thumbnailCache = app.galleryThumbnailCache
    var item by remember(canonicalPath, liveItem?.canonicalPath) { mutableStateOf(liveItem) }
    var loading by remember(canonicalPath) { mutableStateOf(liveItem == null) }
    var loadingFull by remember(canonicalPath) { mutableStateOf(false) }
    var error by remember(canonicalPath) { mutableStateOf<String?>(null) }
    var gridNotifiedForCache by remember(canonicalPath) { mutableStateOf(false) }

    suspend fun loadDisplayItem(includeOriginalFile: File? = null): GalleryDisplayItem? =
        withContext(Dispatchers.Default) {
            syncCoordinator.getMetadata(canonicalPath)?.let { entity ->
                val mapped = GalleryMapper.toDisplayItem(
                    entity,
                    mediaCacheRepository,
                    localMediaRepository,
                )
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
        viewModel.notifyLocalDisplayChanged()
    }

    suspend fun loadFullOriginalForViewing() {
        if (!syncCoordinator.needsOriginalRecall(canonicalPath)) return
        loadingFull = true
        error = null
        try {
            val result = syncCoordinator.recallOriginalToDevice(canonicalPath)
            if (result.isSuccess) {
                item = loadDisplayItem(includeOriginalFile = result.getOrNull())
                notifyGridIfNewlyCached(item)
            } else {
                error = result.exceptionOrNull()?.message
            }
        } finally {
            loadingFull = false
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
                item = refreshed
            }
            loading = false
            withContext(Dispatchers.IO) {
                thumbnailCache.ensureThumbnailCached(canonicalPath)
            }
            item = loadDisplayItem() ?: item
            notifyGridIfNewlyCached(item)
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
            val needsRecall = withContext(Dispatchers.Default) {
                syncCoordinator.needsOriginalRecall(canonicalPath)
            }
            if (needsRecall) {
                loadFullOriginalForViewing()
            } else {
                val refreshed = loadDisplayItem()
                if (refreshed != null) {
                    item = refreshed
                    notifyGridIfNewlyCached(refreshed)
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            error = failure.message ?: failure.javaClass.simpleName
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

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            loading && item == null -> CircularProgressIndicator(color = Color.White)
            item == null -> Text("Item não encontrado.", color = Color.White)
            else -> {
                val display = item!!
                when {
                    GalleryMapper.isAudioFileName(display.displayName) -> {
                        val playback = display.playbackUri()
                        if (playback == null && loadingFull) {
                            CircularProgressIndicator(color = Color.White)
                        } else if (playback == null) {
                            LabUnsupportedMediaMessage(
                                message = error ?: "Áudio sem arquivo local. Abra no visualizador padrão ou sincronize.",
                                onOpenStandardDetail = onOpenStandardDetail,
                            )
                        } else {
                            LabSamsungStyleMediaPlayer(
                                uri = playback,
                                modifier = Modifier.fillMaxSize(),
                                isActive = shouldPlayVideo,
                                kind = LabMediaPlaybackKind.Audio,
                                displayName = display.displayName,
                                artworkItem = display,
                                bottomChromeInset = bottomChromeInset,
                            )
                        }
                    }
                    display.kind == MediaKind.VIDEO -> {
                        val playback = display.playbackUri()
                        if (playback == null && loadingFull) {
                            CircularProgressIndicator(color = Color.White)
                        } else if (playback == null) {
                            LabUnsupportedMediaMessage(
                                message = error ?: "Vídeo sem URI de reprodução. Use o visualizador padrão.",
                                onOpenStandardDetail = onOpenStandardDetail,
                            )
                        } else {
                            LabSamsungStyleMediaPlayer(
                                uri = playback,
                                modifier = Modifier.fillMaxSize(),
                                isActive = shouldPlayVideo,
                                kind = LabMediaPlaybackKind.Video,
                                displayName = display.displayName,
                                bottomChromeInset = bottomChromeInset,
                                onVideoAreaTap = onToggleImmersive,
                            )
                        }
                    }
                    display.detailPreviewModel() != null || loadingFull -> {
                        if (loadingFull && display.detailPreviewModel() == null) {
                            CircularProgressIndicator(color = Color.White)
                        } else {
                            LabSamsungStylePhotoViewer(
                                item = display,
                                modifier = Modifier.fillMaxSize(),
                                onToggleImmersive = onToggleImmersive,
                            )
                        }
                    }
                    else -> {
                        LabUnsupportedMediaMessage(
                            message = error ?: "Prévia indisponível neste lab. Detalhes completos no visualizador padrão.",
                            onOpenStandardDetail = onOpenStandardDetail,
                        )
                    }
                }
            }
        }
        if (loadingFull && item != null) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp),
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun LabUnsupportedMediaMessage(
    message: String,
    onOpenStandardDetail: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodyMedium,
            )
            androidx.compose.material3.OutlinedButton(
                onClick = onOpenStandardDetail,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text("Abrir visualizador padrão")
            }
        }
    }
}
