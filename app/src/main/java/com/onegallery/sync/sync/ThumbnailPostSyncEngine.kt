package com.onegallery.sync.sync

import com.onegallery.sync.data.local.db.SyncMetadataEntity
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlinx.coroutines.ensureActive

/** Política de levas / limites / desistência do prefetch massivo pós-sync e em 2º plano. */
internal interface ThumbnailPostSyncHost {
    suspend fun countIndexedMissingThumbnails(): Int
    suspend fun buildMissingThumbnailBatch(batchSize: Int): List<SyncMetadataEntity>
    suspend fun warmLocalItemsForThumbnailPass()
    fun onBulkThumbnailPrefetchDepthChanged(delta: Int)
    fun refreshThumbnailPrefetchUiFlag()
    fun shouldPublishSyncActivityUi(): Boolean
    suspend fun publishFullPendingThumbnailQueueToUi(): Int
    suspend fun trimThumbnailRunQueueUi(): Int
    fun isSyncCancelRequested(): Boolean
    suspend fun ensureNotCancelled()
    suspend fun ensureSyncMayContinue()
    fun isRunQueueEmpty(): Boolean
    suspend fun setSyncProgress(
        phase: SyncProgressPhase,
        headline: String,
        detail: String,
        alsoUpdateLastMessage: Boolean,
    )
    fun thumbnailPrefetchProgressDetailSuffix(localSourcesOnly: Boolean, cloudSourcesOnly: Boolean): String
    suspend fun flushPendingAlbumCoverRevisions()
    fun cancelBulkGalleryDisplayRefreshJob()
    suspend fun notifyGalleryDisplayRefresh()
    suspend fun refreshThumbnailCacheStats(force: Boolean)
    suspend fun clearRunQueueAndActiveTransfers()
    suspend fun giveUpStubbornThumbnailsBatch(maxItems: Int): Int
}

internal class ThumbnailPostSyncEngine(
    private val host: ThumbnailPostSyncHost,
    private val runner: ThumbnailPrefetchRunner,
) {
    /**
     * Pós-sync: esgota miniaturas antes de GPS/datas.
     * @return quantas ainda faltam (0 = índice de preview completo o suficiente para seguir).
     */
    suspend fun runCompleteBeforeMetadata(): Int {
        var stalledPasses = 0
        var pass = 0
        while (pass < THUMB_POST_SYNC_OUTER_PASSES) {
            host.ensureNotCancelled()
            val pending = host.countIndexedMissingThumbnails()
            if (pending == 0) return 0
            host.setSyncProgress(
                SyncProgressPhase.PREFETCH_THUMBNAILS,
                "Completando cache da galeria…",
                "~$pending item(ns) sem JPEG salvo no app · leva ${pass + 1}. " +
                    "Fotos no aparelho já aparecem na grade; aqui falta sobretudo nuvem e índice.",
                alsoUpdateLastMessage = false,
            )
            val before = pending
            prefetchMissingThumbnails(
                completeAllPending = false,
                maxTotalPerRun = THUMB_POST_SYNC_MAX_TOTAL_PER_PASS,
                progressHeadline = "Completando cache da galeria…",
            )
            forcePlaceholderForStubbornMissingThumbnails()
            val after = host.countIndexedMissingThumbnails()
            if (after == 0) return 0
            if (after >= before - THUMB_STALL_MIN_PROGRESS) {
                stalledPasses++
                if (stalledPasses >= THUMB_POST_SYNC_MAX_STALL_PASSES) {
                    forcePlaceholderForStubbornMissingThumbnails()
                    host.clearRunQueueAndActiveTransfers()
                    return host.countIndexedMissingThumbnails()
                }
            } else {
                stalledPasses = 0
            }
            pass++
        }
        forcePlaceholderForStubbornMissingThumbnails()
        host.clearRunQueueAndActiveTransfers()
        return host.countIndexedMissingThumbnails()
    }

    /** Miniaturas faltantes em levas; limite por execução para não travar GPS/resto do app. */
    suspend fun prefetchMissingThumbnails(
        batchSize: Int = THUMB_PREFETCH_BATCH,
        honorUserSyncCancel: Boolean = true,
        maxTotalPerRun: Int = THUMB_PREFETCH_MAX_TOTAL_PER_SYNC,
        completeAllPending: Boolean = false,
        parallelSlots: Int = PARALLEL_THUMBNAILS,
        progressHeadline: String = "Completando cache da galeria…",
    ) {
        val pendingAtStart = host.countIndexedMissingThumbnails()
        if (pendingAtStart == 0) return
        host.warmLocalItemsForThumbnailPass()
        host.onBulkThumbnailPrefetchDepthChanged(+1)
        host.refreshThumbnailPrefetchUiFlag()
        if (host.shouldPublishSyncActivityUi()) {
            host.publishFullPendingThumbnailQueueToUi()
        }
        var totalCached = 0
        var round = 0
        var consecutiveEmptyBatches = 0
        var remainingEstimate = pendingAtStart
        val roundsNeeded = (pendingAtStart + batchSize - 1) / batchSize
        val maxRounds = if (completeAllPending) {
            min(
                THUMB_POST_SYNC_COMPLETION_MAX_ROUNDS,
                roundsNeeded + THUMB_PREFETCH_EXTRA_ROUNDS,
            )
        } else {
            min(
                THUMB_PREFETCH_MAX_ROUNDS,
                roundsNeeded + THUMB_PREFETCH_EXTRA_ROUNDS,
            )
        }
        val totalCap = if (completeAllPending) Int.MAX_VALUE / 4 else maxTotalPerRun
        val emptyBatchGiveUp = if (completeAllPending) 8 else 5
        var stoppedByCap = false
        try {
            while (round < maxRounds) {
                if (host.isSyncCancelRequested()) break
                if (honorUserSyncCancel) {
                    host.ensureNotCancelled()
                    host.ensureSyncMayContinue()
                } else {
                    coroutineContext.ensureActive()
                    host.ensureSyncMayContinue()
                }
                if (totalCached >= totalCap) {
                    stoppedByCap = true
                    break
                }
                if (round == 0 || round % 4 == 0) {
                    remainingEstimate = host.countIndexedMissingThumbnails()
                }
                if (remainingEstimate == 0) break
                if (host.isRunQueueEmpty() && host.countIndexedMissingThumbnails() > 0) {
                    host.publishFullPendingThumbnailQueueToUi()
                }
                var rows = host.buildMissingThumbnailBatch(batchSize)
                if (rows.isEmpty() && host.countIndexedMissingThumbnails() > 0) {
                    val gaveUp = host.giveUpStubbornThumbnailsBatch(THUMB_STUBBORN_PLACEHOLDER_CAP)
                    if (gaveUp > 0) {
                        consecutiveEmptyBatches = 0
                        round++
                        continue
                    }
                }
                if (rows.isEmpty()) {
                    consecutiveEmptyBatches++
                    if (consecutiveEmptyBatches >= emptyBatchGiveUp) break
                    round++
                    continue
                }
                if (host.shouldPublishSyncActivityUi()) {
                    host.setSyncProgress(
                        SyncProgressPhase.PREFETCH_THUMBNAILS,
                        progressHeadline,
                        "${totalCached.coerceAtLeast(0)} gravadas · ~$remainingEstimate ainda · " +
                            "leva ${round + 1}/$maxRounds. " +
                            host.thumbnailPrefetchProgressDetailSuffix(
                                localSourcesOnly = false,
                                cloudSourcesOnly = false,
                            ),
                        alsoUpdateLastMessage = false,
                    )
                }
                val batchCached = runner.runMissingThumbnailPrefetch(
                    rows,
                    honorSyncCancel = honorUserSyncCancel,
                    parallelSlots = parallelSlots,
                    progressHeadline = progressHeadline,
                )
                totalCached += batchCached
                remainingEstimate = (remainingEstimate - batchCached).coerceAtLeast(0)
                round++
                if (batchCached == 0) {
                    consecutiveEmptyBatches++
                    if (consecutiveEmptyBatches >= emptyBatchGiveUp) break
                } else {
                    consecutiveEmptyBatches = 0
                }
            }
            val remaining = host.countIndexedMissingThumbnails()
            if (host.shouldPublishSyncActivityUi() && (totalCached > 0 || remaining > 0 || stoppedByCap)) {
                host.setSyncProgress(
                    SyncProgressPhase.PREFETCH_THUMBNAILS,
                    "Miniaturas",
                    when {
                        remaining == 0 -> "$totalCached gerada(s)/baixada(s); índice completo."
                        completeAllPending && remaining > 0 ->
                            "$totalCached nesta leva · ~$remaining ainda — continuando antes do GPS…"
                        stoppedByCap ->
                            "$totalCached nesta sync · ~$remaining ainda pendentes " +
                                "(limite por passagem — sincronize de novo para continuar)."
                        else -> "$totalCached nesta sync · $remaining ainda sem miniatura (falha ou cancelamento)."
                    },
                    alsoUpdateLastMessage = true,
                )
            }
            host.flushPendingAlbumCoverRevisions()
            host.cancelBulkGalleryDisplayRefreshJob()
            if (totalCached > 0) {
                host.notifyGalleryDisplayRefresh()
            }
            forcePlaceholderForStubbornMissingThumbnails()
            host.refreshThumbnailCacheStats(force = true)
        } finally {
            host.onBulkThumbnailPrefetchDepthChanged(-1)
            host.refreshThumbnailPrefetchUiFlag()
        }
    }

    private suspend fun forcePlaceholderForStubbornMissingThumbnails() {
        if (host.giveUpStubbornThumbnailsBatch(THUMB_STUBBORN_PLACEHOLDER_CAP) > 0) {
            host.notifyGalleryDisplayRefresh()
        }
    }

    companion object {
        const val THUMB_PREFETCH_BATCH = 512
        const val THUMB_PREFETCH_EXTRA_ROUNDS = 12
        const val THUMB_PREFETCH_MAX_ROUNDS = 48
        const val THUMB_PREFETCH_MAX_TOTAL_PER_SYNC = 6_000
        const val THUMB_POST_SYNC_COMPLETION_MAX_ROUNDS = 96
        const val THUMB_POST_SYNC_OUTER_PASSES = 2
        const val THUMB_POST_SYNC_MAX_STALL_PASSES = 2
        const val THUMB_POST_SYNC_MAX_TOTAL_PER_PASS = 3_000
        const val THUMB_STALL_MIN_PROGRESS = 32
        const val THUMB_STUBBORN_PLACEHOLDER_CAP = 64
        const val PARALLEL_THUMBNAILS = 5
    }
}
