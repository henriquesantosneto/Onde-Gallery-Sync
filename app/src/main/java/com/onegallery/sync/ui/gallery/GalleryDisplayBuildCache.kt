package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.db.SyncMetadataEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * Reutiliza [GalleryDisplayItem] por linha do índice quando os campos relevantes não mudaram.
 */
class GalleryDisplayBuildCache(
    private val mediaCacheRepository: MediaCacheRepository,
) {
    private data class Entry(val signature: Long, val item: GalleryDisplayItem)

    private val byPath = ConcurrentHashMap<String, Entry>(4096)

    fun mapIndexed(metadata: List<SyncMetadataEntity>): List<GalleryDisplayItem> {
        if (metadata.isEmpty()) return emptyList()
        return metadata.map { entity ->
            val sig = entity.displaySignature()
            val cached = byPath[entity.canonicalPath]
            if (cached != null && cached.signature == sig) {
                cached.item
            } else {
                GalleryMapper.toDisplayItem(entity, mediaCacheRepository).also { item ->
                    byPath[entity.canonicalPath] = Entry(sig, item)
                }
            }
        }
    }

    fun clear() {
        byPath.clear()
    }

    private fun SyncMetadataEntity.displaySignature(): Long {
        var h = canonicalPath.hashCode().toLong()
        h = h * 31L + (localMediaId ?: -1L)
        h = h * 31L + (oneDriveItemId?.hashCode()?.toLong() ?: 0L)
        h = h * 31L + cloudState.ordinal
        h = h * 31L + (localModifiedAt ?: 0L)
        h = h * 31L + (remoteModifiedAt ?: 0L)
        h = h * 31L + (cachedFilePath?.hashCode()?.toLong() ?: 0L)
        h = h * 31L + (localContentUri?.hashCode()?.toLong() ?: 0L)
        h = h * 31L + pendingDeleteTarget.ordinal
        h = h * 31L + localReleasedForSpace.hashCode()
        h = h * 31L + (capturedAtSeconds ?: 0L)
        return h
    }
}
