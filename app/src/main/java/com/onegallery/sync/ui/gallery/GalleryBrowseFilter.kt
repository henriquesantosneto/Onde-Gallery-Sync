package com.onegallery.sync.ui.gallery

/** Filtro ativo na aba Fotos (pasta ou local). */
sealed class GalleryBrowseFilter {
    data object All : GalleryBrowseFilter()
    data class AlbumFolder(val name: String) : GalleryBrowseFilter()
    data class Place(
        val displayName: String,
        val matchKey: String,
    ) : GalleryBrowseFilter()
}
