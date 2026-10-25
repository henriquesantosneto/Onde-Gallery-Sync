package com.onegallery.sync.sync

/**
 * Plano pós-sync de miniaturas: offline rápido → capas → leva unificada (local + Graph)
 * → placeholders → esgotamento indexado. Mantém a galeria navegável durante o trabalho.
 */
internal interface SyncThumbnailOrchestratorHost : ThumbnailSyncPipelineHost {
    suspend fun runCompleteThumbnailPrefetchBeforeMetadata(): Int
}

internal class SyncThumbnailOrchestrator(
    private val pipeline: ThumbnailSyncPipeline,
    private val host: SyncThumbnailOrchestratorHost,
) {
    suspend fun runPostSyncThumbnailWork() {
        host.ensureNotCancelled()
        host.setSyncProgress(
            SyncProgressPhase.PREFETCH_THUMBNAILS,
            "Miniaturas locais",
            "Aparelho primeiro (offline); em seguida nuvem e sincronizados no mesmo fluxo.",
            alsoUpdateLastMessage = false,
        )
        host.ensureAllLocalMediaPresentInIndex()
        pipeline.ensureLocalMediaThumbnailsEarly(
            maxPerRun = LOCAL_OFFLINE_QUICK_MAX,
            showProgress = true,
        )
        host.ensureNotCancelled()
        pipeline.ensureAlbumCoverThumbnails(ThumbnailSyncPipeline.PARALLEL_THUMB_PREFETCH)
        host.ensureNotCancelled()
        if (host.isOnline() && host.isOneDriveGraphMode()) {
            pipeline.ensureUnifiedGalleryThumbnailPrefetch(
                maxPerRun = UNIFIED_PREFETCH_MAX_PER_PASS,
            )
        }
        host.ensureNotCancelled()
        pipeline.materializeNonVisualThumbnails()
        host.ensureNotCancelled()
        host.publishFullPendingThumbnailQueueToUi()
        host.setSyncProgress(
            SyncProgressPhase.PREFETCH_THUMBNAILS,
            "Completando índice de miniaturas…",
            "Itens só na nuvem e sincronizados — JPEG no cache para a grade.",
            alsoUpdateLastMessage = false,
        )
        host.runCompleteThumbnailPrefetchBeforeMetadata()
        host.notifyGalleryDisplayRefresh()
    }

    companion object {
        const val LOCAL_OFFLINE_QUICK_MAX = 3_000
        const val UNIFIED_PREFETCH_MAX_PER_PASS = 6_000
    }
}
