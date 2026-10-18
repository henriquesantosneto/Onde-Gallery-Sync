package com.onegallery.sync.sync

import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.db.SyncMetadataDao
import com.onegallery.sync.data.local.db.SyncMetadataEntity

data class IndexSanitizeResult(
    val removedRows: Int,
    val fullRebuild: Boolean,
)

/**
 * Remove entradas órfãs do índice (fora do aparelho e fora da pasta na nuvem atual).
 */
suspend fun sanitizeSyncMetadataIndex(
    syncMode: SyncMode,
    dao: SyncMetadataDao,
    mediaCacheRepository: MediaCacheRepository,
    seenLocalCanonicalPaths: Set<String>,
    remoteCanonicalPaths: Set<String>,
    remoteListingAvailable: Boolean,
): IndexSanitizeResult {
    if (syncMode == SyncMode.LOCAL_ONLY) {
        return purgeOrphans(
            dao = dao,
            mediaCacheRepository = mediaCacheRepository,
            keep = { row, _ ->
                row.localMediaId != null && row.canonicalPath in seenLocalCanonicalPaths
            },
        )
    }
    if (!remoteListingAvailable) {
        return IndexSanitizeResult(removedRows = 0, fullRebuild = false)
    }
    return purgeOrphans(
        dao = dao,
        mediaCacheRepository = mediaCacheRepository,
        keep = { row, _ ->
            val onDevice = row.localMediaId != null && row.canonicalPath in seenLocalCanonicalPaths
            val onRemote = row.canonicalPath in remoteCanonicalPaths
            onDevice || onRemote
        },
    )
}

private suspend fun purgeOrphans(
    dao: SyncMetadataDao,
    mediaCacheRepository: MediaCacheRepository,
    keep: (SyncMetadataEntity, Set<String>) -> Boolean,
): IndexSanitizeResult {
    val rows = dao.getAll()
    var removed = 0
    rows.forEach { row ->
        if (keep(row, emptySet())) return@forEach
        dao.deleteByCanonicalPath(row.canonicalPath)
        mediaCacheRepository.deleteCached(row.canonicalPath)
        removed++
    }
    return IndexSanitizeResult(removedRows = removed, fullRebuild = false)
}

suspend fun rebuildSyncMetadataIndex(
    dao: SyncMetadataDao,
    mediaCacheRepository: MediaCacheRepository,
) {
    dao.deleteAll()
    mediaCacheRepository.clearAll()
}
