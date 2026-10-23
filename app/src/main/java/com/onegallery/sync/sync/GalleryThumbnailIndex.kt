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

    suspend fun buildMissingBatch(maxPerRun: Int): List<SyncMetadataEntity> {
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
        val cursor = syncPreferences.thumbPrefetchCursor % missing.size
        val rotated = missing.drop(cursor) + missing.take(cursor)
        val batch = rotated.take(maxPerRun)
        syncPreferences.thumbPrefetchCursor = (cursor + batch.size) % missing.size
        return batch
    }

    companion object {
        const val THUMB_RUN_QUEUE_UI_CAP = 1_500
        const val THUMB_PREFETCH_MAX_SESSION_ATTEMPTS = 8
    }
}
