package com.onegallery.sync.ui.gallery

/** Pastas: pode usar janela visível; locais usam [itemsForPlaceAlbumGrouping]. */
fun itemsForAlbumListGrouping(
    catalogItems: List<GalleryDisplayItem>,
    visibleItems: List<GalleryDisplayItem>,
): List<GalleryDisplayItem> {
    if (visibleItems.isNotEmpty()) return visibleItems
    return catalogItems
}

/** Locais: sempre o índice completo (evita álbuns duplicados / contagens pela janela da grade). */
fun itemsForPlaceAlbumGrouping(
    catalogItems: List<GalleryDisplayItem>,
    visibleItems: List<GalleryDisplayItem>,
): List<GalleryDisplayItem> {
    if (catalogItems.isNotEmpty()) return catalogItems
    return visibleItems
}
