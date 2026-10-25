package com.onegallery.sync.sync

import com.onegallery.sync.data.local.db.SyncMetadataDao
import com.onegallery.sync.data.local.db.SyncMetadataEntity
/** Fila / contagem de miniaturas pendentes no índice Room. */
internal class GalleryThumbnailIndex(
    private val syncMetadataDao: SyncMetadataDao,
    private val syncPreferences: SyncPreferences,
    private val thumbnails: GalleryThumbnailService,
    private val thumbPrefetchAttemptsThisSession: Map<String, Int>,
) {
    suspend fun allPendingEntitiesForUi(): List<SyncMetadataEntity> {
        return syncMetadataDao.getAll()
            .asSequence()
            .filter { row -> thumbnails.needsThumbnailPrefetch(row) }
            .sortedWith(missingThumbnailPrefetchComparator)
            .toList()
    }

    suspend fun countMissing(): Int {
        return syncMetadataDao.getAll().count { row -> thumbnails.needsThumbnailPrefetch(row) }
    }

    suspend fun buildMissingBatch(
        maxPerRun: Int,
        queuePathOrder: List<String>? = null,
    ): List<SyncMetadataEntity> {
        val missing = syncMetadataDao.getAll()
            .asSequence()
            .filter { row ->
                thumbnails.needsThumbnailPrefetch(row) &&
                    (thumbPrefetchAttemptsThisSession[row.canonicalPath] ?: 0) <
                    THUMB_PREFETCH_MAX_SESSION_ATTEMPTS
            }
            .sortedWith(missingThumbnailPrefetchComparator)
            .toList()
        if (missing.isEmpty()) return emptyList()
        if (!queuePathOrder.isNullOrEmpty()) {
            val missingByPath = missing.associateBy { it.canonicalPath }
            val fromQueue = queuePathOrder.mapNotNull { missingByPath[it] }.take(maxPerRun)
            if (fromQueue.size >= maxPerRun) return fromQueue
            val picked = fromQueue.map { it.canonicalPath }.toHashSet()
            val tail = missing.filter { it.canonicalPath !in picked }
                .take(maxPerRun - fromQueue.size)
            return fromQueue + tail
        }
        val cursor = syncPreferences.thumbPrefetchCursor % missing.size
        val rotated = missing.drop(cursor) + missing.take(cursor)
        val batch = rotated.take(maxPerRun)
        syncPreferences.thumbPrefetchCursor = (cursor + batch.size) % missing.size
        return batch
    }

    companion object {
        const val THUMB_RUN_QUEUE_UI_CAP = 48
        const val THUMB_PREFETCH_MAX_SESSION_ATTEMPTS = 8
    }
}
