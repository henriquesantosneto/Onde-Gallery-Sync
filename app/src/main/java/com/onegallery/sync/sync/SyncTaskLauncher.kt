package com.onegallery.sync.sync

import com.onegallery.sync.OneGalleryApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Dispara sync/index fora do ciclo de vida da Compose (continua com app minimizado). */
class SyncTaskLauncher(
    private val scope: CoroutineScope,
    private val coordinator: SyncCoordinator,
) {
    fun runBidirectionalSync() {
        scope.launch { coordinator.runBidirectionalSync() }
    }

    fun indexLocalLibrary() {
        scope.launch { coordinator.indexLocalLibrary() }
    }

    companion object {
        fun from(app: OneGalleryApplication): SyncTaskLauncher =
            SyncTaskLauncher(app.applicationScope, app.syncCoordinator)
    }
}
