package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.PlaceMatchKey
import com.onegallery.sync.data.local.matchesPlace

/** Lista da grade: rota manda no filtro (evita álbum errado por estado atrasado do ViewModel). */
fun resolveBrowseGridItems(
    requiresActiveBrowseFilter: Boolean,
    libraryItems: List<GalleryDisplayItem>,
    browseItemsFromState: List<GalleryDisplayItem>,
    sortOrder: GallerySortOrder,
    routeAlbumFolderKey: String?,
    routePlaceMatchKey: String?,
    routePlaceTitle: String?,
    @Suppress("UNUSED_PARAMETER") mediaCacheRepository: MediaCacheRepository,
): List<GalleryDisplayItem> {
    fun browsable(items: List<GalleryDisplayItem>): List<GalleryDisplayItem> =
        items.filter { it.belongsOnBrowseGridWithoutDisk() }

    if (!requiresActiveBrowseFilter) {
        return browsable(libraryItems)
    }
    if (!routeAlbumFolderKey.isNullOrBlank()) {
        val fromRoute = browsable(
            libraryItems.filter { it.albumName == routeAlbumFolderKey },
        ).sortedBy(sortOrder)
        if (browseItemsFromState.isNotEmpty() &&
            browseItemsFromState.all { it.albumName == routeAlbumFolderKey }
        ) {
            return browsable(browseItemsFromState).sortedBy(sortOrder)
        }
        return fromRoute
    }
    if (!routePlaceMatchKey.isNullOrBlank()) {
        val matchKey = PlaceMatchKey.decode(routePlaceMatchKey)
        fun matchesPlaceItem(item: GalleryDisplayItem): Boolean = when {
            matchKey != null -> item.matchesPlace(matchKey, routePlaceTitle)
            !routePlaceTitle.isNullOrBlank() -> item.locationLabel == routePlaceTitle
            else -> false
        }
        val fromRoute = browsable(libraryItems.filter(::matchesPlaceItem)).sortedBy(sortOrder)
        if (browseItemsFromState.isNotEmpty() &&
            browseItemsFromState.all(::matchesPlaceItem)
        ) {
            return browsable(browseItemsFromState).sortedBy(sortOrder)
        }
        return fromRoute
    }
    return emptyList()
}

internal fun browseGridItemsAlbumFolderMatches(
    items: List<GalleryDisplayItem>,
    albumFolderKey: String,
): Boolean = items.isNotEmpty() && items.all { it.albumName == albumFolderKey }
