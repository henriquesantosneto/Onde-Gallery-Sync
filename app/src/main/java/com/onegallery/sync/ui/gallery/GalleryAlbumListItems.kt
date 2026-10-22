package com.onegallery.sync.ui.gallery

/** Itens que entram na lista de álbuns (pastas e locais) — alinhado ao que a grade pode exibir. */
fun itemsForAlbumListGrouping(
    catalogItems: List<GalleryDisplayItem>,
    visibleItems: List<GalleryDisplayItem>,
): List<GalleryDisplayItem> {
    if (visibleItems.isNotEmpty()) return visibleItems
    return catalogItems
}
