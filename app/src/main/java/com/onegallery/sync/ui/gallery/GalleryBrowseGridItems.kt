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
        if (browseItemsFromState.isNotEmpty()) return browseItemsFromState
        return libraryItems
            .filter { it.albumName == routeAlbumFolderKey }
            .sortedBy(sortOrder)
    }
    if (!routePlaceMatchKey.isNullOrBlank()) {
        if (browseItemsFromState.isNotEmpty()) return browseItemsFromState
        val matchKey = PlaceMatchKey.decode(routePlaceMatchKey)
        return libraryItems
            .filter { item ->
                when {
                    matchKey != null -> item.matchesPlace(matchKey, routePlaceTitle)
                    !routePlaceTitle.isNullOrBlank() -> item.locationLabel == routePlaceTitle
                    else -> false
                }
            }
            .sortedBy(sortOrder)
    }
    return emptyList()
}
