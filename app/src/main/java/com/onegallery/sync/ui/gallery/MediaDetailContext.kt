package com.onegallery.sync.ui.gallery

/** Conjunto de mídias para pager + faixa (álbum, local ou pasta da foto). */
fun detailContextItems(
    allItems: List<GalleryDisplayItem>,
    albumFilter: String?,
    locationFilter: String?,
    anchorPath: String?,
    geoItems: List<GalleryDisplayItem> = emptyList(),
): List<GalleryDisplayItem> {
    if (albumFilter != null || locationFilter != null) {
        return allItems
    }
    val geoPaths = geoItems.map { it.canonicalPath }.toSet()
    if (anchorPath != null && anchorPath in geoPaths && geoItems.size > 1) {
        return geoItems
    }
    val anchor = anchorPath?.let { path -> allItems.find { it.canonicalPath == path } }
        ?: return allItems
    return allItems.filter { it.albumName == anchor.albumName }
}
