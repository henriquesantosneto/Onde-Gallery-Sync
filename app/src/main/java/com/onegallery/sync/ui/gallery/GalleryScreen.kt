package com.onegallery.sync.ui.gallery

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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.data.local.MediaStorageAccess
import com.onegallery.sync.ui.albums.formatMediaCount
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.sync.SyncCoordinator
import com.onegallery.sync.ui.theme.ZeroScaffoldContentInsets
import com.onegallery.sync.sync.SyncPaths
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    syncCoordinator: SyncCoordinator,
    mediaCacheRepository: MediaCacheRepository,
    galleryPreferences: GalleryPreferences,
    onOpenItem: (String) -> Unit,
    onExitAlbumFilter: () -> Unit = {},
    /** Na rota albums/browse: sem filtro ativo, volta à lista de álbuns (evita “Fotos” com aba Álbuns). */
    requiresActiveBrowseFilter: Boolean = false,
    /** Filtro vindo da rota (evita corrida com o ViewModel antes da 1ª frame). */
    routeAlbumFolderKey: String? = null,
    routePlaceMatchKey: String? = null,
    routePlaceTitle: String? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gridFrame by viewModel.publishedPhotoGrid.collectAsStateWithLifecycle()
    val selectionMode by viewModel.photoSelectionMode.collectAsStateWithLifecycle()
    val selectedPaths by viewModel.photoSelectedPaths.collectAsStateWithLifecycle()
    LaunchedEffect(routeAlbumFolderKey) {
        routeAlbumFolderKey?.let { viewModel.setAlbumFilter(it) }
    }
    LaunchedEffect(routePlaceMatchKey, routePlaceTitle) {
        if (routePlaceMatchKey != null && routePlaceTitle != null) {
            viewModel.setPlaceFilter(routePlaceTitle, routePlaceMatchKey)
        }
    }
    val fullBrowseGridItems = remember(
        requiresActiveBrowseFilter,
        gridFrame.libraryItems,
        gridFrame.items,
        gridFrame.sortOrder,
        state.sortOrder,
        routeAlbumFolderKey,
        routePlaceMatchKey,
        routePlaceTitle,
    ) {
        resolveBrowseGridItems(
            requiresActiveBrowseFilter = requiresActiveBrowseFilter,
            libraryItems = gridFrame.libraryItems,
            browseItemsFromState = gridFrame.items,
            sortOrder = state.sortOrder,
            routeAlbumFolderKey = routeAlbumFolderKey,
            routePlaceMatchKey = routePlaceMatchKey,
            routePlaceTitle = routePlaceTitle,
            mediaCacheRepository = mediaCacheRepository,
        )
    }
    val gridItems = remember(fullBrowseGridItems, state.gridWindowLimit) {
        fullBrowseGridItems.take(state.gridWindowLimit)
    }
    val browseGridHasMore = fullBrowseGridItems.size > gridItems.size
    val gridPinch = rememberGalleryGridZoom(galleryPreferences.galleryGridZoom) { scale ->
        galleryPreferences.galleryGridZoom = scale
    }
    val mediaCellMin = gridAdaptiveMinSize(baseMinDp = 120f, zoomScale = gridPinch.scale)
    val density = LocalDensity.current
    var showSortMenu by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showMediaDetails by remember { mutableStateOf(false) }
    var showSelectionOverflow by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showAllFilesForDelete by remember { mutableStateOf(false) }
    var showAllFilesForRelease by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var browseFilterWasActive by remember(requiresActiveBrowseFilter) { mutableStateOf(false) }
    LaunchedEffect(
        requiresActiveBrowseFilter,
        state.albumFilter,
        state.locationFilter,
        routeAlbumFolderKey,
        routePlaceMatchKey,
    ) {
        if (!requiresActiveBrowseFilter) return@LaunchedEffect
        if (routeAlbumFolderKey != null || routePlaceMatchKey != null) {
            browseFilterWasActive = true
            return@LaunchedEffect
        }
        val hasFilter = state.albumFilter != null || state.locationFilter != null
        if (hasFilter) {
            browseFilterWasActive = true
            return@LaunchedEffect
        }
        if (browseFilterWasActive) {
            onExitAlbumFilter()
        }
    }

    LaunchedEffect(requiresActiveBrowseFilter, state.albumFilter, state.locationFilter) {
        if (requiresActiveBrowseFilter) return@LaunchedEffect
        if (state.albumFilter != null || state.locationFilter != null) {
            viewModel.clearBrowseFilters()
        }
    }
    val title = when {
        selectionMode -> "${selectedPaths.size} selecionado(s)"
        requiresActiveBrowseFilter && routePlaceTitle != null -> routePlaceTitle
        requiresActiveBrowseFilter && routeAlbumFolderKey != null ->
            SyncPaths.albumListDisplayName(routeAlbumFolderKey)
        requiresActiveBrowseFilter && state.locationFilter != null -> state.locationFilter ?: "Local"
        requiresActiveBrowseFilter && state.albumFilter != null ->
            SyncPaths.albumListDisplayName(state.albumFilter ?: "Álbum")
        else -> "Fotos"
    }
    val filteredBrowse = requiresActiveBrowseFilter &&
        (state.locationFilter != null || state.albumFilter != null ||
            routeAlbumFolderKey != null || routePlaceMatchKey != null)
    val browseMediaCountLine = if (filteredBrowse && !selectionMode) {
        val photos = fullBrowseGridItems.count { it.kind == MediaKind.IMAGE }
        val videos = fullBrowseGridItems.count { it.kind == MediaKind.VIDEO }
        formatMediaCount(photos, videos)
    } else {
        null
    }

    Scaffold(
        contentWindowInsets = ZeroScaffoldContentInsets,
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
                    if (!selectionMode && filteredBrowse) {
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
                    if (selectionMode) {
                        IconButton(onClick = { showMoveDialog = true }) {
                            Icon(Icons.Outlined.DriveFileMove, contentDescription = "Mover")
                        }
                        if (selectedPaths.isNotEmpty()) {
                            IconButton(onClick = { showDeleteConfirm = true }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Excluir")
                            }
                            IconButton(
                                onClick = {
                                    shareGalleryMedia(
                                        context,
                                        selectedPaths,
                                        listOf(state.libraryItems, state.items),
                                    )?.let(viewModel::showMessage)
                                },
                            ) {
                                Icon(Icons.Outlined.Share, contentDescription = "Compartilhar")
                            }
                            MediaGalleryOverflowMenuButton(
                                expanded = showSelectionOverflow,
                                onExpandedChange = { showSelectionOverflow = it },
                                showDetails = true,
                                onDetails = { showMediaDetails = true },
                                onFreeSpace = {
                                    if (viewModel.canDeleteLocallyWithoutSystemPrompt()) {
                                        viewModel.releaseLocalSpaceSelected()
                                    } else {
                                        showAllFilesForRelease = true
                                    }
                                },
                            )
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
            gridItems.isEmpty() && gridFrame.libraryItems.isEmpty() && !state.indexHydrated -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
            state.isLoading && gridItems.isEmpty() && gridFrame.libraryItems.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
            state.error != null && gridItems.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { Text(state.error ?: "Erro") }
            }
            gridItems.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (requiresActiveBrowseFilter) {
                            "Nenhuma mídia neste álbum ainda. Sincronize ou aguarde as miniaturas."
                        } else {
                            "Nenhuma mídia encontrada. Verifique permissão de fotos ou aguarde o índice local."
                        },
                    )
                }
            }
            else -> {
                val dateTimeline = isDateTimelineSort(state.sortOrder)
                val timelineGrouping = timelineGroupingForGridScale(gridPinch.scale)
                val galleryScrollToTop by viewModel.galleryScrollToTopRequests.collectAsStateWithLifecycle()
                val timelinePresorted = isDateTimelineSort(state.sortOrder)
                val gridEntries = remember(gridItems, state.sortOrder, timelineGrouping, timelinePresorted) {
                    buildGalleryGridEntries(
                        gridItems,
                        state.sortOrder,
                        timelineGrouping,
                        itemsAlreadySortedForTimeline = timelinePresorted,
                    )
                }
                GalleryBlitPhotoGrid(
                    entries = gridEntries,
                    dateTimeline = dateTimeline,
                    gridScale = gridPinch.scale,
                    cellMinPx = with(density) { mediaCellMin.roundToPx() },
                    selectionMode = selectionMode,
                    selectedPaths = selectedPaths,
                    mediaCache = mediaCacheRepository,
                    scrollToTopToken = galleryScrollToTop,
                    onOpen = { path ->
                        val browsePaths = if (requiresActiveBrowseFilter) {
                            fullBrowseGridItems.map { it.canonicalPath }
                        } else {
                            emptyList()
                        }
                        viewModel.beginDetailSession(path, browsePaths)
                        onOpenItem(URLEncoder.encode(path, StandardCharsets.UTF_8.name()))
                    },
                    onToggle = { path -> viewModel.toggleSelection(path) },
                    onLongPress = { path -> viewModel.enterSelectionMode(path) },
                    onDragSelect = { path -> viewModel.enterSelectionMode(path) },
                    onPinchCancelSelection = { viewModel.clearSelection() },
                    onPinch = gridPinch.applyPinch,
                    onMultiTouch = gridPinch.setMultiTouch,
                    onNearEnd = {
                        if (browseGridHasMore) viewModel.loadMoreGridItems()
                    },
                    onScrollEnd = {
                        syncCoordinator.flushPendingGalleryDisplayRefreshIfAny()
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            }
        }
    }

    if (showMediaDetails && selectedPaths.isNotEmpty()) {
        MediaDetailsBottomSheet(
            canonicalPaths = selectedPaths.toList(),
            displayItems = gridItems,
            syncCoordinator = syncCoordinator,
            mediaCacheRepository = mediaCacheRepository,
            onDismiss = { showMediaDetails = false },
        )
    }

    if (showDeleteConfirm && selectedPaths.isNotEmpty()) {
        val selectedCount = selectedPaths.size
        DeleteSelectionDialog(
            count = selectedCount,
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
                showDeleteConfirm = false
                if (viewModel.canDeleteLocallyWithoutSystemPrompt()) {
                    viewModel.deleteSelected()
                } else {
                    showAllFilesForDelete = true
                }
            },
        )
    }

    if (showAllFilesForDelete && selectedPaths.isNotEmpty()) {
        val paths = selectedPaths.toList()
        AllFilesAccessForLocalRemovalDialog(
            count = paths.size,
            purpose = LocalMediaRemovalPrompt.DELETE,
            onDismiss = { showAllFilesForDelete = false },
            onOpenSettings = {
                showAllFilesForDelete = false
                viewModel.queueDeleteAfterAllFilesAccess(paths)
                if (!MediaStorageAccess.openAllFilesAccessSettings(context)) {
                    viewModel.showMessage(
                        "Abra Configurações → Apps → One Gallery Sync → Permissões → Arquivos.",
                    )
                }
            },
            onContinueWithSystemPrompt = {
                showAllFilesForDelete = false
                viewModel.deleteSelected()
            },
        )
    }

    if (showAllFilesForRelease && selectedPaths.isNotEmpty()) {
        val paths = selectedPaths.toList()
        AllFilesAccessForLocalRemovalDialog(
            count = paths.size,
            purpose = LocalMediaRemovalPrompt.RELEASE_SPACE,
            onDismiss = { showAllFilesForRelease = false },
            onOpenSettings = {
                showAllFilesForRelease = false
                viewModel.queueReleaseAfterAllFilesAccess(paths)
                if (!MediaStorageAccess.openAllFilesAccessSettings(context)) {
                    viewModel.showMessage(
                        "Abra Configurações → Apps → One Gallery Sync → Permissões → Arquivos.",
                    )
                }
            },
            onContinueWithSystemPrompt = {
                showAllFilesForRelease = false
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

