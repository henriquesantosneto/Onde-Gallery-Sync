package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaAlbum
import com.onegallery.sync.data.local.db.UserAlbumEntity

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

    private var folderAlbumsCacheKey: AlbumsCacheKey? = null
    private var cachedAlbums: List<MediaAlbum> = emptyList()
    private var placeAlbumsCacheKey: AlbumsCacheKey? = null
    private var cachedPlaceAlbums: List<MediaAlbum> = emptyList()

    fun publish(bundle: SnapshotBundle) {
        lastBundle = bundle
        sortedLibraryCache = null
        folderAlbumsCacheKey = null
        placeAlbumsCacheKey = null
    }

    fun refreshThumbnailFlags(mediaCacheRepository: MediaCacheRepository): SnapshotBundle? {
        val current = lastBundle ?: return null
        var changed = false
        val updated = current.catalogItems.map { item ->
            if (mediaCacheRepository.isThumbnailCachedFast(item.canonicalPath, item.oneDriveItemId)) {
                val thumb = mediaCacheRepository.thumbFile(item.canonicalPath)
                    .takeIf { it.isFile && it.length() > 0L }
                if (thumb != null && item.cachedPlaceholderThumb?.absolutePath != thumb.absolutePath) {
                    changed = true
                    item.copy(cachedPlaceholderThumb = thumb)
                } else {
                    item
                }
            } else {
                item
            }
        }
        if (!changed) return current
        val visible = updated.filter { it.isVisibleInGalleryBrowse(mediaCacheRepository) }
        val next = current.copy(catalogItems = updated, visibleItems = visible)
        lastBundle = next
        sortedLibraryCache = null
        folderAlbumsCacheKey = null
        placeAlbumsCacheKey = null
        return next
    }

    fun sortedLibraryItems(
        visibleItems: List<GalleryDisplayItem>,
        order: GallerySortOrder,
    ): List<GalleryDisplayItem> {
        val key = visibleItems.size to order
        if (sortedLibraryCache == key && sortedLibraryItems.isNotEmpty()) {
            return sortedLibraryItems
        }
        sortedLibraryItems = visibleItems.sortedBy(order)
        sortedLibraryCache = key
        return sortedLibraryItems
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

    private data class AlbumsCacheKey(
        val catalogSize: Int,
        val userAlbumCount: Int,
        val albumSort: AlbumListSort,
        val deferCovers: Boolean,
        val coverRevisionStamp: Long,
        val catalogHead: String?,
        val catalogTail: String?,
    )
}
