package com.onegallery.sync.ui.albums

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import coil.compose.AsyncImage
import com.onegallery.sync.data.local.MediaAlbum
import com.onegallery.sync.ui.gallery.AlbumGridCoverImage
import com.onegallery.sync.ui.gallery.LiveAlbumCover
import com.onegallery.sync.ui.gallery.AlbumGridFastScrollRail
import com.onegallery.sync.ui.gallery.AlbumListSort
import com.onegallery.sync.ui.gallery.GalleryFastScrollRailReservedWidth
import com.onegallery.sync.ui.gallery.GalleryPreferences
import com.onegallery.sync.ui.gallery.GalleryViewModel
import com.onegallery.sync.sync.SyncPaths
import com.onegallery.sync.ui.gallery.buildAlbumGridFastScrollMarkers
import com.onegallery.sync.ui.theme.ZeroScaffoldContentInsets
import com.onegallery.sync.ui.gallery.albumMediaCountBadgeCorner
import com.onegallery.sync.ui.gallery.albumMediaCountBadgeFontScale
import com.onegallery.sync.ui.gallery.albumMediaCountBadgeInnerPadding
import com.onegallery.sync.ui.gallery.gridAdaptiveMinSize
import com.onegallery.sync.ui.gallery.gridOverlayPadding
import com.onegallery.sync.ui.gallery.rememberGalleryGridZoom

private enum class AlbumsBrowseTab {
    ALBUMS,
    PLACES,
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumsScreen(
    viewModel: GalleryViewModel,
    galleryPreferences: GalleryPreferences,
    albumsBackStackEntry: NavBackStackEntry? = null,
    onOpenFolderAlbum: (filterKey: String) -> Unit,
    onOpenPlaceAlbum: (title: String, matchKey: String) -> Unit,
    onOpenMap: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gridPinch = rememberGalleryGridZoom(galleryPreferences.albumGridZoom) { scale ->
        galleryPreferences.albumGridZoom = scale
    }
    val albumCellMin = gridAdaptiveMinSize(baseMinDp = 156f, zoomScale = gridPinch.scale)
    val compactTiles = albumCellMin < 112.dp
    var showSortMenu by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newAlbumName by remember { mutableStateOf("") }
    var selectedTab by rememberSaveable { mutableStateOf(AlbumsBrowseTab.ALBUMS) }
    var detailsAlbum by remember { mutableStateOf<Pair<MediaAlbum, Boolean>?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    LaunchedEffect(selectedTab) {
        if (selectedTab == AlbumsBrowseTab.PLACES) {
            viewModel.clearAlbumSelection()
        }
    }

    LaunchedEffect(albumsBackStackEntry) {
        if (albumsBackStackEntry?.savedStateHandle?.remove<Boolean>("resetAlbumsSubTab") == true) {
            selectedTab = AlbumsBrowseTab.ALBUMS
        }
    }

    val inAlbumMultiSelect =
        state.albumSelectionMode && selectedTab == AlbumsBrowseTab.ALBUMS
    BackHandler(enabled = inAlbumMultiSelect) {
        viewModel.clearAlbumSelection()
    }

    val selectedAlbumModels = state.albums.filter { it.filterKey in state.selectedAlbumTitles }
    val selectedUserAlbums = selectedAlbumModels.filter { it.isUserManaged }
    val canRename = state.selectedAlbumTitles.size == 1 && selectedUserAlbums.size == 1
    val canDeleteUserAlbums = selectedUserAlbums.isNotEmpty()

    Scaffold(
        contentWindowInsets = ZeroScaffoldContentInsets,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            when {
                                state.albumSelectionMode && selectedTab == AlbumsBrowseTab.ALBUMS ->
                                    "${state.selectedAlbumTitles.size} álbum(ns)"
                                else -> "Álbuns"
                            },
                        )
                    },
                    navigationIcon = {
                        if (inAlbumMultiSelect) {
                            IconButton(onClick = { viewModel.clearAlbumSelection() }) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.ArrowBack,
                                    contentDescription = "Sair da seleção",
                                )
                            }
                        }
                    },
                    actions = {
                        if (inAlbumMultiSelect) {
                            if (state.selectedAlbumTitles.size == 1) {
                                IconButton(
                                    onClick = {
                                        val album = selectedAlbumModels.firstOrNull() ?: return@IconButton
                                        detailsAlbum = album to false
                                    },
                                ) {
                                    Icon(Icons.Outlined.Info, contentDescription = "Detalhes")
                                }
                            }
                            if (canRename) {
                                IconButton(
                                    onClick = {
                                        renameValue = selectedUserAlbums.first().title
                                        showRenameDialog = true
                                    },
                                ) {
                                    Icon(Icons.Outlined.Edit, contentDescription = "Renomear")
                                }
                            }
                            if (canDeleteUserAlbums) {
                                IconButton(onClick = { showDeleteConfirm = true }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "Excluir álbuns")
                                }
                            }
                            TextButton(onClick = { viewModel.clearAlbumSelection() }) {
                                Text("Cancelar")
                            }
                        } else {
                            IconButton(onClick = onOpenMap) {
                                Icon(Icons.Outlined.Map, contentDescription = "Mapa de fotos")
                            }
                            if (selectedTab == AlbumsBrowseTab.ALBUMS) {
                                IconButton(onClick = { showCreateDialog = true }) {
                                    Icon(Icons.Outlined.Add, contentDescription = "Novo álbum")
                                }
                            }
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Outlined.Sort, contentDescription = "Ordenar")
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                            ) {
                                AlbumListSort.entries.forEach { sort ->
                                    DropdownMenuItem(
                                        text = { Text(sort.label) },
                                        onClick = {
                                            viewModel.setAlbumListSort(sort)
                                            showSortMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
                PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                    Tab(
                        selected = selectedTab == AlbumsBrowseTab.ALBUMS,
                        onClick = { selectedTab = AlbumsBrowseTab.ALBUMS },
                        text = { Text("Álbuns") },
                    )
                    Tab(
                        selected = selectedTab == AlbumsBrowseTab.PLACES,
                        onClick = { selectedTab = AlbumsBrowseTab.PLACES },
                        text = { Text("Locais") },
                    )
                }
            }
        },
    ) { padding ->
        val gridEntries = remember(state.placeAlbums, state.albums, selectedTab) {
            when (selectedTab) {
                AlbumsBrowseTab.ALBUMS -> buildAlbumGridEntries(state.albums)
                AlbumsBrowseTab.PLACES -> buildPlaceGridEntries(state.placeAlbums)
            }
        }
        val awaitingLocalAlbumIndex = state.indexHydrated &&
            gridEntries.isEmpty() &&
            state.libraryItems.isNotEmpty()
        when {
            gridEntries.isEmpty() && (!state.indexHydrated || awaitingLocalAlbumIndex) -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            }
            gridEntries.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when (selectedTab) {
                            AlbumsBrowseTab.ALBUMS ->
                                "Nenhum álbum ainda. Crie um álbum ou aguarde o índice da galeria."
                            AlbumsBrowseTab.PLACES ->
                                "Nenhum local com GPS no índice. Conceda " +
                                    "“Localização das mídias” após indexar as fotos."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            else -> {
                val albumGridState = rememberLazyGridState()
                val railAlbums = when (selectedTab) {
                    AlbumsBrowseTab.ALBUMS -> state.albums
                    AlbumsBrowseTab.PLACES -> state.placeAlbums
                }
                val albumScrollMarkers = remember(railAlbums, state.albumListSort) {
                    buildAlbumGridFastScrollMarkers(railAlbums, state.albumListSort)
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .then(gridPinch.gridModifier),
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = albumCellMin),
                        state = albumGridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = 12.dp + GalleryFastScrollRailReservedWidth,
                            top = 12.dp,
                            bottom = 12.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                    items(
                        items = gridEntries,
                        key = { it.key },
                        span = { GridItemSpan(1) },
                    ) { entry ->
                        when (entry) {
                            is AlbumsGridEntry.Album -> {
                                val isPlace = entry.isPlace
                                val selected = !isPlace && entry.album.filterKey in state.selectedAlbumTitles
                                AlbumGridTile(
                                    album = entry.album,
                                    compact = compactTiles,
                                    gridZoomScale = gridPinch.scale,
                                    isPlace = isPlace,
                                    selected = selected,
                                    selectionMode = !isPlace && state.albumSelectionMode,
                                    gesturesEnabled = !gridPinch.blockItemGestures,
                                    coverRevision = state.albumCoverRevisions[entry.album.bucketId] ?: 0L,
                                    resolveLiveCover = viewModel::liveAlbumCoverFor,
                                    onClick = {
                                        if (!isPlace && state.albumSelectionMode) {
                                            viewModel.toggleAlbumSelection(entry.album.filterKey)
                                        } else if (isPlace) {
                                            val matchKey = entry.album.bucketId.removePrefix("place:")
                                            onOpenPlaceAlbum(entry.album.title, matchKey)
                                        } else {
                                            onOpenFolderAlbum(entry.album.filterKey)
                                        }
                                    },
                                    onLongClick = {
                                        if (isPlace) {
                                            detailsAlbum = entry.album to true
                                        } else {
                                            if (!state.albumSelectionMode) {
                                                viewModel.enterAlbumSelectionMode(entry.album.filterKey)
                                            } else {
                                                viewModel.toggleAlbumSelection(entry.album.filterKey)
                                            }
                                        }
                                    },
                                )
                            }
                            is AlbumsGridEntry.Header -> Unit
                        }
                    }
                    }
                    AlbumGridFastScrollRail(
                        gridState = albumGridState,
                        itemCount = gridEntries.size,
                        markers = albumScrollMarkers,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Novo álbum") },
            text = {
                Column {
                    Text(
                        "Álbuns personalizados organizam a galeria; o OneDrive mantém a pasta original.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    OutlinedTextField(
                        value = newAlbumName,
                        onValueChange = { newAlbumName = it },
                        label = { Text("Nome") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newAlbumName.isNotBlank()) {
                            viewModel.createUserAlbum(newAlbumName.trim())
                            newAlbumName = ""
                            showCreateDialog = false
                        }
                    },
                ) { Text("Criar") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancelar") }
            },
        )
    }

    if (showRenameDialog && canRename) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Renomear álbum") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    label = { Text("Novo nome") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val old = selectedUserAlbums.first().title
                        val newName = renameValue.trim()
                        if (newName.isNotBlank()) {
                            viewModel.renameUserAlbum(old, newName) {
                                showRenameDialog = false
                            }
                        }
                    },
                ) { Text("Salvar") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancelar") }
            },
        )
    }

    if (showDeleteConfirm && canDeleteUserAlbums) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Excluir álbum(ns)?") },
            text = {
                Text(
                    "Remove ${selectedUserAlbums.size} álbum(ns) personalizado(s). " +
                        "As fotos e vídeos não serão apagados — só saem dessa organização.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.deleteSelectedUserAlbums()
                    },
                ) { Text("Excluir") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancelar") }
            },
        )
    }

    val details = detailsAlbum
    if (details != null) {
        val (album, isPlace) = details
        AlbumDetailsBottomSheet(
            album = album,
            isPlace = isPlace,
            onDismiss = { detailsAlbum = null },
            onOpenAlbum = {
                if (isPlace) {
                    val matchKey = album.bucketId.removePrefix("place:")
                    onOpenPlaceAlbum(album.title, matchKey)
                } else {
                    onOpenFolderAlbum(album.filterKey)
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumGridTile(
    album: MediaAlbum,
    compact: Boolean,
    gridZoomScale: Float,
    isPlace: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    gesturesEnabled: Boolean = true,
    coverRevision: Long,
    resolveLiveCover: suspend (MediaAlbum) -> LiveAlbumCover,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (gesturesEnabled) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier
                },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(if (compact) 10.dp else 16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            AlbumGridCoverImage(
                album = album,
                isPlace = isPlace,
                fallbackCover = album.coverModel,
                coverRevision = coverRevision,
                resolveLiveCover = resolveLiveCover,
                contentDescription = album.title,
            )
            if (selectionMode) {
                Icon(
                    imageVector = if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(50)),
                )
            }
            val badgeFontScale = albumMediaCountBadgeFontScale(gridZoomScale)
            val (badgePadH, badgePadV) = albumMediaCountBadgeInnerPadding(gridZoomScale)
            val badgeOuterPad = gridOverlayPadding(gridZoomScale)
            Text(
                text = formatAlbumMediaCountBadge(album),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = MaterialTheme.typography.labelSmall.fontSize * badgeFontScale,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(badgeOuterPad)
                    .background(
                        Color.Black.copy(alpha = 0.55f),
                        RoundedCornerShape(albumMediaCountBadgeCorner(gridZoomScale)),
                    )
                    .padding(horizontal = badgePadH, vertical = badgePadV),
            )
        }
        Text(
            text = album.title,
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleMedium,
            maxLines = if (compact) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = if (compact) 6.dp else 10.dp),
        )
        if (!isPlace && !album.isUserManaged && album.filterKey != album.title) {
            Text(
                text = album.filterKey,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (!compact) {
            Text(
                text = formatAlbumMediaCount(album),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
