package com.onegallery.sync.ui.gallery

/** Pastas: pode usar janela visível; locais usam [itemsForPlaceAlbumGrouping]. */
fun itemsForAlbumListGrouping(
    catalogItems: List<GalleryDisplayItem>,
    visibleItems: List<GalleryDisplayItem>,
): List<GalleryDisplayItem> {
    if (visibleItems.isNotEmpty()) return visibleItems
    return catalogItems
}

/** Locais: mesma regra da grade — só itens com miniatura quando o filtro está ativo. */
fun itemsForPlaceAlbumGrouping(
    catalogItems: List<GalleryDisplayItem>,
    visibleItems: List<GalleryDisplayItem>,
): List<GalleryDisplayItem> {
    if (visibleItems.isNotEmpty()) return visibleItems
    return catalogItems
}
