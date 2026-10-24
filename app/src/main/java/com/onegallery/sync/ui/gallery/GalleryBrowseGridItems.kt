package com.onegallery.sync.ui.gallery

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
): List<GalleryDisplayItem> {
    if (!requiresActiveBrowseFilter) {
        return libraryItems
    }
    if (!routeAlbumFolderKey.isNullOrBlank()) {
        val fromRoute = libraryItems
            .filter { it.albumName == routeAlbumFolderKey }
            .sortedBy(sortOrder)
        if (browseItemsFromState.isNotEmpty() &&
            browseItemsFromState.all { it.albumName == routeAlbumFolderKey }
        ) {
            return browseItemsFromState
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
        val fromRoute = libraryItems.filter(::matchesPlaceItem).sortedBy(sortOrder)
        if (browseItemsFromState.isNotEmpty() &&
            browseItemsFromState.all(::matchesPlaceItem)
        ) {
            return browseItemsFromState
        }
        return fromRoute
    }
    return emptyList()
}

internal fun browseGridItemsAlbumFolderMatches(
    items: List<GalleryDisplayItem>,
    albumFolderKey: String,
): Boolean = items.isNotEmpty() && items.all { it.albumName == albumFolderKey }
