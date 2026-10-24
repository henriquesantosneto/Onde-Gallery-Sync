package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.LocalMediaRepository
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.db.SyncMetadataEntity
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Reutiliza [GalleryDisplayItem] por linha do índice quando os campos relevantes não mudaram.
 */
class GalleryDisplayBuildCache(
    private val mediaCacheRepository: MediaCacheRepository,
    private val localMediaRepository: LocalMediaRepository,
) {
    companion object {
        /** Bump quando a montagem de [GalleryDisplayItem] mudar (ex.: URI local). */
        const val DISPLAY_ITEM_BUILD_VERSION = 11
    }
    private data class Entry(val signature: Long, val item: GalleryDisplayItem)

    private val byPath = ConcurrentHashMap<String, Entry>(8192)

    suspend fun mapIndexed(metadata: List<SyncMetadataEntity>): List<GalleryDisplayItem> {
        if (metadata.isEmpty()) return emptyList()
        return withContext(Dispatchers.Default) {
            if (metadata.size < 2_500) {
                mapSequential(metadata)
            } else {
                coroutineScope {
                    metadata.chunked(2_000).map { chunk ->
                        async { mapSequential(chunk) }
                    }.awaitAll().flatten()
                }
            }
        }
    }

    private fun mapSequential(metadata: List<SyncMetadataEntity>): List<GalleryDisplayItem> {
        val out = ArrayList<GalleryDisplayItem>(metadata.size)
        for (entity in metadata) {
            val sig = entity.displaySignature()
            val cached = byPath[entity.canonicalPath]
            if (cached != null && cached.signature == sig) {
                out.add(cached.item)
            } else {
                GalleryMapper.toDisplayItem(
                    entity,
                    mediaCacheRepository,
                    localMediaRepository,
                ).also { item ->
                    byPath[entity.canonicalPath] = Entry(sig, item)
                    out.add(item)
                }
            }
        }
        return out
    }

    fun clear() {
        byPath.clear()
    }

    fun invalidatePaths(paths: Collection<String>) {
        paths.forEach { byPath.remove(it) }
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
        h = h * 31L + DISPLAY_ITEM_BUILD_VERSION
        return h
    }
}
