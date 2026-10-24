package com.onegallery.sync.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.onegallery.sync.data.local.LocalMediaRepository
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.data.local.MediaAlbum
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.PlaceComponents
import com.onegallery.sync.data.local.collapseItemsForPlaceAlbums
import com.onegallery.sync.data.local.groupDisplayItemsForPlaceAlbums
import com.onegallery.sync.data.local.matchesPlaceAlbumBucket
import com.onegallery.sync.data.local.placeAlbumTitleForGroup
import com.onegallery.sync.data.local.preferredPlaceBrowseEncodedKey
import com.onegallery.sync.data.local.PlaceLevel
import com.onegallery.sync.data.local.PlaceMatchKey
import com.onegallery.sync.data.local.matchesPlace
import com.onegallery.sync.data.local.preferredBrowseKey
import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.data.local.db.UserAlbumEntity
import android.content.Context
import com.onegallery.sync.data.local.MediaStorageAccess
import com.onegallery.sync.sync.PendingDeleteTarget
import com.onegallery.sync.OneGalleryApplication
import com.onegallery.sync.sync.GalleryThumbnailCache
import com.onegallery.sync.sync.SyncCoordinator
import com.onegallery.sync.sync.SyncPaths
import com.onegallery.sync.worker.BackgroundSyncScheduler
import com.onegallery.sync.ui.AppUserMessages
import com.onegallery.sync.util.GalleryPerfLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.update
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/** Foco inicial ao abrir o mapa de locais a partir dos detalhes de uma foto. */
data class PlacesMapFocus(
    val latitude: Double,
    val longitude: Double,
)

data class GalleryUiState(
    val isLoading: Boolean = true,
    /** Toda a biblioteca visível (aba Fotos). */
    val libraryItems: List<GalleryDisplayItem> = emptyList(),
    /** Subconjunto após filtro de álbum/local (rota albums/browse). */
    val items: List<GalleryDisplayItem> = emptyList(),
    val albums: List<MediaAlbum> = emptyList(),
    val placeAlbums: List<MediaAlbum> = emptyList(),
    val userAlbums: List<UserAlbumEntity> = emptyList(),
    val albumFilter: String? = null,
    val locationFilter: String? = null,
    val placeBrowseMatchKey: String? = null,
    val sortOrder: GallerySortOrder = GallerySortOrder.DATE_NEWEST,
    val albumListSort: AlbumListSort = AlbumListSort.NAME_A_Z,
    val selectionMode: Boolean = false,
    val selectedPaths: Set<String> = emptySet(),
    val albumSelectionMode: Boolean = false,
    val selectedAlbumTitles: Set<String> = emptySet(),
    val error: String? = null,
    /** Room/bootstrap já emitiu o índice (mesmo vazio) — distingue “carregando” de “sem fotos”. */
    val indexHydrated: Boolean = false,
    /** Itens com coordenadas GPS (mapa de locais). */
    val geoItems: List<GalleryDisplayItem> = emptyList(),
    /** Revisão por álbum (`bucketId` → nanoTime) após miniatura local gravada. */
    val albumCoverRevisions: Map<String, Long> = emptyMap(),
    /** Quantos itens da grade (fotos/vídeos) montar na UI; [libraryItems] permanece completo para o pager. */
    val gridWindowLimit: Int = GalleryGridPagination.initialLimit,
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
    private val galleryThumbnailCache: GalleryThumbnailCache,
    private val galleryPreferences: GalleryPreferences,
    private val mediaCacheRepository: MediaCacheRepository,
    private val userMessages: AppUserMessages,
    private val appContext: Context,
) : ViewModel() {

    private data class PendingLocalMediaRemoval(
        val paths: List<String>,
        val onSingleDone: (() -> Unit)? = null,
    )

    private var pendingDeleteAfterAllFilesAccess: PendingLocalMediaRemoval? = null
    private var pendingReleaseAfterAllFilesAccess: PendingLocalMediaRemoval? = null

    private val browseFilter = MutableStateFlow<GalleryBrowseFilter>(GalleryBrowseFilter.All)
    private val loading = MutableStateFlow(false)
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
    private var initialLibrarySnapshotApplied = false
    @Volatile
    private var latestLibraryItems: List<GalleryDisplayItem> = emptyList()

    @Volatile
    private var latestCatalogItems: List<GalleryDisplayItem> = emptyList()

    @Volatile
    private var pendingPlacesMapFocus: PlacesMapFocus? = null

    private var gridThumbnailPrefetchJob: Job? = null
    private val gridThumbnailPrefetchQueued = ConcurrentHashMap.newKeySet<String>()

    private val catalogSession = GalleryCatalogSession()
    private val displayBuildCache = GalleryDisplayBuildCache(
        mediaCacheRepository,
        localMediaRepository,
    )
    private val localOverlayCache = GalleryLocalOverlayCache()

    /** Só catálogo completo já montado neste processo (RAM); sem disco warm. */
    private val galleryBootstrap = MutableStateFlow(restoreProcessCatalogSnapshot())
    private val indexHydrated = MutableStateFlow(galleryBootstrap.value != null)

    /** Oculta itens na grade assim que o usuário exclui (antes do índice Room/debounce). */
    private val optimisticRemovedPaths = MutableStateFlow<Set<String>>(emptySet())

    private data class DeferredAlbumLists(
        val albums: List<MediaAlbum> = emptyList(),
        val placeAlbums: List<MediaAlbum> = emptyList(),
        val catalogFingerprint: GalleryCatalogFingerprint? = null,
        /** Linhas no Room quando a lista foi montada (detecta índice incompleto vs spill). */
        val metadataRowCount: Int = 0,
        val albumSort: AlbumListSort = AlbumListSort.NAME_A_Z,
    )

    private val deferredAlbumLists = MutableStateFlow(restoreDeferredAlbumListsFromSession())
    private var albumListsBuildJob: Job? = null

    private val gridWindowLimit = MutableStateFlow(GalleryGridPagination.initialLimit)

    private val _galleryScrollToTopRequests = MutableStateFlow(0)
    val galleryScrollToTopRequests: StateFlow<Int> = _galleryScrollToTopRequests

    private val _albumsScrollToTopRequests = MutableStateFlow(0)
    val albumsScrollToTopRequests: StateFlow<Int> = _albumsScrollToTopRequests

    private fun restoreDeferredAlbumListsFromSession(): DeferredAlbumLists {
        val app = appContext.applicationContext as OneGalleryApplication
        val albumEntry = app.galleryProcessAlbumListsCache.peek() ?: return DeferredAlbumLists()
        if (!albumEntry.isRestorable()) return DeferredAlbumLists()
        val catalogEntry = app.galleryProcessCatalogCache.peek()
        val bootstrap = galleryBootstrap.value
        val fingerprint = bootstrap?.fingerprint
            ?: catalogEntry?.fingerprint
            ?: albumEntry.fingerprint
        if (!albumEntry.fingerprint.matchesIndex(fingerprint)) {
            return DeferredAlbumLists()
        }
        val catalogItems = bootstrap?.catalogItems ?: catalogEntry?.catalogItems.orEmpty()
        val albumSort = galleryPreferences.albumListSort
        val albums = sortMediaAlbums(albumEntry.albums, albumSort)
        val placeAlbums = sortPlaceAlbums(albumEntry.placeAlbums, albumSort)
        catalogSession.restoreAlbumCaches(
            albums = albums,
            placeAlbums = placeAlbums,
            catalogItems = catalogItems,
            userAlbumCount = bootstrap?.userAlbums?.size ?: catalogEntry?.userAlbums?.size ?: 0,
            albumSort = albumSort,
            deferCoverModels = catalogItems.size > 3_000,
            coverRevisionStamp = albumEntry.coverRevisionStamp,
        )
        return DeferredAlbumLists(
            albums = albums,
            placeAlbums = placeAlbums,
            catalogFingerprint = fingerprint,
            metadataRowCount = fingerprint.rowCount,
            albumSort = albumSort,
        )
    }

    private fun persistProcessAlbumLists(
        albums: List<MediaAlbum>,
        placeAlbums: List<MediaAlbum>,
        fingerprint: GalleryCatalogFingerprint,
        localRevision: Long,
        albumSort: AlbumListSort,
        coverRevisionStamp: Long,
    ) {
        if (albums.isEmpty() && placeAlbums.isEmpty()) return
        val app = appContext.applicationContext as OneGalleryApplication
        app.galleryProcessAlbumListsCache.save(
            GalleryProcessAlbumListsCache.Entry(
                albums = albums,
                placeAlbums = placeAlbums,
                fingerprint = fingerprint,
                localRevision = localRevision,
                albumListSort = albumSort,
                coverRevisionStamp = coverRevisionStamp,
            ),
        )
        app.scheduleSessionSpillPersistDebounced()
    }

    init {
        if (galleryBootstrap.value == null) {
            loading.value = true
        }
        viewModelScope.launch {
            syncCoordinator.galleryMetadata.collect { metadata ->
                indexHydrated.value = true
                loading.value = false
                if (optimisticRemovedPaths.value.isEmpty()) return@collect
                val indexed = metadata.asSequence().map { it.canonicalPath }.toHashSet()
                optimisticRemovedPaths.update { pending ->
                    pending.filter { path -> path in indexed }.toSet()
                }
            }
        }
        viewModelScope.launch {
            combine(
                syncCoordinator.galleryMetadata,
                syncCoordinator.userAlbums,
                albumListSort,
            ) { metadata, userAlbums, sort ->
                Triple(metadata, userAlbums, sort)
            }
                .distinctUntilChanged { previous, next ->
                    GalleryCatalogFingerprint.from(previous.first)
                        .matchesIndex(GalleryCatalogFingerprint.from(next.first)) &&
                        previous.second == next.second &&
                        previous.third == next.third
                }
                .collect { (metadata, userAlbums, sort) ->
                    scheduleAlbumListsFromMetadata(metadata, userAlbums, sort)
                }
        }
        galleryBootstrap.value?.takeIf { it.catalogItems.isNotEmpty() }?.let { snap ->
            val fingerprint = snap.fingerprint
                ?: GalleryCatalogFingerprint.fromDisplayPaths(snap.catalogItems.map { it.canonicalPath })
            if (deferredAlbumLists.value.albums.isEmpty()) {
                scheduleAlbumListsFromLocalCatalog(
                    catalogItems = snap.catalogItems,
                    visibleItems = snap.visibleItems,
                    fingerprint = fingerprint,
                    userAlbums = snap.userAlbums,
                    albumSort = galleryPreferences.albumListSort,
                    localRevision = catalogSession.lastBundle?.localRevision
                        ?: localMediaRepository.localGalleryRevision.value,
                )
            }
        }
    }

    /** RAM do processo ou spill da última sessão — exibe a grade antes do rebuild completo. */
    private fun restoreProcessCatalogSnapshot(): LibrarySnapshot? {
        val app = appContext.applicationContext as OneGalleryApplication
        val entry = app.galleryProcessCatalogCache.peek() ?: return null
        if (!entry.isRestorable()) return null
        val bundle = entry.toSnapshotBundle()
        catalogSession.publish(bundle, activeSort = galleryPreferences.sortOrder)
        return bundle.toLibrarySnapshot()
    }

    private fun persistProcessCatalog(bundle: GalleryCatalogSession.SnapshotBundle) {
        val app = appContext.applicationContext as OneGalleryApplication
        app.galleryProcessCatalogCache.save(bundle)
        app.scheduleSessionSpillPersistDebounced()
    }

    override fun onCleared() {
        (appContext.applicationContext as? OneGalleryApplication)?.persistSessionCatalogForBackground()
        super.onCleared()
    }

    fun onReturningToForeground() {
        loading.value = false
        syncCoordinator.kickThumbnailPrefetchIfIdle()
    }

    fun loadMoreGridItems() {
        if (GalleryGridScrollGate.isUserScrollingGrid) return
        gridWindowLimit.update { current ->
            current + GalleryGridPagination.pageSize
        }
    }

    private data class LibrarySnapshot(
        /** Índice completo (contagens, capas, locais). */
        val catalogItems: List<GalleryDisplayItem>,
        /** Grade Fotos / browse: local, nuvem indexada ou miniatura em cache. */
        val visibleItems: List<GalleryDisplayItem>,
        val userAlbums: List<UserAlbumEntity>,
        val fingerprint: GalleryCatalogFingerprint? = null,
    )

    private data class LibraryBuildInputs(
        val metadata: List<SyncMetadataEntity>,
        val userAlbums: List<UserAlbumEntity>,
        val localRevision: Long,
    ) {
        val fingerprint: GalleryCatalogFingerprint
            get() = GalleryCatalogFingerprint.from(metadata)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val librarySnapshotCore = combine(
        syncCoordinator.galleryMetadata,
        syncCoordinator.userAlbums,
        localMediaRepository.localGalleryRevision,
    ) { metadata, userAlbums, localRevision ->
        LibraryBuildInputs(metadata, userAlbums, localRevision)
    }.conflate()
        .distinctUntilChanged { previous, next ->
            previous.fingerprint.matchesIndex(next.fingerprint) &&
                previous.userAlbums == next.userAlbums
        }
        .flatMapLatest { inputs ->
            flow {
                val metadata = inputs.metadata
                val userAlbums = inputs.userAlbums
                val localRevision = inputs.localRevision
                val app = appContext.applicationContext as OneGalleryApplication
                val fingerprint = GalleryLibrarySession.fingerprintOf(metadata)
                if (metadata.isEmpty()) {
                    val resolved = GalleryLibrarySession.resolveFromSessionCacheWhenIndexEmpty(
                        userAlbums = userAlbums,
                        localRevision = localRevision,
                        processCache = app.galleryProcessCatalogCache,
                    )
                    if (resolved != null) {
                        catalogSession.publish(resolved.bundle, activeSort = sortOrder.value)
                        val snapshot = resolved.bundle.toLibrarySnapshot()
                        emit(snapshot)
                        galleryBootstrap.value = snapshot
                        return@flow
                    }
                    val localBootstrap = GalleryLocalBootstrap.buildSnapshotBundle(
                        localMediaRepository = localMediaRepository,
                        mediaCacheRepository = mediaCacheRepository,
                        userAlbums = userAlbums,
                        localRevision = localRevision,
                        onlyItemsWithThumbnail = galleryPreferences.browseOnlyItemsWithThumbnail,
                    )
                    if (localBootstrap != null) {
                        catalogSession.publish(localBootstrap, activeSort = sortOrder.value)
                        persistProcessCatalog(localBootstrap)
                        val snapshot = localBootstrap.toLibrarySnapshot()
                        emit(snapshot)
                        galleryBootstrap.value = snapshot
                        app.syncCoordinator.warmLocalIndexIfEmpty()
                        return@flow
                    }
                    emit(
                        LibrarySnapshot(
                            catalogItems = emptyList(),
                            visibleItems = emptyList(),
                            userAlbums = userAlbums,
                        ),
                    )
                    app.syncCoordinator.warmLocalIndexIfEmpty()
                    return@flow
                }
                GalleryLibrarySession.resolveFromSessionCache(
                    metadata = metadata,
                    userAlbums = userAlbums,
                    localRevision = localRevision,
                    processCache = app.galleryProcessCatalogCache,
                )?.let { resolved ->
                    catalogSession.publish(resolved.bundle, activeSort = sortOrder.value)
                    persistProcessCatalog(resolved.bundle)
                    val snapshot = resolved.bundle.toLibrarySnapshot()
                    emit(snapshot)
                    galleryBootstrap.value = snapshot
                    if (GalleryLibrarySession.shouldSkipFullRebuild(metadata, resolved)) {
                        return@flow
                    }
                }
                GalleryLibrarySession.resolveFromInMemorySession(
                    metadata = metadata,
                    userAlbums = userAlbums,
                    localRevision = localRevision,
                    catalogSession = catalogSession,
                )?.let { resolved ->
                    catalogSession.publish(resolved.bundle, activeSort = sortOrder.value)
                    val snapshot = resolved.bundle.toLibrarySnapshot()
                    emit(snapshot)
                    galleryBootstrap.value = snapshot
                    if (GalleryLibrarySession.shouldSkipFullRebuild(metadata, resolved)) {
                        return@flow
                    }
                }

                val snapshot = runCatching {
                    buildLibrarySnapshot(metadata, userAlbums, localRevision, fingerprint)
                }.getOrElse { error ->
                    if (error is OutOfMemoryError) {
                        errorState.value =
                            "Biblioteca muito grande para exibir de uma vez. Sincronize em partes."
                        LibrarySnapshot(
                            catalogItems = emptyList(),
                            visibleItems = emptyList(),
                            userAlbums = userAlbums,
                        )
                    } else {
                        throw error
                    }
                }
                emit(snapshot)
            }.flowOn(Dispatchers.IO)
        }

    private val librarySnapshotLive = combine(
        librarySnapshotCore,
        syncCoordinator.galleryDisplayRefreshEpoch.onStart { emit(0L) },
        syncCoordinator.state.map { it.isRunning }.distinctUntilChanged(),
    ) { snapshot, _, syncRunning ->
        if (GalleryGridScrollGate.shouldDeferCatalogThumbnailRefresh()) {
            snapshot
        } else {
        val onlyThumb = galleryPreferences.browseOnlyItemsWithThumbnail
        val refreshed = when {
            snapshot.catalogItems.size > 1_500 && !onlyThumb -> snapshot
            !syncRunning && !onlyThumb -> snapshot
            else ->
                catalogSession.refreshThumbnailFlags(
                    mediaCacheRepository,
                    onlyThumb,
                )?.toLibrarySnapshot() ?: snapshot
        }
        if (!onlyThumb) {
            refreshed
        } else {
            val visible = filterVisibleForGalleryBrowse(
                refreshed.catalogItems,
                mediaCacheRepository,
                true,
            )
            if (visible === refreshed.visibleItems) refreshed
            else refreshed.copy(visibleItems = visible)
        }
        }
    }.distinctUntilChanged { previous, next ->
        previous.catalogItems === next.catalogItems &&
            previous.visibleItems === next.visibleItems &&
            previous.fingerprint == next.fingerprint &&
            previous.userAlbums == next.userAlbums
    }.flowOn(Dispatchers.Default)

    private val effectiveLibrarySnapshot = combine(
        galleryBootstrap,
        librarySnapshotLive,
        optimisticRemovedPaths,
    ) { bootstrap, live, removed ->
        val bootstrapFiltered = bootstrap?.let { filterRemovedFromLibrarySnapshot(it, removed) }
        val liveFiltered = filterRemovedFromLibrarySnapshot(live, removed)
        val bootstrapVisible = bootstrapFiltered?.visibleItems?.size ?: 0
        val liveVisible = when {
            liveFiltered.visibleItems.isNotEmpty() -> liveFiltered.visibleItems.size
            liveFiltered.catalogItems.isNotEmpty() -> liveFiltered.catalogItems.size
            else -> 0
        }
        when {
            removed.isNotEmpty() -> {
                when {
                    liveFiltered.visibleItems.isNotEmpty() || liveFiltered.catalogItems.isNotEmpty() ->
                        liveFiltered
                    bootstrapFiltered != null -> bootstrapFiltered
                    else -> liveFiltered
                }
            }
            liveVisible > bootstrapVisible -> liveFiltered
            bootstrapFiltered != null && bootstrapVisible > 0 && liveVisible <= bootstrapVisible ->
                bootstrapFiltered
            liveVisible > 0 -> liveFiltered
            else -> bootstrapFiltered ?: liveFiltered
        }
    }

    private fun filterRemovedFromLibrarySnapshot(
        snapshot: LibrarySnapshot,
        removed: Set<String>,
    ): LibrarySnapshot {
        if (removed.isEmpty()) return snapshot
        val catalogItems = snapshot.catalogItems.filter { it.canonicalPath !in removed }
        val visibleItems = snapshot.visibleItems.filter { it.canonicalPath !in removed }
        if (catalogItems.size == snapshot.catalogItems.size &&
            visibleItems.size == snapshot.visibleItems.size
        ) {
            return snapshot
        }
        val fingerprint = GalleryCatalogFingerprint.fromDisplayPaths(
            catalogItems.map { it.canonicalPath },
        )
        return snapshot.copy(
            catalogItems = catalogItems,
            visibleItems = visibleItems,
            fingerprint = fingerprint,
        )
    }

    init {
        viewModelScope.launch {
            combine(
                effectiveLibrarySnapshot.map { it.fingerprint },
                browseFilter,
                sortOrder,
            ) { fingerprint, filter, order ->
                Triple(fingerprint, filter, order)
            }
                .distinctUntilChanged()
                .collect {
                    gridWindowLimit.value = GalleryGridPagination.initialLimit
                }
        }
        viewModelScope.launch {
            combine(
                syncCoordinator.galleryDisplayRefreshEpoch.onStart { emit(0L) },
                effectiveLibrarySnapshot,
                albumListSort,
            ) { epoch, snapshot, sort ->
                Triple(epoch, snapshot, sort)
            }
                .distinctUntilChanged { previous, next ->
                    previous.first == next.first &&
                        previous.third == next.third &&
                        previous.second.visibleItems.size == next.second.visibleItems.size
                }
                .collect { (_, snapshot, sort) ->
                    if (!galleryPreferences.browseOnlyItemsWithThumbnail) return@collect
                    if (snapshot.visibleItems.isEmpty()) return@collect
                    val fingerprint = snapshot.fingerprint ?: return@collect
                    scheduleAlbumListsFromLocalCatalog(
                        catalogItems = snapshot.catalogItems,
                        visibleItems = snapshot.visibleItems,
                        fingerprint = fingerprint,
                        userAlbums = snapshot.userAlbums,
                        albumSort = sort,
                        localRevision = catalogSession.lastBundle?.localRevision
                            ?: localMediaRepository.localGalleryRevision.value,
                    )
                }
        }
        viewModelScope.launch {
            combine(
                effectiveLibrarySnapshot,
                albumListSort,
                syncCoordinator.galleryMetadata,
            ) { snapshot, sort, metadata ->
                Triple(snapshot, sort, metadata)
            }
                .distinctUntilChanged { previous, next ->
                    val prevFp = previous.first.fingerprint
                    val nextFp = next.first.fingerprint
                    prevFp == nextFp &&
                        previous.first.catalogItems.size == next.first.catalogItems.size &&
                        previous.second == next.second &&
                        previous.third.size == next.third.size
                }
                .collect { (snapshot, sort, metadata) ->
                    if (metadata.isNotEmpty()) return@collect
                    val catalogItems = snapshot.catalogItems
                    if (catalogItems.isEmpty()) return@collect
                    val fingerprint = snapshot.fingerprint ?: return@collect
                    val pending = deferredAlbumLists.value
                    if (pending.albums.isNotEmpty() &&
                        pending.catalogFingerprint?.matchesIndex(fingerprint) == true
                    ) {
                        return@collect
                    }
                    scheduleAlbumListsFromLocalCatalog(
                        catalogItems = catalogItems,
                        visibleItems = snapshot.visibleItems,
                        fingerprint = fingerprint,
                        userAlbums = snapshot.userAlbums,
                        albumSort = sort,
                        localRevision = catalogSession.lastBundle?.localRevision
                            ?: localMediaRepository.localGalleryRevision.value,
                    )
                }
        }
    }

    private fun GalleryCatalogSession.SnapshotBundle.toLibrarySnapshot(): LibrarySnapshot =
        LibrarySnapshot(
            catalogItems = catalogItems,
            visibleItems = visibleItems,
            userAlbums = userAlbums,
            fingerprint = fingerprint,
        )

    private suspend fun buildLibrarySnapshot(
        metadata: List<SyncMetadataEntity>,
        userAlbums: List<UserAlbumEntity>,
        localRevision: Long,
        fingerprint: GalleryCatalogFingerprint,
    ): LibrarySnapshot {
        val indexed = dedupeGalleryDisplayItems(displayBuildCache.mapIndexed(metadata))
        val overlay = if (metadata.size >= 1_500) {
            emptyList()
        } else {
            localOverlayCache.overlayItems(
                indexed = indexed,
                localRevision = localRevision,
                localMediaRepository = localMediaRepository,
                mediaCacheRepository = mediaCacheRepository,
            )
        }
        val catalog = if (overlay.isEmpty()) {
            indexed
        } else {
            dedupeGalleryDisplayItems(indexed + overlay)
        }
        val visible = filterVisibleForGalleryBrowse(
            catalog,
            mediaCacheRepository,
            galleryPreferences.browseOnlyItemsWithThumbnail,
        )
        val bundle = GalleryCatalogSession.SnapshotBundle(
            catalogItems = catalog,
            visibleItems = visible,
            userAlbums = userAlbums,
            fingerprint = fingerprint,
            localRevision = localRevision,
        )
        catalogSession.publish(bundle, activeSort = sortOrder.value)
        persistProcessCatalog(bundle)
        return LibrarySnapshot(
            catalogItems = catalog,
            visibleItems = visible,
            userAlbums = userAlbums,
            fingerprint = fingerprint,
        )
    }

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

    private fun applySessionAlbumListsFromEntry(
        entry: GalleryProcessAlbumListsCache.Entry,
        fingerprint: GalleryCatalogFingerprint,
        metadataRowCount: Int,
        userAlbums: List<UserAlbumEntity>,
        albumSort: AlbumListSort,
    ) {
        val app = appContext.applicationContext as OneGalleryApplication
        val albums = sortMediaAlbums(entry.albums, albumSort)
        val placeAlbums = sortPlaceAlbums(entry.placeAlbums, albumSort)
        val catalogItems = latestCatalogItems.ifEmpty {
            galleryBootstrap.value?.catalogItems
                ?: app.galleryProcessCatalogCache.peek()?.catalogItems.orEmpty()
        }
        deferredAlbumLists.value = DeferredAlbumLists(
            albums = albums,
            placeAlbums = placeAlbums,
            catalogFingerprint = fingerprint,
            metadataRowCount = metadataRowCount,
            albumSort = albumSort,
        )
        catalogSession.restoreAlbumCaches(
            albums = albums,
            placeAlbums = placeAlbums,
            catalogItems = catalogItems,
            userAlbumCount = userAlbums.size,
            albumSort = albumSort,
            deferCoverModels = metadataRowCount > 3_000,
            coverRevisionStamp = entry.coverRevisionStamp,
        )
    }

    /** Mesma regra da grade Fotos: RAM/spill por fingerprint, rebuild só se o índice mudou. */
    private fun applySessionAlbumListsIfCurrent(
        fingerprint: GalleryCatalogFingerprint,
        metadataRowCount: Int,
        userAlbums: List<UserAlbumEntity>,
        albumSort: AlbumListSort,
    ): Boolean {
        val entry = (appContext.applicationContext as OneGalleryApplication)
            .galleryProcessAlbumListsCache.peek() ?: return false
        if (!entry.fingerprint.matchesIndex(fingerprint) || !entry.isRestorable()) return false
        applySessionAlbumListsFromEntry(entry, fingerprint, metadataRowCount, userAlbums, albumSort)
        return true
    }

    /** Monta pastas/locais a partir do catálogo local (MediaStore/bootstrap) — sem Room, mesmo caminho rápido da grade Fotos. */
    private fun scheduleAlbumListsFromLocalCatalog(
        catalogItems: List<GalleryDisplayItem>,
        visibleItems: List<GalleryDisplayItem>,
        fingerprint: GalleryCatalogFingerprint,
        userAlbums: List<UserAlbumEntity>,
        albumSort: AlbumListSort,
        localRevision: Long,
    ) {
        if (catalogItems.isEmpty()) return
        val onlyThumb = galleryPreferences.browseOnlyItemsWithThumbnail
        val albumItems = itemsForAlbumListGrouping(
            catalogItems,
            visibleItems,
            onlyItemsWithThumbnail = onlyThumb,
        )
        val placeItems = itemsForPlaceAlbumGrouping(
            catalogItems,
            visibleItems,
            onlyItemsWithThumbnail = onlyThumb,
        )
        val pending = deferredAlbumLists.value
        if (!onlyThumb &&
            pending.albums.isNotEmpty() &&
            pending.catalogFingerprint?.matchesIndex(fingerprint) == true &&
            pending.albumSort == albumSort
        ) {
            return
        }
        albumListsBuildJob?.cancel()
        albumListsBuildJob = viewModelScope.launch(Dispatchers.Default) {
            val deferCovers = catalogItems.size > 3_000
            val app = appContext.applicationContext as OneGalleryApplication
            val skipHeavyCoverPass = deferCovers &&
                app.appForegroundState.isInForeground() &&
                !syncCoordinator.state.value.isRunning
            fun publish(
                albums: List<MediaAlbum>,
                placeAlbums: List<MediaAlbum>,
            ) {
                if (albums.isEmpty() && placeAlbums.isEmpty()) return
                applySessionAlbumListsFromEntry(
                    GalleryProcessAlbumListsCache.Entry(
                        albums = albums,
                        placeAlbums = placeAlbums,
                        fingerprint = fingerprint,
                        localRevision = localRevision,
                        albumListSort = albumSort,
                        coverRevisionStamp = 0L,
                    ),
                    fingerprint,
                    fingerprint.rowCount,
                    userAlbums,
                    albumSort,
                )
                persistProcessAlbumLists(
                    albums = albums,
                    placeAlbums = placeAlbums,
                    fingerprint = fingerprint,
                    localRevision = localRevision,
                    albumSort = albumSort,
                    coverRevisionStamp = 0L,
                )
            }
            val skeletonAlbums = buildAlbums(
                albumItems,
                userAlbums,
                albumSort,
                albumCoverLocalIndex,
                mediaCacheRepository,
                resolveCoverModels = false,
            )
            val skeletonPlaces = sortPlaceAlbums(
                buildPlaceAlbums(
                    placeItems,
                    albumCoverLocalIndex,
                    mediaCacheRepository,
                    resolveCoverModels = false,
                ),
                albumSort,
            )
            if (deferCovers) {
                publish(skeletonAlbums, skeletonPlaces)
            }
            if (skipHeavyCoverPass) return@launch
            val resolveCovers = !deferCovers
            publish(
                buildAlbums(
                    albumItems,
                    userAlbums,
                    albumSort,
                    albumCoverLocalIndex,
                    mediaCacheRepository,
                    resolveCoverModels = resolveCovers,
                ),
                sortPlaceAlbums(
                    buildPlaceAlbums(
                        placeItems,
                        albumCoverLocalIndex,
                        mediaCacheRepository,
                        resolveCoverModels = resolveCovers,
                    ),
                    albumSort,
                ),
            )
        }
    }

    /** Álbuns vêm do índice Room em paralelo ao catálogo da grade — spill/RAM só aceleram reabertura. */
    private fun scheduleAlbumListsFromMetadata(
        metadata: List<SyncMetadataEntity>,
        userAlbums: List<UserAlbumEntity>,
        albumSort: AlbumListSort,
    ) {
        val app = appContext.applicationContext as OneGalleryApplication
        if (metadata.isEmpty()) {
            val spillFingerprint = GalleryAlbumLibrarySession.resolveWhenMetadataEmpty(
                app.galleryProcessAlbumListsCache,
                app.galleryProcessCatalogCache,
            )
            if (spillFingerprint != null &&
                applySessionAlbumListsIfCurrent(
                    fingerprint = spillFingerprint,
                    metadataRowCount = spillFingerprint.rowCount,
                    userAlbums = userAlbums,
                    albumSort = albumSort,
                )
            ) {
                return
            }
            val bootstrapSnap = galleryBootstrap.value
            if (bootstrapSnap != null && bootstrapSnap.catalogItems.isNotEmpty()) {
                val fingerprint = bootstrapSnap.fingerprint
                    ?: GalleryCatalogFingerprint.fromDisplayPaths(
                        bootstrapSnap.catalogItems.map { it.canonicalPath },
                    )
                scheduleAlbumListsFromLocalCatalog(
                    catalogItems = bootstrapSnap.catalogItems,
                    visibleItems = bootstrapSnap.visibleItems,
                    fingerprint = fingerprint,
                    userAlbums = userAlbums,
                    albumSort = albumSort,
                    localRevision = catalogSession.lastBundle?.localRevision
                        ?: localMediaRepository.localGalleryRevision.value,
                )
                app.syncCoordinator.warmLocalIndexIfEmpty()
                return
            }
            val catalogEntry = app.galleryProcessCatalogCache.peek()?.takeIf { it.isRestorable() }
            if (catalogEntry != null && catalogEntry.catalogItems.isNotEmpty()) {
                scheduleAlbumListsFromLocalCatalog(
                    catalogItems = catalogEntry.catalogItems,
                    visibleItems = catalogEntry.visibleItems,
                    fingerprint = catalogEntry.fingerprint,
                    userAlbums = userAlbums,
                    albumSort = albumSort,
                    localRevision = catalogEntry.localRevision,
                )
                app.syncCoordinator.warmLocalIndexIfEmpty()
                return
            }
            albumListsBuildJob?.cancel()
            albumListsBuildJob = viewModelScope.launch(Dispatchers.IO) {
                val localBundle = GalleryLocalBootstrap.buildSnapshotBundle(
                    localMediaRepository = localMediaRepository,
                    mediaCacheRepository = mediaCacheRepository,
                    userAlbums = userAlbums,
                    localRevision = localMediaRepository.localGalleryRevision.value,
                    onlyItemsWithThumbnail = galleryPreferences.browseOnlyItemsWithThumbnail,
                ) ?: return@launch
                scheduleAlbumListsFromLocalCatalog(
                    catalogItems = localBundle.catalogItems,
                    visibleItems = localBundle.visibleItems,
                    fingerprint = localBundle.fingerprint,
                    userAlbums = userAlbums,
                    albumSort = albumSort,
                    localRevision = localBundle.localRevision,
                )
            }
            app.syncCoordinator.warmLocalIndexIfEmpty()
            return
        }
        val fingerprint = GalleryCatalogFingerprint.from(metadata)
        GalleryAlbumLibrarySession.resolveFromProcessCache(
            metadata = metadata,
            userAlbums = userAlbums,
            albumSort = albumSort,
            albumCache = app.galleryProcessAlbumListsCache,
            catalogFingerprint = fingerprint,
        )?.let { entry ->
            applySessionAlbumListsFromEntry(entry, fingerprint, metadata.size, userAlbums, albumSort)
            return
        }
        if (applySessionAlbumListsIfCurrent(fingerprint, metadata.size, userAlbums, albumSort)) {
            return
        }
        val pending = deferredAlbumLists.value
        if (pending.catalogFingerprint?.matchesIndex(fingerprint) == true &&
            pending.metadataRowCount == metadata.size &&
            pending.albumSort == albumSort &&
            pending.albums.isNotEmpty()
        ) {
            return
        }
        albumListsBuildJob?.cancel()
        albumListsBuildJob = viewModelScope.launch(Dispatchers.Default) {
            val deferCovers = metadata.size > 3_000
            val app = appContext.applicationContext as OneGalleryApplication
            val skipHeavyCoverPass = deferCovers &&
                app.appForegroundState.isInForeground() &&
                !syncCoordinator.state.value.isRunning
            val catalogItems = latestCatalogItems
            fun publishLists(
                albums: List<MediaAlbum>,
                placeAlbums: List<MediaAlbum>,
                coverRevisionStamp: Long,
            ) {
                deferredAlbumLists.value = DeferredAlbumLists(
                    albums = albums,
                    placeAlbums = placeAlbums,
                    catalogFingerprint = fingerprint,
                    metadataRowCount = metadata.size,
                    albumSort = albumSort,
                )
                catalogSession.restoreAlbumCaches(
                    albums = albums,
                    placeAlbums = placeAlbums,
                    catalogItems = catalogItems,
                    userAlbumCount = userAlbums.size,
                    albumSort = albumSort,
                    deferCoverModels = deferCovers,
                    coverRevisionStamp = coverRevisionStamp,
                )
                persistProcessAlbumLists(
                    albums = albums,
                    placeAlbums = placeAlbums,
                    fingerprint = fingerprint,
                    localRevision = catalogSession.lastBundle?.localRevision ?: 0L,
                    albumSort = albumSort,
                    coverRevisionStamp = coverRevisionStamp,
                )
            }
            val coverRevisionStamp =
                syncCoordinator.albumCoverRevisions.value.values.fold(0L) { acc, stamp -> acc xor stamp }
            val onlyThumb = galleryPreferences.browseOnlyItemsWithThumbnail
            val skeletonAlbums = GalleryAlbumIndexBuilder.buildFolderAlbums(
                metadata = metadata,
                userAlbums = userAlbums,
                sort = albumSort,
                albumCoverLocalIndex = albumCoverLocalIndex,
                mediaCacheRepository = mediaCacheRepository,
                localMediaRepository = localMediaRepository,
                onlyItemsWithThumbnail = onlyThumb,
                resolveCoverModels = false,
            )
            val skeletonPlaces = GalleryAlbumIndexBuilder.buildPlaceAlbums(
                metadata = metadata,
                sort = albumSort,
                albumCoverLocalIndex = albumCoverLocalIndex,
                mediaCacheRepository = mediaCacheRepository,
                localMediaRepository = localMediaRepository,
                onlyItemsWithThumbnail = onlyThumb,
                resolveCoverModels = false,
            )
            if (deferCovers) {
                publishLists(skeletonAlbums, skeletonPlaces, coverRevisionStamp)
            }
            if (skipHeavyCoverPass) return@launch
            val withCovers = GalleryAlbumIndexBuilder.buildFolderAlbums(
                metadata = metadata,
                userAlbums = userAlbums,
                sort = albumSort,
                albumCoverLocalIndex = albumCoverLocalIndex,
                mediaCacheRepository = mediaCacheRepository,
                localMediaRepository = localMediaRepository,
                onlyItemsWithThumbnail = onlyThumb,
                resolveCoverModels = true,
            )
            val placesWithCovers = GalleryAlbumIndexBuilder.buildPlaceAlbums(
                metadata = metadata,
                sort = albumSort,
                albumCoverLocalIndex = albumCoverLocalIndex,
                mediaCacheRepository = mediaCacheRepository,
                localMediaRepository = localMediaRepository,
                onlyItemsWithThumbnail = onlyThumb,
                resolveCoverModels = true,
            )
            publishLists(withCovers, placesWithCovers, coverRevisionStamp)
        }
    }

    private fun albumListsForUi(
        albumLists: DeferredAlbumLists,
        fingerprint: GalleryCatalogFingerprint,
        catalogItems: List<GalleryDisplayItem>,
        placeCatalogItems: List<GalleryDisplayItem>,
        userAlbums: List<UserAlbumEntity>,
        albumSort: AlbumListSort,
        deferAlbumCoverModels: Boolean,
        coverRevisionStamp: Long,
    ): Pair<List<MediaAlbum>, List<MediaAlbum>> {
        val onlyThumb = galleryPreferences.browseOnlyItemsWithThumbnail
        if (!onlyThumb &&
            albumLists.albums.isNotEmpty() &&
            albumLists.catalogFingerprint?.matchesIndex(fingerprint) == true
        ) {
            return albumLists.albums to albumLists.placeAlbums
        }
        if (catalogItems.size < DEFER_ALBUM_LISTS_MIN_ITEMS || onlyThumb) {
            val folderItems = if (onlyThumb) {
                filterVisibleForGalleryBrowse(catalogItems, mediaCacheRepository, true)
            } else {
                catalogItems
            }
            val placeItems = if (onlyThumb) {
                filterVisibleForGalleryBrowse(placeCatalogItems, mediaCacheRepository, true)
            } else {
                placeCatalogItems
            }
            return catalogSession.albumsFor(
                catalogItems = folderItems,
                userAlbums = userAlbums,
                albumSort = albumSort,
                deferCoverModels = deferAlbumCoverModels,
                albumCoverLocalIndex = albumCoverLocalIndex,
                mediaCacheRepository = mediaCacheRepository,
                coverRevisionStamp = coverRevisionStamp,
            ) to catalogSession.placeAlbumsFor(
                catalogItems = placeItems,
                albumSort = albumSort,
                deferCoverModels = deferAlbumCoverModels,
                albumCoverLocalIndex = albumCoverLocalIndex,
                mediaCacheRepository = mediaCacheRepository,
                coverRevisionStamp = coverRevisionStamp,
            )
        }
        return albumLists.albums to albumLists.placeAlbums
    }

    private data class GalleryStateInputs(
        val snapshot: LibrarySnapshot,
        val controls: UiControls,
        val coverRevisions: Map<String, Long>,
        val hydrated: Boolean,
        val albumLists: DeferredAlbumLists,
    )

    private val galleryStateInputs = combine(
        effectiveLibrarySnapshot,
        uiControls,
        syncCoordinator.albumCoverRevisions,
        indexHydrated,
        deferredAlbumLists,
    ) { snapshot, controls, coverRevisions, hydrated, albumLists ->
        GalleryStateInputs(snapshot, controls, coverRevisions, hydrated, albumLists)
    }

    private val galleryUiStateCore = galleryStateInputs
        .map { inputs -> buildGalleryUiState(inputs, GalleryGridPagination.initialLimit) }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            initialGalleryUiState(),
        )

    val state: StateFlow<GalleryUiState> = combine(
        galleryUiStateCore,
        gridWindowLimit,
    ) { core, limit ->
        if (core.gridWindowLimit == limit) core else core.copy(gridWindowLimit = limit)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        initialGalleryUiState(),
    )

    private fun initialGalleryUiState(): GalleryUiState {
        val snap = galleryBootstrap.value ?: return GalleryUiState(isLoading = true)
        val libraryItems = catalogSession.sortedLibraryItems(
            snap.visibleItems,
            galleryPreferences.sortOrder,
        )
        val restoredAlbums = deferredAlbumLists.value
        return GalleryUiState(
            isLoading = false,
            libraryItems = libraryItems,
            items = libraryItems,
            albums = restoredAlbums.albums,
            placeAlbums = restoredAlbums.placeAlbums,
            userAlbums = snap.userAlbums,
            albumListSort = galleryPreferences.albumListSort,
            indexHydrated = true,
            gridWindowLimit = GalleryGridPagination.initialLimit,
        )
    }

    private fun buildGalleryUiState(
        inputs: GalleryStateInputs,
        gridLimit: Int,
    ): GalleryUiState {
        val snapshot = inputs.snapshot
        val controls = inputs.controls
        val coverRevisions = inputs.coverRevisions
        val hydrated = inputs.hydrated
        val albumLists = inputs.albumLists
        coverRevisions.forEach { (key, stamp) ->
            val previous = lastAlbumCoverRevisions[key]
            if (previous != null && previous != stamp) {
                albumCoverLocalIndex.invalidateKey(key)
            }
        }
        lastAlbumCoverRevisions = coverRevisions
        if (!initialLibrarySnapshotApplied &&
            (hydrated || snapshot.catalogItems.isNotEmpty() || snapshot.visibleItems.isNotEmpty())
        ) {
            initialLibrarySnapshotApplied = true
            loading.value = false
        }
        val catalogItems = snapshot.catalogItems
        val visibleItems = snapshot.visibleItems
        latestCatalogItems = catalogItems
        latestLibraryItems = visibleItems
        val deferAlbumCoverModels = catalogItems.size > 3_000
        val fingerprint = snapshot.fingerprint
            ?: catalogSession.lastBundle?.fingerprint
            ?: GalleryCatalogFingerprint(
                rowCount = catalogItems.size,
                pathChecksum = catalogItems.fold(0L) { acc, item ->
                    acc xor item.canonicalPath.hashCode().toLong()
                },
            )
        val partialCatalog = fingerprint != null &&
            fingerprint.rowCount > catalogItems.size
        val coverKeysToRefresh = if (!partialCatalog && catalogItems.size <= 5_000) {
            detectAlbumCoverSignatureChanges(catalogItems)
        } else {
            emptySet()
        }
        if (coverKeysToRefresh.isNotEmpty()) {
            syncCoordinator.bumpAlbumCoverRevisionsForBucketIds(coverKeysToRefresh)
        }
        val userAlbums = snapshot.userAlbums
        val coverRevisionStamp = coverRevisions.values.fold(0L) { acc, stamp -> acc xor stamp }
        val onlyThumbBrowse = galleryPreferences.browseOnlyItemsWithThumbnail
        val albumListItems = itemsForAlbumListGrouping(
            catalogItems,
            visibleItems,
            onlyItemsWithThumbnail = onlyThumbBrowse,
        )
        val placeListItems = itemsForPlaceAlbumGrouping(
            catalogItems,
            visibleItems,
            onlyItemsWithThumbnail = onlyThumbBrowse,
        )
        val (displayAlbums, displayPlaceAlbums) = albumListsForUi(
            albumLists = albumLists,
            fingerprint = fingerprint,
            catalogItems = albumListItems,
            placeCatalogItems = placeListItems,
            userAlbums = userAlbums,
            albumSort = controls.albumSort,
            deferAlbumCoverModels = deferAlbumCoverModels,
            coverRevisionStamp = coverRevisionStamp,
        )
        val libraryItems = catalogSession.sortedLibraryItems(visibleItems, controls.order)
        val items = catalogSession.browseItemsFor(visibleItems, controls.browseFilter, controls.order)
        val albumFilter = (controls.browseFilter as? GalleryBrowseFilter.AlbumFolder)?.name
        val placeBrowse = controls.browseFilter as? GalleryBrowseFilter.Place
        val locationFilter = placeBrowse?.displayName
        val placeBrowseMatchKey = placeBrowse?.matchKey

        return GalleryUiState(
            isLoading = controls.isLoading,
            libraryItems = libraryItems,
            items = items,
            albums = displayAlbums,
            placeAlbums = displayPlaceAlbums,
            userAlbums = userAlbums,
            albumFilter = albumFilter,
            locationFilter = locationFilter,
            placeBrowseMatchKey = placeBrowseMatchKey,
            sortOrder = controls.order,
            albumListSort = controls.albumSort,
            selectionMode = controls.selecting,
            selectedPaths = controls.selected,
            albumSelectionMode = controls.albumSelecting,
            selectedAlbumTitles = controls.selectedAlbums,
            error = controls.error,
            indexHydrated = hydrated,
            geoItems = catalogSession.geoItems(),
            albumCoverRevisions = coverRevisions,
            gridWindowLimit = gridLimit,
        )
    }

    fun clearBrowseFilters() {
        browseFilter.value = GalleryBrowseFilter.All
        clearSelection()
    }

    /** Aba Fotos já aberta — barra inferior tocada de novo. */
    fun requestGalleryScrollToTop() {
        _galleryScrollToTopRequests.update { it + 1 }
    }

    /** Aba Álbuns já aberta — barra inferior tocada de novo. */
    fun requestAlbumsScrollToTop() {
        _albumsScrollToTopRequests.update { it + 1 }
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
                prefetchBrowseThumbnailsForPlace(placeKey, displayName)
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

    /** Abre o álbum de local da célula ~3 km quando a foto tem GPS. */
    fun setPlaceFilterFromItem(item: GalleryDisplayItem) {
        val geoKey = item.preferredPlaceBrowseEncodedKey()
        if (geoKey != null) {
            setPlaceFilter(placeAlbumTitleForGroup(listOf(item), coverItem = item), geoKey)
            return
        }
        val label = item.locationLabel?.trim().orEmpty()
        if (label.isNotEmpty()) setPlaceFilterFromLocationLabel(label)
    }

    fun setSortOrder(order: GallerySortOrder) {
        sortOrder.value = order
        galleryPreferences.sortOrder = order
    }

    fun setAlbumListSort(sort: AlbumListSort) {
        albumListSort.value = sort
        galleryPreferences.albumListSort = sort
    }

    /** Reaplica filtro “só com miniatura” após mudança em Ajustes. */
    fun refreshBrowseVisibilityFromPreferences() {
        val bundle = catalogSession.lastBundle ?: return
        val visible = filterVisibleForGalleryBrowse(
            bundle.catalogItems,
            mediaCacheRepository,
            galleryPreferences.browseOnlyItemsWithThumbnail,
        )
        catalogSession.publish(bundle.copy(visibleItems = visible))
        syncCoordinator.notifyGalleryDisplayRefresh()
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

    private fun detectAlbumCoverSignatureChanges(catalogItems: List<GalleryDisplayItem>): Set<String> {
        if (catalogItems.isEmpty()) return emptySet()
        val changed = mutableSetOf<String>()
        catalogItems.groupBy { it.albumName }.forEach { (name, items) ->
            val key = "album:$name"
            val latest = items.maxOfOrNull { it.sortTimestamp } ?: 0L
            val previous = folderAlbumLatestSort.put(key, latest)
            if (previous != null && previous != latest) {
                changed.add(key)
            }
        }
        groupDisplayItemsForPlaceAlbums(
            itemsForPlaceAlbumGrouping(
                catalogItems,
                latestLibraryItems,
                onlyItemsWithThumbnail = galleryPreferences.browseOnlyItemsWithThumbnail,
            ),
        ).forEach { (encoded, group) ->
            val key = "place:$encoded"
            val latest = group.maxOfOrNull { it.sortTimestamp } ?: 0L
            val previous = placeAlbumLatestSort.put(key, latest)
            if (previous != null && previous != latest) {
                changed.add(key)
            }
        }
        return changed
    }

    private suspend fun prefetchBrowseThumbnailsForFolder(albumName: String) {
        val items = latestLibraryItems.filter { it.albumName == albumName }
        prefetchBrowseThumbnails(items, coverBucketId = "album:$albumName")
    }

    private suspend fun prefetchBrowseThumbnailsForPlace(encodedMatchKey: String, albumTitle: String? = null) {
        val items = collapseItemsForPlaceAlbums(latestLibraryItems).filter { item ->
            if (encodedMatchKey.isNotBlank()) {
                item.matchesPlaceAlbumBucket(encodedMatchKey)
            } else {
                albumTitle != null && item.locationLabel == albumTitle
            }
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
        prefetchThumbnailPathsParallel(paths.toList())
        if (paths.isNotEmpty()) {
            syncCoordinator.bumpAlbumCoverRevisionsForBucketIds(setOf(coverBucketId))
        }
    }

    /** Miniaturas visíveis / próximas na grade (prioridade sobre prefetch em massa do sync). */
    fun prefetchGridThumbnails(canonicalPaths: List<String>) {
        val paths = canonicalPaths
            .asSequence()
            .filter { it.isNotBlank() }
            .filter { path -> !gridThumbnailCached(path) }
            .filter { path -> gridThumbnailPrefetchQueued.add(path) }
            .take(GRID_THUMB_PREFETCH_BATCH)
            .toList()
        if (paths.isEmpty()) return
        val scrolling = GalleryGridScrollGate.isUserScrollingGrid
        gridThumbnailPrefetchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                prefetchThumbnailPathsParallel(
                    paths,
                    lightweightPrefetch = scrolling,
                    videoAggressive = true,
                )
            } finally {
                paths.forEach { gridThumbnailPrefetchQueued.remove(it) }
            }
        }
    }

    private suspend fun prefetchThumbnailPathsParallel(
        paths: List<String>,
        lightweightPrefetch: Boolean = false,
        videoAggressive: Boolean = false,
    ) {
        if (paths.isEmpty()) return
        var cachedAny = false
        supervisorScope {
            val semaphore = Semaphore(GRID_THUMB_PREFETCH_PARALLEL)
            paths.map { path ->
                async {
                    semaphore.withPermit {
                        galleryThumbnailCache.ensureThumbnailCached(
                            path,
                            lightweightPrefetch = lightweightPrefetch,
                            cloudVideoAggressive = videoAggressive,
                        ).getOrNull()
                        val hit = gridThumbnailCached(path)
                        if (hit) cachedAny = true
                    }
                }
            }.awaitAll()
        }
        GalleryPerfLogger.log(
            "grid-thumb-prefetch count=${paths.size} cached=$cachedAny",
        )
        if (cachedAny && !GalleryGridScrollGate.isUserScrollingGrid) {
            syncCoordinator.notifyGalleryDisplayRefresh()
        }
    }

    private companion object {
        const val GRID_THUMB_PREFETCH_PARALLEL = 14
        const val GRID_THUMB_PREFETCH_BATCH = 96
        const val DEFER_ALBUM_LISTS_MIN_ITEMS = 800
    }

    private fun gridThumbnailCached(canonicalPath: String): Boolean {
        val item = latestCatalogItems.find { it.canonicalPath == canonicalPath }
        if (item == null) {
            return mediaCacheRepository.displayableThumbnailIfCached(canonicalPath) != null
        }
        if (item.kind == MediaKind.VIDEO) {
            return mediaCacheRepository.galleryThumbnailResolved(
                item.canonicalPath,
                item.oneDriveItemId,
                requireVideoAcceptable = true,
            ) != null
        }
        if (item.localUri != null) return true
        return mediaCacheRepository.displayableThumbnailIfCached(
            item.canonicalPath,
            item.oneDriveItemId,
        ) != null
    }

    private fun itemNeedsGeneratedThumbnail(item: GalleryDisplayItem): Boolean {
        if (!GalleryMapper.supportsGeneratedThumbnail(item.displayName)) return false
        val isVideo = item.kind == com.onegallery.sync.data.local.MediaKind.VIDEO
        if (
            mediaCacheRepository.galleryThumbnailResolved(
                item.canonicalPath,
                item.oneDriveItemId,
                requireVideoAcceptable = isVideo,
            ) != null
        ) {
            return false
        }
        if (!isVideo && item.localUri != null) return false
        if (item.oneDriveItemId != null) return true
        if (item.localMediaId != null) return true
        if (item.localUri != null) return true
        return false
    }

    private fun itemsForAlbumCover(album: MediaAlbum): List<GalleryDisplayItem> {
        val all = latestCatalogItems
        if (all.isEmpty()) return emptyList()
        return when {
            album.bucketId.startsWith("place:") -> {
                val encoded = album.bucketId.removePrefix("place:")
                val matchKey = PlaceMatchKey.decode(encoded)
                all.filter { item ->
                    matchKey?.let { item.matchesPlace(it, album.title) }
                        ?: (item.locationLabel == album.title)
                }
            }
            else -> all.filter { it.albumName == album.filterKey }
        }
    }

    fun refresh() {
        val app = appContext.applicationContext as? OneGalleryApplication ?: return
        BackgroundSyncScheduler.enqueueUserRequestedSync(app, app.syncPreferences)
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

    fun canDeleteLocallyWithoutSystemPrompt(): Boolean =
        MediaStorageAccess.hasAllFilesAccess(appContext)

    fun showMessage(message: String) {
        userMessages.show(message)
    }

    fun queueDeleteAfterAllFilesAccess(paths: List<String>, onSingleRemoved: (() -> Unit)? = null) {
        if (paths.isEmpty()) return
        pendingDeleteAfterAllFilesAccess = PendingLocalMediaRemoval(paths, onSingleRemoved)
        userMessages.show(
            "Conceda “Acesso a todos os arquivos” e volte ao app — a exclusão continua sozinha.",
        )
    }

    fun queueReleaseAfterAllFilesAccess(paths: List<String>, onSingleDone: (() -> Unit)? = null) {
        if (paths.isEmpty()) return
        pendingReleaseAfterAllFilesAccess = PendingLocalMediaRemoval(paths, onSingleDone)
        userMessages.show(
            "Conceda “Acesso a todos os arquivos” e volte ao app — liberar espaço continua sozinho.",
        )
    }

    fun resumePendingLocalRemovalIfAccessGranted() {
        if (!MediaStorageAccess.hasAllFilesAccess(appContext)) return
        pendingDeleteAfterAllFilesAccess?.let { pending ->
            pendingDeleteAfterAllFilesAccess = null
            runDeleteImmediately(pending.paths, pending.onSingleDone)
        }
        pendingReleaseAfterAllFilesAccess?.let { pending ->
            pendingReleaseAfterAllFilesAccess = null
            runReleaseImmediately(pending.paths, pending.onSingleDone)
        }
    }

    fun deleteSelected() {
        val paths = selectedPaths.value.toList()
        if (paths.isEmpty()) return
        runDeleteImmediately(paths)
    }

    fun deleteSingle(canonicalPath: String, onRemoved: () -> Unit = {}) {
        runDeleteImmediately(listOf(canonicalPath), onRemoved)
    }

    private fun runDeleteImmediately(paths: List<String>, onSingleRemoved: (() -> Unit)? = null) {
        if (paths.isEmpty()) return
        applyImmediateGalleryRemoval(paths)
        viewModelScope.launch {
            syncCoordinator.deleteItemsImmediately(paths, PendingDeleteTarget.BOTH)
                .onSuccess { count ->
                    if (onSingleRemoved != null) {
                        toast("Item excluído.")
                        onSingleRemoved()
                    } else {
                        toast("$count item(ns) excluído(s).")
                        clearSelection()
                    }
                    syncCoordinator.notifyGalleryDisplayRefresh()
                }
                .onFailure {
                    revertImmediateGalleryRemoval(paths)
                    toast(it.message)
                }
        }
    }

    private fun applyImmediateGalleryRemoval(paths: List<String>) {
        val removed = paths.toSet()
        optimisticRemovedPaths.update { it + removed }
        displayBuildCache.invalidatePaths(removed)
        val bundle = catalogSession.removeCanonicalPaths(removed, sortOrder.value)
        if (bundle != null) {
            val snapshot = bundle.toLibrarySnapshot()
            galleryBootstrap.value = snapshot
            persistProcessCatalog(bundle)
            scheduleAlbumListsFromLocalCatalog(
                catalogItems = snapshot.catalogItems,
                visibleItems = snapshot.visibleItems,
                fingerprint = bundle.fingerprint,
                userAlbums = snapshot.userAlbums,
                albumSort = albumListSort.value,
                localRevision = bundle.localRevision,
            )
        }
        syncCoordinator.notifyGalleryDisplayRefresh()
    }

    private fun revertImmediateGalleryRemoval(paths: List<String>) {
        val removed = paths.toSet()
        optimisticRemovedPaths.update { it - removed }
        syncCoordinator.notifyGalleryDisplayRefresh()
    }

    fun releaseLocalSpaceSelected() {
        val paths = selectedPaths.value.toList()
        if (paths.isEmpty()) return
        runReleaseImmediately(paths)
    }

    fun releaseSingle(canonicalPath: String, onDone: () -> Unit = {}) {
        runReleaseImmediately(listOf(canonicalPath), onDone)
    }

    private fun runReleaseImmediately(paths: List<String>, onSingleDone: (() -> Unit)? = null) {
        viewModelScope.launch {
            syncCoordinator.releaseLocalSpace(paths)
                .onSuccess { count ->
                    if (onSingleDone != null) {
                        toast("Cópia local removida; miniatura no app e arquivo no OneDrive mantidos.")
                        onSingleDone()
                    } else {
                        toast(
                            "$count item(ns): só a cópia local foi removida; miniatura no app e " +
                                "arquivo no OneDrive mantidos.",
                        )
                        clearSelection()
                    }
                }
                .onFailure { toast(it.message) }
        }
    }

}

internal fun buildAlbums(
    mirrored: List<GalleryDisplayItem>,
    userAlbums: List<UserAlbumEntity>,
    sort: AlbumListSort,
    albumCoverLocalIndex: AlbumCoverLocalIndex,
    mediaCacheRepository: MediaCacheRepository,
    resolveCoverModels: Boolean = true,
): List<MediaAlbum> {
    val grouped = mirrored.groupBy { it.albumName }
    val titles = (userAlbums.map { it.title } + grouped.keys).distinct()
    val albums = titles.mapNotNull { title ->
        val items = grouped[title].orEmpty()
        val userMeta = userAlbums.find { it.title == title }
        if (items.isEmpty() && userMeta == null) return@mapNotNull null
        val latest = items.maxOfOrNull { it.sortTimestamp } ?: 0L
        val photoCount = items.count { it.kind == com.onegallery.sync.data.local.MediaKind.IMAGE }
        val videoCount = items.count { it.kind == com.onegallery.sync.data.local.MediaKind.VIDEO }
        MediaAlbum(
            bucketId = "album:$title",
            title = if (userMeta != null) title else SyncPaths.albumListDisplayName(title),
            filterKey = title,
            itemCount = items.size,
            photoCount = photoCount,
            videoCount = videoCount,
            coverModel = if (resolveCoverModels) {
                albumCoverLocalIndex.coverModelForKey(
                    cacheKey = "album:$title",
                    items = items,
                    mediaCacheRepository = mediaCacheRepository,
                )
            } else {
                null
            },
            sortTimestamp = latest,
            isUserManaged = userMeta != null,
            userAlbumId = userMeta?.id,
            createdAtEpoch = userMeta?.createdAtEpoch,
        )
    }
    return sortMediaAlbums(albums, sort)
}

internal fun sortMediaAlbums(albums: List<MediaAlbum>, sort: AlbumListSort): List<MediaAlbum> {
    return when (sort) {
        AlbumListSort.NAME_A_Z -> albums.sortedBy { it.title.lowercase() }
        AlbumListSort.NAME_Z_A -> albums.sortedByDescending { it.title.lowercase() }
        AlbumListSort.DATE_NEWEST -> albums.sortedByDescending { it.sortTimestamp }
    }
}

internal fun buildPlaceAlbums(
    items: List<GalleryDisplayItem>,
    albumCoverLocalIndex: AlbumCoverLocalIndex,
    mediaCacheRepository: MediaCacheRepository,
    resolveCoverModels: Boolean = true,
): List<MediaAlbum> {
    return groupDisplayItemsForPlaceAlbums(items).mapNotNull { (encodedKey, group) ->
        if (group.isEmpty()) return@mapNotNull null
        val coverItem = if (resolveCoverModels) {
            resolveAlbumCoverItem(group, mediaCacheRepository)
        } else {
            null
        }
        val title = placeAlbumTitleForGroup(group, coverItem)
        val latest = group.maxOfOrNull { it.sortTimestamp } ?: 0L
        val photoCount = group.count { it.kind == com.onegallery.sync.data.local.MediaKind.IMAGE }
        val videoCount = group.count { it.kind == com.onegallery.sync.data.local.MediaKind.VIDEO }
        MediaAlbum(
            bucketId = "place:$encodedKey",
            title = title,
            itemCount = group.size,
            photoCount = photoCount,
            videoCount = videoCount,
            coverModel = if (resolveCoverModels) {
                albumCoverLocalIndex.coverModelForKey(
                    cacheKey = "place:$encodedKey",
                    items = group,
                    mediaCacheRepository = mediaCacheRepository,
                )
            } else {
                null
            },
            sortTimestamp = latest,
        )
    }
}

internal fun sortPlaceAlbums(albums: List<MediaAlbum>, sort: AlbumListSort): List<MediaAlbum> {
    fun hierarchyKey(album: MediaAlbum): List<String> {
        val raw = album.bucketId.removePrefix("place:")
        val match = PlaceMatchKey.decode(raw)
        if (match != null && match.level != PlaceLevel.LEGACY_LABEL) {
            return when (match.level) {
                PlaceLevel.GEO_CELL -> listOf(album.title.lowercase())
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
    private val galleryThumbnailCache: GalleryThumbnailCache,
    private val galleryPreferences: GalleryPreferences,
    private val mediaCacheRepository: MediaCacheRepository,
    private val userMessages: AppUserMessages,
    private val appContext: Context,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GalleryViewModel::class.java)) {
            return GalleryViewModel(
                localMediaRepository,
                syncCoordinator,
                galleryThumbnailCache,
                galleryPreferences,
                mediaCacheRepository,
                userMessages,
                appContext,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
