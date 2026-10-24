package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.data.local.MediaAlbum
import com.onegallery.sync.data.local.PlaceMatchKey
import com.onegallery.sync.data.local.collapseItemsForPlaceAlbums
import com.onegallery.sync.data.local.db.UserAlbumEntity
import com.onegallery.sync.data.local.matchesPlaceAlbumBucket

/** Estado derivado reutilizável entre recomposições e mudanças leves (miniaturas). */
class GalleryCatalogSession {
    data class SnapshotBundle(
        val catalogItems: List<GalleryDisplayItem>,
        val visibleItems: List<GalleryDisplayItem>,
        val userAlbums: List<UserAlbumEntity>,
        val fingerprint: GalleryCatalogFingerprint,
        val localRevision: Long,
    )

    @Volatile
    var lastBundle: SnapshotBundle? = null
        private set

    private var sortedLibraryCache: Pair<Int, GallerySortOrder>? = null
    private var sortedLibraryItems: List<GalleryDisplayItem> = emptyList()

    private var preSortedDateNewest: List<GalleryDisplayItem> = emptyList()
    private var preSortedDateOldest: List<GalleryDisplayItem> = emptyList()
    private var preSortVisibleRef: List<GalleryDisplayItem>? = null

    private var cachedGeoItems: List<GalleryDisplayItem> = emptyList()
    private var geoCatalogRef: List<GalleryDisplayItem>? = null

    private var browseFilterCacheKey: BrowseFilterCacheKey? = null
    private var browseFilterCachedItems: List<GalleryDisplayItem> = emptyList()

    private var folderAlbumsCacheKey: AlbumsCacheKey? = null
    private var cachedAlbums: List<MediaAlbum> = emptyList()
    private var placeAlbumsCacheKey: AlbumsCacheKey? = null
    private var cachedPlaceAlbums: List<MediaAlbum> = emptyList()

    /** Remove itens da RAM da sessão (exclusão na galeria) sem esperar Room/debounce. */
    fun removeCanonicalPaths(
        paths: Set<String>,
        activeSort: GallerySortOrder = GallerySortOrder.DATE_NEWEST,
    ): SnapshotBundle? {
        if (paths.isEmpty()) return lastBundle
        val current = lastBundle ?: return null
        val catalogItems = current.catalogItems.filter { it.canonicalPath !in paths }
        val visibleItems = current.visibleItems.filter { it.canonicalPath !in paths }
        if (catalogItems.size == current.catalogItems.size &&
            visibleItems.size == current.visibleItems.size
        ) {
            return current
        }
        val fingerprint = GalleryCatalogFingerprint.fromDisplayPaths(
            catalogItems.map { it.canonicalPath },
        )
        val next = current.copy(
            catalogItems = catalogItems,
            visibleItems = visibleItems,
            fingerprint = fingerprint,
        )
        publish(next, activeSort)
        return next
    }

    fun publish(bundle: SnapshotBundle, activeSort: GallerySortOrder = GallerySortOrder.DATE_NEWEST) {
        lastBundle = bundle
        sortedLibraryCache = null
        folderAlbumsCacheKey = null
        placeAlbumsCacheKey = null
        browseFilterCacheKey = null
        if (bundle.visibleItems !== preSortVisibleRef) {
            preSortVisibleRef = bundle.visibleItems
            val visible = bundle.visibleItems
            if (visible.size < 400) {
                preSortedDateNewest = visible
                preSortedDateOldest = visible
            } else {
                when (activeSort) {
                    GallerySortOrder.DATE_NEWEST -> {
                        preSortedDateNewest = visible.sortedByDescending { it.sortTimestamp }
                    }
                    GallerySortOrder.DATE_OLDEST -> {
                        preSortedDateOldest = visible.sortedBy { it.sortTimestamp }
                    }
                    else -> Unit
                }
            }
        }
        if (bundle.catalogItems !== geoCatalogRef) {
            geoCatalogRef = bundle.catalogItems
            cachedGeoItems = computeGeoItems(bundle.catalogItems)
        }
    }

    fun geoItems(): List<GalleryDisplayItem> = cachedGeoItems

    fun refreshThumbnailFlags(
        mediaCacheRepository: MediaCacheRepository,
        onlyItemsWithThumbnail: Boolean = false,
    ): SnapshotBundle? {
        val current = lastBundle ?: return null
        var changed = false
        val updated = current.catalogItems.map { item ->
            val thumb = if (item.kind == MediaKind.VIDEO) {
                mediaCacheRepository.galleryThumbnailResolved(
                    item.canonicalPath,
                    item.oneDriveItemId,
                    requireVideoAcceptable = true,
                )
            } else {
                mediaCacheRepository.displayableThumbnailIfCached(
                    item.canonicalPath,
                    item.oneDriveItemId,
                )
            }
            if (thumb != null) {
                if (item.cachedPlaceholderThumb?.absolutePath != thumb.absolutePath) {
                    changed = true
                    item.copy(cachedPlaceholderThumb = thumb)
                } else {
                    item
                }
            } else {
                item
            }
        }
        val catalog = if (changed) updated else current.catalogItems
        val visible = filterVisibleForGalleryBrowse(
            catalog,
            mediaCacheRepository,
            onlyItemsWithThumbnail,
        )
        if (!changed && visible === current.visibleItems) return current
        val next = current.copy(catalogItems = catalog, visibleItems = visible)
        publish(next)
        return next
    }

    fun sortedLibraryItems(
        visibleItems: List<GalleryDisplayItem>,
        order: GallerySortOrder,
    ): List<GalleryDisplayItem> {
        when (order) {
            GallerySortOrder.DATE_NEWEST -> {
                if (visibleItems === preSortVisibleRef && preSortedDateNewest.isNotEmpty()) {
                    return preSortedDateNewest
                }
            }
            GallerySortOrder.DATE_OLDEST -> {
                if (visibleItems === preSortVisibleRef && preSortedDateOldest.isNotEmpty()) {
                    return preSortedDateOldest
                }
            }
            else -> Unit
        }
        val key = visibleItems.size to order
        if (sortedLibraryCache == key && sortedLibraryItems.isNotEmpty()) {
            return sortedLibraryItems
        }
        sortedLibraryItems = visibleItems.sortedBy(order)
        sortedLibraryCache = key
        return sortedLibraryItems
    }

    fun browseItemsFor(
        visibleItems: List<GalleryDisplayItem>,
        filter: GalleryBrowseFilter,
        order: GallerySortOrder,
    ): List<GalleryDisplayItem> {
        when (filter) {
            GalleryBrowseFilter.All -> return sortedLibraryItems(visibleItems, order)
            is GalleryBrowseFilter.AlbumFolder -> {
                val cacheKey = BrowseFilterCacheKey(
                    visibleSize = visibleItems.size,
                    visibleHead = visibleItems.firstOrNull()?.canonicalPath,
                    filter = filter,
                    order = order,
                )
                if (browseFilterCacheKey == cacheKey && browseFilterCachedItems.isNotEmpty()) {
                    return browseFilterCachedItems
                }
                val filtered = visibleItems.filter { it.albumName == filter.name }.sortedBy(order)
                browseFilterCacheKey = cacheKey
                browseFilterCachedItems = filtered
                return filtered
            }
            is GalleryBrowseFilter.Place -> {
                val cacheKey = BrowseFilterCacheKey(
                    visibleSize = visibleItems.size,
                    visibleHead = visibleItems.firstOrNull()?.canonicalPath,
                    filter = filter,
                    order = order,
                )
                if (browseFilterCacheKey == cacheKey && browseFilterCachedItems.isNotEmpty()) {
                    return browseFilterCachedItems
                }
                val collapsed = collapseItemsForPlaceAlbums(visibleItems)
                val filtered = collapsed.filter { item ->
                    if (filter.matchKey.isNotBlank()) {
                        item.matchesPlaceAlbumBucket(filter.matchKey)
                    } else {
                        item.locationLabel == filter.displayName
                    }
                }.sortedBy(order)
                browseFilterCacheKey = cacheKey
                browseFilterCachedItems = filtered
                return filtered
            }
        }
    }

    fun albumsFor(
        catalogItems: List<GalleryDisplayItem>,
        userAlbums: List<UserAlbumEntity>,
        albumSort: AlbumListSort,
        deferCoverModels: Boolean,
        albumCoverLocalIndex: AlbumCoverLocalIndex,
        mediaCacheRepository: MediaCacheRepository,
        coverRevisionStamp: Long,
    ): List<MediaAlbum> {
        val key = AlbumsCacheKey(
            catalogSize = catalogItems.size,
            userAlbumCount = userAlbums.size,
            albumSort = albumSort,
            deferCovers = deferCoverModels,
            coverRevisionStamp = coverRevisionStamp,
            catalogHead = catalogItems.firstOrNull()?.canonicalPath,
            catalogTail = catalogItems.lastOrNull()?.canonicalPath,
        )
        if (folderAlbumsCacheKey == key && cachedAlbums.isNotEmpty()) {
            return cachedAlbums
        }
        cachedAlbums = buildAlbums(
            catalogItems,
            userAlbums,
            albumSort,
            albumCoverLocalIndex,
            mediaCacheRepository,
            resolveCoverModels = !deferCoverModels,
        )
        folderAlbumsCacheKey = key
        return cachedAlbums
    }

    fun placeAlbumsFor(
        catalogItems: List<GalleryDisplayItem>,
        albumSort: AlbumListSort,
        deferCoverModels: Boolean,
        albumCoverLocalIndex: AlbumCoverLocalIndex,
        mediaCacheRepository: MediaCacheRepository,
        coverRevisionStamp: Long,
    ): List<MediaAlbum> {
        val key = AlbumsCacheKey(
            catalogSize = catalogItems.size,
            userAlbumCount = 0,
            albumSort = albumSort,
            deferCovers = deferCoverModels,
            coverRevisionStamp = coverRevisionStamp,
            catalogHead = catalogItems.firstOrNull()?.canonicalPath,
            catalogTail = catalogItems.lastOrNull()?.canonicalPath,
        )
        if (placeAlbumsCacheKey == key && cachedPlaceAlbums.isNotEmpty()) {
            return cachedPlaceAlbums
        }
        cachedPlaceAlbums = sortPlaceAlbums(
            buildPlaceAlbums(
                catalogItems,
                albumCoverLocalIndex,
                mediaCacheRepository,
                resolveCoverModels = !deferCoverModels,
            ),
            albumSort,
        )
        placeAlbumsCacheKey = key
        return cachedPlaceAlbums
    }

    /** Repõe listas de álbuns após spill / RAM de sessão (evita remontar milhares de pastas). */
    fun restoreAlbumCaches(
        albums: List<MediaAlbum>,
        placeAlbums: List<MediaAlbum>,
        catalogItems: List<GalleryDisplayItem>,
        userAlbumCount: Int,
        albumSort: AlbumListSort,
        deferCoverModels: Boolean,
        coverRevisionStamp: Long,
    ) {
        val catalogSizeForKey = catalogItems.size.takeIf { it > 0 }
            ?: albums.sumOf { it.itemCount }.coerceAtLeast(1)
        val key = AlbumsCacheKey(
            catalogSize = catalogSizeForKey,
            userAlbumCount = userAlbumCount,
            albumSort = albumSort,
            deferCovers = deferCoverModels,
            coverRevisionStamp = coverRevisionStamp,
            catalogHead = catalogItems.firstOrNull()?.canonicalPath,
            catalogTail = catalogItems.lastOrNull()?.canonicalPath,
        )
        cachedAlbums = albums
        folderAlbumsCacheKey = key
        cachedPlaceAlbums = placeAlbums
        placeAlbumsCacheKey = key.copy(userAlbumCount = 0)
    }

    private data class BrowseFilterCacheKey(
        val visibleSize: Int,
        val visibleHead: String?,
        val filter: GalleryBrowseFilter,
        val order: GallerySortOrder,
    )

    private data class AlbumsCacheKey(
        val catalogSize: Int,
        val userAlbumCount: Int,
        val albumSort: AlbumListSort,
        val deferCovers: Boolean,
        val coverRevisionStamp: Long,
        val catalogHead: String?,
        val catalogTail: String?,
        /** Invalida cache após mudança de agrupamento de locais (~3 km / rótulo). */
        val placeGroupingVersion: Int = PLACE_ALBUM_GROUPING_VERSION,
    )

    companion object {
        const val PLACE_ALBUM_GROUPING_VERSION = 3
    }

    private fun computeGeoItems(catalog: List<GalleryDisplayItem>): List<GalleryDisplayItem> {
        if (catalog.isEmpty()) return emptyList()
        val out = ArrayList<GalleryDisplayItem>()
        for (item in catalog) {
            if (item.latitude != null && item.longitude != null) {
                out.add(item)
            }
        }
        return out
    }
}
