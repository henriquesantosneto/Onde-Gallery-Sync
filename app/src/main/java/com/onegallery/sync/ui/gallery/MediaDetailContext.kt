package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.PlaceMatchKey
import com.onegallery.sync.data.local.matchesPlace

/**
 * Pager da foto aberta.
 * Se a grade de álbum passou a lista que estava na tela, ela vence o filtro do
 * ViewModel — esse filtro é único e ainda pode ser o álbum anterior.
 */
fun detailItemsForOpenedPath(
    allItems: List<GalleryDisplayItem>,
    openedPath: String,
    browsePaths: List<String>?,
    albumFilter: String?,
    locationFilter: String?,
    placeMatchKey: String?,
    geoItems: List<GalleryDisplayItem> = emptyList(),
    sortOrder: GallerySortOrder = GallerySortOrder.DATE_NEWEST,
    timelineGrouping: TimelineGrouping = TimelineGrouping.RELAXED,
): List<GalleryDisplayItem> {
    if (!browsePaths.isNullOrEmpty() && openedPath in browsePaths) {
        val byPath = allItems.associateBy { it.canonicalPath }
        val ordered = browsePaths.mapNotNull { byPath[it] }
        if (ordered.any { it.canonicalPath == openedPath }) return ordered
    }
    return detailContextItems(
        allItems = allItems,
        albumFilter = albumFilter,
        locationFilter = locationFilter,
        placeMatchKey = placeMatchKey,
        anchorPath = openedPath,
        geoItems = geoItems,
        sortOrder = sortOrder,
        timelineGrouping = timelineGrouping,
    )
}

/** Conjunto de mídias para pager + faixa (seção da timeline, álbum, local ou pasta da foto). */
fun detailContextItems(
    allItems: List<GalleryDisplayItem>,
    albumFilter: String?,
    locationFilter: String?,
    placeMatchKey: String?,
    anchorPath: String?,
    geoItems: List<GalleryDisplayItem> = emptyList(),
    sortOrder: GallerySortOrder = GallerySortOrder.DATE_NEWEST,
    timelineGrouping: TimelineGrouping = TimelineGrouping.RELAXED,
): List<GalleryDisplayItem> {
    fun List<GalleryDisplayItem>.keepsOpenedPath(): Boolean =
        anchorPath.isNullOrEmpty() || any { it.canonicalPath == anchorPath }

    albumFilter?.let { album ->
        val filtered = allItems
            .filter { it.albumName == album }
            .sortedBy(sortOrder)
        if (filtered.keepsOpenedPath()) return filtered
    }
    if (placeMatchKey != null || locationFilter != null) {
        val key = placeMatchKey?.let { PlaceMatchKey.decode(it) }
        val filtered = allItems
            .filter { item ->
                when {
                    key != null -> item.matchesPlace(key)
                    !locationFilter.isNullOrBlank() -> item.locationLabel == locationFilter
                    else -> false
                }
            }
            .sortedBy(sortOrder)
        if (filtered.keepsOpenedPath()) return filtered
    }
    val geoPaths = geoItems.map { it.canonicalPath }.toSet()
    if (anchorPath != null && anchorPath in geoPaths && geoItems.size > 1) {
        return geoItems
    }
    val anchor = anchorPath?.let { path -> allItems.find { it.canonicalPath == path } }
        ?: return allItems

    if (isDateTimelineSort(sortOrder)) {
        val sectionKey = timelineSectionKeyForItem(anchor, timelineGrouping)
        val inSection = sortedTimelinePhotos(allItems, sortOrder).filter { item ->
            timelineSectionKeyForItem(item, timelineGrouping) == sectionKey
        }
        if (inSection.isNotEmpty()) {
            return inSection
        }
    }

    return allItems
        .filter { it.albumName == anchor.albumName }
        .sortedBy(sortOrder)
}
