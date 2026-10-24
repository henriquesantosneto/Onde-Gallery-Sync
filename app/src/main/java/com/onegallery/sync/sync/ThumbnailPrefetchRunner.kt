package com.onegallery.sync.sync

import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.ui.gallery.GalleryMapper
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

internal interface ThumbnailPrefetchHost {
    val thumbnailPrefetchBlocking: kotlinx.coroutines.CoroutineDispatcher
    fun isSyncCancelRequested(): Boolean
    suspend fun ensureNotCancelled()
    suspend fun ensureSyncMayContinue()
    fun shouldPublishSyncActivityUi(): Boolean
    suspend fun publishFullPendingThumbnailQueueToUi(): Int
    suspend fun publishThumbnailBatchAtFront(batch: List<SyncMetadataEntity>): Int
    fun upsertActiveSyncItem(item: ActiveSyncItem, force: Boolean)
    fun removeActiveSyncItem(canonicalPath: String)
    fun removeFromRunQueue(canonicalPath: String)
    suspend fun setSyncProgress(
        phase: SyncProgressPhase,
        headline: String,
        detail: String,
        alsoUpdateLastMessage: Boolean = true,
    )
    suspend fun missingThumbnailCountForProgress(): Int
    fun thumbnailOperationLabel(row: SyncMetadataEntity): String
    fun activeSyncProgressCallback(
        row: SyncMetadataEntity,
        operationLabel: String,
    ): ((Long, Long?) -> Unit)?
    fun thumbnailPrefetchProgressDetailSuffix(localSourcesOnly: Boolean, cloudSourcesOnly: Boolean): String
    suspend fun ensureThumbnailCached(
        canonicalPath: String,
        onProgress: ((Long, Long?) -> Unit)?,
        lightweightPrefetch: Boolean,
        localSourcesOnly: Boolean,
        cloudSourcesOnly: Boolean,
        cloudVideoAggressive: Boolean,
    ): Result<File?>
    fun rowHasGalleryThumbnail(row: SyncMetadataEntity): Boolean
    fun incrementThumbnailFailure(canonicalPath: String)
    fun recordThumbPrefetchAttempt(canonicalPath: String)
    fun clearThumbPrefetchAttempt(canonicalPath: String)
}

internal class ThumbnailPrefetchRunner(
    private val host: ThumbnailPrefetchHost,
) {
    suspend fun runMissingThumbnailPrefetch(
        rows: List<SyncMetadataEntity>,
        honorSyncCancel: Boolean,
        parallelSlots: Int = PARALLEL_THUMBNAILS,
        localSourcesOnly: Boolean = false,
        cloudSourcesOnly: Boolean = false,
        progressHeadline: String = "Completando cache da galeria…",
        publishUi: Boolean = true,
    ): Int {
        if (rows.isEmpty()) return 0
        if (honorSyncCancel && host.isSyncCancelRequested()) return 0
        val globalTotalAtStart = if (publishUi && host.shouldPublishSyncActivityUi()) {
            host.publishThumbnailBatchAtFront(rows).coerceAtLeast(rows.size)
        } else {
            rows.size
        }
        var total = 0
        val chunks = rows.chunked(THUMB_PREFETCH_RUN_CHUNK)
        chunks.forEachIndexed { index, chunk ->
            if (honorSyncCancel && host.isSyncCancelRequested()) return total
            total += runMissingThumbnailPrefetchChunk(
                chunk,
                honorSyncCancel,
                parallelSlots,
                localSourcesOnly,
                cloudSourcesOnly,
                progressHeadline,
                publishUi,
                globalTotalAtStart = globalTotalAtStart,
                chunkIndex = index,
                chunkCount = chunks.size,
            )
            if (publishUi && host.shouldPublishSyncActivityUi() && index < chunks.lastIndex) {
                val remaining = host.missingThumbnailCountForProgress()
                val done = (globalTotalAtStart - remaining).coerceIn(0, globalTotalAtStart)
                host.setSyncProgress(
                    SyncProgressPhase.PREFETCH_THUMBNAILS,
                    progressHeadline,
                    "$done/$globalTotalAtStart · faltam ~$remaining · leva ${index + 1}/${chunks.size} ok · " +
                        "preparando próxima…",
                    alsoUpdateLastMessage = false,
                )
            }
        }
        return total
    }

    private fun thumbPrefetchItemTimeoutMs(
        row: SyncMetadataEntity,
        cloudSourcesOnly: Boolean,
        localSourcesOnly: Boolean,
    ): Long {
        when {
            cloudSourcesOnly -> return CLOUD_VIDEO_THUMB_FETCH_TIMEOUT_MS
            localSourcesOnly && GalleryMapper.isVideoFileName(row.displayName) ->
                return THUMB_PREFETCH_LOCAL_VIDEO_TIMEOUT_MS
            GalleryMapper.isVideoFileName(row.displayName) ->
                return THUMB_PREFETCH_VIDEO_TIMEOUT_MS
        }
        return THUMB_PREFETCH_ITEM_TIMEOUT_MS
    }

    private suspend fun runMissingThumbnailPrefetchChunk(
        rows: List<SyncMetadataEntity>,
        honorSyncCancel: Boolean,
        parallelSlots: Int,
        localSourcesOnly: Boolean,
        cloudSourcesOnly: Boolean,
        progressHeadline: String,
        publishUi: Boolean,
        globalTotalAtStart: Int,
        chunkIndex: Int,
        chunkCount: Int,
    ): Int {
        if (rows.isEmpty()) return 0
        val slots = parallelSlots.coerceIn(1, PARALLEL_THUMBNAILS)
        val semaphore = Semaphore(slots)
        val completed = AtomicInteger(0)
        val cachedCount = AtomicInteger(0)
        return supervisorScope {
            rows.map { row ->
                async {
                    semaphore.withPermit {
                        try {
                            if (honorSyncCancel) {
                                coroutineContext.ensureActive()
                                host.ensureNotCancelled()
                                host.ensureSyncMayContinue()
                            }
                            if (publishUi && host.shouldPublishSyncActivityUi()) {
                                host.upsertActiveSyncItem(
                                    ActiveSyncItem(
                                        canonicalPath = row.canonicalPath,
                                        displayName = row.displayName,
                                        operationLabel = host.thumbnailOperationLabel(row),
                                    ),
                                    force = true,
                                )
                            }
                            try {
                                val itemTimeoutMs = thumbPrefetchItemTimeoutMs(
                                    row,
                                    cloudSourcesOnly,
                                    localSourcesOnly,
                                )
                                withContext(host.thumbnailPrefetchBlocking) {
                                    withTimeout(itemTimeoutMs) {
                                        host.ensureThumbnailCached(
                                            row.canonicalPath,
                                            host.activeSyncProgressCallback(
                                                row,
                                                host.thumbnailOperationLabel(row),
                                            ),
                                            lightweightPrefetch = !localSourcesOnly && !cloudSourcesOnly,
                                            localSourcesOnly = localSourcesOnly,
                                            cloudSourcesOnly = cloudSourcesOnly,
                                            cloudVideoAggressive = cloudSourcesOnly &&
                                                GalleryMapper.isVideoFileName(row.displayName),
                                        )
                                    }
                                }
                            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                                host.incrementThumbnailFailure(row.canonicalPath)
                            }
                            var hit = host.rowHasGalleryThumbnail(row)
                            if (!hit) {
                                host.recordThumbPrefetchAttempt(row.canonicalPath)
                            } else {
                                host.clearThumbPrefetchAttempt(row.canonicalPath)
                            }
                            if (hit) cachedCount.incrementAndGet()
                            val done = completed.incrementAndGet()
                            if (publishUi && host.shouldPublishSyncActivityUi() &&
                                (done % 8 == 0 || done == rows.size)
                            ) {
                                val remaining = host.missingThumbnailCountForProgress()
                                val globalDone = (globalTotalAtStart - remaining).coerceIn(0, globalTotalAtStart)
                                host.setSyncProgress(
                                    SyncProgressPhase.PREFETCH_THUMBNAILS,
                                    progressHeadline,
                                    "$globalDone/$globalTotalAtStart · faltam ~$remaining · " +
                                        "${cachedCount.get()} JPEG nesta leva · " +
                                        "leva ${chunkIndex + 1}/$chunkCount ($done/${rows.size}) · " +
                                        "até $slots em paralelo. " +
                                        host.thumbnailPrefetchProgressDetailSuffix(
                                            localSourcesOnly,
                                            cloudSourcesOnly,
                                        ),
                                    alsoUpdateLastMessage = false,
                                )
                            }
                            if (hit) 1 else 0
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            0
                        } finally {
                            host.removeActiveSyncItem(row.canonicalPath)
                            host.removeFromRunQueue(row.canonicalPath)
                        }
                    }
                }
            }.awaitAll().sum()
        }
    }

    private companion object {
        const val THUMB_PREFETCH_RUN_CHUNK = 16
        const val PARALLEL_THUMB_PREFETCH = 5
        const val PARALLEL_THUMBNAILS = 5
        const val CLOUD_VIDEO_THUMB_FETCH_TIMEOUT_MS = 90_000L
        const val THUMB_PREFETCH_LOCAL_VIDEO_TIMEOUT_MS = 75_000L
        const val THUMB_PREFETCH_VIDEO_TIMEOUT_MS = 120_000L
        const val THUMB_PREFETCH_ITEM_TIMEOUT_MS = 30_000L
    }
}
