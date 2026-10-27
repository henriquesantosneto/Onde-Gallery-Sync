package com.onegallery.sync.ui.gallery

import android.graphics.Bitmap
import android.net.Uri
import java.util.LinkedHashMap

/**
 * Capas já pintadas na grade de álbuns. A célula some ao rolar e, sem isto,
 * a próxima aparição recomeça vazia e busca de novo no meio do gesto.
 */
internal object AlbumCoverBitmapCache {
    data class Hit(
        val model: Any?,
        val isVideo: Boolean,
        val cacheKey: String,
        val revision: Long,
        val empty: Boolean,
    )

    private const val MAX_ENTRIES = 400
    private const val DEFAULT_BITMAP_BYTES = 28 * 1024 * 1024

    private val lock = Any()
    private val entries = LinkedHashMap<String, Hit>(64, 0.75f, true)

    @Volatile
    private var maxBitmapBytes: Int = DEFAULT_BITMAP_BYTES

    fun noteLargeHeap(largeHeapMb: Int) {
        maxBitmapBytes = albumCoverCacheBytes(largeHeapMb)
    }

    fun get(bucketId: String): Hit? = synchronized(lock) {
        val hit = entries[bucketId] ?: return null
        val bitmap = hit.model as? Bitmap
        if (bitmap != null && bitmap.isRecycled) {
            entries.remove(bucketId)
            return null
        }
        hit
    }

    fun put(
        bucketId: String,
        model: Any,
        isVideo: Boolean,
        cacheKey: String,
        revision: Long,
    ) {
        if (!model.isPaintableAlbumCoverModel()) return
        if (model is Bitmap && (model.isRecycled || model.width <= 0 || model.height <= 0)) return
        synchronized(lock) {
            entries[bucketId] = Hit(
                model = model,
                isVideo = isVideo,
                cacheKey = cacheKey,
                revision = revision,
                empty = false,
            )
            trimLocked()
        }
    }

    fun markUnresolved(bucketId: String, revision: Long) {
        synchronized(lock) {
            val current = entries[bucketId]
            if (current != null && !current.empty && current.model.isPaintableAlbumCoverModel()) return
            entries[bucketId] = Hit(
                model = null,
                isVideo = false,
                cacheKey = bucketId,
                revision = revision,
                empty = true,
            )
            trimLocked()
        }
    }

    fun drop(bucketIds: Collection<String>) {
        if (bucketIds.isEmpty()) return
        synchronized(lock) {
            bucketIds.forEach { entries.remove(it) }
        }
    }

    fun clearForTest() {
        synchronized(lock) { entries.clear() }
        maxBitmapBytes = DEFAULT_BITMAP_BYTES
    }

    private fun trimLocked() {
        while (entries.size > MAX_ENTRIES) {
            val eldest = entries.entries.firstOrNull() ?: break
            entries.remove(eldest.key)
        }
        var bitmapBytes = 0L
        val bitmapKeysEldestFirst = ArrayList<String>()
        for ((key, hit) in entries) {
            val bitmap = hit.model as? Bitmap ?: continue
            bitmapBytes += bitmap.byteCount.toLong()
            bitmapKeysEldestFirst.add(key)
        }
        for (key in bitmapKeysEldestFirst) {
            if (bitmapBytes <= maxBitmapBytes) break
            val bitmap = entries[key]?.model as? Bitmap ?: continue
            bitmapBytes -= bitmap.byteCount.toLong()
            entries.remove(key)
        }
    }
}

/** Uma fatia do large heap para as capas já decodificadas. O JPEG de cada álbum fica no disco. */
internal fun albumCoverCacheBytes(largeHeapMb: Int): Int {
    val mb = (largeHeapMb / 6).coerceIn(48, 96)
    return mb * 1024 * 1024
}

/** URI e poster de vídeo ainda não decodificado não podem ir para o Coil. */
internal fun Any?.isPaintableAlbumCoverModel(): Boolean {
    if (this == null || this is Uri || this is DeviceVideoCover) return false
    if (asDeviceVideoCover() != null) return false
    return true
}

/** Cinza 96px ou stub do sync: não é foto de capa. */
internal fun Any?.isBlankSyncedCover(
    mediaCacheRepository: com.onegallery.sync.data.local.MediaCacheRepository,
): Boolean {
    val file = this as? java.io.File ?: return false
    return mediaCacheRepository.isSyncStubPlaceholderThumbnail(file) ||
        mediaCacheRepository.isSyncFailurePlaceholderThumbnail(file)
}

/** Primeiro quadro da célula: capa já vista, senão arquivo estável. Nunca URI. */
internal fun albumCoverDisplaySeed(cached: Any?, fallback: Any?): Any? =
    cached?.takeIf { it.isPaintableAlbumCoverModel() }
        ?: fallback?.takeIf { it.isPaintableAlbumCoverModel() }

/**
 * Falha ou URI não apagam uma capa que já estava na tela.
 * O modelo novo só substitui quando ele mesmo pode ser pintado.
 */
internal fun nextAlbumCoverModel(current: Any?, resolved: Any?, fallback: Any?): Any? {
    if (resolved.isPaintableAlbumCoverModel()) return resolved
    if (current.isPaintableAlbumCoverModel()) return current
    return fallback?.takeIf { it.isPaintableAlbumCoverModel() }
}
