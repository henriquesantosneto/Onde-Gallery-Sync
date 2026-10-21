package com.onegallery.sync.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.onegallery.sync.data.local.LocalMediaRepository
import com.onegallery.sync.data.local.MediaAlbum
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.PlaceComponents
import com.onegallery.sync.data.local.PlaceLevel
import com.onegallery.sync.data.local.PlaceMatchKey
import com.onegallery.sync.data.local.matchesPlace
import com.onegallery.sync.data.local.preferredBrowseKey
import com.onegallery.sync.data.local.db.UserAlbumEntity
import com.onegallery.sync.sync.PendingDeleteTarget
import com.onegallery.sync.sync.SyncCoordinator
import com.onegallery.sync.sync.SyncPaths
import com.onegallery.sync.ui.AppUserMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Foco inicial ao abrir o mapa de locais a partir dos detalhes de uma foto. */
data class PlacesMapFocus(
    val latitude: Double,
    val longitude: Double,
)

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
    val albumSelectionMode: Boolean = false,
    val selectedAlbumTitles: Set<String> = emptySet(),
    val error: String? = null,
    /** Itens com coordenadas GPS (mapa de locais). */
    val geoItems: List<GalleryDisplayItem> = emptyList(),
    /** Revisão por álbum (`bucketId` → nanoTime) após miniatura local gravada. */
    val albumCoverRevisions: Map<String, Long> = emptyMap(),
)

private data class SelectionSlice(
    val browseFilter: GalleryBrowseFilter,
    val isLoading: Boolean,
    val error: String?,
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
    val selecting: Boolean,
    val selected: Set<String>,
    val order: GallerySortOrder,
    val albumSort: AlbumListSort,
    val albumSelecting: Boolean,
    val selectedAlbums: Set<String>,
)

class GalleryViewModel(
    private val localMediaRepository: LocalMediaRepository,
    private val syncCoordinator: SyncCoordinator,
    private val galleryPreferences: GalleryPreferences,
    private val mediaCacheRepository: MediaCacheRepository,
    private val userMessages: AppUserMessages,
) : ViewModel() {

    private val browseFilter = MutableStateFlow<GalleryBrowseFilter>(GalleryBrowseFilter.All)
    private val loading = MutableStateFlow(true)
    private val errorState = MutableStateFlow<String?>(null)
    private val selectionMode = MutableStateFlow(false)
    private val selectedPaths = MutableStateFlow<Set<String>>(emptySet())
    private val sortOrder = MutableStateFlow(galleryPreferences.sortOrder)
    private val albumListSort = MutableStateFlow(galleryPreferences.albumListSort)
    private val albumSelectionMode = MutableStateFlow(false)
    private val selectedAlbumTitles = MutableStateFlow<Set<String>>(emptySet())

    private val albumCoverLocalIndex = AlbumCoverLocalIndex()
    private var lastAlbumCoverRevisions: Map<String, Long> = emptyMap()
    private val folderAlbumLatestSort = mutableMapOf<String, Long>()
    private val placeAlbumLatestSort = mutableMapOf<String, Long>()
    @Volatile
    private var latestLibraryItems: List<GalleryDisplayItem> = emptyList()

    @Volatile
    private var pendingPlacesMapFocus: PlacesMapFocus? = null

    private data class LibrarySnapshot(
        val allItems: List<GalleryDisplayItem>,
        val userAlbums: List<UserAlbumEntity>,
    )

    private val librarySnapshot = combine(
        syncCoordinator.galleryMetadata,
        syncCoordinator.userAlbums,
        syncCoordinator.galleryDisplayRefreshEpoch,
    ) { metadata, userAlbums, _ ->
        metadata to userAlbums
    }.conflate()
        .map { (metadata, userAlbums) ->
            runCatching {
                LibrarySnapshot(
                    allItems = dedupeGalleryDisplayItems(
                        metadata.map { GalleryMapper.toDisplayItem(it, mediaCacheRepository) },
                    ).filter { it.isVisibleInGalleryBrowse(mediaCacheRepository) },
                    userAlbums = userAlbums,
                )
            }.getOrElse { error ->
                if (error is OutOfMemoryError) {
                    errorState.value = "Biblioteca muito grande para exibir de uma vez. Sincronize em partes."
                    LibrarySnapshot(allItems = emptyList(), userAlbums = userAlbums)
                } else {
                    throw error
                }
            }
        }.flowOn(Dispatchers.Default)

    private val uiControls = combine(
        combine(browseFilter, loading, errorState, selectionMode) { filter, isLoading, error, selecting ->
            SelectionSlice(filter, isLoading, error, selecting)
        },
        combine(selectedPaths, sortOrder, albumListSort) { selected, order, albumSort ->
            SortSlice(selected, order, albumSort)
        },
        combine(albumSelectionMode, selectedAlbumTitles) { selecting, titles ->
            selecting to titles
        },
    ) { selection, sort, albumSelection ->
        UiControls(
            browseFilter = selection.browseFilter,
            isLoading = selection.isLoading,
            error = selection.error,
            selecting = selection.selecting,
            selected = sort.selected,
            order = sort.order,
            albumSort = sort.albumSort,
            albumSelecting = albumSelection.first,
            selectedAlbums = albumSelection.second,
        )
    }

    val state: StateFlow<GalleryUiState> = combine(
        librarySnapshot,
        uiControls,
        syncCoordinator.albumCoverRevisions,
    ) { snapshot, controls, coverRevisions ->
        coverRevisions.forEach { (key, stamp) ->
            if (lastAlbumCoverRevisions[key] != stamp) {
                albumCoverLocalIndex.invalidateKey(key)
            }
        }
        lastAlbumCoverRevisions = coverRevisions
        val allItems = snapshot.allItems
        latestLibraryItems = allItems
        val coverKeysToRefresh = detectAlbumCoverSignatureChanges(allItems)
        if (coverKeysToRefresh.isNotEmpty()) {
            syncCoordinator.bumpAlbumCoverRevisionsForBucketIds(coverKeysToRefresh)
        }
        val userAlbums = snapshot.userAlbums
        val items = allItems
            .filter { item ->
                when (val filter = controls.browseFilter) {
                    GalleryBrowseFilter.All -> true
                    is GalleryBrowseFilter.AlbumFolder -> item.albumName == filter.name
                    is GalleryBrowseFilter.Place -> {
                        PlaceMatchKey.decode(filter.matchKey)?.let { key ->
                            item.matchesPlace(key)
                        } ?: (item.locationLabel == filter.displayName)
                    }
                }
            }
            .sortedBy(controls.order)

        val albumFilter = (controls.browseFilter as? GalleryBrowseFilter.AlbumFolder)?.name
        val locationFilter = (controls.browseFilter as? GalleryBrowseFilter.Place)?.displayName

        GalleryUiState(
            isLoading = controls.isLoading,
            items = items,
            albums = buildAlbums(allItems, userAlbums, controls.albumSort, albumCoverLocalIndex, mediaCacheRepository),
            placeAlbums = sortPlaceAlbums(
                buildPlaceAlbums(allItems, albumCoverLocalIndex, mediaCacheRepository),
                controls.albumSort,
            ),
            userAlbums = userAlbums,
            albumFilter = albumFilter,
            locationFilter = locationFilter,
            sortOrder = controls.order,
            albumListSort = controls.albumSort,
            selectionMode = controls.selecting,
            selectedPaths = controls.selected,
            albumSelectionMode = controls.albumSelecting,
            selectedAlbumTitles = controls.selectedAlbums,
            error = controls.error,
            geoItems = allItems.filter { it.latitude != null && it.longitude != null },
            albumCoverRevisions = coverRevisions,
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GalleryUiState())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { syncCoordinator.compactDuplicateIndexRows() }
        }
        viewModelScope.launch {
            try {
                syncCoordinator.galleryMetadata.first()
            } finally {
                loading.value = false
            }
        }
    }

    fun clearBrowseFilters() {
        browseFilter.value = GalleryBrowseFilter.All
        clearSelection()
    }

    fun requestPlacesMapFocus(latitude: Double, longitude: Double) {
        pendingPlacesMapFocus = PlacesMapFocus(latitude, longitude)
    }

    /** Lê e limpa o foco pedido antes de abrir [com.onegallery.sync.ui.places.PlacesMapScreen]. */
    fun consumePlacesMapFocus(): PlacesMapFocus? {
        val focus = pendingPlacesMapFocus
        pendingPlacesMapFocus = null
        return focus
    }

    fun setAlbumFilter(album: String?) {
        browseFilter.value = if (album == null) GalleryBrowseFilter.All else GalleryBrowseFilter.AlbumFolder(album)
        clearSelection()
        if (album != null) {
            viewModelScope.launch(Dispatchers.IO) {
                prefetchBrowseThumbnailsForFolder(album)
            }
        }
    }

    fun setPlaceFilter(displayName: String?, matchKey: String? = null) {
        val placeKey = displayName?.let { matchKey ?: PlaceMatchKey.legacyLabel(it).encode() }
        browseFilter.value = if (displayName == null || placeKey == null) {
            GalleryBrowseFilter.All
        } else {
            GalleryBrowseFilter.Place(displayName = displayName, matchKey = placeKey)
        }
        clearSelection()
        if (placeKey != null) {
            viewModelScope.launch(Dispatchers.IO) {
                prefetchBrowseThumbnailsForPlace(placeKey)
            }
        }
    }

    /** Abre filtro a partir do rótulo salvo na foto (mapa / detalhes). */
    fun setPlaceFilterFromLocationLabel(locationLabel: String) {
        val components = PlaceComponents.fromGeocoderLabel(locationLabel)
        if (components != null && components.hasAnyNamedLevel()) {
            val matchKey = components.preferredBrowseKey(locationLabel).encode()
            setPlaceFilter(components.hierarchicalDisplayTitle(), matchKey)
        } else {
            setPlaceFilter(locationLabel)
        }
    }

    fun setSortOrder(order: GallerySortOrder) {
        sortOrder.value = order
        galleryPreferences.sortOrder = order
    }

    fun setAlbumListSort(sort: AlbumListSort) {
        albumListSort.value = sort
        galleryPreferences.albumListSort = sort
    }

    /** Reaplica cache/local no índice da galeria (ex.: após baixar original no detalhe). */
    fun notifyLocalDisplayChanged() {
        syncCoordinator.notifyGalleryDisplayRefresh()
    }

    /** Capa ao rolar a grade: foto ou vídeo com miniatura local mais recente (sem rede). */
    suspend fun liveAlbumCoverFor(album: MediaAlbum): LiveAlbumCover {
        val items = itemsForAlbumCover(album)
        if (items.isEmpty()) {
            return LiveAlbumCover(displayModel = album.coverModel, sourceItem = null)
        }
        return resolveLiveAlbumCover(items, mediaCacheRepository, album.coverModel)
    }

    private fun detectAlbumCoverSignatureChanges(allItems: List<GalleryDisplayItem>): Set<String> {
        if (allItems.isEmpty()) return emptySet()
        val changed = mutableSetOf<String>()
        allItems.groupBy { it.albumName }.forEach { (name, items) ->
            val key = "album:$name"
            val latest = items.maxOfOrNull { it.sortTimestamp } ?: 0L
            if (folderAlbumLatestSort[key] != latest) {
                folderAlbumLatestSort[key] = latest
                changed.add(key)
            }
        }
        val placeLocated = allItems.mapNotNull { item ->
            val label = item.locationLabel?.trim().orEmpty()
            val components = PlaceComponents.forItem(item)
                ?: label.takeIf { it.isNotEmpty() }?.let { PlaceComponents.fromGeocoderLabel(it) }
            when {
                components != null -> PlaceMatchKey.forComponents(components, item.locationLabel).encode() to item
                label.isNotEmpty() -> PlaceMatchKey.legacyLabel(label).encode() to item
                else -> null
            }
        }
        placeLocated.groupBy({ it.first }, { it.second }).forEach { (encoded, group) ->
            val key = "place:$encoded"
            val latest = group.maxOfOrNull { it.sortTimestamp } ?: 0L
            if (placeAlbumLatestSort[key] != latest) {
                placeAlbumLatestSort[key] = latest
                changed.add(key)
            }
        }
        return changed
    }

    private suspend fun prefetchBrowseThumbnailsForFolder(albumName: String) {
        val items = latestLibraryItems.filter { it.albumName == albumName }
        prefetchBrowseThumbnails(items, coverBucketId = "album:$albumName")
    }

    private suspend fun prefetchBrowseThumbnailsForPlace(encodedMatchKey: String) {
        val matchKey = PlaceMatchKey.decode(encodedMatchKey)
        val items = latestLibraryItems.filter { item ->
            matchKey?.let { item.matchesPlace(it) } == true
        }
        prefetchBrowseThumbnails(items, coverBucketId = "place:$encodedMatchKey")
    }

    private suspend fun prefetchBrowseThumbnails(
        items: List<GalleryDisplayItem>,
        coverBucketId: String,
    ) {
        if (items.isEmpty()) return
        val ordered = items.sortedBy(sortOrder.value)
        val paths = linkedSetOf<String>()
        ordered.firstOrNull { itemNeedsGeneratedThumbnail(it) }?.canonicalPath?.let { paths.add(it) }
        resolveAlbumCoverItem(items, mediaCacheRepository)
            ?.takeIf { itemNeedsGeneratedThumbnail(it) }
            ?.canonicalPath
            ?.let { paths.add(it) }
        ordered
            .asSequence()
            .filter { itemNeedsGeneratedThumbnail(it) }
            .take(48)
            .forEach { paths.add(it.canonicalPath) }
        paths.forEach { path ->
            syncCoordinator.ensureThumbnailCached(path)
        }
        if (paths.isNotEmpty()) {
            syncCoordinator.bumpAlbumCoverRevisionsForBucketIds(setOf(coverBucketId))
        }
    }

    private fun itemNeedsGeneratedThumbnail(item: GalleryDisplayItem): Boolean {
        if (mediaCacheRepository.thumbnailIfCached(item.canonicalPath) != null) return false
        if (item.oneDriveItemId != null) return true
        if (item.localMediaId != null) return true
        if (item.localUri != null) return true
        return false
    }

    private fun itemsForAlbumCover(album: MediaAlbum): List<GalleryDisplayItem> {
        val all = latestLibraryItems
        if (all.isEmpty()) return emptyList()
        return when {
            album.bucketId.startsWith("place:") -> {
                val encoded = album.bucketId.removePrefix("place:")
                val matchKey = PlaceMatchKey.decode(encoded)
                all.filter { item ->
                    matchKey?.let { item.matchesPlace(it) }
                        ?: (item.locationLabel == album.title)
                }
            }
            else -> all.filter { it.albumName == album.filterKey }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            coroutineContext[Job]?.let { syncCoordinator.registerManagedSyncJob(it) }
            loading.value = true
            errorState.value = null
            try {
                syncCoordinator.runBidirectionalSync(userInitiated = false)
                    .onFailure { error ->
                        if (error is CancellationException) return@onFailure
                        if (errorState.value == null) {
                            errorState.value = error.message
                        }
                    }
            } finally {
                loading.value = false
            }
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

    private fun toast(message: String?) {
        message?.let { userMessages.show(it) }
    }

    fun enterAlbumSelectionMode(initialTitle: String? = null) {
        albumSelectionMode.value = true
        if (initialTitle != null) {
            selectedAlbumTitles.value = setOf(initialTitle)
        }
    }

    fun toggleAlbumSelection(title: String) {
        selectedAlbumTitles.update { current ->
            val next = if (title in current) current - title else current + title
            if (next.isEmpty()) {
                albumSelectionMode.value = false
            }
            next
        }
    }

    fun clearAlbumSelection() {
        albumSelectionMode.value = false
        selectedAlbumTitles.value = emptySet()
    }

    fun renameUserAlbum(oldTitle: String, newTitle: String, onSuccess: (String) -> Unit = {}) {
        viewModelScope.launch {
            syncCoordinator.renameUserAlbum(oldTitle, newTitle)
                .onSuccess {
                    selectedAlbumTitles.update { selected ->
                        if (oldTitle in selected) (selected - oldTitle) + newTitle else selected
                    }
                    toast("Álbum renomeado para \"$newTitle\".")
                    onSuccess(newTitle)
                }
                .onFailure { toast(it.message) }
        }
    }

    fun deleteSelectedUserAlbums() {
        val titles = selectedAlbumTitles.value.toList()
        if (titles.isEmpty()) return
        viewModelScope.launch {
            syncCoordinator.deleteUserAlbums(titles)
                .onSuccess { count ->
                    toast(
                        if (count > 0) {
                            "$count álbum(ns) personalizado(s) removido(s). As fotos foram mantidas."
                        } else {
                            "Nenhum álbum personalizado para remover."
                        },
                    )
                    clearAlbumSelection()
                }
                .onFailure { toast(it.message) }
        }
    }

    fun createUserAlbum(title: String, onSuccess: (String) -> Unit = {}) {
        viewModelScope.launch {
            syncCoordinator.createUserAlbum(title)
                .onSuccess { album ->
                    toast("Álbum \"${album.title}\" criado.")
                    onSuccess(album.title)
                }
                .onFailure { toast(it.message) }
        }
    }

    fun moveSelectedToAlbum(albumTitle: String?) {
        val paths = selectedPaths.value.toList()
        if (paths.isEmpty()) return
        viewModelScope.launch {
            syncCoordinator.moveItemsToUserAlbum(paths, albumTitle)
                .onSuccess {
                    val label = albumTitle ?: "pasta original"
                    toast("${paths.size} item(ns) movidos para \"$label\".")
                    clearSelection()
                }
                .onFailure { toast(it.message) }
        }
    }

    fun deleteSelected() {
        val paths = selectedPaths.value.toList()
        if (paths.isEmpty()) return
        viewModelScope.launch {
            syncCoordinator.deleteItemsImmediately(paths, PendingDeleteTarget.BOTH)
                .onSuccess { count ->
                    toast("$count item(ns) excluído(s).")
                    clearSelection()
                }
                .onFailure { toast(it.message) }
        }
    }

    fun deleteSingle(canonicalPath: String, onRemoved: () -> Unit = {}) {
        viewModelScope.launch {
            syncCoordinator.deleteItemsImmediately(
                listOf(canonicalPath),
                PendingDeleteTarget.BOTH,
            ).onSuccess {
                toast("Item excluído.")
                onRemoved()
            }.onFailure { toast(it.message) }
        }
    }

    fun releaseLocalSpaceSelected() {
        val paths = selectedPaths.value.toList()
        if (paths.isEmpty()) return
        viewModelScope.launch {
            syncCoordinator.releaseLocalSpace(paths)
                .onSuccess { count ->
                    toast(
                        "$count item(ns): só a cópia local foi removida; miniatura no app e " +
                            "arquivo no OneDrive mantidos.",
                    )
                    clearSelection()
                }
                .onFailure { toast(it.message) }
        }
    }

    fun releaseSingle(canonicalPath: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            syncCoordinator.releaseLocalSpace(listOf(canonicalPath))
                .onSuccess {
                    toast("Cópia local removida; miniatura no app e arquivo no OneDrive mantidos.")
                    onDone()
                }
                .onFailure { toast(it.message) }
        }
    }

}

private fun buildAlbums(
    mirrored: List<GalleryDisplayItem>,
    userAlbums: List<UserAlbumEntity>,
    sort: AlbumListSort,
    albumCoverLocalIndex: AlbumCoverLocalIndex,
    mediaCacheRepository: MediaCacheRepository,
): List<MediaAlbum> {
    val grouped = mirrored.groupBy { it.albumName }
    val titles = (userAlbums.map { it.title } + grouped.keys).distinct()
    val albums = titles.map { title ->
        val items = grouped[title].orEmpty()
        val latest = items.maxOfOrNull { it.sortTimestamp } ?: 0L
        val userMeta = userAlbums.find { it.title == title }
        val photoCount = items.count { it.kind == com.onegallery.sync.data.local.MediaKind.IMAGE }
        val videoCount = items.count { it.kind == com.onegallery.sync.data.local.MediaKind.VIDEO }
        MediaAlbum(
            bucketId = "album:$title",
            title = if (userMeta != null) title else SyncPaths.albumListDisplayName(title),
            filterKey = title,
            itemCount = items.size,
            photoCount = photoCount,
            videoCount = videoCount,
            coverModel = albumCoverLocalIndex.coverModelForKey(
                cacheKey = "album:$title",
                items = items,
                mediaCacheRepository = mediaCacheRepository,
            ),
            sortTimestamp = latest,
            isUserManaged = userMeta != null,
            userAlbumId = userMeta?.id,
            createdAtEpoch = userMeta?.createdAtEpoch,
        )
    }
    return sortMediaAlbums(albums, sort)
}

private fun sortMediaAlbums(albums: List<MediaAlbum>, sort: AlbumListSort): List<MediaAlbum> {
    return when (sort) {
        AlbumListSort.NAME_A_Z -> albums.sortedBy { it.title.lowercase() }
        AlbumListSort.NAME_Z_A -> albums.sortedByDescending { it.title.lowercase() }
        AlbumListSort.DATE_NEWEST -> albums.sortedByDescending { it.sortTimestamp }
    }
}

private fun buildPlaceAlbums(
    items: List<GalleryDisplayItem>,
    albumCoverLocalIndex: AlbumCoverLocalIndex,
    mediaCacheRepository: MediaCacheRepository,
): List<MediaAlbum> {
    val located = items.mapNotNull { item ->
        val label = item.locationLabel?.trim().orEmpty()
        val components = PlaceComponents.forItem(item)
            ?: label.takeIf { it.isNotEmpty() }?.let { PlaceComponents.fromGeocoderLabel(it) }
        when {
            components != null -> {
                val matchKey = PlaceMatchKey.forComponents(components, item.locationLabel)
                Triple(components, matchKey, item)
            }
            label.isNotEmpty() -> Triple(null, PlaceMatchKey.legacyLabel(label), item)
            else -> null
        }
    }
    return located
        .groupBy({ it.second.encode() }, { it.third })
        .map { (encodedKey, group) ->
            val sampleComponents = PlaceComponents.forItem(group.first())
                ?: group.first().locationLabel?.let { PlaceComponents.fromGeocoderLabel(it) }
            val title = sampleComponents?.hierarchicalDisplayTitle()?.takeIf { it.isNotBlank() }
                ?: group.first().locationLabel.orEmpty()
            val latest = group.maxOfOrNull { it.sortTimestamp } ?: 0L
            val photoCount = group.count { it.kind == com.onegallery.sync.data.local.MediaKind.IMAGE }
            val videoCount = group.count { it.kind == com.onegallery.sync.data.local.MediaKind.VIDEO }
            MediaAlbum(
                bucketId = "place:$encodedKey",
                title = title,
                itemCount = group.size,
                photoCount = photoCount,
                videoCount = videoCount,
                coverModel = albumCoverLocalIndex.coverModelForKey(
                    cacheKey = "place:$encodedKey",
                    items = group,
                    mediaCacheRepository = mediaCacheRepository,
                ),
                sortTimestamp = latest,
            )
        }
}

private fun sortPlaceAlbums(albums: List<MediaAlbum>, sort: AlbumListSort): List<MediaAlbum> {
    fun hierarchyKey(album: MediaAlbum): List<String> {
        val raw = album.bucketId.removePrefix("place:")
        val match = PlaceMatchKey.decode(raw)
        if (match != null && match.level != PlaceLevel.LEGACY_LABEL) {
            return when (match.level) {
                PlaceLevel.COUNTRY -> listOf(match.parts.getOrElse(0) { "" }, "", "", "")
                PlaceLevel.ADMIN_AREA -> listOf(
                    match.parts.getOrElse(0) { "" },
                    match.parts.getOrElse(1) { "" },
                    "",
                    "",
                )
                PlaceLevel.LOCALITY -> listOf(
                    match.parts.getOrElse(0) { "" },
                    match.parts.getOrElse(1) { "" },
                    match.parts.getOrElse(2) { "" },
                    "",
                )
                PlaceLevel.SUB_LOCALITY -> listOf(
                    match.parts.getOrElse(0) { "" },
                    match.parts.getOrElse(1) { "" },
                    match.parts.getOrElse(2) { "" },
                    match.parts.getOrElse(3) { "" },
                )
                PlaceLevel.LEGACY_LABEL -> listOf(match.parts.firstOrNull().orEmpty().lowercase())
            }.map { it.lowercase() }
        }
        return listOf(album.title.lowercase())
    }
    val byHierarchy = compareBy<MediaAlbum>(
        { hierarchyKey(it).getOrElse(0) { "" } },
        { hierarchyKey(it).getOrElse(1) { "" } },
        { hierarchyKey(it).getOrElse(2) { "" } },
        { hierarchyKey(it).getOrElse(3) { "" } },
    )
    return when (sort) {
        AlbumListSort.NAME_A_Z -> albums.sortedWith(byHierarchy)
        AlbumListSort.NAME_Z_A -> albums.sortedWith(byHierarchy.reversed())
        AlbumListSort.DATE_NEWEST -> albums.sortedByDescending { it.sortTimestamp }
    }
}

class GalleryViewModelFactory(
    private val localMediaRepository: LocalMediaRepository,
    private val syncCoordinator: SyncCoordinator,
    private val galleryPreferences: GalleryPreferences,
    private val mediaCacheRepository: MediaCacheRepository,
    private val userMessages: AppUserMessages,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GalleryViewModel::class.java)) {
            return GalleryViewModel(
                localMediaRepository,
                syncCoordinator,
                galleryPreferences,
                mediaCacheRepository,
                userMessages,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
