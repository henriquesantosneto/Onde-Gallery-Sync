package com.onegallery.sync.ui.gallery

import com.onegallery.sync.sync.CloudSyncState
import java.io.File

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

/**
 * O catálogo da grade demora a reler o Room depois do download.
 * Enquanto isso, o arquivo já recuperado entra como Sync na publicação atual.
 * Um item liberado para economizar espaço não volta a Sync por causa desse mapa.
 */
internal fun GalleryGridPublication.applyingRecalledOriginals(
    recalledFiles: Map<String, String>,
): GalleryGridPublication {
    if (recalledFiles.isEmpty()) return this
    fun patch(items: List<GalleryDisplayItem>): List<GalleryDisplayItem> {
        var changed = false
        val next = ArrayList<GalleryDisplayItem>(items.size)
        for (item in items) {
            val path = recalledFiles[item.canonicalPath]
            if (path == null || !item.acceptsRecalledOriginal(path)) {
                next.add(item)
            } else {
                changed = true
                next.add(
                    item.copy(
                        cloudState = CloudSyncState.SYNCED,
                        cachedOriginal = File(path),
                        localReleasedForSpace = false,
                    ),
                )
            }
        }
        return if (changed) next else items
    }
    val library = patch(libraryItems)
    val visible = patch(items)
    if (library === libraryItems && visible === items) return this
    return copy(libraryItems = library, items = visible)
}

private fun GalleryDisplayItem.acceptsRecalledOriginal(absolutePath: String): Boolean {
    if (localReleasedForSpace) return false
    if (cloudState == CloudSyncState.LOCAL_ONLY || cloudState == CloudSyncState.PENDING_UPLOAD) {
        return false
    }
    if (cloudState == CloudSyncState.SYNCED && cachedOriginal?.path == absolutePath) return false
    return cloudState == CloudSyncState.CLOUD_ONLY ||
        cloudState == CloudSyncState.PENDING_DOWNLOAD ||
        cloudState == CloudSyncState.SYNCED
}
