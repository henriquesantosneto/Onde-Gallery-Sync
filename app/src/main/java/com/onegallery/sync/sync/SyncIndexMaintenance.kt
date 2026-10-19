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
    seenLocalMediaIds: Set<Long>,
    remoteCanonicalPaths: Set<String>,
    remoteListingAvailable: Boolean,
): IndexSanitizeResult {
    fun rowStillOnDevice(row: SyncMetadataEntity): Boolean {
        val id = row.localMediaId ?: return false
        return row.canonicalPath in seenLocalCanonicalPaths || id in seenLocalMediaIds
    }
    if (syncMode == SyncMode.LOCAL_ONLY) {
        val purged = purgeOrphans(
            dao = dao,
            mediaCacheRepository = mediaCacheRepository,
            keep = { row, _ -> rowStillOnDevice(row) },
        )
        val dupes = removeDuplicateMetadataRows(dao, mediaCacheRepository)
        return purged.copy(removedRows = purged.removedRows + dupes)
    }
    if (!remoteListingAvailable) {
        val dupes = removeDuplicateMetadataRows(dao, mediaCacheRepository)
        return IndexSanitizeResult(removedRows = dupes, fullRebuild = false)
    }
    val purged = purgeOrphans(
        dao = dao,
        mediaCacheRepository = mediaCacheRepository,
        keep = { row, _ ->
            rowStillOnDevice(row) || row.canonicalPath in remoteCanonicalPaths
        },
    )
    val dupes = removeDuplicateMetadataRows(dao, mediaCacheRepository)
    return purged.copy(removedRows = purged.removedRows + dupes)
}

/** Mesma mídia indexada duas vezes (caminho antigo/novo ou nuvem + local). */
suspend fun removeDuplicateMetadataRows(
    dao: SyncMetadataDao,
    mediaCacheRepository: MediaCacheRepository,
): Int {
    val rows = dao.getAll()
    if (rows.size <= 1) return 0
    val localSeen = mutableSetOf<Long>()
    val cloudSeen = mutableSetOf<String>()
    val pathSeen = mutableSetOf<String>()
    fun score(row: SyncMetadataEntity): Long {
        var s = 0L
        if (row.localMediaId != null) s += 1_000_000_000L
        if (!row.localContentUri.isNullOrBlank()) s += 100_000_000L
        if (!row.cachedFilePath.isNullOrBlank()) s += 10_000_000L
        s += row.localModifiedAt ?: row.remoteModifiedAt ?: 0L
        return s
    }
    val sorted = rows.sortedByDescending { score(it) }
    val keep = mutableSetOf<String>()
    for (row in sorted) {
        val duplicate = row.localMediaId?.let { it in localSeen } == true ||
            row.oneDriveItemId?.let { it in cloudSeen } == true ||
            row.canonicalPath in pathSeen
        if (duplicate) continue
        row.localMediaId?.let { localSeen.add(it) }
        row.oneDriveItemId?.let { cloudSeen.add(it) }
        pathSeen.add(row.canonicalPath)
        keep.add(row.canonicalPath)
    }
    var removed = 0
    for (row in rows) {
        if (row.canonicalPath in keep) continue
        dao.deleteByCanonicalPath(row.canonicalPath)
        mediaCacheRepository.deleteCached(row.canonicalPath)
        removed++
    }
    return removed
}

private suspend fun purgeOrphans(
    dao: SyncMetadataDao,
    mediaCacheRepository: MediaCacheRepository,
    keep: (SyncMetadataEntity, Set<String>) -> Boolean,
): IndexSanitizeResult {
    val rows = dao.getAll()
    var removed = 0
    rows.forEachIndexed { index, row ->
        if (index % 48 == 0) {
            kotlinx.coroutines.yield()
        }
        if (keep(row, emptySet())) return@forEachIndexed
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
