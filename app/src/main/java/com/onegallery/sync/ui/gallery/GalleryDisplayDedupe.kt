package com.onegallery.sync.ui.gallery

/** Uma célula por mídia (local, nuvem ou caminho), mesmo com linhas duplicadas no índice. */
fun dedupeGalleryDisplayItems(items: List<GalleryDisplayItem>): List<GalleryDisplayItem> {
    if (items.size <= 1) return items
    val sorted = items.sortedWith(
        compareByDescending<GalleryDisplayItem> { it.localUri != null }
            .thenByDescending { it.cachedOriginal?.isFile == true }
            .thenByDescending { it.oneDriveItemId != null }
            .thenByDescending { it.sortTimestamp },
    )
    val keptKeys = mutableSetOf<String>()
    val keptPaths = mutableSetOf<String>()
    return sorted.filter { item ->
        if (item.canonicalPath in keptPaths) return@filter false
        val key = item.galleryDedupeKey()
        if (key != null) {
            if (key in keptKeys) return@filter false
            keptKeys.add(key)
        }
        keptPaths.add(item.canonicalPath)
        true
    }
}

private fun GalleryDisplayItem.galleryDedupeKey(): String? {
    localMediaId?.let { return "localId:$it" }
    oneDriveItemId?.let { return "cloudId:$it" }
    localUri?.toString()?.takeIf { it.isNotBlank() }?.let { return "uri:$it" }
    cachedOriginal?.takeIf { it.isFile && it.exists() }?.absolutePath?.let { return "file:$it" }
    return null
}
