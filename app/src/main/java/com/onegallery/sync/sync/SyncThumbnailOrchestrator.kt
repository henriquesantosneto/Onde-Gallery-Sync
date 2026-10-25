package com.onegallery.sync.sync

/** @deprecated host alias — use [SyncGalleryThumbnailEngineHost]. */
internal typealias SyncThumbnailOrchestratorHost = SyncGalleryThumbnailEngineHost

internal class SyncThumbnailOrchestrator(
    private val engine: SyncGalleryThumbnailEngine,
    private val host: SyncGalleryThumbnailEngineHost,
) {
    suspend fun runPostSyncThumbnailWork() {
        engine.runPostSyncGalleryThumbnails()
        host.notifyGalleryDisplayRefresh()
    }
}
