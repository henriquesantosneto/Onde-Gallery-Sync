package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.locationSpecificityScore
import com.onegallery.sync.sync.PendingDeleteTarget

/** Uma célula por mídia (local, nuvem ou caminho), mesmo com linhas duplicadas no índice. */
fun dedupeGalleryDisplayItems(items: List<GalleryDisplayItem>): List<GalleryDisplayItem> {
    if (items.size <= 1) return items
    return if (items.size >= 1_500) {
        dedupeGalleryDisplayItemsFast(items)
    } else {
        dedupeGalleryDisplayItemsSorted(items)
    }
}

private fun dedupeGalleryDisplayItemsSorted(items: List<GalleryDisplayItem>): List<GalleryDisplayItem> {
    val sorted = items.sortedWith(galleryDisplayItemDedupeComparator())
    val keptKeys = mutableSetOf<String>()
    return sorted.filter { item ->
        val key = item.galleryDedupeKey()
        if (key in keptKeys) return@filter false
        keptKeys.add(key)
        true
    }
}

/** O(n) — sem sort global em bibliotecas grandes. */
private fun dedupeGalleryDisplayItemsFast(items: List<GalleryDisplayItem>): List<GalleryDisplayItem> {
    val best = LinkedHashMap<String, GalleryDisplayItem>(items.size)
    for (item in items) {
        val key = item.galleryDedupeKey()
        val existing = best[key]
        if (existing == null || galleryDisplayItemDedupeComparator().compare(item, existing) < 0) {
            best[key] = item
        }
    }
    return best.values.toList()
}

private fun galleryDisplayItemDedupeComparator() =
    compareByDescending<GalleryDisplayItem> { it.pendingDeleteTarget != PendingDeleteTarget.NONE }
        .thenByDescending { it.oneDriveItemId != null }
        .thenByDescending { it.localUri != null }
        .thenByDescending { it.cachedOriginal?.isFile == true }
        .thenByDescending { it.locationSpecificityScore() }
        .thenByDescending { it.sortTimestamp }

internal fun GalleryDisplayItem.galleryDedupeKey(): String {
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
