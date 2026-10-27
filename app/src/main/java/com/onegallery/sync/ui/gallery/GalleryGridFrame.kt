package com.onegallery.sync.ui.gallery

/**
 * Lista que a grade de fotos está mostrando.
 * O catálogo novo é montado ao lado e entra numa troca só, com a rolagem parada.
 */
data class GalleryGridPublication(
    val libraryItems: List<GalleryDisplayItem> = emptyList(),
    val items: List<GalleryDisplayItem> = emptyList(),
    val sortOrder: GallerySortOrder = GallerySortOrder.DATE_NEWEST,
    val albumFilter: String? = null,
    val locationFilter: String? = null,
    val placeBrowseMatchKey: String? = null,
)

internal fun GalleryUiState.toGridPublication(): GalleryGridPublication = GalleryGridPublication(
    libraryItems = libraryItems,
    items = items,
    sortOrder = sortOrder,
    albumFilter = albumFilter,
    locationFilter = locationFilter,
    placeBrowseMatchKey = placeBrowseMatchKey,
)

/** Ordenação e filtro trocam na hora. O restante espera a lista parar. */
internal fun GalleryGridPublication.replacesPhotoGridImmediately(
    previous: GalleryGridPublication,
): Boolean {
    return sortOrder != previous.sortOrder ||
        albumFilter != previous.albumFilter ||
        locationFilter != previous.locationFilter ||
        placeBrowseMatchKey != previous.placeBrowseMatchKey
}

internal fun samePhotoGridFrame(
    current: GalleryGridPublication,
    candidate: GalleryGridPublication,
): Boolean {
    if (candidate.replacesPhotoGridImmediately(current)) return false
    return samePhotoRows(current.libraryItems, candidate.libraryItems) &&
        samePhotoRows(current.items, candidate.items)
}

internal fun samePhotoRows(
    current: List<GalleryDisplayItem>,
    candidate: List<GalleryDisplayItem>,
): Boolean {
    if (current === candidate) return true
    if (current.size != candidate.size) return false
    for (index in current.indices) {
        val left = current[index]
        val right = candidate[index]
        if (left.canonicalPath != right.canonicalPath) return false
        if (left.localUri != right.localUri) return false
        if (left.showInBrowseGrid != right.showInBrowseGrid) return false
        if (left.cachedPlaceholderThumb?.path != right.cachedPlaceholderThumb?.path) return false
        if (left.cloudState != right.cloudState) return false
        if (left.oneDriveItemId != right.oneDriveItemId) return false
        if (left.localMediaId != right.localMediaId) return false
        if (left.localReleasedForSpace != right.localReleasedForSpace) return false
    }
    return true
}
