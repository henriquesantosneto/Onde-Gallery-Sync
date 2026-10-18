package com.onegallery.sync.ui.gallery

enum class GallerySortOrder(val label: String) {
    DATE_NEWEST("Captura (recentes)"),
    DATE_OLDEST("Captura (antigas)"),
    NAME_A_Z("Nome A → Z"),
    NAME_Z_A("Nome Z → A"),
}

fun List<GalleryDisplayItem>.sortedBy(order: GallerySortOrder): List<GalleryDisplayItem> =
    when (order) {
        GallerySortOrder.DATE_NEWEST -> sortedByDescending { it.sortTimestamp }
        GallerySortOrder.DATE_OLDEST -> sortedBy { it.sortTimestamp }
        GallerySortOrder.NAME_A_Z -> sortedBy { it.displayName.lowercase() }
        GallerySortOrder.NAME_Z_A -> sortedByDescending { it.displayName.lowercase() }
    }
