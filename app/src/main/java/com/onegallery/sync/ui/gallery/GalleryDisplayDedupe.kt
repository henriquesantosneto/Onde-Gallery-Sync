package com.onegallery.sync.ui.gallery

/** Uma célula por mídia (local, nuvem ou caminho), mesmo com linhas duplicadas no índice. */
fun dedupeGalleryDisplayItems(items: List<GalleryDisplayItem>): List<GalleryDisplayItem> {
    if (items.size <= 1) return items
    val sorted = items.sortedWith(
        compareByDescending<GalleryDisplayItem> { it.oneDriveItemId != null }
            .thenByDescending { it.localUri != null }
            .thenByDescending { it.cachedOriginal?.isFile == true }
            .thenByDescending { it.sortTimestamp },
    )
    val keptKeys = mutableSetOf<String>()
    return sorted.filter { item ->
        val key = item.galleryDedupeKey()
        if (key in keptKeys) return@filter false
        keptKeys.add(key)
        true
    }
}

private fun GalleryDisplayItem.galleryDedupeKey(): String {
    localMediaId?.let { return "localId:$it" }
    oneDriveItemId?.let { return "cloudId:$it" }
    localUri?.toString()?.takeIf { it.isNotBlank() }?.let { return "uri:$it" }
    cachedOriginal?.takeIf { it.isFile && it.exists() }?.absolutePath?.let { return "file:$it" }
    localOnlyContentSignature()?.let { return "sig:$it" }
    return "path:$canonicalPath"
}

/** Mesmo arquivo copiado (ex.: WhatsApp + Câmera): nome + data da foto, sem id na nuvem. */
private fun GalleryDisplayItem.localOnlyContentSignature(): String? {
    if (oneDriveItemId != null) return null
    val name = displayName.normalizedForDedupe()
    if (name.isBlank()) return null
    val bucket = sortTimestampToEpochSeconds(sortTimestamp) ?: return null
    return "$name|$bucket"
}

private fun sortTimestampToEpochSeconds(sortTimestamp: Long): Long? {
    if (sortTimestamp <= 0L) return null
    return if (sortTimestamp > 1_000_000_000_000L) sortTimestamp / 1000L else sortTimestamp
}

private fun String.normalizedForDedupe(): String =
    trim().lowercase().replace(Regex(" \\(\\d+\\)(?=\\.)"), "")
