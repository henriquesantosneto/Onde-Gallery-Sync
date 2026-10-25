package com.onegallery.sync.sync

import com.onegallery.sync.data.local.db.SyncMetadataDao
import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.ui.gallery.AlbumCoverThumbnailPlanner
import com.onegallery.sync.ui.gallery.GalleryMapper
import kotlinx.coroutines.yield
internal interface ThumbnailSyncPipelineHost {
    suspend fun ensureNotCancelled()
    suspend fun setSyncProgress(
        phase: SyncProgressPhase,
        headline: String,
        detail: String,
        alsoUpdateLastMessage: Boolean,
    )
    suspend fun publishFullPendingThumbnailQueueToUi(): Int
    suspend fun trimThumbnailRunQueueUi(): Int
    fun clearActiveSyncItems()
    suspend fun notifyGalleryDisplayRefresh()
    suspend fun warmLocalItemsForThumbnailPass()
    suspend fun ensureAllLocalMediaPresentInIndex()
    suspend fun <T> withThumbnailPrefetchUi(block: suspend () -> T): T
    suspend fun runMissingThumbnailPrefetch(
        rows: List<SyncMetadataEntity>,
        honorSyncCancel: Boolean,
        parallelSlots: Int,
        localSourcesOnly: Boolean,
        cloudSourcesOnly: Boolean,
        progressHeadline: String,
        publishUi: Boolean,
    ): Int
    fun isOnline(): Boolean
    fun isOneDriveGraphMode(): Boolean
}

/** Levas de prefetch durante o sync (local → capas → nuvem → não-visuais). */
internal class ThumbnailSyncPipeline(
    private val syncMetadataDao: SyncMetadataDao,
    private val mediaCacheRepository: com.onegallery.sync.data.local.MediaCacheRepository,
    private val thumbnails: GalleryThumbnailService,
    private val host: ThumbnailSyncPipelineHost,
) {
    suspend fun ensureLocalMediaThumbnailsEarly(
        maxPerRun: Int = LOCAL_THUMB_EARLY_POST_SYNC_MAX,
        showProgress: Boolean = true,
    ) {
        host.ensureNotCancelled()
        if (showProgress) {
            host.setSyncProgress(
                SyncProgressPhase.PREFETCH_THUMBNAILS,
                "Miniaturas locais no cache…",
                "Procurando itens do aparelho sem preview salvo…",
                alsoUpdateLastMessage = false,
            )
        }
        yield()
        val rows = syncMetadataDao.getAll()
            .asSequence()
            .filter { row ->
                thumbnails.canBuildThumbnail(row) &&
                    thumbnails.hasLocalThumbnailSource(row) &&
                    thumbnails.needsThumbnailPrefetch(row)
            }
            .sortedWith(missingThumbnailPrefetchComparator)
            .take(maxPerRun)
            .toList()
        if (rows.isEmpty()) {
            if (showProgress) {
                host.setSyncProgress(
                    SyncProgressPhase.PREFETCH_THUMBNAILS,
                    "Miniaturas locais",
                    "Nada pendente no aparelho · seguindo…",
                    alsoUpdateLastMessage = false,
                )
            }
            return
        }
        host.warmLocalItemsForThumbnailPass()
        if (showProgress) {
            val videoCount = rows.count { GalleryMapper.isVideoFileName(it.displayName) }
            host.setSyncProgress(
                SyncProgressPhase.PREFETCH_THUMBNAILS,
                "Miniaturas locais…",
                buildString {
                    append("${rows.size} no aparelho")
                    if (videoCount > 0) append(" ($videoCount vídeo(s))")
                    append(" — funciona offline; nuvem vem na leva seguinte.")
                },
                alsoUpdateLastMessage = false,
            )
        }
        val cached = host.withThumbnailPrefetchUi {
            host.runMissingThumbnailPrefetch(
                rows,
                honorSyncCancel = true,
                parallelSlots = PARALLEL_THUMB_PREFETCH,
                localSourcesOnly = true,
                cloudSourcesOnly = false,
                progressHeadline = "Miniaturas locais no cache…",
                publishUi = showProgress,
            )
        }
        if (showProgress) {
            host.clearActiveSyncItems()
            host.trimThumbnailRunQueueUi()
            host.setSyncProgress(
                SyncProgressPhase.PREFETCH_THUMBNAILS,
                "Miniaturas locais concluídas",
                "$cached JPEG gravado(s) de ${rows.size} · seguindo para OneDrive/capas…",
                alsoUpdateLastMessage = false,
            )
        }
        host.notifyGalleryDisplayRefresh()
    }

    suspend fun ensureCloudThumbnailsPrefetch(
        maxPerRun: Int = CLOUD_THUMB_PREFETCH_MAX_PER_PASS,
    ) {
        host.ensureNotCancelled()
        if (!host.isOnline() || !host.isOneDriveGraphMode()) return
        host.setSyncProgress(
            SyncProgressPhase.PREFETCH_THUMBNAILS,
            "Preparando fila OneDrive…",
            "Varrendo o índice (pode levar um instante em bibliotecas grandes)…",
            alsoUpdateLastMessage = false,
        )
        yield()
        val rows = syncMetadataDao.getAll()
            .asSequence()
            .filter { row ->
                !row.oneDriveItemId.isNullOrBlank() &&
                    thumbnails.canBuildThumbnail(row) &&
                    thumbnails.needsThumbnailPrefetch(row)
            }
            .sortedWith(missingThumbnailPrefetchComparator)
            .take(maxPerRun)
            .toList()
        if (rows.isEmpty()) return
        host.setSyncProgress(
            SyncProgressPhase.PREFETCH_THUMBNAILS,
            "Miniaturas do OneDrive…",
            "${rows.size} na nuvem · Graph ou amostra do vídeo · grade usa cache.",
            alsoUpdateLastMessage = false,
        )
        val cached = host.withThumbnailPrefetchUi {
            host.runMissingThumbnailPrefetch(
                rows,
                honorSyncCancel = true,
                parallelSlots = PARALLEL_THUMB_PREFETCH,
                localSourcesOnly = false,
                cloudSourcesOnly = true,
                progressHeadline = "Miniaturas do OneDrive…",
                publishUi = true,
            )
        }
        host.clearActiveSyncItems()
        host.trimThumbnailRunQueueUi()
        host.setSyncProgress(
            SyncProgressPhase.PREFETCH_THUMBNAILS,
            "Leva OneDrive concluída",
            "$cached preview(s) nesta passagem · completando restante do índice em seguida…",
            alsoUpdateLastMessage = false,
        )
        host.notifyGalleryDisplayRefresh()
    }

    /** Local + Graph no mesmo item (vídeo sync que falhou no aparelho tenta OneDrive). */
    suspend fun ensureUnifiedGalleryThumbnailPrefetch(
        maxPerRun: Int = SyncThumbnailOrchestrator.UNIFIED_PREFETCH_MAX_PER_PASS,
    ) {
        host.ensureNotCancelled()
        if (!host.isOnline() || !host.isOneDriveGraphMode()) return
        host.setSyncProgress(
            SyncProgressPhase.PREFETCH_THUMBNAILS,
            "Miniaturas na galeria…",
            "Nuvem e sincronizados · local + Graph por item.",
            alsoUpdateLastMessage = false,
        )
        yield()
        val rows = syncMetadataDao.getAll()
            .asSequence()
            .filter { row ->
                thumbnails.canBuildThumbnail(row) &&
                    thumbnails.needsThumbnailPrefetch(row)
            }
            .sortedWith(missingThumbnailPrefetchComparator)
            .take(maxPerRun)
            .toList()
        if (rows.isEmpty()) {
            host.setSyncProgress(
                SyncProgressPhase.PREFETCH_THUMBNAILS,
                "Miniaturas",
                "Nenhum item pendente nesta leva unificada.",
                alsoUpdateLastMessage = false,
            )
            return
        }
        val cloudCount = rows.count { !it.oneDriveItemId.isNullOrBlank() }
        val localCount = rows.count { thumbnails.hasLocalThumbnailSource(it) }
        host.setSyncProgress(
            SyncProgressPhase.PREFETCH_THUMBNAILS,
            "Miniaturas na galeria…",
            buildString {
                append("${rows.size} pendente(s)")
                if (cloudCount > 0) append(" · $cloudCount com OneDrive")
                if (localCount > 0) append(" · $localCount com arquivo local")
                append(" — prioridade vídeo e recentes.")
            },
            alsoUpdateLastMessage = false,
        )
        val cached = host.withThumbnailPrefetchUi {
            host.runMissingThumbnailPrefetch(
                rows,
                honorSyncCancel = true,
                parallelSlots = ThumbnailPrefetchRunner.adaptiveParallelSlots(PARALLEL_THUMB_PREFETCH),
                localSourcesOnly = false,
                cloudSourcesOnly = false,
                progressHeadline = "Miniaturas na galeria…",
                publishUi = true,
            )
        }
        host.clearActiveSyncItems()
        host.trimThumbnailRunQueueUi()
        host.setSyncProgress(
            SyncProgressPhase.PREFETCH_THUMBNAILS,
            "Leva unificada concluída",
            "$cached JPEG nesta passagem · completando restante do índice…",
            alsoUpdateLastMessage = false,
        )
        host.notifyGalleryDisplayRefresh()
    }

    suspend fun ensureAlbumCoverThumbnails(parallelThumbnails: Int) {
        host.ensureNotCancelled()
        val paths = AlbumCoverThumbnailPlanner.coverPathsNeedingThumbnail(
            syncMetadataDao.getAll(),
            mediaCacheRepository,
        )
        if (paths.isEmpty()) return
        val rows = paths.mapNotNull { syncMetadataDao.getByCanonicalPath(it) }
        if (rows.isEmpty()) return
        host.setSyncProgress(
            SyncProgressPhase.PREFETCH_THUMBNAILS,
            "Capas dos álbuns…",
            "${paths.size} álbum(s) sem capa · 1 miniatura prioritária cada.",
            alsoUpdateLastMessage = false,
        )
        host.withThumbnailPrefetchUi {
            host.runMissingThumbnailPrefetch(
                rows,
                honorSyncCancel = true,
                parallelSlots = parallelThumbnails,
                localSourcesOnly = false,
                cloudSourcesOnly = false,
                progressHeadline = "Capas dos álbuns…",
                publishUi = true,
            )
        }
    }

    suspend fun materializeNonVisualThumbnails() {
        val rows = syncMetadataDao.getAll().filter { row ->
            mediaCacheRepository.thumbnailIfCached(row.canonicalPath, row.oneDriveItemId) == null &&
                !GalleryMapper.supportsGeneratedThumbnail(row.displayName) &&
                (
                    row.oneDriveItemId != null || row.localMediaId != null ||
                        !row.localContentUri.isNullOrBlank() || !row.cachedFilePath.isNullOrBlank()
                    )
        }
        if (rows.isEmpty()) return
        rows.forEach { row ->
            thumbnails.persistMinimalPlaceholderThumbnail(row)
        }
    }

    suspend fun giveUpStubbornThumbnailsBatch(
        maxItems: Int,
        onPlaceholderSaved: (SyncMetadataEntity) -> Unit,
    ): Int {
        val stubborn = syncMetadataDao.getAll()
            .asSequence()
            .filter { row -> thumbnails.needsThumbnailPrefetch(row) }
            .filter { row ->
                GalleryMapper.isVideoFileName(row.displayName) ||
                    !thumbnails.hasLocalThumbnailSource(row)
            }
            .sortedWith(missingThumbnailPrefetchComparator)
            .take(maxItems)
            .toList()
        if (stubborn.isEmpty()) return 0
        var saved = 0
        stubborn.forEach { row ->
            thumbnails.persistMinimalPlaceholderThumbnail(row)?.let {
                onPlaceholderSaved(row)
                saved++
            }
        }
        return saved
    }

    companion object {
        const val LOCAL_THUMB_EARLY_DURING_SYNC_MAX = 4_000
        const val LOCAL_THUMB_EARLY_POST_SYNC_MAX = 6_000
        const val PARALLEL_THUMB_PREFETCH = 8
        const val CLOUD_THUMB_PREFETCH_MAX_PER_PASS = 2_000
    }
}
