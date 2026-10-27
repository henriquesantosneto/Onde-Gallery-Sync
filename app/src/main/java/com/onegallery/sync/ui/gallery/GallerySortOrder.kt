package com.onegallery.sync.ui.gallery

enum class GallerySortOrder(val label: String) {
    DATE_NEWEST("Captura (recentes)"),
    DATE_OLDEST("Captura (antigas)"),
    NAME_A_Z("Nome A → Z"),
    NAME_Z_A("Nome Z → A"),
}

/** Segundos e milissegundos no mesmo eixo, para a data não misturar as duas unidades. */
internal fun GalleryDisplayItem.librarySortMillis(): Long {
    val raw = sortTimestamp
    if (raw <= 0L) return 0L
    return if (raw >= 10_000_000_000L) raw else raw * 1000L
}

internal fun GalleryDisplayItem.librarySortName(): String {
    val name = displayName.trim()
    if (name.isNotEmpty()) return name
    return canonicalPath.substringAfterLast('/')
}

fun List<GalleryDisplayItem>.sortedBy(order: GallerySortOrder): List<GalleryDisplayItem> =
    when (order) {
        GallerySortOrder.DATE_NEWEST -> sortedWith(
            compareByDescending<GalleryDisplayItem> { it.librarySortMillis() }
                .thenByDescending { it.librarySortName().lowercase() },
        )
        GallerySortOrder.DATE_OLDEST -> sortedWith(
            compareBy<GalleryDisplayItem> { it.librarySortMillis() }
                .thenBy { it.librarySortName().lowercase() },
        )
        GallerySortOrder.NAME_A_Z -> sortedWith(
            compareBy<GalleryDisplayItem> { it.librarySortName().lowercase() }
                .thenByDescending { it.librarySortMillis() },
        )
        GallerySortOrder.NAME_Z_A -> sortedWith(
            compareByDescending<GalleryDisplayItem> { it.librarySortName().lowercase() }
                .thenByDescending { it.librarySortMillis() },
        )
    }
