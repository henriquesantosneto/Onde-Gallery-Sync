package com.onegallery.sync.sync

import com.onegallery.sync.data.local.db.SyncMetadataDao
import com.onegallery.sync.ui.gallery.AlbumCoverThumbnailPlanner
import kotlinx.coroutines.yield

/**
 * Motor único de miniaturas da galeria OneDrive: local + nuvem no mesmo fluxo,
 * prioridade vídeo/recentes, capas de álbum, esgotamento do índice.
 */
internal interface SyncGalleryThumbnailEngineHost : ThumbnailSyncPipelineHost {
    suspend fun runCompleteThumbnailPrefetchBeforeMetadata(): Int
    suspend fun runMissingThumbnailPrefetchUnified(
        rows: List<com.onegallery.sync.data.local.db.SyncMetadataEntity>,
        progressHeadline: String,
        publishUi: Boolean,
        localSourcesOnly: Boolean = false,
        cloudSourcesOnly: Boolean = false,
    ): Int
}

internal class SyncGalleryThumbnailEngine(
    private val syncMetadataDao: SyncMetadataDao,
    private val mediaCacheRepository: com.onegallery.sync.data.local.MediaCacheRepository,
    private val thumbnails: GalleryThumbnailService,
    private val host: SyncGalleryThumbnailEngineHost,
) {
    suspend fun runPostSyncGalleryThumbnails() {
        host.ensureNotCancelled()
        host.ensureAllLocalMediaPresentInIndex()
        yield()
        val missing = syncMetadataDao.getAll()
            .asSequence()
            .filter { row ->
                thumbnails.canBuildThumbnail(row) &&
                    thumbnails.needsThumbnailPrefetch(row)
            }
            .sortedWith(missingThumbnailPrefetchComparator)
            .toList()
        val localRows = missing.filter { thumbnails.hasLocalThumbnailSource(it) }
        val remoteRows = missing.filter {
            !thumbnails.hasLocalThumbnailSource(it) && !it.oneDriveItemId.isNullOrBlank()
        }
        if (localRows.isNotEmpty()) {
            host.setSyncProgress(
                SyncProgressPhase.PREFETCH_THUMBNAILS,
                "Miniaturas do aparelho…",
                "${localRows.size} no dispositivo antes da nuvem.",
                alsoUpdateLastMessage = false,
            )
            host.warmLocalItemsForThumbnailPass()
            host.withThumbnailPrefetchUi {
                host.runMissingThumbnailPrefetchUnified(
                    rows = localRows.take(LOCAL_PASS_CAP),
                    progressHeadline = "Miniaturas do aparelho…",
                    publishUi = true,
                    localSourcesOnly = true,
                )
            }
            host.clearActiveSyncItems()
            host.trimThumbnailRunQueueUi()
            host.notifyGalleryDisplayRefresh()
        }
        host.ensureNotCancelled()
        if (remoteRows.isNotEmpty() && host.isOnline() && host.isOneDriveGraphMode()) {
            host.setSyncProgress(
                SyncProgressPhase.PREFETCH_THUMBNAILS,
                "Miniaturas do OneDrive…",
                "${remoteRows.size} só na nuvem · aparelho já foi processado.",
                alsoUpdateLastMessage = false,
            )
            host.withThumbnailPrefetchUi {
                host.runMissingThumbnailPrefetchUnified(
                    rows = remoteRows.take(REMOTE_PASS_CAP),
                    progressHeadline = "Miniaturas do OneDrive…",
                    publishUi = true,
                    cloudSourcesOnly = true,
                )
            }
            host.clearActiveSyncItems()
            host.trimThumbnailRunQueueUi()
            host.notifyGalleryDisplayRefresh()
        }
        host.ensureNotCancelled()
        val coverPaths = AlbumCoverThumbnailPlanner.coverPathsNeedingThumbnail(
            syncMetadataDao.getAll(),
            mediaCacheRepository,
        )
        if (coverPaths.isNotEmpty()) {
            val coverRows = coverPaths.mapNotNull { syncMetadataDao.getByCanonicalPath(it) }
            if (coverRows.isNotEmpty()) {
                host.setSyncProgress(
                    SyncProgressPhase.PREFETCH_THUMBNAILS,
                    "Capas dos álbuns…",
                    "${coverRows.size} álbum(s) sem capa.",
                    alsoUpdateLastMessage = false,
                )
                host.withThumbnailPrefetchUi {
                    host.runMissingThumbnailPrefetchUnified(
                        rows = coverRows,
                        progressHeadline = "Capas dos álbuns…",
                        publishUi = true,
                    )
                }
            }
        }
        host.ensureNotCancelled()
        ThumbnailSyncPipeline(
            syncMetadataDao,
            mediaCacheRepository,
            thumbnails,
            host,
        ).materializeNonVisualThumbnails()
        host.publishFullPendingThumbnailQueueToUi()
        host.setSyncProgress(
            SyncProgressPhase.PREFETCH_THUMBNAILS,
            "Completando miniaturas…",
            "Restante do índice (nuvem grande continua em 2º plano).",
            alsoUpdateLastMessage = false,
        )
        host.runCompleteThumbnailPrefetchBeforeMetadata()
    }

    companion object {
        const val LOCAL_PASS_CAP = 12_000
        const val REMOTE_PASS_CAP = 8_000
        const val FIRST_PASS_CAP = LOCAL_PASS_CAP
    }
}
