package com.onegallery.sync.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.onegallery.sync.data.local.LocalMediaRepository
import com.onegallery.sync.data.local.MediaAlbum
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.db.UserAlbumEntity
import com.onegallery.sync.sync.PendingDeleteTarget
import com.onegallery.sync.sync.SyncCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GalleryUiState(
    val isLoading: Boolean = true,
    val items: List<GalleryDisplayItem> = emptyList(),
    val albums: List<MediaAlbum> = emptyList(),
    val placeAlbums: List<MediaAlbum> = emptyList(),
    val userAlbums: List<UserAlbumEntity> = emptyList(),
    val albumFilter: String? = null,
    val locationFilter: String? = null,
    val sortOrder: GallerySortOrder = GallerySortOrder.DATE_NEWEST,
    val albumListSort: AlbumListSort = AlbumListSort.NAME_A_Z,
    val selectionMode: Boolean = false,
    val selectedPaths: Set<String> = emptySet(),
    val error: String? = null,
    val actionMessage: String? = null,
)

private data class SelectionSlice(
    val browseFilter: GalleryBrowseFilter,
    val isLoading: Boolean,
    val error: String?,
    val message: String?,
    val selecting: Boolean,
)

private data class SortSlice(
    val selected: Set<String>,
    val order: GallerySortOrder,
    val albumSort: AlbumListSort,
)

private data class UiControls(
    val browseFilter: GalleryBrowseFilter,
    val isLoading: Boolean,
    val error: String?,
    val message: String?,
    val selecting: Boolean,
    val selected: Set<String>,
    val order: GallerySortOrder,
    val albumSort: AlbumListSort,
)

class GalleryViewModel(
    private val localMediaRepository: LocalMediaRepository,
    private val syncCoordinator: SyncCoordinator,
    private val galleryPreferences: GalleryPreferences,
    private val mediaCacheRepository: MediaCacheRepository,
) : ViewModel() {

    private val browseFilter = MutableStateFlow<GalleryBrowseFilter>(GalleryBrowseFilter.All)
    private val loading = MutableStateFlow(true)
    private val errorState = MutableStateFlow<String?>(null)
    private val actionMessage = MutableStateFlow<String?>(null)
    private val selectionMode = MutableStateFlow(false)
    private val selectedPaths = MutableStateFlow<Set<String>>(emptySet())
    private val sortOrder = MutableStateFlow(galleryPreferences.sortOrder)
    private val albumListSort = MutableStateFlow(galleryPreferences.albumListSort)

    private val libraryFlow = combine(
        syncCoordinator.galleryMetadata,
        syncCoordinator.userAlbums,
    ) { metadata, userAlbums -> metadata to userAlbums }

    private val uiControls = combine(
        combine(browseFilter, loading, errorState, actionMessage, selectionMode) {
                filter, isLoading, error, message, selecting ->
            SelectionSlice(filter, isLoading, error, message, selecting)
        },
        combine(selectedPaths, sortOrder, albumListSort) { selected, order, albumSort ->
            SortSlice(selected, order, albumSort)
        },
    ) { selection, sort ->
        UiControls(
            browseFilter = selection.browseFilter,
            isLoading = selection.isLoading,
            error = selection.error,
            message = selection.message,
            selecting = selection.selecting,
            selected = sort.selected,
            order = sort.order,
            albumSort = sort.albumSort,
        )
    }

    val state: StateFlow<GalleryUiState> = combine(libraryFlow, uiControls) { (metadata, userAlbums), controls ->
        val allItems = metadata.map { GalleryMapper.toDisplayItem(it, mediaCacheRepository) }
        val items = allItems
            .filter { item ->
                when (val filter = controls.browseFilter) {
                    GalleryBrowseFilter.All -> true
                    is GalleryBrowseFilter.AlbumFolder -> item.albumName == filter.name
                    is GalleryBrowseFilter.Place -> item.locationLabel == filter.label
                }
            }
            .sortedBy(controls.order)

        val albumFilter = (controls.browseFilter as? GalleryBrowseFilter.AlbumFolder)?.name
        val locationFilter = (controls.browseFilter as? GalleryBrowseFilter.Place)?.label

        GalleryUiState(
            isLoading = controls.isLoading,
            items = items,
            albums = buildAlbums(allItems, userAlbums, controls.albumSort),
            placeAlbums = buildPlaceAlbums(allItems),
            userAlbums = userAlbums,
            albumFilter = albumFilter,
            locationFilter = locationFilter,
            sortOrder = controls.order,
            albumListSort = controls.albumSort,
            selectionMode = controls.selecting,
            selectedPaths = controls.selected,
            error = controls.error,
            actionMessage = controls.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GalleryUiState())

    init {
        refresh()
    }

    fun clearBrowseFilters() {
        browseFilter.value = GalleryBrowseFilter.All
        clearSelection()
    }

    fun setAlbumFilter(album: String?) {
        browseFilter.value = if (album == null) GalleryBrowseFilter.All else GalleryBrowseFilter.AlbumFolder(album)
        clearSelection()
    }

    fun setPlaceFilter(placeLabel: String?) {
        browseFilter.value = if (placeLabel == null) {
            GalleryBrowseFilter.All
        } else {
            GalleryBrowseFilter.Place(placeLabel)
        }
        clearSelection()
    }

    fun setSortOrder(order: GallerySortOrder) {
        sortOrder.value = order
        galleryPreferences.sortOrder = order
    }

    fun setAlbumListSort(sort: AlbumListSort) {
        albumListSort.value = sort
        galleryPreferences.albumListSort = sort
    }

    fun refresh() {
        viewModelScope.launch {
            loading.value = true
            errorState.value = null
            syncCoordinator.runBidirectionalSync(userInitiated = false)
                .onFailure {
                    if (errorState.value == null) {
                        errorState.value = it.message
                    }
                }
            loading.value = false
        }
    }

    fun enterSelectionMode(initialPath: String? = null) {
        selectionMode.value = true
        if (initialPath != null) {
            selectedPaths.update { it + initialPath }
        }
    }

    fun toggleSelection(canonicalPath: String) {
        selectedPaths.update { current ->
            if (canonicalPath in current) current - canonicalPath else current + canonicalPath
        }
    }

    fun clearSelection() {
        selectionMode.value = false
        selectedPaths.value = emptySet()
    }

    fun clearActionMessage() {
        actionMessage.value = null
    }

    fun createUserAlbum(title: String, onSuccess: (String) -> Unit = {}) {
        viewModelScope.launch {
            syncCoordinator.createUserAlbum(title)
                .onSuccess { album ->
                    actionMessage.value = "Álbum \"${album.title}\" criado."
                    onSuccess(album.title)
                }
                .onFailure { actionMessage.value = it.message }
        }
    }

    fun moveSelectedToAlbum(albumTitle: String?) {
        val paths = selectedPaths.value.toList()
        if (paths.isEmpty()) return
        viewModelScope.launch {
            syncCoordinator.moveItemsToUserAlbum(paths, albumTitle)
                .onSuccess {
                    val label = albumTitle ?: "pasta original"
                    actionMessage.value = "${paths.size} item(ns) movidos para \"$label\"."
                    clearSelection()
                }
                .onFailure { actionMessage.value = it.message }
        }
    }

    fun deleteSelected() {
        val paths = selectedPaths.value.toList()
        if (paths.isEmpty()) return
        viewModelScope.launch {
            syncCoordinator.deleteItemsImmediately(paths, PendingDeleteTarget.BOTH)
                .onSuccess { count ->
                    actionMessage.value = "$count item(ns) excluído(s)."
                    clearSelection()
                }
                .onFailure { actionMessage.value = it.message }
        }
    }

    fun deleteSingle(canonicalPath: String, onRemoved: () -> Unit = {}) {
        viewModelScope.launch {
            syncCoordinator.deleteItemsImmediately(
                listOf(canonicalPath),
                PendingDeleteTarget.BOTH,
            ).onSuccess {
                actionMessage.value = "Item excluído."
                onRemoved()
            }.onFailure { actionMessage.value = it.message }
        }
    }

    fun releaseLocalSpaceSelected() {
        val paths = selectedPaths.value.toList()
        if (paths.isEmpty()) return
        viewModelScope.launch {
            syncCoordinator.releaseLocalSpace(paths)
                .onSuccess { count ->
                    actionMessage.value =
                        "$count item(ns): cópia local removida; miniatura e OneDrive mantidos."
                    clearSelection()
                }
                .onFailure { actionMessage.value = it.message }
        }
    }

    fun releaseSingle(canonicalPath: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            syncCoordinator.releaseLocalSpace(listOf(canonicalPath))
                .onSuccess { onDone() }
                .onFailure { actionMessage.value = it.message }
        }
    }
}

private fun buildAlbums(
    mirrored: List<GalleryDisplayItem>,
    userAlbums: List<UserAlbumEntity>,
    sort: AlbumListSort,
): List<MediaAlbum> {
    val grouped = mirrored.groupBy { it.albumName }
    val titles = (userAlbums.map { it.title } + grouped.keys).distinct()
    val albums = titles.map { title ->
        val items = grouped[title].orEmpty()
        val latest = items.maxOfOrNull { it.sortTimestamp } ?: 0L
        MediaAlbum(
            bucketId = "album:$title",
            title = title,
            itemCount = items.size,
            coverUri = items.firstOrNull()?.localUri ?: items.firstOrNull()?.cachedOriginal?.let {
                android.net.Uri.fromFile(it)
            },
            sortTimestamp = latest,
        )
    }
    return when (sort) {
        AlbumListSort.NAME_A_Z -> albums.sortedBy { it.title.lowercase() }
        AlbumListSort.NAME_Z_A -> albums.sortedByDescending { it.title.lowercase() }
        AlbumListSort.DATE_NEWEST -> albums.sortedByDescending { it.sortTimestamp }
    }
}

private fun buildPlaceAlbums(items: List<GalleryDisplayItem>): List<MediaAlbum> {
    return items
        .mapNotNull { item -> item.locationLabel?.let { label -> label to item } }
        .groupBy({ it.first }, { it.second })
        .map { (label, group) ->
            val latest = group.maxOfOrNull { it.sortTimestamp } ?: 0L
            MediaAlbum(
                bucketId = "place:$label",
                title = label,
                itemCount = group.size,
                coverUri = group.maxByOrNull { it.sortTimestamp }?.localUri
                    ?: group.firstNotNullOfOrNull { it.cachedOriginal?.let(android.net.Uri::fromFile) },
                sortTimestamp = latest,
            )
        }
        .sortedByDescending { it.sortTimestamp }
}

class GalleryViewModelFactory(
    private val localMediaRepository: LocalMediaRepository,
    private val syncCoordinator: SyncCoordinator,
    private val galleryPreferences: GalleryPreferences,
    private val mediaCacheRepository: MediaCacheRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GalleryViewModel::class.java)) {
            return GalleryViewModel(
                localMediaRepository,
                syncCoordinator,
                galleryPreferences,
                mediaCacheRepository,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
