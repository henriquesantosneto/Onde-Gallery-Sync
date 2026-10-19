package com.onegallery.sync.ui.gallery

/** Uma célula por mídia local, item na nuvem ou caminho canônico. */
fun dedupeGalleryDisplayItems(items: List<GalleryDisplayItem>): List<GalleryDisplayItem> {
    if (items.size <= 1) return items
    val localSeen = mutableSetOf<Long>()
    val cloudSeen = mutableSetOf<String>()
    val pathSeen = mutableSetOf<String>()
    val sorted = items.sortedWith(
        compareByDescending<GalleryDisplayItem> { it.localUri != null }
            .thenByDescending { it.cachedOriginal != null }
            .thenByDescending { it.sortTimestamp },
    )
    return sorted.filter { item ->
        item.localMediaId?.let { id ->
            if (id in localSeen) return@filter false
            localSeen.add(id)
        }
        item.oneDriveItemId?.let { id ->
            if (id in cloudSeen) return@filter false
            cloudSeen.add(id)
        }
        if (item.canonicalPath in pathSeen) return@filter false
        pathSeen.add(item.canonicalPath)
        true
    }
}
