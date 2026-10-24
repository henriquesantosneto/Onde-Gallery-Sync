package com.onegallery.sync.ui.gallery

/** Pastas: usa itens já filtrados para a grade; sem fallback para catálogo cheio com filtro de miniatura. */
fun itemsForAlbumListGrouping(
    catalogItems: List<GalleryDisplayItem>,
    visibleItems: List<GalleryDisplayItem>,
    onlyItemsWithThumbnail: Boolean = false,
): List<GalleryDisplayItem> {
    if (visibleItems.isNotEmpty()) return visibleItems
    if (onlyItemsWithThumbnail) return emptyList()
    return catalogItems
}

/** Locais: mesma regra da grade — só itens com miniatura quando o filtro está ativo. */
fun itemsForPlaceAlbumGrouping(
    catalogItems: List<GalleryDisplayItem>,
    visibleItems: List<GalleryDisplayItem>,
    onlyItemsWithThumbnail: Boolean = false,
): List<GalleryDisplayItem> {
    if (visibleItems.isNotEmpty()) return visibleItems
    if (onlyItemsWithThumbnail) return emptyList()
    return catalogItems
}
